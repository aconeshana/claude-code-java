package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.paste.InputPasteTruncation;
import com.claudecode.core.paste.PastedRefParser;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chip-aware editing over the prompt text: {@code [Image #N]},
 * {@code [Pasted text #N +M lines]} and {@code [...Truncated text #N ...]}
 * references are atomic cursor tokens. The caret hops over them, Backspace
 * and Delete remove them whole, and content whose chip disappears from the
 * text is pruned from the pasted-content store.
 *
 * <p>All text mutation goes through {@link Host} so the owning panel keeps
 * its mode/query notifications and undo bookkeeping in one place.
 *
 * <ul>
 *   <li>{@code src/components/PromptInput/usePastedContent.ts} /
 *       {@code utils/Cursor.ts} — {@code imageRefStartingAt} /
 *       {@code imageRefEndingAt} hop semantics and whole-token deletion.</li>
 *   <li>{@code src/components/PromptInput/PromptInput.tsx} — pruning pasted
 *       contents whose reference left the input ({@code useEffect([input])}).</li>
 *   <li>{@code src/utils/messages.ts} — {@code maybeTruncateMessageForInput}
 *       folding oversized programmatic input into a truncation chip.</li>
 * </ul>
 */
final class PromptChipEditor {

    /** Text-box access plus the panel's post-edit notifications. */
    interface Host {
        String text();
        int caret();
        /** Replaces the text without any mode/query side effects. */
        void setTextRaw(String text);
        void moveCaretTo(int offset);
        /** Mode re-detection + query-changed notification after an edit. */
        void textEdited();
        /** Snapshots the draft for Ctrl+_ before an insertion. */
        void recordUndoSnapshot();
        void pastedContentsChanged();
    }

    /** Result of {@link #truncateForInput}. */
    record Truncation(String text, boolean applied) {}

    private static final Pattern IMAGE_REF_AT_START = Pattern.compile("^\\[Image #\\d+]");
    private static final Pattern IMAGE_REF_AT_END = Pattern.compile("\\[Image #\\d+]$");
    /** A token ref immediately before the caret, preceded by start-of-text or whitespace. */
    private static final Pattern TOKEN_REF_AT_END = Pattern.compile(
        "(^|\\s)\\[(Pasted text #\\d+(?: \\+\\d+ lines)?|Image #\\d+"
            + "|\\.\\.\\.Truncated text #\\d+ \\+\\d+ lines\\.\\.\\.)]$");

    private final Host host;
    private final PromptPastedContentController pastedContent;

    PromptChipEditor(Host host, PromptPastedContentController pastedContent) {
        this.host = host;
        this.pastedContent = pastedContent;
    }

    /** Inserts {@code chip} at the caret and leaves the caret after it. */
    void insertAtCursor(String chip) {
        insertAtCursor(chip, false);
    }

    /**
     * Inserts a chip; {@code armLazySpace} defers a separating space until the
     * next printable keystroke (image chips).
     */
    void insertAtCursor(String chip, boolean armLazySpace) {
        host.recordUndoSnapshot();
        String text = host.text();
        int caret = Math.min(host.caret(), text.length()); // Lanterna may report past-end on empty input
        String prefix = pastedContent.prefixBeforeChipAndArm(armLazySpace);
        host.setTextRaw(text.substring(0, caret) + prefix + chip + text.substring(caret));
        // Position the caret directly: synthetic arrow keystrokes would route
        // back through the chip hop and skip the chip just inserted.
        host.moveCaretTo(caret + prefix.length() + chip.length());
        host.textEdited();
    }

    /** ← at a chip's end jumps to its start. */
    boolean hopLeft() {
        int[] chip = imageRefEndingAt(host.text(), host.caret());
        if (chip == null) return false;
        host.moveCaretTo(chip[0]);
        return true;
    }

    /** → at a chip's start jumps past it. */
    boolean hopRight() {
        int[] chip = imageRefStartingAt(host.text(), host.caret());
        if (chip == null) return false;
        host.moveCaretTo(chip[1]);
        return true;
    }

    /** Backspace removes a whole chip before/at the caret; false = ordinary backspace. */
    boolean backspace() {
        String text = host.text();
        int caret = host.caret();

        // Case 1: caret at chip start → delete the chip forward (+ one trailing space)
        int[] chipAfter = imageRefStartingAt(text, caret);
        if (chipAfter != null) {
            int end = chipAfter[1];
            if (end < text.length() && text.charAt(end) == ' ') end++;
            replace(text.substring(0, caret) + text.substring(end), caret);
            return true;
        }

        // Case 2: caret right after a pasted/truncated/image ref, next char is whitespace/EOL
        if (caret > 0 && (caret >= text.length() || Character.isWhitespace(text.charAt(caret)))) {
            Matcher m = TOKEN_REF_AT_END.matcher(text.substring(0, caret));
            if (m.find()) {
                int start = m.start() + m.group(1).length();
                replace(text.substring(0, start) + text.substring(caret), start);
                return true;
            }
        }

        // Case 3: caret at chip end → remove the chip behind it
        int[] chipBefore = imageRefEndingAt(text, caret);
        if (chipBefore != null) {
            replace(text.substring(0, chipBefore[0]) + text.substring(chipBefore[1]), chipBefore[0]);
            return true;
        }
        return false;
    }

    /** Delete removes a whole chip starting at the caret; false = ordinary delete. */
    boolean delete() {
        String text = host.text();
        int caret = host.caret();
        int[] chip = imageRefStartingAt(text, caret);
        if (chip == null) return false;
        replace(text.substring(0, chip[0]) + text.substring(chip[1]), chip[0]);
        return true;
    }

    private void replace(String newText, int caret) {
        host.setTextRaw(newText);
        host.moveCaretTo(caret);
        host.textEdited();
    }

    /** Drops image contents whose {@code [Image #N]} chip is no longer present in the text. */
    void pruneOrphanedImages() {
        if (pastedContent.isEmpty()) return;
        Set<Integer> referenced = new HashSet<>();
        for (PastedRefParser.Ref ref : PastedRefParser.parseReferences(host.text())) {
            referenced.add(ref.id());
        }
        boolean changed = false;
        for (PastedContent c : pastedContent.valuesSnapshot()) {
            if (c.isImage() && !referenced.contains(c.id())) {
                pastedContent.remove(c.id());
                changed = true;
            }
        }
        if (changed) host.pastedContentsChanged();
    }

    /**
     * Folds programmatic input above the truncation threshold into a
     * {@code [...Truncated text #N ...]} chip whose full content is stored.
     */
    Truncation truncateForInput(String text) {
        if (text == null) return new Truncation("", false);
        // Take an id only once the length gate passes — nextId increments a counter.
        if (text.length() <= InputPasteTruncation.TRUNCATION_THRESHOLD) {
            return new Truncation(text, false);
        }
        int pasteId = pastedContent.nextId();
        InputPasteTruncation.Truncated truncated =
            InputPasteTruncation.maybeTruncateMessageForInput(text, pasteId);
        pastedContent.put(PastedContent.text(pasteId, truncated.placeholderContent()));
        host.pastedContentsChanged();
        return new Truncation(truncated.truncatedText(), true);
    }

    private static int[] imageRefStartingAt(String text, int offset) {
        Matcher m = IMAGE_REF_AT_START.matcher(text.substring(offset));
        return m.find() ? new int[]{offset, offset + m.group().length()} : null;
    }

    private static int[] imageRefEndingAt(String text, int offset) {
        Matcher m = IMAGE_REF_AT_END.matcher(text.substring(0, offset));
        return m.find() ? new int[]{offset - m.group().length(), offset} : null;
    }
}
