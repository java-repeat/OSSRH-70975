package com.example.otel.aws;

import java.util.ArrayList;
import java.util.List;

import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.contrib.awsxray.propagator.AwsXrayPropagator;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AwsOtelAutoConfigurationTests {

	private static final AttributeKey<String> CLOUD_PROVIDER = AttributeKey.stringKey("cloud.provider");

	private static final AttributeKey<String> CLOUD_REGION = AttributeKey.stringKey("cloud.region");

	private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

	// Fake AWS detection so tests never call IMDS
	private static final AwsResourceDetector FAKE_AWS = () -> Resource.builder()
		.put(CLOUD_PROVIDER, "aws")
		.put(CLOUD_REGION, "us-east-1")
		.put(SERVICE_NAME, "from-aws-should-lose")
		.build();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenTelemetrySdkAutoConfiguration.class, AwsOtelAutoConfiguration.class))
		.withBean(AwsResourceDetector.class, () -> FAKE_AWS);

	@AfterEach
	void reset() {
		LazyOtelAwsInterceptor.bind(null);
	}

	@Test
	void mergesAwsAttributesIntoBootsResource() {
		this.runner.withPropertyValues("spring.application.name=orders").run((context) -> {
			Resource resource = context.getBean(Resource.class);
			assertThat(resource.getAttribute(CLOUD_PROVIDER)).isEqualTo("aws");
			assertThat(resource.getAttribute(CLOUD_REGION)).isEqualTo("us-east-1");
			// Boot's service.name wins over anything a detector sets
			assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("orders");
		});
	}

	@Test
	void bootResourceAttributesStillApply() {
		this.runner.withPropertyValues("management.opentelemetry.resource-attributes.cloud.region=eu-west-1")
			.run((context) -> {
				Resource resource = context.getBean(Resource.class);
				// Boot/user configuration wins over detection on conflict
				assertThat(resource.getAttribute(CLOUD_REGION)).isEqualTo("eu-west-1");
				assertThat(resource.getAttribute(CLOUD_PROVIDER)).isEqualTo("aws");
			});
	}

	@Test
	void resourceDetectionCanBeDisabled() {
		this.runner.withPropertyValues("aws.otel.resources.enabled=false").run((context) -> {
			assertThat(context.getBean(Resource.class).getAttribute(CLOUD_PROVIDER)).isNull();
		});
	}

	@Test
	void mergesAwsAttributesIntoBootsOtlpMetricsResource() {
		this.runner.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
				OtlpMetricsExportAutoConfiguration.class))
			.withPropertyValues("spring.application.name=orders",
					"management.opentelemetry.resource-attributes.cloud.region=eu-west-1",
					"management.otlp.metrics.export.url=http://collector:4318/v1/metrics")
			.run((context) -> {
				OtlpConfig config = context.getBean(OtlpConfig.class);
				assertThat(config.resourceAttributes()).containsEntry("cloud.provider", "aws")
					// Boot/user configuration wins over detection on conflict
					.containsEntry("cloud.region", "eu-west-1")
					.containsEntry("service.name", "orders");
				// Everything else still comes from Boot's bean
				assertThat(config.url()).isEqualTo("http://collector:4318/v1/metrics");
				assertThat(context.getBean(OtlpMeterRegistry.class)).isNotNull();
			});
	}

	@Test
	void metricsResourceDetectionCanBeDisabled() {
		this.runner.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
				OtlpMetricsExportAutoConfiguration.class))
			.withPropertyValues("aws.otel.resources.enabled=false")
			.run((context) -> assertThat(context.getBean(OtlpConfig.class).resourceAttributes())
				.doesNotContainKey("cloud.provider"));
	}

	@Test
	void xrayPropagatorIsOptIn() {
		this.runner.run((context) -> assertThat(context).doesNotHaveBean(AwsXrayPropagator.class));
		this.runner.withPropertyValues("aws.otel.xray-propagator.enabled=true")
			.run((context) -> assertThat(context).hasSingleBean(AwsXrayPropagator.class));
	}

	@Test
	void bindsAwsSdkTracingToBootsSdkAndUnbindsOnClose() {
		this.runner.run((context) -> assertThat(LazyOtelAwsInterceptor.isBound()).isTrue());
		assertThat(LazyOtelAwsInterceptor.isBound()).isFalse();
	}

	@Test
	void awsSdkTracingCanBeDisabled() {
		this.runner.withPropertyValues("aws.otel.sdk.enabled=false")
			.run((context) -> assertThat(LazyOtelAwsInterceptor.isBound()).isFalse());
	}

	@Test
	void callStartedBeforeBindingStaysUntracedEvenIfBindingHappensMidCall() {
		LazyOtelAwsInterceptor interceptor = new LazyOtelAwsInterceptor();
		RecordingInterceptor recorder = new RecordingInterceptor();
		ExecutionAttributes attributes = new ExecutionAttributes();
		InterceptorContext context = context();

		interceptor.beforeExecution(context, attributes); // not bound yet
		LazyOtelAwsInterceptor.bind(recorder); // binding lands mid-call
		SdkHttpRequest result = interceptor.modifyHttpRequest(context, attributes);
		interceptor.afterExecution(context, attributes);

		assertThat(recorder.calls).isEmpty();
		assertThat(result).isSameAs(context.httpRequest());
	}

	@Test
	void callStartedAfterBindingIsDelegatedForEveryHook() {
		LazyOtelAwsInterceptor interceptor = new LazyOtelAwsInterceptor();
		RecordingInterceptor recorder = new RecordingInterceptor();
		LazyOtelAwsInterceptor.bind(recorder);
		ExecutionAttributes attributes = new ExecutionAttributes();
		InterceptorContext context = context();

		interceptor.beforeExecution(context, attributes);
		LazyOtelAwsInterceptor.bind(null); // unbinding mid-call doesn't break the pinned call
		interceptor.modifyHttpRequest(context, attributes);
		interceptor.afterExecution(context, attributes);

		assertThat(recorder.calls).containsExactly("beforeExecution", "modifyHttpRequest", "afterExecution");
	}

	private static InterceptorContext context() {
		SdkHttpFullRequest httpRequest = SdkHttpFullRequest.builder()
			.method(SdkHttpMethod.GET)
			.protocol("https")
			.host("example.amazonaws.com")
			.build();
		return InterceptorContext.builder().request(mock(SdkRequest.class)).httpRequest(httpRequest).build();
	}

	static class RecordingInterceptor implements ExecutionInterceptor {

		final List<String> calls = new ArrayList<>();

		@Override
		public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
			this.calls.add("beforeExecution");
		}

		@Override
		public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context, ExecutionAttributes attributes) {
			this.calls.add("modifyHttpRequest");
			return context.httpRequest();
		}

		@Override
		public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
			this.calls.add("afterExecution");
		}

	}

}
