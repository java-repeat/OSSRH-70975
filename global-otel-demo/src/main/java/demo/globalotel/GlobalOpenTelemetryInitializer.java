package demo.globalotel;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * In {@code delegating} mode, publishes the global before the context creates any bean, so a
 * bean that touches {@code GlobalOpenTelemetry} early cannot lock it to a no-op. Registered
 * in {@code META-INF/spring.factories}.
 */
public class GlobalOpenTelemetryInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		GlobalOpenTelemetryMode mode = context.getEnvironment()
			.getProperty(GlobalOpenTelemetryMode.PROPERTY, GlobalOpenTelemetryMode.class, GlobalOpenTelemetryMode.NONE);
		if (mode == GlobalOpenTelemetryMode.DELEGATING) {
			GlobalOpenTelemetryBridge.install();
		}
	}

}
