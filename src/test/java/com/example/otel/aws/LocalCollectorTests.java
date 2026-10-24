package com.example.otel.aws;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exports to a real OpenTelemetry Collector and checks what it received, as written by the
 * collector's file exporter. Run with {@code local-collector/verify.sh}, which builds and
 * starts the collector; skipped otherwise.
 */
@EnabledIfSystemProperty(named = "otel.collector.output", matches = ".+")
class LocalCollectorTests {

	private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

	private static final String CALLER_SPAN_ID = "b7ad6b7169203331";

	private final Path output = Path.of(System.getProperty("otel.collector.output"));

	private final String endpoint = System.getProperty("otel.collector.endpoint", "http://127.0.0.1:4318");

	@Test
	void collectorReceivesTheTraceAndMetricsWithAwsResource() throws Exception {
		List<SpanData> created;
		try (FakeS3 fakeS3 = FakeS3.start()) {
			ConfigurableApplicationContext context = new SpringApplicationBuilder(TracingEndToEndTests.App.class)
				.web(WebApplicationType.SERVLET)
				.properties("server.port=0", "spring.application.name=orders",
						"management.tracing.sampling.probability=1.0",
						"management.opentelemetry.otlp.endpoint=" + this.endpoint,
						"management.otlp.metrics.export.step=1s", "fake.s3.endpoint=" + fakeS3.endpoint())
				.run();
			int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/buckets"))
					.header("traceparent", "00-" + TRACE_ID + "-" + CALLER_SPAN_ID + "-01")
					.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			InMemorySpanExporter inMemory = context.getBean(InMemorySpanExporter.class);
			await().atMost(Duration.ofSeconds(10)).until(() -> inMemory.getFinishedSpanItems().size() >= 2);
			created = List.copyOf(inMemory.getFinishedSpanItems());
			// Closing flushes Boot's span processor and publishes the last metrics step
			context.close();
		}

		// Traces: the collector got exactly the spans the SDK created, with the same lineage
		List<JsonNode> spans = await().atMost(Duration.ofSeconds(15))
			.until(() -> records("traces.jsonl", "resourceSpans", "scopeSpans", "spans", TRACE_ID),
					(found) -> found.size() >= created.size());
		Map<String, JsonNode> exported = spans.stream()
			.collect(Collectors.toMap((span) -> span.path("spanId").asString(), (span) -> span));
		for (SpanData span : created) {
			JsonNode match = exported.get(span.getSpanId());
			assertThat(match).as("exported span %s", span.getName()).isNotNull();
			assertThat(match.path("name").asString()).isEqualTo(span.getName());
			assertThat(match.path("parentSpanId").asString()).isEqualTo(span.getParentSpanId());
		}
		JsonNode server = only(spans, (span) -> span.path("kind").asInt() == 2);
		JsonNode s3 = only(spans, (span) -> span.path("name").asString().equals("S3.ListBuckets"));
		assertThat(server.path("parentSpanId").asString()).isEqualTo(CALLER_SPAN_ID);
		assertThat(s3.path("kind").asInt()).isEqualTo(3);
		assertThat(s3.path("parentSpanId").asString()).isEqualTo(server.path("spanId").asString());
		assertThat(attributes(s3)).containsEntry("rpc.system", "aws-api")
			.containsEntry("rpc.service", "S3")
			.containsEntry("rpc.method", "ListBuckets")
			.containsEntry("http.response.status_code", "200");
		assertThat(attributes(resourceOf("traces.jsonl", "resourceSpans", TRACE_ID)))
			.containsEntry("service.name", "orders")
			.containsEntry("cloud.provider", "aws")
			.containsKey("telemetry.sdk.version");

		// Metrics: Boot's Micrometer OTLP export carries the same AWS resource attributes
		await().atMost(Duration.ofSeconds(15)).until(() -> contains("metrics.jsonl", "http.server.requests"));
		assertThat(attributes(resourceOf("metrics.jsonl", "resourceMetrics", "http.server.requests")))
			.containsEntry("service.name", "orders")
			.containsEntry("cloud.provider", "aws");
	}

	private List<JsonNode> lines(String file) throws IOException {
		Path path = this.output.resolve(file);
		if (!Files.exists(path)) {
			return List.of();
		}
		List<JsonNode> lines = new ArrayList<>();
		for (String line : Files.readAllLines(path)) {
			if (!line.isBlank()) {
				lines.add(JsonMapper.shared().readTree(line));
			}
		}
		return lines;
	}

	private boolean contains(String file, String text) throws IOException {
		Path path = this.output.resolve(file);
		return Files.exists(path) && Files.readString(path).contains(text);
	}

	// Leaf records (spans) from every export line that mentions the marker
	private List<JsonNode> records(String file, String resources, String scopes, String leaves, String marker)
			throws IOException {
		List<JsonNode> found = new ArrayList<>();
		for (JsonNode line : lines(file)) {
			for (JsonNode resource : line.path(resources)) {
				for (JsonNode scope : resource.path(scopes)) {
					for (JsonNode leaf : scope.path(leaves)) {
						if (leaf.toString().contains(marker)) {
							found.add(leaf);
						}
					}
				}
			}
		}
		return found;
	}

	private JsonNode resourceOf(String file, String resources, String marker) throws IOException {
		for (JsonNode line : lines(file)) {
			for (JsonNode resource : line.path(resources)) {
				if (resource.toString().contains(marker)) {
					return resource.path("resource");
				}
			}
		}
		throw new AssertionError("No " + resources + " containing " + marker + " in " + file);
	}

	private static JsonNode only(List<JsonNode> nodes, java.util.function.Predicate<JsonNode> filter) {
		List<JsonNode> matches = nodes.stream().filter(filter).toList();
		assertThat(matches).hasSize(1);
		return matches.get(0);
	}

	// OTLP JSON attributes as key -> value text (ints are JSON strings in OTLP/JSON)
	private static Map<String, String> attributes(JsonNode owner) {
		Map<String, String> attributes = new java.util.LinkedHashMap<>();
		for (JsonNode attribute : owner.path("attributes")) {
			JsonNode value = attribute.path("value");
			String text = value.has("stringValue") ? value.path("stringValue").asString()
					: value.has("intValue") ? value.path("intValue").asString() : value.toString();
			attributes.put(attribute.path("key").asString(), text);
		}
		return attributes;
	}

}
