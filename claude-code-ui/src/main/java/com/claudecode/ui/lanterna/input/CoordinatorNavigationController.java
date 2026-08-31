package com.claudecode.ui.lanterna.input;

import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Navigation state machine for the subagent coordinator panel — the vertical unified tasks footer:
 * optional background-task pill ({@code -1}), {@code main} ({@code 0}), then local agents ({@code
 * 1..n}).
 */
public final class CoordinatorNavigationController {

    /** Narrow port so the model stays free of Lanterna widget concerns. */
    public interface Host {
        /** Rebuild the visible transcript after a view switch. */
        void teammateViewChanged();
        /** Refresh the prompt hint line. */
        void refreshHint();
        /** Clear any transient status line (e.g. "Viewing @..."). */
        void clearStatusLine();
    }

    private final ViewedTeammateHolder view = ViewedTeammateHolder.instance();
    private final Supplier<Instant> clock;
    private TaskRegistry registry;

    /** -1 = background pill, 0 = {@code main}, 1..n = local agents. */
    private int coordinatorIndex;
    /** Whether the unified tasks footer currently owns keyboard focus. */
    private boolean tasksSelected;
    private boolean backgroundPillAvailable;
    private String selectedTaskId;

    public CoordinatorNavigationController(TaskRegistry registry) {
        this(registry, Instant::now);
    }

    CoordinatorNavigationController(TaskRegistry registry, Supplier<Instant> clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = clock;
    }

    public void setRegistry(TaskRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        clampIndex();
    }

    /** Panel rows (excluding the {@code main} leader), in display order. */
    public List<TaskState> panelAgents() {
        return registry.listPanelAgentTasks(clock.get());
    }

    /** Whether the coordinator panel has any rows to show. */
    public boolean panelAvailable() {
        return !panelAgents().isEmpty();
    }

    public boolean isPanelSelected() {
        return tasksSelected;
    }

    /** Selection index: 0 = {@code main}, 1..n = the nth panel agent. */
    public int coordinatorIndex() {
        return coordinatorIndex;
    }

    public String selectedTaskId() {
        return selectedTaskId;
    }

    /** True while a subagent transcript (not a teammate) is being viewed. */
    public boolean isViewingLocalAgent() {
        return view.isViewingLocalAgent();
    }

    /** Gives the panel keyboard focus, parking the selection on {@code main}. */
    public void selectPanel() {
        selectPanel(false);
    }

    public void selectPanel(boolean hasBackgroundPill) {
        backgroundPillAvailable = hasBackgroundPill;
        tasksSelected = panelAvailable();
        coordinatorIndex = hasBackgroundPill ? -1 : 0;
        selectedTaskId = null;
    }

    /** Keeps the unified tasks group's minimum index aligned with the live pill. */
    void synchronizeBackgroundPill(boolean available) {
        backgroundPillAvailable = available;
        List<TaskState> agents = panelAgents();
        if (agents.isEmpty()) {
            tasksSelected = false;
            coordinatorIndex = available ? -1 : 0;
            selectedTaskId = null;
            return;
        }
        if (!available && coordinatorIndex < 0) {
            coordinatorIndex = 0;
            selectedTaskId = null;
        }
        clampIndex(agents);
    }

    /** Releases panel focus without changing which transcript is viewed. */
    public void deselectPanel() {
        tasksSelected = false;
    }

    /**
     * Moves the selection by {@code delta}, clamped to {@code [0, agentCount]}
     * (no wrap; {@code main} is index 0). Selecting on an empty panel is a no-op.
     */
    public void step(int delta, Host host) {
        List<TaskState> agents = panelAgents();
        int count = agents.size();
        if (count == 0) {
            tasksSelected = false;
            return;
        }
        tasksSelected = true;
        int minimum = backgroundPillAvailable ? -1 : 0;
        coordinatorIndex = Math.max(minimum, Math.min(coordinatorIndex + delta, count));
        selectedTaskId = coordinatorIndex > 0
            ? agents.get(coordinatorIndex - 1).id() : null;
        host.refreshHint();
    }

    /**
     * Opens the selected row: index 0 returns to {@code main}, any other index enters that subagent's
     * transcript.
     */
    public void openSelected(Host host) {
        List<TaskState> agents = panelAgents();
        int index = Math.max(-1, Math.min(coordinatorIndex, agents.size()));
        if (index < 0) return;
        if (index == 0) {
            exitToMain(host);
        } else {
            enterView(agents.get(index - 1).id(), host);
        }
    }


    public void enterView(String taskId, Host host) {
        // retain: block eviction while viewed by clearing the deadline.
        registry.store().setEvictAfter(taskId, null);
        view.enterLocalAgentViewing(taskId);
        host.teammateViewChanged();
        host.refreshHint();
    }

    /**
     * Escape handling for the panel. A local-agent view returns to {@code main}
     * without stopping the agent; explicit {@code x} is the stop/dismiss path.
     * While merely selecting, Escape drops panel focus. Returns {@code true}
     * when the key was consumed.
     */
    public boolean handleEscape(Host host) {
        if (view.isViewingLocalAgent()) {
            exitToMain(host);
            return true;
        }
        if (tasksSelected) {
            deselectPanel();
            host.refreshHint();
            return true;
        }
        return false;
    }


    public void dismissSelected(Host host) {
        List<TaskState> agents = panelAgents();
        int index = Math.max(0, Math.min(coordinatorIndex, agents.size()));
        if (index == 0) return;
        TaskState task = agents.get(index - 1);
        if (task.status() == TaskStatus.RUNNING) {
            registry.killAgentByUser(task.id());
        } else {
            registry.dismissAgent(task.id());
            if (task.id().equals(view.viewingTaskId())) {
                exitToMain(host);
            }
        }
        clampIndex();
        host.refreshHint();
    }

    public boolean isViewingSelectedAgent() {
        return coordinatorIndex > 0
            && selectedTaskId != null
            && selectedTaskId.equals(view.viewingTaskId());
    }

    /**
     * Returns to {@code main}.
     */
    public void exitToMain(Host host) {
        String prevId = view.viewingTaskId();
        if (view.isViewingLocalAgent()) {
            view.exit();
        }
        if (prevId != null) {
            registry.get(prevId).ifPresent(task -> {
                if (task.status().isTerminal()) {
                    registry.store().setEvictAfter(
                        prevId, clock.get().plus(LocalAgentTask.PANEL_GRACE));
                }
            });
        }
        host.clearStatusLine();
        host.teammateViewChanged();
        host.refreshHint();
    }

    /**
     * Per-tick lifecycle: auto-exit a view whose agent vanished/failed, then
     * sweep expired terminal rows (protecting the one being viewed), then
     * re-clamp the selection. Drives the panel's 1 s cadence from the REPL's
     * existing refresh timer.
     */
    public void tick(Host host) {
        syncAutoExit(host);
        Instant now = clock.get();
        String retained = view.isViewingLocalAgent() ? view.viewingTaskId() : null;
        registry.evictExpiredPanelTasks(now, retained);
        clampIndex();
    }

    /**
     * Auto-exits when the viewed subagent was evicted out from under the viewer or transitioned to
     * killed/failed.
     */
    public void syncAutoExit(Host host) {
        if (!view.isViewingLocalAgent()) return;
        String taskId = view.viewingTaskId();
        if (taskId == null) {
            exitToMain(host);
            return;
        }
        autoExitIfGone(host, taskId);
    }

    private void autoExitIfGone(Host host, String taskId) {
        var task = registry.get(taskId);
        if (task.isEmpty()) {
            // Evicted from under us: exit without re-stamping (already gone).
            view.exit();
            host.clearStatusLine();
            host.teammateViewChanged();
            host.refreshHint();
            return;
        }
        TaskStatus status = task.get().status();
        if (status == TaskStatus.KILLED || status == TaskStatus.FAILED) {
            exitToMain(host);
        }
    }

    private void clampIndex() {
        clampIndex(panelAgents());
    }

    private void clampIndex(List<TaskState> agents) {
        int count = agents.size();
        if (selectedTaskId != null) {
            for (int i = 0; i < agents.size(); i++) {
                if (selectedTaskId.equals(agents.get(i).id())) {
                    coordinatorIndex = i + 1;
                    return;
                }
            }
            selectedTaskId = null;
        }
        int minimum = backgroundPillAvailable ? -1 : 0;
        coordinatorIndex = Math.max(minimum, Math.min(coordinatorIndex, count));
        if (count == 0 && !backgroundPillAvailable) tasksSelected = false;
    }
}
