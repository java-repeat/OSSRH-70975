package com.example.otel.aws;

import io.opentelemetry.contrib.aws.resource.BeanstalkResource;
import io.opentelemetry.contrib.aws.resource.Ec2Resource;
import io.opentelemetry.contrib.aws.resource.EcsResource;
import io.opentelemetry.contrib.aws.resource.EksResource;
import io.opentelemetry.contrib.aws.resource.LambdaResource;
import io.opentelemetry.sdk.resources.Resource;

/**
 * Detects resource attributes for the current runtime. Every {@code ResourceDetector} bean
 * is applied, in {@link org.springframework.core.annotation.Order @Order}, and a later
 * detector wins on conflicting attributes. Boot's own resource configuration always wins
 * over every detector.
 *
 * <p>
 * Two detectors are built in: {@link #aws()} (bean {@code awsResourceDetector}, order
 * {@link #AWS_ORDER}) and one that applies every OpenTelemetry
 * {@code ResourceProvider} on the classpath (bean {@code resourceProviderDetector}, order
 * {@link #RESOURCE_PROVIDERS_ORDER}), so adding a contrib jar such as
 * {@code opentelemetry-gcp-resources} is enough for another platform. Declare a bean with
 * either name to replace that detector, for example with {@code Resource::empty} in tests;
 * give the replacement the same {@code @Order} to keep its place. Your own detector beans
 * without {@code @Order} run last, so they win over the built-in ones.
 */
@FunctionalInterface
public interface ResourceDetector {

	/** Order of the built-in AWS detector. */
	int AWS_ORDER = -200;

	/** Order of the built-in detector for classpath {@code ResourceProvider}s. */
	int RESOURCE_PROVIDERS_ORDER = -100;

	Resource detect();

	/**
	 * Detects attributes given everything detected so far. Override to skip work that isn't
	 * needed, for example probing a cloud metadata endpoint once {@code cloud.provider} is
	 * known.
	 * @param detected the attributes from detectors that ran earlier
	 * @return the attributes this detector adds
	 */
	default Resource detect(Resource detected) {
		return detect();
	}

	/**
	 * Merges every contrib AWS detector in the same order as OpenTelemetry's own declarative
	 * {@code aws} detector, so the more specific platform wins on overlapping attributes
	 * (ECS or EKS over EC2, for example). Each detector returns an empty Resource when it
	 * isn't running on its platform.
	 *
	 * <p>
	 * This deliberately differs from the contrib {@code ResourceProvider}s, where the first
	 * provider to set {@code cloud.provider} stops the rest: on ECS on EC2 that keeps only
	 * the EC2 attributes. Because this runs first and sets {@code cloud.provider}, those
	 * providers then skip themselves.
	 */
	static ResourceDetector aws() {
		return () -> Resource.empty()
			.merge(BeanstalkResource.get())
			.merge(Ec2Resource.get())
			.merge(EcsResource.get())
			.merge(EksResource.get())
			.merge(LambdaResource.get());
	}

}
