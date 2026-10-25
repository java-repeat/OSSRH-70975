package com.example.otel.aws;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import io.opentelemetry.sdk.autoconfigure.spi.internal.ConditionalResourceProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.resources.Resource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Applies every OpenTelemetry {@link ResourceProvider} registered on the classpath through
 * {@code META-INF/services}, the same SPI the OpenTelemetry SDK autoconfigure module and
 * Java agent use. Contrib detectors for GCP, Azure and others, and providers you write for
 * the agent, work unchanged.
 *
 * <p>
 * Follows the SDK's rules: providers run by {@link ResourceProvider#order()}, a
 * {@link ConditionalResourceProvider} is skipped when {@code shouldApply} says so (cloud
 * providers skip once {@code cloud.provider} is set, so other clouds' metadata endpoints
 * aren't probed), and {@code otel.java.enabled.resource.providers} /
 * {@code otel.java.disabled.resource.providers} filter by class name. A provider that fails
 * to load or throws is logged and skipped, so detection never stops the application.
 *
 * <p>
 * {@code ConditionalResourceProvider} is in the SPI's {@code internal} package, but it is
 * what the contrib detectors implement and what the SDK itself checks, so it is checked
 * here too.
 */
class ResourceProviderDetector implements ResourceDetector {

	private static final Log logger = LogFactory.getLog(ResourceProviderDetector.class);

	private final ConfigProperties config;

	private final List<ResourceProvider> providers;

	ResourceProviderDetector(ConfigProperties config, ClassLoader classLoader) {
		this.config = config;
		this.providers = load(config, classLoader);
	}

	List<String> providerNames() {
		return this.providers.stream().map((provider) -> provider.getClass().getName()).toList();
	}

	@Override
	public Resource detect() {
		return detect(Resource.empty());
	}

	@Override
	public Resource detect(Resource detected) {
		Resource current = detected;
		Resource added = Resource.empty();
		for (ResourceProvider provider : this.providers) {
			try {
				if (provider instanceof ConditionalResourceProvider conditional
						&& !conditional.shouldApply(this.config, current)) {
					continue;
				}
				Resource resource = provider.createResource(this.config);
				current = current.merge(resource);
				added = added.merge(resource);
			}
			catch (RuntimeException ex) {
				logger.warn("Resource provider " + provider.getClass().getName() + " failed; skipping it", ex);
			}
		}
		return added;
	}

	private static List<ResourceProvider> load(ConfigProperties config, ClassLoader classLoader) {
		List<String> enabled = config.getList("otel.java.enabled.resource.providers");
		List<String> disabled = config.getList("otel.java.disabled.resource.providers");
		List<ResourceProvider> providers = new ArrayList<>();
		Iterator<ResourceProvider> loader = ServiceLoader.load(ResourceProvider.class, classLoader).iterator();
		while (true) {
			try {
				if (!loader.hasNext()) {
					break;
				}
				ResourceProvider provider = loader.next();
				String name = provider.getClass().getName();
				if ((enabled.isEmpty() || enabled.contains(name)) && !disabled.contains(name)) {
					providers.add(provider);
				}
			}
			catch (ServiceConfigurationError ex) {
				logger.warn("Could not load a resource provider; skipping it", ex);
			}
		}
		providers.sort(Comparator.comparingInt(ResourceProvider::order));
		return List.copyOf(providers);
	}

}
