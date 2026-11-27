package demo.globalotel;

import java.util.Collection;
import java.util.function.Function;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * An {@link OpenTelemetry} that can be published as the global before Spring Boot has built
 * its SDK, and pointed at Boot's {@code OpenTelemetry} bean later with {@link #bind}.
 *
 * <p>
 * Libraries usually fetch a {@link Tracer} once and keep it. So tracers and propagators
 * handed out here look up the current delegate on every use, and a tracer fetched before
 * {@link #bind} still records once Boot's SDK is bound (and follows a context restart).
 *
 * <p>
 * Limits, on purpose, to keep this small: meters and the logs bridge are taken from the
 * delegate at the moment they are requested, so a meter fetched before {@link #bind} stays
 * a no-op. Spans started while unbound are no-ops.
 */
public final class DelegatingOpenTelemetry implements OpenTelemetry {

	private static final OpenTelemetry NOOP = OpenTelemetry.noop();

	private volatile OpenTelemetry delegate = NOOP;

	private final TracerProvider tracerProvider = new DelegatingTracerProvider();

	private final ContextPropagators propagators = () -> new DelegatingTextMapPropagator();

	/** Sends everything to {@code openTelemetry} from now on. */
	public void bind(OpenTelemetry openTelemetry) {
		this.delegate = openTelemetry;
	}

	/** Goes back to no-op, only if {@code openTelemetry} is still the one bound. */
	public void unbind(OpenTelemetry openTelemetry) {
		if (this.delegate == openTelemetry) {
			this.delegate = NOOP;
		}
	}

	public boolean isBound() {
		return this.delegate != NOOP;
	}

	@Override
	public TracerProvider getTracerProvider() {
		return this.tracerProvider;
	}

	@Override
	public MeterProvider getMeterProvider() {
		return this.delegate.getMeterProvider();
	}

	@Override
	public LoggerProvider getLogsBridge() {
		return this.delegate.getLogsBridge();
	}

	@Override
	public ContextPropagators getPropagators() {
		return this.propagators;
	}

	private final class DelegatingTracerProvider implements TracerProvider {

		@Override
		public Tracer get(String name) {
			return new DelegatingTracer((provider) -> provider.get(name));
		}

		@Override
		public Tracer get(String name, String version) {
			return new DelegatingTracer((provider) -> provider.get(name, version));
		}

		@Override
		public TracerBuilder tracerBuilder(String name) {
			return new TracerBuilder() {

				private String schemaUrl;

				private String version;

				@Override
				public TracerBuilder setSchemaUrl(String schemaUrl) {
					this.schemaUrl = schemaUrl;
					return this;
				}

				@Override
				public TracerBuilder setInstrumentationVersion(String version) {
					this.version = version;
					return this;
				}

				@Override
				public Tracer build() {
					String schemaUrl = this.schemaUrl;
					String version = this.version;
					return new DelegatingTracer((provider) -> {
						TracerBuilder builder = provider.tracerBuilder(name);
						if (schemaUrl != null) {
							builder.setSchemaUrl(schemaUrl);
						}
						if (version != null) {
							builder.setInstrumentationVersion(version);
						}
						return builder.build();
					});
				}

			};
		}

	}

	/** Resolves the real tracer from the current delegate, and caches it per delegate. */
	private final class DelegatingTracer implements Tracer {

		private final Function<TracerProvider, Tracer> factory;

		private volatile Resolved resolved;

		DelegatingTracer(Function<TracerProvider, Tracer> factory) {
			this.factory = factory;
		}

		private Tracer current() {
			OpenTelemetry owner = DelegatingOpenTelemetry.this.delegate;
			Resolved resolved = this.resolved;
			if (resolved == null || resolved.owner != owner) {
				resolved = new Resolved(owner, this.factory.apply(owner.getTracerProvider()));
				this.resolved = resolved;
			}
			return resolved.tracer;
		}

		@Override
		public boolean isEnabled() {
			return current().isEnabled();
		}

		@Override
		public SpanBuilder spanBuilder(String spanName) {
			return current().spanBuilder(spanName);
		}

	}

	private record Resolved(OpenTelemetry owner, Tracer tracer) {
	}

	private final class DelegatingTextMapPropagator implements TextMapPropagator {

		private TextMapPropagator current() {
			return DelegatingOpenTelemetry.this.delegate.getPropagators().getTextMapPropagator();
		}

		@Override
		public Collection<String> fields() {
			return current().fields();
		}

		@Override
		public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
			current().inject(context, carrier, setter);
		}

		@Override
		public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
			return current().extract(context, carrier, getter);
		}

	}

}
