package com.example.otel.aws;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.registry.otlp.OtlpConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.mock.env.MockEnvironment;

import static com.example.otel.aws.TestResourceProviders.CLOUD_PROVIDER;
import static com.example.otel.aws.TestResourceProviders.TEST_VALUE;
import static org.assertj.core.api.Assertions.assertThat;

class ResourceDetectionTests {

	private static final AttributeKey<String> CLOUD_REGION = AttributeKey.stringKey("cloud.region");

	private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey("deployment.environment.name");

	private static final ResourceDetector FAKE_AWS = () -> Resource.builder()
		.put(CLOUD_PROVIDER, "aws")
		.put(CLOUD_REGION, "us-east-1")
		.build();

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenTelemetrySdkAutoConfiguration.class, AwsOtelAutoConfiguration.class));

	@BeforeEach
	void resetCalls() {
		TestResourceProviders.TestCloudResourceProvider.calls.set(0);
	}

	@Test
	void discoversEveryResourceProviderOnTheClasspathInOrder() {
		ResourceProviderDetector detector = new ResourceProviderDetector(
				new EnvironmentConfigProperties(new MockEnvironment()), getClass().getClassLoader());
		// The test cloud provider has order -1; the rest default to 0
		assertThat(detector.providerNames()).first()
			.isEqualTo(TestResourceProviders.TestCloudResourceProvider.class.getName());
		assertThat(detector.providerNames()).contains(TestResourceProviders.TestResourceProvider.class.getName(),
				"io.opentelemetry.contrib.aws.resource.BeanstalkResourceProvider",
				"io.opentelemetry.contrib.aws.resource.Ec2ResourceProvider",
				"io.opentelemetry.contrib.aws.resource.EcsResourceProvider",
				"io.opentelemetry.contrib.aws.resource.EksResourceProvider",
				"io.opentelemetry.contrib.aws.resource.LambdaResourceProvider",
				// Other clouds: only their contrib jars were added (test scope)
				"io.opentelemetry.contrib.gcp.resource.GCPResourceProvider",
				"io.opentelemetry.contrib.azure.resource.AzureVmResourceProvider",
				"io.opentelemetry.contrib.azure.resource.AzureAppServiceResourceProvider");
	}

	@Test
	void classpathProvidersApplyWhenNoCloudIsDetected() {
		this.runner.withBean("awsResourceDetector", ResourceDetector.class, () -> Resource::empty)
			.withPropertyValues("otel.test.provider.value=from-spring")
			.run((context) -> {
				Resource resource = context.getBean(Resource.class);
				assertThat(resource.getAttribute(CLOUD_PROVIDER)).isEqualTo("testcloud");
				// ConfigProperties for providers come from the Spring Environment
				assertThat(resource.getAttribute(TEST_VALUE)).isEqualTo("from-spring");
			});
	}

	@Test
	void cloudProvidersSkipOnceTheAwsDetectorFindsACloud() {
		this.runner.withUserConfiguration(OrderedFakeAws.class).run((context) -> {
			Resource resource = context.getBean(Resource.class);
			assertThat(resource.getAttribute(CLOUD_PROVIDER)).isEqualTo("aws");
			assertThat(TestResourceProviders.TestCloudResourceProvider.calls).hasValue(0);
			// Unconditional providers still add their attributes
			assertThat(resource.getAttribute(TEST_VALUE)).isEqualTo("default");
		});
	}

	@Test
	void providersCanBeDisabledOrAllowListedByClassName() {
		ApplicationContextRunner noAws = this.runner.withBean("awsResourceDetector", ResourceDetector.class,
				() -> Resource::empty);
		noAws
			.withPropertyValues(
					"otel.java.disabled.resource.providers=" + TestResourceProviders.TestResourceProvider.class.getName())
			.run((context) -> assertThat(context.getBean(Resource.class).getAttribute(TEST_VALUE)).isNull());
		noAws
			.withPropertyValues(
					"otel.java.enabled.resource.providers=" + TestResourceProviders.TestResourceProvider.class.getName())
			.run((context) -> {
				Resource resource = context.getBean(Resource.class);
				assertThat(resource.getAttribute(TEST_VALUE)).isEqualTo("default");
				assertThat(resource.getAttribute(CLOUD_PROVIDER)).isNull();
			});
	}

	@Test
	void yourOwnDetectorsAreMergedInOrderAndBootStillWins() {
		this.runner.withUserConfiguration(OrderedFakeAws.class, OwnDetectors.class)
			.withPropertyValues("management.opentelemetry.resource-attributes.deployment.environment.name=staging")
			.run((context) -> {
				Resource resource = context.getBean(Resource.class);
				// Unordered (last) detector beats the built-in AWS detector...
				assertThat(resource.getAttribute(CLOUD_REGION)).isEqualTo("from-late-detector");
				// ...an early one loses to it, and Boot's configuration beats every detector
				assertThat(resource.getAttribute(CLOUD_PROVIDER)).isEqualTo("aws");
				assertThat(resource.getAttribute(ENVIRONMENT)).isEqualTo("staging");
				assertThat(resource.getAttribute(AttributeKey.stringKey("team"))).isEqualTo("payments");
			});
	}

	@Test
	void detectionRunsOnceForTracesAndMetrics() {
		AtomicInteger calls = new AtomicInteger();
		this.runner.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
				OtlpMetricsExportAutoConfiguration.class))
			.withBean("awsResourceDetector", ResourceDetector.class, () -> () -> {
				calls.incrementAndGet();
				return FAKE_AWS.detect();
			})
			.run((context) -> {
				assertThat(context.getBean(Resource.class).getAttribute(CLOUD_PROVIDER)).isEqualTo("aws");
				assertThat(context.getBean(OtlpConfig.class).resourceAttributes()).containsEntry("cloud.provider",
						"aws");
				assertThat(calls).hasValue(1);
			});
	}

	@Test
	void environmentConfigPropertiesUseOpenTelemetryFormats() {
		EnvironmentConfigProperties config = new EnvironmentConfigProperties(
				new MockEnvironment().withProperty("otel.a.list", " one, two ,,three")
					.withProperty("otel.a.map", "k1=v1, k2 = v2")
					.withProperty("otel.a.duration", "500")
					.withProperty("otel.b.duration", "10s")
					.withProperty("otel.a.flag", "true"));
		assertThat(config.getList("otel.a.list")).isEqualTo(List.of("one", "two", "three"));
		assertThat(config.getMap("otel.a.map")).isEqualTo(Map.of("k1", "v1", "k2", "v2"));
		assertThat(config.getDuration("otel.a.duration")).isEqualTo(Duration.ofMillis(500));
		assertThat(config.getDuration("otel.b.duration")).isEqualTo(Duration.ofSeconds(10));
		assertThat(config.getBoolean("otel.a.flag")).isTrue();
		assertThat(config.getList("otel.missing")).isEmpty();
		assertThat(config.getString("otel.missing")).isNull();
	}

	// A replacement keeps the built-in's place only with the same @Order
	@Configuration(proxyBeanMethods = false)
	static class OrderedFakeAws {

		@Bean
		@Order(ResourceDetector.AWS_ORDER)
		ResourceDetector awsResourceDetector() {
			return FAKE_AWS;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class OwnDetectors {

		@Bean
		@Order(ResourceDetector.AWS_ORDER - 1)
		ResourceDetector earlyDetector() {
			return () -> Resource.builder().put(CLOUD_PROVIDER, "from-early-detector").build();
		}

		@Bean
		ResourceDetector lateDetector() {
			return () -> Resource.builder()
				.put(CLOUD_REGION, "from-late-detector")
				.put(ENVIRONMENT, "from-late-detector")
				.put("team", "payments")
				.build();
		}

	}

}
