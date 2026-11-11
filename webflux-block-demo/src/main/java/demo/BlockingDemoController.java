package demo;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class BlockingDemoController {

    private final WebClient.Builder builder;
    private final Environment env;

    public BlockingDemoController(WebClient.Builder builder, Environment env) {
        this.builder = builder;
        this.env = env;
    }

    @GetMapping("/downstream")
    Mono<String> downstream() {
        return Mono.just("hello").delayElement(Duration.ofMillis(50));
    }

    private Mono<String> callDownstream() {
        return builder.clone().baseUrl("http://localhost:" + env.getProperty("local.server.port")).build()
                .get().uri("/downstream")
                .retrieve().bodyToMono(String.class);
    }

    private static String thread() {
        return Thread.currentThread().getName();
    }

    // 1. block() directly -> IllegalStateException
    @GetMapping("/direct-block")
    String directBlock() {
        String caller = thread();
        String body = callDownstream().block(Duration.ofSeconds(3));
        return body + " | caller=" + caller;
    }

    // 2. publishOn then block() -> still IllegalStateException
    @GetMapping("/publishon-block")
    String publishOnBlock() {
        String caller = thread();
        String body = callDownstream()
                .publishOn(Schedulers.boundedElastic())
                .block(Duration.ofSeconds(3));
        return body + " | caller=" + caller;
    }

    // 3. toFuture().get() -> no exception, but deadlocks the event loop
    @GetMapping("/tofuture-get")
    String toFutureGet() throws Exception {
        String caller = thread();
        String body = callDownstream().toFuture().get(3, TimeUnit.SECONDS);
        return body + " | caller=" + caller;
    }

    // 4. fromCallable + subscribeOn(boundedElastic) -> works
    @GetMapping("/wrapped")
    Mono<String> wrapped() {
        String caller = thread();
        return Mono.fromCallable(() -> {
                    String blockedOn = thread();
                    String body = callDownstream().block(Duration.ofSeconds(3));
                    return body + " | caller=" + caller + " | blockedOn=" + blockedOn;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // 5. fully reactive -> works
    @GetMapping("/reactive")
    Mono<String> reactive() {
        String caller = thread();
        return callDownstream().map(body -> body + " | caller=" + caller);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> onError(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(e.getClass().getSimpleName() + ": " + e.getMessage() + " | thread=" + thread());
    }
}
