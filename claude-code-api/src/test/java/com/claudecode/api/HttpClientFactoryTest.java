package com.claudecode.api;

import com.claudecode.http.SharedHttpClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpClientFactoryTest {

    @Test
    void apiClientSharesCommonTransportResources() {
        OkHttpClient base = SharedHttpClient.shared();
        OkHttpClient api = HttpClientFactory.anthropicStreaming();

        assertSame(base.connectionPool(), api.connectionPool());
        assertSame(base.dispatcher(), api.dispatcher());
    }

    @Test
    void anthropicStreamingHasHeaderDeadlineButNoBodyIdleDeadline() {
        OkHttpClient api = HttpClientFactory.anthropicStreaming();

        assertEquals(Duration.ofMinutes(10).toMillis(), api.connectTimeoutMillis());
        assertEquals(Duration.ZERO.toMillis(), api.readTimeoutMillis());
        assertEquals(Duration.ofMinutes(10).toMillis(), api.writeTimeoutMillis());
        assertEquals(Duration.ZERO.toMillis(), api.callTimeoutMillis());
        assertTrue(api.interceptors().stream().anyMatch(RetryInterceptor.class::isInstance));
        assertTrue(SharedHttpClient.shared().interceptors().isEmpty());
    }

    @Test
    void anthropicNonStreamingUsesApiTimeoutAsTotalDeadline() {
        OkHttpClient api = HttpClientFactory.anthropicNonStreaming();

        assertEquals(Duration.ofMinutes(10).toMillis(), api.connectTimeoutMillis());
        assertEquals(Duration.ofMinutes(10).toMillis(), api.readTimeoutMillis());
        assertEquals(Duration.ofMinutes(10).toMillis(), api.writeTimeoutMillis());
        assertEquals(Duration.ofMinutes(10).toMillis(), api.callTimeoutMillis());
        assertTrue(api.interceptors().stream().anyMatch(RetryInterceptor.class::isInstance));
    }

    @Test
    void openAiProfilesUseTheReleasedRetryPolicy() {
        OkHttpClient streaming = HttpClientFactory.openAiStreaming();
        OkHttpClient nonStreaming = HttpClientFactory.openAiNonStreaming();

        assertTrue(streaming.interceptors().stream().anyMatch(RetryInterceptor.class::isInstance));
        assertTrue(nonStreaming.interceptors().stream().anyMatch(RetryInterceptor.class::isInstance));
        assertEquals(0, streaming.readTimeoutMillis());
        assertEquals(0, streaming.callTimeoutMillis());
        assertEquals(Duration.ofMinutes(10).toMillis(), nonStreaming.callTimeoutMillis());
    }

    @Test
    void timeoutAndWatchdogEnvironmentValuesUseTsDefaultsAndValidation() {
        assertEquals(Duration.ofMinutes(10), ApiTimeouts.resolveApiTimeout(null));
        assertEquals(Duration.ofMinutes(10), ApiTimeouts.resolveApiTimeout("invalid"));
        assertEquals(Duration.ofSeconds(42), ApiTimeouts.resolveApiTimeout("42000"));

        assertFalse(ApiTimeouts.resolveWatchdog(null, null).enabled());
        assertEquals(Duration.ofSeconds(90),
            ApiTimeouts.resolveWatchdog("true", null).idleTimeout());
        assertEquals(Duration.ofSeconds(12),
            ApiTimeouts.resolveWatchdog("1", "12000").idleTimeout());
        assertTrue(ApiTimeouts.resolveWatchdog("on", null).enabled());
    }

    @Test
    void nonStreamingFallbackTimeoutMirrorsTsDefaults() {

        // fallback and the streaming path share one ceiling, remote sessions
        // drop to 120s to stay under CCR's container idle-kill, everything else
        // gets 300s — deliberately under the API's 10-minute non-streaming
        // boundary so a wedged backend yields a clean client-side timeout.
        assertEquals(Duration.ofSeconds(300),
            ApiTimeouts.resolveNonStreamingFallbackTimeout(null, null));
        assertEquals(Duration.ofSeconds(120),
            ApiTimeouts.resolveNonStreamingFallbackTimeout(null, "1"));
        assertEquals(Duration.ofSeconds(42),
            ApiTimeouts.resolveNonStreamingFallbackTimeout("42000", "1"));
        assertEquals(Duration.ofSeconds(300),
            ApiTimeouts.resolveNonStreamingFallbackTimeout("invalid", null));
        assertNotEquals(ApiTimeouts.resolveApiTimeout(null),
            ApiTimeouts.resolveNonStreamingFallbackTimeout(null, null),
            "the fallback must not inherit the 10-minute streaming default");
    }
}
