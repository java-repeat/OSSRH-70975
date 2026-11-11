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

/** Only the correct path (/jwt/verify, offloaded to boundedElastic), from a cold cache. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JwtHappyPathTest {

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
    void validToken_fetchesJwksOnceAndVerifies() {
        assertThat(get("/jwt/verify?token=" + token(), 200))
                .startsWith("valid sub=alice")
                .containsPattern("caller=" + EVENT_LOOP)
                .contains("processedOn=boundedElastic")
                .contains("jwksFetches=1");
    }

    @Test
    @Order(2)
    void secondValidToken_isServedFromCache() {
        assertThat(get("/jwt/verify?token=" + token(), 200))
                .startsWith("valid sub=alice")
                .contains("jwksFetches=1");
    }

    @Test
    @Order(3)
    void tamperedSignature_isRejectedWith401_withoutRefetch() {
        assertThat(get("/jwt/verify?token=" + JwtTokens.tampered(token()), 401))
                .contains("BadJWSException: Signed JWT rejected: Invalid signature")
                .contains("thread=boundedElastic")
                .contains("jwksFetches=1"); // kid is known, so the cached key is used
    }

    @Test
    @Order(4)
    void unknownKey_triggersOneRefetchThenIsRejectedWith401() throws Exception {
        assertThat(get("/jwt/verify?token=" + JwtTokens.signedWithUnknownKey(), 401))
                .contains("BadJOSEException")
                .contains("no matching key(s) found")
                .contains("thread=boundedElastic")
                .contains("jwksFetches=2"); // unknown kid -> Nimbus refetches in case of key rotation
    }
}
