package com.claudecode.tools.monitor;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.Supplier;


public final class MonitorFeatureGate {

    private static final String FEATURE = "tengu_amber_sentinel";
    private static final ScopedValue<Boolean> TEST_OVERRIDE = ScopedValue.newInstance();

    private MonitorFeatureGate() {}

    public static boolean systemEnabled() {
        Boolean override = TEST_OVERRIDE.isBound() ? TEST_OVERRIDE.get() : null;
        if (override != null) return override;
        JsonNode global = null;
        try {
            if (Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                global = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
            }
        } catch (Exception _) { }
        return evaluate(SubprocessEnvironment.snapshot(), global);
    }

    /** Test seam for exercising the MONITOR_TOOL-dependent Bash/PowerShell path. */
    public static <T> T withSystemEnabled(boolean enabled, Supplier<T> action) {
        return ScopedValue.where(TEST_OVERRIDE, enabled).call(action::get);
    }

    public static boolean evaluate(Map<String, String> env, JsonNode globalConfig) {
        boolean growthBookDisabled = Strings.CS.equals("test", env.get("NODE_ENV"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_BEDROCK"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_VERTEX"))
            || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_FOUNDRY"))
            || env.containsKey("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC")
            || env.containsKey("DISABLE_TELEMETRY");
        if (growthBookDisabled || globalConfig == null) return false;
        JsonNode value = globalConfig.path("cachedGrowthBookFeatures").get(FEATURE);
        return value != null && value.isBoolean() && value.asBoolean();
    }
}
