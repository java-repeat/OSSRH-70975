package demo.jwt;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;

/**
 * A Nimbus {@link ResourceRetriever} that fetches with Spring's {@link WebClient}.
 *
 * <p>{@code ResourceRetriever} is a synchronous interface, so this class has to call
 * {@code block()}. That is fine on a worker thread, but on a Netty event-loop thread
 * Reactor throws {@link IllegalStateException}. Never call
 * {@code JWTProcessor.process(...)} from a reactive handler without first moving to
 * another thread, e.g. {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}.
 */
public class WebClientResourceRetriever implements ResourceRetriever {

    private final WebClient webClient;
    private final Duration timeout;

    /**
     * @param builder      the Spring-configured builder (cloned, so the caller's builder is not modified)
     * @param timeout      overall timeout for one fetch (connect + response + body)
     * @param maxBodyBytes reject JWK sets larger than this
     */
    public WebClientResourceRetriever(WebClient.Builder builder, Duration timeout, int maxBodyBytes) {
        this.webClient = builder.clone()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBodyBytes))
                .build();
        this.timeout = timeout;
    }

    @Override
    public Resource retrieveResource(URL url) throws IOException {
        System.out.println(">>> [retriever] fetching " + url.getPath() + " on " + Thread.currentThread().getName());
        ResponseEntity<String> response;
        try {
            response = webClient.get()
                    .uri(url.toURI())
                    .accept(MediaType.APPLICATION_JSON, MediaType.valueOf("application/jwk-set+json"))
                    .retrieve()            // 4xx/5xx -> WebClientResponseException
                    .toEntity(String.class)
                    .block(timeout);       // throws IllegalStateException on an event-loop thread
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL: " + url, e);
        } catch (WebClientException e) {
            // Nimbus expects IOException for fetch failures (it becomes a KeySourceException upstream).
            throw new IOException("Couldn't retrieve " + url + ": " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Timeout")) {
                throw new IOException("Timed out after " + timeout + " retrieving " + url, e);
            }
            throw e; // blocking on an event loop is a programming error, so let it propagate
        }

        if (response == null || response.getBody() == null) {
            throw new IOException("Empty response from " + url);
        }
        MediaType contentType = response.getHeaders().getContentType();
        return new Resource(response.getBody(), contentType != null ? contentType.toString() : null);
    }
}
