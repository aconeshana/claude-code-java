package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.PastedContent;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.input.KeyStroke;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class InputPanelPushToTalkKeybindingTest {

    private static final KeyStroke SPACE = new KeyStroke(' ', false, false);

    @Test
    void singleTapSpaceIsForwardedAsTap_andFallsThroughToInput(@TempDir Path tmp)
            throws Exception {
        PushToTalkActions actions = new PushToTalkActions(false);
        try (StoreFixture store = enabledStore(tmp, chatSpaceBinding())) {
            InputPanel panel = panel(store.value(), actions);

            panel.handleKeyForTest(SPACE);

            assertEquals(List.of(false), actions.heldCalls,
                "a single tap must classify as a plain space, not a hold");
            assertEquals(" ", panel.getText(),
                "with no voice backend the space must still reach the input");
        }
    }

    @Test
    void sustainedHoldClassifiesAsHeld_butStillFallsThroughWithoutVoice(@TempDir Path tmp)
            throws Exception {
        PushToTalkActions actions = new PushToTalkActions(false);
        try (StoreFixture store = enabledStore(tmp, chatSpaceBinding())) {
            InputPanel panel = panel(store.value(), actions);

            // A held key surfaces as a rapid stream of repeated spaces (no
            // distinct key-release). Fire a burst inside the reset window.
            for (int i = 0; i < 6; i++) {
                panel.handleKeyForTest(SPACE);
            }

            assertTrue(actions.heldCalls.get(actions.heldCalls.size() - 1),
                "a sustained burst must eventually classify as a hold");
            assertEquals("      ", panel.getText(),
                "without a voice port the repeated spaces still fall through to input");
        }
    }

    @Test
    void voicePortConsumingTapSwallowsTheSpace(@TempDir Path tmp) throws Exception {
        PushToTalkActions actions = new PushToTalkActions(true);
        try (StoreFixture store = enabledStore(tmp, chatSpaceBinding())) {
            InputPanel panel = panel(store.value(), actions);

            panel.handleKeyForTest(SPACE);

            assertEquals("", panel.getText(),
                "a voice port that claims recording swallows the space");
        }
    }

    private static String chatSpaceBinding() {
        return """
            [
              {"context":"Chat","bindings":{"space":"voice:pushToTalk"}}
            ]
            """;
    }

    private static InputPanel panel(UserKeybindingsStore store, PushToTalkActions actions) {
        InputPanel panel = new InputPanel();
        panel.setKeybindingsStore(store);
        panel.setActions(actions);
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

    private static final class PushToTalkActions implements InputActions {
        final boolean consume;
        final List<Boolean> heldCalls = new ArrayList<>();

        PushToTalkActions(boolean consume) {
            this.consume = consume;
        }

        @Override public boolean handlePushToTalk(boolean held) {
            heldCalls.add(held);
            return consume;
        }
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