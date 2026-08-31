package com.claudecode.services.claudemd;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.core.process.SubprocessEnvironment;

import java.nio.file.Path;


public final class AutoMemory {
    private AutoMemory() {}


    public static boolean isEnabled() {
        return isEnabled(
            SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_AUTO_MEMORY"),
            SubprocessEnvironment.get("CLAUDE_CODE_SIMPLE"),
            SubprocessEnvironment.get("CLAUDE_CODE_REMOTE"),
            SubprocessEnvironment.get("CLAUDE_CODE_REMOTE_MEMORY_DIR"),
            RuntimeSettings.loadAutoMemoryEnabled());
    }

    /** Test seam — explicit env values and resolved settings value instead of process globals. */
    static boolean isEnabled(String disableAutoMemory, String simple, String remote,
            String remoteMemoryDir, boolean settingsAutoMemoryEnabled) {
        if (EnvUtils.isEnvTruthy(disableAutoMemory)) return false;
        if (EnvUtils.isEnvDefinedFalsy(disableAutoMemory)) return true;
        if (EnvUtils.isEnvTruthy(simple)) return false;
        if (EnvUtils.isEnvTruthy(remote) && blank(remoteMemoryDir)) return false;
        return settingsAutoMemoryEnabled;
    }

    /**
     * Absolute path to the auto-memory folder for the given working directory.
     */
    public static String autoMemoryPath(Path workingDirectory) {
        return AutoMemoryPrompt.resolveAutoMemPath(workingDirectory).toString();
    }


    private static boolean blank(String value) {
        return StringUtils.isBlank(value);
    }
}
