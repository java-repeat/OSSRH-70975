package demo.globalotel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Q: Out of the box, does Spring Boot 4.2 set GlobalOpenTelemetry, and what happens to a
 * library that relies on it?
 */
class BootLeavesGlobalUnsetTests {

	@Test
	void bootNeverSetsTheGlobalSoGlobalLibrariesRecordNothingAndLockItToNoop() {
		try (ConfigurableApplicationContext context = Harness.start("none", false)) {
			// Boot's SDK works, but it is not the global
			assertThat(GlobalOpenTelemetry.isSet()).isFalse();

			// Building any AWS client loads the library's interceptor, which reads the global
			try (S3Client s3 = Harness.s3Client()) {
				Harness.listBucketsInsideBootSpan(context, s3);
			}

			// Boot's own span is exported; the library's AWS span is not
			assertThat(Harness.exportedNames(context)).containsExactly("request");

			// Side effect: the library called GlobalOpenTelemetry.get(), which installed a no-op
			// for the rest of the JVM, so publishing Boot's SDK now is impossible
			assertThat(GlobalOpenTelemetry.isSet()).isTrue();
			assertThat(Harness.globalRecords()).isFalse();
			assertThatIllegalStateException()
				.isThrownBy(() -> GlobalOpenTelemetry.set(context.getBean(OpenTelemetry.class)));
		}
	}

}
