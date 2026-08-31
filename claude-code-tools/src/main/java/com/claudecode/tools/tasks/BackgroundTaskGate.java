package com.claudecode.tools.tasks;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;


public final class BackgroundTaskGate {

    private BackgroundTaskGate() {}

    public static boolean disabled() {
        return EnvUtils.isEnvTruthy(
            SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_BACKGROUND_TASKS"));
    }
}
