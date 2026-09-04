package com.claudecode.ui.lanterna.input;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.PastedContent;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.tools.workflows.WorkflowRun;
import com.claudecode.tools.workflows.WorkflowRunStore;
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
import com.claudecode.ui.lanterna.repl.CoordinatorTaskPanel;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * State-machine tests for {@link InputPanel}'s background-tasks footer pill — drives {@link
 * InputPanel#handleKeyForTest} directly against an in-memory {@link TaskRegistry} (no GUI thread,
 * no real {@code ~/.claude}).
 */
class InputPanelTasksPillTest {

    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);
    private static final KeyStroke SHIFT_DOWN = new KeyStroke(KeyType.ARROW_DOWN, false, false, true);

    /** Records outward calls; every abstract InputActions method is a no-op. */
    private static final class RecordingActions implements InputActions {
        final AtomicInteger openTasksDialogCalls = new AtomicInteger();
        final AtomicInteger submitCalls = new AtomicInteger();
        final AtomicInteger cancelCalls = new AtomicInteger();
        final AtomicInteger openCollaborationPickerCalls = new AtomicInteger();
        final AtomicInteger teammateViewChangedCalls = new AtomicInteger();
        final AtomicReference<String> openedWorkflowTaskId = new AtomicReference<>();
        @Override public void submit(String text) { submitCalls.incrementAndGet(); }
        @Override public void cancel() { cancelCalls.incrementAndGet(); }
        @Override public void showMessageSelector() {}
        @Override public void toggleTranscript() {}
        @Override public void transcriptShowAll() {}
        @Override public void redrawScreen() {}
        @Override public void externalEditor() {}
        @Override public void stash() {}
        @Override public void undo() {}
        @Override public void permissionModeChanged(String uiMode) {}
        @Override public void openTasksDialog() { openTasksDialogCalls.incrementAndGet(); }
        @Override public void openWorkflowDialog(String taskId) {
            openedWorkflowTaskId.set(taskId);
        }
        @Override public void openCollaborationPicker() {
            openCollaborationPickerCalls.incrementAndGet();
        }
        @Override public void teammateViewChanged() {
            teammateViewChangedCalls.incrementAndGet();
        }
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

    private record Fixture(InputPanel panel, RecordingActions actions, TaskRegistry registry) {}

    private static Fixture fixture() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        InputPanel panel = new InputPanel();
        panel.setTaskRegistry(registry);
        RecordingActions actions = new RecordingActions();
        panel.setActions(actions);
        panel.setCollaborationController(collaborationFixture().controller());
        return new Fixture(panel, actions, registry);
    }

    private record CollaborationFixture(
            SessionCollaborationController controller, SessionHostInfo info) {}

    private static CollaborationFixture collaborationFixture() {
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
            @Override public CompletionStage<SessionHostSession> activate(
                    SessionOpenRequest request) {
                return CompletableFuture.completedFuture(session);
            }
            @Override public List<SessionHostInfo> list() { return List.of(session.info()); }
        });
        registry.activateLocal(session);
        SessionCollaborationController controller = new SessionCollaborationController(registry);
        controller.replaceAvailableChannels(List.of("feishu"));
        return new CollaborationFixture(controller, session.info());
    }

    @Test
    void collaborationIsPermanentFooterEntryAndOpensWithoutSubmitting() {
        Fixture f = fixture();

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → Collaboration
        assertTrue(f.panel().isCollaborationPillSelected());
        assertEquals("Collaboration: Off", f.panel().collaborationPillTextForTest());

        f.panel().handleKeyForTest(ENTER);
        assertEquals(1, f.actions().openCollaborationPickerCalls.get());
        assertEquals(0, f.actions().submitCalls.get());
        assertFalse(f.panel().isCollaborationPillSelected());
    }

    @Test
    void collaborationEnterWithDraftOpensPickerAndPreservesDraft() {
        Fixture f = fixture();
        f.panel().setText("keep this draft");

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → Collaboration
        f.panel().handleKeyForTest(ENTER);

        assertEquals(1, f.actions().openCollaborationPickerCalls.get());
        assertEquals(0, f.actions().submitCalls.get());
        assertEquals("keep this draft", f.panel().getText());
    }

    @Test
    void collaborationDownAtFinalFooterIsBoundaryNoOp() {
        Fixture f = fixture();
        f.panel().handleKeyForTest(DOWN);

        f.panel().handleKeyForTest(DOWN);

        assertTrue(f.panel().isCollaborationPillSelected());
        assertEquals(0, f.actions().openCollaborationPickerCalls.get(),
            "197 footer:down only advances; opening is footer:openSelected");
    }

    @Test
    void remoteCollaborationSelectionRefreshesFooterWithoutLocalInteraction() {
        CollaborationFixture collaboration = collaborationFixture();
        InputPanel panel = new InputPanel();
        AtomicReference<Runnable> guiRefresh = new AtomicReference<>();
        panel.setGuiInvoker(guiRefresh::set);
        panel.setCollaborationController(collaboration.controller());
        assertEquals("Collaboration: Off", panel.collaborationPillTextForTest());

        collaboration.controller().selectRemote(collaboration.info(), "feishu");

        assertEquals("Collaboration: Off", panel.collaborationPillTextForTest(),
            "a Session Link worker must not mutate Lanterna components directly");
        guiRefresh.get().run();
        assertEquals("Collaboration: Feishu", panel.collaborationPillTextForTest());
    }

    @Test
    void collaborationFooterRemainsReachableWhileModelIsRunning() {
        Fixture f = fixture();
        f.panel().setIsLoading(true);

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → Collaboration
        assertTrue(f.panel().isCollaborationPillSelected());
        f.panel().handleKeyForTest(ENTER);

        assertEquals(1, f.actions().openCollaborationPickerCalls.get());
        assertEquals(0, f.actions().submitCalls.get());
    }

    @Test
    void rightArrowMovesFromTasksToCollaborationAndLeftMovesBack() {
        Fixture f = fixture();
        runningShell(f.registry(), "task");

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → pill
        assertTrue(f.panel().isTasksPillSelected());
        f.panel().handleKeyForTest(new KeyStroke(KeyType.ARROW_RIGHT));
        assertTrue(f.panel().isCollaborationPillSelected());
        f.panel().handleKeyForTest(new KeyStroke(KeyType.ARROW_LEFT));
        assertTrue(f.panel().isTasksPillSelected());
    }

    private static TaskState runningShell(TaskRegistry registry, String command) {
        TaskState t = registry.store().create(TaskType.LOCAL_BASH, command);
        registry.store().updateStatus(t.id(), TaskStatus.RUNNING);
        return t;
    }

    // ── ↓ entry: last line + history at bottom + tasks present ────────────

    @Test
    void downArrow_noTasks_doesNotSelectPill() {
        Fixture f = fixture();
        f.panel().handleKeyForTest(DOWN);
        assertFalse(f.panel().isTasksPillSelected());
        assertEquals(0, f.actions().openTasksDialogCalls.get());
    }

    @Test
    void downArrow_withTasks_selectsPill_thenMovesToFollowingCollaborationFooter() {
        Fixture f = fixture();
        runningShell(f.registry(), "npm run build");

        f.panel().handleKeyForTest(DOWN);
        assertTrue(f.panel().isProjectsButtonSelectedForTest(),
            "first ↓ at history bottom selects the ≡ projects button (extension stop)");
        f.panel().handleKeyForTest(DOWN);
        assertTrue(f.panel().isTasksPillSelected(), "the next ↓ resumes the released chain at the pill");
        assertEquals(0, f.actions().openTasksDialogCalls.get(), "footer entry must not open the dialog yet");

        f.panel().handleKeyForTest(DOWN);
        assertEquals(0, f.actions().openTasksDialogCalls.get(),
            "197 footer:down advances to the next footer item before opening tasks");
        assertFalse(f.panel().isTasksPillSelected());
        assertTrue(f.panel().isCollaborationPillSelected());
    }

    @Test
    void downArrow_multilineCursorNotOnLastLine_movesCursor_neverSelectsPill() {
        Fixture f = fixture();
        runningShell(f.registry(), "task");
        // Build a 2-line input: "a" + Shift+Enter + "b"; caret ends on line 1.
        f.panel().handleKeyForTest(new KeyStroke('a', false, false));
        f.panel().handleKeyForTest(new KeyStroke(KeyType.ENTER, false, false, true)); // Shift+Enter newline
        f.panel().handleKeyForTest(new KeyStroke('b', false, false));
        f.panel().handleKeyForTest(UP);   // caret to line 0 (cursor move, not history)
        f.panel().handleKeyForTest(DOWN); // caret back to line 1 — cursor move only

        assertFalse(f.panel().isTasksPillSelected(),
            "↓ must move the cursor inside multi-line input, not jump to the footer");
    }

    @Test
    void downArrow_whileBrowsingHistory_navigatesHistoryFirst_thenSelectsPill(@TempDir Path tmp) {
        Fixture f = fixture();
        runningShell(f.registry(), "task");
        Path historyFile = tmp.resolve("history.jsonl");
        PromptHistory history = new PromptHistory(historyFile);
        history.addEntry("echo older", "s1", "/proj");
        // Wait for the async flush to land on disk so this test exercises
        // footer-entry precedence against a fully-persisted history.
        // (Flush-window visibility is covered by PromptHistoryTest.)
        awaitTrue(() -> {
            try {
                return Files.isRegularFile(historyFile)
                    && Strings.CS.contains(Files.readString(historyFile), "echo older");
            } catch (Exception _) { return false; }
        });
        f.panel().setPromptHistory(history);
        f.panel().setHistoryContext("s1", "/proj");

        f.panel().handleKeyForTest(UP); // browse into history (index 1)
        awaitTrue(() -> Strings.CS.equals("echo older", f.panel().getText()));
        assertEquals("echo older", f.panel().getText(), "↑ must have loaded the history entry");
        f.panel().handleKeyForTest(DOWN); // returns to draft (index 0) — NOT the pill
        assertFalse(f.panel().isTasksPillSelected(),
            "↓ while browsing history must step back toward the draft first");

        f.panel().handleKeyForTest(DOWN); // at bottom now → ≡ projects button (extension stop)
        assertTrue(f.panel().isProjectsButtonSelectedForTest());
        f.panel().handleKeyForTest(DOWN); // → pill
        assertTrue(f.panel().isTasksPillSelected());
    }

    /** Polls until {@code condition} is true or a 10s deadline (20ms steps). */
    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition did not become true within 10s");
            }
            try { Thread.sleep(20); } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted");
            }
        }
    }

    // ── selected-state keys ───────────────────────────────────────────────

    @Test
    void upArrow_whilePillSelected_returnsToInput() {
        Fixture f = fixture();
        runningShell(f.registry(), "task");
        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → pill
        assertTrue(f.panel().isTasksPillSelected());

        f.panel().handleKeyForTest(UP);

        assertFalse(f.panel().isTasksPillSelected());
        assertEquals(0, f.actions().openTasksDialogCalls.get());
    }

    @Test
    void escape_whilePillSelected_clearsSelectionOnly() {
        Fixture f = fixture();
        runningShell(f.registry(), "task");
        f.panel().handleKeyForTest(DOWN);

        f.panel().handleKeyForTest(ESC);

        assertFalse(f.panel().isTasksPillSelected());
        assertEquals(0, f.actions().openTasksDialogCalls.get());
    }

    @Test
    void enter_whilePillSelectedAndDraftPresent_opensDialogWithoutSubmitting() {
        Fixture f = fixture();
        runningShell(f.registry(), "task");
        f.panel().handleKeyForTest(new KeyStroke('h', false, false)); // draft text present
        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → pill
        assertTrue(f.panel().isTasksPillSelected());

        f.panel().handleKeyForTest(ENTER);

        assertEquals(1, f.actions().openTasksDialogCalls.get());
        assertEquals(0, f.actions().submitCalls.get(),
            "197's onSubmit footer guard leaves Enter owned by footer:openSelected");
        assertEquals("h", f.panel().getText(), "opening a footer must preserve the draft");
        assertFalse(f.panel().isTasksPillSelected());
    }

    @Test
    void enter_whilePillSelectedAndInputEmpty_opensDialog() {
        Fixture f = fixture();
        runningShell(f.registry(), "task");
        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → pill

        f.panel().handleKeyForTest(ENTER);

        assertEquals(1, f.actions().openTasksDialogCalls.get());
        assertEquals(0, f.actions().submitCalls.get());
        assertFalse(f.panel().isTasksPillSelected());
    }

    @Test
    void plainTyping_whilePillSelected_isDropped() {
        Fixture f = fixture();
        runningShell(f.registry(), "task");
        f.panel().handleKeyForTest(new KeyStroke('a', false, false));
        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → pill
        assertTrue(f.panel().isTasksPillSelected());

        f.panel().handleKeyForTest(new KeyStroke('z', false, false));

        assertEquals("a", f.panel().getText(),
            "typed characters go nowhere while the pill is selected (TS TextInput focus=false)");
        assertTrue(f.panel().isTasksPillSelected(), "typing does not clear the selection");
    }

    // ── Shift+↓ direct open ───────────────────────────────────────────────

    @Test
    void shiftDown_withTasks_opensDialogDirectly_fromAnyState() {
        Fixture f = fixture();
        runningShell(f.registry(), "task");

        f.panel().handleKeyForTest(SHIFT_DOWN);

        assertEquals(1, f.actions().openTasksDialogCalls.get());
        assertFalse(f.panel().isTasksPillSelected());
    }

    @Test
    void shiftDown_noTasks_isConsumedNoOp() {
        Fixture f = fixture();

        f.panel().handleKeyForTest(SHIFT_DOWN);

        assertEquals(0, f.actions().openTasksDialogCalls.get());
        assertFalse(f.panel().isTasksPillSelected());
    }

    @Test
    void clickingVisibleShellPillOpensTasksDialog() {
        Fixture f = fixture();
        runningShell(f.registry(), "npm test");
        f.panel().refreshTasksPill();
        TerminalPosition origin = new TerminalPosition(4, 8);
        TerminalSize size = new TerminalSize(8, 1);

        assertTrue(f.panel().handleTasksPillMouseForTest(new MouseAction(
            MouseActionType.CLICK_DOWN, 1, new TerminalPosition(5, 8)), origin, size));
        assertTrue(f.panel().handleTasksPillMouseForTest(new MouseAction(
            MouseActionType.CLICK_RELEASE, 1, new TerminalPosition(5, 8)), origin, size));

        assertEquals(1, f.actions().openTasksDialogCalls.get());
    }

    @Test
    void clickingOutsideShellPillRemainsAvailableForTextSelection() {
        Fixture f = fixture();
        runningShell(f.registry(), "npm test");
        f.panel().refreshTasksPill();

        assertFalse(f.panel().handleTasksPillMouseForTest(new MouseAction(
            MouseActionType.CLICK_DOWN, 1, new TerminalPosition(20, 8)),
            new TerminalPosition(4, 8), new TerminalSize(8, 1)));
        assertEquals(0, f.actions().openTasksDialogCalls.get());
    }

    @Test
    void movingAcrossShellPillTracksReleasedHoverState() {
        Fixture f = fixture();
        runningShell(f.registry(), "npm test");
        f.panel().refreshTasksPill();
        TerminalPosition origin = new TerminalPosition(4, 8);
        TerminalSize size = new TerminalSize(8, 1);

        assertTrue(f.panel().handleTasksPillMouseForTest(new MouseAction(
            MouseActionType.MOVE, 0, new TerminalPosition(5, 8)), origin, size));
        assertTrue(f.panel().isTasksPillHoveredForTest());
        assertFalse(f.panel().handleTasksPillMouseForTest(new MouseAction(
            MouseActionType.MOVE, 0, new TerminalPosition(20, 8)), origin, size));
        assertFalse(f.panel().isTasksPillHoveredForTest());
    }

    // ── pill rendering / refresh ──────────────────────────────────────────

    @Test
    void refreshTasksPill_labelAndHintFollowRegistry() {
        Fixture f = fixture();
        assertEquals("", f.panel().tasksPillTextForTest(), "no tasks → pill hidden");
        assertEquals("", f.panel().tasksHintTextForTest());

        runningShell(f.registry(), "npm run build");
        f.panel().refreshTasksPill();
        assertEquals("1 shell", f.panel().tasksPillTextForTest());
        assertEquals("", f.panel().tasksHintTextForTest(),
            "197 ordinary running-task pills do not show an attention CTA");

        f.panel().handleKeyForTest(DOWN); // select
        assertEquals("", f.panel().tasksHintTextForTest(),
            "selection changes styling, not the ordinary task CTA");
    }

    @Test
    void refreshTasksPill_tasksVanish_pillHiddenAndSelectionCleared() {
        Fixture f = fixture();
        TaskState t = runningShell(f.registry(), "npm run build");
        f.panel().refreshTasksPill();
        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → pill
        assertTrue(f.panel().isTasksPillSelected());

        f.registry().store().updateStatus(t.id(), TaskStatus.COMPLETED);
        f.panel().refreshTasksPill();

        assertEquals("", f.panel().tasksPillTextForTest(), "finished task → pill disappears");
        assertFalse(f.panel().isTasksPillSelected(),
            "selection on a vanished pill is void (TS derived footerItemSelected)");
    }

    @Test
    void keyOnVanishedPill_fallsThroughToNormalHandling() {
        Fixture f = fixture();
        TaskState t = runningShell(f.registry(), "npm run build");
        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → pill
        assertTrue(f.panel().isTasksPillSelected());

        // Task finishes while selected; next key must not be swallowed by the
        // stale selection.
        f.registry().store().updateStatus(t.id(), TaskStatus.COMPLETED);
        f.panel().handleKeyForTest(new KeyStroke('x', false, false));

        assertFalse(f.panel().isTasksPillSelected());
        assertEquals("x", f.panel().getText(), "key takes its normal path once the pill is gone");
    }

    @Test
    void mixedTasks_pillExcludesPanelAgents() {

        Fixture f = fixture();
        runningShell(f.registry(), "shell");
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);

        f.panel().refreshTasksPill();

        assertEquals("1 shell", f.panel().tasksPillTextForTest());
    }

    // ── Coexistence: coordinator panel vs. the horizontal footer pills ────────

    // The subagent coordinator panel and the teammate/tasks footer are two
    // distinct subsystems that must never steal each other's keys. These tests
// wire the coordinator (which fixture deliberately omits) and assert the
    // footer-entry precedence stays isolated in both directions.

    /** A no-op {@link CoordinatorPanelView}; the panel projection is exercised
     *  by CoordinatorTaskPanel's own tests, so here we only need a sink. */
    private static final class NoopPanelView implements CoordinatorPanelView {
        @Override public void refresh(List<TaskState> agents,
                List<WorkflowRun> workflows, int selectedIndex,
                int selectedWorkflowIndex,
                String viewingTaskId, Instant now,
                Function<String, String> nameResolver) {}
    }

    private static void wireCoordinator(InputPanel panel, TaskRegistry registry) {
        CoordinatorNavigationController nav =
            new CoordinatorNavigationController(registry);
        panel.setCoordinatorNavigation(nav, new NoopPanelView(), id -> id);
    }

    @Test
    void downArrow_withPanelAgent_entersCoordinatorPanel_notTasksPill() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → coordinator panel

        assertTrue(f.panel().isCoordinatorPanelSelected(),
            "↓ past the ≡ button enters the subagent panel when a panel agent exists");
        assertFalse(f.panel().isTasksPillSelected(),
            "the coordinator panel must not also engage the tasks pill");
        assertFalse(f.panel().isCollaborationPillSelected());
    }

    @Test
    void coordinatorThenCollaborationShareVisualAndKeyboardOrder() {
        Fixture f = fixture();
        CoordinatorTaskPanel coordinatorPanel = new CoordinatorTaskPanel();
        CoordinatorNavigationController navigation =
            new CoordinatorNavigationController(f.registry());
        f.panel().setCoordinatorNavigation(
            navigation, coordinatorPanel, f.registry()::resolveAgentName);
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // main
        assertEquals(0, f.panel().coordinatorIndexForTest());
        f.panel().handleKeyForTest(DOWN); // agent
        assertEquals(1, f.panel().coordinatorIndexForTest());
        f.panel().handleKeyForTest(DOWN); // Collaboration

        assertTrue(f.panel().isCollaborationPillSelected());
        assertFalse(f.panel().isCoordinatorPanelSelected());
        int coordinatorVisualIndex = f.panel().getChildrenList().indexOf(coordinatorPanel);
        assertTrue(coordinatorVisualIndex >= 0,
            "the coordinator must render inside the prompt footer");
        assertTrue(f.panel().hintRowVisualIndexForTest() < coordinatorVisualIndex,
            "the released mode/tasks footer row must render before main/subagents");
        assertTrue(coordinatorVisualIndex < f.panel().collaborationRowVisualIndexForTest(),
            "the coordinator must render before the final Collaboration row");
        assertEquals(f.panel().getChildrenList().size() - 1,
            f.panel().collaborationRowVisualIndexForTest());

        f.panel().handleKeyForTest(UP);
        assertTrue(f.panel().isCoordinatorPanelSelected());
        assertEquals(0, f.panel().coordinatorIndexForTest());
        assertFalse(f.panel().isCollaborationPillSelected());
        f.panel().handleKeyForTest(UP); // input
        assertFalse(f.panel().isCoordinatorPanelSelected());
        assertFalse(f.panel().isCollaborationPillSelected());
    }

    @Test
    void renderedFooterPlacesCollaborationAfterMainAndAgentRows() {
        Fixture f = fixture();
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "visual-agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);
        CoordinatorTaskPanel coordinatorPanel = new CoordinatorTaskPanel();
        f.panel().setCoordinatorNavigation(
            new CoordinatorNavigationController(f.registry()), coordinatorPanel, _ -> null);
        TerminalSize size = new TerminalSize(100, 20);
        f.panel().setSize(size);
        BasicTextImage image = new BasicTextImage(size);

        f.panel().draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));

        List<String> lines = renderedLines(image);
        int mainRow = lineContaining(lines, "main");
        int agentRow = lineContaining(lines, "visual-agent");
        int collaborationRow = lineContaining(lines, "Collaboration: Off");
        assertTrue(mainRow < agentRow && agentRow < collaborationRow,
            "the final Lanterna frame must follow main → agent → Collaboration");
        assertTrue(Strings.CS.startsWith(lines.get(collaborationRow), "  Collaboration: Off"),
            "Collaboration must align with the coordinator's two-column idle prefix");
    }

    @Test
    void ctrlNAndCtrlPUseTheSameReleasedFooterStateMachine() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);
        KeyStroke ctrlN = new KeyStroke('n', true, false);
        KeyStroke ctrlP = new KeyStroke('p', true, false);

        f.panel().handleKeyForTest(DOWN);  // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN);  // main
        f.panel().handleKeyForTest(ctrlN); // agent
        assertEquals(1, f.panel().coordinatorIndexForTest());
        f.panel().handleKeyForTest(ctrlN); // Collaboration
        assertTrue(f.panel().isCollaborationPillSelected());
        f.panel().handleKeyForTest(ctrlP); // tasks group resets to main
        assertTrue(f.panel().isCoordinatorPanelSelected());
        assertEquals(0, f.panel().coordinatorIndexForTest());
    }

    @Test
    void collaborationFooterOwnsEscapeAndEnterWhileSubagentIsRunning() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);
        f.panel().setIsLoading(true);

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // main
        f.panel().handleKeyForTest(DOWN); // agent
        f.panel().handleKeyForTest(DOWN); // Collaboration
        assertTrue(f.panel().isCollaborationPillSelected());
        f.panel().handleKeyForTest(ESC);
        assertFalse(f.panel().isCollaborationPillSelected());
        assertEquals(0, f.actions().cancelCalls.get(),
            "footer Esc must return to input instead of aborting the running turn");

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // main
        f.panel().handleKeyForTest(DOWN); // agent
        f.panel().handleKeyForTest(DOWN); // Collaboration
        f.panel().handleKeyForTest(ENTER);
        assertEquals(1, f.actions().openCollaborationPickerCalls.get());
        assertEquals(0, f.actions().cancelCalls.get());
    }

    @Test
    void coordinatorEnterWithDraftOpensSelectedAgentWithoutSubmitting() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);
        f.panel().setText("draft");

        f.panel().handleKeyForTest(DOWN); // main
        f.panel().handleKeyForTest(DOWN); // agent
        f.panel().handleKeyForTest(ENTER);

        assertEquals(0, f.actions().submitCalls.get());
        assertEquals("draft", f.panel().getText());
        assertEquals(1, f.actions().teammateViewChangedCalls.get(),
            "Enter must open the selected subagent transcript");
        assertTrue(f.panel().isCoordinatorPanelSelected(),
            "197 enterTeammateView does not clear footerSelection=tasks");
    }

    @Test
    void typingInViewedAgentExitsFooterSelectionAndKeepsFirstCharacter() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // main
        f.panel().handleKeyForTest(DOWN); // agent
        f.panel().handleKeyForTest(ENTER); // view agent, footer remains selected
        f.panel().handleKeyForTest(new KeyStroke('c', false, false));

        assertEquals("c", f.panel().getText(),
            "197 type-to-exit must deliver the same first key to the steering editor");
        assertFalse(f.panel().isCoordinatorPanelSelected(),
            "typing in the viewed agent transfers focus away from the footer");
    }

    @Test
    void escapeFromViewedAgentReturnsToMainWithoutLeavingFooterFocusStuck() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);

        f.panel().handleKeyForTest(DOWN); // main
        f.panel().handleKeyForTest(DOWN); // agent
        f.panel().handleKeyForTest(ENTER);
        f.panel().handleKeyForTest(ESC);
        f.panel().handleKeyForTest(new KeyStroke('m', false, false));

        assertEquals(TaskStatus.RUNNING, f.registry().store().get(agent.id()).orElseThrow().status());
        assertEquals("m", f.panel().getText(),
            "after returning to main, ordinary input must not be swallowed by stale selection");
        assertFalse(f.panel().isCoordinatorPanelSelected());
    }

    @Test
    void plainTypingWhileCoordinatorFooterIsSelectedIsDropped() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);
        f.panel().setText("draft");

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // main
        f.panel().handleKeyForTest(new KeyStroke('z', false, false));

        assertEquals("draft", f.panel().getText());
        assertTrue(f.panel().isCoordinatorPanelSelected(),
            "197 TextInput focus=false while any footer item is selected");
    }

    @Test
    void workflowFooterHasIndependentSelectionAndEnterOpensItsDetail() {
        Fixture f = fixture();
        WorkflowRunStore runs = new WorkflowRunStore();
        TaskState task = f.registry().store().create(TaskType.LOCAL_WORKFLOW, "research");
        f.registry().store().updateStatus(task.id(), TaskStatus.RUNNING);
        runs.put(WorkflowRun.builder("wf_footer", task.id(), TaskStatus.RUNNING)
            .workflowName("ecosystem-top10")
            .summary("Find and verify projects")
            .script("")
            .scriptPath(Path.of("/tmp/workflow.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(System.currentTimeMillis())
            .build());
        f.panel().setWorkflowRunStore(runs);
        wireCoordinator(f.panel(), f.registry());

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → workflow footer
        assertTrue(f.panel().isWorkflowFooterSelectedForTest());
        assertFalse(f.panel().isTasksPillSelected(),
            "local_workflow has an independent ETf row and must not duplicate into tasks");
        assertEquals(0, f.panel().workflowFooterIndexForTest());
        assertEquals(0, f.actions().openTasksDialogCalls.get());
        assertTrue(Strings.CS.contains(f.panel().leaderHintTextForTest(), "enter view"));
        assertTrue(Strings.CS.contains(f.panel().leaderHintTextForTest(), "x stop"));
        f.panel().handleKeyForTest(ENTER);
        assertEquals(task.id(), f.actions().openedWorkflowTaskId.get());
    }

    @Test
    void workflowEnterWithDraftOpensDetailWithoutSubmitting() {
        Fixture f = fixture();
        WorkflowRunStore runs = new WorkflowRunStore();
        TaskState task = f.registry().store().create(TaskType.LOCAL_WORKFLOW, "research");
        f.registry().store().updateStatus(task.id(), TaskStatus.RUNNING);
        runs.put(WorkflowRun.builder("wf_footer_draft", task.id(), TaskStatus.RUNNING)
            .workflowName("draft-workflow")
            .summary("Preserve the prompt")
            .script("")
            .scriptPath(Path.of("/tmp/workflow.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(System.currentTimeMillis())
            .build());
        f.panel().setWorkflowRunStore(runs);
        wireCoordinator(f.panel(), f.registry());
        f.panel().setText("draft");

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → workflow footer
        f.panel().handleKeyForTest(ENTER);

        assertEquals(task.id(), f.actions().openedWorkflowTaskId.get());
        assertEquals(0, f.actions().submitCalls.get());
        assertEquals("draft", f.panel().getText());
    }

    @Test
    void plainTypingWhileWorkflowFooterIsSelectedIsDropped() {
        Fixture f = fixture();
        WorkflowRunStore runs = new WorkflowRunStore();
        TaskState task = f.registry().store().create(TaskType.LOCAL_WORKFLOW, "workflow");
        f.registry().store().updateStatus(task.id(), TaskStatus.RUNNING);
        runs.put(WorkflowRun.builder("wf_typing", task.id(), TaskStatus.RUNNING)
            .workflowName("typing")
            .summary("Typing must not escape footer focus")
            .script("")
            .scriptPath(Path.of("/tmp/workflow.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(System.currentTimeMillis())
            .build());
        f.panel().setWorkflowRunStore(runs);
        wireCoordinator(f.panel(), f.registry());
        f.panel().setText("draft");

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → workflow footer
        f.panel().handleKeyForTest(new KeyStroke('z', false, false));

        assertEquals("draft", f.panel().getText());
        assertTrue(f.panel().isWorkflowFooterSelectedForTest());
    }

    @Test
    void terminalWorkflowXClearsReleasedFooterRowImmediately() {
        Fixture f = fixture();
        WorkflowRunStore runs = new WorkflowRunStore();
        TaskState task = f.registry().store().create(TaskType.LOCAL_WORKFLOW, "done");
        f.registry().store().updateStatus(task.id(), TaskStatus.RUNNING);
        f.registry().store().updateStatus(task.id(), TaskStatus.COMPLETED);
        f.registry().store().setEvictAfter(task.id(), Instant.now().plusSeconds(30));
        runs.put(WorkflowRun.builder("wf_done_footer", task.id(), TaskStatus.COMPLETED)
            .workflowName("done-workflow")
            .summary("Done")
            .script("")
            .scriptPath(Path.of("/tmp/workflow.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(System.currentTimeMillis() - 1_000)
            .durationMs(1_000)
            .build());
        f.panel().setWorkflowRunStore(runs);
        wireCoordinator(f.panel(), f.registry());

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → workflow footer
        assertTrue(f.panel().isWorkflowFooterSelectedForTest());
        f.panel().handleKeyForTest(new KeyStroke('x', false, false));

        assertTrue(f.registry().listPanelWorkflowTasks(Instant.now()).isEmpty());
        assertFalse(f.panel().isWorkflowFooterSelectedForTest());
    }

    @Test
    void downFromLastAgentMovesIntoWorkflowGroup() {
        Fixture f = fixture();
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);
        WorkflowRunStore runs = new WorkflowRunStore();
        TaskState workflowTask = f.registry().store().create(TaskType.LOCAL_WORKFLOW, "workflow");
        f.registry().store().updateStatus(workflowTask.id(), TaskStatus.RUNNING);
        runs.put(WorkflowRun.builder("wf_after_agent", workflowTask.id(), TaskStatus.RUNNING)
            .workflowName("after-agent")
            .summary("After agent")
            .script("")
            .scriptPath(Path.of("/tmp/workflow.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(System.currentTimeMillis())
            .build());
        f.panel().setWorkflowRunStore(runs);
        wireCoordinator(f.panel(), f.registry());

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // main
        f.panel().handleKeyForTest(DOWN); // agent
        f.panel().handleKeyForTest(DOWN); // workflows

        assertTrue(f.panel().isWorkflowFooterSelectedForTest());
        assertEquals(0, f.panel().workflowFooterIndexForTest());
    }

    @Test
    void workflowGroupThenCollaborationPreserveBidirectionalFooterOrder() {
        Fixture f = fixture();
        WorkflowRunStore runs = new WorkflowRunStore();
        TaskState workflowTask = f.registry().store().create(
            TaskType.LOCAL_WORKFLOW, "workflow");
        f.registry().store().updateStatus(workflowTask.id(), TaskStatus.RUNNING);
        runs.put(WorkflowRun.builder(
                "wf_before_collaboration", workflowTask.id(), TaskStatus.RUNNING)
            .workflowName("before-collaboration")
            .summary("Before collaboration")
            .script("")
            .scriptPath(Path.of("/tmp/workflow.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(System.currentTimeMillis())
            .build());
        f.panel().setWorkflowRunStore(runs);
        wireCoordinator(f.panel(), f.registry());

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // workflow
        assertTrue(f.panel().isWorkflowFooterSelectedForTest());
        f.panel().handleKeyForTest(DOWN); // Collaboration
        assertTrue(f.panel().isCollaborationPillSelected());
        assertFalse(f.panel().isWorkflowFooterSelectedForTest());

        f.panel().handleKeyForTest(UP); // workflow
        assertTrue(f.panel().isWorkflowFooterSelectedForTest());
        assertFalse(f.panel().isCollaborationPillSelected());
    }

    @Test
    void workflowSelectionStaysOnTaskIdWhenEarlierRowDisappears() {
        Fixture f = fixture();
        WorkflowRunStore runs = new WorkflowRunStore();
        for (int i = 0; i < 2; i++) {
            TaskState task = f.registry().store().create(TaskType.LOCAL_WORKFLOW, "workflow " + i);
            f.registry().store().updateStatus(task.id(), TaskStatus.RUNNING);
            runs.put(WorkflowRun.builder("wf_stable_" + i, task.id(), TaskStatus.RUNNING)
                .workflowName("workflow-" + i)
                .summary("Stable " + i)
                .script("")
                .scriptPath(Path.of("/tmp/workflow-" + i + ".js"))
                .transcriptDir(Path.of("/tmp"))
                .startTime(System.currentTimeMillis() + i)
                .build());
        }
        f.panel().setWorkflowRunStore(runs);
        wireCoordinator(f.panel(), f.registry());
        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // workflow 0

        f.panel().handleKeyForTest(new KeyStroke(KeyType.ARROW_RIGHT)); // Collaboration
        f.panel().handleKeyForTest(UP); // restore workflows group
        assertEquals(0, f.panel().workflowFooterIndexForTest(),
            "navigateFooter must preserve the current workflow index, not choose the last row");

        f.panel().handleKeyForTest(DOWN); // workflow 1
        String retained = f.panel().selectedWorkflowTaskIdForTest();

        f.panel().handleKeyForTest(DOWN); // Collaboration
        f.panel().handleKeyForTest(UP);   // restore workflows group
        assertEquals(1, f.panel().workflowFooterIndexForTest());
        assertEquals(retained, f.panel().selectedWorkflowTaskIdForTest(),
            "197 navigateFooter preserves workflowFooterIndex across footer groups");

        f.panel().handleKeyForTest(ESC);  // input
        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // workflows group again
        assertEquals(1, f.panel().workflowFooterIndexForTest(),
            "re-entering the workflows footer must preserve workflowFooterIndex");

        String removed = f.registry().listPanelWorkflowTasks(Instant.now()).stream()
            .map(TaskState::id).filter(id -> !id.equals(retained)).findFirst().orElseThrow();

        f.registry().store().remove(removed);
        f.panel().refreshCoordinatorPanel();

        assertEquals(retained, f.panel().selectedWorkflowTaskIdForTest());
        assertEquals(0, f.panel().workflowFooterIndexForTest());
    }

    @Test
    void downArrow_withShellAndPanelAgent_selectsUnifiedPillBeforeMain() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        runningShell(f.registry(), "npm test");
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → unified pill

        assertTrue(f.panel().isCoordinatorPanelSelected());
        assertTrue(f.panel().isTasksPillSelected());
        assertEquals(-1, f.panel().coordinatorIndexForTest());
        f.panel().handleKeyForTest(DOWN);
        assertEquals(0, f.panel().coordinatorIndexForTest());
        assertFalse(f.panel().isTasksPillSelected());
    }

    @Test
    void vanishedBackgroundPillClampsUnifiedCoordinatorSelectionToMain() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        TaskState shell = runningShell(f.registry(), "build");
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → unified background pill
        assertEquals(-1, f.panel().coordinatorIndexForTest());

        f.registry().store().remove(shell.id());
        f.panel().refreshTasksPill();

        assertTrue(f.panel().isCoordinatorPanelSelected());
        assertEquals(0, f.panel().coordinatorIndexForTest(),
            "the selected tasks group must not retain an invisible -1 row");
    }

    @Test
    void vanishedPanelAgentHandsUnifiedSelectionBackToBackgroundPill() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        runningShell(f.registry(), "build");
        TaskState agent = f.registry().store().create(TaskType.LOCAL_AGENT, "agent");
        f.registry().store().updateStatus(agent.id(), TaskStatus.RUNNING);

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // unified background pill (-1)
        f.panel().handleKeyForTest(DOWN); // main
        f.panel().handleKeyForTest(DOWN); // agent
        assertEquals(1, f.panel().coordinatorIndexForTest());
        f.registry().store().remove(agent.id());
        f.panel().refreshTasksPill();

        assertFalse(f.panel().isCoordinatorPanelSelected());
        assertTrue(f.panel().isTasksPillSelected());
        f.panel().handleKeyForTest(DOWN);
        assertTrue(f.panel().isCollaborationPillSelected(),
            "one footer:down must advance after the coordinator rows disappear");
    }

    @Test
    void downArrow_onlyShellTask_selectsTasksPill_coordinatorStaysIdle() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        runningShell(f.registry(), "npm run build"); // a LOCAL_BASH pill task, no panel agent

        f.panel().handleKeyForTest(DOWN); // ≡ projects button (extension stop)
        f.panel().handleKeyForTest(DOWN); // → pill

        assertTrue(f.panel().isTasksPillSelected(),
            "with no panel agent, ↓ past ≡ selects the tasks pill as before");
        assertFalse(f.panel().isCoordinatorPanelSelected(),
            "a shell-only scene must not engage the subagent panel");
    }

    @Test
    void downArrow_onlyTeammate_neverEngagesCoordinatorPanel() {
        Fixture f = fixture();
        wireCoordinator(f.panel(), f.registry());
        TaskState teammate =
            f.registry().store().create(TaskType.IN_PROCESS_TEAMMATE, "helper");
        f.registry().store().updateStatus(teammate.id(), TaskStatus.RUNNING);

        f.panel().handleKeyForTest(DOWN);

        assertFalse(f.panel().isCoordinatorPanelSelected(),
            "a teammate-only scene belongs to the horizontal footer, not the subagent panel");
    }

    private static List<String> renderedLines(BasicTextImage image) {
        return IntStream.range(0, image.getSize().getRows())
            .mapToObj(row -> IntStream.range(0, image.getSize().getColumns())
                .mapToObj(column -> image.getCharacterAt(column, row).getCharacterString())
                .collect(Collectors.joining()))
            .toList();
    }

    private static int lineContaining(List<String> lines, String text) {
        return IntStream.range(0, lines.size())
            .filter(index -> Strings.CS.contains(lines.get(index), text))
            .findFirst().orElseThrow();
    }
}
