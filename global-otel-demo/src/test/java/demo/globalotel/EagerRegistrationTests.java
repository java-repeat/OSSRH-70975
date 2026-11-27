package demo.globalotel;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q: If Boot calls {@code GlobalOpenTelemetry.set(bean)} as soon as its bean exists, do
 * libraries that use the global start reporting to Boot's SDK?
 */
class EagerRegistrationTests {

	@Test
	void libraryFirstUsedAfterStartupIsTracedThroughBootsSdk() {
		try (ConfigurableApplicationContext context = Harness.start("eager", false);
				S3Client s3 = Harness.s3Client()) {
			assertThat(GlobalOpenTelemetry.isSet()).isTrue();
			assertThat(Harness.globalRecords()).isTrue();

			Span request = Harness.listBucketsInsideBootSpan(context, s3);

			// The library's span goes through Boot's SDK: Boot's exporter, sampler and resource,
			// and it is a child of the span Boot's tracer made current
			SpanData aws = Harness.exportedSpan(context, Harness.AWS_SPAN);
			assertThat(aws.getTraceId()).isEqualTo(request.getSpanContext().getTraceId());
			assertThat(aws.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId());
			assertThat(aws.getResource()).isEqualTo(Harness.exportedSpan(context, "request").getResource());

			// Libraries that only read the global's propagators get Boot's (W3C by default)
			assertThat(GlobalOpenTelemetry.getPropagators().getTextMapPropagator().fields()).contains("traceparent");
		}
		// Closing the context unsets the global again
		assertThat(GlobalOpenTelemetry.isSet()).isFalse();
	}

	@Test
	void metricsRecordedThroughTheGlobalGoNowhere() {
		try (ConfigurableApplicationContext context = Harness.start("eager", false)) {
			// Boot does metrics with Micrometer and never creates an OpenTelemetry SdkMeterProvider,
			// so OpenTelemetry-API meters (what libraries using the global record) have no reader
			assertThat(context.getBeanProvider(SdkMeterProvider.class).getIfAvailable()).isNull();

			GlobalOpenTelemetry.getMeter("library").counterBuilder("library.calls").build().add(1);

			assertThat(context.getBeansOfType(MeterRegistry.class).values())
				.allSatisfy((registry) -> assertThat(registry.find("library.calls").meter()).isNull());
		}
	}

}
