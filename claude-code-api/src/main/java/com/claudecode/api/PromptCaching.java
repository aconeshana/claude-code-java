package com.claudecode.api;

import java.util.Objects;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.process.SubprocessEnvironment;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class PromptCaching {

    private static final Logger log = LoggerFactory.getLogger(PromptCaching.class);

    @Explanation("A three-state override permits explicit enable or disable while unset keeps the five-minute default")
    public static final String JAVA_PROMPT_CACHE_MODE = "CLAUDE_CODE_JAVA_PROMPT_CACHE_MODE";

    public static final String DISABLE_PROMPT_CACHING = "DISABLE_PROMPT_CACHING";
    public static final String DISABLE_PROMPT_CACHING_HAIKU = "DISABLE_PROMPT_CACHING_HAIKU";
    public static final String DISABLE_PROMPT_CACHING_SONNET = "DISABLE_PROMPT_CACHING_SONNET";
    public static final String DISABLE_PROMPT_CACHING_OPUS = "DISABLE_PROMPT_CACHING_OPUS";

    private static final String ANTHROPIC_SMALL_FAST_MODEL = "ANTHROPIC_SMALL_FAST_MODEL";
    private static final ConfiguredModeLatch CONFIGURED_MODE =
        new ConfiguredModeLatch(() -> SubprocessEnvironment.get(JAVA_PROMPT_CACHE_MODE));

    private PromptCaching() {}

    /** Production overload — observes the real process environment. */
    public static boolean isEnabled(String model) {
        return isEnabled(model, SubprocessEnvironment::get);
    }

    /** Process-latched production decision used by the main request adapter. */
    public static CacheDecision resolve(String model) {
        return decision(CONFIGURED_MODE.get(), model, SubprocessEnvironment::get);
    }

    /** Pure decision path for tests and embedders that provide an environment snapshot. */
    public static CacheDecision resolve(String model, Function<String, String> env) {
        Objects.requireNonNull(env, "env");
        return decision(parseConfiguredMode(env.apply(JAVA_PROMPT_CACHE_MODE)), model, env);
    }

    /** Pure overload with an injectable env lookup — testable without side effects. */
    public static boolean isEnabled(String model, Function<String, String> env) {
        if (EnvUtils.isEnvTruthy(env.apply(DISABLE_PROMPT_CACHING))) return false;

        if (EnvUtils.isEnvTruthy(env.apply(DISABLE_PROMPT_CACHING_HAIKU))
                && Objects.equals(model, smallFastModel(env))) {
            return false;
        }
        if (EnvUtils.isEnvTruthy(env.apply(DISABLE_PROMPT_CACHING_SONNET))
                && Objects.equals(model, ModelNames.defaultMainLoopModel(env))) {
            return false;
        }
        return !EnvUtils.isEnvTruthy(env.apply(DISABLE_PROMPT_CACHING_OPUS))
          || !Objects.equals(model, ModelNames.defaultOpusModel(env));
    }


    private static String smallFastModel(Function<String, String> env) {
        String override = env.apply(ANTHROPIC_SMALL_FAST_MODEL);
        if (StringUtils.isNotBlank(override)) return override;
        return ModelNames.defaultHaikuModel(env);
    }

    private static CacheDecision decision(ConfiguredMode mode, String model,
                                          Function<String, String> env) {
        return switch (mode) {
            case OFF -> CacheDecision.disabled();
            case FIVE_MINUTES -> CacheDecision.fiveMinutes();
            case ONE_HOUR -> CacheDecision.oneHour();
            case LEGACY -> isEnabled(model, env)
                ? CacheDecision.fiveMinutes() : CacheDecision.disabled();
        };
    }

    static ConfiguredMode parseConfiguredMode(String raw) {
        if (StringUtils.isBlank(raw)) return ConfiguredMode.LEGACY;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> ConfiguredMode.OFF;
            case "5m" -> ConfiguredMode.FIVE_MINUTES;
            case "1h" -> ConfiguredMode.ONE_HOUR;
            default -> ConfiguredMode.LEGACY;
        };
    }

    enum ConfiguredMode { LEGACY, OFF, FIVE_MINUTES, ONE_HOUR }

    /** Immutable resolved request policy. Disabled decisions intentionally carry no TTL. */
    public record CacheDecision(boolean enabled, CreateMessageRequest.PromptCacheTtl ttl) {
        static CacheDecision disabled() { return new CacheDecision(false, null); }
        static CacheDecision fiveMinutes() {
            return new CacheDecision(true, CreateMessageRequest.PromptCacheTtl.FIVE_MINUTES);
        }
        static CacheDecision oneHour() {
            return new CacheDecision(true, CreateMessageRequest.PromptCacheTtl.ONE_HOUR);
        }
    }

    /** Lazy JVM-lifetime latch; intentionally has no production reset operation. */
    static final class ConfiguredModeLatch {
        private final Supplier<String> rawValue;
        private volatile ConfiguredMode latched;

        ConfiguredModeLatch(Supplier<String> rawValue) {
            this.rawValue = Objects.requireNonNull(rawValue, "rawValue");
        }

        ConfiguredMode get() {
            ConfiguredMode result = latched;
            if (result != null) return result;
            synchronized (this) {
                result = latched;
                if (result == null) {
                    String raw = rawValue.get();
                    result = parseConfiguredMode(raw);
                    if (result == ConfiguredMode.LEGACY && StringUtils.isNotBlank(raw)) {
                        log.warn("Ignoring invalid {} value '{}'; expected off, 5m, or 1h",
                            JAVA_PROMPT_CACHE_MODE, raw);
                    }
                    latched = result;
                }
                return result;
            }
        }
    }
}
