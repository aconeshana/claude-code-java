package com.claudecode.ui.lanterna.input;

import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.workflows.WorkflowRun;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.Component;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Binding between the subagent coordinator navigation state machine and the
 * panel view it renders into, so a single tick can advance the model and
 * repaint the rows.
 *
 * <ul>
 *   <li>{@code src/components/CoordinatorAgentStatus.tsx} — the persistent
 *       {@code main} + local-agent row list below the prompt; this class owns its
 *       projection and mouse hit-testing, {@link CoordinatorNavigationController}
 *       owns the selection.</li>
 * </ul>
 */
final class CoordinatorFooter {

    private CoordinatorNavigationController navigation;
    private CoordinatorPanelView view;
    /** Lanterna component backing {@link #view}, when it has one. */
    private Component component;
    private Function<String, String> nameResolver = _ -> null;
    private final FooterMouseLatch latch = new FooterMouseLatch();

    /** Hit-test result for one pointer position over the panel. */
    record Hit(int contentRow, int coordinatorIndex) {
        static final Hit NONE = new Hit(FooterMouseLatch.OUTSIDE, -1);

        int target() { return coordinatorIndex >= 0 ? contentRow : FooterMouseLatch.OUTSIDE; }
    }

    void bind(CoordinatorNavigationController navigation, CoordinatorPanelView view,
              Function<String, String> agentNameResolver) {
        this.navigation = navigation;
        this.view = view;
        this.component = view instanceof Component c ? c : null;
        this.nameResolver = agentNameResolver != null ? agentNameResolver : _ -> null;
    }

    boolean isBound() { return navigation != null; }

    CoordinatorNavigationController navigation() { return navigation; }

    CoordinatorPanelView view() { return view; }

    Component component() { return component; }

    FooterMouseLatch latch() { return latch; }

    boolean panelAvailable() {
        return navigation != null && navigation.panelAvailable();
    }

    boolean isPanelSelected() {
        return navigation != null && navigation.isPanelSelected();
    }

    boolean isViewingLocalAgent() {
        return navigation != null && navigation.isViewingLocalAgent();
    }

    void deselect() {
        if (navigation != null) navigation.deselectPanel();
    }

    void setRegistry(TaskRegistry registry) {
        if (navigation != null) navigation.setRegistry(registry);
    }

    /**
     * Rebuilds the panel snapshot from the live navigation state: the visible
     * panel agents, the selection (only while the panel owns focus), and which
     * subagent transcript is being viewed. No-op until bound. Thread-agnostic:
     * the view is thread-safe.
     */
    void refresh(List<WorkflowRun> workflows, int selectedWorkflowIndex, TaskRegistry registry) {
        CoordinatorNavigationController nav = navigation;
        CoordinatorPanelView panel = view;
        if (nav == null || panel == null) return;
        List<TaskState> agents = nav.panelAgents();
        int selectedIndex = nav.isPanelSelected() ? nav.coordinatorIndex() : -1;
        String viewingTaskId = nav.isViewingLocalAgent()
            ? ViewedTeammateHolder.instance().viewingTaskId() : null;
        panel.refresh(agents, workflows, selectedIndex, selectedWorkflowIndex, viewingTaskId,
            Instant.now(), nameResolver,
            registry == null ? _ -> 0 : registry::pendingAgentMessageCount);
    }

    /** Resolves the pointer to a content row and the coordinator index rendered there. */
    Hit hit(TerminalPosition point, TerminalPosition origin, TerminalSize size) {
        CoordinatorPanelView panel = view;
        if (panel == null) return Hit.NONE;
        boolean insideCols = point.getColumn() >= origin.getColumn()
            && point.getColumn() < origin.getColumn() + size.getColumns();
        int contentRow = point.getRow() - origin.getRow() - 1; // row 0 is the blank margin
        boolean insideRows = insideCols && contentRow >= 0
            && point.getRow() < origin.getRow() + size.getRows();
        int coordinatorIndex = insideRows
            ? panel.coordinatorIndexForRow(contentRow, size.getColumns()) : -1;
        return new Hit(contentRow, coordinatorIndex);
    }
}
