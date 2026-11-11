package demo.jwt;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * This app plays both roles:
 * <ul>
 *   <li>issuer: serves a JWK set at /.well-known/jwks.json and mints tokens at /jwt/token</li>
 *   <li>resource server: verifies tokens with a Nimbus {@link DefaultJWTProcessor} whose
 *       JWK set is fetched by {@link WebClientResourceRetriever}</li>
 * </ul>
 */
@RestController
public class JwtDemoController {

    private static final String ISSUER = "https://issuer.demo";

    private final WebClient.Builder webClientBuilder;
    private final RSAKey signingKey;
    private final AtomicInteger jwksFetches = new AtomicInteger();
    private volatile ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    public JwtDemoController(WebClient.Builder webClientBuilder) throws JOSEException {
        this.webClientBuilder = webClientBuilder;
        this.signingKey = new RSAKeyGenerator(2048).keyID("demo-key-1").generate();
    }

    /** The JWKS URL needs the server port, which is known only once Netty has started. */
    @EventListener
    void onServerStarted(WebServerInitializedEvent event) throws MalformedURLException {
        URI jwksUri = URI.create("http://localhost:" + event.getWebServer().getPort() + "/.well-known/jwks.json");

        WebClientResourceRetriever retriever =
                new WebClientResourceRetriever(webClientBuilder, Duration.ofSeconds(3), 64 * 1024);

        JWKSource<SecurityContext> jwkSource = JWKSourceBuilder
                .create(jwksUri.toURL(), retriever)
                .cache(Duration.ofMinutes(5).toMillis(), Duration.ofSeconds(5).toMillis())
                .build();

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().issuer(ISSUER).build(),   // exact-match claims
                Set.of("sub", "iat", "exp")));                       // required claims
        this.jwtProcessor = processor;
    }

    // ---- issuer side ----------------------------------------------------------------

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    String jwks() {
        System.out.println(">>> [jwks] served fetch #" + jwksFetches.incrementAndGet() + " on " + thread());
        return new JWKSet(signingKey).toString(true); // true = public keys only
    }

    @GetMapping(value = "/jwt/token", produces = MediaType.TEXT_PLAIN_VALUE)
    String token(@RequestParam(defaultValue = "alice") String sub) throws JOSEException {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .subject(sub)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(300)))
                        .build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    // ---- resource-server side -------------------------------------------------------

    /** Correct: process() (which may block on the JWKS fetch) runs on boundedElastic. */
    @GetMapping("/jwt/verify")
    Mono<String> verify(@RequestParam String token) {
        String caller = thread();
        return Mono.fromCallable(() -> describe(jwtProcessor.process(token, null), caller))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Wrong: process() runs on the Netty event loop. It fails while the JWKS cache is cold,
     * but succeeds once another request has filled the cache, because a cache hit does no I/O.
     */
    @GetMapping("/jwt/verify-on-event-loop")
    String verifyOnEventLoop(@RequestParam String token) throws Exception {
        return describe(jwtProcessor.process(token, null), thread());
    }

    private String describe(JWTClaimsSet claims, String caller) {
        return "valid sub=" + claims.getSubject()
                + " | caller=" + caller
                + " | processedOn=" + thread()
                + " | jwksFetches=" + jwksFetches.get();
    }

    private static String thread() {
        return Thread.currentThread().getName();
    }

    @ExceptionHandler(BadJOSEException.class)
    ResponseEntity<String> onInvalidToken(BadJOSEException e) {
        return error(HttpStatus.UNAUTHORIZED, e);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> onError(Exception e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    private ResponseEntity<String> error(HttpStatus status, Exception e) {
        return ResponseEntity.status(status).body(e.getClass().getSimpleName() + ": " + e.getMessage()
                + " | thread=" + thread() + " | jwksFetches=" + jwksFetches.get());
    }
}
