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
    }

    @Test
    void streamWatchdogIsOnUnlessExplicitlyDisabledAndFloorsItsIdleWindow() {

        // Oja(): max(CLAUDE_STREAM_IDLE_TIMEOUT_MS || 0, 300000), so a small
        // configured window is raised rather than taken verbatim.
        assertTrue(ApiTimeouts.resolveWatchdog(null, null, true).enabled());
        assertEquals(Duration.ofMinutes(5),
            ApiTimeouts.resolveWatchdog(null, null, true).idleTimeout());
        assertEquals(Duration.ofMinutes(5),
            ApiTimeouts.resolveWatchdog(null, "12000", true).idleTimeout());
        assertEquals(Duration.ofSeconds(600),
            ApiTimeouts.resolveWatchdog(null, "600000", true).idleTimeout());
        assertTrue(ApiTimeouts.resolveWatchdog("on", null, true).enabled());
        assertTrue(ApiTimeouts.resolveWatchdog("1", null, true).enabled());
        assertFalse(ApiTimeouts.resolveWatchdog("off", null, true).enabled());
        assertFalse(ApiTimeouts.resolveWatchdog("0", null, true).enabled());
    }

    @Test
    void streamWatchdogSwitchFallsBackToTheRemoteDefaultWhenUnset() {
        assertFalse(ApiTimeouts.resolveWatchdog(null, null, false).enabled());
        assertTrue(ApiTimeouts.resolveWatchdog("true", null, false).enabled(),
            "an explicit truthy switch overrides a remote default of off");
    }

    @Test
    void byteWatchdogIsFirstPartyOnlyAndClampsItsIdleWindow() {
        var provider = ApiConfig.ApiProvider.ANTHROPIC;

        // A third-party ANTHROPIC_BASE_URL reports om() false, which disables the
        // byte-level tier even though the switch itself defaults to on.
        assertFalse(ApiTimeouts.resolveByteWatchdog(
            provider, "https://gateway.example.com", null, null, null, null, true, null).enabled());
        assertFalse(ApiTimeouts.resolveByteWatchdog(
            provider, "https://api.anthropic.com", "off", null, null, null, true, null).enabled());

        var firstParty = ApiTimeouts.resolveByteWatchdog(
            provider, "https://api.anthropic.com", null, null, null, null, true, null);
        assertTrue(firstParty.enabled());
        assertEquals(Duration.ofSeconds(180), firstParty.idleTimeout());

        // An explicit byte timeout wins, and the resolved window is clamped.
        assertEquals(Duration.ofSeconds(45), ApiTimeouts.resolveByteWatchdog(
            provider, "https://api.anthropic.com", null, null, "45000", null, true, null)
            .idleTimeout());
        assertEquals(Duration.ofSeconds(10), ApiTimeouts.resolveByteWatchdog(
            provider, "https://api.anthropic.com", null, null, "1", null, true, null)
            .idleTimeout());
        assertEquals(Duration.ofMinutes(30), ApiTimeouts.resolveByteWatchdog(
            provider, "https://api.anthropic.com", null, null, "99999999", null, true, null)
            .idleTimeout());

        // A positive stream idle window replaces the provider default and, being
        // positive, suppresses the remote byte-idle lookup entirely.
        assertEquals(Duration.ofMinutes(5), ApiTimeouts.resolveByteWatchdog(
            provider, "https://api.anthropic.com", null, null, null, "1000", true, 99_000L)
            .idleTimeout());
    }

    @Test
    void byteWatchdogUsesTheRemoteDefaultWhenNeitherTimeoutIsConfigured() {
        assertEquals(Duration.ofSeconds(99), ApiTimeouts.resolveByteWatchdog(
            ApiConfig.ApiProvider.ANTHROPIC, "https://api.anthropic.com",
            null, null, null, null, true, 99_000L).idleTimeout());
    }

    @Test
    void byteWatchdogOnlyWatchesBedrockBehindItsOwnOptIn() {
        var provider = ApiConfig.ApiProvider.BEDROCK;

        assertFalse(ApiTimeouts.resolveByteWatchdog(
            provider, null, null, null, null, null, true, null).enabled());
        assertTrue(ApiTimeouts.resolveByteWatchdog(
            provider, null, null, "true", null, null, true, null).enabled());
        assertFalse(ApiTimeouts.resolveByteWatchdog(
            ApiConfig.ApiProvider.OPENAI_COMPAT, null, null, "true", null, null, true, null)
            .enabled());
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
