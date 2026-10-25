package com.example.otel.aws;

import io.opentelemetry.sdk.resources.Resource;

import org.springframework.beans.factory.ObjectProvider;

/**
 * The result of running every {@link ResourceDetector} bean, in order, once. Shared by the
 * post-processors for traces and metrics so detection (and any metadata endpoint probes)
 * happens once per context.
 */
class DetectedResource {

	private final ObjectProvider<ResourceDetector> detectors;

	private volatile Resource resource;

	DetectedResource(ObjectProvider<ResourceDetector> detectors) {
		this.detectors = detectors;
	}

	Resource get() {
		Resource result = this.resource;
		if (result == null) {
			synchronized (this) {
				result = this.resource;
				if (result == null) {
					result = Resource.empty();
					for (ResourceDetector detector : this.detectors.orderedStream().toList()) {
						result = result.merge(detector.detect(result));
					}
					this.resource = result;
				}
			}
		}
		return result;
	}

}
