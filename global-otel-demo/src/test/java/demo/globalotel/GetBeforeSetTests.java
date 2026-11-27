package demo.globalotel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Q: What exactly happens when code reads the global before anyone sets it?
 * (opentelemetry-api 1.67.0, no Spring)
 */
class GetBeforeSetTests {

	// An SDK tracer with no span processor is disabled and records nothing, so add one
	private final OpenTelemetry sdk = OpenTelemetrySdk.builder()
		.setTracerProvider(SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
			.build())
		.build();

	@AfterEach
	void reset() {
		GlobalOpenTelemetry.resetForTest();
	}

	@Test
	void getBeforeSetInstallsAPermanentNoopAndSetThenThrows() {
		assertThat(GlobalOpenTelemetry.isSet()).isFalse();

		GlobalOpenTelemetry.get();

		assertThat(GlobalOpenTelemetry.isSet()).isTrue();
		assertThat(Harness.globalRecords()).isFalse();
		assertThatIllegalStateException().isThrownBy(() -> GlobalOpenTelemetry.set(this.sdk))
			.withMessageContaining("GlobalOpenTelemetry.set has already been called")
			// The cause is a stack trace of whoever set it first: useful when debugging
			.withCauseInstanceOf(Throwable.class);
	}

	@Test
	void getOrNoopAndIsSetDoNotLockTheGlobal() {
		// Added in 1.57.0 for exactly this problem; libraries must opt in to using them
		GlobalOpenTelemetry.getOrNoop();
		assertThat(GlobalOpenTelemetry.isSet()).isFalse();

		GlobalOpenTelemetry.set(this.sdk);
		assertThat(Harness.globalRecords()).isTrue();
	}

	@Test
	void setTwiceThrowsAndTheOnlyWayToUnsetIsResetForTest() {
		GlobalOpenTelemetry.set(this.sdk);
		assertThatIllegalStateException().isThrownBy(() -> GlobalOpenTelemetry.set(this.sdk));

		GlobalOpenTelemetry.resetForTest();
		GlobalOpenTelemetry.set(this.sdk);
		assertThat(GlobalOpenTelemetry.isSet()).isTrue();
	}

	@Test
	void callersThatCachedTheOldGlobalKeepIt() {
		GlobalOpenTelemetry.set(this.sdk);
		OpenTelemetry cached = GlobalOpenTelemetry.get();

		GlobalOpenTelemetry.resetForTest();
		GlobalOpenTelemetry.set(OpenTelemetry.noop());

		// A library that kept the instance still uses the first SDK
		assertThat(cached.getTracer("x").spanBuilder("s").startSpan().getSpanContext().isValid()).isTrue();
		assertThat(Harness.globalRecords()).isFalse();
	}

}
