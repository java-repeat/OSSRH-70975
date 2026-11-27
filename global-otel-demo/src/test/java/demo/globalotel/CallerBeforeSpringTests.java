package demo.globalotel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q: Is there anything Spring can't fix? Yes: code that reads the global before
 * {@code SpringApplication.run} (a static initializer, work in {@code main}).
 */
@ExtendWith(OutputCaptureExtension.class)
class CallerBeforeSpringTests {

	@Test
	void clientBuiltBeforeSpringStartsLocksTheGlobalEvenInDelegatingMode(CapturedOutput output) {
		try (S3Client early = Harness.s3Client()) {
			early.listBuckets();
		}

		try (ConfigurableApplicationContext context = Harness.start("delegating", false);
				S3Client s3 = Harness.s3Client()) {
			Harness.listBucketsInsideBootSpan(context, s3);

			assertThat(output).contains("GlobalOpenTelemetry is already set");
			assertThat(Harness.exportedNames(context)).containsExactly("request");
		}
	}

}
