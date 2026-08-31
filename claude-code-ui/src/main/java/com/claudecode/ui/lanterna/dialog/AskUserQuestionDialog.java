package com.claudecode.ui.lanterna.dialog;

import com.claudecode.tools.questions.QuestionPresenter;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;

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

import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Inline dialog for the {@code AskUserQuestion} tool — 1-4 multiple-choice questions with an
 * automatic "Other" free-text option, multi-select, option previews, and free-text notes on a
 * selection.
 */
public final class AskUserQuestionDialog extends Panel implements InlineOverlay {

    /** Per-question mutable UI state. */
    private static final class QState {
        final Set<Integer> selected = new LinkedHashSet<>();  // option indices
        boolean otherSelected = false;
        StringBuilder text = new StringBuilder();             // Other answer / notes
        int focus = 0; // 0..options.size (last = Other)
    }

    private volatile boolean active = false;
    private List<QuestionPresenter.Question> questions = List.of();
    private List<QState> states = List.of();
    private int current = 0;
    private Consumer<Map<String, QuestionPresenter.Answer>> resultConsumer;
    private Runnable onClose;

    private final Body body = new Body();

    public AskUserQuestionDialog() {
        addComponent(body);
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
        switch (key.getKeyType()) {
            case ESCAPE -> resolve(null);
            case ARROW_UP -> st.focus = InlineOverlay.cycleIndex(st.focus, -1, optionCount + 1);
            case ARROW_DOWN -> st.focus = InlineOverlay.cycleIndex(st.focus, +1, optionCount + 1);
            case ARROW_LEFT -> current = InlineOverlay.cycleIndex(current, -1, questions.size());
            case ARROW_RIGHT -> current = InlineOverlay.cycleIndex(current, +1, questions.size());
            case BACKSPACE -> {
                if (!st.text.isEmpty()) st.text.deleteCharAt(st.text.length() - 1);
            }
            case ENTER -> confirmCurrent(st, q);
            case CHARACTER -> {
                char c = key.getCharacter() != null ? key.getCharacter() : '\0';
                boolean multiToggle = q.multiSelect() && c == ' ' && st.focus < optionCount;
                if (multiToggle) {
                    if (!st.selected.remove(st.focus)) st.selected.add(st.focus);
                } else if (c >= 0x20) {
                    st.text.append(c);
                }
            }
            default -> { /* swallow while active */ }
        }
        deliver.set(false);
        invalidate();
    }

    /** Enter: record the focused choice, advance or submit. */
    private void confirmCurrent(QState st, QuestionPresenter.Question q) {
        int optionCount = q.options().size();
        if (q.multiSelect()) {
            // Enter submits the toggled set; focusing Other with text adds it.
            st.otherSelected = st.focus == optionCount && !st.text.isEmpty();
            if (st.selected.isEmpty() && !st.otherSelected) {
                return; // nothing chosen yet — ignore
            }
        } else {
            if (st.focus == optionCount) {
                if (st.text.isEmpty()) return; // Other needs text
                st.otherSelected = true;
                st.selected.clear();
            } else {
                st.otherSelected = false;
                st.selected.clear();
                st.selected.add(st.focus);
            }
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
        return super.calculatePreferredSize();
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private static final int MAX_PREVIEW_LINES = 8;


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
                    // header + question + options(2 rows each) + Other + text + hint + preview
                    int rows = 2 + q.options().size() * 2 + 1 + 1 + 1 + previewLines;
                    return new TerminalSize(80, rows);
                }

                @Override
                public void drawComponent(TextGUIGraphics g, Body c) {
                    if (!active) return;
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.fill(' ');
                    QuestionPresenter.Question q = questions.get(current);
                    QState st = states.get(current);
                    int width = g.getSize().getColumns();
                    int y = 0;

                    // "[header]  Question i/n" line
                    String nav = questions.size() > 1
                        ? "  (" + (current + 1) + "/" + questions.size() + " — ←/→ to switch)"
                        : "";
                    g.setForegroundColor(LanternaTheme.planTeal());
                    g.putString(1, y, "[" + q.header() + "]" + nav);
                    y++;
                    g.setForegroundColor(LanternaTheme.inputText());
                    g.putString(1, y, InlineOverlay.clip(q.question(), width - 2));
                    y++;

                    int optionCount = q.options().size();
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
                        g.putString(x, y, pointer);
                        x += pointer.length();

                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        g.putString(x, y, index);
                        x += index.length();

                        if (q.multiSelect()) {
                            String marker = multiSelectMarker(chosen) + " ";
                            g.setForegroundColor(chosen
                                ? LanternaTheme.toolSuccess() : LanternaTheme.inputText());
                            g.putString(x, y, marker);
                            x += marker.length();
                        }

                        g.setForegroundColor(chosen
                            ? LanternaTheme.toolSuccess()
                            : focused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                        g.putString(x, y, InlineOverlay.clip(opt.label(), Math.max(0, width - x - 1)));
                        if (!q.multiSelect() && chosen && x + opt.label().length() + 2 < width) {
                            g.setForegroundColor(LanternaTheme.toolSuccess());
                            g.putString(x + opt.label().length(), y, " ✓");
                        }
                        y++;
                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        g.putString(1, y, InlineOverlay.clip("  " + opt.description(), width - 2));
                        y++;
                    }
                    boolean otherFocused = st.focus == optionCount;
                    String otherPointer = otherFocused ? "❯ " : "  ";
                    String otherIndex = optionIndex(optionCount, renderedOptionCount);
                    int otherX = 1;
                    g.setForegroundColor(otherFocused
                        ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                    g.putString(otherX, y, otherPointer);
                    otherX += otherPointer.length();
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(otherX, y, otherIndex);
                    otherX += otherIndex.length();
                    if (q.multiSelect()) {
                        String marker = multiSelectMarker(st.otherSelected) + " ";
                        g.setForegroundColor(st.otherSelected
                            ? LanternaTheme.toolSuccess() : LanternaTheme.inputText());
                        g.putString(otherX, y, marker);
                        otherX += marker.length();
                    }
                    g.setForegroundColor(st.otherSelected
                        ? LanternaTheme.toolSuccess()
                        : otherFocused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                    g.putString(otherX, y, "Other");
                    y++;
                    g.setForegroundColor(LanternaTheme.inputText());
                    g.putString(1, y, InlineOverlay.clip("       " + st.text
                        + (otherFocused ? "▏" : ""), width - 2));
                    y++;
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    String hint = q.multiSelect()
                        ? "space to toggle · enter to confirm · esc to cancel"
                        : "enter to select · esc to cancel";
                    g.putString(1, y, InlineOverlay.clip(hint, width - 2));
                    y++;

                    String pv = focusedPreview(q);
                    if (pv != null) {
                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        g.putString(1, y, "── preview ──");
                        y++;
                        int shown = 0;
                        for (String line : pv.split("\n", -1)) {
                            if (shown++ >= MAX_PREVIEW_LINES) break;
                            g.putString(1, y, InlineOverlay.clip(line, width - 2));
                            y++;
                        }
                    }
                }
            };
        }

        private String focusedPreview(QuestionPresenter.Question q) {
            QState st = states.get(current);
            if (st.focus >= q.options().size()) return null;
            String pv = q.options().get(st.focus).preview();
            return StringUtils.isNotBlank(pv) ? pv : null;
        }
    }
}
