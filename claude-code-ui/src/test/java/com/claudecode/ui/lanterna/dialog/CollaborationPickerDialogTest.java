package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.claudecode.ui.lanterna.overlay.OverlayHost;
import com.claudecode.ui.lanterna.components.SmartLayout;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.gui2.Panel;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollaborationPickerDialogTest {

    @Test
    void exposesOffAndConfiguredChannelsInKeyboardOrder() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        AtomicReference<String> result = new AtomicReference<>("unchanged");
        dialog.show(List.of("feishu", "slack"), "", result::set);

        assertTrue(dialog.isActive());
        assertEquals(List.of("Off", "Feishu", "Slack"), dialog.optionLabelsForTest());
        assertEquals(0, dialog.focusedIndexForTest());
        assertEquals(10, dialog.calculatePreferredSize().getRows(),
            "compact Select rows must not insert expanded-layout gaps between options");

        route(dialog, new KeyStroke(KeyType.ARROW_DOWN));
        route(dialog, new KeyStroke(KeyType.ENTER));

        assertEquals("feishu", result.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void narrowPickerWrapsItsDescriptionInsteadOfClippingIt() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        dialog.show(List.of("feishu"), "", _ -> {});
        TerminalSize size = new TerminalSize(40, dialog.calculatePreferredSize().getRows());
        dialog.setSize(size);
        BasicTextImage image = new BasicTextImage(size);

        dialog.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));

        String rendered = renderedText(image);
        assertTrue(rendered.contains("Mirror progress and interactions to"));
        assertTrue(rendered.contains("one IM channel."));
        assertTrue(rendered.contains("Enter to select · Esc to cancel"));
    }

    @Test
    void preservesCallerChannelOrderWhileDeduplicatingNormalizedValues() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();

        dialog.show(List.of("slack", "FEISHU", "slack"), "", _ -> {});

        assertEquals(List.of("Off", "Slack", "Feishu"), dialog.optionLabelsForTest());
    }

    @Test
    void escapeCancelsAndCompletesThePickerLifecycle() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        AtomicBoolean callbackInvoked = new AtomicBoolean();
        AtomicReference<String> result = new AtomicReference<>("unchanged");
        dialog.show(List.of("feishu"), "feishu", selected -> {
            callbackInvoked.set(true);
            result.set(selected);
        });

        route(dialog, new KeyStroke(KeyType.ESCAPE));

        assertTrue(callbackInvoked.get(),
            "the owner must be notified so it can restore the suppressed input panel");
        assertNull(result.get(), "cancel must not change the selected channel");
        assertFalse(dialog.isActive());
    }

    @Test
    void exposesFeishuSetupAsAStablePickerAction() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        AtomicReference<String> result = new AtomicReference<>("unchanged");
        dialog.show(List.of(), "", true, result::set);

        assertEquals(List.of("Off", "Set up Feishu…"), dialog.optionLabelsForTest());
        route(dialog, new KeyStroke(KeyType.ARROW_DOWN));
        route(dialog, new KeyStroke(KeyType.ENTER));

        assertEquals(CollaborationPickerDialog.SETUP_FEISHU, result.get());
    }

    @Test
    void incompleteSetupOffersContinueInsteadOfStartingOver() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        AtomicReference<String> result = new AtomicReference<>("unchanged");
        dialog.show(List.of(), "", false, true, result::set);

        assertEquals(List.of("Off", "Continue Feishu setup…"), dialog.optionLabelsForTest());
        route(dialog, new KeyStroke(KeyType.ARROW_DOWN));
        route(dialog, new KeyStroke(KeyType.ENTER));

        assertEquals(CollaborationPickerDialog.CONTINUE_FEISHU, result.get());
    }

    @Test
    void toolInteractionTemporarilyYieldsKeyboardWithoutClosingPicker() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        AtomicBoolean toolInteractionActive = new AtomicBoolean(true);
        dialog.setInteractionBlocked(toolInteractionActive::get);
        dialog.show(List.of("feishu"), "", _ -> {});
        OverlayHost host = new OverlayHost();
        host.register(dialog);
        AtomicBoolean deliver = new AtomicBoolean(true);

        assertFalse(host.route(new KeyStroke(KeyType.ARROW_DOWN), deliver));
        assertTrue(dialog.isVisibleInScene(),
            "permission input temporarily owns keys without unmounting the picker");
        new SmartLayout().doLayout(new TerminalSize(80, 24),
            List.of(new Panel(), dialog));
        assertTrue(dialog.isVisible(),
            "scene layout must keep the picker painted behind the temporary interaction");
        assertTrue(deliver.get());
        assertEquals(0, dialog.focusedIndexForTest());
        assertTrue(dialog.calculatePreferredSize().getRows() > 0,
            "the picker may remain visible while permission input owns the keyboard");

        toolInteractionActive.set(false);
        assertTrue(host.route(new KeyStroke(KeyType.ARROW_DOWN), deliver));
        assertFalse(deliver.get());
        assertEquals(1, dialog.focusedIndexForTest());
    }

    @Test
    void followsReleasedSelectKeyboardHabits() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        dialog.show(List.of("feishu", "slack"), "", _ -> {});

        route(dialog, new KeyStroke(KeyType.ARROW_UP));
        assertEquals(2, dialog.focusedIndexForTest(),
            "released Select navigation wraps from the first option to the last");
        route(dialog, new KeyStroke(KeyType.ARROW_DOWN));
        assertEquals(0, dialog.focusedIndexForTest());
        route(dialog, new KeyStroke('j', false, false));
        assertEquals(1, dialog.focusedIndexForTest());
        route(dialog, new KeyStroke('k', false, false));
        assertEquals(0, dialog.focusedIndexForTest());
        route(dialog, new KeyStroke(KeyType.PAGE_DOWN));
        assertEquals(2, dialog.focusedIndexForTest());
        route(dialog, new KeyStroke(KeyType.PAGE_UP));
        assertEquals(0, dialog.focusedIndexForTest());
    }

    @Test
    void pageNavigationMovesByReleasedSelectVisibleCount() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        dialog.show(List.of("a", "b", "c", "d", "e", "f", "g"), "", _ -> {});

        assertEquals(12, dialog.calculatePreferredSize().getRows(),
            "released Select renders at most five visible options");
        route(dialog, new KeyStroke(KeyType.PAGE_DOWN));
        assertEquals(5, dialog.focusedIndexForTest());
        route(dialog, new KeyStroke(KeyType.PAGE_UP));
        assertEquals(0, dialog.focusedIndexForTest());
    }

    @Test
    void fullWidthNumberSelectsTheSameOptionAsAsciiNumber() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        AtomicReference<String> result = new AtomicReference<>();
        dialog.show(List.of("feishu", "slack"), "", result::set);

        route(dialog, new KeyStroke('２', false, false));

        assertEquals("feishu", result.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void twoDigitIndexesKeepTheirLabelColumn() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        dialog.show(List.of("c01", "c02", "c03", "c04", "c05",
            "c06", "c07", "c08", "c09", "c10"), "", _ -> {});
        route(dialog, new KeyStroke(KeyType.PAGE_DOWN));
        route(dialog, new KeyStroke(KeyType.PAGE_DOWN));
        TerminalSize size = dialog.calculatePreferredSize();
        dialog.setSize(size);
        BasicTextImage image = new BasicTextImage(size);

        dialog.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));

        String rendered = renderedText(image);
        assertTrue(rendered.contains("11. c10"), rendered);
    }

    @Test
    void consumesPasteSoItCannotLeakIntoTheSuppressedPrompt() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        dialog.show(List.of("feishu"), "", _ -> {});

        route(dialog, new PasteKeyStroke("must not reach input"));

        assertTrue(dialog.isActive());
        assertEquals(0, dialog.focusedIndexForTest());
    }

    @Test
    void numericPasteUsesReleasedNumberSelectionInsteadOfLeakingToPrompt() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        AtomicReference<String> result = new AtomicReference<>();
        dialog.show(List.of("feishu", "slack"), "", result::set);

        route(dialog, new PasteKeyStroke("２"));

        assertEquals("feishu", result.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void nonAsciiUnicodeDigitsDoNotActAsReleasedNumericShortcuts() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        AtomicReference<String> result = new AtomicReference<>();
        dialog.show(List.of("feishu", "slack"), "", result::set);

        AtomicBoolean deliver = new AtomicBoolean(true);
        dialog.handleKey(new KeyStroke('٢', false, false), deliver);

        assertTrue(dialog.isActive());
        assertNull(result.get());
    }

    @Test
    void longListsRenderOnlyTheReleasedFiveRowViewportAndScrollHints() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        dialog.show(List.of("a", "b", "c", "d", "e", "f", "g"), "", _ -> {});

        route(dialog, new KeyStroke(KeyType.PAGE_DOWN));
        TerminalSize size = dialog.calculatePreferredSize();
        dialog.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        dialog.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));

        String rendered = renderedText(image);
        assertEquals(12, size.getRows());
        assertTrue(rendered.contains("↑ 2. a"), rendered);
        assertTrue(rendered.contains("❯ 6. e"), rendered);
        assertFalse(rendered.contains("1. Off"), rendered);
        assertFalse(rendered.contains("7. f"), rendered);
    }

    @Test
    void rendersCompactReleasedSelectVisualsAndCurrentValueMark() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        dialog.show(List.of("feishu", "slack"), "feishu", _ -> {});
        TerminalSize size = dialog.calculatePreferredSize();
        dialog.setSize(size);
        BasicTextImage image = new BasicTextImage(size);

        dialog.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));

        String rendered = renderedText(image);
        assertTrue(rendered.contains("────────────────"));
        assertTrue(rendered.contains("1. Off"));
        assertTrue(rendered.contains("2. Feishu ✓"));
        assertTrue(rendered.contains("3. Slack"));
        assertTrue(rendered.contains("Enter to select · Esc to cancel"));
        assertFalse(rendered.contains("↑/↓ to navigate"), rendered);
    }

    @Test
    void ctrlCAndCtrlDShowReleasedPendingExitFooterAndUseGlobalExitGate() {
        CollaborationPickerDialog dialog = new CollaborationPickerDialog();
        AtomicReference<Character> routed = new AtomicReference<>();
        dialog.setExitGestureHandler(routed::set);
        dialog.show(List.of("feishu"), "", _ -> {});

        route(dialog, new KeyStroke('c', true, false));

        assertEquals('c', routed.get());
        assertTrue(rendered(dialog).contains("Press Ctrl-C again to exit"));

        route(dialog, new KeyStroke('d', true, false));

        assertEquals('d', routed.get());
        assertTrue(rendered(dialog).contains("Press Ctrl-D again to exit"));
        assertTrue(dialog.isActive(), "the global exit controller owns actual shutdown");
    }

    @Test
    void selectContextSupportsRemappingAndNullUnbind(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Select","bindings":{
              "space":"select:accept",
              "enter":null,
              "escape":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            CollaborationPickerDialog dialog = new CollaborationPickerDialog();
            AtomicReference<String> result = new AtomicReference<>();
            dialog.setKeybindingsStore(store);
            dialog.show(List.of("feishu"), "", result::set);

            route(dialog, new KeyStroke(KeyType.ARROW_DOWN));
            route(dialog, new KeyStroke(KeyType.ENTER));
            route(dialog, new KeyStroke(KeyType.ESCAPE));
            assertTrue(dialog.isActive(), "null-unbound defaults must be consumed");
            assertNull(result.get());

            route(dialog, new KeyStroke(' ', false, false));
            assertEquals("feishu", result.get());
            assertFalse(dialog.isActive());
        } finally {
            store.dispose();
        }
    }

    private static void route(CollaborationPickerDialog dialog, KeyStroke key) {
        AtomicBoolean deliver = new AtomicBoolean(true);
        dialog.handleKey(key, deliver);
        assertFalse(deliver.get());
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    private static String renderedText(BasicTextImage image) {
        StringBuilder text = new StringBuilder();
        TerminalSize size = image.getSize();
        for (int row = 0; row < size.getRows(); row++) {
            for (int column = 0; column < size.getColumns(); column++) {
                text.append(image.getCharacterAt(column, row).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String rendered(CollaborationPickerDialog dialog) {
        TerminalSize size = dialog.calculatePreferredSize();
        dialog.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        dialog.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        return renderedText(image);
    }
}
