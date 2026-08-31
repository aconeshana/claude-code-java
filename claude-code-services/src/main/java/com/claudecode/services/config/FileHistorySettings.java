package com.claudecode.services.config;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;

import java.nio.file.Path;

/**
 * Resolves whether the {@code /rewind} "Restore code" checkpoint subsystem
 * ({@code com.claudecode.core.engine.FileHistoryManager}) should be active.
 *
 * <ul>
 *   <li>{@code fileHistoryEnabled}
 *       and {@code fileHistoryEnabledSdk}: interactive sessions default on,
 *       while print/SDK sessions require
 *       {@code CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING}; the disable env
 *       always wins.</li>
 * </ul>
 *
 * <p>Read once by the composition root (CLI/app wiring) at
 * {@code QuerySessionSpec} construction time and passed in as a plain
 * boolean — {@code QuerySession}/core cannot depend on this class directly
 * ({@code claude-code-core} has no dependency on {@code claude-code-services}).
 */
public final class FileHistorySettings {

    private FileHistorySettings() {}

    /**
     * {@code true} unless {@code fileCheckpointingEnabled} is explicitly set
     * to {@code false} in, or the
     * {@code CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING} environment variable is
     * truthy (which always wins, regardless of the persisted setting).
     */
    public static boolean isEnabled() {
        return isEnabled(ClaudePaths.GLOBAL_JSON,
            SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING"));
    }

    /** Resolves the interactive vs print/SDK branch used by the current entrypoint. */
    public static boolean isEnabled(boolean nonInteractive) {
        return isEnabled(
            ClaudePaths.GLOBAL_JSON,
            SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING"),
            nonInteractive,
            SubprocessEnvironment.get("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING"));
    }

    /** Test seam — explicit config path and env value instead of process globals. */
    public static boolean isEnabled(Path globalConfigPath, String disableEnvValue) {
        return isEnabled(globalConfigPath, disableEnvValue, false, null);
    }


    public static boolean isEnabled(Path globalConfigPath, String disableEnvValue,
                                    boolean nonInteractive, String sdkEnableEnvValue) {
        if (EnvUtils.isEnvTruthy(disableEnvValue)) return false;
        if (nonInteractive) return EnvUtils.isEnvTruthy(sdkEnableEnvValue);
        return GlobalConfigStore.getBoolean(globalConfigPath, "fileCheckpointingEnabled", true);
    }

}
