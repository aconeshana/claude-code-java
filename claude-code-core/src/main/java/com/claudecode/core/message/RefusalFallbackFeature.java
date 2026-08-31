package com.claudecode.core.message;

import org.apache.commons.lang3.StringUtils;

import java.util.function.Function;

/**
 * The one switch that turns the client-driven refusal-fallback lane off.
 */
public final class RefusalFallbackFeature {

    private static final String DISABLE_ENV = "CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK";

    private RefusalFallbackFeature() {}

    /**
     * Whether the refusal-fallback lane is on. Any non-empty value of
     * {@code CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK} turns it off.
     *
     * @param envLookup environment reader; {@code null} reads as an empty one
     */
    public static boolean enabled(Function<String, String> envLookup) {
        return envLookup == null || StringUtils.isEmpty(envLookup.apply(DISABLE_ENV));
    }

    /** Process-environment overload. */
    public static boolean enabled() {
        return enabled(System::getenv);
    }

    /** Whether the {@code switchModelsOnFlag} settings row is offered. */
    public static boolean settingVisible(Function<String, String> envLookup) {
        return enabled(envLookup);
    }

    /** Process-environment overload. */
    public static boolean settingVisible() {
        return enabled();
    }
}
