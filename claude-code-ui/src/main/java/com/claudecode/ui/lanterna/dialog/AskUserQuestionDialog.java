package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.questions.QuestionPresenter;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Inline dialog for the {@code AskUserQuestion} tool — 1-4 multiple-choice questions with an
 * automatic "Other" free-text option, multi-select, option previews, and free-text notes on a
 * selection.
 *
 * <ul>
 *   <li>src/components/permissions/AskUserQuestionPermissionRequest/QuestionView.tsx —
 *       question card layout and the "Other" input option. When the wrapped card exceeds
 *       the overlay height, 197/Ink shows the terminal tail; here the focused row is kept
 *       visible instead (Other focus bottom-anchors the card)</li>
 *   <li>src/components/CustomSelect/select.tsx (compact-vertical layout) — option description
 *       word-wrap; released 2.1.197 relies on Ink's default {@code wrap="wrap"}, so descriptions
 *       wrap instead of clipping in narrow terminals. Single-select keyboard: Enter selects and
 *       submits; digits 1-9 address options by their visible index (a preset digit submits
 *       immediately, the Other option's own digit focuses the input — or submits it when
 *       pre-filled); typing on a preset option is a no-op (verified against the released
 *       bundle's {@code use-select-input})</li>
 *   <li>src/components/CustomSelect/SelectMulti.tsx +
 *       src/components/CustomSelect/use-multi-select-state.ts — multi-select interaction:
 *       Enter/Space toggle the focused option, digits toggle by index (the Other option's own
 *       digit focuses its input — 197 toggles its selection instead, answer-identical), a bold
 *       "Submit"/"Next" row submits (also Ctrl+Enter inside the Other input), and the Other
 *       checkbox mirrors its text live.
 *       Verified against the released 2.1.197 bundle's {@code handleKeyDown}</li>
 *   <li>src/components/CustomSelect/select-input-option.tsx — "Other" free-text input as ONE
 *       row: dimmed index + (multi: checkbox) + typed text or dimmed placeholder
 *       ("Type something." single / "Type something" multi — 197 {@code showLabel} defaults
 *       false, so there is no "Other" label and no separate text row). Cursor-based single-line
 *       editing (insert/delete at the insertion point, arrow/Home/End and Ctrl+A/E movement,
 *       cursor-anchored scroll window, inverse-video cursor like ink-text-input's
 *       {@code showCursor}) plus bracketed-paste insertion. Enter on an EMPTY input cancels the
 *       whole dialog (197 {@code onSubmit} → {@code onCancel} parity). TODO: 197's input is
 *       {@code multiline} — ours stays single-line with a horizontal scroll window</li>
 * </ul>
 */
public final class AskUserQuestionDialog extends Panel implements InlineOverlay {

    /** Per-question mutable UI state. */
    private static final class QState {
        final Set<Integer> selected = new LinkedHashSet<>();  // option indices
        boolean otherSelected = false;
        final StringBuilder text = new StringBuilder();       // Other answer / notes
        int cursor = 0;   // insertion point within text, 0..text.length()
        int focus = 0; // 0..options.size (last = Other)
    }

    private volatile boolean active = false;
    private List<QuestionPresenter.Question> questions = List.of();
    private List<QState> states = List.of();
    private int current = 0;
    private Consumer<Map<String, QuestionPresenter.Answer>> resultConsumer;
    private Runnable onClose;

    private static final int DEFAULT_TERMINAL_COLUMNS = 80;
    private IntSupplier terminalColumnsSupplier = () -> DEFAULT_TERMINAL_COLUMNS;
    private int lastMeasuredTerminalColumns = -1;

    private final Body body = new Body();

    public AskUserQuestionDialog() {
        addComponent(body);
    }

    /** Host hook: supplies the live terminal width so wrapped rows are measured correctly. */
    public void setTerminalColumnsSupplier(IntSupplier supplier) {
        terminalColumnsSupplier = supplier != null ? supplier : () -> DEFAULT_TERMINAL_COLUMNS;
    }

    private int terminalColumns() {
        return Math.max(1, terminalColumnsSupplier.getAsInt());
    }

    // ── entry point (tool virtual thread) ───────────────────────────────────

    /**
     * Blocks the calling (tool) thread until the user answers or cancels.
     * matches {@code PermissionDialog.showAndWait}'s queue pattern.
     *
     * @return answers keyed by question text, or null on cancel
     */
    public Map<String, QuestionPresenter.Answer> showAndWait(
            MultiWindowTextGUI gui, List<QuestionPresenter.Question> qs, Runnable onCloseCb) {
        return showAndWait(gui, qs, onCloseCb, () -> false);
    }

    /**
     * Cancellation-aware mount matching the compatibility queue item's unmount semantics
     * when another endpoint resolves the question before the GUI turn runs.
     */
    public Map<String, QuestionPresenter.Answer> showAndWait(
            MultiWindowTextGUI gui, List<QuestionPresenter.Question> qs, Runnable onCloseCb,
            BooleanSupplier cancelled) {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        Consumer<Map<String, QuestionPresenter.Answer>> complete = answers -> {
            try {
                queue.put(answers != null ? answers : CANCELLED);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };
        gui.getGUIThread().invokeLater(() -> {
            if (cancelled != null && cancelled.getAsBoolean()) {
                complete.accept(null);
                if (onCloseCb != null) onCloseCb.run();
                return;
            }
            show(qs, complete, onCloseCb);
            if (cancelled != null && cancelled.getAsBoolean()) cancelPending();
        });
        try {
            Object taken = queue.take();
            @SuppressWarnings("unchecked")
            Map<String, QuestionPresenter.Answer> answers =
                taken == CANCELLED ? null : (Map<String, QuestionPresenter.Answer>) taken;
            return answers;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static final Object CANCELLED = new Object();

    /** GUI-thread entry: activate and render. */
    private void show(List<QuestionPresenter.Question> qs,
                      Consumer<Map<String, QuestionPresenter.Answer>> consumer,
                      Runnable onCloseCb) {
        this.questions = List.copyOf(qs);
        List<QState> st = new ArrayList<>(qs.size());
        for (int i = 0; i < qs.size(); i++) st.add(new QState());
        this.states = st;
        this.current = 0;
        this.resultConsumer = consumer;
        this.onClose = onCloseCb;
        this.active = true;
        invalidate();
    }

    // ── InlineOverlay ────────────────────────────────────────────────────────

    @Override
    public boolean isActive() { return active; }

    @Override
    public void handleKey(KeyStroke key, AtomicBoolean deliver) {
        QState st = states.get(current);
        QuestionPresenter.Question q = questions.get(current);
        int optionCount = q.options().size();       // focus index optionCount = Other
        // Multi-select adds a Submit/Next row after Other (197 SelectMulti parity).
        int itemCount = optionCount + (q.multiSelect() ? 2 : 1);
        switch (key.getKeyType()) {
            case ESCAPE -> resolve(null);
            case ARROW_UP -> st.focus = InlineOverlay.cycleIndex(st.focus, -1, itemCount);
            case ARROW_DOWN -> st.focus = InlineOverlay.cycleIndex(st.focus, +1, itemCount);
            case TAB -> st.focus = InlineOverlay.cycleIndex(
                st.focus, key.isShiftDown() ? -1 : +1, itemCount);
            case ARROW_LEFT -> {
                if (st.focus == optionCount) {
                    st.cursor = Math.max(0, st.cursor - 1);
                } else {
                    current = InlineOverlay.cycleIndex(current, -1, questions.size());
                }
            }
            case ARROW_RIGHT -> {
                if (st.focus == optionCount) {
                    st.cursor = Math.min(st.text.length(), st.cursor + 1);
                } else {
                    current = InlineOverlay.cycleIndex(current, +1, questions.size());
                }
            }
            case HOME -> {
                if (st.focus >= optionCount) st.cursor = 0;
            }
            case END -> {
                if (st.focus >= optionCount) st.cursor = st.text.length();
            }
            case DELETE -> {
                if (st.focus >= optionCount && st.cursor < st.text.length()) {
                    st.text.deleteCharAt(st.cursor);
                }
            }
            case BACKSPACE, CHARACTER, PASTE -> {
                char c = key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
                    ? key.getCharacter() : '\0';
                boolean plainChar = key.getKeyType() == KeyType.CHARACTER
                    && !key.isCtrlDown() && !key.isAltDown();
                if (q.multiSelect() && c == ' ' && st.focus < optionCount) {
                    if (!st.selected.remove(st.focus)) st.selected.add(st.focus);
                } else if (plainChar && c >= '1' && c <= '9' && st.focus < optionCount) {
                    // 197 use-select-input digits branch: keys 1-9 address options by their
                    // visible index while the focus is NOT inside the input. A preset digit
                    // submits (single) / toggles (multi) immediately; the input option's own
                    // digit focuses it, or submits it when it already holds text.
                    int idx = c - '1';
                    if (idx < optionCount) {
                        if (q.multiSelect()) {
                            if (!st.selected.remove(idx)) st.selected.add(idx);
                        } else {
                            st.focus = idx;
                            confirmCurrent(st, q);
                        }
                    } else if (idx == optionCount) {
                        st.focus = optionCount;
                        if (!q.multiSelect() && !st.text.isEmpty()) {
                            confirmCurrent(st, q);   // pre-filled Other submits on its digit
                        }
                    }
                } else if (st.focus >= optionCount) {
                    // Text editing lives on the Other row only — in multi-select the Submit
                    // row keeps the input's key focus (197 isInInput parity: focusedValue
                    // stays on the input while the submit row is highlighted). Typing on a
                    // preset option is a no-op; there is no per-preset notes editor.
                    if (key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown()) {
                        // readline-style jumps inside the free-text buffer (TextInput parity)
                        if (c == 'a' || c == 'A') st.cursor = 0;
                        else if (c == 'e' || c == 'E') st.cursor = st.text.length();
                    } else {
                        applyEdit(st, key);
                    }
                }
            }
            case ENTER -> {
                if (q.multiSelect()) {
                    // 197 SelectMulti (use-multi-select-state handleKeyDown, verified against
                    // the released bundle): with a submit button present, Enter/Space TOGGLE
                    // the focused option; submitting happens on the Submit row — or via
                    // Ctrl+Enter while inside the Other text input.
                    if (st.focus == submitFocus(q)) {
                        confirmMulti(st, q);
                    } else if (st.focus == optionCount) {
                        if (key.isCtrlDown()) confirmMulti(st, q);
                        // plain Enter inside the TextInput just re-affirms the auto-selection
                    } else {
                        if (!st.selected.remove(st.focus)) st.selected.add(st.focus);
                    }
                } else {
                    confirmCurrent(st, q);
                }
            }
            default -> { /* swallow while active */ }
        }
        deliver.set(false);
        invalidate();
    }

    /**
     * Cursor-aware single-line edit: printable chars, backspace, and bracketed-paste all apply
     * at the insertion point instead of only at the buffer tail (197 TextInput parity —
     * {@code cursorOffset} in the released bundle). Pasted newlines normalize to spaces, same
     * as {@code TextInputs}.
     */
    private static void applyEdit(QState st, KeyStroke key) {
        switch (key.getKeyType()) {
            case BACKSPACE -> {
                if (st.cursor > 0) {
                    st.text.deleteCharAt(st.cursor - 1);
                    st.cursor--;
                }
            }
            case PASTE -> {
                if (key instanceof PasteKeyStroke pks
                        && StringUtils.isNotEmpty(pks.getPastedText())) {
                    String normalized = pks.getPastedText()
                        .replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
                    st.text.insert(st.cursor, normalized);
                    st.cursor += normalized.length();
                }
            }
            case CHARACTER -> {
                Character ch = key.getCharacter();
                if (ch != null && ch >= 0x20 && !key.isCtrlDown() && !key.isAltDown()) {
                    st.text.insert(st.cursor, ch.charValue());
                    st.cursor++;
                }
            }
            default -> { /* non-text key */ }
        }
    }

    /**
     * Visible slice of the Other text and the cursor's char offset within that slice. The
     * window scrolls so the insertion point always stays on screen — long pasted text no
     * longer hides edits happening at the tail (the old prefix-clip made backspace look
     * dead). Widths are measured in display columns, not chars: double-width (CJK) input
     * occupies two columns per char, and slicing by char count would push the tail past
     * the right edge where {@code InlineOverlay.clip} cuts it off.
     */
    record TextWindow(int start, String visible, int cursorColumn) {}

    static TextWindow textWindow(String text, int cursor, int viewWidth) {
        int safeWidth = Math.max(1, viewWidth);
        int safeCursor = Math.max(0, Math.min(cursor, text.length()));
        // colPrefix[i] = display columns of text[0..i)
        int[] colPrefix = new int[text.length() + 1];
        for (int i = 0; i < text.length(); i++) {
            colPrefix[i + 1] = colPrefix[i]
                + (TerminalTextUtils.isCharDoubleWidth(text.charAt(i)) ? 2 : 1);
        }
        int start = 0;
        if (colPrefix[text.length()] > safeWidth) {
            int startCol = Math.min(
                Math.max(0, colPrefix[safeCursor] - safeWidth + 1),
                Math.max(0, colPrefix[text.length()] - safeWidth));
            while (start < safeCursor && colPrefix[start] < startCol) start++;
        }
        int end = start;
        while (end < text.length()
                && colPrefix[end + 1] - colPrefix[start] <= safeWidth) end++;
        return new TextWindow(start, text.substring(start, end), safeCursor - start);
    }

    /** Focus index of the Submit/Next row (multi-select only). */
    private static int submitFocus(QuestionPresenter.Question q) {
        return q.options().size() + 1;
    }

    /**
     * Submit-row Enter (or Ctrl+Enter inside the Other input) in multi-select mode:
     * Other's selection mirrors its text live (197 updateInputValue parity), then the
     * whole question submits — unless nothing is selected at all.
     */
    private void confirmMulti(QState st, QuestionPresenter.Question q) {
        st.otherSelected = !st.text.isEmpty();
        if (st.selected.isEmpty() && !st.otherSelected) {
            return; // nothing chosen yet — ignore
        }
        if (current < questions.size() - 1) {
            current++;
        } else {
            resolve(collectAnswers());
        }
    }

    /** Single-select Enter: record the focused choice, advance or submit. */
    private void confirmCurrent(QState st, QuestionPresenter.Question q) {
        int optionCount = q.options().size();
        if (st.focus == optionCount) {
            if (st.text.toString().isBlank()) {
                // 197 select-input-option onSubmit parity: submitting an EMPTY input option
                // calls onCancel — Enter on an untouched Other cancels the whole dialog.
                resolve(null);
                return;
            }
            st.otherSelected = true;
            st.selected.clear();
        } else {
            st.otherSelected = false;
            st.selected.clear();
            st.selected.add(st.focus);
        }
        if (current < questions.size() - 1) {
            current++;
        } else {
            resolve(collectAnswers());
        }
    }

    private Map<String, QuestionPresenter.Answer> collectAnswers() {
        Map<String, QuestionPresenter.Answer> out = new LinkedHashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            QuestionPresenter.Question q = questions.get(i);
            QState st = states.get(i);
            String text = st.text.toString().strip();
            List<String> labels = new ArrayList<>();
            String preview = null;
            for (Integer idx : st.selected) {
                QuestionPresenter.Option opt = q.options().get(idx);
                labels.add(opt.label());
                if (preview == null && opt.preview() != null) preview = opt.preview();
            }
            String answer;
            String notes = null;
            if (st.otherSelected && labels.isEmpty()) {
                answer = text;                       // Other IS the answer
            } else {
                answer = String.join(", ", labels);
                if (st.otherSelected) answer = answer + ", " + text;
                else if (!text.isEmpty()) notes = text;  // text becomes notes
            }
            out.put(q.question(), new QuestionPresenter.Answer(answer, preview, notes));
        }
        return out;
    }

    private void resolve(Map<String, QuestionPresenter.Answer> answers) {
        var consumer = resultConsumer;
        active = false;
        resultConsumer = null;
        Runnable closer = onClose;
        onClose = null;
        invalidate();
        if (consumer != null) consumer.accept(answers);
        if (closer != null) closer.run();
    }

    /** Dismisses a stale local question card after another endpoint answered. */
    public void cancelPending() {
        if (active) resolve(null);
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return TerminalSize.of(0, 0);
        int columns = terminalColumns();
        if (columns != lastMeasuredTerminalColumns) {
            lastMeasuredTerminalColumns = columns;
            body.invalidate();
        }
        return super.calculatePreferredSize();
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private static final int MAX_PREVIEW_LINES = 8;

    /**
     * Word-wraps an option description to {@code width} columns: soft wrap at word boundaries
     * first, then a hard wrap as the fallback for overlong words — the same two-level scheme as
     * {@code MessageSelectorDialog}. Released 2.1.197 relies on Ink's default {@code wrap="wrap"}
     * for these descriptions instead of clipping them.
     */
    static List<String> descriptionLines(String description, int width) {
        int safeWidth = Math.max(1, width);
        List<String> out = new ArrayList<>();
        for (String soft : DialogText.wrapWords(description, safeWidth)) {
            List<String> hard = FormatUtils.wrapText(soft, safeWidth);
            if (hard.isEmpty()) out.add("");
            else out.addAll(hard);
        }
        if (out.isEmpty()) out.add("");
        return List.copyOf(out);
    }


    static String multiSelectMarker(boolean selected) {
        return selected ? "[✓]" : "[ ]";
    }


    static String optionIndex(int zeroBasedIndex, int optionCount) {
        int digits = Integer.toString(Math.max(1, optionCount)).length();
        String n = Integer.toString(zeroBasedIndex + 1);
        return " ".repeat(Math.max(0, digits - n.length())) + n + ". ";
    }

    private final class Body extends AbstractComponent<Body> {
        @Override
        protected ComponentRenderer<Body> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override
                public TerminalSize getPreferredSize(Body c) {
                    if (!active) return TerminalSize.of(0, 0);
                    QuestionPresenter.Question q = questions.get(current);
                    int previewLines = 0;
                    String pv = focusedPreview(q);
                    if (pv != null) {
                        previewLines = Math.min(MAX_PREVIEW_LINES,
                            (int) pv.lines().count()) + 1;
                    }
                    int columns = terminalColumns();
                    int optionRows = 0;
                    for (QuestionPresenter.Option opt : q.options()) {
                        optionRows += 1 + descriptionLines(opt.description(), columns - 3).size();
                    }
                    // header + question + option rows + Other + Submit row
                    // (multi-select only) + hint + preview
                    int rows = 2 + optionRows + 1 + (q.multiSelect() ? 1 : 0) + 1
                        + previewLines;
                    return new TerminalSize(DEFAULT_TERMINAL_COLUMNS, rows);
                }

                @Override
                public void drawComponent(TextGUIGraphics g, Body c) {
                    if (!active) return;
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.fill(' ');
                    QuestionPresenter.Question q = questions.get(current);
                    QState st = states.get(current);
                    int width = g.getSize().getColumns();
                    int height = g.getSize().getRows();

                    // Vertical overflow: SmartLayout clamps the overlay height, so a card
                    // with many wrapped descriptions can exceed the assigned rows. Ink
                    // shows the terminal tail in that case; we additionally anchor on the
                    // focused row — options keep their label+description block visible,
                    // and focusing Other bottom-anchors so Other/text/hint never get cut.
                    int optionCount = q.options().size();
                    List<List<String>> optionDesc = new ArrayList<>(optionCount);
                    int totalRows = 2;   // header + question
                    for (QuestionPresenter.Option opt : q.options()) {
                        List<String> desc = descriptionLines(opt.description(), width - 3);
                        optionDesc.add(desc);
                        totalRows += 1 + desc.size();
                    }
                    int focusRowY = totalRows;   // Other input row (focus == optionCount)
                    if (st.focus < optionCount) {
                        focusRowY = 2;
                        for (int i = 0; i < st.focus; i++) {
                            focusRowY += 1 + optionDesc.get(i).size();
                        }
                    }
                    totalRows += 2 + (q.multiSelect() ? 1 : 0);   // Other + Submit? + hint
                    String pv = focusedPreview(q);
                    if (pv != null) {
                        totalRows += Math.min(MAX_PREVIEW_LINES,
                            (int) pv.lines().count()) + 1;
                    }
                    int maxOffset = Math.max(0, totalRows - height);
                    int yOffset;
                    if (st.focus >= optionCount) {
                        yOffset = maxOffset;
                    } else {
                        int focusEndY = focusRowY + optionDesc.get(st.focus).size();
                        yOffset = Math.min(Math.max(0, focusEndY - height + 1), maxOffset);
                    }
                    int y = -yOffset;

                    // "[header]  Question i/n" line
                    String nav = questions.size() > 1
                        ? "  (" + (current + 1) + "/" + questions.size() + " — ←/→ to switch)"
                        : "";
                    g.setForegroundColor(LanternaTheme.planTeal());
                    putRow(g, 1, y, "[" + q.header() + "]" + nav);
                    y++;
                    g.setForegroundColor(LanternaTheme.inputText());
                    putRow(g, 1, y, InlineOverlay.clip(q.question(), width - 2));
                    y++;

                    int renderedOptionCount = optionCount + 1;
                    for (int i = 0; i < optionCount; i++) {
                        QuestionPresenter.Option opt = q.options().get(i);
                        boolean focused = st.focus == i;
                        boolean chosen = st.selected.contains(i);
                        String pointer = focused ? "❯ " : "  ";
                        String index = optionIndex(i, renderedOptionCount);
                        int x = 1;

                        g.setForegroundColor(focused
                            ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                        putRow(g, x, y, pointer);
                        x += pointer.length();

                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        putRow(g, x, y, index);
                        x += index.length();

                        if (q.multiSelect()) {
                            String marker = multiSelectMarker(chosen) + " ";
                            g.setForegroundColor(chosen
                                ? LanternaTheme.toolSuccess() : LanternaTheme.inputText());
                            putRow(g, x, y, marker);
                            x += marker.length();
                        }

                        g.setForegroundColor(chosen
                            ? LanternaTheme.toolSuccess()
                            : focused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                        putRow(g, x, y, InlineOverlay.clip(opt.label(), Math.max(0, width - x - 1)));
                        if (!q.multiSelect() && chosen && x + opt.label().length() + 2 < width) {
                            g.setForegroundColor(LanternaTheme.toolSuccess());
                            putRow(g, x + opt.label().length(), y, " ✓");
                        }
                        y++;
                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        for (String descLine : optionDesc.get(i)) {
                            putRow(g, 1, y, InlineOverlay.clip("  " + descLine, width - 2));
                            y++;
                        }
                    }
                    boolean otherFocused = st.focus == optionCount;
                    // 197 updateInputValue parity: in multi-select the Other checkbox
                    // mirrors its text live — typing checks it, clearing unchecks it.
                    boolean otherChosen = q.multiSelect()
                        ? !st.text.isEmpty() : st.otherSelected;
                    String otherPointer = otherFocused ? "❯ " : "  ";
                    String otherIndex = optionIndex(optionCount, renderedOptionCount);
                    int otherX = 1;
                    g.setForegroundColor(otherFocused
                        ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                    putRow(g, otherX, y, otherPointer);
                    otherX += otherPointer.length();
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    putRow(g, otherX, y, otherIndex);
                    otherX += otherIndex.length();
                    if (q.multiSelect()) {
                        String marker = multiSelectMarker(otherChosen) + " ";
                        g.setForegroundColor(otherChosen
                            ? LanternaTheme.toolSuccess() : LanternaTheme.inputText());
                        putRow(g, otherX, y, marker);
                        otherX += marker.length();
                    }
                    // 197 select-input-option (showLabel=false): the row IS the input — no
                    // "Other" label, no separate text row. Unfocused it shows the typed text
                    // or a dimmed placeholder ("Type something." single / "Type something"
                    // multi, QuestionView.tsx parity); focused it shows the editable window
                    // with an inverse-video cursor (ink-text-input showCursor parity — an
                    // inserted glyph both shifts the tail and, being East Asian ambiguous
                    // width, renders as a phantom double-width space in CJK terminals).
                    int textStart = otherX;
                    int viewWidth = Math.max(1, width - textStart - 1);
                    TextWindow window = textWindow(st.text.toString(), st.cursor, viewWidth);
                    String textLine = window.visible();
                    boolean rowVisible = y >= 0 && y < height;
                    if (textLine.isEmpty()) {
                        String placeholder = q.multiSelect()
                            ? "Type something" : "Type something.";
                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        putRow(g, textStart, y, InlineOverlay.clip(placeholder, viewWidth));
                        if (otherFocused && rowVisible) {
                            g.enableModifiers(SGR.REVERSE);
                            g.putString(textStart, y, placeholder.substring(0, 1));
                            g.disableModifiers(SGR.REVERSE);
                        }
                    } else {
                        g.setForegroundColor(LanternaTheme.inputText());
                        putRow(g, textStart, y, InlineOverlay.clip(textLine, viewWidth));
                        if (otherFocused && rowVisible) {
                            int cc = Math.min(window.cursorColumn(), textLine.length());
                            int cursorCell = textStart
                                + TerminalTextUtils.getColumnWidth(textLine.substring(0, cc));
                            g.enableModifiers(SGR.REVERSE);
                            g.putString(cursorCell, y,
                                cc < textLine.length() ? String.valueOf(textLine.charAt(cc)) : " ");
                            g.disableModifiers(SGR.REVERSE);
                        }
                    }
                    y++;
                    if (q.multiSelect()) {
                        // 197 SelectMulti submit row: pointer + bold label; "Submit" on the
                        // last question, "Next" otherwise. Enter here (not on an option)
                        // submits the toggled set.
                        boolean submitFocused = st.focus == submitFocus(q);
                        String submitLabel = current == questions.size() - 1 ? "Submit" : "Next";
                        g.setForegroundColor(submitFocused
                            ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                        g.enableModifiers(SGR.BOLD);
                        putRow(g, 1, y, (submitFocused ? "❯    " : "     ") + submitLabel);
                        g.disableModifiers(SGR.BOLD);
                        y++;
                    }
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    String hint = q.multiSelect()
                        ? "enter to toggle · tab to submit · esc to cancel"
                        : "enter to select · esc to cancel";
                    putRow(g, 1, y, InlineOverlay.clip(hint, width - 2));
                    y++;

                    if (pv != null) {
                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        putRow(g, 1, y, "── preview ──");
                        y++;
                        int shown = 0;
                        for (String line : pv.split("\n", -1)) {
                            if (shown++ >= MAX_PREVIEW_LINES) break;
                            putRow(g, 1, y, InlineOverlay.clip(line, width - 2));
                            y++;
                        }
                    }
                }
            };
        }

        /** Draws a row only when it falls inside the assigned height (see yOffset). */
        private static void putRow(TextGUIGraphics g, int x, int y, String s) {
            if (y >= 0 && y < g.getSize().getRows()) {
                g.putString(x, y, s);
            }
        }

        private String focusedPreview(QuestionPresenter.Question q) {
            QState st = states.get(current);
            if (st.focus >= q.options().size()) return null;
            String pv = q.options().get(st.focus).preview();
            return StringUtils.isNotBlank(pv) ? pv : null;
        }
    }
}
