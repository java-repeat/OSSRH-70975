package demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class BlockingDemoTest {

    @Autowired
    WebTestClient client;

    private String get(String path, int expectedStatus) {
        String body = client.mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .get().uri(path).exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(String.class).returnResult().getResponseBody();
        System.out.printf("%n>>> GET %-17s -> %d  %s%n", path, expectedStatus, body);
        return body;
    }

    @Test
    void directBlock_throwsOnEventLoop() {
        assertThat(get("/direct-block", 500))
                .contains("IllegalStateException")
                .contains("block()/blockFirst()/blockLast() are blocking");
    }

    @Test
    void publishOnThenBlock_stillThrows() {
        assertThat(get("/publishon-block", 500)).contains("IllegalStateException");
    }

    @Test
    void toFutureGet_noErrorButDeadlocks() {
        long start = System.nanoTime();
        String body = get("/tofuture-get", 500);
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println(">>> /tofuture-get took " + ms + " ms (downstream only takes ~50 ms)");
        assertThat(body).contains("TimeoutException");
        assertThat(ms).isGreaterThanOrEqualTo(3000);
    }

    @Test
    void wrappedInFromCallable_works() {
        assertThat(get("/wrapped", 200))
                .startsWith("hello")
                .contains("caller=reactor-http")
                .contains("blockedOn=boundedElastic");
    }

    @Test
    void fullyReactive_works() {
        assertThat(get("/reactive", 200))
                .startsWith("hello")
                .contains("caller=reactor-http");
    }
}
