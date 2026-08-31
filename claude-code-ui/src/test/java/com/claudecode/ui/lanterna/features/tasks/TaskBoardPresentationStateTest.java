package com.claudecode.ui.lanterna.features.tasks;

import com.claudecode.runtime.tasks.TaskBoardPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskBoardPresentationStateTest {

    @Test
    void recordsCompletionTransitionsForExactlyTheRecentWindow() {
        TaskBoardPresentationState state = new TaskBoardPresentationState();
        state.update(snapshot("session", TaskBoardPort.Status.PENDING), 1_000L);

        state.update(snapshot("session", TaskBoardPort.Status.COMPLETED), 2_000L);

        assertEquals(2_000L, state.completionTimes(31_999L).get("1"));
        assertEquals(1L, state.nextExpiryDelayMillis(31_999L));
        assertTrue(state.completionTimes(32_000L).isEmpty());
        assertEquals(-1L, state.nextExpiryDelayMillis(32_000L));
    }

    @Test
    void changingTaskListsDoesNotTreatPrecompletedRowsAsNewTransitions() {
        TaskBoardPresentationState state = new TaskBoardPresentationState();
        state.update(snapshot("session-a", TaskBoardPort.Status.PENDING), 1_000L);

        state.update(snapshot("session-b", TaskBoardPort.Status.COMPLETED), 2_000L);

        assertTrue(state.completionTimes(2_000L).isEmpty());
    }

    @Test
    void newlyAppearingCompletedTaskIsRecentAfterTheInitialBaselineLikeReleased197() {
        TaskBoardPresentationState state = new TaskBoardPresentationState();
        state.update(new TaskBoardPort.Snapshot(
            "session", 1L, List.of(), true), 1_000L);

        state.update(snapshot("session", TaskBoardPort.Status.COMPLETED), 2_000L);

        assertEquals(2_000L, state.completionTimes(2_000L).get("1"));
    }

    private static TaskBoardPort.Snapshot snapshot(
            String listId, TaskBoardPort.Status status) {
        return new TaskBoardPort.Snapshot(listId, 1L, List.of(
            new TaskBoardPort.TaskItem(
                "1", "task", "", null, null, status, List.of(), List.of())), false);
    }
}
