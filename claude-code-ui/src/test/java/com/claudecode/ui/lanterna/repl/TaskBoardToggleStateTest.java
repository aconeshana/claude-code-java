package com.claudecode.ui.lanterna.repl;

import com.claudecode.runtime.tasks.TaskBoardPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskBoardToggleStateTest {

    @Test
    void visibleCompactBoardExpandsBeforeItHides() {
        TaskBoardToggleState state = new TaskBoardToggleState();
        state.updateSnapshot(snapshot("session-a", false));

        assertEquals(TaskBoardToggleState.Toggle.SHOW_COMPACT,
            state.toggle(false, true));
        assertFalse(state.expanded());

        assertEquals(TaskBoardToggleState.Toggle.SHOW_EXPANDED,
            state.toggle(true, true));
        assertTrue(state.expanded());

        assertEquals(TaskBoardToggleState.Toggle.HIDE,
            state.toggle(true, true));
        assertFalse(state.expanded());
    }

    @Test
    void visibleBoardHidesImmediatelyWhenNoAdditionalRowsCanBeShown() {
        TaskBoardToggleState state = new TaskBoardToggleState();
        state.updateSnapshot(snapshot("session-a", false));

        assertEquals(TaskBoardToggleState.Toggle.HIDE,
            state.toggle(true, false));
        assertFalse(state.expanded());
    }

    @Test
    void changingOrHidingTheTaskListClearsExpansion() {
        TaskBoardToggleState state = new TaskBoardToggleState();
        state.updateSnapshot(snapshot("session-a", false));
        state.toggle(true, true);

        state.updateSnapshot(snapshot("session-b", false));
        assertFalse(state.expanded());

        state.toggle(true, true);
        state.updateSnapshot(snapshot("session-b", true));
        assertFalse(state.expanded());
    }

    private static TaskBoardPort.Snapshot snapshot(String listId, boolean hidden) {
        return new TaskBoardPort.Snapshot(listId, 1L, List.of(
            new TaskBoardPort.TaskItem(
                "1", "task", "", null, null, TaskBoardPort.Status.PENDING,
                List.of(), List.of())), hidden);
    }
}
