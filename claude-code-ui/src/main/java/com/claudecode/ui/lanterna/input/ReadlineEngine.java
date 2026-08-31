package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.gui2.Interactable.Result;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.function.Function;

/**
 * The pure Emacs/readline editing layer of {@link InputPanel}: cursor motion, word motion, and the
 * kill/yank ring.
 */
final class ReadlineEngine {

    // Raw control-byte values Lanterna delivers as KeyStroke characters.
    private static final char CTRL_K = 11;
    private static final char CTRL_U = 21;
    private static final char CTRL_W = 23;
    private static final char CTRL_Y = 25;

    /** The edited text box (owns the actual text + caret). */
    private final TextBox textBox;
    /**
     * The anonymous subclass's {@code super.handleKeyStroke}. Used to move the
     * caret via synthetic HOME/END/ARROW keystrokes — the parent Lanterna
     * TextBox handles the real cross-row motion. Bypassing the overridden
     * {@code handleKeyStroke} avoids the chip-hop re-entrancy that would recurse.
     */
    private final Function<KeyStroke, Result> superKey;

    private final Runnable onChange;

    /** Start position of the last yank, for Alt+Y yank-pop. */
    private int lastYankStart = -1;
    /** Length of the last yanked text, for Alt+Y yank-pop. */
    private int lastYankLength = 0;

    ReadlineEngine(TextBox textBox, Function<KeyStroke, Result> superKey, Runnable onChange) {
        this.textBox = textBox;
        this.superKey = superKey;
        this.onChange = onChange;
    }

    // ── Caret primitives ────────────────────────────────────────────────────

    private int caret() {
        try { return TextBoxOffsetAdapter.offset(textBox); }
        catch (Exception _) { return textBox.getText().length(); }
    }

    /** Move the Lanterna caret to an absolute prompt offset. */
    private void moveCaret(int target) {
        TextBoxOffsetAdapter.setOffset(textBox, target);
    }

    /**
     * Replace the whole text and place the caret at absolute offset
     * {@code newCaret}, then fire the change notification. Every text-mutating edit routes through here so the
     * "set text → reposition caret → notify" sequence lives in exactly one place.
     */
    private Result replace(String newText, int newCaret) {
        textBox.setText(newText);
        TextBoxOffsetAdapter.setOffset(textBox, newCaret);
        onChange.run();
        return Result.HANDLED;
    }

    // ── Motion / endpoints ──────────────────────────────────────────────────

    Result home() {
        return superKey.apply(new KeyStroke(KeyType.HOME, false, false));
    }

    Result end() {
        return superKey.apply(new KeyStroke(KeyType.END, false, false));
    }

    Result left() {
        return superKey.apply(new KeyStroke(KeyType.ARROW_LEFT, false, false));
    }

    Result right() {
        return superKey.apply(new KeyStroke(KeyType.ARROW_RIGHT, false, false));
    }

    Result prevWord() {
        String text = textBox.getText();
        moveCaret(prevWordBoundary(text, caret()));
        return Result.HANDLED;
    }

    Result nextWord() {
        String text = textBox.getText();
        moveCaret(nextWordBoundary(text, caret()));
        return Result.HANDLED;
    }

    // ── Kill / yank ─────────────────────────────────────────────────────────

    Result killToEnd() {
        String text = textBox.getText();
        int c = caret();
        int end = TextBoxOffsetAdapter.logicalLineEnd(textBox);
        if (end <= c) return Result.HANDLED;
        KillRing.INSTANCE.push(text.substring(c, end), KillRing.Direction.APPEND);
        return replace(text.substring(0, c) + text.substring(end), c);
    }

    Result killToStart() {
        String text = textBox.getText();
        int c = caret();
        int start = TextBoxOffsetAdapter.logicalLineStart(textBox);
        if (start >= c) return Result.HANDLED;
        KillRing.INSTANCE.push(text.substring(start, c), KillRing.Direction.PREPEND);
        return replace(text.substring(0, start) + text.substring(c), start);
    }

    Result killWordBefore() {
        String text = textBox.getText();
        int c = caret();
        if (c <= 0) return Result.HANDLED;
        int start = wordStartBefore(text, c);
        KillRing.INSTANCE.push(text.substring(start, c), KillRing.Direction.PREPEND);
        return replace(text.substring(0, start) + text.substring(c), start);
    }

    Result killWordAfter() {
        String text = textBox.getText();
        int c = caret();
        if (c >= text.length()) return Result.HANDLED;
        int end = wordEndAfter(text, c);
        return replace(text.substring(0, c) + text.substring(end), c);
    }

    Result yank() {
        String kill = KillRing.INSTANCE.getLast();
        if (kill.isEmpty()) return Result.HANDLED;
        String text = textBox.getText();
        int c = caret();
        lastYankStart = c;
        lastYankLength = kill.length();
        KillRing.INSTANCE.recordYank();
        return replace(text.substring(0, c) + kill + text.substring(c), c + kill.length());
    }

    Result yankPop() {
        String next = KillRing.INSTANCE.yankPop();
        if (next == null || lastYankStart < 0) return Result.HANDLED;
        String text = textBox.getText();
        int yankEnd = lastYankStart + lastYankLength;
        if (yankEnd > text.length() || lastYankStart > text.length()) return Result.HANDLED;
        lastYankLength = next.length();
        KillRing.INSTANCE.recordYank();
        return replace(
            text.substring(0, lastYankStart) + next + text.substring(yankEnd),
            lastYankStart + next.length());
    }

    // ── Key predicates (kill/yank accumulation gates) ───────────────────────


    static boolean isKillKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isAltDown()) {
            char ch = key.getCharacter();
            if (ch == CTRL_K || ch == CTRL_U || ch == CTRL_W) return true;
        }
        return (key.getKeyType() == KeyType.BACKSPACE || key.getKeyType() == KeyType.DELETE)
                && key.isAltDown();
    }


    static boolean isYankKey(KeyStroke key) {
        if (key.getKeyType() != KeyType.CHARACTER || key.getCharacter() == null) return false;
        char ch = key.getCharacter();
        if (ch == CTRL_Y && !key.isAltDown()) return true;
        return key.isAltDown() && (ch == 'y' || ch == 'Y');
    }

    // ── Pure word-boundary / kill-range logic (Lanterna-free, unit-tested) ──

    /**
     * The caret position after moving one word left: skip trailing non-word
     * chars, then the word chars. matches {@code rl_prevWord}'s scan.
     */
    static int prevWordBoundary(String text, int caret) {
        int pos = Math.max(0, Math.min(caret, text.length()));
        while (pos > 0 && !Character.isLetterOrDigit(text.charAt(pos - 1))) pos--;
        while (pos > 0 &&  Character.isLetterOrDigit(text.charAt(pos - 1))) pos--;
        return pos;
    }

    /**
     * The caret position after moving one word right: skip leading non-word
     * chars, then the word chars. matches {@code rl_nextWord}'s scan.
     */
    static int nextWordBoundary(String text, int caret) {
        int len = text.length();
        int pos = Math.max(0, Math.min(caret, len));
        while (pos < len && !Character.isLetterOrDigit(text.charAt(pos))) pos++;
        while (pos < len &&  Character.isLetterOrDigit(text.charAt(pos))) pos++;
        return pos;
    }

    /**
     * The start of the kill-word-before span: from {@code caret}, skip trailing
     * whitespace then the word chars. The span deleted by {@link #killWordBefore}
     * is {@code [wordStartBefore(text, caret), caret)}. matches {@code rl_killWordBefore}'s scan.
     */
    static int wordStartBefore(String text, int caret) {
        int pos = Math.max(0, Math.min(caret, text.length()));
        while (pos > 0 && Character.isWhitespace(text.charAt(pos - 1))) pos--;
        while (pos > 0 && !Character.isWhitespace(text.charAt(pos - 1))) pos--;
        return pos;
    }

    /**
     * The end of the kill-word-after span: from {@code caret}, skip leading
     * whitespace then the word chars. The span deleted by {@link #killWordAfter}
     * is {@code [caret, wordEndAfter(text, caret))}. matches {@code rl_killWordAfter}'s scan.
     */
    static int wordEndAfter(String text, int caret) {
        int len = text.length();
        int pos = Math.max(0, Math.min(caret, len));
        while (pos < len && Character.isWhitespace(text.charAt(pos))) pos++;
        while (pos < len && !Character.isWhitespace(text.charAt(pos))) pos++;
        return pos;
    }
}
