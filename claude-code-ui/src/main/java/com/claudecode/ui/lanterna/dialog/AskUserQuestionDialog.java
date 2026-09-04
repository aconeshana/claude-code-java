package com.claudecode.ui.lanterna.dialog;

import com.claudecode.tools.questions.QuestionPresenter;
import com.claudecode.ui.lanterna.dialog.question.AnswerSubmission;
import com.claudecode.ui.lanterna.dialog.question.ClarifyFeedback;
import com.claudecode.ui.lanterna.dialog.question.DesignQuestionView;
import com.claudecode.ui.lanterna.dialog.question.DisplayQuestion;
import com.claudecode.ui.lanterna.dialog.question.ListQuestionView;
import com.claudecode.ui.lanterna.dialog.question.PreviewBox;
import com.claudecode.ui.lanterna.dialog.question.QuestionOutcome;
import com.claudecode.ui.lanterna.dialog.question.QuestionSanitizer;
import com.claudecode.ui.lanterna.dialog.question.QuestionState;
import com.claudecode.ui.lanterna.dialog.question.ReviewScreen;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

/**
 * Inline dialog for the {@code AskUserQuestion} tool — 1-4 multiple-choice questions rendered as
 * one of three screens, with an answer model shared between them.
 *
 * <p>This class is the host ({@code H$c}): it owns mounting, the per-question {@link QuestionState},
 * the recorded answers, the tab index, and the routing between screens. Painting and key semantics
 * live in the {@code dialog.question} package.
 *
 * <ul>
 *   <li>Covers: {@code H$c}'s render routing ({@code D$c}) — a question index below the question
 *       count paints {@link DesignQuestionView} or {@link ListQuestionView} depending on
 *       {@code A2g}; an index equal to it paints {@link ReviewScreen}. See {@link Body}.</li>
 *   <li>Covers: {@code d2g}/{@code S4E} — an answer is recorded as the sanitized
 *       {@code displayLabel}, and the whole request auto-submits only when it holds exactly one
 *       single-select question. See {@link #recordAnswer(DisplayQuestion, String)}.</li>
 *   <li>Covers: {@code p2g} and the {@code jjE} reducer's tab cases — the tab index clamps to
 *       {@code [0, questions.size()]} (one less when the {@code Submit} tab is hidden) and never
 *       wraps. See {@link #switchTab(int)}.</li>
 *   <li>Covers: {@code jys} — {@code submit} sends every recorded answer, {@code cancel} denies.
 *       See {@link QuestionOutcome}.</li>
 *   <li>Covers: {@code onRespondToClaude} — the {@code Chat about this} row resolves the prompt
 *       into a {@code deny}-with-feedback rather than an abort, with the body built by
 *       {@link ClarifyFeedback}. See {@link #clarify()}.</li>
 *   <li>src/components/permissions/AskUserQuestionPermissionRequest/use-multiple-choice-state.ts —
 *       which question is current and how a choice advances to the next one.</li>
 *   <li>src/components/CustomSelect/select.tsx — the plain list card's single-select keyboard:
 *       Enter selects and submits; digits 1-9 address options by their visible index (a preset
 *       digit submits immediately, the Other option's own digit focuses the input — or submits it
 *       when pre-filled); typing on a preset option is a no-op.</li>
 *   <li>src/components/CustomSelect/use-multi-select-state.ts — multi-select interaction:
 *       Enter/Space toggle the focused option, digits toggle by index, a "Submit"/"Next" row
 *       records the question (also Ctrl+Enter inside the Other input).</li>
 *   <li>src/components/CustomSelect/select-input-option.tsx — Enter on an EMPTY Other input
 *       cancels the whole dialog ({@code onSubmit} → {@code onCancel} parity).</li>
 * </ul>
 *
 * <p>Not covered: the screen-reader projection ({@code hl()}), AFK timeouts ({@code C2g}), image
 * attachments ({@code R2g}), and actually launching {@code $EDITOR} on {@code ctrl+g} — the design
 * card advertises the chord, but no editor is spawned yet (TODO).
 */
public final class AskUserQuestionDialog extends Panel implements InlineOverlay {

    private static final int DEFAULT_TERMINAL_COLUMNS = 80;
    private static final int DEFAULT_TERMINAL_ROWS = 40;

    private volatile boolean active = false;
    private List<QuestionPresenter.Question> questions = List.of();
    private List<DisplayQuestion> displayQuestions = List.of();
    private List<QuestionState> states = List.of();
    private final Map<String, String> answers = new LinkedHashMap<>();
    private int current = 0;
    private int confirmFocus = ReviewScreen.SUBMIT_FOCUS;
    private boolean hideSubmitTab = false;
    private int sharedPreviewWidth = PreviewBox.MIN_WIDTH;
    private Consumer<QuestionOutcome> resultConsumer;
    private Runnable onClose;

    private IntSupplier terminalColumnsSupplier = () -> DEFAULT_TERMINAL_COLUMNS;
    private IntSupplier terminalRowsSupplier = () -> DEFAULT_TERMINAL_ROWS;
    private Supplier<String> editorNameSupplier = AskUserQuestionDialog::environmentEditorName;
    private int lastMeasuredTerminalColumns = -1;

    private final Body body = new Body();

    public AskUserQuestionDialog() {
        addComponent(body);
    }

    /** Host hook: supplies the live terminal width so wrapped rows are measured correctly. */
    public void setTerminalColumnsSupplier(IntSupplier supplier) {
        terminalColumnsSupplier = supplier != null ? supplier : () -> DEFAULT_TERMINAL_COLUMNS;
    }

    /** Host hook: supplies the live terminal height, which caps the preview box. */
    public void setTerminalRowsSupplier(IntSupplier supplier) {
        terminalRowsSupplier = supplier != null ? supplier : () -> DEFAULT_TERMINAL_ROWS;
    }

    /** Host hook: the editor name the notes footer advertises, or null when none is configured. */
    public void setEditorNameSupplier(Supplier<String> supplier) {
        editorNameSupplier =
            supplier != null ? supplier : AskUserQuestionDialog::environmentEditorName;
    }

    private int terminalColumns() {
        return Math.max(1, terminalColumnsSupplier.getAsInt());
    }

    private int terminalRows() {
        return Math.max(1, terminalRowsSupplier.getAsInt());
    }

    /**
     * {@code z1(cne())} — the display name of the configured external editor. Approximated from
     * {@code $VISUAL}/{@code $EDITOR}: the command's basename, capitalized.
     */
    private static String environmentEditorName() {
        String editor = StringUtils.firstNonBlank(System.getenv("VISUAL"), System.getenv("EDITOR"));
        if (StringUtils.isBlank(editor)) return null;
        String[] parts = editor.strip().split("[\\s/\\\\]+");
        String name = parts.length == 0 ? "" : parts[parts.length - 1];
        return name.isEmpty() ? null : StringUtils.capitalize(name.toLowerCase(Locale.ROOT));
    }

    // ── entry point (tool virtual thread) ───────────────────────────────────

    /**
     * Blocks the calling (tool) thread until the user submits, asks to clarify, or cancels.
     * Matches {@code PermissionDialog.showAndWait}'s queue pattern.
     */
    public QuestionOutcome showAndWait(
            MultiWindowTextGUI gui, List<QuestionPresenter.Question> qs, Runnable onCloseCb) {
        return showAndWait(gui, qs, onCloseCb, () -> false);
    }

    /**
     * Cancellation-aware mount matching the compatibility queue item's unmount semantics
     * when another endpoint resolves the question before the GUI turn runs.
     */
    public QuestionOutcome showAndWait(
            MultiWindowTextGUI gui, List<QuestionPresenter.Question> qs, Runnable onCloseCb,
            BooleanSupplier cancelled) {
        BlockingQueue<QuestionOutcome> queue = new ArrayBlockingQueue<>(1);
        Consumer<QuestionOutcome> complete = outcome -> {
            try {
                queue.put(outcome != null ? outcome : new QuestionOutcome.Cancelled());
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };
        gui.getGUIThread().invokeLater(() -> {
            if (cancelled != null && cancelled.getAsBoolean()) {
                complete.accept(new QuestionOutcome.Cancelled());
                if (onCloseCb != null) onCloseCb.run();
                return;
            }
            show(qs, complete, onCloseCb);
            if (cancelled != null && cancelled.getAsBoolean()) cancelPending();
        });
        try {
            return queue.take();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return new QuestionOutcome.Cancelled();
        }
    }

    /** GUI-thread entry: activate and render. */
    private void show(List<QuestionPresenter.Question> qs,
                      Consumer<QuestionOutcome> consumer,
                      Runnable onCloseCb) {
        this.questions = List.copyOf(qs);
        this.displayQuestions = QuestionSanitizer.sanitize(this.questions);
        List<QuestionState> fresh = new ArrayList<>(qs.size());
        for (int index = 0; index < qs.size(); index++) fresh.add(new QuestionState());
        this.states = fresh;
        this.answers.clear();
        this.current = 0;
        this.confirmFocus = ReviewScreen.SUBMIT_FOCUS;
        // I$c — a lone single-select question has nothing to review, so it auto-submits instead.
        this.hideSubmitTab = questions.size() == 1 && !questions.getFirst().multiSelect();
        this.sharedPreviewWidth = PreviewBox.sharedMinWidth(displayQuestions);
        this.resultConsumer = consumer;
        this.onClose = onCloseCb;
        this.active = true;
        invalidate();
    }

    // ── screen routing ──────────────────────────────────────────────────────

    private boolean onReviewScreen() {
        return current >= questions.size();
    }

    /** {@code Nys} — the design card, unless the terminal is too narrow for its preview column. */
    private boolean useDesignCard() {
        return QuestionSanitizer.isDesignVariant(displayQuestions.get(current))
            && DesignQuestionView.fitsTerminal(terminalColumns());
    }

    /** {@code p2g} — the highest reachable tab index. */
    private int maxTabIndex() {
        return hideSubmitTab ? questions.size() - 1 : questions.size();
    }

    private Set<String> answeredKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            if (StringUtils.isNotEmpty(entry.getValue())) keys.add(entry.getKey());
        }
        return keys;
    }

    private DesignQuestionView.Context designContext() {
        return new DesignQuestionView.Context(displayQuestions, current, answeredKeys(),
            hideSubmitTab, sharedPreviewWidth, terminalColumns(), terminalRows(),
            editorNameSupplier.get());
    }

    private ReviewScreen.Context reviewContext() {
        return new ReviewScreen.Context(
            displayQuestions, answers, hideSubmitTab, terminalColumns());
    }

    // ── InlineOverlay ────────────────────────────────────────────────────────

    @Override
    public boolean isActive() { return active; }

    @Override
    public void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (onReviewScreen()) reviewKey(key);
        else if (useDesignCard()) designKey(key);
        else listKey(key);
        deliver.set(false);
        invalidate();
    }

    private void designKey(KeyStroke key) {
        DisplayQuestion question = displayQuestions.get(current);
        DesignQuestionView.Action action =
            DesignQuestionView.handleKey(key, question, states.get(current));
        switch (action) {
            case DesignQuestionView.Action.Answer answer ->
                recordAnswer(question, answer.value());
            case DesignQuestionView.Action.Cancel _ -> resolve(new QuestionOutcome.Cancelled());
            case DesignQuestionView.Action.RespondToClaude _ -> clarify();
            case DesignQuestionView.Action.SwitchTab tab -> switchTab(tab.delta());
            case DesignQuestionView.Action.None _ -> { /* consumed by the card */ }
        }
    }

    private void reviewKey(KeyStroke key) {
        ReviewScreen.KeyResult result = ReviewScreen.handleKey(key, confirmFocus);
        confirmFocus = result.confirmFocus();
        switch (result.action()) {
            case ReviewScreen.Action.Submit _ -> submit();
            case ReviewScreen.Action.Cancel _ -> resolve(new QuestionOutcome.Cancelled());
            case ReviewScreen.Action.SwitchTab tab -> switchTab(tab.delta());
            case ReviewScreen.Action.None _ -> { /* consumed by the confirm select */ }
        }
    }

    // ── plain list card keys ────────────────────────────────────────────────

    private void listKey(KeyStroke key) {
        QuestionState st = states.get(current);
        QuestionPresenter.Question q = questions.get(current);
        int optionCount = q.options().size();       // focus index optionCount = Other
        // Multi-select adds a Submit/Next row after Other (197 SelectMulti parity).
        int itemCount = optionCount + (q.multiSelect() ? 2 : 1);
        switch (key.getKeyType()) {
            case ESCAPE -> resolve(new QuestionOutcome.Cancelled());
            case ARROW_UP -> st.setFocus(InlineOverlay.cycleIndex(st.focus(), -1, itemCount));
            case ARROW_DOWN -> st.setFocus(InlineOverlay.cycleIndex(st.focus(), +1, itemCount));
            case TAB -> st.setFocus(InlineOverlay.cycleIndex(
                st.focus(), key.isShiftDown() ? -1 : +1, itemCount));
            case ARROW_LEFT -> {
                if (st.focus() == optionCount) st.moveCursor(-1);
                else switchTab(-1);
            }
            case ARROW_RIGHT -> {
                if (st.focus() == optionCount) st.moveCursor(+1);
                else switchTab(+1);
            }
            case HOME -> {
                if (st.focus() >= optionCount) st.cursorToStart();
            }
            case END -> {
                if (st.focus() >= optionCount) st.cursorToEnd();
            }
            case DELETE -> {
                if (st.focus() >= optionCount) st.deleteForward();
            }
            case BACKSPACE, CHARACTER, PASTE -> handleTextOrShortcut(key, st, q, optionCount);
            case ENTER -> handleEnter(key, st, q, optionCount);
            default -> { /* swallow while active */ }
        }
    }

    private void handleTextOrShortcut(KeyStroke key, QuestionState st,
                                      QuestionPresenter.Question q, int optionCount) {
        char c = key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
            ? key.getCharacter() : '\0';
        boolean plainChar = key.getKeyType() == KeyType.CHARACTER
            && !key.isCtrlDown() && !key.isAltDown();
        if (q.multiSelect() && c == ' ' && st.focus() < optionCount) {
            st.toggle(st.focus());
        } else if (plainChar && c >= '1' && c <= '9' && st.focus() < optionCount) {
            selectByDigit(c - '1', st, q, optionCount);
        } else if (st.focus() >= optionCount) {
            // Text editing lives on the Other row only — in multi-select the Submit row keeps the
            // input's key focus (197 isInInput parity: focusedValue stays on the input while the
            // submit row is highlighted). Typing on a preset option is a no-op; there is no
            // per-preset notes editor on this card.
            if (key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown()) {
                // readline-style jumps inside the free-text buffer (TextInput parity)
                if (c == 'a' || c == 'A') st.cursorToStart();
                else if (c == 'e' || c == 'E') st.cursorToEnd();
            } else {
                st.applyEdit(key);
            }
        }
    }

    /**
     * 197 use-select-input digits branch: keys 1-9 address options by their visible index while
     * the focus is NOT inside the input. A preset digit submits (single) / toggles (multi)
     * immediately; the input option's own digit focuses it, or submits it when it already holds
     * text.
     */
    private void selectByDigit(int index, QuestionState st, QuestionPresenter.Question q,
                               int optionCount) {
        if (index < optionCount) {
            if (q.multiSelect()) {
                st.toggle(index);
            } else {
                st.setFocus(index);
                confirmCurrent(st, q);
            }
        } else if (index == optionCount) {
            st.setFocus(optionCount);
            if (!q.multiSelect() && !st.textEmpty()) {
                confirmCurrent(st, q);   // pre-filled Other submits on its digit
            }
        }
    }

    private void handleEnter(KeyStroke key, QuestionState st, QuestionPresenter.Question q,
                             int optionCount) {
        if (!q.multiSelect()) {
            confirmCurrent(st, q);
            return;
        }
        // 197 SelectMulti (use-multi-select-state handleKeyDown, verified against the released
        // bundle): with a submit button present, Enter/Space TOGGLE the focused option;
        // recording happens on the Submit row — or via Ctrl+Enter inside the Other text input.
        if (st.focus() == ListQuestionView.submitFocus(q)) {
            confirmMulti(st);
        } else if (st.focus() == optionCount) {
            if (key.isCtrlDown()) confirmMulti(st);
            // plain Enter inside the TextInput just re-affirms the auto-selection
        } else {
            st.toggle(st.focus());
        }
    }

    /**
     * Submit-row Enter (or Ctrl+Enter inside the Other input) in multi-select mode:
     * Other's selection mirrors its text live (197 updateInputValue parity), then the
     * question is recorded — unless nothing is selected at all.
     */
    private void confirmMulti(QuestionState st) {
        st.setOtherSelected(!st.textEmpty());
        if (!st.hasSelection() && !st.otherSelected()) {
            return; // nothing chosen yet — ignore
        }
        recordListAnswer(st);
    }

    /** Single-select Enter on the list card: record the focused choice, then advance. */
    private void confirmCurrent(QuestionState st, QuestionPresenter.Question q) {
        int optionCount = q.options().size();
        if (st.focus() == optionCount) {
            if (StringUtils.isBlank(st.text())) {
                // 197 select-input-option onSubmit parity: submitting an EMPTY input option
                // calls onCancel — Enter on an untouched Other cancels the whole dialog.
                resolve(new QuestionOutcome.Cancelled());
                return;
            }
            st.setOtherSelected(true);
            st.clearSelection();
        } else {
            st.setOtherSelected(false);
            st.selectOnly(st.focus());
        }
        recordListAnswer(st);
    }

    private void recordListAnswer(QuestionState st) {
        DisplayQuestion question = displayQuestions.get(current);
        recordAnswer(question, AnswerSubmission.listAnswer(question, st));
    }

    // ── answer model ────────────────────────────────────────────────────────

    /**
     * {@code d2g}/{@code S4E}: record the answer, then either submit the whole request — which only
     * happens when it holds exactly one single-select question — or move on to the next tab.
     *
     * @param rawValue the option's raw value; the design card hands back {@code value}, the list
     *                 card an already-sanitized composite, and {@code E2g} maps the former to its
     *                 {@code displayLabel}
     */
    private void recordAnswer(DisplayQuestion question, String rawValue) {
        answers.put(question.key(), QuestionSanitizer.answerValueFor(question, rawValue));
        if (hideSubmitTab) {
            submit();
            return;
        }
        current = Math.min(current + 1, maxTabIndex());
        onTabEntered();
    }

    /** {@code jjE}'s tab cases — clamped at both ends, never wrapping. */
    private void switchTab(int delta) {
        int target = Math.clamp(current + delta, 0, maxTabIndex());
        if (target == current) return;
        current = target;
        onTabEntered();
    }

    /** {@code d$c}'s question-change effect, which only the design card wires. */
    private void onTabEntered() {
        if (onReviewScreen()) {
            confirmFocus = ReviewScreen.SUBMIT_FOCUS;
        } else if (QuestionSanitizer.isDesignVariant(displayQuestions.get(current))) {
            DesignQuestionView.syncFocusToSelection(states.get(current));
        }
    }

    private void submit() {
        resolve(new QuestionOutcome.Submitted(
            AnswerSubmission.collect(displayQuestions, answers, states)));
    }

    private void clarify() {
        Map<String, String> notes = new LinkedHashMap<>();
        for (int index = 0; index < displayQuestions.size(); index++) {
            notes.put(displayQuestions.get(index).key(), states.get(index).text());
        }
        resolve(new QuestionOutcome.Clarify(
            ClarifyFeedback.build(displayQuestions, answers, notes)));
    }

    private void resolve(QuestionOutcome outcome) {
        var consumer = resultConsumer;
        active = false;
        resultConsumer = null;
        Runnable closer = onClose;
        onClose = null;
        invalidate();
        if (consumer != null) consumer.accept(outcome);
        if (closer != null) closer.run();
    }

    /** Dismisses a stale local question card after another endpoint answered. */
    public void cancelPending() {
        if (active) resolve(new QuestionOutcome.Cancelled());
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

    private final class Body extends AbstractComponent<Body> {
        @Override
        protected ComponentRenderer<Body> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override
                public TerminalSize getPreferredSize(Body c) {
                    if (!active) return TerminalSize.of(0, 0);
                    if (onReviewScreen()) {
                        return new TerminalSize(terminalColumns(),
                            ReviewScreen.preferredRows(reviewContext(), confirmFocus));
                    }
                    if (useDesignCard()) {
                        return new TerminalSize(terminalColumns(),
                            DesignQuestionView.preferredRows(
                                designContext(), states.get(current)));
                    }
                    return new TerminalSize(DEFAULT_TERMINAL_COLUMNS,
                        ListQuestionView.preferredRows(
                            questions.get(current), states.get(current), terminalColumns()));
                }

                @Override
                public void drawComponent(TextGUIGraphics g, Body c) {
                    if (!active) return;
                    if (onReviewScreen()) {
                        ReviewScreen.draw(g, reviewContext(), confirmFocus);
                    } else if (useDesignCard()) {
                        DesignQuestionView.draw(g, designContext(), states.get(current));
                    } else {
                        ListQuestionView.draw(g, questions.get(current), states.get(current),
                            current, questions.size());
                    }
                }
            };
        }
    }
}
