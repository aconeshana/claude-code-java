package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.ui.lanterna.repl.CoordinatorTaskPanel;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mouse hover/click tests for the subagent coordinator panel — mirrors {@link
 * InputPanelTasksPillTest}'s press/release-latch conventions, but the panel is a
 * variable-height row list, so the hit test resolves a content row via {@link
 * CoordinatorPanelView#coordinatorIndexForRow} instead of a fixed rect.
 */
class InputPanelCoordinatorPanelMouseTest {

    private static final class RecordingActions implements InputActions {
        final AtomicInteger teammateViewChangedCalls = new AtomicInteger();
        @Override public void submit(String text) {}
        @Override public void cancel() {}
        @Override public void showMessageSelector() {}
        @Override public void toggleTranscript() {}
        @Override public void transcriptShowAll() {}
        @Override public void redrawScreen() {}
        @Override public void externalEditor() {}
        @Override public void stash() {}
        @Override public void undo() {}
        @Override public void permissionModeChanged(String uiMode) {}
        @Override public void openTasksDialog() {}
        @Override public void openWorkflowDialog(String taskId) {}
        @Override public void openCollaborationPicker() {}
        @Override public void teammateViewChanged() { teammateViewChangedCalls.incrementAndGet(); }
        @Override public void toggleMessageActions() {}
        @Override public void messageActionsPrev() {}
        @Override public void messageActionsNext() {}
        @Override public void messageActionsCopy() {}
        @Override public void messageActionsEdit() {}
        @Override public void queryChanged(String text, int cursor) {}
        @Override public void pastedContentsChanged(Map<Integer, PastedContent> contents) {}
        @Override public void cursorStyleChanged(CursorStyle style) {}
        @Override public void focusChanged(boolean focused) {}
    }

    private record Fixture(InputPanel panel, RecordingActions actions, TaskRegistry registry,
                            CoordinatorTaskPanel coordinatorPanel) {}

    private static Fixture fixture() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        InputPanel panel = new InputPanel();
        panel.setTaskRegistry(registry);
        RecordingActions actions = new RecordingActions();
        panel.setActions(actions);
        panel.setCollaborationController(collaborationController());
        CoordinatorTaskPanel coordinatorPanel = new CoordinatorTaskPanel();
        CoordinatorNavigationController navigation = new CoordinatorNavigationController(registry);
        panel.setCoordinatorNavigation(navigation, coordinatorPanel, registry::resolveAgentName);
        return new Fixture(panel, actions, registry, coordinatorPanel);
    }

    private static SessionCollaborationController collaborationController() {
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo("session-1", "/project", "", 0, null, ""),
            new SessionEventHub(new SessionSink() {
                @Override public void onTurnStart(UserInput input) {}
                @Override public void onMessage(SDKMessage message) {}
                @Override public void onError(Throwable error, boolean userCancel) {}
                @Override public void onTurnComplete(TurnOutcome outcome) {}
                @Override public void onIdle() {}
            }, _ -> {}), _ -> CompletableFuture.completedFuture(null));
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletionStage<SessionHostSession> activate(SessionOpenRequest request) {
                return CompletableFuture.completedFuture(session);
            }
            @Override public List<SessionHostInfo> list() { return List.of(session.info()); }
        });
        registry.activateLocal(session);
        return new SessionCollaborationController(registry);
    }

    private static TaskState runningAgent(TaskRegistry registry, String desc) {
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, desc);
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        return task;
    }

    private static final TerminalPosition ORIGIN = new TerminalPosition(0, 0);
    private static final TerminalSize SIZE = new TerminalSize(80, 10);

    // Content row 0 = main; content row 1 = the first (and here, only) agent row.
    private static TerminalPosition atContentRow(int contentRow) {
        return new TerminalPosition(5, contentRow + 1);
    }

    @Test
    void movingOntoMainRowHighlightsItAndMovingAwayClearsHover() {
        Fixture f = fixture();
        runningAgent(f.registry(), "agent");
        f.panel().refreshCoordinatorPanel();

        assertTrue(f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.MOVE, 0, atContentRow(0)), ORIGIN, SIZE));

        assertFalse(f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.MOVE, 0, new TerminalPosition(5, 9)), ORIGIN, SIZE),
            "moving off the panel's selectable rows must not consume the event");
    }

    @Test
    void clickingAgentRowEntersThatAgentsTranscript() {
        Fixture f = fixture();
        TaskState agent = runningAgent(f.registry(), "explore");
        f.panel().refreshCoordinatorPanel();
        TerminalPosition point = atContentRow(1); // first agent row

        assertTrue(f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.CLICK_DOWN, 1, point), ORIGIN, SIZE));
        assertTrue(f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.CLICK_RELEASE, 1, point), ORIGIN, SIZE));

        assertEquals(1, f.panel().coordinatorIndexForTest());
        assertEquals(1, f.actions().teammateViewChangedCalls.get());
    }

    @Test
    void clickingMainRowReturnsToMainFromAnAgentView() {
        Fixture f = fixture();
        runningAgent(f.registry(), "explore");
        f.panel().refreshCoordinatorPanel();
        TerminalPosition agentPoint = atContentRow(1);
        f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.CLICK_DOWN, 1, agentPoint), ORIGIN, SIZE);
        f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.CLICK_RELEASE, 1, agentPoint), ORIGIN, SIZE);
        assertEquals(1, f.panel().coordinatorIndexForTest());

        TerminalPosition mainPoint = atContentRow(0);
        f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.CLICK_DOWN, 1, mainPoint), ORIGIN, SIZE);
        f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.CLICK_RELEASE, 1, mainPoint), ORIGIN, SIZE);

        assertEquals(0, f.panel().coordinatorIndexForTest());
        assertEquals(2, f.actions().teammateViewChangedCalls.get());
    }

    @Test
    void clickingOutsidePanelRowsIsANoOp() {
        Fixture f = fixture();
        runningAgent(f.registry(), "agent");
        f.panel().refreshCoordinatorPanel();
        TerminalPosition outside = new TerminalPosition(5, 9);

        assertFalse(f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.CLICK_DOWN, 1, outside), ORIGIN, SIZE));
        assertFalse(f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.CLICK_RELEASE, 1, outside), ORIGIN, SIZE));

        assertEquals(0, f.actions().teammateViewChangedCalls.get());
    }

    @Test
    void pressOnOneRowAndReleaseOnAnotherDoesNotActivate() {
        Fixture f = fixture();
        runningAgent(f.registry(), "explore");
        f.panel().refreshCoordinatorPanel();
        TerminalPosition mainPoint = atContentRow(0);
        TerminalPosition agentPoint = atContentRow(1);

        assertTrue(f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.CLICK_DOWN, 1, mainPoint), ORIGIN, SIZE));
        assertTrue(f.panel().handleCoordinatorPanelMouseForTest(
            new MouseAction(MouseActionType.CLICK_RELEASE, 1, agentPoint), ORIGIN, SIZE),
            "the release must still be consumed (a latch was armed) even though it misses");

        assertEquals(0, f.actions().teammateViewChangedCalls.get(),
            "split press/release across different rows must not activate either one");
    }
}
