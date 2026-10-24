package com.example.otel.aws;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs a real Boot web app with the starter: an incoming HTTP request calls S3 (a fake
 * endpoint on localhost) and the resulting trace is checked in memory, on the wire to S3,
 * and in what Boot's OTLP exporter sends to a fake collector.
 */
class TracingEndToEndTests {

	private static final AttributeKey<String> CLOUD_PROVIDER = AttributeKey.stringKey("cloud.provider");

	private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

	private static FakeS3 fakeS3;

	private static HttpServer fakeCollector;

	private static final List<String> collectorPaths = new CopyOnWriteArrayList<>();

	private static final List<byte[]> collectorBodies = new CopyOnWriteArrayList<>();

	private ConfigurableApplicationContext context;

	private InMemorySpanExporter spans;

	private int port;

	@BeforeAll
	static void startFakes() throws IOException {
		fakeS3 = FakeS3.start();
		fakeCollector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		fakeCollector.createContext("/", (exchange) -> {
			collectorPaths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
			collectorBodies.add(exchange.getRequestBody().readAllBytes());
			exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
			respond(exchange, new byte[0]);
		});
		fakeCollector.start();
	}

	@AfterAll
	static void stopFakes() {
		fakeS3.close();
		fakeCollector.stop(0);
	}

	@BeforeEach
	void startApp() {
		fakeS3.requestHeaders().clear();
		collectorPaths.clear();
		collectorBodies.clear();
		this.context = new SpringApplicationBuilder(App.class).web(WebApplicationType.SERVLET)
			.properties("server.port=0", "spring.application.name=orders",
					"management.tracing.sampling.probability=1.0",
					"management.opentelemetry.otlp.endpoint=http://127.0.0.1:" + fakeCollector.getAddress().getPort(),
					"aws.otel.xray-propagator.enabled=true",
					"fake.s3.endpoint=" + fakeS3.endpoint())
			.run();
		this.spans = this.context.getBean(InMemorySpanExporter.class);
		this.port = this.context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
	}

	@AfterEach
	void stopApp() {
		this.context.close();
	}

	@Test
	void awsSdkSpanIsChildOfIncomingRequestAndIsExportedOverOtlp() throws Exception {
		String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
		get("/buckets", Map.of("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"));

		SpanData s3 = awaitSpan("S3.ListBuckets");
		SpanData server = awaitServerSpan();

		// Continues the caller's W3C trace, and the AWS call is a child of the request span
		assertThat(server.getTraceId()).isEqualTo(traceId);
		assertThat(server.getParentSpanId()).isEqualTo("00f067aa0ba902b7");
		assertThat(s3.getKind()).isEqualTo(SpanKind.CLIENT);
		assertThat(s3.getTraceId()).isEqualTo(traceId);
		assertThat(s3.getParentSpanId()).isEqualTo(server.getSpanId());
		assertThat(s3.getAttributes().get(AttributeKey.stringKey("rpc.system"))).isEqualTo("aws-api");

		// Boot's Resource, with the AWS attributes merged in and Boot's service.name kept
		Resource resource = s3.getResource();
		assertThat(resource.getAttribute(CLOUD_PROVIDER)).isEqualTo("aws");
		assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("orders");

		// The instrumentation puts X-Ray context on the S3 request, parented by the S3 span
		assertThat(fakeS3.requestHeaders()).hasSize(1);
		assertThat(fakeS3.header(0, "X-amzn-trace-id"))
			.isEqualTo("Root=1-4bf92f35-77b34da6a3ce929d0e0e4736;Parent=" + s3.getSpanId() + ";Sampled=1");

		// Boot's OTLP exporter ships the same trace to the collector
		this.context.getBean(SdkTracerProvider.class).forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
		await().atMost(Duration.ofSeconds(10)).until(() -> collectorPaths.contains("POST /v1/traces"));
		String exported = new String(collectorBodies.get(collectorPaths.indexOf("POST /v1/traces")),
				StandardCharsets.ISO_8859_1);
		assertThat(exported).contains("S3.ListBuckets", "orders", "cloud.provider", "aws");

		// Nothing touched the global
		assertThat(GlobalOpenTelemetry.isSet()).isFalse();
	}

	@Test
	void xrayHeaderWinsOverTraceparentOnExtract() throws Exception {
		get("/buckets",
				Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "X-Amzn-Trace-Id",
						"Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8;Sampled=1"));

		SpanData server = awaitServerSpan();
		assertThat(server.getTraceId()).isEqualTo("5759e988bd862e3fe1be46a994272793");
		assertThat(server.getParentSpanId()).isEqualTo("53995c3f42cd8ad8");
		// Boot's composite propagator injects both formats on outgoing calls
		assertThat(this.context.getBean(ContextPropagators.class).getTextMapPropagator().fields())
			.contains("traceparent", "X-Amzn-Trace-Id");
	}

	private void get(String path, Map<String, String> headers) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + path));
		headers.forEach(request::header);
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(request.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("1");
	}

	private SpanData awaitSpan(String name) {
		return await().atMost(Duration.ofSeconds(10))
			.until(() -> flushed().stream().filter((span) -> span.getName().equals(name)).findFirst(),
					java.util.Optional::isPresent)
			.get();
	}

	private SpanData awaitServerSpan() {
		return await().atMost(Duration.ofSeconds(10))
			.until(() -> flushed().stream().filter((span) -> span.getKind() == SpanKind.SERVER).findFirst(),
					java.util.Optional::isPresent)
			.get();
	}

	private List<SpanData> flushed() {
		this.context.getBean(SdkTracerProvider.class).forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
		return this.spans.getFinishedSpanItems();
	}

	private static void respond(HttpExchange exchange, byte[] body) throws IOException {
		exchange.sendResponseHeaders(200, (body.length == 0) ? -1 : body.length);
		if (body.length > 0) {
			exchange.getResponseBody().write(body);
		}
		exchange.close();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class App {

		@Bean
		InMemorySpanExporter inMemorySpanExporter() {
			return InMemorySpanExporter.create();
		}

		@Bean
		AwsResourceDetector fakeAwsResourceDetector() {
			return () -> Resource.builder().put(CLOUD_PROVIDER, "aws").put(SERVICE_NAME, "from-aws").build();
		}

		// A plain client: nothing in the app wires tracing into it
		@Bean(destroyMethod = "close")
		S3Client s3(org.springframework.core.env.Environment environment) {
			return S3Client.builder()
				.endpointOverride(URI.create(environment.getRequiredProperty("fake.s3.endpoint")))
				.forcePathStyle(true)
				.region(Region.US_EAST_1)
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
				.httpClient(UrlConnectionHttpClient.create())
				.build();
		}

		@Bean
		BucketsController bucketsController(S3Client s3) {
			return new BucketsController(s3);
		}

	}

	@RestController
	static class BucketsController {

		private final S3Client s3;

		BucketsController(S3Client s3) {
			this.s3 = s3;
		}

		@GetMapping("/buckets")
		String buckets() {
			return String.valueOf(this.s3.listBuckets().buckets().size());
		}

	}

}
