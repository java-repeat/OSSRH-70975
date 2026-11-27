package demo.globalotel;

import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q: What if the OpenTelemetry Java agent (or anything else) already owns the global?
 * Simulated here by setting the global to a separate SDK before Boot starts.
 */
class AgentAlreadySetTests {

	private static final InMemorySpanExporter agentSpans = InMemorySpanExporter.create();

	private static OpenTelemetrySdk agent;

	@BeforeAll
	static void installFakeAgent() {
		agent = OpenTelemetrySdk.builder()
			.setTracerProvider(
					SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(agentSpans)).build())
			.build();
		GlobalOpenTelemetry.set(agent);
	}

	@AfterAll
	static void removeFakeAgent() {
		agent.close();
		GlobalOpenTelemetry.resetForTest();
	}

	@ParameterizedTest
	@ValueSource(strings = { "eager", "delegating" })
	void bootStartsLeavesTheAgentsGlobalAloneAndTheTelemetryIsSplit(String mode) {
		agentSpans.reset();
		try (ConfigurableApplicationContext context = Harness.start(mode, false); S3Client s3 = Harness.s3Client()) {
			Harness.listBucketsInsideBootSpan(context, s3);

			// Boot's span goes to Boot's SDK, the library's span to the agent's SDK: two
			// pipelines, and the library span is still parented by Boot's span (shared Context)
			assertThat(Harness.exportedNames(context)).containsExactly("request");
			agent.getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
			assertThat(agentSpans.getFinishedSpanItems()).extracting(SpanData::getName)
				.containsExactly(Harness.AWS_SPAN);
			assertThat(agentSpans.getFinishedSpanItems().get(0).getParentSpanId())
				.isEqualTo(Harness.exportedSpan(context, "request").getSpanId());
		}
	}

}
