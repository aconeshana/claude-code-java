package com.claudecode.api;

import okhttp3.Request;

import java.time.Duration;

/**
 * Per-request byte-level watchdog setting, carried as an OkHttp request tag.
 *
 * <p>The watchdog has to be applied to the response body <em>inside</em> the
 * interceptor chain: {@code okhttp-sse}'s {@code RealEventSource} captures
 * {@code body.source()} before it invokes {@code onOpen}, so wrapping the body
 * from the {@link EventSourceStreamBridge} callback would be too late to affect
 * the reader. Tagging the request keeps provider resolution with the client that
 * knows the provider, while leaving the transport to apply it.
 */
record ByteWatchdogTag(Duration idleTimeout) {

    /** Arms the byte-level watchdog for {@code request}, or returns it unchanged when disabled. */
    static Request apply(Request request, ApiTimeouts.ByteWatchdog watchdog) {
        if (!watchdog.enabled()) return request;
        return request.newBuilder()
            .tag(ByteWatchdogTag.class, new ByteWatchdogTag(watchdog.idleTimeout()))
            .build();
    }
}
