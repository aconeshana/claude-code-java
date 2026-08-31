package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.util.Map;


public final class AutoDreamFeatureGate {

    private static final String FEATURE = "tengu_onyx_plover";
    private static final double DEFAULT_MIN_HOURS = 24.0;
    private static final int DEFAULT_MIN_SESSIONS = 5;




    public record Schedule(double minHours, double minSessions) {}

    private AutoDreamFeatureGate() {}

    /** Resolve the effective gate from the process environment and global cache. */
    public static boolean enabled(Boolean explicitSetting) {
        JsonNode global = readGlobalConfig();
        return evaluate(SubprocessEnvironment.snapshot(), global, explicitSetting);
    }


    public static Schedule schedule() {
        return schedule(readGlobalConfig());
    }

    static Schedule schedule(JsonNode globalConfig) {
        JsonNode value = featureValue(globalConfig);
        double minHours = positiveFinite(value == null ? null : value.get("minHours"), DEFAULT_MIN_HOURS);
        double minSessions = positiveFinite(value == null ? null : value.get("minSessions"), DEFAULT_MIN_SESSIONS);
        return new Schedule(minHours, minSessions);
    }

    /** Package-private seam used by deterministic wire/gate tests. */
    static boolean evaluate(Map<String, String> env, JsonNode globalConfig,
                            Boolean explicitSetting) {
        if (growthBookDisabled(env) || globalConfig == null) return false;
        JsonNode value = featureValue(globalConfig);
        if (!rolloutAvailable(value)) return false;
        return explicitSetting == null || explicitSetting;
    }

    private static JsonNode readGlobalConfig() {
        try {
            if (Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                return JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
            }
        } catch (Exception _) {
            // Missing/corrupt cached GrowthBook data means the external gate is off.
        }
        return null;
    }

    private static JsonNode featureValue(JsonNode globalConfig) {
        if (globalConfig == null) return null;
        JsonNode features = globalConfig.path("cachedGrowthBookFeatures");
        return features.isObject() ? features.get(FEATURE) : null;
    }

    private static boolean rolloutAvailable(JsonNode value) {
        if (value == null) return false;
// Some cache generations store a bare boolean.
        if (value.isBoolean()) return value.asBoolean();
        return value.isObject()
            && (value.path("enabled").asBoolean(false)
                || value.path("available").asBoolean(false));
    }

    private static double positiveFinite(JsonNode value, double fallback) {
        if (value == null || !value.isNumber()) return fallback;
        double parsed = value.asDouble();
        return Double.isFinite(parsed) && parsed > 0 ? parsed : fallback;
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
