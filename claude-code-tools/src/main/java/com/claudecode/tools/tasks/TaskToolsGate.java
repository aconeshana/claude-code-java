package com.claudecode.tools.tasks;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;

/**
 * Selects the model-facing task-management surface: Task* tools or legacy {@code TodoWrite}.
 */
final class TaskToolsGate {

    static final String ENV_ENABLE_TASKS = "CLAUDE_CODE_ENABLE_TASKS";

    private TaskToolsGate() {}

    static boolean isEnabled() {
        return isEnabled(SubprocessEnvironment.get(ENV_ENABLE_TASKS));
    }

    static boolean isEnabled(String configuredValue) {
        return !EnvUtils.isEnvDefinedFalsy(configuredValue);
    }
}
