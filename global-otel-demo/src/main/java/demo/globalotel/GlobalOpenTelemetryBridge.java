package demo.globalotel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The one JVM-wide {@link DelegatingOpenTelemetry} that the {@code delegating} mode
 * publishes as {@link GlobalOpenTelemetry}.
 *
 * <p>
 * Spring calls {@link #install()} from an {@code ApplicationContextInitializer}, before any
 * bean exists. Code that can touch the global even earlier (a static initializer, or work in
 * {@code main} before {@code SpringApplication.run}) must call {@link #install()} itself
 * first.
 */
public final class GlobalOpenTelemetryBridge {

	private static final Log logger = LogFactory.getLog(GlobalOpenTelemetryBridge.class);

	private static final DelegatingOpenTelemetry INSTANCE = new DelegatingOpenTelemetry();

	private static boolean installed;

	private GlobalOpenTelemetryBridge() {
	}

	/**
	 * Publishes the delegating instance as the global, once per JVM.
	 * @return the instance to bind, or {@code null} if something else (the OpenTelemetry
	 * Java agent, or an earlier {@code GlobalOpenTelemetry.get()}) already owns the global
	 */
	public static synchronized DelegatingOpenTelemetry install() {
		if (installed) {
			return INSTANCE;
		}
		if (GlobalOpenTelemetry.isSet()) {
			logger.warn("GlobalOpenTelemetry is already set (OpenTelemetry Java agent, or an earlier "
					+ "GlobalOpenTelemetry.get()); leaving it alone. Libraries that use the global "
					+ "will not report to Spring Boot's OpenTelemetry.");
			return null;
		}
		try {
			GlobalOpenTelemetry.set(INSTANCE);
		}
		catch (IllegalStateException ex) {
			logger.warn("Lost the race to set GlobalOpenTelemetry; leaving it alone.", ex);
			return null;
		}
		installed = true;
		return INSTANCE;
	}

	static synchronized DelegatingOpenTelemetry installedInstance() {
		return installed ? INSTANCE : null;
	}

}
