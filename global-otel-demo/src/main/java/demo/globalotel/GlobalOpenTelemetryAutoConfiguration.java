package demo.globalotel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Hooks Spring Boot's {@link OpenTelemetry} bean into {@link GlobalOpenTelemetry}, in the way
 * {@code demo.otel.global.mode} picks ({@code none} by default, which is Boot's behavior).
 */
@AutoConfiguration
public final class GlobalOpenTelemetryAutoConfiguration {

	@Bean
	@ConditionalOnProperty(name = GlobalOpenTelemetryMode.PROPERTY, havingValue = "eager")
	static EagerGlobalRegistrar eagerGlobalOpenTelemetryRegistrar() {
		return new EagerGlobalRegistrar();
	}

	@Bean
	@ConditionalOnProperty(name = GlobalOpenTelemetryMode.PROPERTY, havingValue = "delegating")
	static DelegatingGlobalBinder delegatingGlobalOpenTelemetryBinder() {
		return new DelegatingGlobalBinder();
	}

	/**
	 * {@code GlobalOpenTelemetry.set(bean)} the moment Boot's bean is initialized. Any
	 * {@code GlobalOpenTelemetry.get()} before that has already locked the global to a no-op,
	 * and this only logs a warning.
	 */
	static final class EagerGlobalRegistrar implements BeanPostProcessor, DisposableBean {

		private static final Log logger = LogFactory.getLog(EagerGlobalRegistrar.class);

		private volatile boolean registered;

		private volatile boolean attempted;

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (bean instanceof OpenTelemetry openTelemetry && !this.attempted) {
				this.attempted = true;
				if (GlobalOpenTelemetry.isSet()) {
					logger.warn("GlobalOpenTelemetry was already set before Spring Boot's OpenTelemetry bean "
							+ "existed (OpenTelemetry Java agent, or an earlier GlobalOpenTelemetry.get(), which "
							+ "installs a permanent no-op). Libraries that use the global will not report to "
							+ "Spring Boot's OpenTelemetry.");
					return bean;
				}
				try {
					GlobalOpenTelemetry.set(openTelemetry);
					this.registered = true;
				}
				catch (IllegalStateException ex) {
					logger.warn("Lost the race to set GlobalOpenTelemetry.", ex);
				}
			}
			return bean;
		}

		/** The only way to unset the global is a method named for tests. */
		@Override
		public void destroy() {
			if (this.registered) {
				GlobalOpenTelemetry.resetForTest();
				this.registered = false;
			}
		}

	}

	/** Binds the delegating global to Boot's bean, and unbinds it on close. */
	static final class DelegatingGlobalBinder implements BeanPostProcessor, DisposableBean {

		private volatile OpenTelemetry bound;

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (bean instanceof OpenTelemetry openTelemetry && !(bean instanceof DelegatingOpenTelemetry)
					&& this.bound == null) {
				DelegatingOpenTelemetry global = GlobalOpenTelemetryBridge.installedInstance();
				if (global != null) {
					global.bind(openTelemetry);
					this.bound = openTelemetry;
				}
			}
			return bean;
		}

		@Override
		public void destroy() {
			OpenTelemetry openTelemetry = this.bound;
			DelegatingOpenTelemetry global = GlobalOpenTelemetryBridge.installedInstance();
			if (openTelemetry != null && global != null) {
				global.unbind(openTelemetry);
				this.bound = null;
			}
		}

	}

}
