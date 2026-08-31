package com.claudecode.services.config;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


public final class SimpleSystemPromptFeatureGate {

    private static final String FEATURE = "tengu_velvet_cascade";

    private SimpleSystemPromptFeatureGate() { }

    /** Returns the cached model substrings that force the Harness profile. */
    public static List<String> modelPatterns() {
        JsonNode global = null;
        try {
            if (Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                global = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
            }
        } catch (Exception _) { }
        return evaluate(SubprocessEnvironment.snapshot(), global);
    }

    /** Package-private deterministic seam for cache/privacy tests. */
    static List<String> evaluate(Map<String, String> env, JsonNode globalConfig) {
        if (growthBookDisabled(env) || globalConfig == null) return List.of();

        JsonNode feature = globalConfig.path("cachedGrowthBookFeatures").get(FEATURE);
        JsonNode models = feature != null && feature.isObject() ? feature.get("models") : null;
        if (models == null || !models.isArray()) return List.of();

        List<String> patterns = new ArrayList<>();
        for (JsonNode model : models) {
            if (model.isTextual() && !StringUtils.isBlank(model.asText())) {
                patterns.add(model.asText());
            }
        }
        return List.copyOf(patterns);
    }

    private static boolean growthBookDisabled(Map<String, String> env) {
        return Strings.CS.equals("test", env.get("NODE_ENV"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_BEDROCK"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_VERTEX"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_FOUNDRY"))
            || env.containsKey("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC")
            || env.containsKey("DISABLE_TELEMETRY");
    }
}
