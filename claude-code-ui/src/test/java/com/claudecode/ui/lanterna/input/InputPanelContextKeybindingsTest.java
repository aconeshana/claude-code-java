package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.PastedContent;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.keybindings.KeybindingResolver;
import com.claudecode.keybindings.KeystrokeParser;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.apache.commons.lang3.Strings;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.claudecode.ui.lanterna.suggest.SuggestionPanel;

/** Runtime-remapping coverage for InputPanel-owned overlay contexts. */
class InputPanelContextKeybindingsTest {

    @Test
    void autocompleteUsesUserBindingAndHonorsDefaultUnbind(@TempDir Path tmp) throws Exception {
        try (StoreFixture store = enabledStore(tmp, """
            [
              {"context":"Autocomplete","bindings":{
                "ctrl+j":"autocomplete:next",
                "down":null
              }}
            ]
            """)) {
            InputPanel panel = panel(store.value());
            panel.showSuggestions(List.of(
                new SuggestionPanel.Suggestion("/first", "one"),
                new SuggestionPanel.Suggestion("/second", "two")), 100);

            panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_DOWN));
            panel.handleKeyForTest(new KeyStroke(KeyType.TAB));
            assertEquals("/first", panel.getText().stripTrailing(),
                "null-unbound ↓ must not move selection");

            panel.showSuggestions(List.of(
                new SuggestionPanel.Suggestion("/first", "one"),
                new SuggestionPanel.Suggestion("/second", "two")), 100);
            panel.handleKeyForTest(new KeyStroke('j', true, false));
            panel.handleKeyForTest(new KeyStroke(KeyType.TAB));
            assertEquals("/second", panel.getText().stripTrailing(),
                "custom Ctrl+J runs autocomplete:next");
        }
    }

    @Test
    void footerUsesUserOpenBindingAndHonorsDefaultUnbind(@TempDir Path tmp) throws Exception {
        try (StoreFixture store = enabledStore(tmp, """
            [
              {"context":"Footer","bindings":{
                "space":"footer:openSelected",
                "enter":null,
                "escape":null
              }}
            ]
            """)) {
            RecordingActions actions = new RecordingActions();
            TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
            var task = registry.store().create(TaskType.LOCAL_BASH, "build");
            registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
            InputPanel panel = panel(store.value());
            panel.setActions(actions);
            panel.setTaskRegistry(registry);

            panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_DOWN));
            assertTrue(panel.isTasksPillSelected());

            panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
            assertEquals(0, actions.openTasksDialogCalls.get());
            assertTrue(panel.isTasksPillSelected(), "null-unbound Enter stays on the pill");

            panel.handleKeyForTest(new KeyStroke(' ', false, false));
            assertEquals(1, actions.openTasksDialogCalls.get());
            assertFalse(panel.isTasksPillSelected());
        }
    }

    @Test
    void collaborationFooterUsesTheSameFooterContextBindings(@TempDir Path tmp)
            throws Exception {
        try (StoreFixture store = enabledStore(tmp, """
            [
              {"context":"Footer","bindings":{
                "space":"footer:openSelected",
                "enter":null,
                "escape":null
              }}
            ]
            """)) {
            RecordingActions actions = new RecordingActions();
            InputPanel panel = panel(store.value());
            panel.setActions(actions);

            panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_DOWN));
            assertTrue(panel.isCollaborationPillSelected());

            panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
            assertEquals(0, actions.openCollaborationPickerCalls.get());
            assertTrue(panel.isCollaborationPillSelected());

            panel.handleKeyForTest(new KeyStroke(KeyType.ESCAPE));
            assertTrue(panel.isCollaborationPillSelected(),
                "a null-unbound Footer Escape must not fall through to native clearing");

            panel.handleKeyForTest(new KeyStroke(' ', false, false));
            assertEquals(1, actions.openCollaborationPickerCalls.get());
            assertFalse(panel.isCollaborationPillSelected());
        }
    }

    @Test
    void historySearchUsesCustomCancelBinding(@TempDir Path tmp) throws Exception {
        try (StoreFixture store = enabledStore(tmp, """
            [
              {"context":"HistorySearch","bindings":{
                "ctrl+g":"historySearch:cancel",
                "ctrl+c":null
              }}
            ]
            """)) {
            var resolved = store.value().currentResolver().resolve(
                List.of("HistorySearch"), KeystrokeParser.parseKeystroke("ctrl+g"));
            assertInstanceOf(KeybindingResolver.ResolveResult.Match.class, resolved);
            InputPanel panel = panel(store.value());
            PromptHistory history = new PromptHistory(tmp.resolve("history.jsonl")) {
                @Override public CompletableFuture<List<Entry>> getEntriesWithPastedAsync(
                        String project, String sessionId, String modeFilter) {
                    return CompletableFuture.completedFuture(List.of(new Entry(
                        "git status", "s1", 1L, "/proj", "/proj", Map.of())));
                }
                @Override HistoryReader openGlobalHistoryReader() {
                    return new HistoryReader(List.of(new Entry(
                        "git status", "s1", 1L, "/proj", "/proj", Map.of())));
                }
            };
            panel.setPromptHistory(history);
            panel.setHistoryContext("s1", "/proj");
            panel.setText("draft");

            panel.handleKeyForTest(new KeyStroke('r', true, false));
            assertTrue(panel.isHistorySearchingForTest());
            assertEquals("draft", panel.historySearchDraftForTest());
            panel.handleKeyForTest(new KeyStroke('g', false, false));
            long deadline = System.currentTimeMillis() + 2_000;
            while (!Strings.CS.equals("git status", panel.getText())
                    && System.currentTimeMillis() < deadline) Thread.sleep(1);
            assertEquals("git status", panel.getText());
            assertTrue(panel.isHistorySearchingForTest());

            panel.handleKeyForTest(new KeyStroke('g', true, false));
            assertFalse(panel.isHistorySearchingForTest());
            assertEquals("draft", panel.getText(),
                "custom Ctrl+G dispatches historySearch:cancel and restores the draft");
        }
    }

    @Test
    void pickerSelectionPreservesArrowHistoryCursorAndDraft(@TempDir Path tmp) throws Exception {
        InputPanel panel = new InputPanel();
        PromptHistory history = new PromptHistory(tmp.resolve("history.jsonl")) {
            @Override public CompletableFuture<List<Entry>> getEntriesWithPastedAsync(
                    int limit, String project, String sessionId, String modeFilter) {
                return CompletableFuture.completedFuture(List.of(new Entry(
                    "recalled", "s1", 1L, "/proj", "/proj", Map.of())));
            }
            @Override public CompletableFuture<Integer> countEntriesAsync(
                    String project, String modeFilter) {
                return CompletableFuture.completedFuture(1);
            }
        };
        panel.setPromptHistory(history);
        panel.setHistoryContext("s1", "/proj");
        panel.setText("draft");

        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_UP));
        long deadline = System.currentTimeMillis() + 2_000;
        while (!Strings.CS.equals("recalled", panel.getText())
                && System.currentTimeMillis() < deadline) Thread.sleep(1);
        assertEquals("recalled", panel.getText());

        panel.applyHistoryPickerEntry(new PromptHistory.Entry(
            "picked", "s1", 2L, "/proj", "/proj", Map.of()));
        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_DOWN));

        assertEquals("draft", panel.getText(),
            "released PromptInput keeps useArrowKeyHistory state across picker selection");
        history.close();
    }

    @Test
    void messageActionsUsesCustomContextAndHonorsDefaultUnbind(@TempDir Path tmp)
            throws Exception {
        try (StoreFixture store = enabledStore(tmp, """
            [
              {"context":"MessageActions","bindings":{
                "ctrl+x":"messageActions:next",
                "down":null
              }}
            ]
            """)) {
            RecordingActions actions = new RecordingActions();
            InputPanel panel = panel(store.value());
            panel.setActions(actions);
            panel.setMessageActionsActive(true);

            panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_DOWN));
            assertEquals(0, actions.messageActionsNextCalls.get());

            panel.handleKeyForTest(new KeyStroke('x', true, false));
            assertEquals(1, actions.messageActionsNextCalls.get());
        }
    }

    @Test
    void messageActionsFallbackKeepsEscapeAndCtrlCProtocolsDistinct() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setMessageActionsActive(true);

        panel.handleKeyForTest(new KeyStroke(KeyType.ESCAPE));
        panel.handleKeyForTest(new KeyStroke('c', true, false));

        assertEquals(1, actions.messageActionsEscapeCalls.get());
        assertEquals(1, actions.messageActionsForceExitCalls.get());
    }

    private static InputPanel panel(UserKeybindingsStore store) {
        InputPanel panel = new InputPanel();
        panel.setKeybindingsStore(store);
        panel.setActions(new RecordingActions());
        return panel;
    }

    private static StoreFixture enabledStore(Path tmp, String json) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, json);
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return new StoreFixture((UserKeybindingsStore) create.invoke(null, file, true));
    }

    private record StoreFixture(UserKeybindingsStore value) implements AutoCloseable {
        @Override public void close() { value.dispose(); }
    }

    private static final class RecordingActions implements InputActions {
        final AtomicInteger openTasksDialogCalls = new AtomicInteger();
        final AtomicInteger openCollaborationPickerCalls = new AtomicInteger();
        final AtomicInteger messageActionsNextCalls = new AtomicInteger();
        final AtomicInteger messageActionsEscapeCalls = new AtomicInteger();
        final AtomicInteger messageActionsForceExitCalls = new AtomicInteger();
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
        @Override public void openTasksDialog() { openTasksDialogCalls.incrementAndGet(); }
        @Override public void openCollaborationPicker() {
            openCollaborationPickerCalls.incrementAndGet();
        }
        @Override public void toggleMessageActions() {}
        @Override public void messageActionsPrev() {}
        @Override public void messageActionsNext() { messageActionsNextCalls.incrementAndGet(); }
        @Override public void messageActionsEscape() { messageActionsEscapeCalls.incrementAndGet(); }
        @Override public void messageActionsForceExit() { messageActionsForceExitCalls.incrementAndGet(); }
        @Override public void messageActionsCopy() {}
        @Override public void messageActionsEdit() {}
        @Override public void queryChanged(String text, int cursor) {}
        @Override public void pastedContentsChanged(Map<Integer, PastedContent> contents) {}
        @Override public void cursorStyleChanged(CursorStyle style) {}
        @Override public void focusChanged(boolean focused) {}
    }
}
