package com.claudecode.ui.lanterna.input;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.permissions.PermissionMode;
import com.claudecode.tools.tasks.InProcessTeammateTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.HorizontalScroll;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.claudecode.ui.lanterna.transcript.BackgroundTaskPill;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;

/**
 * State machine for the prompt's background-task footer and teammate navigation.
 */
final class PromptTaskNavigationController {

    interface Host {
        void openTasksDialog();
        void refreshHint();
        void clearStatusLine();
        void showTeammateStatus(InProcessTeammateTask task);
        void showInterruptedHint();
        void showPermissionModeHint(PermissionMode mode);
        void teammateViewChanged();
        void setTeammateTreeExpanded(boolean expanded);
        boolean isTeammateTreeExpanded();
    }

    record PillView(String label, String hint, boolean selected) {}
    record TeammateHint(String main, String suffix, boolean accent) {}
    record TeammatePillView(String name, boolean selected, boolean viewed, boolean idle) {}
    record TeammateFooterView(List<TeammatePillView> visiblePills,
                              boolean showLeftArrow, boolean showRightArrow) {}

    private TaskRegistry registry;
    private final ViewedTeammateHolder view = ViewedTeammateHolder.instance();
    private boolean pillSelected;
    private boolean teammateTreeExpanded;
    private int previousTeammateCount;
    /** 0 = main leader, 1..n = sorted in-process teammates. */
    private int teammateFooterIndex;

    boolean isActive() { return view.isActive(); }
    boolean isViewing() { return view.viewingTaskId() != null; }
    boolean isPillSelected() { return pillSelected; }
    TaskRegistry registry() { return registry; }
    void setTeammateTreeExpanded(boolean expanded) { teammateTreeExpanded = expanded; }

    void setRegistry(TaskRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        String viewedTaskId = view.viewingTaskId();
        if (viewedTaskId != null
                && registry.get(viewedTaskId).isEmpty()
                && registry.getTeammateHandle(viewedTaskId).isEmpty()) {
            view.exit();
        }
        if (!pillAvailable()) pillSelected = false;
        teammateFooterIndex = Math.max(0, Math.min(teammateFooterIndex,
            Math.max(0, runningTeammates().size())));
        previousTeammateCount = runningTeammates().size();
    }

    void synchronizeTeammateCount() {
        int currentCount = runningTeammates().size();
        if (currentCount == 0 && previousTeammateCount > 0 && view.selectedIndex() != -1) {
            if (view.isViewing()) view.updateSelectedIndex(-1);
            else view.leaveSelecting();
        } else if (currentCount > 0) {
            int maxIndex = teammateTreeExpanded ? currentCount : currentCount - 1;
            if (view.selectedIndex() > maxIndex) view.updateSelectedIndex(maxIndex);
        }
        previousTeammateCount = currentCount;
    }

    boolean pillAvailable() {
        // Coordinator-panel subagents own the vertical panel, not the footer

        return registry != null && !registry.listBackgroundExcludingPanelAgents().isEmpty();
    }

    void selectPill() {
        pillSelected = pillAvailable();
        if (pillSelected) teammateFooterIndex = 0;
    }

    void deselectPill() {
        pillSelected = false;
    }

    PillView pillView() {
        if (registry == null) return new PillView("", "", false);
        List<TaskState> tasks = registry.listBackgroundExcludingPanelAgents();
        String label = BackgroundTaskPill.labelFor(tasks, registry::isMonitorTask);
        if (label.isEmpty()) {
            pillSelected = false;
            return new PillView("", "", false);
        }

        return new PillView(label, "", pillSelected);
    }

    TextBox.Result handlePillKey(KeyStroke key, Host host) {
        if (!pillAvailable()) {
            deselectPill();
            return null;
        }
        KeyType type = key.getKeyType();
        if (type == KeyType.ARROW_UP || type == KeyType.ESCAPE) {
            handlePillAction(type == KeyType.ARROW_UP
                ? "footer:up" : "footer:clearSelection", host);
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_DOWN || type == KeyType.ENTER) {
            handlePillAction(type == KeyType.ARROW_DOWN
                ? "footer:down" : "footer:openSelected", host);
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_LEFT || type == KeyType.ARROW_RIGHT) {
            if (isTeammateFooterVisible()) {
                handlePillAction(type == KeyType.ARROW_RIGHT
                    ? "footer:next" : "footer:previous", host);
            }
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && key.isCtrlDown() && !key.isAltDown()) {
            char c = Character.toLowerCase(key.getCharacter());
            if (c == 'p') {
                handlePillAction("footer:up", host);
                return TextBox.Result.HANDLED;
            }
            if (c == 'n') {
                handlePillAction("footer:down", host);
                return TextBox.Result.HANDLED;
            }
            return null;
        }
        if (key.isCtrlDown() || key.isAltDown()) return null;
        return TextBox.Result.HANDLED;
    }

    /** Dispatches a resolved Footer-context action. */
    boolean handlePillAction(String action, Host host) {
        if (!pillAvailable()) {
            deselectPill();
            return true;
        }
        return switch (action) {
            case "footer:up", "footer:clearSelection" -> {
                deselectPill();
                host.refreshHint();
                yield true;
            }
            case "footer:down", "footer:openSelected" -> {
                boolean teammateVisible = isTeammateFooterVisible();
                boolean openSelected = Strings.CS.equals("footer:openSelected", action);
                boolean moveDown = Strings.CS.equals("footer:down", action);
                // footer:down with a visible teammate intentionally falls
                // through, keeping the pill selected; there is no adjacent
                // Java footer item.
                if (openSelected && teammateVisible) {
                    openSelectedTeammate(host);
                } else if (!moveDown || !teammateVisible) {
                    deselectPill();
                    host.openTasksDialog();
                }
                host.refreshHint();
                yield true;
            }
            case "footer:next" -> {
                cycleTeammate(1);
                host.refreshHint();
                yield true;
            }
            case "footer:previous" -> {
                cycleTeammate(-1);
                host.refreshHint();
                yield true;
            }
            default -> false;
        };
    }

    boolean hasRunningTeammates() {
        return !runningTeammates().isEmpty();
    }

    void handleShiftSelection(int delta, Host host) {
        if (!runningTeammates().isEmpty()) {
            if (!host.isTeammateTreeExpanded()) {
                host.setTeammateTreeExpanded(true);
                view.enterSelecting(-1);
                host.refreshHint();
            } else {
                stepSelection(delta, host);
            }
        } else if (pillAvailable()) {
            deselectPill();
            host.openTasksDialog();
        }
    }

    /** Whether the original multi-agent footer (rather than the summary pill) is visible. */
    boolean isTeammateFooterVisible() {
        if (teammateTreeExpanded) return false;
        List<InProcessTeammateTask> teammates = runningTeammates();
        if (teammates.isEmpty()) return false;
        if (view.isViewing() && !view.isViewingLocalAgent()) return true;
        // Panel agents live in the vertical coordinator panel, so they don't
        // count against the "all running tasks are teammates" test that decides
        // whether the horizontal footer (vs. the summary pill) is shown.
        List<TaskState> running = registry.listBackgroundExcludingPanelAgents();
        return !running.isEmpty() && running.stream().allMatch(
            task -> task.type() == TaskType.IN_PROCESS_TEAMMATE);
    }

    /** Builds the width-constrained teammate pill window used by the footer renderer. */
    TeammateFooterView teammateFooterView(int availableWidth) {
        if (!isTeammateFooterVisible()) {
            return new TeammateFooterView(List.of(), false, false);
        }
        List<InProcessTeammateTask> teammates = runningTeammates();
        List<String> names = new ArrayList<>(teammates.size() + 1);
        names.add("main");
        teammates.forEach(task -> names.add(task.name() == null ? task.getTaskId() : task.name()));

        List<Integer> widths = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            widths.add(FormatUtils.displayWidth("@" + names.get(i)) + (i > 0 ? 1 : 0));
        }
        int selected = Math.max(0, Math.min(teammateFooterIndex, names.size() - 1));
        int viewed = 0;
        String viewedTaskId = view.viewingTaskId();
        if (viewedTaskId != null) {
            for (int i = 0; i < teammates.size(); i++) {
                if (viewedTaskId.equals(teammates.get(i).getTaskId())) {
                    viewed = i + 1;
                    break;
                }
            }
        }
        HorizontalScroll.Window window = HorizontalScroll.calculate(
            widths, Math.max(20, availableWidth), 2,
            pillSelected ? selected : viewed, true);
        List<TeammatePillView> visible = new ArrayList<>();
        for (int i = window.startIndex(); i < window.endIndex(); i++) {
            boolean idle = i > 0 && teammates.get(i - 1).isIdle();
            visible.add(new TeammatePillView(names.get(i), pillSelected && selected == i,
                viewed == i, idle));
        }
        return new TeammateFooterView(List.copyOf(visible),
            window.showLeftArrow(), window.showRightArrow());
    }

    private void cycleTeammate(int delta) {
        if (!isTeammateFooterVisible()) return;
        int count = 1 + runningTeammates().size();
        teammateFooterIndex = (teammateFooterIndex + delta + count) % count;
    }

    private void openSelectedTeammate(Host host) {
        List<InProcessTeammateTask> teammates = runningTeammates();
        int selected = Math.max(0, Math.min(teammateFooterIndex, teammates.size()));
        if (selected == 0) {
            view.exit();
            host.clearStatusLine();
            host.teammateViewChanged();
            deselectPill();
            return;
        }
        InProcessTeammateTask task = teammates.get(selected - 1);
        view.enterViewing(task.getTaskId(), selected - 1);
        host.showTeammateStatus(task);
        host.teammateViewChanged();
        deselectPill();
    }

    TextBox.Result handleTeammateKey(KeyStroke key, Host host) {
        if (key.getKeyType() == KeyType.ESCAPE) {
            if (view.isViewing()) {
                if (view.isViewingLocalAgent()) {
                    exitView(host, true);
                    return TextBox.Result.HANDLED;
                }
                String taskId = view.viewingTaskId();
                boolean running = taskId != null && registry.getTeammateHandle(taskId)
                    .map(InProcessTeammateTask::isRunning).orElse(false);
                if (running) {
                    registry.getTeammateHandle(taskId)
                        .ifPresent(InProcessTeammateTask::abortCurrentTurn);
                    host.showInterruptedHint();
                } else {
                    exitView(host, true);
                }
            } else {
                view.leaveSelecting();
                host.refreshHint();
            }
            return TextBox.Result.HANDLED;
        }
        if (key.isShiftDown() && !key.isCtrlDown() && !key.isAltDown()
                && (key.getKeyType() == KeyType.ARROW_UP
                    || key.getKeyType() == KeyType.ARROW_DOWN)) {
            if (view.isViewingLocalAgent()) return null;
            stepSelection(key.getKeyType() == KeyType.ARROW_DOWN ? 1 : -1, host);
            return TextBox.Result.HANDLED;
        }
        if (view.isSelecting()) {
            if (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() == 'f') {
                enterSelectedView(host);
                return TextBox.Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() == 'k') {
                killSelected(host);
                return TextBox.Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ENTER && !key.isShiftDown()) {
                confirmSelection(host);
                return TextBox.Result.HANDLED;
            }
        }
        return null;
    }

    TeammateHint teammateHint(Host host) {
        if (registry == null) return null;
        if (view.isViewing()) {
            if (view.isViewingLocalAgent()) {
                TaskState task = view.viewingTaskId() == null ? null
                    : registry.get(view.viewingTaskId()).orElse(null);
                if (task == null) {
                    view.exit();
                    host.clearStatusLine();
                    return null;
                }
                return new TeammateHint("Viewing @" + task.description(), "esc: return", true);
            }
            InProcessTeammateTask task = viewedTask();
            if (task == null) {
                view.exit();
                host.clearStatusLine();
                return null;
            }
            String name = task.name() == null ? task.getTaskId() : task.name();
            return new TeammateHint("Viewing @" + name,
                "esc: return · shift+tab: cycle mode · shift+↑/↓: switch", true);
        }
        return new TeammateHint("Teammates (" + runningTeammates().size() + " running)",
            "Shift+↑/↓: select · f/Enter: view · k: kill · Esc: exit", false);
    }

    void injectViewed(String text) {
        String taskId = view.viewingTaskId();
        if (taskId != null && text != null && !StringUtils.isBlank(text)) {
            if (view.isViewingLocalAgent()) {
                registry.queueAgentMessage(taskId, text);
                return;
            }
            registry.getTeammateHandle(taskId).ifPresent(t -> t.injectUserMessage(text));
        }
    }

    void cycleViewedPermissionMode(Host host) {
        if (view.isViewingLocalAgent()) return;
        String taskId = view.viewingTaskId();
        if (taskId == null) return;
        registry.getTeammateHandle(taskId).ifPresent(task -> {
            PermissionMode next = nextPermissionMode(task.permissionMode());
            task.setPermissionMode(next);
            host.showPermissionModeHint(next);
            host.refreshHint();
        });
    }

    private void stepSelection(int delta, Host host) {
        List<InProcessTeammateTask> teammates = runningTeammates();
        if (teammates.isEmpty()) {
            exitView(host, false);
            return;
        }
        int count = teammates.size();
        int current = Math.max(-1, view.selectedIndex());
        int next = delta > 0
            ? (current >= count ? -1 : current + 1)
            : (current <= -1 ? count : current - 1);
        view.enterSelecting(next);
        host.refreshHint();
    }

    private void enterSelectedView(Host host) {
        List<InProcessTeammateTask> teammates = runningTeammates();
        int index = view.selectedIndex();
        if (index < 0 || index >= teammates.size()) return;
        InProcessTeammateTask task = teammates.get(index);
        view.enterViewing(task.getTaskId(), index);
        host.showTeammateStatus(task);
        host.teammateViewChanged();
        host.refreshHint();
    }

    private void confirmSelection(Host host) {
        int index = view.selectedIndex();
        if (index < 0) {
            exitView(host, true);
        } else if (index >= runningTeammates().size()) {
            host.setTeammateTreeExpanded(false);
            view.leaveSelecting();
            host.refreshHint();
        } else {
            enterSelectedView(host);
        }
    }

    private void killSelected(Host host) {
        List<InProcessTeammateTask> teammates = runningTeammates();
        int index = view.selectedIndex();
        if (index < 0 || index >= teammates.size()) return;
        InProcessTeammateTask task = teammates.get(index);
        if (task.isRunning()) registry.killTeammate(task.getTaskId());
        if (view.isViewing() && task.getTaskId().equals(view.viewingTaskId())) {
            exitView(host, true);
        } else {
            host.refreshHint();
        }
    }

    private void exitView(Host host, boolean notify) {
        view.exit();
        host.clearStatusLine();
        if (notify) host.teammateViewChanged();
        host.refreshHint();
    }

    private InProcessTeammateTask viewedTask() {
        String taskId = view.viewingTaskId();
        return taskId == null ? null : registry.getTeammateHandle(taskId).orElse(null);
    }

    private List<InProcessTeammateTask> runningTeammates() {
        return registry == null ? List.of() : registry.listRunningTeammates();
    }

    static PermissionMode nextPermissionMode(PermissionMode current) {
        return switch (current) {
            case DEFAULT -> PermissionMode.ACCEPT_EDITS;
            case ACCEPT_EDITS -> PermissionMode.PLAN;
            case PLAN -> PermissionMode.BYPASS_PERMISSIONS;
            case BYPASS_PERMISSIONS -> PermissionMode.DEFAULT;
            default -> PermissionMode.DEFAULT;
        };
    }
}
