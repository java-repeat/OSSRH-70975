package demo.globalotel;

/** Values of {@code demo.otel.global.mode}. */
public enum GlobalOpenTelemetryMode {

	/** Spring Boot's default: the global is never set by Spring. */
	NONE,

	/**
	 * Call {@code GlobalOpenTelemetry.set(bean)} as soon as Boot's {@code OpenTelemetry}
	 * bean is initialized, and {@code resetForTest()} when the context closes.
	 */
	EAGER,

	/**
	 * Publish a {@link DelegatingOpenTelemetry} as the global before any bean exists, and
	 * bind it to Boot's bean once that is initialized.
	 */
	DELEGATING;

	static final String PROPERTY = "demo.otel.global.mode";

}
