package demo.globalotel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * A plain Boot 4.2 app with {@code spring-boot-starter-opentelemetry}, a fake S3 on
 * localhost, and {@code opentelemetry-aws-sdk-2.2-autoconfigure} on the classpath: a real
 * library whose SPI interceptor traces every AWS SDK client through
 * {@code GlobalOpenTelemetry}, resolved once per JVM when the first client is built.
 */
final class Harness {

	static final String AWS_SPAN = "S3.ListBuckets";

	private static HttpServer fakeS3;

	private Harness() {
	}

	/** Starts Boot in a {@code demo.otel.global.mode}, optionally with an S3Client bean. */
	static ConfigurableApplicationContext start(String mode, boolean s3ClientBean) {
		return new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE)
			.properties("demo.otel.global.mode=" + mode, "demo.s3-client-bean=" + s3ClientBean,
					"fake.s3.endpoint=" + fakeS3Endpoint(), "management.tracing.sampling.probability=1.0",
					"management.otlp.tracing.export.enabled=false", "management.otlp.metrics.export.enabled=false",
					"management.otlp.logging.export.enabled=false")
			.run();
	}

	/** Builds an AWS client nothing in the app wires tracing into. */
	static S3Client s3Client() {
		return S3Client.builder()
			.endpointOverride(URI.create(fakeS3Endpoint()))
			.forcePathStyle(true)
			.region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
			.httpClient(UrlConnectionHttpClient.create())
			.build();
	}

	/** Calls S3 inside a span from Boot's own tracer, and returns that span. */
	static Span listBucketsInsideBootSpan(ConfigurableApplicationContext context, S3Client s3) {
		Span parent = context.getBean(io.opentelemetry.api.OpenTelemetry.class)
			.getTracer("test")
			.spanBuilder("request")
			.startSpan();
		try (Scope ignored = parent.makeCurrent()) {
			s3.listBuckets();
		}
		finally {
			parent.end();
		}
		return parent;
	}

	/** Everything Boot's SDK exported so far (flushes first, so no waiting). */
	static List<SpanData> exported(ConfigurableApplicationContext context) {
		context.getBean(SdkTracerProvider.class).forceFlush().join(10, TimeUnit.SECONDS);
		return new ArrayList<>(context.getBean(InMemorySpanExporter.class).getFinishedSpanItems());
	}

	static List<String> exportedNames(ConfigurableApplicationContext context) {
		return exported(context).stream().map(SpanData::getName).toList();
	}

	static SpanData exportedSpan(ConfigurableApplicationContext context, String name) {
		return exported(context).stream()
			.filter((span) -> span.getName().equals(name))
			.findFirst()
			.orElseThrow(() -> new AssertionError(name + " not exported; got " + exportedNames(context)));
	}

	/**
	 * Whether a span started from {@code GlobalOpenTelemetry} would record anything. The probe
	 * span is never ended, so it is never exported.
	 */
	static boolean globalRecords() {
		return GlobalOpenTelemetry.getTracer("probe").spanBuilder("probe").startSpan().getSpanContext().isValid();
	}

	static synchronized String fakeS3Endpoint() {
		if (fakeS3 == null) {
			try {
				fakeS3 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			byte[] body = """
					<?xml version="1.0" encoding="UTF-8"?>
					<ListAllMyBucketsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">\
					<Owner><ID>owner</ID></Owner><Buckets><Bucket><Name>b1</Name></Bucket></Buckets>\
					</ListAllMyBucketsResult>""".getBytes(StandardCharsets.UTF_8);
			fakeS3.createContext("/", (exchange) -> {
				exchange.getRequestBody().readAllBytes();
				exchange.getResponseHeaders().add("Content-Type", "application/xml");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			fakeS3.start();
		}
		return "http://127.0.0.1:" + fakeS3.getAddress().getPort();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class App {

		@Bean
		InMemorySpanExporter inMemorySpanExporter() {
			return InMemorySpanExporter.create();
		}

		/**
		 * An ordinary application bean. Nothing makes it depend on OpenTelemetry, so Spring is
		 * free to build it before Boot's OpenTelemetry bean, as it would in a real app.
		 */
		@Bean(destroyMethod = "close")
		@ConditionalOnProperty(name = "demo.s3-client-bean", havingValue = "true")
		S3Client s3() {
			return s3Client();
		}

	}

}
