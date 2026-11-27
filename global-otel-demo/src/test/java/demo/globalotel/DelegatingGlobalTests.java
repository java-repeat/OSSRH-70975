package demo.globalotel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q: Does publishing a delegating global before any bean exists fix the ordering and
 * restart problems of eager registration?
 */
class DelegatingGlobalTests {

	@Test
	void s3ClientBeanBuiltBeforeBootsOpenTelemetryBeanIsTracedAndFollowsRestarts() {
		try (ConfigurableApplicationContext first = Harness.start("delegating", true)) {
			// Same S3Client bean that locked the global to a no-op in eager mode
			Span request = Harness.listBucketsInsideBootSpan(first, first.getBean(S3Client.class));

			SpanData aws = Harness.exportedSpan(first, Harness.AWS_SPAN);
			assertThat(aws.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId());
		}

		// Between contexts the global is a no-op, not a closed SDK
		assertThat(Harness.globalRecords()).isFalse();

		try (ConfigurableApplicationContext second = Harness.start("delegating", true)) {
			Harness.listBucketsInsideBootSpan(second, second.getBean(S3Client.class));

			// The library still holds the tracer it got from the first context; it now
			// resolves to the second context's SDK
			assertThat(Harness.exportedNames(second)).contains(Harness.AWS_SPAN);
		}
	}

}
