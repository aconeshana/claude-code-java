package com.claudecode.tools.tasks;

import com.claudecode.core.process.SubprocessEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class TaskToolsGateTest {

    @AfterEach
    void clearRuntimeEnvironment() {
        SubprocessEnvironment.clearRuntimeOverrides();
    }

    @Test
    void taskToolsAreEnabledByDefaultAndForNonFalsyOverrides() {
        assertTrue(TaskToolsGate.isEnabled(null));
        assertTrue(TaskToolsGate.isEnabled(""));
        assertTrue(TaskToolsGate.isEnabled("1"));
        assertTrue(TaskToolsGate.isEnabled("true"));
        assertTrue(TaskToolsGate.isEnabled("anything-else"));
    }

    @Test
    void definedFalsyOverrideFallsBackToLegacyTodoWrite() {
        assertFalse(TaskToolsGate.isEnabled("0"));
        assertFalse(TaskToolsGate.isEnabled(" false "));
        assertFalse(TaskToolsGate.isEnabled("NO"));
        assertFalse(TaskToolsGate.isEnabled("Off"));
    }

    @Test
    void noArgGateObservesSdkRuntimeEnvironmentUpdates() {
        SubprocessEnvironment.updateRuntime(Map.of(TaskToolsGate.ENV_ENABLE_TASKS, "false"));

        assertFalse(TaskToolsGate.isEnabled());

        SubprocessEnvironment.updateRuntime(Map.of(TaskToolsGate.ENV_ENABLE_TASKS, "true"));
        assertTrue(TaskToolsGate.isEnabled());
    }
}
