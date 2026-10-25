package com.example.otel.aws;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.contrib.awsxray.propagator.AwsXrayPropagator;
import io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry;
import io.opentelemetry.sdk.resources.Resource;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/**
 * Plugs AWS pieces into the beans Spring Boot's OpenTelemetry support already creates.
 * Boot keeps ownership of the SDK, sampling, W3C propagation and OTLP export; nothing here
 * replaces a Boot bean or relies on {@code GlobalOpenTelemetry}.
 *
 * <ul>
 * <li>Resource: AWS attributes merged into Boot's {@code Resource} bean</li>
 * <li>Propagation: an extra {@code TextMapPropagator} bean, which Boot composes with its own</li>
 * <li>AWS SDK v2: a global SPI interceptor bound lazily to Boot's {@code OpenTelemetry}</li>
 * </ul>
 */
@AutoConfiguration(afterName = {
		"org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration",
		"org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration" })
@ConditionalOnClass({ OpenTelemetry.class, Resource.class })
@EnableConfigurationProperties(AwsOtelProperties.class)
public final class AwsOtelAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBooleanProperty(name = "aws.otel.resources.enabled", matchIfMissing = true)
	static class ResourceConfiguration {

		@Bean
		@Order(ResourceDetector.AWS_ORDER)
		@ConditionalOnMissingBean(name = "awsResourceDetector")
		@ConditionalOnClass(name = "io.opentelemetry.contrib.aws.resource.Ec2Resource")
		ResourceDetector awsResourceDetector() {
			return ResourceDetector.aws();
		}

		@Bean
		@Order(ResourceDetector.RESOURCE_PROVIDERS_ORDER)
		@ConditionalOnMissingBean(name = "resourceProviderDetector")
		ResourceDetector resourceProviderDetector(Environment environment) {
			return new ResourceProviderDetector(new EnvironmentConfigProperties(environment),
					AwsOtelAutoConfiguration.class.getClassLoader());
		}

		@Bean
		DetectedResource detectedResource(ObjectProvider<ResourceDetector> detectors) {
			return new DetectedResource(detectors);
		}

		// static: BeanPostProcessors must not force early init of their configuration class
		@Bean
		static AwsResourcePostProcessor awsResourcePostProcessor(ObjectProvider<DetectedResource> detected) {
			return new AwsResourcePostProcessor(detected);
		}

		@Configuration(proxyBeanMethods = false)
		@ConditionalOnClass(name = "io.micrometer.registry.otlp.OtlpConfig")
		static class OtlpMetricsResourceConfiguration {

			@Bean
			static AwsOtlpMetricsPostProcessor awsOtlpMetricsPostProcessor(ObjectProvider<DetectedResource> detected) {
				return new AwsOtlpMetricsPostProcessor(detected);
			}

		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(AwsXrayPropagator.class)
	@ConditionalOnBooleanProperty(name = "aws.otel.xray-propagator.enabled")
	static class XrayPropagationConfiguration {

		// Boot builds ContextPropagators from every TextMapPropagator bean via orderedStream().
		// Boot's own propagator beans are unordered, so ties fall back to registration order,
		// and afterName above registers this bean after Boot's. Extract runs each propagator in
		// turn and the last one wins, so when a request carries both traceparent and
		// X-Amzn-Trace-Id, the X-Ray context is used. Inject runs all of them, so outgoing
		// calls carry both headers.
		@Bean
		AwsXrayPropagator awsXrayPropagator() {
			return AwsXrayPropagator.getInstance();
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ ExecutionInterceptor.class, AwsSdkTelemetry.class })
	@ConditionalOnBooleanProperty(name = "aws.otel.sdk.enabled", matchIfMissing = true)
	static class AwsSdkTracingConfiguration {

		@Bean
		static AwsSdkTracingBinder awsSdkTracingBinder(ObjectProvider<AwsOtelProperties> properties) {
			return new AwsSdkTracingBinder(properties);
		}

	}

}
