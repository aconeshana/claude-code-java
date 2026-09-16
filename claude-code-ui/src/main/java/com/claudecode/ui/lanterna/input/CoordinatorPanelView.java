package com.claudecode.ui.lanterna.input;

import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.workflows.WorkflowRun;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Narrow render port for the subagent coordinator panel, so {@link InputPanel}
 * can push snapshot updates without a compile-time dependency on the concrete
 * Lanterna component (which lives in the {@code repl} package and already imports
 * this package). Implemented by {@code CoordinatorTaskPanel}.
 */
public interface CoordinatorPanelView {

    /**
     * Replace the displayed snapshot.
     */
    void refresh(List<TaskState> agents,
                 List<WorkflowRun> workflows,
                 int selectedIndex,
                 int selectedWorkflowIndex,
                 String viewingTaskId,
                 Instant now,
                 Function<String, String> nameResolver);

    default void refresh(List<TaskState> agents,
                         List<WorkflowRun> workflows,
                         int selectedIndex,
                         int selectedWorkflowIndex,
                         String viewingTaskId,
                         Instant now,
                         Function<String, String> nameResolver,
                         ToIntFunction<String> pendingCountResolver) {
        refresh(agents, workflows, selectedIndex, selectedWorkflowIndex,
            viewingTaskId, now, nameResolver);
    }

    /**
     * Highlights content row {@code contentRow} (0-based, top margin excluded) as
     * mouse-hovered; {@code -1} clears any hover highlight.
     */
    void setHoveredRow(int contentRow);

    /**
     * Returns the {@code coordinatorIndex} (-1/0/1..n) rendered at content row
     * {@code contentRow} for a panel {@code columns} wide, or {@code -1} if that
     * row isn't agent-selectable (a workflow row, a "more" indicator, or out of
     * bounds).
     */
    int coordinatorIndexForRow(int contentRow, int columns);
}
