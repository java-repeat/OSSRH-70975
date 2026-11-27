package demo.globalotel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q: With eager registration, what happens when a second context starts in the same JVM
 * (test suites with several cached contexts, DevTools restarts, a parent and child context)?
 */
class EagerRegistrationRestartTests {

	@Test
	void secondContextGetsTheGlobalButLibrariesKeepTheFirstContextsClosedSdk() {
		try (ConfigurableApplicationContext first = Harness.start("eager", false); S3Client s3 = Harness.s3Client()) {
			Harness.listBucketsInsideBootSpan(first, s3);
			assertThat(Harness.exportedNames(first)).contains(Harness.AWS_SPAN);
		}

		try (ConfigurableApplicationContext second = Harness.start("eager", false);
				S3Client s3 = Harness.s3Client()) {
			// The global now points at the second context's SDK...
			assertThat(Harness.globalRecords()).isTrue();

			Harness.listBucketsInsideBootSpan(second, s3);

			// ...but the library resolved the global once per JVM, so its spans still go to the
			// first context's SDK, which is shut down: they are lost
			assertThat(Harness.exportedNames(second)).containsExactly("request");

			// While it is running, a third context cannot register at all
			try (ConfigurableApplicationContext third = Harness.start("eager", false)) {
				assertThat(GlobalOpenTelemetry.isSet()).isTrue();
			}
		}
	}

}
