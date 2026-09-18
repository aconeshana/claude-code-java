package com.claudecode.core.engine;

import com.claudecode.core.config.CachedFeatureValues;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.model.AnthropicProviderUrls;
import com.claudecode.core.process.SubprocessEnvironment;
import org.apache.commons.lang3.StringUtils;

/**
 * Session-stable gates for fields added to model-visible tool schemas.
 */
public final class ToolSchemaGate {

    private static final String FGTS_FEATURE = "tengu_fgts";
    private static final String FGTS_ENV = "CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING";

    private ToolSchemaGate() {}

    /** Returns whether {@code eager_input_streaming: true} is safe to emit. */
    public static boolean eagerInputStreamingEnabled() {
        return eagerInputStreamingEnabled(null);
    }

    /**
     * Model-aware form: {@code baseUrlOverride} (a model.json catalogue lookup for
     * the tool registry's configured model) takes priority over the process-wide
     * {@code ANTHROPIC_BASE_URL} when non-blank.
     */
    public static boolean eagerInputStreamingEnabled(String baseUrlOverride) {
        if (EnvUtils.isEnvTruthy(SubprocessEnvironment.get(
                "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"))) {
            return false;
        }
        if (EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_BEDROCK"))
                || EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_VERTEX"))
                || EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_FOUNDRY"))) {
            return false;
        }
        String baseUrl = StringUtils.isNotBlank(baseUrlOverride)
            ? baseUrlOverride : SubprocessEnvironment.get("ANTHROPIC_BASE_URL");
        if (!AnthropicProviderUrls.isFirstPartyBaseUrl(baseUrl)) return false;
        if (EnvUtils.isEnvTruthy(SubprocessEnvironment.get(FGTS_ENV))) return true;
        return cachedFeature(FGTS_FEATURE);
    }

    private static boolean cachedFeature(String name) {
        return CachedFeatureValues.bool(name, false);
    }
}
