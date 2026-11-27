package demo.globalotel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.services.s3.S3Client;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q: With eager registration, what if an ordinary bean uses the library before Boot's
 * OpenTelemetry bean is created?
 */
@ExtendWith(OutputCaptureExtension.class)
class EagerRegistrationOrderingTests {

	@Test
	void anS3ClientBeanBuiltBeforeBootsOpenTelemetryBeanLocksTheGlobalToNoop(CapturedOutput output) {
		try (ConfigurableApplicationContext context = Harness.start("eager", true)) {
			Harness.listBucketsInsideBootSpan(context, context.getBean(S3Client.class));

			// Building the client loaded the library's interceptor, which read the global
			// before Boot's bean existed: a permanent no-op, and registration was refused
			assertThat(output).contains("GlobalOpenTelemetry was already set before Spring Boot's OpenTelemetry bean");
			assertThat(GlobalOpenTelemetry.isSet()).isTrue();
			assertThat(Harness.globalRecords()).isFalse();
			assertThat(Harness.exportedNames(context)).containsExactly("request");
		}
	}

}
