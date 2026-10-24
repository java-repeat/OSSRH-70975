package com.example.otel.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the AWS OpenTelemetry starter, bound from {@code aws.otel.*}.
 *
 * <pre>
 * aws:
 *   otel:
 *     resources:
 *       enabled: true          # merge EC2/ECS/EKS/Lambda/Beanstalk attributes into Boot's Resource
 *     xray-propagator:
 *       enabled: false         # also read/write X-Amzn-Trace-Id alongside W3C traceparent
 *     sdk:
 *       enabled: true          # trace every AWS SDK v2 client
 *       experimental-span-attributes: false
 * </pre>
 */
@ConfigurationProperties("aws.otel")
public class AwsOtelProperties {

	private final Resources resources = new Resources();

	private final XrayPropagator xrayPropagator = new XrayPropagator();

	private final Sdk sdk = new Sdk();

	public Resources getResources() {
		return this.resources;
	}

	public XrayPropagator getXrayPropagator() {
		return this.xrayPropagator;
	}

	public Sdk getSdk() {
		return this.sdk;
	}

	public static class Resources {

		/** Whether to merge AWS-detected resource attributes into Boot's Resource bean. */
		private boolean enabled = true;

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

	}

	public static class XrayPropagator {

		/** Whether to add the AWS X-Ray propagator next to Boot's configured propagators. */
		private boolean enabled = false;

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

	}

	public static class Sdk {

		/** Whether to trace AWS SDK v2 clients. */
		private boolean enabled = true;

		/** Whether to capture experimental span attributes, such as DynamoDB table names. */
		private boolean experimentalSpanAttributes = false;

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isExperimentalSpanAttributes() {
			return this.experimentalSpanAttributes;
		}

		public void setExperimentalSpanAttributes(boolean experimentalSpanAttributes) {
			this.experimentalSpanAttributes = experimentalSpanAttributes;
		}

	}

}
