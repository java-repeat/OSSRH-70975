package com.example.otel.aws;

import io.opentelemetry.contrib.aws.resource.BeanstalkResource;
import io.opentelemetry.contrib.aws.resource.Ec2Resource;
import io.opentelemetry.contrib.aws.resource.EcsResource;
import io.opentelemetry.contrib.aws.resource.EksResource;
import io.opentelemetry.contrib.aws.resource.LambdaResource;
import io.opentelemetry.sdk.resources.Resource;

/**
 * Detects the AWS {@link Resource} for the current runtime. Declared as a bean so apps and
 * tests can replace it (for example with {@code Resource::empty} off AWS).
 */
@FunctionalInterface
public interface AwsResourceDetector {

	Resource detect();

	/**
	 * Merges every contrib AWS detector in the same order as OpenTelemetry's own declarative
	 * {@code aws} detector, so the more specific platform wins on overlapping attributes
	 * (ECS or EKS over EC2, for example). Each detector returns an empty Resource when it
	 * isn't running on its platform.
	 */
	static AwsResourceDetector contrib() {
		return () -> Resource.empty()
			.merge(BeanstalkResource.get())
			.merge(Ec2Resource.get())
			.merge(EcsResource.get())
			.merge(EksResource.get())
			.merge(LambdaResource.get());
	}

}
