package com.claudecode.services.model;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.attachment.FeatureFlag;
import com.claudecode.core.attachment.FeatureFlagRegistry;
import com.claudecode.core.config.EnvValidation;
import com.claudecode.core.process.SubprocessEnvironment;

import java.util.Locale;

/**
 * Per-model output-token bounds and the {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} resolver.
 */
public final class ModelOutputTokens {


    private static final long DEFAULT_TOKENS = 32_000;
    private static final long DEFAULT_UPPER_LIMIT = 64_000;

    static final String ENV_VAR = "CLAUDE_CODE_MAX_OUTPUT_TOKENS";

    private ModelOutputTokens() {}

    /** Default + upper-limit output tokens for a model. */
    public record Bounds(long defaultTokens, long upperLimit) {}

    /**
     * Per-model output-token bounds.
     */
    public static Bounds getModelMaxOutputTokens(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(m, "opus-5") || Strings.CS.contains(m, "opus-4-6")) {
            return new Bounds(64_000, 128_000);
        } else if (Strings.CS.contains(m, "sonnet-4-6")) {
            return new Bounds(32_000, 128_000);
        } else if (Strings.CS.contains(m, "opus-4-5") || Strings.CS.contains(m, "sonnet-4") || Strings.CS.contains(m, "haiku-4")) {
            return new Bounds(32_000, 64_000);
        } else if (Strings.CS.contains(m, "opus-4-1") || Strings.CS.contains(m, "opus-4")) {
            return new Bounds(32_000, 32_000);
        } else if (Strings.CS.contains(m, "claude-3-opus")) {
            return new Bounds(4_096, 4_096);
        } else if (Strings.CS.contains(m, "claude-3-sonnet")) {
            return new Bounds(8_192, 8_192);
        } else if (Strings.CS.contains(m, "claude-3-haiku")) {
            return new Bounds(4_096, 4_096);
        } else if (Strings.CS.contains(m, "3-5-sonnet") || Strings.CS.contains(m, "3-5-haiku")) {
            return new Bounds(8_192, 8_192);
        } else if (Strings.CS.contains(m, "3-7-sonnet")) {
            return new Bounds(32_000, 64_000);
        }
        return new Bounds(DEFAULT_TOKENS, DEFAULT_UPPER_LIMIT);
    }

    /**
     * The effective request {@code max_tokens} for a model, applying the {@code
     * CLAUDE_CODE_MAX_OUTPUT_TOKENS} env override against that model's bounds.
     */
    public static long getMaxOutputTokensForModel(String model) {
        return getMaxOutputTokensForModel(model, FeatureFlagRegistry.allOff());
    }

    /** Resolves a model-derived default using the supplied real feature flags. */
    public static long getMaxOutputTokensForModel(String model, FeatureFlagRegistry flags) {
        Bounds b = getModelMaxOutputTokens(model);
        long defaultTokens = defaultTokens(b, flags);
        return EnvValidation.validateBoundedIntEnvVar(
            ENV_VAR, SubprocessEnvironment.get(ENV_VAR), defaultTokens, b.upperLimit()).effective();
    }


    public static long resolveMaxOutputTokens(String model, long cliFallback) {
        return resolveMaxOutputTokens(model, cliFallback, FeatureFlagRegistry.allOff(), true);
    }




    public static long resolveMaxOutputTokens(String model, long cliFallback,
                                               FeatureFlagRegistry flags,
                                               boolean explicitMaxTokens) {
        if (!explicitMaxTokens) {
            return getMaxOutputTokensForModel(model, flags);
        }
        String env = SubprocessEnvironment.get(ENV_VAR);
        if (StringUtils.isBlank(env)) {
            return cliFallback;
        }
        Bounds b = getModelMaxOutputTokens(model);
        return EnvValidation.validateBoundedIntEnvVar(ENV_VAR, env, b.defaultTokens(), b.upperLimit())
            .effective();
    }

    private static long defaultTokens(Bounds bounds, FeatureFlagRegistry flags) {
        if (flags != null && flags.isEnabled(FeatureFlag.MAX_OUTPUT_TOKENS_SLOT)) {
            return Math.min(bounds.defaultTokens(), 8_000L);
        }
        return bounds.defaultTokens();
    }
}
