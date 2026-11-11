package demo.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * What calling JWTProcessor.process() on the event loop does over time.
 * The order matters because the Nimbus JWK cache and rate limiter are shared state;
 * a fresh context guarantees both start empty.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JwtDemoTest {

    // Spring Boot Test names Netty threads webflux-http-*, the real app reactor-http-*.
    private static final String EVENT_LOOP = "(reactor|webflux)-http-epoll-1";

    @Autowired
    WebTestClient client;

    private String get(String uri, int expectedStatus) {
        String body = client.mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .get().uri(uri).exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(String.class).returnResult().getResponseBody();
        System.out.printf("%n>>> GET %-40s -> %d  %s%n", uri.replaceAll("token=[^&]+", "token=..."), expectedStatus, body);
        return body;
    }

    private String token() {
        return client.get().uri("/jwt/token?sub=alice").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    @Test
    @Order(1)
    void coldCache_onEventLoop_failsBecauseRetrieverBlocks() {
        assertThat(get("/jwt/verify-on-event-loop?token=" + token(), 500))
                .contains("IllegalStateException")
                .contains("block()/blockFirst()/blockLast() are blocking")
                .containsPattern("thread=" + EVENT_LOOP)
                // Not served *yet*: the request is queued on the event loop we are occupying.
                .contains("jwksFetches=0");
    }

    @Test
    @Order(2)
    void offloaded_verifies_butTheFailedAttemptStillHitTheJwksEndpoint() {
        // Mono.block() subscribes before it checks the thread, so the failed attempt in
        // test 1 still sent its request (fetch #1, response thrown away). This is fetch #2.
        assertThat(get("/jwt/verify?token=" + token(), 200))
                .startsWith("valid sub=alice")
                .containsPattern("caller=" + EVENT_LOOP)
                .contains("processedOn=boundedElastic")
                .contains("jwksFetches=2");
    }

    @Test
    @Order(3)
    void warmCache_onEventLoop_succeedsWhichHidesTheBug() {
        assertThat(get("/jwt/verify-on-event-loop?token=" + token(), 200))
                .startsWith("valid sub=alice")
                .containsPattern("processedOn=" + EVENT_LOOP)
                .contains("jwksFetches=2"); // cache hit: no I/O, so no block(), so no error
    }

    @Test
    @Order(4)
    void unknownKey_hitsRateLimitBecauseTheFailedAttemptUsedItsBudget() throws Exception {
        // An unknown kid makes Nimbus refetch the JWK set, but its rate limiter refuses:
        // the failed attempt and the real fetch already used this period's budget.
        // Compare JwtHappyPathTest, where the same token gets a refetch and a 401.
        assertThat(get("/jwt/verify?token=" + JwtTokens.signedWithUnknownKey(), 500))
                .contains("RateLimitReachedException")
                .contains("jwksFetches=2");
    }
}
