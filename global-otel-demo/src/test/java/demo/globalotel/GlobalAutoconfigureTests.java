package demo.globalotel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q: Can an early {@code GlobalOpenTelemetry.get()} do worse than install a no-op? Yes: with
 * {@code opentelemetry-sdk-extension-autoconfigure} on the classpath and
 * {@code otel.java.global-autoconfigure.enabled=true}, it builds a second SDK, configured
 * from {@code otel.*} system properties and {@code OTEL_*} environment variables, not from
 * Spring. By default that SDK exports OTLP to localhost.
 */
@ExtendWith(OutputCaptureExtension.class)
class GlobalAutoconfigureTests {

	@BeforeEach
	void enableGlobalAutoconfigure() {
		System.setProperty("otel.java.global-autoconfigure.enabled", "true");
		// Keep the hidden SDK off the network for the test
		System.setProperty("otel.traces.exporter", "none");
		System.setProperty("otel.metrics.exporter", "none");
		System.setProperty("otel.logs.exporter", "none");
	}

	@AfterEach
	void reset() {
		GlobalOpenTelemetry.resetForTest();
	}

	@Test
	void earlyGetBuildsASecondSdkThatBootCannotReplace(CapturedOutput output) {
		GlobalOpenTelemetry.get();

		// Not a no-op: a configured SDK (a no-op has no propagator fields)
		assertThat(GlobalOpenTelemetry.getPropagators().getTextMapPropagator().fields()).contains("traceparent",
				"baggage");

		try (ConfigurableApplicationContext context = Harness.start("eager", false)) {
			assertThat(output).contains("GlobalOpenTelemetry was already set before Spring Boot's OpenTelemetry bean");
		}
	}

}
