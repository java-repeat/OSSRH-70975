package demo.globalotel;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q: How do you cover code that runs before Spring? Install the delegating global as the
 * first line of {@code main}.
 */
class InstallFirstInMainTests {

	@Test
	void installingFirstCoversClientsBuiltBeforeSpringStarts() {
		// What main() would do first
		assertThat(GlobalOpenTelemetryBridge.install()).isNotNull();

		try (S3Client early = Harness.s3Client()) {
			// Untraced (nothing is bound yet), but it no longer locks the global
			early.listBuckets();

			try (ConfigurableApplicationContext context = Harness.start("delegating", false)) {
				Harness.listBucketsInsideBootSpan(context, early);
				assertThat(Harness.exportedNames(context)).contains(Harness.AWS_SPAN);
			}
		}
	}

}
