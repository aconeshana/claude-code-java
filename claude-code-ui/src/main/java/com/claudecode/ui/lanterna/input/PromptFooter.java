package com.claudecode.ui.lanterna.input;

import com.claudecode.permissions.PermissionMode;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.tools.tasks.InProcessTeammateTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.workflows.WorkflowRun;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Interactable.Result;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The prompt footer: every keyboard-selectable control below the input text
 * box and the single selection that walks between them.
 *
 * <p>The footer chain, left to right and top to bottom, is
 * {@code ≡} project button → background tasks pill → Collaboration →
 * subagent coordinator panel (with workflow rows nested inside it). The first
 * three stops share the hint row's visual line; the coordinator panel is its
 * own row beneath it, mounted only while bound and non-empty. Exactly one
 * stop may be selected; every transition here deselects the others, so the
 * widgets never disagree about who owns footer focus. Keys reach this class
 * only while a stop is selected (or while teammate navigation is active);
 * mouse events are hit-tested per widget by {@link InputPanel}'s window
 * listener.
 *
 * <p>The 1 s refresh tick runs off the GUI thread and must only descend into
 * this footer's own components (labels, panels) — never up into the panel
 * ancestry — to preserve Lanterna's top-down monitor order.
 *
 * <ul>
 *   <li>{@code src/components/PromptInput/PromptInputFooter.tsx} and
 *       {@code PromptInputFooterLeftSide.tsx} — footer part ordering
 *       ({@code [modePart][tasksPart][...parts]}) and the teammate strip.</li>
 *   <li>{@code src/components/CoordinatorAgentStatus.tsx} — the coordinator rows
 *       and workflow rows as one vertically navigable block.</li>
 *   <li>The {@code ≡} button and Collaboration pill are Java-side extensions.</li>
 * </ul>
 */
final class PromptFooter {

    /** What the footer needs from the surrounding prompt panel. */
    interface Host {
        /** Outward REPL action port; may be null in headless tests. */
        InputActions actions();
        /** Recomputes the hint row from current state. */
        void refreshHint();
        /** Clears transient (non-persistent) status-line text. */
        void clearStatusLine();
        void setTransientStatusLine(String text, int padding);
        void showTemporaryHint(String text, TextColor color, long timeoutMs);
        /** Current panel width in columns, for the teammate strip window. */
        int footerWidth();
        /** Runs {@code task} on the GUI thread, or inline when already there / no GUI. */
        void runOnGui(Runnable task);
    }

    /** Hint shown while a workflow row is selected. */
    record WorkflowHint(String main, String suffix) {}

    private final Host host;
    private final PromptTaskNavigationController taskNavigation =
        new PromptTaskNavigationController();
    private final ProjectsButton projectsButton = new ProjectsButton();
    private final TasksPill tasksPill = new TasksPill();
    private final WorkflowFooter workflows = new WorkflowFooter();
    private final CoordinatorFooter coordinator = new CoordinatorFooter();
    private final CollaborationPill collaboration = new CollaborationPill();
    /** Periodic pill refresh; runs only while the panel is attached to a GUI. */
    private ScheduledFuture<?> refreshFuture;

    private final PromptTaskNavigationController.Host taskNavigationHost =
        new PromptTaskNavigationController.Host() {
            @Override public void openTasksDialog() {
                InputActions actions = host.actions();
                if (actions != null) actions.openTasksDialog();
            }
            @Override public void refreshHint() { host.refreshHint(); }
            @Override public void clearStatusLine() { host.clearStatusLine(); }
            @Override public void showTeammateStatus(InProcessTeammateTask task) {
                String name = task.name() == null ? task.getTaskId() : task.name();
                String preview = task.lastMessagePreview(160).replace("\n", " ");
                host.setTransientStatusLine("Viewing @" + name + " — "
                    + (preview.isEmpty() ? "(idle)" : preview), 0);
            }
            @Override public void showInterruptedHint() {
                host.showTemporaryHint("Interrupted teammate turn (Esc)",
                    LanternaTheme.welcomeDim(), PromptTextBox.HINT_TIMEOUT_MS);
            }
            @Override public void showPermissionModeHint(PermissionMode mode) {
                host.showTemporaryHint("Teammate mode → " + mode.title(),
                    LanternaTheme.colorFor(mode), PromptTextBox.HINT_TIMEOUT_MS);
            }
            @Override public void teammateViewChanged() {
                InputActions actions = host.actions();
                if (actions != null) actions.teammateViewChanged();
            }
            @Override public void setTeammateTreeExpanded(boolean expanded) {
                taskNavigation.setTeammateTreeExpanded(expanded);
                InputActions actions = host.actions();
                if (actions != null) actions.setTeammateTreeExpanded(expanded);
            }
            @Override public boolean isTeammateTreeExpanded() {
                InputActions actions = host.actions();
                return actions != null && actions.isTeammateTreeExpanded();
            }
        };

    private final CoordinatorNavigationController.Host coordinatorNavigationHost =
        new CoordinatorNavigationController.Host() {
            @Override public void teammateViewChanged() {
                InputActions actions = host.actions();
                if (actions != null) actions.teammateViewChanged();
            }
            @Override public void refreshHint() {
                host.refreshHint();
                refreshCoordinatorPanel();
            }
            @Override public void clearStatusLine() { host.clearStatusLine(); }
        };

    PromptFooter(Host host) {
        this.host = host;
    }

    // ── Components mounted by the panel ─────────────────────────────────────

    Label projectsButtonComponent() { return projectsButton.component(); }

    Panel tasksPillsPanel() { return tasksPill.pillsPanel(); }

    Label tasksHintLabel() { return tasksPill.hintLabel(); }

    /** The " · " joining Collaboration to whatever precedes it in the hint row. */
    Label collaborationSeparatorLabel() { return collaboration.separatorLabel(); }

    /** Collaboration text, mounted inline as the hint row's final child. */
    Label collaborationLabel() { return collaboration.textLabel(); }

    Component coordinatorComponent() { return coordinator.component(); }

    // ── State queries ───────────────────────────────────────────────────────

    PromptTaskNavigationController taskNavigation() { return taskNavigation; }

    boolean isAnySelected() {
        return workflows.isSelected()
            || coordinator.isPanelSelected()
            || projectsButton.isSelected()
            || taskNavigation.isPillSelected()
            || collaboration.isSelected();
    }

    /**
     * Whether footer/teammate state must see every plain character before the
     * editor. Deliberately excludes the ≡ button, whose selection never
     * suppressed the direct-edit fast path.
     */
    boolean capturesPlainInput() {
        return taskNavigation.isActive()
            || taskNavigation.isPillSelected()
            || collaboration.isSelected()
            || workflows.isSelected()
            || coordinator.isPanelSelected();
    }

    boolean isCoordinatorPanelSelected() { return coordinator.isPanelSelected(); }

    boolean isTasksPillSelected() { return taskNavigation.isPillSelected(); }

    boolean isCollaborationPillSelected() { return collaboration.isSelected(); }

    boolean isProjectsButtonSelected() { return projectsButton.isSelected(); }

    boolean isProjectsButtonActive() { return projectsButton.isActive(); }

    boolean isWorkflowSelected() { return workflows.isSelected(); }

    int workflowIndex() { return workflows.index(); }

    String selectedWorkflowTaskId() { return workflows.selectedTaskId(); }

    int coordinatorIndex() {
        return coordinator.isBound() ? coordinator.navigation().coordinatorIndex()
            : Integer.MIN_VALUE;
    }

    String collaborationPillText() { return collaboration.text(); }

    String tasksPillText() { return tasksPill.pillText(); }

    String tasksHintText() { return tasksPill.hintText(); }

    boolean isTasksPillHovered() { return tasksPill.latch().hovered(); }

    /**
     * Hint for the selected workflow row, or null when no row is visible any
     * more (the selection is dropped and the caller falls back to the leader hint).
     */
    WorkflowHint workflowHint() {
        List<WorkflowRun> visible = workflows.visibleRuns();
        if (visible.isEmpty()) {
            workflows.blur();
            return null;
        }
        WorkflowRun run = workflows.current(visible);
        return new WorkflowHint("  enter view",
            " · x " + (run.status().hasResult() ? "clear" : "stop"));
    }

    PromptTaskNavigationController.TeammateHint teammateHint() {
        return taskNavigation.teammateHint(taskNavigationHost);
    }

    // ── Wiring ──────────────────────────────────────────────────────────────

    void setTaskRegistry(TaskRegistry registry) {
        workflows.setRegistry(registry);
        taskNavigation.setRegistry(registry);
        coordinator.setRegistry(registry);
        refreshPills();
        refreshCoordinatorPanel();
    }

    void setTeammateTreeExpanded(boolean expanded) {
        taskNavigation.setTeammateTreeExpanded(expanded);
    }

    void setWorkflowRunStore(WorkflowRunStore runs) {
        workflows.setRunStore(runs);
        refreshPills();
        refreshCoordinatorPanel();
    }

    /**
     * Binds the subagent coordinator panel — its navigation state machine plus
     * the view it renders into. The caller mounts {@link #coordinatorComponent()}.
     */
    void bindCoordinator(CoordinatorNavigationController navigation, CoordinatorPanelView view,
                         Function<String, String> agentNameResolver) {
        coordinator.bind(navigation, view, agentNameResolver);
        refreshCoordinatorPanel();
    }

    /** Binds the footer to the shared collaboration state. */
    synchronized void setCollaborationController(SessionCollaborationController controller) {
        collaboration.bind(controller, host::runOnGui, this::refreshPills);
        refreshPills();
    }

    /** Releases the collaboration listener when the REPL is shutting down. */
    synchronized void closeCollaborationBinding() {
        collaboration.close();
    }

    /** Mirrors the project drawer's open state on the ≡ button. */
    synchronized void setProjectsButtonActive(boolean active) {
        if (projectsButton.setActive(active)) refreshPills();
    }

    /**
     * Starts the live task-footer refresh after the REPL scene is attached.
     *
     * <p>The scheduler fires {@link #tick} on its own background thread, never
     * the GUI thread. {@code tick} itself does nothing but hand its whole body
     * off via {@link Host#runOnGui}, so every actual mutation — including the
     * ones that reach upward into {@code host.refreshHint()} (hint bar, other
     * footer children, vim label) — runs serialized on the GUI thread like any
     * other input event. This avoids acquiring Lanterna's component monitors
     * (top-down: TextGUI → window → container → leaf) from a background thread
     * out of order, which previously could deadlock against
     * {@code updateScreen} with no exception thrown.
     */
    synchronized void startRefresh(ScheduledExecutorService scheduler) {
        if (refreshFuture == null) {
            refreshFuture = scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        }
    }

    synchronized ScheduledFuture<?> refreshFutureForTest() { return refreshFuture; }

    synchronized void stopRefresh() {
        if (refreshFuture != null) {
            refreshFuture.cancel(false);
            refreshFuture = null;
        }
    }

    /**
     * One periodic tick: advance the subagent coordinator lifecycle (auto-exit +
     * 30 s grace eviction), repaint its panel, then refresh the teammate/tasks
     * footer. The coordinator and teammate subsystems are stepped independently.
     * Marshaled onto the GUI thread as a whole; see {@link #startRefresh}.
     */
    void tick() {
        host.runOnGui(this::tickOnGuiThread);
    }

    private void tickOnGuiThread() {
        if (coordinator.isBound()) {
            coordinator.navigation().tick(coordinatorNavigationHost);
            refreshCoordinatorPanel();
        }
        if (workflows.isSelected()) host.refreshHint();
        refreshTasksPill();
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    synchronized void refreshPills() {
        projectsButton.render();
        refreshTasksPill();
        collaboration.render();
    }

    /**
     * Recomputes the pill + its trailing hint from the live registry.
     *
     * <p>The coordinator is now the terminal footer stop, so if its content
     * vanishes (agents dismissed) while it owns selection, the selection must
     * retreat rather than dangle on a now-collapsed component: to the
     * background pill when one exists, else to Collaboration when even the
     * workflow rows are gone too.
     */
    synchronized void refreshTasksPill() {
        taskNavigation.synchronizeTeammateCount();
        if (coordinator.isBound()) {
            CoordinatorNavigationController nav = coordinator.navigation();
            boolean coordinatorWasSelected = nav.isPanelSelected();
            nav.synchronizeBackgroundPill(taskNavigation.pillAvailable());
            if (coordinatorWasSelected && !nav.isPanelSelected()) {
                if (taskNavigation.pillAvailable()) {
                    taskNavigation.selectPill();
                } else if (!workflows.hasRows()) {
                    workflows.blur();
                    collaboration.setSelected(true);
                }
            }
        }
        tasksPill.render(taskNavigation, host.footerWidth());
    }

    /** Repaints the coordinator rows from live navigation + workflow state; no-op until bound. */
    void refreshCoordinatorPanel() {
        if (!coordinator.isBound() || coordinator.view() == null) return;
        List<WorkflowRun> visible = workflows.visibleRuns();
        workflows.clamp();
        coordinator.refresh(visible, workflows.selectedIndexOrMinusOne(), workflows.registry());
    }

    // ── Selection transitions ───────────────────────────────────────────────

    /** The first ↓ from the prompt lands on the ≡ button, the leftmost stop. */
    void enterFromPrompt() {
        selectProjectsButton();
    }

    /** Drops any footer selection and collapses the teammate tree. */
    void clearSelection() {
        if (!isAnySelected()) return;
        taskNavigation.deselectPill();
        taskNavigationHost.setTeammateTreeExpanded(false);
        coordinator.deselect();
        workflows.deselect();
        collaboration.setSelected(false);
        projectsButton.setSelected(false);
        refreshCoordinatorPanel();
        refreshPills();
        host.refreshHint();
    }

    /** Selects the ≡ button as the sole footer selection. */
    private void selectProjectsButton() {
        workflows.deselect();
        collaboration.setSelected(false);
        taskNavigation.deselectPill();
        coordinator.deselect();
        projectsButton.setSelected(true);
        refreshCoordinatorPanel();
        refreshPills();
        host.refreshHint();
    }

    /**
     * ≡'s forward chain: tasks pill (free-standing only — a pill folded into
     * the coordinator's unified row is reached only once the coordinator
     * itself is selected) → Collaboration → coordinator panel (+ workflow rows).
     */
    private void selectFirstStopAfterProjectsButton() {
        projectsButton.setSelected(false);
        if (taskNavigation.pillAvailable() && !coordinator.panelAvailable()) {
            collaboration.setSelected(false);
            taskNavigation.selectPill();
            refreshPills();
            return;
        }
        if (coordinator.panelAvailable()) {
            workflows.blur();
            collaboration.setSelected(false);
            selectCoordinatorPanel();
            refreshCoordinatorPanel();
            refreshPills();
            return;
        }
        if (workflows.hasRows()) {
            collaboration.setSelected(false);
            selectCurrentWorkflow();
            return;
        }
        collaboration.setSelected(true);
        refreshPills();
    }

    /** Selects the coordinator panel, entering on the background pill row when one exists. */
    private void selectCoordinatorPanel() {
        boolean hasBackgroundPill = taskNavigation.pillAvailable();
        coordinator.navigation().selectPanel(hasBackgroundPill);
        if (hasBackgroundPill) taskNavigation.selectPill();
        else taskNavigation.deselectPill();
    }

    private void selectWorkflow(int index) {
        if (!workflows.select(index)) return;
        taskNavigation.deselectPill();
        collaboration.setSelected(false);
        coordinator.deselect();
        refreshCoordinatorPanel();
        refreshPills();
        host.refreshHint();
    }

    private void selectCurrentWorkflow() {
        selectWorkflow(workflows.index());
    }

    /** Selects the permanent footer item, entered from the tasks pill or as the sole stop. */
    private void selectCollaboration() {
        workflows.blur();
        taskNavigation.deselectPill();
        coordinator.deselect();
        collaboration.setSelected(true);
        refreshCoordinatorPanel();
        refreshPills();
        host.refreshHint();
    }

    /**
     * Moves from Collaboration to the preceding stop — the free-standing tasks
     * pill when one exists, otherwise ≡ itself. ≡ is always present, so this
     * always succeeds; Collaboration is never the leftmost reachable stop.
     */
    private void selectBeforeCollaboration() {
        if (taskNavigation.pillAvailable() && !coordinator.panelAvailable()) {
            collaboration.setSelected(false);
            taskNavigation.selectPill();
            refreshPills();
            host.refreshHint();
            return;
        }
        collaboration.setSelected(false);
        selectProjectsButton();
    }

    /**
     * Moves from Collaboration forward into the coordinator/workflow block;
     * false when nothing follows (Collaboration stays the terminal stop).
     */
    private boolean selectAfterCollaboration() {
        if (coordinator.panelAvailable()) {
            collaboration.setSelected(false);
            selectCoordinatorPanel();
            refreshCoordinatorPanel();
            refreshPills();
            host.refreshHint();
            return true;
        }
        if (workflows.hasRows()) {
            collaboration.setSelected(false);
            selectCurrentWorkflow();
            return true;
        }
        return false;
    }

    /** Moves from workflows to the preceding tasks group, if one is visible. */
    private boolean selectBeforeWorkflows(boolean exitAtStart) {
        if (coordinator.panelAvailable()) {
            workflows.deselect();
            selectCoordinatorPanel();
        } else if (taskNavigation.pillAvailable()) {
            workflows.deselect();
            taskNavigation.selectPill();
        } else if (exitAtStart) {
            // Nothing precedes the workflow block itself — retreat further,
            // to Collaboration, rather than dropping the footer selection.
            workflows.deselect();
            selectCollaboration();
            return true;
        } else {
            return false;
        }
        refreshCoordinatorPanel();
        refreshPills();
        host.refreshHint();
        return true;
    }

    /** Leaving the free-standing tasks pill forward — Collaboration is its only forward neighbor. */
    private void advanceFromTasksPill() {
        selectCollaboration();
    }

    /**
     * Coordinator's forward jump/boundary target: the workflow rows nested in
     * the same terminal block when any exist, otherwise a no-op — nothing
     * follows the coordinator/workflow block any more.
     */
    private void advanceFromCoordinator() {
        if (workflows.hasRows()) selectCurrentWorkflow();
    }

    // ── Keyboard protocol ───────────────────────────────────────────────────

    /**
     * Teammate navigation owns only its navigation keys; null lets every other
     * key fall through to the footer and editor stages.
     */
    Result handleTeammateKey(KeyStroke key) {
        if (!taskNavigation.isActive()) return null;
        boolean exitingLocalAgentView = key.getKeyType() == KeyType.ESCAPE
            && coordinator.isViewingLocalAgent();
        Result result = taskNavigation.handleTeammateKey(key, taskNavigationHost);
        if (result != null && exitingLocalAgentView) clearSelection();
        return result;
    }

    /** Shift+↑/↓ teammate stepping from the prompt. */
    void handleTeammateShiftSelection(int delta) {
        taskNavigation.handleShiftSelection(delta, taskNavigationHost);
    }

    /** Shift+Tab while viewing a teammate cycles that teammate's permission mode. */
    void cycleViewedTeammatePermissionMode() {
        taskNavigation.cycleViewedPermissionMode(taskNavigationHost);
    }

    /** Maps a resolved {@code footer:*} keybinding action onto the native footer key protocol. */
    boolean dispatchFooterAction(String action) {
        KeyStroke canonical = switch (action) {
            case "footer:up" -> new KeyStroke(KeyType.ARROW_UP);
            case "footer:down" -> new KeyStroke(KeyType.ARROW_DOWN);
            case "footer:next" -> new KeyStroke(KeyType.ARROW_RIGHT);
            case "footer:previous" -> new KeyStroke(KeyType.ARROW_LEFT);
            case "footer:openSelected" -> new KeyStroke(KeyType.ENTER);
            case "footer:clearSelection" -> new KeyStroke(KeyType.ESCAPE);
            case "footer:close" -> new KeyStroke('x', false, false);
            default -> null;
        };
        return canonical != null && handleSelectedKey(canonical) != null;
    }

    /**
     * Native key protocol for whichever stop is selected. Null means the key is
     * not a footer key and must continue to global Ctrl/Alt handling.
     *
     * <p>Order matters: {@code collaboration} and the free-standing tasks pill
     * are dispatched before {@code workflows}/{@code coordinator} because they
     * now precede the coordinator/workflow block in the chain. The
     * free-standing-pill branch is guarded by
     * {@code !coordinator.isPanelSelected()} so it never intercepts keys meant
     * for the coordinator's own unified pill row (index {@code -1}), which
     * also reports {@code taskNavigation.isPillSelected() == true}.
     */
    Result handleSelectedKey(KeyStroke key) {
        KeyStroke normalized = normalizeNativeFooterKey(key);
        if (projectsButton.isSelected()) {
            return handleProjectsButtonKey(normalized);
        }
        if (collaboration.isSelected()) {
            return handleCollaborationKey(normalized);
        }
        if (taskNavigation.isPillSelected() && !coordinator.isPanelSelected()) {
            KeyType type = normalized.getKeyType();
            if (type == KeyType.ARROW_DOWN || type == KeyType.ARROW_RIGHT) {
                advanceFromTasksPill();
                return Result.HANDLED;
            }
            if (type == KeyType.ARROW_LEFT) {
                // ← walks back left — from the tasks pill that is the ≡ button.
                selectProjectsButton();
                return Result.HANDLED;
            }
            return taskNavigation.handlePillKey(normalized, taskNavigationHost);
        }
        if (workflows.isSelected()) {
            Result r = handleWorkflowKey(normalized);
            if (r != null) return r;
        }
        // The coordinator panel owns footer focus independently of the teammate/bash pill.
        if (coordinator.isPanelSelected()) {
            return handleCoordinatorKey(normalized);
        }
        return null;
    }

    private static KeyStroke normalizeNativeFooterKey(KeyStroke key) {
        if (key.getKeyType() != KeyType.CHARACTER || key.getCharacter() == null
                || !key.isCtrlDown() || key.isAltDown() || key.isShiftDown()) {
            return key;
        }
        return switch (Character.toLowerCase(key.getCharacter())) {
            case 'p' -> new KeyStroke(KeyType.ARROW_UP);
            case 'n' -> new KeyStroke(KeyType.ARROW_DOWN);
            default -> key;
        };
    }

    /**
     * Keys while the ≡ projects button is selected: ↑/Esc leave the footer,
     * ↓/→ resume the released pill chain, Enter toggles the drawer.
     */
    private Result handleProjectsButtonKey(KeyStroke key) {
        KeyType type = key.getKeyType();
        if (type == KeyType.ARROW_UP || type == KeyType.ESCAPE) {
            clearSelection();
            host.refreshHint();
            return Result.HANDLED;
        }
        if (type == KeyType.ARROW_DOWN || type == KeyType.ARROW_RIGHT) {
            selectFirstStopAfterProjectsButton();
            host.refreshHint();
            return Result.HANDLED;
        }
        if (type == KeyType.ENTER && !key.isShiftDown()) {
            projectsButton.setSelected(false);
            refreshPills();
            host.refreshHint();
            InputActions actions = host.actions();
            if (actions != null) actions.toggleProjectPanel();
            return Result.HANDLED;
        }
        return Result.HANDLED; // ← is the leftmost stop; everything else is swallowed
    }

    private Result handleWorkflowKey(KeyStroke key) {
        List<WorkflowRun> visible = workflows.visibleRuns();
        if (visible.isEmpty()) {
            workflows.blur();
            refreshCoordinatorPanel();
            return null;
        }
        WorkflowRun current = workflows.current(visible);
        KeyType type = key.getKeyType();
        boolean plain = !key.isCtrlDown() && !key.isAltDown() && !key.isShiftDown();
        if (type == KeyType.ARROW_UP && plain) {
            if (!workflows.step(-1, visible)) {
                selectBeforeWorkflows(true);
                return Result.HANDLED;
            }
            refreshCoordinatorPanel();
            refreshPills();
            host.refreshHint();
            return Result.HANDLED;
        }
        if (type == KeyType.ARROW_DOWN && plain) {
            if (workflows.step(1, visible)) {
                refreshCoordinatorPanel();
                host.refreshHint();
            }
            // else: boundary no-op — workflow rows are the tail of the now-terminal
            // coordinator/workflow block; nothing follows them any more.
            return Result.HANDLED;
        }
        if (type == KeyType.ARROW_RIGHT && plain) {
            return Result.HANDLED;
        }
        if (type == KeyType.ARROW_LEFT && plain) {
            selectBeforeWorkflows(false);
            return Result.HANDLED;
        }
        if (type == KeyType.ENTER && !key.isShiftDown()) {
            workflows.deselect();
            refreshCoordinatorPanel();
            InputActions actions = host.actions();
            if (actions != null) actions.openWorkflowDialog(current.taskId());
            return Result.HANDLED;
        }
        if (type == KeyType.ESCAPE) {
            workflows.deselect();
            refreshCoordinatorPanel();
            host.refreshHint();
            return Result.HANDLED;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && plain && key.getCharacter() == 'x') {
            workflows.dismissOrKill(current);
            workflows.clamp();
            refreshCoordinatorPanel();
            refreshPills();
            host.refreshHint();
            return Result.HANDLED;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isCtrlDown() && !key.isAltDown()) {
            return Result.HANDLED;
        }
        return null;
    }

    /** Footer-context key routing while the subagent coordinator panel owns focus. */
    private Result handleCoordinatorKey(KeyStroke key) {
        CoordinatorNavigationController nav = coordinator.navigation();
        KeyType type = key.getKeyType();
        boolean plain = !key.isCtrlDown() && !key.isAltDown() && !key.isShiftDown();
        if (type == KeyType.ARROW_UP && plain) {
            int minimum = taskNavigation.pillAvailable() ? -1 : 0;
            if (nav.coordinatorIndex() > minimum) {
                nav.step(-1, coordinatorNavigationHost);
                if (nav.coordinatorIndex() < 0) taskNavigation.selectPill();
                else taskNavigation.deselectPill();
                refreshCoordinatorPanel();
                refreshPills();
                return Result.HANDLED;
            }
            // At the panel's first stop: retreat to Collaboration instead of
            // dropping the footer selection entirely.
            nav.deselectPanel();
            taskNavigation.deselectPill();
            selectCollaboration();
            return Result.HANDLED;
        }
        if (type == KeyType.ARROW_DOWN && plain) {
            if (nav.coordinatorIndex() >= nav.panelAgents().size()) {
                advanceFromCoordinator();
                return Result.HANDLED;
            }
            nav.step(1, coordinatorNavigationHost);
            if (nav.coordinatorIndex() >= 0) taskNavigation.deselectPill();
            refreshCoordinatorPanel();
            refreshPills();
            return Result.HANDLED;
        }
        if (type == KeyType.ARROW_RIGHT && plain) {
            advanceFromCoordinator();
            return Result.HANDLED;
        }
        if (type == KeyType.ARROW_LEFT && plain) {
            return Result.HANDLED;
        }
        if (type == KeyType.ENTER && !key.isShiftDown()) {
            if (nav.coordinatorIndex() < 0) {
                nav.deselectPanel();
                taskNavigation.handlePillAction("footer:openSelected", taskNavigationHost);
            } else {
                nav.openSelected(coordinatorNavigationHost);
            }
            refreshCoordinatorPanel();
            refreshPills();
            return Result.HANDLED;
        }
        if (type == KeyType.ESCAPE) {
            if (nav.handleEscape(coordinatorNavigationHost)) {
                taskNavigation.deselectPill();
                refreshCoordinatorPanel();
                refreshPills();
                return Result.HANDLED;
            }
            return null;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && plain && key.getCharacter() == 'x') {
            if (nav.coordinatorIndex() > 0 && !nav.isViewingSelectedAgent()) {
                nav.dismissSelected(coordinatorNavigationHost);
                refreshCoordinatorPanel();
                refreshPills();
                return Result.HANDLED;
            }
            if (nav.coordinatorIndex() <= 0) {
                return Result.HANDLED;
            }
            clearSelection();
            return null;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isCtrlDown() && !key.isAltDown()) {
            if (nav.isViewingSelectedAgent()) {
                clearSelection();
                return null;
            }
            return Result.HANDLED;
        }
        // Ctrl/Alt combos fall through to global handling.
        return null;
    }

    private Result handleCollaborationKey(KeyStroke key) {
        KeyType type = key.getKeyType();
        if (type == KeyType.ARROW_UP) {
            selectBeforeCollaboration();
            return Result.HANDLED;
        }
        if (type == KeyType.ESCAPE) {
            collaboration.setSelected(false);
            refreshPills();
            return Result.HANDLED;
        }
        if (type == KeyType.ARROW_LEFT) {
            selectBeforeCollaboration();
            return Result.HANDLED;
        }
        if (type == KeyType.ENTER) {
            collaboration.setSelected(false);
            refreshPills();
            InputActions actions = host.actions();
            if (actions != null) actions.openCollaborationPicker();
            return Result.HANDLED;
        }
        if (type == KeyType.ARROW_DOWN || type == KeyType.ARROW_RIGHT) {
            selectAfterCollaboration();
            return Result.HANDLED;
        }
        if (type == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null) {
            char ch = Character.toLowerCase(key.getCharacter());
            if (ch == 'p') {
                selectBeforeCollaboration();
                return Result.HANDLED;
            }
            if (ch == 'n') {
                selectAfterCollaboration();
                return Result.HANDLED;
            }
            return null;
        }
        if (key.isCtrlDown() || key.isAltDown()) return null;
        return Result.HANDLED;
    }

    // ── Mouse protocol ──────────────────────────────────────────────────────

    boolean handleTasksPillMouse(MouseAction mouse, TerminalPosition origin, TerminalSize size) {
        FooterMouseLatch latch = tasksPill.latch();
        if (mouse == null || origin == null || size == null
                || !taskNavigation.pillAvailable()
                || taskNavigation.isTeammateFooterVisible()) {
            latch.reset();
            return false;
        }
        FooterMouseLatch.Outcome outcome = latch.track(mouse,
            FooterMouseLatch.rectTarget(mouse.getPosition(), origin, size));
        if (outcome.hoverChanged()) refreshTasksPill();
        if (outcome.activated()) {
            taskNavigation.deselectPill();
            InputActions actions = host.actions();
            if (actions != null) actions.openTasksDialog();
            refreshTasksPill();
        }
        return outcome.consumed();
    }

    /** Click handling for the footer ≡ button; release inside toggles the drawer. */
    boolean handleProjectsButtonMouse(MouseAction mouse, TerminalPosition origin,
                                      TerminalSize size) {
        FooterMouseLatch latch = projectsButton.latch();
        if (mouse == null || origin == null || size == null) {
            latch.reset();
            return false;
        }
        FooterMouseLatch.Outcome outcome = latch.track(mouse,
            FooterMouseLatch.rectTarget(mouse.getPosition(), origin, size));
        if (outcome.hoverChanged()) projectsButton.render();
        if (outcome.activated()) {
            projectsButton.setSelected(false);
            refreshPills();
            host.refreshHint();
            InputActions actions = host.actions();
            if (actions != null) actions.toggleProjectPanel();
        }
        return outcome.consumed();
    }

    /**
     * Click/hover handling for the {@code main}/subagent coordinator rows: the hit
     * target is a content row rather than a fixed rectangle, so a release must
     * land on the row that was pressed.
     */
    boolean handleCoordinatorPanelMouse(MouseAction mouse, TerminalPosition origin,
                                        TerminalSize size) {
        CoordinatorPanelView view = coordinator.view();
        CoordinatorNavigationController nav = coordinator.navigation();
        FooterMouseLatch latch = coordinator.latch();
        if (mouse == null || origin == null || size == null || view == null || nav == null) {
            if (view != null) view.setHoveredRow(-1);
            latch.reset();
            return false;
        }
        CoordinatorFooter.Hit hit = coordinator.hit(mouse.getPosition(), origin, size);
        FooterMouseLatch.Outcome outcome = latch.track(mouse, hit.target());
        if (mouse.getActionType() == MouseActionType.MOVE) {
            view.setHoveredRow(latch.hoveredTarget());
        }
        if (outcome.activated()) {
            nav.selectAndOpen(hit.coordinatorIndex(), coordinatorNavigationHost);
            refreshCoordinatorPanel();
            refreshPills();
            host.refreshHint();
        }
        return outcome.consumed();
    }
}
