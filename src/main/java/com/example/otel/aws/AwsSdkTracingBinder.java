package com.example.otel.aws;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Binds {@link LazyOtelAwsInterceptor} to Boot's {@link OpenTelemetry} bean the moment that
 * bean is initialized, which is the earliest point it can exist. Unbinds on context close so
 * test contexts and restarts don't leak a stale SDK into the next context.
 */
class AwsSdkTracingBinder implements BeanPostProcessor, DisposableBean {

	private final ObjectProvider<AwsOtelProperties> properties;

	private volatile ExecutionInterceptor bound;

	AwsSdkTracingBinder(ObjectProvider<AwsOtelProperties> properties) {
		this.properties = properties;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof OpenTelemetry openTelemetry && this.bound == null) {
			AwsOtelProperties.Sdk sdk = this.properties.getObject().getSdk();
			ExecutionInterceptor interceptor = AwsSdkTelemetry.builder(openTelemetry)
				.setCaptureExperimentalSpanAttributes(sdk.isExperimentalSpanAttributes())
				.build()
				.createExecutionInterceptor();
			LazyOtelAwsInterceptor.bind(interceptor);
			this.bound = interceptor;
		}
		return bean;
	}

	@Override
	public void destroy() {
		ExecutionInterceptor interceptor = this.bound;
		if (interceptor != null) {
			LazyOtelAwsInterceptor.unbind(interceptor);
			this.bound = null;
		}
	}

}
