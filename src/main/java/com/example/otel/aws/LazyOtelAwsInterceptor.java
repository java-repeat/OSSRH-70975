package com.example.otel.aws;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Optional;

import org.reactivestreams.Publisher;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;

/**
 * Global AWS SDK v2 interceptor that traces every client, including ones built inside
 * libraries, without touching {@code GlobalOpenTelemetry}.
 *
 * <p>
 * The AWS SDK instantiates this class through its own SPI file
 * ({@code software/amazon/awssdk/global/handlers/execution.interceptors}) for every client.
 * It holds no OpenTelemetry state itself; Spring hands it the real interceptor (built from
 * Boot's {@code OpenTelemetry} bean) via {@link #bind}. Until then, calls pass through
 * untraced, and nothing is permanently bound to a no-op.
 *
 * <p>
 * The delegate is captured once in {@link #beforeExecution} and pinned to the call's
 * {@link ExecutionAttributes}, so a single request never mixes "bound" and "unbound" hooks
 * even if binding happens mid-flight.
 */
public final class LazyOtelAwsInterceptor implements ExecutionInterceptor {

	private static final ExecutionAttribute<ExecutionInterceptor> DELEGATE = new ExecutionAttribute<>(
			LazyOtelAwsInterceptor.class.getName() + ".delegate");

	private static volatile ExecutionInterceptor delegate;

	/** Called by Spring once Boot's OpenTelemetry bean exists. */
	static void bind(ExecutionInterceptor interceptor) {
		delegate = interceptor;
	}

	/** Called on context close, only if the current delegate is still the one we bound. */
	static void unbind(ExecutionInterceptor interceptor) {
		if (delegate == interceptor) {
			delegate = null;
		}
	}

	static boolean isBound() {
		return delegate != null;
	}

	private static ExecutionInterceptor pinned(ExecutionAttributes attributes) {
		return attributes.getAttribute(DELEGATE);
	}

	@Override
	public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
		ExecutionInterceptor current = delegate;
		if (current == null) {
			return;
		}
		attributes.putAttribute(DELEGATE, current);
		current.beforeExecution(context, attributes);
	}

	@Override
	public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		return (d != null) ? d.modifyRequest(context, attributes) : context.request();
	}

	@Override
	public void beforeMarshalling(Context.BeforeMarshalling context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		if (d != null) {
			d.beforeMarshalling(context, attributes);
		}
	}

	@Override
	public void afterMarshalling(Context.AfterMarshalling context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		if (d != null) {
			d.afterMarshalling(context, attributes);
		}
	}

	@Override
	public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		return (d != null) ? d.modifyHttpRequest(context, attributes) : context.httpRequest();
	}

	@Override
	public Optional<RequestBody> modifyHttpContent(Context.ModifyHttpRequest context,
			ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		return (d != null) ? d.modifyHttpContent(context, attributes) : context.requestBody();
	}

	@Override
	public Optional<AsyncRequestBody> modifyAsyncHttpContent(Context.ModifyHttpRequest context,
			ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		return (d != null) ? d.modifyAsyncHttpContent(context, attributes) : context.asyncRequestBody();
	}

	@Override
	public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		if (d != null) {
			d.beforeTransmission(context, attributes);
		}
	}

	@Override
	public void afterTransmission(Context.AfterTransmission context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		if (d != null) {
			d.afterTransmission(context, attributes);
		}
	}

	@Override
	public SdkHttpResponse modifyHttpResponse(Context.ModifyHttpResponse context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		return (d != null) ? d.modifyHttpResponse(context, attributes) : context.httpResponse();
	}

	@Override
	public Optional<Publisher<ByteBuffer>> modifyAsyncHttpResponseContent(Context.ModifyHttpResponse context,
			ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		return (d != null) ? d.modifyAsyncHttpResponseContent(context, attributes) : context.responsePublisher();
	}

	@Override
	public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
			ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		return (d != null) ? d.modifyHttpResponseContent(context, attributes) : context.responseBody();
	}

	@Override
	public void beforeUnmarshalling(Context.BeforeUnmarshalling context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		if (d != null) {
			d.beforeUnmarshalling(context, attributes);
		}
	}

	@Override
	public void afterUnmarshalling(Context.AfterUnmarshalling context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		if (d != null) {
			d.afterUnmarshalling(context, attributes);
		}
	}

	@Override
	public SdkResponse modifyResponse(Context.ModifyResponse context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		return (d != null) ? d.modifyResponse(context, attributes) : context.response();
	}

	@Override
	public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		if (d != null) {
			d.afterExecution(context, attributes);
		}
	}

	@Override
	public Throwable modifyException(Context.FailedExecution context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		return (d != null) ? d.modifyException(context, attributes) : context.exception();
	}

	@Override
	public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
		ExecutionInterceptor d = pinned(attributes);
		if (d != null) {
			d.onExecutionFailure(context, attributes);
		}
	}

}
