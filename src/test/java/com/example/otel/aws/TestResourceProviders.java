package com.example.otel.aws;

import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.spi.internal.ConditionalResourceProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.resources.Resource;

/**
 * {@link ResourceProvider}s registered for tests in {@code META-INF/services}, standing in
 * for a contrib cloud detector and a bring-your-own provider.
 */
final class TestResourceProviders {

	static final AttributeKey<String> CLOUD_PROVIDER = AttributeKey.stringKey("cloud.provider");

	static final AttributeKey<String> TEST_VALUE = AttributeKey.stringKey("test.provider.value");

	private TestResourceProviders() {
	}

	/**
	 * Behaves like the contrib cloud detectors: skips once a cloud is known. Ordered before
	 * the contrib AWS providers, so tests never reach the real EC2 metadata endpoint.
	 */
	public static final class TestCloudResourceProvider implements ConditionalResourceProvider {

		static final AtomicInteger calls = new AtomicInteger();

		@Override
		public boolean shouldApply(ConfigProperties config, Resource existing) {
			return existing.getAttribute(CLOUD_PROVIDER) == null;
		}

		@Override
		public Resource createResource(ConfigProperties config) {
			calls.incrementAndGet();
			return Resource.builder().put(CLOUD_PROVIDER, "testcloud").build();
		}

		@Override
		public int order() {
			return -1;
		}

	}

	/** Reads its value from OpenTelemetry-style config, which comes from Spring here. */
	public static final class TestResourceProvider implements ResourceProvider {

		@Override
		public Resource createResource(ConfigProperties config) {
			return Resource.builder().put(TEST_VALUE, config.getString("otel.test.provider.value", "default")).build();
		}

	}

}
