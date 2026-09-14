package com.claudecode.api;

import okhttp3.Interceptor;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Applies the byte-level idle watchdog to streaming responses inside the
 * interceptor chain.
 *
 * <p>{@code okhttp-sse}'s {@code RealEventSource} reads {@code body.source()}
 * before it reports {@code onOpen}, so the wrapping has to happen here rather
 * than in a {@link EventSourceStreamBridge} callback. The setting travels on the
 * request as a {@link ByteWatchdogTag}, which the constructing client resolves
 * from its own provider identity.
 *
 * <ul>
 *   <li>the byte-level stream watchdog that wraps a
 *       streaming response body and aborts it once the resolved idle window
 *       elapses without a byte.</li>
 * </ul>
 */
final class ByteWatchdogInterceptor implements Interceptor {

    @Override
    public @NotNull Response intercept(@NotNull Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        ByteWatchdogTag tag = chain.request().tag(ByteWatchdogTag.class);
        if (tag == null) return response;
        return ByteWatchdogBody.wrap(response, new ApiTimeouts.ByteWatchdog(true, tag.idleTimeout()));
    }
}
