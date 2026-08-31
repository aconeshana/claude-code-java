package com.claudecode.tools.loop;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.Map;


public final class LoopFeatureGate {

    private static final String DYNAMIC = "tengu_kairos_loop_dynamic";
    private static final String DEFAULT_PROMPT = "tengu_kairos_loop_prompt";
    private static final String KEEPALIVE = "tengu_kairos_loop_keepalive";
    private static final String PERSISTENT = "tengu_kairos_loop_persistent";

    private final boolean dynamicEnabled;
    private final boolean defaultPromptEnabled;
    private final boolean keepaliveEnabled;
    private final boolean persistentEnabled;

    private LoopFeatureGate(boolean dynamicEnabled, boolean defaultPromptEnabled,
                            boolean keepaliveEnabled, boolean persistentEnabled) {
        this.dynamicEnabled = dynamicEnabled;
        this.defaultPromptEnabled = defaultPromptEnabled;
        this.keepaliveEnabled = keepaliveEnabled;
        this.persistentEnabled = persistentEnabled;
    }

    public static LoopFeatureGate system() {
        JsonNode global = null;
        try {
            if (Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                global = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
            }
        } catch (Exception _) { }
        return evaluate(SubprocessEnvironment.snapshot(), global);
    }

    static LoopFeatureGate evaluate(Map<String, String> env, JsonNode globalConfig) {
        boolean growthBookDisabled = Strings.CS.equals("test", env.get("NODE_ENV"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_BEDROCK"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_VERTEX"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_FOUNDRY"))
            || env.containsKey("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC")
            || env.containsKey("DISABLE_TELEMETRY");
        JsonNode features = growthBookDisabled || globalConfig == null
            ? null : globalConfig.path("cachedGrowthBookFeatures");
        return new LoopFeatureGate(
            booleanFeature(features, DYNAMIC),
            booleanFeature(features, DEFAULT_PROMPT),
            EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_LOOP_KEEPALIVE"))
                || booleanFeature(features, KEEPALIVE),
            EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_LOOP_PERSISTENT"))
                || booleanFeature(features, PERSISTENT));
    }

    private static boolean booleanFeature(JsonNode features, String name) {
        if (features == null) return false;
        JsonNode value = features.get(name);
        return value != null && value.isBoolean() && value.asBoolean();
    }

    public boolean dynamicEnabled() { return dynamicEnabled; }
    public boolean defaultPromptEnabled() { return defaultPromptEnabled; }
    public boolean keepaliveEnabled() { return keepaliveEnabled; }
    public boolean persistentEnabled() { return persistentEnabled; }
}
