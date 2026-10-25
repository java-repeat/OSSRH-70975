package com.example.otel.aws;

import io.opentelemetry.sdk.resources.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Merges detected attributes into Spring Boot's own {@link Resource} bean.
 *
 * <p>
 * Wrapping Boot's bean, rather than declaring a replacement {@code Resource} bean, matters:
 * Boot's {@code Resource} is {@code @ConditionalOnMissingBean}, and only Boot's bean merges
 * {@code OTEL_RESOURCE_ATTRIBUTES}, {@code OTEL_SERVICE_NAME} and
 * {@code management.opentelemetry.resource-attributes}. Defining our own bean would silently
 * drop all of that.
 *
 * <p>
 * Detected attributes go first so that anything Boot or the user configured wins on
 * conflict.
 */
class AwsResourcePostProcessor implements BeanPostProcessor {

	private final ObjectProvider<DetectedResource> detected;

	AwsResourcePostProcessor(ObjectProvider<DetectedResource> detected) {
		this.detected = detected;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof Resource bootResource) {
			return this.detected.getObject().get().merge(bootResource);
		}
		return bean;
	}

}
