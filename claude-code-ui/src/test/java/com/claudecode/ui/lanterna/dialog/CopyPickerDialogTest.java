package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.impl.terminal.CopyCommand.CodeBlock;
import com.claudecode.ui.lanterna.dialog.CopyPickerDialog.CopySelection;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CopyPickerDialogTest {

    private static final String FULL_TEXT = "intro\n```java\nint x = 1;\n```\noutro";
    private static final List<CodeBlock> ONE_BLOCK =
        List.of(new CodeBlock("int x = 1;", "java"));

    private static CopyPickerDialog shown(AtomicReference<CopySelection> result,
                                          AtomicBoolean called) {
        CopyPickerDialog dialog = new CopyPickerDialog();
        dialog.show(FULL_TEXT, ONE_BLOCK, sel -> {
            result.set(sel);
            called.set(true);
        });
        return dialog;
    }

    private static void press(CopyPickerDialog dialog, KeyStroke key) {
        dialog.handleKey(key, new AtomicBoolean(true));
    }

    private static KeyStroke ch(char c) {
        return new KeyStroke(c, false, false);
    }

    // ── options ──────────────────────────────────────────────────────────────

    @Test
    void options_fullBlocksAlwaysInTsOrder() {
        var opts = CopyPickerDialog.buildOptions(FULL_TEXT, ONE_BLOCK);
        assertEquals(3, opts.size());
        assertEquals("Full response", opts.getFirst().label());
        assertEquals(FULL_TEXT.length() + " chars, 5 lines", opts.getFirst().description());
        assertEquals("int x = 1;", opts.get(1).label());
        assertEquals("java", opts.get(1).description());
        assertEquals("Always copy full response", opts.get(2).label());
        assertEquals("Skip this picker in the future (revert via /config)",
            opts.get(2).description());
    }

    @Test
    void options_blockDescriptionJoinsLangAndLines() {
        var opts = CopyPickerDialog.buildOptions("x",
            List.of(new CodeBlock("a\nb\nc", "python"),   // lang + 3 lines
                    new CodeBlock("single", null),          // 1 line, no lang → null
                    new CodeBlock("a\nb", null)));          // lines only
        assertEquals("python, 3 lines", opts.get(1).description());
        assertNull(opts.get(2).description());
        assertEquals("2 lines", opts.get(3).description());
    }

    @Test
    void truncateLine_firstLineOnlyWidthAware() {
        assertEquals("short", CopyPickerDialog.truncateLine("short\nsecond line", 60));
        String long70 = "x".repeat(70);
        String truncated = CopyPickerDialog.truncateLine(long70, 60);
        assertEquals(60, truncated.length());
        assertTrue(Strings.CS.endsWith(truncated, "…"));
        // CJK chars are 2 columns wide — fewer fit in the same budget.
        String cjk = "一二三四五六七八九十".repeat(4); // 40 chars, 80 columns
        String cjkTruncated = CopyPickerDialog.truncateLine(cjk, 60);
        assertTrue(Strings.CS.endsWith(cjkTruncated, "…"));
        assertTrue(cjkTruncated.length() <= 31, "≤29 CJK chars (58 cols) + ellipsis");
    }

    // ── selection ────────────────────────────────────────────────────────────

    @Test
    void enterOnFullResponse_returnsFullSelection() {
        AtomicReference<CopySelection> result = new AtomicReference<>();
        AtomicBoolean called = new AtomicBoolean();
        CopyPickerDialog dialog = shown(result, called);
        press(dialog, new KeyStroke(KeyType.ENTER));
        assertTrue(called.get());
        assertEquals(new CopySelection(-1, false, false), result.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void enterOnBlock_returnsBlockIndex() {
        AtomicReference<CopySelection> result = new AtomicReference<>();
        CopyPickerDialog dialog = shown(result, new AtomicBoolean());
        press(dialog, new KeyStroke(KeyType.ARROW_DOWN));
        press(dialog, new KeyStroke(KeyType.ENTER));
        assertEquals(new CopySelection(0, false, false), result.get());
    }

    @Test
    void enterOnAlways_setsAlwaysFlag() {
        AtomicReference<CopySelection> result = new AtomicReference<>();
        CopyPickerDialog dialog = shown(result, new AtomicBoolean());
        press(dialog, new KeyStroke(KeyType.ARROW_DOWN));
        press(dialog, new KeyStroke(KeyType.ARROW_DOWN));
        press(dialog, new KeyStroke(KeyType.ENTER));
        assertEquals(new CopySelection(-1, true, false), result.get());
    }

    @Test
    void wKey_writeOnlyForFocusedOption() {
        AtomicReference<CopySelection> result = new AtomicReference<>();
        CopyPickerDialog dialog = shown(result, new AtomicBoolean());
        press(dialog, new KeyStroke(KeyType.ARROW_DOWN)); // focus the code block
        press(dialog, ch('w'));
        assertEquals(new CopySelection(0, false, true), result.get());
    }

    @Test
    void digitKey_jumpsAndConfirms() {
        AtomicReference<CopySelection> result = new AtomicReference<>();
        CopyPickerDialog dialog = shown(result, new AtomicBoolean());
        press(dialog, ch('2')); // option 2 = the code block
        assertEquals(new CopySelection(0, false, false), result.get());
    }

    @Test
    void escape_returnsNull() {
        AtomicReference<CopySelection> result =
            new AtomicReference<>(new CopySelection(9, false, false));
        AtomicBoolean called = new AtomicBoolean();
        CopyPickerDialog dialog = shown(result, called);
        press(dialog, new KeyStroke(KeyType.ESCAPE));
        assertTrue(called.get());
        assertNull(result.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void ctrlC_cancels() {
        AtomicBoolean called = new AtomicBoolean();
        CopyPickerDialog dialog = shown(new AtomicReference<>(), called);
        press(dialog, new KeyStroke('c', true, false));
        assertTrue(called.get());
        assertFalse(dialog.isActive());
    }

    // ── navigation ───────────────────────────────────────────────────────────

    @Test
    void arrowNavigation_wrapsAround() {
        AtomicReference<CopySelection> result = new AtomicReference<>();
        CopyPickerDialog dialog = shown(result, new AtomicBoolean());
        press(dialog, new KeyStroke(KeyType.ARROW_UP)); // wraps to last = always
        press(dialog, new KeyStroke(KeyType.ENTER));
        assertEquals(new CopySelection(-1, true, false), result.get());
    }

    @Test
    void jkAndCtrlNP_navigate() {
        AtomicReference<CopySelection> result = new AtomicReference<>();
        CopyPickerDialog dialog = shown(result, new AtomicBoolean());
        press(dialog, ch('j'));                          // down → block
        press(dialog, ch('j'));                          // down → always
        press(dialog, ch('k'));                          // up → block
        press(dialog, new KeyStroke('n', true, false));  // Ctrl+n → always
        press(dialog, new KeyStroke('p', true, false));  // Ctrl+p → block
        press(dialog, new KeyStroke(KeyType.ENTER));
        assertEquals(new CopySelection(0, false, false), result.get());
    }

    @Test
    void pageUpDown_jumpToBoundaries() {
        AtomicReference<CopySelection> result = new AtomicReference<>();
        CopyPickerDialog dialog = shown(result, new AtomicBoolean());
        press(dialog, new KeyStroke(KeyType.PAGE_DOWN));
        press(dialog, new KeyStroke(KeyType.ENTER));
        assertEquals(new CopySelection(-1, true, false), result.get(), "PageDown → last (always)");
    }

    // ── overlay contract ─────────────────────────────────────────────────────

    @Test
    void pasteKey_isConsumedNotDelivered() {
        CopyPickerDialog dialog = shown(new AtomicReference<>(), new AtomicBoolean());
        AtomicBoolean deliver = new AtomicBoolean(true);
        dialog.handleKey(new KeyStroke(KeyType.PASTE), deliver);
        assertFalse(deliver.get(),
            "PASTE must be swallowed so it can't leak into the main input");
        assertTrue(dialog.isActive(), "PASTE must not close the picker");
    }

    @Test
    void idle_zeroSizeAndKeysFallThrough() {
        CopyPickerDialog dialog = new CopyPickerDialog();
        assertEquals(0, dialog.calculatePreferredSize().getRows());
        AtomicBoolean deliver = new AtomicBoolean(true);
        dialog.handleKey(new KeyStroke(KeyType.ENTER), deliver);
        assertTrue(deliver.get(), "idle dialog must not consume keys");
    }

    @Test
    void active_hasNonZeroHeightIncludingDescriptionRows() {
        CopyPickerDialog dialog = shown(new AtomicReference<>(), new AtomicBoolean());
        // 4 chrome rows + 3 options each w/ description (2 rows) + gap + footer
        assertEquals(4 + 6 + 2, dialog.calculatePreferredSize().getRows());
    }

    @Test
    void scrollWindow_capsAtFiveVisibleOptions() {
        // 8 blocks + full + always = 10 options; window = 5.
        List<CodeBlock> many = IntStream.range(0, 8)
            .mapToObj(i -> new CodeBlock("code" + i, null))
            .toList();
        AtomicReference<CopySelection> result = new AtomicReference<>();
        CopyPickerDialog dialog = new CopyPickerDialog();
        dialog.show("full", many, result::set);
        // Walk to the last option: window slides, wraps nothing.
        for (int i = 0; i < 9; i++) press(dialog, new KeyStroke(KeyType.ARROW_DOWN));
        press(dialog, new KeyStroke(KeyType.ENTER));
        assertEquals(new CopySelection(-1, true, false), result.get(),
            "last option (always) reachable through the scroll window");
    }
}
