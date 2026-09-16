package com.claudecode.ui.lanterna.input;

import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.workflows.WorkflowRun;
import com.claudecode.tools.workflows.WorkflowRunStore;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Selection state for the workflow rows rendered inside the coordinator panel.
 *
 * <p>Rows are the current-process workflow tasks joined with their persisted
 * {@link WorkflowRun}; persisted history without a live task has no footer
 * row. Selection follows the task id so an eviction elsewhere in the list
 * never silently moves the highlight.
 *
 * <ul>
 *   <li>{@code src/components/CoordinatorAgentStatus.tsx} — the workflow rows of
 *       the coordinator status block and their {@code enter view · x stop/clear}
 *       affordances.</li>
 * </ul>
 */
final class WorkflowFooter {

    private WorkflowRunStore runs;
    private TaskRegistry registry;
    private boolean selected;
    private int index;
    /** Keeps selection on the same workflow when another row is evicted. */
    private String selectedTaskId;

    void setRunStore(WorkflowRunStore runs) { this.runs = runs; }

    void setRegistry(TaskRegistry registry) { this.registry = registry; }

    TaskRegistry registry() { return registry; }

    boolean isSelected() { return selected; }

    int index() { return index; }

    String selectedTaskId() { return selectedTaskId; }

    /** Selected row index for the panel projection, or {@code -1} when unselected. */
    int selectedIndexOrMinusOne() { return selected ? index : -1; }

    List<WorkflowRun> visibleRuns() {
        if (runs == null || registry == null) return List.of();
        var byTaskId = runs.list().stream()
            .collect(Collectors.toMap(WorkflowRun::taskId,
                Function.identity(), (left, _) -> left));
        return registry.listPanelWorkflowTasks(Instant.now()).stream()
            .map(task -> byTaskId.get(task.id()))
            .filter(Objects::nonNull)
            .toList();
    }

    boolean hasRows() { return !visibleRuns().isEmpty(); }

    /** Selects {@code requestedIndex} clamped to the visible rows; no-op when there are none. */
    boolean select(int requestedIndex) {
        List<WorkflowRun> workflows = visibleRuns();
        if (workflows.isEmpty()) return false;
        selected = true;
        index = Math.max(0, Math.min(requestedIndex, workflows.size() - 1));
        selectedTaskId = workflows.get(index).taskId();
        return true;
    }

    /** Re-selects the previously highlighted row. */
    boolean selectCurrent() {
        return select(index);
    }

    /** Drops the selection and forgets the row. */
    void deselect() {
        selected = false;
        selectedTaskId = null;
    }

    /** Drops the selection but remembers the row so a later re-entry lands on it. */
    void blur() {
        selected = false;
    }

    /** Moves the highlight by one row; {@code false} when already at that edge. */
    boolean step(int delta, List<WorkflowRun> workflows) {
        int next = index + delta;
        if (next < 0 || next >= workflows.size()) return false;
        index = next;
        selectedTaskId = workflows.get(index).taskId();
        return true;
    }

    /** Clamps the row index into the current list; tracks the selected task id across evictions. */
    void clamp() {
        List<WorkflowRun> workflows = visibleRuns();
        int count = workflows.size();
        if (count == 0) {
            selected = false;
            index = 0;
            selectedTaskId = null;
            return;
        }
        int retained = selectedTaskId == null ? -1
            : IntStream.range(0, workflows.size())
                .filter(i -> selectedTaskId.equals(workflows.get(i).taskId()))
                .findFirst().orElse(-1);
        index = retained >= 0 ? retained : Math.min(index, count - 1);
        if (selected) {
            selectedTaskId = workflows.get(index).taskId();
        }
    }

    /** Clamps the index into {@code workflows} without touching the task-id memory. */
    WorkflowRun current(List<WorkflowRun> workflows) {
        index = Math.max(0, Math.min(index, workflows.size() - 1));
        return workflows.get(index);
    }

    /** Stops a running workflow or clears a finished one. */
    void dismissOrKill(WorkflowRun run) {
        if (registry == null) return;
        if (run.status().hasResult()) registry.dismissWorkflow(run.taskId());
        else registry.killWorkflow(run.taskId());
    }
}
