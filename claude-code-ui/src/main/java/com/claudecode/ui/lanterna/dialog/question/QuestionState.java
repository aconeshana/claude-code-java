package com.claudecode.ui.lanterna.dialog.question;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.PasteKeyStroke;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Mutable per-question UI state for the {@code AskUserQuestion} card: which options are ticked,
 * where the focus sits, and the free-text buffer with its insertion point.
 *
 * <p>Authority is the {@code 2.1.236} bundle; the reverse-engineered 2.1.197 counterparts are
 * {@code components/CustomSelect/use-multi-select-state.ts} and
 * {@code AskUserQuestionPermissionRequest/use-multiple-choice-state.ts}.
 *
 * <ul>
 *   <li>Covers: the per-question slice of {@code questionStates} — {@code selectedValues},
 *       {@code textInputValue}, and the focus cursor.</li>
 *   <li>Covers: {@code TextInput}'s {@code cursorOffset} editing — insert, backspace, and
 *       bracketed paste all apply at the insertion point. See {@link #applyEdit(KeyStroke)}.</li>
 *   <li>Covers: {@code d$c}'s {@code g} (notes editing) and {@code m} (chat row focused) flags.
 *       See {@link #notesEditing()} and {@link #chatFocused()}.</li>
 * </ul>
 *
 * <p>Deliberately mutable and deliberately not thread-safe: it is only ever touched from the
 * Lanterna GUI thread, and the renderers read it in place rather than through a copy.
 */
public final class QuestionState {

    private final Set<Integer> selected = new LinkedHashSet<>();
    private final StringBuilder text = new StringBuilder();
    private boolean otherSelected;
    private boolean notesEditing;
    private boolean chatFocused;
    private int cursor;
    private int focus;

    // ── selection ───────────────────────────────────────────────────────────

    /** Flips one option's tick. */
    public void toggle(int index) {
        if (!selected.remove(index)) selected.add(index);
    }

    /** Replaces the whole selection with a single option (single-select). */
    public void selectOnly(int index) {
        selected.clear();
        selected.add(index);
    }

    public void clearSelection() {
        selected.clear();
    }

    public boolean isSelected(int index) {
        return selected.contains(index);
    }

    public boolean hasSelection() {
        return !selected.isEmpty();
    }

    /** The ticked option indices, in the order they were ticked. */
    public List<Integer> selectedIndices() {
        return List.copyOf(selected);
    }

    public boolean otherSelected() {
        return otherSelected;
    }

    public void setOtherSelected(boolean value) {
        otherSelected = value;
    }

    // ── focus ───────────────────────────────────────────────────────────────

    public int focus() {
        return focus;
    }

    public void setFocus(int value) {
        focus = value;
    }

    // ── design-card modes ───────────────────────────────────────────────────

    /**
     * {@code d$c}'s {@code g}: the notes editor holds the keyboard.
     *
     * <p>The bundle keeps this in the card component rather than per question, but a tab switch is
     * blocked while it is set ({@code isActive: !g && !m}), so the two placements cannot be told
     * apart from the outside — and keeping it here lets the view take a single state object.
     */
    public boolean notesEditing() {
        return notesEditing;
    }

    public void setNotesEditing(boolean value) {
        notesEditing = value;
    }

    /** {@code d$c}'s {@code m}: the {@code Chat about this} row is focused instead of an option. */
    public boolean chatFocused() {
        return chatFocused;
    }

    public void setChatFocused(boolean value) {
        chatFocused = value;
    }

    // ── free text ───────────────────────────────────────────────────────────

    public String text() {
        return text.toString();
    }

    public boolean textEmpty() {
        return text.isEmpty();
    }

    public int cursor() {
        return cursor;
    }

    public void moveCursor(int delta) {
        cursor = Math.max(0, Math.min(text.length(), cursor + delta));
    }

    public void cursorToStart() {
        cursor = 0;
    }

    public void cursorToEnd() {
        cursor = text.length();
    }

    /** Forward delete at the insertion point; a no-op at the end of the buffer. */
    public void deleteForward() {
        if (cursor < text.length()) text.deleteCharAt(cursor);
    }

    /**
     * Cursor-aware single-line edit: printable chars, backspace, and bracketed paste all apply at
     * the insertion point instead of only at the buffer tail. Pasted newlines normalize to spaces,
     * same as {@code TextInputs}.
     */
    public void applyEdit(KeyStroke key) {
        switch (key.getKeyType()) {
            case BACKSPACE -> {
                if (cursor > 0) {
                    text.deleteCharAt(cursor - 1);
                    cursor--;
                }
            }
            case PASTE -> {
                if (key instanceof PasteKeyStroke paste
                        && StringUtils.isNotEmpty(paste.getPastedText())) {
                    String normalized = paste.getPastedText()
                        .replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
                    text.insert(cursor, normalized);
                    cursor += normalized.length();
                }
            }
            case CHARACTER -> {
                Character ch = key.getCharacter();
                if (ch != null && ch >= 0x20 && !key.isCtrlDown() && !key.isAltDown()) {
                    text.insert(cursor, ch.charValue());
                    cursor++;
                }
            }
            default -> { /* non-text key */ }
        }
    }
}
