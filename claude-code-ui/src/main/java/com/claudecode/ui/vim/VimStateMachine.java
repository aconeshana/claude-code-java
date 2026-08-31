package com.claudecode.ui.vim;

import org.apache.commons.lang3.Strings;

import java.text.BreakIterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * Vim-style state machine for terminal input handling.
 */
public class VimStateMachine {

    private VimMode mode = VimMode.INSERT;
    private VimOperator pendingOperator;
    private char pendingFind;
    private boolean findForward;
    private boolean findTill;

    // Pending text object: operator + scope awaiting the target char (e.g. d i " → ")
    private VimOperator pendingTextObjectOp;
    private VimTextObject.Scope pendingTextObjectScope;

    // Pending operator+find (d f x / c t "): operator awaiting the find target
    private VimOperator pendingFindOp;
    private String lastChange = "";
    private final StringBuilder currentChange = new StringBuilder();

    // Count/multiplier
    private int pendingCount = 0;
    private boolean countingDigits = false;

    // g-prefix state (gg / gj / gk). Set when 'g' is seen; cleared once the
    // follow-up key is consumed (or on cancel).
    private boolean pendingGoto = false;

    // Undo/redo history
    private final Deque<UndoState> undoStack = new ArrayDeque<>();
    private final Deque<UndoState> redoStack = new ArrayDeque<>();
    private static final int MAX_UNDO_DEPTH = 1000;



    // register is covered by yankRegister, which every operator populates.

    // Repeat find
    private char lastFindChar = 0;
    private boolean lastFindForward = true;
    private boolean lastFindTill = false;

    // Buffer state
    private StringBuilder buffer;
    private int cursor;
    // Sticky column used by vertical motions (j/k/gj/gk) so the cursor keeps
// its column across lines of differing length (matches vim).
    private int preferredColumn;


    // OperatorContext.setRegister(content, linewise)).
    private String yankRegister = "";
    private boolean yankLinewise = false;

    public VimStateMachine() {
        this.buffer = new StringBuilder();
        this.cursor = 0;
        this.preferredColumn = 0;
    }

    public VimMode getMode() {
        return mode;
    }

    public int getCursor() {
        return cursor;
    }

    /**
     * Forces the internal cursor to {@code pos}, clamped to {@code [0, buffer.length]}.
     * Used by InputPanel to re-sync vim state after a readline shortcut moved the textBox caret.
     */
    public void setCursor(int pos) {
        this.cursor = Math.max(0, Math.min(pos, buffer.length()));
        this.preferredColumn = colOf(buffer.toString(), this.cursor);
    }

    public String getBuffer() {
        return buffer.toString();
    }

    public void setBuffer(String text) {
        pushUndoState();
        this.buffer = new StringBuilder(text);
        this.cursor = Math.min(cursor, Math.max(0, buffer.length() - 1));
        this.preferredColumn = colOf(buffer.toString(), this.cursor);
        this.pendingGoto = false;
        this.yankLinewise = false;
    }

    public String getYankRegister() {
        return yankRegister;
    }

    // Undo
    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        pushRedoState();
        UndoState state = undoStack.pop();
        buffer = new StringBuilder(state.text);
        cursor = state.cursor;
        preferredColumn = colOf(buffer.toString(), cursor);
        return true;
    }

    // Redo
    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        pushUndoState();
        UndoState state = redoStack.pop();
        buffer = new StringBuilder(state.text);
        cursor = state.cursor;
        preferredColumn = colOf(buffer.toString(), cursor);
        return true;
    }

    /**
     * Process a key press and return the resulting action.
     */
    public VimAction processKey(char key) {
        return switch (mode) {
            case INSERT -> processInsertMode(key);
            case NORMAL -> processNormalMode(key);
        };
    }

    private VimAction processInsertMode(char key) {
        if (key == 27) { // ESC
            mode = VimMode.NORMAL;
            if (cursor > 0 && cursor >= buffer.length()) {
                cursor = buffer.length() - 1;
            }
            if (!currentChange.isEmpty()) {
                lastChange = currentChange.toString();
                currentChange.setLength(0);
            }
            countingDigits = false;
            pendingCount = 0;
            return VimAction.modeChange(VimMode.NORMAL);
        }
        if (key == 127 || key == 8) { // Backspace
            if (cursor > 0) {
                pushUndoState();
                buffer.deleteCharAt(cursor - 1);
                cursor--;
                preferredColumn = colOf(buffer.toString(), cursor);
            }
            currentChange.append(key);
            return VimAction.bufferChanged();
        }
        pushUndoState();
        buffer.insert(cursor, key);
        cursor++;
        preferredColumn = colOf(buffer.toString(), cursor);
        currentChange.append(key);
        return VimAction.bufferChanged();
    }

    private VimAction processNormalMode(char key) {
        // ---- Pending state: consume the next key as a continuation ----
        // Text object operator awaiting its target char (d i " → " etc.)
        if (pendingTextObjectOp != null) {
            if (key == 27) { // ESC cancels the pending text object
                pendingTextObjectOp = null;
                pendingTextObjectScope = null;
                resetCount();
                return VimAction.none();
            }
            VimAction a = processTextObjectTarget(pendingTextObjectOp, pendingTextObjectScope, key);
            pendingTextObjectOp = null;
            pendingTextObjectScope = null;
            return a;
        }
        // Find (f/F/t/T) awaiting its target char
        if (pendingFind != 0) {
            if (key == 27) { // ESC cancels the pending find
                pendingFind = 0;
                pendingFindOp = null;
                resetCount();
                return VimAction.none();
            }
            if (pendingFindOp != null) {
                VimAction a = processOperatorFind(pendingFindOp, findForward, findTill, key);
                pendingFindOp = null;
                return a;
            }
            return processFindChar(key);
        }
        // g-prefix continuation (gg / gj / gk), possibly with a pending operator (dgg)
        if (pendingGoto) {
            return processGoto(key);
        }

        // Count/multiplier digit handling

        // line-start motion, not a count prefix). Subsequent digits (incl. '0',
        // e.g. "10w") are accumulated below.
        if (key >= '1' && key <= '9' && !countingDigits && pendingOperator == null) {
            pendingCount = key - '0';
            countingDigits = true;
            return VimAction.countPending(pendingCount);
        }
        if (Character.isDigit(key) && countingDigits) {
            pendingCount = pendingCount * 10 + (key - '0');
            return VimAction.countPending(pendingCount);
        }

        // Undo/redo
        if (key == 'u' && pendingOperator == null) {
            int count = countingDigits ? Math.max(1, pendingCount) : 1;
            for (int i = 0; i < count; i++) {
                if (!undo()) break;
            }
            resetCount();
            return VimAction.bufferChanged();
        }
        if (key == 18) { // Ctrl+R for redo
            int count = countingDigits ? Math.max(1, pendingCount) : 1;
            for (int i = 0; i < count; i++) {
                if (!redo()) break;
            }
            resetCount();
            return VimAction.bufferChanged();
        }

        // Pending operator handling — MUST precede the i/a/A/I mode switches,
        // otherwise i/a would be misread as "enter insert" while an operator is
        // pending (e.g. "di"" would drop into INSERT and type the quote instead
        // of being parsed as a text-object scope).
        if (pendingOperator != null) {
            return processOperatorPending(key);
        }

        // Mode switches
        if (key == 'i') {
            mode = VimMode.INSERT;
            currentChange.setLength(0);
            resetCount();
            return VimAction.modeChange(VimMode.INSERT);
        }
        if (key == 'a') {
            mode = VimMode.INSERT;
            if (!buffer.isEmpty()) {
                cursor = Math.min(cursor + 1, buffer.length());
                preferredColumn = colOf(buffer.toString(), cursor);
            }
            currentChange.setLength(0);
            resetCount();
            return VimAction.modeChange(VimMode.INSERT);
        }
        if (key == 'A') {
            mode = VimMode.INSERT;
            cursor = buffer.length();
            preferredColumn = colOf(buffer.toString(), cursor);
            currentChange.setLength(0);
            resetCount();
            return VimAction.modeChange(VimMode.INSERT);
        }
        if (key == 'I') {
            mode = VimMode.INSERT;
            cursor = firstNonBlankOnLine(buffer.toString(), cursor);
            preferredColumn = colOf(buffer.toString(), cursor);
            currentChange.setLength(0);
            resetCount();
            return VimAction.modeChange(VimMode.INSERT);
        }

        // Operators
        VimOperator op = VimOperator.fromChar(key);
        if (op != null) {
            pendingOperator = op;
            return VimAction.operatorPending(op);
        }

        // g-prefix initiator: wait for the follow-up key (g/j/k)
        if (key == 'g') {
            pendingGoto = true;
            return VimAction.none();
        }

        // Motions with count
        VimMotion motion = VimMotion.fromChar(key);
        if (motion != null) {
            int count = Math.max(1, pendingCount);
            // Line-target motions (G/gg) are absolute, not stepped by count.
            if (motion == VimMotion.LAST_LINE || motion == VimMotion.FIRST_LINE) {
                String text = buffer.toString();
                int targetLine = (count == 1)
                        ? (motion == VimMotion.LAST_LINE ? lineCount(text) - 1 : 0)
                        : count - 1;
                targetLine = Math.clamp(targetLine, 0, lineCount(text) - 1);
                cursor = startOfLine(text, targetLine);
                preferredColumn = 0;
                resetCount();
                return VimAction.cursorMoved(cursor);
            }
            for (int i = 0; i < count; i++) {
                executeMotion(motion);
            }
            resetCount();
            return VimAction.cursorMoved(cursor);
        }

        // Find commands
        if (key == 'f' || key == 'F' || key == 't' || key == 'T') {
            pendingFind = key;
            findForward = (key == 'f' || key == 't');
            findTill = (key == 't' || key == 'T');
// NOTE: do NOT resetCount here — a count prefix (e.g. "3fw") must
            // survive until processFindChar consumes the target char.
            return VimAction.waitingForChar();
        }

        // Repeat last find
        if (key == ';' && lastFindChar != 0) {
            int count = Math.max(1, pendingCount);
            for (int i = 0; i < count; i++) {
                repeatFind(lastFindForward, lastFindTill, lastFindChar);
            }
            resetCount();
            return VimAction.cursorMoved(cursor);
        }
        if (key == ',' && lastFindChar != 0) {
            int count = Math.max(1, pendingCount);
            for (int i = 0; i < count; i++) {
                repeatFind(!lastFindForward, lastFindTill, lastFindChar);
            }
            resetCount();
            return VimAction.cursorMoved(cursor);
        }

        // Dot repeat
        if (key == '.') {
            int count = Math.max(1, pendingCount);
            for (int i = 0; i < count; i++) {
                executeDotRepeat();
            }
            resetCount();
            return VimAction.bufferChanged();
        }

        // Yank/paste
        if (key == 'p') {
            if (!yankRegister.isEmpty()) {
                int count = Math.max(1, pendingCount);
                if (yankLinewise) {
                    pushUndoState();
                    pasteLinewise(true, count);
                } else {
                    String text = buffer.toString();
                    String content = yankRegister.repeat(count);
                    int insertPoint = (cursor < text.length()) ? nextOffset(text, cursor) : cursor;
                    pushUndoState();
                    buffer.insert(insertPoint, content);
                    cursor = Math.max(insertPoint, insertPoint + content.length() - lastGraphemeLength(content));
                    preferredColumn = colOf(buffer.toString(), cursor);
                }
            }
            resetCount();
            return VimAction.bufferChanged();
        }
        if (key == 'P') {
            if (!yankRegister.isEmpty()) {
                int count = Math.max(1, pendingCount);
                if (yankLinewise) {
                    pushUndoState();
                    pasteLinewise(false, count);
                } else {
                    String content = yankRegister.repeat(count);
                    int insertPoint = cursor;
                    pushUndoState();
                    buffer.insert(insertPoint, content);
                    cursor = Math.max(insertPoint, insertPoint + content.length() - lastGraphemeLength(content));
                    preferredColumn = colOf(buffer.toString(), cursor);
                }
            }
            resetCount();
            return VimAction.bufferChanged();
        }

        // Delete char under cursor (countable: deletes `count` graphemes,

        if (key == 'x') {
            if (!buffer.isEmpty() && cursor < buffer.length()) {
                int count = Math.max(1, pendingCount);
                String text = buffer.toString();
                int from = cursor;
                int to = from;
                for (int i = 0; i < count && to < text.length(); i++) {
                    to = nextOffset(text, to);
                }
                if (to > from) {
                    pushUndoState();
                    yankRegister = text.substring(from, to);
                    yankLinewise = false;
                    buffer.delete(from, to);
                    int maxOff = Math.max(0, buffer.length() - lastGraphemeLength(buffer.toString()));
                    cursor = Math.min(from, maxOff);
                    preferredColumn = colOf(buffer.toString(), cursor);
                }
            }
            resetCount();
            return VimAction.bufferChanged();
        }

        resetCount();
        return VimAction.none();
    }

    private VimAction processOperatorPending(char key) {
        // g-prefix continuation of an operator (dgg / dG is handled via the
        // single-char G path; here we preserve the pending operator so that
        // the follow-up g/j/k can be applied as an operator motion).
        if (key == 'g') {
            pendingGoto = true;   // keep pendingOperator for the follow-up key
            return VimAction.none();
        }

        VimOperator op = pendingOperator;
        pendingOperator = null;

        // Double operator (dd, cc, yy) = whole line(s)
        if (key == op.key()) {
            int count = Math.max(1, pendingCount);
            String text = buffer.toString();
            int line = lineNumberOf(text, cursor);
            int endLine = Math.min(lineCount(text) - 1, line + count - 1);
            int from = startOfLine(text, line);
            int to = lineEndExclOf(text, startOfLine(text, endLine));
            String content = text.substring(from, to);
            if (!Strings.CS.endsWith(content, "\n")) {
                content = content + "\n";
            }
            yankRegister = content;
            yankLinewise = true;
            pushUndoState();

            if (op == VimOperator.CHANGE) {
                // Replace the affected lines with one empty logical line,

                String before = text.substring(0, from);
                buffer = to == text.length()
                    ? new StringBuilder(before)
                    : new StringBuilder(before + "\n" + text.substring(to));
                // Insert mode permits the caret one position past the final
                // character; cc on the last line must land at that EOF offset.
                cursor = Math.min(from, buffer.length());
                preferredColumn = colOf(buffer.toString(), cursor);
                mode = VimMode.INSERT;
                resetCount();
                return VimAction.modeChange(VimMode.INSERT);
            }

            if (op == VimOperator.DELETE) {
                int deleteFrom = from;
                // If deleting to end of file and there's a preceding newline,
                // include it so the last line doesn't leave a trailing newline.
                if (to == text.length() && deleteFrom > 0 && text.charAt(deleteFrom - 1) == '\n') {
                    deleteFrom -= 1;
                }
                buffer.delete(deleteFrom, to);
                cursor = Math.min(deleteFrom, Math.max(0, buffer.length() - 1));
            } else {
                cursor = from;
            }
            preferredColumn = colOf(buffer.toString(), cursor);
            resetCount();
            return VimAction.bufferChanged();
        }

        // Count prefix after operator (d3w): keep the operator pending and
        // accumulate the count; the next key is parsed as a motion/find/text-obj.
        if (key >= '1' && key <= '9') {
            pendingOperator = op;
            pendingCount = key - '0';
            countingDigits = true;
            return VimAction.operatorPending(op);
        }

        // Text object: i/a followed by target
        VimTextObject.Scope scope = VimTextObject.Scope.fromChar(key);
        if (scope != null) {
            pendingTextObjectOp = op;        // preserve operator (was discarded before)
            pendingTextObjectScope = scope;
            return VimAction.waitingForChar();
        }

        // Find within an operator (d f x, c t ", ...): the next char is the
        // find target; the operator is applied across the found range.
        if (key == 'f' || key == 'F' || key == 't' || key == 'T') {
            pendingFind = key;
            findForward = (key == 'f' || key == 't');
            findTill = (key == 't' || key == 'T');
            pendingFindOp = op;
            return VimAction.waitingForChar();
        }

        // Motion-based operator with count
        VimMotion motion = VimMotion.fromChar(key);
        if (motion != null) {
            int count = Math.max(1, pendingCount);
            return applyOperatorMotion(op, motion, count);
        }

        resetCount();
        return VimAction.none();
    }


    private VimAction applyOperatorMotion(VimOperator op, VimMotion motion, int count) {
        String text = buffer.toString();
        int start = cursor;
        int target;
        if (motion == VimMotion.LAST_LINE || motion == VimMotion.FIRST_LINE) {
            int targetLine = (count == 1)
                    ? (motion == VimMotion.LAST_LINE ? lineCount(text) - 1 : 0)
                    : count - 1;
            targetLine = Math.clamp(targetLine, 0, lineCount(text) - 1);
            target = startOfLine(text, targetLine);
        } else {
            target = start;
            for (int i = 0; i < count; i++) {
                target = stepMotion(target, motion);
            }
        }
        cursor = target;
        preferredColumn = colOf(text, target);

        int from = Math.min(start, target);
        int to = Math.max(start, target);
        boolean linewise = false;

        if (op == VimOperator.CHANGE
                && (motion == VimMotion.WORD_FORWARD || motion == VimMotion.WORD_BIG_FORWARD)) {
// cw/cW changes to the end of the word, not the start of the next word.
            int wc = start;
            for (int i = 0; i < count - 1; i++) {
                wc = (motion == VimMotion.WORD_FORWARD)
                        ? nextWordStart(text, wc)
                        : nextBigWordStart(text, wc);
            }
            int we = (motion == VimMotion.WORD_FORWARD)
                    ? wordEnd(text, wc)
                    : bigWordEnd(text, wc);
            to = Math.min(nextOffset(text, we), text.length());
            from = start;
        } else if (isLinewiseMotion(motion)) {

            // getOperatorRange: only `to` is extended to the next newline
            // (or to EOF with the preceding newline pulled in); `from` is left
            // at the cursor column and is NOT snapped to the line start.
            linewise = true;
            to = lineEndExclOf(text, to);
            // Deleting to end of file: include the preceding newline if present.
            if (to == text.length() && from > 0 && text.charAt(from - 1) == '\n') {
                from -= 1;
            }
        } else if (isInclusiveMotion(motion) && start <= target) {
// Inclusive motions (e, E, $) extend one grapheme past the landing position.
            to = Math.min(nextOffset(text, to), text.length());
        }

        if (from < to) {
            pushUndoState();
            String deleted = text.substring(from, to);
            if (linewise && !Strings.CS.endsWith(deleted, "\n")) {
                deleted = deleted + "\n";
            }
            yankRegister = deleted;
            yankLinewise = linewise;
            if (op == VimOperator.DELETE || op == VimOperator.CHANGE) {
                buffer.delete(from, to);
                cursor = Math.min(from, Math.max(0, buffer.length() - 1));
                preferredColumn = colOf(buffer.toString(), cursor);
                if (op == VimOperator.CHANGE) {
                    mode = VimMode.INSERT;
                    return VimAction.modeChange(VimMode.INSERT);
                }
            } else {
                cursor = from;
                preferredColumn = colOf(buffer.toString(), cursor);
            }
        }
        resetCount();
        return VimAction.bufferChanged();
    }

    /**
     * Consume the key following a {@code g} prefix (gg / gj / gk), with or
     * without a pending operator (dgg, dG is the single-char G path).
     */
    private VimAction processGoto(char key) {
        pendingGoto = false;
        VimMotion motion;
        if (key == 'g') {
            motion = VimMotion.FIRST_LINE;
        } else if (key == 'j') {
            motion = VimMotion.DOWN_VISUAL;
        } else if (key == 'k') {
            motion = VimMotion.UP_VISUAL;
        } else {
            resetCount();
            return VimAction.none();
        }

        int count = Math.max(1, pendingCount);
        if (pendingOperator != null) {
            VimOperator op = pendingOperator;
            pendingOperator = null;
            return applyOperatorMotion(op, motion, count);
        }

        // Plain motion (gg / gj / gk)
        if (motion == VimMotion.FIRST_LINE) {
            String text = buffer.toString();
            int targetLine = (count == 1) ? 0 : count - 1;
            targetLine = Math.clamp(targetLine, 0, lineCount(text) - 1);
            cursor = startOfLine(text, targetLine);
            preferredColumn = 0;
            resetCount();
            return VimAction.cursorMoved(cursor);
        }
        // gj / gk: vertical move, count times
        for (int i = 0; i < count; i++) {
            executeMotion(motion);
        }
        resetCount();
        return VimAction.cursorMoved(cursor);
    }

    /**
     * Process a character for find (f/F/t/T) commands.
     */
    public VimAction processFindChar(char target) {
        if (pendingFind == 0) {
            return VimAction.none();
        }
        pendingFind = 0;

        lastFindChar = target;
        lastFindForward = findForward;
        lastFindTill = findTill;

        int count = Math.max(1, pendingCount);
        for (int i = 0; i < count; i++) {
            if (!executeFind(target, findForward, findTill)) {
                break;
            }
        }
        resetCount();
        return VimAction.cursorMoved(cursor);
    }

    /**
     * Processes an operator plus find command (for example, {@code d f x}). The
     * operator applies from the cursor through the matched character.
     */
    public VimAction processOperatorFind(VimOperator op, boolean forward, boolean till, char targetChar) {
        if (pendingFind == 0) {
            return VimAction.none();
        }
        pendingFind = 0;

        int from = cursor;
        if (!executeFind(targetChar, forward, till)) {
            return VimAction.none();
        }
        int target = cursor;

        // Record last find so ';' / ',' repeat the motion.
        lastFindChar = targetChar;
        lastFindForward = forward;
        lastFindTill = till;

        int start = Math.min(from, target);
        int end = nextOffset(buffer.toString(), Math.max(from, target)); // inclusive of target grapheme
        pushUndoState();
        yankRegister = buffer.substring(start, end);
        yankLinewise = false;
        if (op == VimOperator.DELETE || op == VimOperator.CHANGE) {
            buffer.delete(start, end);
            cursor = Math.min(start, Math.max(0, buffer.length() - 1));
            preferredColumn = colOf(buffer.toString(), cursor);
            if (op == VimOperator.CHANGE) {
                mode = VimMode.INSERT;
                return VimAction.modeChange(VimMode.INSERT);
            }
        } else {
            cursor = start;
            preferredColumn = colOf(buffer.toString(), cursor);
        }
        resetCount();
        return VimAction.bufferChanged();
    }

    private boolean executeFind(char target, boolean forward, boolean till) {
        String text = buffer.toString();
        if (forward) {
            int i = nextOffset(text, cursor);
            while (i < text.length()) {
                if (text.charAt(i) == target) {
                    cursor = till ? prevOffset(text, i) : i;
                    preferredColumn = colOf(text, cursor);
                    return true;
                }
                i = nextOffset(text, i);
            }
        } else {
            int i = prevOffset(text, cursor);
            while (i >= 0) {
                if (text.charAt(i) == target) {
                    cursor = till ? nextOffset(text, i) : i;
                    preferredColumn = colOf(text, cursor);
                    return true;
                }
                i = prevOffset(text, i);
            }
        }
        return false;
    }

    private void repeatFind(boolean forward, boolean till, char target) {
        executeFind(target, forward, till);
    }

    /**
     * Process a text object target character.
     */
    public VimAction processTextObjectTarget(VimOperator op, VimTextObject.Scope scope, char targetChar) {
        VimTextObject.Target target = VimTextObject.Target.fromChar(targetChar);
        if (target == null) {
            return VimAction.none();
        }

        int[] range = findTextObjectRange(scope, target);
        if (range == null) {
            return VimAction.none();
        }

        int start = range[0];
        int end = range[1];
        pushUndoState();
        yankRegister = buffer.substring(start, end);
        yankLinewise = false;

        if (op == VimOperator.DELETE || op == VimOperator.CHANGE) {
            buffer.delete(start, end);
            cursor = Math.min(start, Math.max(0, buffer.length() - 1));
            preferredColumn = colOf(buffer.toString(), cursor);
            resetCount();
            if (op == VimOperator.CHANGE) {
                mode = VimMode.INSERT;
                return VimAction.modeChange(VimMode.INSERT);
            }
        } else {
            cursor = start;
            preferredColumn = colOf(buffer.toString(), cursor);
            resetCount();
        }
        return VimAction.bufferChanged();
    }

    private int[] findTextObjectRange(VimTextObject.Scope scope, VimTextObject.Target target) {
        String text = buffer.toString();
        if (text.isEmpty()) return null;

        boolean around = scope == VimTextObject.Scope.AROUND;
        if (target == VimTextObject.Target.WORD) {
            return findWordRange(text, around, false);
        }
        if (target == VimTextObject.Target.BIG_WORD) {
            return findWordRange(text, around, true);
        }

        char open, close;
        switch (target) {
            case SINGLE_QUOTE -> { open = '\''; close = '\''; }
            case DOUBLE_QUOTE -> { open = '"'; close = '"'; }
            case BACKTICK -> { open = '`'; close = '`'; }
            case PAREN -> { open = '('; close = ')'; }
            case BRACKET -> { open = '['; close = ']'; }
            case BRACE -> { open = '{'; close = '}'; }
            case ANGLE -> { open = '<'; close = '>'; }
            default -> { return null; }
        }

        return findDelimiterRange(text, open, close, around);
    }

    /**
     * Find a word-like object range.
     */
    private int[] findWordRange(String text, boolean around, boolean bigWord) {
        if (cursor >= text.length()) return null;
        Predicate<Character> isWord = bigWord
                ? (ch) -> !Character.isWhitespace(ch)
                : VimStateMachine::isWordChar;

        char at = text.charAt(cursor);
        boolean ws = Character.isWhitespace(at);

        int start = cursor;
        int end = cursor;

        if (isWord.test(at)) {
            while (start > 0 && isWord.test(text.charAt(start - 1))) start--;
            while (end < text.length() && isWord.test(text.charAt(end))) end++;
        } else if (ws) {
            // Whitespace run: select the contiguous whitespace sequence.
            while (start > 0 && Character.isWhitespace(text.charAt(start - 1))) start--;
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) end++;
            return new int[]{start, end};
        } else {
            // Punctuation run: select the contiguous non-word, non-space run.
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))
                    && !isWord.test(text.charAt(start - 1))) start--;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))
                    && !isWord.test(text.charAt(end))) end++;
        }

        if (!around) {
            return new int[]{start, end};
        }
        // Around: extend to include adjacent whitespace (one side only, like vim).
        if (end < text.length() && Character.isWhitespace(text.charAt(end))) {
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) end++;
        } else if (start > 0 && Character.isWhitespace(text.charAt(start - 1))) {
            while (start > 0 && Character.isWhitespace(text.charAt(start - 1))) start--;
        }
        return new int[]{start, end};
    }

    /**
     * Find a delimiter-pair range.
     */
    private int[] findDelimiterRange(String text, char open, char close, boolean around) {
        if (open == close) {
            return findQuoteRange(text, open, around);
        }

        int depth = 0;
        int start = -1;
        for (int i = cursor; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == close && i != cursor) depth++;
            else if (ch == open) {
                if (depth == 0) {
                    start = i;
                    break;
                }
                depth--;
            }
        }
        if (start < 0) return null;

        int end = -1;
        for (int i = start + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == open) depth++;
            else if (ch == close) {
                if (depth == 0) {
                    end = i;
                    break;
                }
                depth--;
            }
        }
        if (end < 0) return null;

        if (around) {
            return new int[]{start, end + 1};
        } else {
            return new int[]{start + 1, end};
        }
    }

    /**
     * Quote-pair range: collect all quote positions on the (single-line) buffer,
     * pair them 0-1/2-3/..., and return the pair containing the cursor.
     */
    private int[] findQuoteRange(String text, char quote, boolean around) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == quote) positions.add(i);
        }
        for (int i = 0; i < positions.size() - 1; i += 2) {
            int qs = positions.get(i);
            int qe = positions.get(i + 1);
            if (qs <= cursor && cursor <= qe) {
                return around ? new int[]{qs, qe + 1} : new int[]{qs + 1, qe};
            }
        }
        return null;
    }

    private void executeMotion(VimMotion motion) {
        String text = buffer.toString();
        cursor = stepMotion(cursor, motion);
        preferredColumn = colOf(text, cursor);
    }

    /**
     * Pure single-step motion: returns the offset after applying {@code motion} starting from {@code
     * from}.
     */
    private int stepMotion(int from, VimMotion motion) {
        String text = buffer.toString();
        return switch (motion) {
            case LEFT -> Math.max(0, from - 1);
            case RIGHT -> Math.min(Math.max(0, text.length() - 1), from + 1);
            case DOWN, DOWN_VISUAL, UP, UP_VISUAL -> {
                int delta = (motion == VimMotion.DOWN || motion == VimMotion.DOWN_VISUAL) ? 1 : -1;
                int line = lineNumberOf(text, from);
                int targetLine = Math.max(0, Math.min(lineCount(text) - 1, line + delta));
                int off = offsetOfLineCol(text, targetLine, preferredColumn);
                preferredColumn = colOf(text, off);
                yield off;
            }
            case WORD_FORWARD -> nextWordStart(text, from);
            case WORD_BACK -> prevWordStart(text, from);
            case WORD_END -> wordEnd(text, from);
            case WORD_BIG_FORWARD -> nextBigWordStart(text, from);
            case WORD_BIG_BACK -> prevBigWordStart(text, from);
            case WORD_BIG_END -> bigWordEnd(text, from);
            case LINE_START -> lineStartOf(text, from);
            case FIRST_NON_BLANK -> firstNonBlankOnLine(text, from);
            case LINE_END -> {
                int e = lineEndExclOf(text, from);
                yield Math.max(lineStartOf(text, from), e - 1);
            }
            // LAST_LINE / FIRST_LINE are resolved as absolute line-targets by
            // the callers (they ignore any stepped accumulation).
            case LAST_LINE, FIRST_LINE -> from;
        };
    }

    private int firstNonBlankOnLine(String text, int off) {
        int s = lineStartOf(text, off);
        int e = lineEndExclOf(text, off);
        for (int i = s; i < e; i++) {
            if (!Character.isWhitespace(text.charAt(i))) return i;
        }
        return s;
    }

    static int nextWordStart(String text, int pos) {
        if (pos >= text.length() - 1) return Math.max(0, text.length() - 1);
        int i = pos;
        if (isWordChar(text.charAt(i))) {
            while (i < text.length() && isWordChar(text.charAt(i))) i = nextOffset(text, i);
        } else if (isPunctuation(text.charAt(i))) {
// Traverse the whole punctuation run.
            while (i < text.length() && isPunctuation(text.charAt(i))) i = nextOffset(text, i);
        }
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i = nextOffset(text, i);
        return Math.min(i, Math.max(0, text.length() - 1));
    }

    static int prevWordStart(String text, int pos) {
        if (pos <= 0) return 0;
        int i = prevOffset(text, pos);
        while (i > 0 && Character.isWhitespace(text.charAt(i))) i = prevOffset(text, i);
        if (i == 0 && Character.isWhitespace(text.charAt(0))) return 0;
        if (isWordChar(text.charAt(i))) {
            while (i > 0 && isWordChar(text.charAt(prevOffset(text, i)))) i = prevOffset(text, i);
        } else if (isPunctuation(text.charAt(i))) {
// Traverse the whole punctuation run.
            while (i > 0 && isPunctuation(text.charAt(prevOffset(text, i)))) i = prevOffset(text, i);
        }
        return i;
    }

    static int wordEnd(String text, int pos) {
        if (pos >= text.length() - 1) return Math.max(0, text.length() - 1);
        int i = nextOffset(text, pos);
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i = nextOffset(text, i);
        if (i >= text.length()) return Math.max(0, text.length() - 1);
        if (isWordChar(text.charAt(i))) {
            while (true) {
                int n = nextOffset(text, i);
                if (n >= text.length() || !isWordChar(text.charAt(n))) break;
                i = n;
            }
        } else if (isPunctuation(text.charAt(i))) {
// Traverse the whole punctuation run.
            while (true) {
                int n = nextOffset(text, i);
                if (n >= text.length() || !isPunctuation(text.charAt(n))) break;
                i = n;
            }
        }
        return i;
    }

    static int nextBigWordStart(String text, int pos) {
        if (pos >= text.length() - 1) return Math.max(0, text.length() - 1);
        int i = pos;
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i = nextOffset(text, i);
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i = nextOffset(text, i);
        return Math.min(i, Math.max(0, text.length() - 1));
    }

    static int prevBigWordStart(String text, int pos) {
        if (pos <= 0) return 0;
        int i = pos - 1;
        while (i > 0 && Character.isWhitespace(text.charAt(i))) i = prevOffset(text, i);
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i = prevOffset(text, i);
        return i;
    }

    static int bigWordEnd(String text, int pos) {
        if (pos >= text.length() - 1) return Math.max(0, text.length() - 1);
        int i = nextOffset(text, pos);
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i = nextOffset(text, i);
        while (i < text.length()) {
            int n = nextOffset(text, i);
            if (n >= text.length() || Character.isWhitespace(text.charAt(n))) break;
            i = n;
        }
        return i;
    }

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * A non-word, non-whitespace character.
     */
    static boolean isPunctuation(char c) {
        return !isWordChar(c) && !Character.isWhitespace(c);
    }

    // ---- line geometry helpers (multi-line aware) ----

    private static int lineNumberOf(String text, int off) {
        int n = 0;
        for (int i = 0; i < off && i < text.length(); i++) {
            if (text.charAt(i) == '\n') n++;
        }
        return n;
    }

    private static int lineCount(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') n++;
        }
        return n;
    }

    private static int lineStartOf(String text, int off) {
        int s = 0;
        for (int i = 0; i < off && i < text.length(); i++) {
            if (text.charAt(i) == '\n') s = i + 1;
        }
        return s;
    }

    private static int lineEndExclOf(String text, int off) {
        int nl = text.indexOf('\n', off);
        return nl == -1 ? text.length() : nl + 1;
    }

    private static int startOfLine(String text, int lineIdx) {
        int line = 0;
        int i = 0;
        while (i < text.length() && line < lineIdx) {
            if (text.charAt(i) == '\n') line++;
            i++;
        }
        return i;
    }

    private static int colOf(String text, int off) {
        return off - lineStartOf(text, off);
    }

    private static int offsetOfLineCol(String text, int lineIdx, int col) {
        int start = startOfLine(text, lineIdx);
        int end = lineEndExclOf(text, start);
        int len = end - start; // chars in the line, excluding the newline
        int c = Math.max(0, Math.min(col, Math.max(0, len - 1)));
        return start + c;
    }



    /**
     * Offset of the start of the next grapheme cluster after {@code off}.
     */
    private static int nextOffset(String text, int off) {
        if (off >= text.length()) return text.length();
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        int n = it.following(off);
        return n == BreakIterator.DONE ? text.length() : n;
    }

    /**
     * Offset of the start of the grapheme cluster before {@code off}.
     */
    private static int prevOffset(String text, int off) {
        if (off <= 0) return 0;
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        int p = it.preceding(off);
        return p == BreakIterator.DONE ? 0 : p;
    }

    /**
     * Length (in code units) of the last grapheme cluster of {@code text}.
     */
    private static int lastGraphemeLength(String text) {
        if (text.isEmpty()) return 0;
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        int end = it.last();
        int start = it.preceding(end);
        return end - start;
    }

    /**
     * Offset of the start of logical line {@code lineIndex} within a split line array.
     */
    private static int getLineStartOffset(List<String> lines, int lineIndex) {
        int off = 0;
        for (int i = 0; i < lineIndex && i < lines.size(); i++) {
            off += lines.get(i).length() + 1; // +1 for the newline
        }
        return off;
    }

    /**
     * Paste the linewise yank register as whole lines, splicing the line array.
     */
    private void pasteLinewise(boolean after, int count) {
        String content = Strings.CS.endsWith(yankRegister, "\n")
                ? yankRegister.substring(0, yankRegister.length() - 1)
                : yankRegister;
        String[] lines = buffer.toString().split("\n", -1);
        int currentLine = lineNumberOf(buffer.toString(), cursor);
        int insertLine = after ? currentLine + 1 : currentLine;
        String[] contentLines = content.split("\n", -1);
        List<String> repeated = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            repeated.addAll(Arrays.asList(contentLines));
        }
        List<String> all = new ArrayList<>(Arrays.asList(lines));
        all.addAll(Math.min(insertLine, all.size()), repeated);
        buffer = new StringBuilder(String.join("\n", all));
        cursor = getLineStartOffset(all, insertLine);
        preferredColumn = 0;
    }

    /**
     * Whether a motion is inclusive (its landing position is part of the operated range).
     */
    private static boolean isInclusiveMotion(VimMotion motion) {
        return motion == VimMotion.WORD_END
                || motion == VimMotion.WORD_BIG_END
                || motion == VimMotion.LINE_END;
    }

    /**
     * Whether a motion is linewise (the operator acts on full lines).
     */
    private static boolean isLinewiseMotion(VimMotion motion) {
        return motion == VimMotion.DOWN
                || motion == VimMotion.UP
                || motion == VimMotion.LAST_LINE
                || motion == VimMotion.FIRST_LINE;
    }

    private void executeDotRepeat() {
        if (lastChange.isEmpty()) return;
        for (char c : lastChange.toCharArray()) {
            processKey(c);
        }
    }

    private void pushUndoState() {
        undoStack.push(new UndoState(buffer.toString(), cursor));
        if (undoStack.size() > MAX_UNDO_DEPTH) {
            List<UndoState> list = new ArrayList<>(undoStack);
            undoStack.clear();
            for (int i = 1; i < list.size(); i++) {
                undoStack.push(list.get(list.size() - i));
            }
        }
        redoStack.clear();
    }

    private void pushRedoState() {
        redoStack.push(new UndoState(buffer.toString(), cursor));
    }

    private void resetCount() {
        pendingCount = 0;
        countingDigits = false;
        pendingGoto = false;
    }

    /**
     * Reset the state machine.
     */
    public void reset() {
        mode = VimMode.INSERT;
        pendingOperator = null;
        pendingFind = 0;
        pendingFindOp = null;
        pendingTextObjectOp = null;
        pendingTextObjectScope = null;
        pendingCount = 0;
        countingDigits = false;
        pendingGoto = false;
        buffer.setLength(0);
        cursor = 0;
        preferredColumn = 0;
        yankRegister = "";
        yankLinewise = false;
        lastChange = "";
    }

    /**
     * Snapshot of buffer + cursor for undo/redo.
     */
    private record UndoState(String text, int cursor) {
    }
}
