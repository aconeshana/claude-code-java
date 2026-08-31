package com.claudecode.tools.cron;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.Map;

/**
 * Resolves cron-system availability, durable-cron behavior, and scheduler tuning.
 */
public final class CronFeatureGate {

    private static final String DURABLE = "tengu_kairos_cron_durable";

    private final boolean cronEnabled;
    private final boolean durableEnabled;
    private final CronJitterConfig jitterConfig;

    private CronFeatureGate(boolean cronEnabled, boolean durableEnabled,
                            CronJitterConfig jitterConfig) {
        this.cronEnabled = cronEnabled;
        this.durableEnabled = durableEnabled;
        this.jitterConfig = jitterConfig;
    }

    public static CronFeatureGate system() {
        JsonNode global = null;
        try {
            if (Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                global = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
            }
        } catch (Exception _) { }
        return evaluate(SubprocessEnvironment.snapshot(), global);
    }

    @Explanation("Cron availability uses the local CLAUDE_CODE_DISABLE_CRON override; cached tuning values are optional refinements over local defaults.")
    static CronFeatureGate evaluate(Map<String, String> env, JsonNode globalConfig) {
        boolean growthBookDisabled = Strings.CS.equals("test", env.get("NODE_ENV"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_BEDROCK"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_VERTEX"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_FOUNDRY"))
            || env.containsKey("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC")
            || env.containsKey("DISABLE_TELEMETRY");
        JsonNode features = growthBookDisabled || globalConfig == null
            ? null : globalConfig.path("cachedGrowthBookFeatures");
        return new CronFeatureGate(
            !EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_DISABLE_CRON")),
            booleanFeature(features, DURABLE, true),
            CronJitterConfig.from(features == null ? null : features.get("tengu_kairos_cron_config")));
    }

    private static boolean booleanFeature(JsonNode features, String name,
                                          boolean defaultValue) {
        if (features == null) return defaultValue;
        JsonNode value = features.get(name);
        return value != null && value.isBoolean() ? value.asBoolean() : defaultValue;
    }

    public boolean cronEnabled() { return cronEnabled; }
    public boolean durableEnabled() { return durableEnabled; }
    public CronJitterConfig jitterConfig() { return jitterConfig; }
}
