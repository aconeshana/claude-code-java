package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.services.git.GitignoreHelper;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;

/**
 * Resolves git-related behavior that depends on settings without coupling the git helpers back to
 * the settings loader.
 */
public final class GitSettings {

    private static final String DISABLE_GIT_INSTRUCTIONS_ENV =
        "CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS";
    private static final String LOCAL_SETTINGS_PATH = ".claude/settings.local.json";

    private GitSettings() {}

    /**
     * Returns whether git guidance belongs in the system prompt for this
     * process. A recognized environment override takes precedence over the
     * effective layered setting, which defaults to enabled.
     */
    public static boolean shouldIncludeGitInstructions() {
        String disableEnv = SubprocessEnvironment.get(DISABLE_GIT_INSTRUCTIONS_ENV);
        if (EnvUtils.isEnvTruthy(disableEnv)) return false;
        if (EnvUtils.isEnvDefinedFalsy(disableEnv)) return true;
        return resolveIncludeGitInstructions(
            disableEnv,
            RuntimeSettings.loadOptionalBoolean("includeGitInstructions"));
    }

    static boolean resolveIncludeGitInstructions(String disableEnv, Boolean settingValue) {
        if (EnvUtils.isEnvTruthy(disableEnv)) return false;
        if (EnvUtils.isEnvDefinedFalsy(disableEnv)) return true;
        return settingValue == null || settingValue;
    }

    /**
     * Keeps a locally written settings file out of source control without
     * blocking the settings write that triggered it.
     */
    static void ensureLocalSettingsIgnored(String cwd, RuleSource tier) {
        if (tier == RuleSource.LOCAL_SETTINGS) {
            Thread.startVirtualThread(() ->
                GitignoreHelper.addFileGlobRuleToGitignore(LOCAL_SETTINGS_PATH,
                    SettingsPaths.sessionProjectRoot(cwd).toString()));
        }
    }
}
