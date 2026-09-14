package com.claudecode.api;

import com.claudecode.core.config.CachedFeatureValues;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.model.AnthropicProviderUrls;
import com.claudecode.core.process.SubprocessEnvironment;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;

/**
 * Environment-backed API timeout policy, covering both streaming watchdogs.
 *
 * <ul>
 *   <li>{@code API_TIMEOUT_MS}, default 600 seconds, and the
 *       streaming-to-non-streaming recovery timeout.</li>
 *   <li>the event-level idle watchdog
 *       ({@code CLAUDE_ENABLE_STREAM_WATCHDOG}, enabled unless explicitly
 *       falsy; {@code CLAUDE_STREAM_IDLE_TIMEOUT_MS} floored at 300s).</li>
 *   <li>the byte-level idle watchdog
 *       ({@code CLAUDE_ENABLE_BYTE_WATCHDOG}, enabled by default but
 *       additionally gated on a first-party provider;
 *       {@code CLAUDE_BYTE_STREAM_IDLE_TIMEOUT_MS} &gt;
 *       {@code CLAUDE_STREAM_IDLE_TIMEOUT_MS} &gt; the 180s first-party
 *       default, clamped to [10s, 30min]).</li>
 * </ul>
 */
public final class ApiTimeouts {

    private static final Duration DEFAULT_API_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_FALLBACK_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration REMOTE_FALLBACK_TIMEOUT = Duration.ofSeconds(120);

    /** {@code Oja()}'s floor: the event-level idle window never drops below this. */
    private static final Duration MIN_STREAM_IDLE_TIMEOUT = Duration.ofMinutes(5);
    /** {@code k8S}: the first-party default byte-level idle window. */
    private static final Duration FIRST_PARTY_BYTE_IDLE_TIMEOUT = Duration.ofSeconds(180);
    /** {@code E8S}/{@code C8S}: clamp bounds for the resolved byte-level window. */
    private static final Duration MIN_BYTE_IDLE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_BYTE_IDLE_TIMEOUT = Duration.ofMinutes(30);

    private static final String BYTE_IDLE_FEATURE = "tengu_byte_stream_idle_timeout_ms";
    private static final String WATCHDOG_DEFAULT_FEATURE = "tengu_stream_watchdog_default_on";

    private ApiTimeouts() {}

    static Duration apiTimeout() {
        return resolveApiTimeout(SubprocessEnvironment.get("API_TIMEOUT_MS"));
    }

    /**
     * Per-attempt timeout for the non-streaming fallback.
     */
    public static Duration nonStreamingFallbackTimeout() {
        return resolveNonStreamingFallbackTimeout(
            SubprocessEnvironment.get("API_TIMEOUT_MS"),
            SubprocessEnvironment.get("CLAUDE_CODE_REMOTE"));
    }

    /** Pure resolution, split out so tests need not mutate the process environment. */
    static Duration resolveNonStreamingFallbackTimeout(String apiTimeoutRaw, String remoteRaw) {
        return positiveMillis(apiTimeoutRaw,
            EnvUtils.isEnvTruthy(remoteRaw)
                ? REMOTE_FALLBACK_TIMEOUT : DEFAULT_FALLBACK_TIMEOUT);
    }

    static StreamWatchdog watchdog() {
        return resolveWatchdog(
            SubprocessEnvironment.get("CLAUDE_ENABLE_STREAM_WATCHDOG"),
            SubprocessEnvironment.get("CLAUDE_STREAM_IDLE_TIMEOUT_MS"));
    }

    static Duration resolveApiTimeout(String raw) {
        return positiveMillis(raw, DEFAULT_API_TIMEOUT);
    }

    /**
     * Event-level watchdog policy. The released client reads its switch as a
     * {@code triBool} defaulting to enabled, so only an explicit falsy value
     * disables it, and the idle window is floored at 300s rather than taking the
     * configured value verbatim.
     */
    static StreamWatchdog resolveWatchdog(String enabledRaw, String timeoutRaw) {
        return resolveWatchdog(enabledRaw, timeoutRaw, remoteWatchdogDefaultOn());
    }

    /** Resolution with the remote-config default supplied, so tests stay off the shared config cache. */
    static StreamWatchdog resolveWatchdog(String enabledRaw, String timeoutRaw,
                                          boolean remoteDefault) {
        return new StreamWatchdog(
            resolveTriBool(enabledRaw, remoteDefault),
            streamIdleTimeout(timeoutRaw));
    }

    /** {@code nt("tengu_stream_watchdog_default_on", true)}. */
    static boolean remoteWatchdogDefaultOn() {
        return CachedFeatureValues.bool(WATCHDOG_DEFAULT_FEATURE, true);
    }

    /** {@code Oja()}: {@code max(CLAUDE_STREAM_IDLE_TIMEOUT_MS || 0, 300000)}. */
    static Duration streamIdleTimeout(String raw) {
        Duration resolved = positiveMillis(raw, MIN_STREAM_IDLE_TIMEOUT);
        return resolved.compareTo(MIN_STREAM_IDLE_TIMEOUT) < 0
            ? MIN_STREAM_IDLE_TIMEOUT : resolved;
    }

    /**
     * Byte-level watchdog policy for the provider about to be called.
     *
     * <p>The switch is a {@code triBool} defaulting to enabled, and the resolved
     * policy is additionally gated on a first-party provider — a proxy or
     * third-party {@code ANTHROPIC_BASE_URL} reports {@code om()} false, which
     * turns this tier off entirely. Bedrock is the one non-first-party provider
     * the released client still watches, behind its own opt-in switch.
     */
    static ByteWatchdog byteWatchdog(ApiConfig.ApiProvider provider, String baseUrl) {
        return resolveByteWatchdog(
            provider, baseUrl,
            SubprocessEnvironment.get("CLAUDE_ENABLE_BYTE_WATCHDOG"),
            SubprocessEnvironment.get("CLAUDE_ENABLE_BYTE_WATCHDOG_BEDROCK"),
            SubprocessEnvironment.get("CLAUDE_BYTE_STREAM_IDLE_TIMEOUT_MS"),
            SubprocessEnvironment.get("CLAUDE_STREAM_IDLE_TIMEOUT_MS"));
    }

    static ByteWatchdog resolveByteWatchdog(ApiConfig.ApiProvider provider, String baseUrl,
                                            String enabledRaw, String bedrockEnabledRaw,
                                            String byteTimeoutRaw, String streamTimeoutRaw) {
        return resolveByteWatchdog(provider, baseUrl, enabledRaw, bedrockEnabledRaw,
            byteTimeoutRaw, streamTimeoutRaw, remoteWatchdogDefaultOn(), null);
    }

    static ByteWatchdog resolveByteWatchdog(ApiConfig.ApiProvider provider, String baseUrl,
                                            String enabledRaw, String bedrockEnabledRaw,
                                            String byteTimeoutRaw, String streamTimeoutRaw,
                                            boolean remoteDefault, Long remoteByteIdleMs) {
        if (!resolveTriBool(enabledRaw, remoteDefault)) {
            return ByteWatchdog.DISABLED;
        }
        if (!byteWatchdogProviderEligible(provider, baseUrl, bedrockEnabledRaw)) {
            return ByteWatchdog.DISABLED;
        }
        Duration streamIdle = isPositive(streamTimeoutRaw)
            ? streamIdleTimeout(streamTimeoutRaw) : null;
        return new ByteWatchdog(true,
            resolveByteIdleTimeout(provider, byteTimeoutRaw, streamIdle, remoteByteIdleMs));
    }

    /**
     * {@code A8S}: an explicit {@code CLAUDE_BYTE_STREAM_IDLE_TIMEOUT_MS} wins.
     * Otherwise the byte window is only derived — and the remote
     * {@code tengu_byte_stream_idle_timeout_ms} lookup only consulted — when the
     * caller did <em>not</em> configure a positive
     * {@code CLAUDE_STREAM_IDLE_TIMEOUT_MS}; with one configured, the byte window
     * stays the event window ({@code t}). The result is clamped to [10s, 30min].
     *
     * @param streamIdleOrNull the positive {@code CLAUDE_STREAM_IDLE_TIMEOUT_MS}
     *        window, or null when that variable was unset/non-positive
     */
    static Duration resolveByteIdleTimeout(ApiConfig.ApiProvider provider,
                                           String byteTimeoutRaw,
                                           Duration streamIdleOrNull) {
        return resolveByteIdleTimeout(provider, byteTimeoutRaw, streamIdleOrNull,
            CachedFeatureValues.number(BYTE_IDLE_FEATURE));
    }

    static Duration resolveByteIdleTimeout(ApiConfig.ApiProvider provider,
                                           String byteTimeoutRaw,
                                           Duration streamIdleOrNull,
                                           Long remoteByteIdleMs) {
        // t: the event-level window, which is floored at 300s even when the
        // variable is unset (Oja()).
        Duration eventWindow = streamIdleOrNull != null
            ? streamIdleOrNull : MIN_STREAM_IDLE_TIMEOUT;
        // r: the fallback value, keyed on the provider rather than the caller.
        Duration providerDefault = provider == ApiConfig.ApiProvider.ANTHROPIC
            ? FIRST_PARTY_BYTE_IDLE_TIMEOUT : eventWindow;
        Duration resolved;
        Long explicitByte = parseLong(byteTimeoutRaw);
        if (explicitByte != null && explicitByte > 0) {
            resolved = Duration.ofMillis(explicitByte);
        } else if (streamIdleOrNull == null) {
            resolved = remoteByteIdleMs != null && remoteByteIdleMs > 0
                ? Duration.ofMillis(remoteByteIdleMs) : providerDefault;
        } else {
            resolved = eventWindow;
        }
        if (resolved.compareTo(MIN_BYTE_IDLE_TIMEOUT) < 0) return MIN_BYTE_IDLE_TIMEOUT;
        return resolved.compareTo(MAX_BYTE_IDLE_TIMEOUT) > 0
            ? MAX_BYTE_IDLE_TIMEOUT : resolved;
    }

    /**
     * {@code Lja}: {@code cif() && iif(provider) && iif(currentProvider)}. Java
     * has no {@code anthropicAws} provider, so {@code Mja} reduces to "the
     * Anthropic provider is pointed at the first-party host".
     */
    static boolean byteWatchdogProviderEligible(ApiConfig.ApiProvider provider, String baseUrl,
                                                String bedrockEnabledRaw) {
        if (provider == ApiConfig.ApiProvider.ANTHROPIC) {
            return AnthropicProviderUrls.isFirstPartyBaseUrl(baseUrl);
        }
        if (provider == ApiConfig.ApiProvider.BEDROCK) {
            return EnvUtils.isEnvTruthy(bedrockEnabledRaw);
        }
        return false;
    }

    /**
     * {@code nt(name, default)} for a {@code triBool} switch: an explicit falsy
     * value disables, an explicit truthy value enables, and anything else (unset
     * or unrecognized) falls back to the cached remote value and then to
     * {@code fallback}.
     */
    static boolean resolveTriBool(String raw, boolean remoteDefault) {
        if (EnvUtils.isEnvDefinedFalsy(raw)) return false;
        if (EnvUtils.isEnvTruthy(raw)) return true;
        return remoteDefault;
    }

    static boolean isPositive(String raw) {
        Long value = parseLong(raw);
        return value != null && value > 0;
    }

    private static Long parseLong(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static Duration positiveMillis(String raw, Duration fallback) {
        Long millis = parseLong(raw);
        return millis != null && millis > 0 ? Duration.ofMillis(millis) : fallback;
    }

    record StreamWatchdog(boolean enabled, Duration idleTimeout) {
        Duration warningTimeout() {
            return idleTimeout.dividedBy(2);
        }
    }

    /** Byte-level idle policy; {@link #idleTimeout()} is meaningless when disabled. */
    record ByteWatchdog(boolean enabled, Duration idleTimeout) {
        static final ByteWatchdog DISABLED = new ByteWatchdog(false, Duration.ZERO);
    }
}
