package com.example.otel.aws;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.micrometer.registry.otlp.OtlpConfig;
import io.opentelemetry.sdk.resources.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Merges AWS-detected attributes into the resource of Boot's OTLP metrics export.
 *
 * <p>
 * Boot exports metrics through Micrometer's {@code OtlpMeterRegistry}, which never sees the
 * OpenTelemetry {@link Resource} bean: it takes its resource attributes from
 * {@link OtlpConfig#resourceAttributes()}, which Boot fills from {@code OTEL_*} variables
 * and {@code management.opentelemetry.resource-attributes} only. Without this, spans carry
 * the AWS attributes and metrics don't, so the two can't be joined on {@code cloud.*} or
 * {@code host.id}.
 *
 * <p>
 * Boot's {@code OtlpConfig} bean is wrapped rather than replaced, for the same reason as
 * {@link AwsResourcePostProcessor}. The wrapper is a proxy so every other setting, including
 * ones added to {@code OtlpConfig} later, still comes from Boot's bean. AWS attributes go
 * first so that anything Boot or the user configured wins on conflict.
 */
class AwsOtlpMetricsPostProcessor implements BeanPostProcessor {

	private final ObjectProvider<AwsResourceDetector> detector;

	AwsOtlpMetricsPostProcessor(ObjectProvider<AwsResourceDetector> detector) {
		this.detector = detector;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof OtlpConfig bootConfig) {
			AwsResourceDetector awsDetector = this.detector.getIfAvailable(AwsResourceDetector::contrib);
			return withAwsAttributes(bootConfig, toAttributes(awsDetector.detect()));
		}
		return bean;
	}

	private static OtlpConfig withAwsAttributes(OtlpConfig bootConfig, Map<String, String> aws) {
		return (OtlpConfig) Proxy.newProxyInstance(OtlpConfig.class.getClassLoader(),
				new Class<?>[] { OtlpConfig.class }, (proxy, method, args) -> {
					if (method.getName().equals("resourceAttributes") && method.getParameterCount() == 0) {
						Map<String, String> merged = new LinkedHashMap<>(aws);
						merged.putAll(bootConfig.resourceAttributes());
						return Collections.unmodifiableMap(merged);
					}
					try {
						return method.invoke(bootConfig, args);
					}
					catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
				});
	}

	// Micrometer's resource attributes are strings; OTLP arrays become comma-separated values
	private static Map<String, String> toAttributes(Resource resource) {
		Map<String, String> attributes = new LinkedHashMap<>();
		resource.getAttributes().forEach((key, value) -> attributes.put(key.getKey(), (value instanceof List<?> list)
				? list.stream().map(String::valueOf).collect(Collectors.joining(",")) : String.valueOf(value)));
		return attributes;
	}

}
