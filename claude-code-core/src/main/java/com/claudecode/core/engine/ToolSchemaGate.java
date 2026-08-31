package com.claudecode.core.engine;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.model.AnthropicProviderUrls;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;

/**
 * Session-stable gates for fields added to model-visible tool schemas.
 */
public final class ToolSchemaGate {

    private static final String FGTS_FEATURE = "tengu_fgts";
    private static final String FGTS_ENV = "CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING";

    private ToolSchemaGate() {}

    /** Returns whether {@code eager_input_streaming: true} is safe to emit. */
    public static boolean eagerInputStreamingEnabled() {
        if (EnvUtils.isEnvTruthy(SubprocessEnvironment.get(
                "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"))) {
            return false;
        }
        if (EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_BEDROCK"))
                || EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_VERTEX"))
                || EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_FOUNDRY"))) {
            return false;
        }
        String baseUrl = SubprocessEnvironment.get("ANTHROPIC_BASE_URL");
        if (!AnthropicProviderUrls.isFirstPartyBaseUrl(baseUrl)) return false;
        if (EnvUtils.isEnvTruthy(SubprocessEnvironment.get(FGTS_ENV))) return true;
        return cachedFeature(FGTS_FEATURE);
    }

    private static boolean cachedFeature(String name) {
        try {
            if (!Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) return false;
            JsonNode global = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
            JsonNode value = global == null ? null
                : global.path("cachedGrowthBookFeatures").get(name);
            return value != null && value.isBoolean() && value.asBoolean();
        } catch (Exception _) {
            return false;
        }
    }
}
