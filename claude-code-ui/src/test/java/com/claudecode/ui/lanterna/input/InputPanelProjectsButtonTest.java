package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.PastedContent;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * State-machine tests for {@link InputPanel}'s ≡ projects footer button — a
 * Java-side extension with no 197 counterpart. The button is the FIRST stop of
 * the footer focus chain (its spatial position is leftmost): empty input ↓ →
 * ≡ → the released pill chain (tasks → workflows → Collaboration). Enter or a
 * mouse click toggles the project drawer via {@link InputActions#toggleProjectPanel()}.
 */
class InputPanelProjectsButtonTest {

    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke LEFT = new KeyStroke(KeyType.ARROW_LEFT);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static final class RecordingActions implements InputActions {
        final AtomicInteger toggleProjectPanelCalls = new AtomicInteger();
        final AtomicInteger submitCalls = new AtomicInteger();
        @Override public void submit(String text) { submitCalls.incrementAndGet(); }
        @Override public void toggleProjectPanel() { toggleProjectPanelCalls.incrementAndGet(); }
        @Override public void cancel() {}
        @Override public void showMessageSelector() {}
        @Override public void toggleTranscript() {}
        @Override public void transcriptShowAll() {}
        @Override public void redrawScreen() {}
        @Override public void externalEditor() {}
        @Override public void stash() {}
        @Override public void undo() {}
        @Override public void permissionModeChanged(String uiMode) {}
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
        return new Fixture(panel, actions, registry);
    }

    private static TaskState runningShell(TaskRegistry registry, String command) {
        TaskState t = registry.store().create(TaskType.LOCAL_BASH, command);
        registry.store().updateStatus(t.id(), TaskStatus.RUNNING);
        return t;
    }

    @Test
    void downFromEmptyInputSelectsProjectsButtonFirst() {
        Fixture f = fixture();

        f.panel().handleKeyForTest(DOWN);

        assertTrue(f.panel().isProjectsButtonSelectedForTest(), "≡ is the first footer stop");
        assertFalse(f.panel().isTasksPillSelected());
        assertFalse(f.panel().isCollaborationPillSelected());
        assertEquals(0, f.actions().toggleProjectPanelCalls.get(), "selection never opens");
    }

    @Test
    void enterOnProjectsButtonTogglesPanelAndClearsSelection() {
        Fixture f = fixture();

        f.panel().handleKeyForTest(DOWN);
        f.panel().handleKeyForTest(ENTER);

        assertEquals(1, f.actions().toggleProjectPanelCalls.get());
        assertEquals(0, f.actions().submitCalls.get(), "footer Enter never submits");
        assertFalse(f.panel().isProjectsButtonSelectedForTest(),
            "the drawer owns input while open; footer selection clears");
    }

    @Test
    void enterOnProjectsButtonPreservesDraft() {
        Fixture f = fixture();
        f.panel().setText("keep this draft");

        f.panel().handleKeyForTest(DOWN);
        f.panel().handleKeyForTest(ENTER);

        assertEquals(1, f.actions().toggleProjectPanelCalls.get());
        assertEquals("keep this draft", f.panel().getText());
    }

    @Test
    void downFromProjectsButtonMovesIntoExistingChain() {
        Fixture f = fixture(); // no background tasks → next stop is Collaboration

        f.panel().handleKeyForTest(DOWN);
        f.panel().handleKeyForTest(DOWN);

        assertFalse(f.panel().isProjectsButtonSelectedForTest());
        assertTrue(f.panel().isCollaborationPillSelected());
    }

    @Test
    void downFromProjectsButtonPrefersTasksPillWhenPresent() {
        Fixture f = fixture();
        runningShell(f.registry(), "npm run build");

        f.panel().handleKeyForTest(DOWN);
        assertTrue(f.panel().isProjectsButtonSelectedForTest());
        f.panel().handleKeyForTest(DOWN);

        assertFalse(f.panel().isProjectsButtonSelectedForTest());
        assertTrue(f.panel().isTasksPillSelected(), "released chain resumes at the tasks pill");
    }

    @Test
    void leftFromTasksPillReturnsToProjectsButton() {
        Fixture f = fixture();
        runningShell(f.registry(), "task");

        f.panel().handleKeyForTest(DOWN);   // ≡
        f.panel().handleKeyForTest(DOWN);   // tasks pill
        f.panel().handleKeyForTest(LEFT);   // back to ≡

        assertTrue(f.panel().isProjectsButtonSelectedForTest(), "≡ sits left of the tasks pill");
        assertFalse(f.panel().isTasksPillSelected());
    }

    @Test
    void upFromProjectsButtonReturnsToInput() {
        Fixture f = fixture();

        f.panel().handleKeyForTest(DOWN);
        f.panel().handleKeyForTest(UP);

        assertFalse(f.panel().isProjectsButtonSelectedForTest());
        assertFalse(f.panel().isCollaborationPillSelected());
    }

    @Test
    void escFromProjectsButtonDeselectsWithoutToggling() {
        Fixture f = fixture();

        f.panel().handleKeyForTest(DOWN);
        f.panel().handleKeyForTest(ESC);

        assertFalse(f.panel().isProjectsButtonSelectedForTest());
        assertEquals(0, f.actions().toggleProjectPanelCalls.get());
    }

    @Test
    void mouseClickOnProjectsButtonToggles() {
        Fixture f = fixture();
        TerminalPosition origin = new TerminalPosition(0, 10);
        TerminalSize size = new TerminalSize(2, 1);
        MouseAction press = new MouseAction(MouseActionType.CLICK_DOWN, 1,
            new TerminalPosition(1, 10));
        MouseAction release = new MouseAction(MouseActionType.CLICK_RELEASE, 1,
            new TerminalPosition(1, 10));

        assertTrue(f.panel().handleProjectsButtonMouseForTest(press, origin, size));
        assertTrue(f.panel().handleProjectsButtonMouseForTest(release, origin, size));
        assertEquals(1, f.actions().toggleProjectPanelCalls.get());

        // release outside after an inside press: swallowed (press-latch) but no activation
        MouseAction pressAgain = new MouseAction(MouseActionType.CLICK_DOWN, 1,
            new TerminalPosition(1, 10));
        MouseAction releaseFar = new MouseAction(MouseActionType.CLICK_RELEASE, 1,
            new TerminalPosition(40, 12));
        f.panel().handleProjectsButtonMouseForTest(pressAgain, origin, size);
        assertTrue(f.panel().handleProjectsButtonMouseForTest(releaseFar, origin, size),
            "press started inside consumes the release (same latch as the tasks pill)");
        assertEquals(1, f.actions().toggleProjectPanelCalls.get(), "no activation outside");
    }

    @Test
    void openStateKeepsButtonHighlightedWithoutSelection() {
        Fixture f = fixture();

        f.panel().setProjectsButtonActive(true);
        assertTrue(f.panel().isProjectsButtonActiveForTest());
        assertFalse(f.panel().isProjectsButtonSelectedForTest(),
            "open-state highlight is independent of keyboard selection");

        f.panel().setProjectsButtonActive(false);
        assertFalse(f.panel().isProjectsButtonActiveForTest());
    }

    @Test
    void footerChainUnchangedAfterProjectsButtonEntry() {
        Fixture f = fixture();
        runningShell(f.registry(), "task");

        f.panel().handleKeyForTest(DOWN);   // ≡
        f.panel().handleKeyForTest(DOWN);   // tasks pill
        f.panel().handleKeyForTest(new KeyStroke(KeyType.ARROW_RIGHT)); // → Collaboration
        assertTrue(f.panel().isCollaborationPillSelected());
        f.panel().handleKeyForTest(LEFT);   // ← back to tasks
        assertTrue(f.panel().isTasksPillSelected());
    }
}
