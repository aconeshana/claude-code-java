package com.claudecode.tools.tasks.teammate;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import org.apache.commons.lang3.Strings;

/**
 * Feature gate for the in-process teammate (agent-teams) subsystem.
 */
public final class AgentTeamsEnabled {


    public static final String ENV_EXPERIMENTAL_AGENT_TEAMS = "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS";
    private static final String CLI_FLAG_PROPERTY = "claude.code.agentTeams";
    private static final String KILLSWITCH_PROPERTY = "claude.code.agentTeamsKillswitch";
    private static final String KILLSWITCH_ENV = "CLAUDE_CODE_AGENT_TEAMS_KILLSWITCH";

    /**
     * Test seam. When non-null, {@link #isEnabled} returns this value instead
     * of consulting the env var — lets unit tests exercise the opt-in gate both
     * ways without spawning a subprocess. {@code null} (the default) restores
     * normal env-var behavior. matches the {@code setGlobalForTest} seam in
     * {@code TaskRegistry}.
     */
    private static Boolean testOverride;

    private AgentTeamsEnabled() {}


    public static boolean isEnabled() {
        if (testOverride != null) {
            return testOverride;
        }
        if (Strings.CS.equals("ant", SubprocessEnvironment.get("USER_TYPE"))) {
            return true;
        }
        boolean optedIn = EnvUtils.isEnvTruthy(
            SubprocessEnvironment.get(ENV_EXPERIMENTAL_AGENT_TEAMS))
            || Boolean.parseBoolean(System.getProperty(CLI_FLAG_PROPERTY, "false"));
        if (!optedIn) return false;
        String killswitch = System.getProperty(KILLSWITCH_PROPERTY);
        if (killswitch == null) {
            killswitch = SubprocessEnvironment.get(KILLSWITCH_ENV);
        }
        if (killswitch != null) {
            return !EnvUtils.isEnvDefinedFalsy(killswitch);
        }



        // killswitch cannot accidentally expose teammate tools.
        try {
            if (Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                JsonNode global = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
                JsonNode feature = global == null ? null
                    : global.path("cachedGrowthBookFeatures").get("tengu_amber_flint");
                if (feature != null && feature.isBoolean()) {
                    return feature.asBoolean();
                }
            }
        } catch (Exception _) {

        }
        return true;
    }

/** Test seam: force {@link #isEnabled} to a fixed value. Pass {@code null} to restore env-var behavior. */
    public static void setEnabledForTest(Boolean value) {
        testOverride = value;
    }

    /** Test seam: restore env-var-driven behavior. */
    public static void resetForTest() {
        testOverride = null;
        System.clearProperty(CLI_FLAG_PROPERTY);
        System.clearProperty(KILLSWITCH_PROPERTY);
    }

    /** Called by the CLI after Picocli parses {@code --agent-teams}. */
    public static void setCliFlag(boolean enabled) {
        System.setProperty(CLI_FLAG_PROPERTY, Boolean.toString(enabled));
    }
}
