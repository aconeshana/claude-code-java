package com.claudecode.ui.lanterna.repl;

import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.features.tasks.TaskBoardPresentationState;
import com.claudecode.ui.lanterna.features.tasks.TaskBoardProjection;
import com.claudecode.ui.lanterna.features.tasks.TaskListPanel;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.screen.Screen;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;

/**
 * Owns the inline task-board projection and lifecycle: {@link TaskBoardPort} snapshot/intent
 * subscriptions, presentation-state bookkeeping ({@link TaskBoardPresentationState},
 * {@link TaskBoardToggleState}), and the scheduled completion-refresh that keeps recently
 * finished tasks visible for their fade-out window. Extracted from {@code LanternaReplScreen}.
 */
final class TaskBoardFeature {

    private static final ScheduledExecutorService TASK_BOARD_PRESENTATION_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofVirtual().name("task-board-presentation").unstarted(runnable));

    private final MultiWindowTextGUI gui;
    private final Screen screen;
    private final SpinnerComponent spinnerComponent;
    private final InputPanel inputPanel;
    private final TaskBoardPort taskBoard;
    private final ReplFeatureRuntime featureRuntime;
    private final Supplier<List<SpinnerComponent.TeammateMetric>> teammateMetrics;

    private TaskListPanel taskListPanel;
    private volatile TaskBoardPort.Snapshot taskBoardSnapshot = TaskBoardPort.Snapshot.EMPTY;
    private final TaskBoardPresentationState taskBoardPresentationState =
        new TaskBoardPresentationState();
    private final TaskBoardToggleState taskBoardToggleState = new TaskBoardToggleState();
    private boolean taskBoardExpandable;
    private AutoCloseable taskBoardSubscription;
    private AutoCloseable taskBoardIntentSubscription;
    private volatile ScheduledFuture<?> taskBoardCompletionRefresh;
    private volatile boolean taskBoardLoading;

    TaskBoardFeature(
            MultiWindowTextGUI gui,
            Screen screen,
            SpinnerComponent spinnerComponent,
            InputPanel inputPanel,
            TaskBoardPort taskBoard,
            ReplFeatureRuntime featureRuntime,
            Supplier<List<SpinnerComponent.TeammateMetric>> teammateMetrics) {
        this.gui = gui;
        this.screen = screen;
        this.spinnerComponent = spinnerComponent;
        this.inputPanel = inputPanel;
        this.taskBoard = taskBoard;
        this.featureRuntime = featureRuntime;
        this.teammateMetrics = teammateMetrics;
    }

    void start() {
        taskListPanel = new TaskListPanel();       // inline, zero height until shown
        applyTaskBoardSnapshot(taskBoard.snapshot());
        taskListPanel.setVisible(UiSettings.readGlobalBoolean("showExpandedTodos", false)
            && !taskBoardSnapshot.hidden());
        taskBoardSubscription = taskBoard.subscribe(snapshot ->
            gui.getGUIThread().invokeLater(() -> applyTaskBoardSnapshot(snapshot)));
        taskBoardIntentSubscription = taskBoard.subscribeIntents(_ ->
            gui.getGUIThread().invokeLater(this::expandTaskBoard));
    }

    Component view() {
        return taskListPanel;
    }

    boolean isVisible() {
        return taskListPanel != null && taskListPanel.isVisible();
    }

    void setLoading(boolean loading) {
        taskBoardLoading = loading;
        refreshTaskBoardProjection();
    }

    void refreshProjection() {
        refreshTaskBoardProjection();
    }

    void toggle() {
        toggleTaskBoard();
    }

    void collapseForTeammateTreeExpansion() {
        taskBoardToggleState.showCompact();
        taskListPanel.setVisible(false);
    }

    void close() {
        closeTaskBoardSubscriptions();
    }

    private void applyTaskBoardSnapshot(TaskBoardPort.Snapshot snapshot) {
        taskBoardSnapshot = snapshot == null ? TaskBoardPort.Snapshot.EMPTY : snapshot;
        taskBoardToggleState.updateSnapshot(taskBoardSnapshot);
        long nowMillis = System.currentTimeMillis();
        taskBoardPresentationState.update(taskBoardSnapshot, nowMillis);
        if (spinnerComponent != null) spinnerComponent.setTaskSnapshot(taskBoardSnapshot);
        refreshTaskBoardProjection(nowMillis);
        scheduleTaskBoardCompletionRefresh(nowMillis);
        if (taskBoardSnapshot.hidden() && taskListPanel.isVisible()) {
            taskListPanel.setVisible(false);
            UiSettings.ensureGlobalBooleanAsync("showExpandedTodos", false);
        }
    }

    private void refreshTaskBoardProjection() {
        refreshTaskBoardProjection(System.currentTimeMillis());
    }

    private void refreshTaskBoardProjection(long nowMillis) {
        if (taskListPanel == null || screen == null) return;
        TerminalSize size = screen.getTerminalSize();
        TaskBoardProjection.View view = TaskBoardProjection.project(
            taskBoardSnapshot, size.getRows(), size.getColumns(), !taskBoardLoading,
            taskBoardToggleState.expanded(),
            nowMillis, taskBoardPresentationState.completionTimes(nowMillis),
            activeTaskOwners());
        taskBoardExpandable = view.expandable();
        taskListPanel.refresh(view);
    }

    private Map<String, TaskBoardProjection.ActiveOwner> activeTaskOwners() {
        return activeTaskOwners(teammateMetrics.get());
    }

    static Map<String, TaskBoardProjection.ActiveOwner> activeTaskOwners(
            List<SpinnerComponent.TeammateMetric> teammates) {
        Map<String, TaskBoardProjection.ActiveOwner> owners = new LinkedHashMap<>();
        for (SpinnerComponent.TeammateMetric teammate : teammates) {
            if (StringUtils.isNotBlank(teammate.taskId())) {
                owners.put(teammate.taskId(), new TaskBoardProjection.ActiveOwner(
                    null, teammate.activity()));
            }
            if (StringUtils.isNotBlank(teammate.name())) {
                owners.put(teammate.name(), new TaskBoardProjection.ActiveOwner(
                    teammate.colorName(), teammate.activity()));
            }
        }
        return Map.copyOf(owners);
    }

    private synchronized void scheduleTaskBoardCompletionRefresh(long nowMillis) {
        cancelTaskBoardCompletionRefresh();
        long delayMillis = taskBoardPresentationState.nextExpiryDelayMillis(nowMillis);
        if (delayMillis < 0L) return;
        taskBoardCompletionRefresh = TASK_BOARD_PRESENTATION_SCHEDULER.schedule(() -> {
            if (gui == null) return;
            gui.getGUIThread().invokeLater(() -> {
                long refreshAt = System.currentTimeMillis();
                refreshTaskBoardProjection(refreshAt);
                scheduleTaskBoardCompletionRefresh(refreshAt);
            });
        }, Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelTaskBoardCompletionRefresh() {
        ScheduledFuture<?> current = taskBoardCompletionRefresh;
        taskBoardCompletionRefresh = null;
        if (current != null) current.cancel(false);
    }

    private void expandTaskBoard() {
        boolean alreadyVisible = taskListPanel.isVisible();
        applyTaskBoardSnapshot(taskBoard.snapshot());
        if (taskBoardSnapshot.hidden()) return;
        if (!alreadyVisible) taskBoardToggleState.showCompact();
        spinnerComponent.setTeammateTreeExpanded(false);
        inputPanel.setTeammateTreeExpanded(false);
        taskListPanel.setVisible(true);
        refreshTaskBoardProjection();
        UiSettings.ensureGlobalBooleanAsync("showExpandedTodos", true);
    }

    private void toggleTaskBoard() {
        boolean hasTeammates = !featureRuntime.taskRegistry().listRunningTeammates().isEmpty();
        applyTaskBoardSnapshot(taskBoard.snapshot());
        if (taskListPanel.isVisible()) {
            TaskBoardToggleState.Toggle toggle = taskBoardToggleState.toggle(
                true, taskBoardExpandable);
            if (toggle == TaskBoardToggleState.Toggle.SHOW_EXPANDED) {
                spinnerComponent.setTeammateTreeExpanded(false);
                inputPanel.setTeammateTreeExpanded(false);
                refreshTaskBoardProjection();
            } else {
                taskListPanel.setVisible(false);
                spinnerComponent.setTeammateTreeExpanded(hasTeammates);
                inputPanel.setTeammateTreeExpanded(hasTeammates);
            }
        } else if (hasTeammates && spinnerComponent.isTeammateTreeExpanded()) {
            taskBoardToggleState.showCompact();
            spinnerComponent.setTeammateTreeExpanded(false);
            inputPanel.setTeammateTreeExpanded(false);
        } else if (!taskBoardSnapshot.hidden()) {
            taskBoardToggleState.toggle(false, taskBoardExpandable);
            spinnerComponent.setTeammateTreeExpanded(false);
            inputPanel.setTeammateTreeExpanded(false);
            taskListPanel.setVisible(true);
            refreshTaskBoardProjection();
        } else {
            taskBoardToggleState.showCompact();
            spinnerComponent.setTeammateTreeExpanded(false);
            inputPanel.setTeammateTreeExpanded(false);
        }
        UiSettings.ensureGlobalBooleanAsync(
            "showExpandedTodos", taskListPanel.isVisible());
    }

    private void closeTaskBoardSubscriptions() {
        cancelTaskBoardCompletionRefresh();
        closeQuietly(taskBoardSubscription);
        closeQuietly(taskBoardIntentSubscription);
        taskBoardSubscription = null;
        taskBoardIntentSubscription = null;
    }

    private static void closeQuietly(AutoCloseable subscription) {
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (Exception _) {
            // UI teardown is best effort.
        }
    }
}
