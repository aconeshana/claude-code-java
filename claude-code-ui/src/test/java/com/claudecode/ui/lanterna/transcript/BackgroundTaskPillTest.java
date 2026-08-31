package com.claudecode.ui.lanterna.transcript;

import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


class BackgroundTaskPillTest {

    private static TaskState task(TaskType type) {
        return TaskState.withId("t-" + type + "-" + Math.random(), type, "desc");
    }

    @Test
    void empty_rendersNothing() {
        assertEquals("", BackgroundTaskPill.labelFor(List.of()));
    }

    @Test
    void allShells_singular() {
        assertEquals("1 shell", BackgroundTaskPill.labelFor(List.of(task(TaskType.LOCAL_BASH))));
    }

    @Test
    void allShells_plural() {
        assertEquals("3 shells", BackgroundTaskPill.labelFor(List.of(
            task(TaskType.LOCAL_BASH), task(TaskType.LOCAL_BASH), task(TaskType.LOCAL_BASH))));
    }

    @Test
    void allAgents_singular() {
        assertEquals("1 local agent", BackgroundTaskPill.labelFor(List.of(task(TaskType.LOCAL_AGENT))));
    }

    @Test
    void allAgents_plural() {
        assertEquals("2 local agents", BackgroundTaskPill.labelFor(List.of(
            task(TaskType.LOCAL_AGENT), task(TaskType.LOCAL_AGENT))));
    }

    @Test
    void commandBackedMonitorsSplitFromOrdinaryShells() {
        TaskState shell = TaskState.withId("b-shell", TaskType.LOCAL_BASH, "shell");
        TaskState monitor = TaskState.withId("b-monitor", TaskType.LOCAL_BASH, "monitor");

        assertEquals("1 shell, 1 monitor", BackgroundTaskPill.labelFor(
            List.of(shell, monitor), "b-monitor"::equals));
    }

    @Test
    void nonShellMonitorWorkflowAndDreamLabelsMatchReleasedNames() {
        assertEquals("2 monitors", BackgroundTaskPill.labelFor(List.of(
            task(TaskType.MONITOR_WS), task(TaskType.MONITOR_WS))));
        assertEquals("1 background workflow", BackgroundTaskPill.labelFor(List.of(
            task(TaskType.LOCAL_WORKFLOW))));
        assertEquals("dreaming", BackgroundTaskPill.labelFor(List.of(
            task(TaskType.DREAM), task(TaskType.DREAM))));
    }

    @Test
    void mixed_usesGenericBackgroundTasksLabel() {
        assertEquals("2 background tasks", BackgroundTaskPill.labelFor(List.of(
            task(TaskType.LOCAL_BASH), task(TaskType.LOCAL_AGENT))));
    }

    @Test
    void nonJavaTaskType_fallsThroughToGenericLabel_neverMislabels() {

        // — the generic label is the honest fallback.
        assertEquals("1 background task", BackgroundTaskPill.labelFor(List.of(
            task(TaskType.IN_PROCESS_TEAMMATE))));
    }
}
