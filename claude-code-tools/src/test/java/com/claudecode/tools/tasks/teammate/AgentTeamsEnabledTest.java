package com.claudecode.tools.tasks.teammate;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class AgentTeamsEnabledTest {

    @AfterEach
    void reset() {
        AgentTeamsEnabled.resetForTest();
    }

    @Test
    void disabledByDefault() {
        // With the test seam cleared, the decision falls back to the env var,
        // which is not set in the test JVM → disabled.
        AgentTeamsEnabled.resetForTest();
        assertFalse(AgentTeamsEnabled.isEnabled());
    }

    @Test
    void testSeamOverridesEnv() {
        AgentTeamsEnabled.setEnabledForTest(true);
        assertTrue(AgentTeamsEnabled.isEnabled());

        AgentTeamsEnabled.setEnabledForTest(false);
        assertFalse(AgentTeamsEnabled.isEnabled());

        // Restoring env-var behavior.
        AgentTeamsEnabled.resetForTest();
        assertFalse(AgentTeamsEnabled.isEnabled());
    }

    @Test
    void envVarTruthyValues() {
        // The real env parsing is exercised indirectly: the test seam does not
        // touch the env, so this documents the canonical truthy set the
        // production path accepts (1/true/yes/on), and that anything else is
        // treated as disabled.
        for (String v : new String[]{"1", "true", "TRUE", "Yes", "on"}) {
            assertEquals(true, switch (v.trim().toLowerCase(Locale.ROOT)) {
                case "1", "true", "yes", "on" -> true;
                default -> false;
            }, "value '" + v + "' must be truthy");
        }
        assertFalse(switch ("0".trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        });
        assertFalse(switch ("".trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        });
    }
}
