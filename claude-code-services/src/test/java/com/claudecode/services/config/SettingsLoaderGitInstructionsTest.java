package com.claudecode.services.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SettingsGitInstructionsTest {

    @Test
    void truthyDisableEnvironmentWinsOverSettings() {
        assertFalse(GitSettings.resolveIncludeGitInstructions("true", true));
        assertFalse(GitSettings.resolveIncludeGitInstructions("on", null));
    }

    @Test
    void explicitlyFalsyDisableEnvironmentForcesInstructionsOn() {
        assertTrue(GitSettings.resolveIncludeGitInstructions("false", false));
        assertTrue(GitSettings.resolveIncludeGitInstructions("0", false));
    }

    @Test
    void absentOrUnknownEnvironmentUsesSettingThenDefaultsTrue() {
        assertFalse(GitSettings.resolveIncludeGitInstructions(null, false));
        assertTrue(GitSettings.resolveIncludeGitInstructions(null, true));
        assertTrue(GitSettings.resolveIncludeGitInstructions(null, null));
        assertFalse(GitSettings.resolveIncludeGitInstructions("unexpected", false));
    }
}
