package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.Map;


public final class McpSkillsFeatureGate {

    private static final String FEATURE = "tengu_mcp_skills";

    private McpSkillsFeatureGate() { }

    public static boolean enabled() {
        JsonNode global = null;
        try {
            if (Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                global = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
            }
        } catch (Exception _) { }
        return evaluate(SubprocessEnvironment.snapshot(), global);
    }

    static boolean evaluate(Map<String, String> env, JsonNode globalConfig) {
        if (Strings.CS.equals("test", env.get("NODE_ENV"))
                || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_BEDROCK"))
                || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_VERTEX"))
                || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_FOUNDRY"))
                || env.containsKey("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC")
                || env.containsKey("DISABLE_TELEMETRY")) {
            return false;
        }
        JsonNode cached = globalConfig == null ? null
            : globalConfig.path("cachedGrowthBookFeatures").get(FEATURE);
        return cached != null && cached.isBoolean() && cached.asBoolean();
    }

}
