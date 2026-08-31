package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.message.PastedContent;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class InputPanelKillAgentsKeybindingTest {

    private static final KeyStroke CTRL_X = new KeyStroke('x', true, false);
    private static final KeyStroke CTRL_K = new KeyStroke('k', true, false);

    @Test
    void defaultChordWorksWhenCustomizationGateIsOff_andRequiresTwoPresses(@TempDir Path tmp)
            throws Exception {
        Fixture f = fixture(tmp);
        var task = f.registry().store().create(TaskType.LOCAL_AGENT, "inspect auth flow");
        f.registry().store().updateStatus(task.id(), TaskStatus.RUNNING);

        pressChord(f.panel());

        assertEquals(0, f.actions().killAgentsCalls.get(),
            "first chord only arms confirmation");
        assertEquals("  Press ctrl+x ctrl+k again to stop background agents",
            f.panel().hintTextForTest());

        pressChord(f.panel());

        assertEquals(1, f.actions().killAgentsCalls.get(),
            "second chord inside the confirmation window kills agents");
    }

    @Test
    void chordWithNoRunningAgentsShowsNoAgentsHint(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);

        pressChord(f.panel());

        assertEquals(0, f.actions().killAgentsCalls.get());
        assertEquals("  No background agents running", f.panel().hintTextForTest());
    }

    @Test
    void escapeCancelsForegroundTurnWithoutKillingBackgroundAgents(@TempDir Path tmp)
            throws Exception {
        Fixture f = fixture(tmp);
        var task = f.registry().store().create(TaskType.LOCAL_AGENT, "long research");
        f.registry().store().updateStatus(task.id(), TaskStatus.RUNNING);
        f.panel().setIsLoading(true);

        f.panel().handleKeyForTest(new KeyStroke(KeyType.ESCAPE));

        assertEquals(1, f.actions().cancelCalls.get());
        assertEquals(0, f.actions().killAgentsCalls.get(),
            "ordinary Escape cancellation is not the deliberate kill-agents gesture");
    }

    private static void pressChord(InputPanel panel) {
        panel.handleKeyForTest(CTRL_X);
        panel.handleKeyForTest(CTRL_K);
    }

    private static Fixture fixture(Path tmp) throws Exception {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        InputPanel panel = new InputPanel();
        panel.setTaskRegistry(registry);
        panel.setKeybindingsStore(disabledStore(tmp.resolve("keybindings.json")));
        RecordingActions actions = new RecordingActions();
        panel.setActions(actions);
        return new Fixture(panel, actions, registry);
    }

    private static UserKeybindingsStore disabledStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, false);
    }

    private record Fixture(InputPanel panel, RecordingActions actions, TaskRegistry registry) {}

    private static final class RecordingActions implements InputActions {
        final AtomicInteger killAgentsCalls = new AtomicInteger();
        final AtomicInteger cancelCalls = new AtomicInteger();
        @Override public void submit(String text) {}
        @Override public void cancel() { cancelCalls.incrementAndGet(); }
        @Override public void killBackgroundAgents() { killAgentsCalls.incrementAndGet(); }
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
}
