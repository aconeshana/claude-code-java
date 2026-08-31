package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * State-machine tests for {@link ThemePickerDialog}, driven directly (no real
 * GUI thread) — same pattern as {@code DoctorDialogTest}.
 */
class ThemePickerDialogTest {

    @TempDir Path tempDir;

    @Test
    void themePickerContextActionTogglesAndPersistsSyntaxHighlighting() throws Exception {
        AtomicBoolean disabled = new AtomicBoolean(false);
        ThemePickerDialog d = new ThemePickerDialog(disabled::get, disabled::set);
        var store = createStore(tempDir.resolve("toggle.json"), """
            [{"context":"ThemePicker","bindings":{"ctrl+x":"theme:toggleSyntaxHighlighting"}}]
            """);
        try {
            d.setKeybindingsStore(store);
            d.show("dark", _ -> {}, _ -> {});

            AtomicBoolean deliver = new AtomicBoolean(true);
            d.handleKey(new KeyStroke('x', true, false), deliver);

            assertTrue(disabled.get());
            assertTrue(d.syntaxHighlightingDisabled());
            assertFalse(deliver.get());
            assertTrue(d.isActive());
        } finally {
            store.dispose();
        }
    }

    @Test
    void syntaxToggleCanBeExplicitlyUnbound() throws Exception {
        AtomicBoolean disabled = new AtomicBoolean(false);
        ThemePickerDialog d = new ThemePickerDialog(disabled::get, disabled::set);
        var store = createStore(tempDir.resolve("unbind.json"), """
            [{"context":"ThemePicker","bindings":{"ctrl+t":null}}]
            """);
        try {
            d.setKeybindingsStore(store);
            d.show("dark", _ -> {}, _ -> {});

            AtomicBoolean deliver = new AtomicBoolean(true);
            d.handleKey(new KeyStroke('t', true, false), deliver);

            assertFalse(disabled.get());
            assertFalse(deliver.get());
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(Path file, String json)
            throws Exception {
        Files.writeString(file, json);
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    @Test
    void idle_hasZeroPreferredSize() {
        ThemePickerDialog d = new ThemePickerDialog();
        assertFalse(d.isActive());
        assertEquals(new TerminalSize(0, 0), d.calculatePreferredSize());
    }

    @Test
    void show_activatesAndPreselectsMatchingIndex() {
        // Navigating up from "light-ansi" (index 5) should preview "dark-ansi" (index 4).
        List<String> previews = new ArrayList<>();
        ThemePickerDialog d = new ThemePickerDialog();
        d.show("light-ansi", previews::add, _ -> {});
        assertTrue(d.isActive());

        d.handleKey(new KeyStroke(KeyType.ARROW_UP), new AtomicBoolean(true));
        assertEquals(List.of("dark-ansi"), previews);
    }

    @Test
    void show_unknownName_fallsBackToFirstIndex() {

        List<String> previews = new ArrayList<>();
        ThemePickerDialog d = new ThemePickerDialog();
        d.show("not-a-real-theme", previews::add, _ -> {});
        // From "auto" (index 0), arrow-down should preview "dark" (index 1).
        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        assertEquals(List.of("dark"), previews);
    }

    @Test
    void arrowNavigation_firesOnPreviewWithNewValue() {
        ThemePickerDialog d = new ThemePickerDialog();
        List<String> previews = new ArrayList<>();
        d.show("dark", previews::add, _ -> {});

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));

        assertEquals(List.of("light", "dark-daltonized"), previews);
    }

    @Test
    void arrowUp_atTop_wrapsToBottomAndPreviews() {

        ThemePickerDialog d = new ThemePickerDialog();
        List<String> previews = new ArrayList<>();
        d.show("auto", previews::add, _ -> {}); // index 0, top of list

        d.handleKey(new KeyStroke(KeyType.ARROW_UP), new AtomicBoolean(true));

        assertEquals(List.of("light-ansi"), previews);
    }

    @Test
    void arrowDown_atBottom_wrapsToTopAndPreviews() {

        ThemePickerDialog d = new ThemePickerDialog();
        List<String> previews = new ArrayList<>();
        d.show("light-ansi", previews::add, _ -> {}); // index 6, bottom of list

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));

        assertEquals(List.of("auto"), previews);
    }

    @Test
    void enter_resolvesWithSelectedValue() {
        ThemePickerDialog d = new ThemePickerDialog();
        AtomicReference<String> result = new AtomicReference<>("unset");
        d.show("dark", _ -> {}, result::set);

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true)); // -> light
        d.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals("light", result.get());
        assertFalse(d.isActive());
    }

    @Test
    void escape_revertsPreviewToOriginalThenResolvesNull() {
        ThemePickerDialog d = new ThemePickerDialog();
        List<String> previews = new ArrayList<>();
        AtomicReference<String> result = new AtomicReference<>("unset");
        d.show("dark", previews::add, result::set);

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true)); // preview "light"
        d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));

        assertEquals(List.of("light", "dark"), previews); // nav preview, then revert to original
        assertNull(result.get());
        assertFalse(d.isActive());
    }

    @Test
    void handleKey_consumesKeysWhileActive() {
        ThemePickerDialog d = new ThemePickerDialog();
        d.show("dark", _ -> {}, _ -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), deliver);

        assertFalse(deliver.get());
    }

    @Test
    void handleKey_noOpWhileIdle() {
        ThemePickerDialog d = new ThemePickerDialog();
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new KeyStroke(KeyType.ENTER), deliver);

        assertTrue(deliver.get()); // untouched — dialog is idle, key falls through
        assertFalse(d.isActive());
    }



    @Test
    void jKey_stepsDownLikeArrowDown() {
        ThemePickerDialog d = new ThemePickerDialog();
        List<String> previews = new ArrayList<>();
        d.show("dark", previews::add, _ -> {});

        d.handleKey(new KeyStroke('j', false, false), new AtomicBoolean(true));

        assertEquals(List.of("light"), previews);
    }

    @Test
    void kKey_stepsUpLikeArrowUp() {
        ThemePickerDialog d = new ThemePickerDialog();
        List<String> previews = new ArrayList<>();
        d.show("dark", previews::add, _ -> {});

        d.handleKey(new KeyStroke('k', false, false), new AtomicBoolean(true));

        assertEquals(List.of("auto"), previews);
    }

    @Test
    void ctrlN_stepsDown_ctrlP_stepsUp() {
        ThemePickerDialog d = new ThemePickerDialog();
        List<String> previews = new ArrayList<>();
        d.show("dark", previews::add, _ -> {});

        d.handleKey(new KeyStroke('n', true, false), new AtomicBoolean(true));
        d.handleKey(new KeyStroke('p', true, false), new AtomicBoolean(true));
        d.handleKey(new KeyStroke('p', true, false), new AtomicBoolean(true));

        assertEquals(List.of("light", "dark", "auto"), previews);
    }



    @Test
    void digitKey_jumpsAndImmediatelyConfirms() {
        ThemePickerDialog d = new ThemePickerDialog();
        AtomicReference<String> result = new AtomicReference<>("unset");
        d.show("auto", _ -> {}, result::set);

        // "3" -> 3rd option (1-based) = index 2 = "light"
        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(new KeyStroke('3', false, false), deliver);

        assertEquals("light", result.get());
        assertFalse(d.isActive());
        assertFalse(deliver.get());
    }

    @Test
    void digitKey_outOfRange_isIgnored() {
        ThemePickerDialog d = new ThemePickerDialog();
        AtomicReference<String> result = new AtomicReference<>("unset");
        d.show("auto", _ -> {}, result::set);

        d.handleKey(new KeyStroke('9', false, false), new AtomicBoolean(true));

        assertEquals("unset", result.get());
        assertTrue(d.isActive());
    }



    @Test
    void pageDown_jumpsToLastOptionNoWrap() {
        ThemePickerDialog d = new ThemePickerDialog();
        List<String> previews = new ArrayList<>();
        d.show("dark", previews::add, _ -> {});

        d.handleKey(new KeyStroke(KeyType.PAGE_DOWN), new AtomicBoolean(true));

        assertEquals(List.of("light-ansi"), previews);
    }

    @Test
    void pageUp_jumpsToFirstOptionNoWrap() {
        ThemePickerDialog d = new ThemePickerDialog();
        List<String> previews = new ArrayList<>();
        d.show("light-ansi", previews::add, _ -> {});

        d.handleKey(new KeyStroke(KeyType.PAGE_UP), new AtomicBoolean(true));

        assertEquals(List.of("auto"), previews);
    }
}
