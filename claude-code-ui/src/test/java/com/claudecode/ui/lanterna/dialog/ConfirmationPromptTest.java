package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior contract for the shared two-choice confirmation prompt. */
class ConfirmationPromptTest {

    @Test
    void confirmFirstWithConfirmFocusIsTheDefaultConfiguration() {
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        ConfirmationPrompt prompt = prompt(false, ConfirmationPrompt.Choice.CONFIRM);
        prompt.activate(confirmed::incrementAndGet, cancelled::incrementAndGet);

        assertEquals(List.of(
            new ConfirmationPrompt.Option(ConfirmationPrompt.Choice.CONFIRM, "Yes"),
            new ConfirmationPrompt.Option(ConfirmationPrompt.Choice.CANCEL, "No")),
            prompt.options());
        assertEquals(ConfirmationPrompt.Choice.CONFIRM, prompt.focusedChoice());
        assertTrue(prompt.isFocused(ConfirmationPrompt.Choice.CONFIRM));
        assertFalse(prompt.isFocused(ConfirmationPrompt.Choice.CANCEL));

        press(prompt, new KeyStroke(KeyType.ENTER));

        assertEquals(1, confirmed.get());
        assertEquals(0, cancelled.get());
    }

    @Test
    void cancelFirstAndInitialCancelFocusAreIndependentConfiguration() {
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        ConfirmationPrompt prompt = prompt(true, ConfirmationPrompt.Choice.CANCEL);
        prompt.activate(confirmed::incrementAndGet, cancelled::incrementAndGet);

        assertEquals(List.of(
            new ConfirmationPrompt.Option(ConfirmationPrompt.Choice.CANCEL, "No"),
            new ConfirmationPrompt.Option(ConfirmationPrompt.Choice.CONFIRM, "Yes")),
            prompt.options());
        assertEquals(ConfirmationPrompt.Choice.CANCEL, prompt.focusedChoice());

        press(prompt, new KeyStroke(KeyType.ENTER));

        assertEquals(0, confirmed.get());
        assertEquals(1, cancelled.get());
    }

    @Test
    void arrowsAndJkMoveFocusWithWrapping() {
        ConfirmationPrompt prompt = prompt(false, ConfirmationPrompt.Choice.CONFIRM);
        prompt.activate(() -> {}, () -> {});

        assertConsumed(prompt, new KeyStroke(KeyType.ARROW_DOWN));
        assertEquals(ConfirmationPrompt.Choice.CANCEL, prompt.focusedChoice());
        assertConsumed(prompt, key('j'));
        assertEquals(ConfirmationPrompt.Choice.CONFIRM, prompt.focusedChoice());
        assertConsumed(prompt, new KeyStroke(KeyType.ARROW_LEFT));
        assertEquals(ConfirmationPrompt.Choice.CANCEL, prompt.focusedChoice());
        assertConsumed(prompt, key('k'));
        assertEquals(ConfirmationPrompt.Choice.CONFIRM, prompt.focusedChoice());
        assertConsumed(prompt, new KeyStroke(KeyType.ARROW_RIGHT));
        assertEquals(ConfirmationPrompt.Choice.CANCEL, prompt.focusedChoice());
        assertConsumed(prompt, new KeyStroke(KeyType.ARROW_UP));
        assertEquals(ConfirmationPrompt.Choice.CONFIRM, prompt.focusedChoice());
    }

    @Test
    void selectContextRebindingsDriveNavigationAcceptAndCancel(
            @TempDir Path tmp) throws Exception {
        UserKeybindingsStore store = createStore(tmp, """
            [{"context":"Select","bindings":{
              "x":"select:next",
              "z":"select:accept",
              "q":"select:cancel"
            }}]
            """);
        try {
            AtomicInteger confirmed = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            ConfirmationPrompt prompt = prompt(false, ConfirmationPrompt.Choice.CONFIRM);
            prompt.setKeybindingsStore(store);
            prompt.activate(confirmed::incrementAndGet, cancelled::incrementAndGet);

            assertConsumed(prompt, key('x'));
            assertEquals(ConfirmationPrompt.Choice.CANCEL, prompt.focusedChoice());
            assertConsumed(prompt, key('z'));
            assertEquals(0, confirmed.get());
            assertEquals(1, cancelled.get(),
                "select:accept invokes the callback for the focused choice");

            prompt.activate(confirmed::incrementAndGet, cancelled::incrementAndGet);
            assertConsumed(prompt, key('q'));
            assertEquals(0, confirmed.get());
            assertEquals(2, cancelled.get(),
                "select:cancel always invokes the cancel callback");
        } finally {
            store.dispose();
        }
    }

    @Test
    void explicitlyNullUnboundEnterIsConsumedWithoutNativeFallback(
            @TempDir Path tmp) throws Exception {
        UserKeybindingsStore store = createStore(tmp, """
            [{"context":"Select","bindings":{"enter":null}}]
            """);
        try {
            AtomicInteger confirmed = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            ConfirmationPrompt prompt = prompt(false, ConfirmationPrompt.Choice.CONFIRM);
            prompt.setKeybindingsStore(store);
            prompt.activate(confirmed::incrementAndGet, cancelled::incrementAndGet);

            assertConsumed(prompt, new KeyStroke(KeyType.ENTER));

            assertEquals(0, confirmed.get());
            assertEquals(0, cancelled.get());
            assertEquals(ConfirmationPrompt.Choice.CONFIRM, prompt.focusedChoice());
        } finally {
            store.dispose();
        }
    }

    @Test
    void resolutionCallbackRunsExactlyOnceUntilReactivated() {
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        ConfirmationPrompt prompt = prompt(false, ConfirmationPrompt.Choice.CONFIRM);
        prompt.activate(confirmed::incrementAndGet, cancelled::incrementAndGet);

        press(prompt, new KeyStroke(KeyType.ENTER));
        press(prompt, new KeyStroke(KeyType.ENTER));
        press(prompt, new KeyStroke(KeyType.ESCAPE));

        assertEquals(1, confirmed.get());
        assertEquals(0, cancelled.get());

        prompt.activate(confirmed::incrementAndGet, cancelled::incrementAndGet);
        press(prompt, new KeyStroke(KeyType.ESCAPE));
        assertEquals(1, confirmed.get());
        assertEquals(1, cancelled.get(),
            "activate starts a fresh exactly-once callback lifecycle");
    }

    private static ConfirmationPrompt prompt(
            boolean cancelFirst, ConfirmationPrompt.Choice initialFocus) {
        return new ConfirmationPrompt(
            "Yes", "No", cancelFirst, initialFocus, () -> {});
    }

    private static void assertConsumed(ConfirmationPrompt prompt, KeyStroke key) {
        AtomicBoolean deliver = new AtomicBoolean(true);
        prompt.handleKey(key, deliver);
        assertFalse(deliver.get(), "handled prompt keys must not leak to the chat input");
    }

    private static void press(ConfirmationPrompt prompt, KeyStroke key) {
        prompt.handleKey(key, new AtomicBoolean(true));
    }

    private static KeyStroke key(char value) {
        return new KeyStroke(value, false, false);
    }

    private static UserKeybindingsStore createStore(Path directory, String json)
            throws Exception {
        Path file = directory.resolve("keybindings.json");
        Files.writeString(file, json);
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}
