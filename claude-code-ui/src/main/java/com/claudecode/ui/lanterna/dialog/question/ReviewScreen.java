package com.claudecode.ui.lanterna.dialog.question;

import com.claudecode.core.constants.Figures;
import com.claudecode.ui.lanterna.components.TableBorders;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel.Segment;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * The tab past the last question: every answer so far, and a confirm widget that submits them.
 *
 * <p>Authority is the {@code 2.1.236} bundle, where this screen is {@code Fys}. The host reaches it
 * when the tab index equals the question count, and only when the request has a {@code Submit} tab
 * at all.
 *
 * <ul>
 *   <li>Covers: {@code Fys}'s layout — the rule, the tab strip, the bold title, the
 *       unanswered-questions status row, the answer list, the closing prompt, and the confirm rows.
 *       See {@link #render(Context, int)}.</li>
 *   <li>Covers: {@code VP} and {@code yi}/{@code _ro} — the two-column status row and the
 *       {@code warning} icon and colour it selects. See {@link #INCOMPLETE_NOTICE}.</li>
 *   <li>Covers: {@code NE} — the two-column bullet cell each answered question hangs from, and
 *       {@code wm}, the dim left rule a gutter-flagged question text is wrapped in.</li>
 *   <li>Covers: {@code Xl} together with the select it builds ({@code Tn} / {@code KRl} /
 *       {@code xRl}): two options in {@code confirm}-first order, focus wrapping at both ends,
 *       {@code enter} to accept, {@code escape} to cancel, and digits that submit outright rather
 *       than only moving the focus. See {@link #handleKey(KeyStroke, int)}.</li>
 *   <li>Covers: {@code l2g} — a request counts as fully answered only when every question has a
 *       non-blank answer. See {@link Context#allQuestionsAnswered()}.</li>
 * </ul>
 *
 * <p>Two behaviours differ from {@link DesignQuestionView} and are the bundle's, not this port's.
 * The confirm select <em>wraps</em> where the design card clamps, because {@code Xl} passes neither
 * {@code onUpFromFirstItem} nor {@code onDownFromLastItem}. And {@code Tab} does nothing here:
 * {@code xRl} intercepts it for its input-mode toggle and calls {@code preventDefault} even when no
 * toggle is wired, so the host's {@code Tabs} context never sees it — the tab strip's own
 * {@code ←}/{@code →} remain the way back to a question.
 *
 * <p>Not covered: {@code W3}, the permission decision reason {@code Fys} renders above the closing
 * prompt. It only draws when the permission result carries a {@code decisionReason} or a matched
 * ask rule, which an {@code AskUserQuestion} request never does, and this port has no counterpart
 * for it.
 *
 * <p>The screen is stateless. Its only mutable value is which confirm row holds the focus, which
 * the host owns and passes in.
 */
public final class ReviewScreen {

    /** {@code qFe}'s title, painted bold in the text colour. */
    public static final String TITLE = "Review your answers";

    /** The {@code VP} status row shown while some question is still unanswered. */
    public static final String INCOMPLETE_NOTICE = "You have not answered all questions";

    /** The line immediately above the confirm rows. */
    public static final String READY_PROMPT = "Ready to submit your answers?";

    /** {@code Xl}'s {@code confirmLabel}. */
    public static final String SUBMIT_LABEL = "Submit answers";

    /** {@code Xl}'s {@code cancelLabel}. */
    public static final String CANCEL_LABEL = "Cancel";

    /** {@code Fys}'s stand-in when a question sanitizes down to nothing. */
    static final String MISSING_QUESTION_TEXT = "Question";

    /** The focus index of the {@code Submit answers} row. */
    public static final int SUBMIT_FOCUS = 0;

    /** The focus index of the {@code Cancel} row. */
    public static final int CANCEL_FOCUS = 1;

    private static final int CONFIRM_OPTIONS = 2;

    /** {@code NE}'s bullet cell and {@code VP}'s icon cell are both two columns wide. */
    private static final int ICON_CELL_WIDTH = 2;

    private ReviewScreen() {}

    /**
     * Everything the screen paints from.
     *
     * @param questions     every question in the request, in order
     * @param answers       the answers recorded so far, keyed by {@link DisplayQuestion#key()}
     * @param hideSubmitTab whether the tab strip omits its {@code Submit} chip; the host only
     *                      routes here when it does not
     * @param columns       the terminal width
     */
    public record Context(
        List<DisplayQuestion> questions,
        Map<String, String> answers,
        boolean hideSubmitTab,
        int columns) {

        public Context {
            questions = List.copyOf(questions);
            answers = Map.copyOf(answers);
        }

        /** {@code l2g} — every question carries a key and a non-blank answer. */
        public boolean allQuestionsAnswered() {
            return questions.stream().allMatch(question -> answerOf(question) != null);
        }

        /** {@code Fys}'s filter: the questions that have something to show, in order. */
        Map<DisplayQuestion, String> answered() {
            Map<DisplayQuestion, String> shown = new LinkedHashMap<>();
            for (DisplayQuestion question : questions) {
                String answer = answerOf(question);
                if (answer != null) shown.put(question, answer);
            }
            return shown;
        }

        /** The keys the tab strip ticks — {@code answers[key]} truthy, so blanks do not count. */
        Set<String> answeredKeys() {
            Set<String> keys = new LinkedHashSet<>();
            for (DisplayQuestion question : questions) {
                if (answerOf(question) != null) keys.add(question.key());
            }
            return keys;
        }

        private String answerOf(DisplayQuestion question) {
            if (StringUtils.isEmpty(question.key())) return null;
            String answer = answers.get(question.key());
            return StringUtils.isEmpty(answer) ? null : answer;
        }
    }

    /** What a key press asks the host to do. */
    public sealed interface Action {

        /** The key was consumed without changing the dialog's flow. */
        record None() implements Action {}

        /** {@code onFinalResponse("submit")} — send every recorded answer. */
        record Submit() implements Action {}

        /** {@code onFinalResponse("cancel")} — dismiss the whole dialog. */
        record Cancel() implements Action {}

        /** {@code tabs:previous} — the host clamps the resulting index. */
        record SwitchTab(int delta) implements Action {}
    }

    /**
     * The outcome of one key press.
     *
     * @param confirmFocus which confirm row holds the focus afterwards
     * @param action       what the host still has to do
     */
    public record KeyResult(int confirmFocus, Action action) {}

    // ── rendering ───────────────────────────────────────────────────────────

    /** Lays the whole screen out as styled lines, top to bottom. */
    public static List<List<Segment>> render(Context context, int confirmFocus) {
        List<List<Segment>> lines = new ArrayList<>();
        lines.add(rule(context.columns()));
        lines.add(QuestionTabStrip.render(context.questions(), context.questions().size(),
            context.answeredKeys(), context.hideSubmitTab(), context.columns()));
        lines.add(List.of(new Segment(TITLE, LanternaTheme.inputText(), null, null,
            Set.of(SGR.BOLD))));
        lines.add(List.of());

        if (!context.allQuestionsAnswered()) {
            lines.add(statusRow(Figures.WARNING, INCOMPLETE_NOTICE, LanternaTheme.toolWarning()));
            lines.add(List.of());
        }

        Map<DisplayQuestion, String> answered = context.answered();
        if (!answered.isEmpty()) {
            for (Map.Entry<DisplayQuestion, String> entry : answered.entrySet()) {
                lines.addAll(questionLines(entry.getKey(), context.columns()));
                lines.add(answerRow(entry.getValue()));
            }
            lines.add(List.of());
        }

        lines.add(SegmentLines.plain(READY_PROMPT, inactive()));
        lines.add(List.of());
        lines.add(confirmRow(SUBMIT_LABEL, SUBMIT_FOCUS, confirmFocus == SUBMIT_FOCUS));
        lines.add(confirmRow(CANCEL_LABEL, CANCEL_FOCUS, confirmFocus == CANCEL_FOCUS));
        return List.copyOf(lines);
    }

    /** Paints {@link #render} into a component's graphics. */
    public static void draw(TextGUIGraphics graphics, Context context, int confirmFocus) {
        SegmentPainter.paint(graphics, render(context, confirmFocus));
    }

    /** How many rows {@link #render} produces at this size. */
    public static int preferredRows(Context context, int confirmFocus) {
        return render(context, confirmFocus).size();
    }

    // ── rows ────────────────────────────────────────────────────────────────

    /** {@code K_} with no title — one full-width rule in the inactive colour. */
    private static List<Segment> rule(int columns) {
        return SegmentLines.plain(
            String.valueOf(TableBorders.HORIZONTAL).repeat(Math.max(0, columns)), inactive());
    }

    /** {@code VP} — a two-column icon cell then the message, both in the status colour. */
    private static List<Segment> statusRow(String icon, String message, TextColor color) {
        return List.of(new Segment(pad(icon), color), new Segment(message, color));
    }

    /**
     * {@code NE} inside {@code wm}: the question hangs off a two-column bullet cell, indented one
     * column, and gains a dim left rule when its text claims a multi-line slot.
     */
    private static List<List<Segment>> questionLines(DisplayQuestion question, int columns) {
        DisplayQuestion.DisplayText title = question.displayQuestion();
        String text = StringUtils.defaultIfEmpty(title.text(), MISSING_QUESTION_TEXT);
        String indent = " ";
        String gutter = title.needsGutter() ? TableBorders.VERTICAL + " " : "";
        int available =
            Math.max(1, columns - indent.length() - gutter.length() - ICON_CELL_WIDTH);

        List<List<Segment>> lines = new ArrayList<>();
        List<List<Segment>> wrapped =
            SegmentLines.wrap(SegmentLines.plain(text, LanternaTheme.inputText()), available);
        for (int index = 0; index < wrapped.size(); index++) {
            List<Segment> row = new ArrayList<>(wrapped.get(index).size() + 2);
            row.add(new Segment(indent, LanternaTheme.inputText()));
            if (!gutter.isEmpty()) row.add(new Segment(gutter, inactive()));
            row.add(new Segment(index == 0 ? pad(Figures.BULLET) : pad(""),
                LanternaTheme.inputText()));
            row.addAll(wrapped.get(index));
            lines.add(List.copyOf(row));
        }
        return lines;
    }

    /** The answer sits two further columns in, arrow and all, in the success colour. */
    private static List<Segment> answerRow(String answer) {
        return SegmentLines.plain(
            "   " + Figures.ARROW_RIGHT + " " + answer, LanternaTheme.toolSuccess());
    }

    /** One numbered {@code Tn} option row: the pointer, a dim ordinal, then the label. */
    private static List<Segment> confirmRow(String label, int index, boolean focused) {
        return List.of(
            focused
                ? new Segment(Figures.POINTER, LanternaTheme.suggestion())
                : new Segment(" ", LanternaTheme.inputText()),
            new Segment(" " + (index + 1) + ".", inactive()),
            new Segment(" " + label,
                focused ? LanternaTheme.suggestion() : LanternaTheme.inputText()));
    }

    /** Right-pads a glyph into Ink's fixed-width icon box. */
    private static String pad(String glyph) {
        return StringUtils.rightPad(glyph, ICON_CELL_WIDTH);
    }

    private static TextColor inactive() {
        return LanternaTheme.ghostText();
    }

    // ── keys ────────────────────────────────────────────────────────────────

    /**
     * {@code xRl} against {@code Xl}'s two options. Neither end stop is wired, so the focus wraps;
     * a digit submits the row it names outright instead of only focusing it.
     */
    public static KeyResult handleKey(KeyStroke key, int confirmFocus) {
        int focus = Math.floorMod(confirmFocus, CONFIRM_OPTIONS);
        if (isPrevious(key)) return moved(focus - 1);
        if (isNext(key)) return moved(focus + 1);
        return switch (key.getKeyType()) {
            case HOME, PAGE_UP -> new KeyResult(SUBMIT_FOCUS, new Action.None());
            case END, PAGE_DOWN -> new KeyResult(CANCEL_FOCUS, new Action.None());
            case ENTER -> new KeyResult(focus, accept(focus));
            case ESCAPE -> new KeyResult(focus, new Action.Cancel());
            case ARROW_LEFT -> new KeyResult(focus, new Action.SwitchTab(-1));
            case ARROW_RIGHT -> new KeyResult(focus, new Action.SwitchTab(+1));
            case CHARACTER -> characterKey(key, focus);
            default -> new KeyResult(focus, new Action.None());
        };
    }

    private static KeyResult moved(int focus) {
        return new KeyResult(Math.floorMod(focus, CONFIRM_OPTIONS), new Action.None());
    }

    private static KeyResult characterKey(KeyStroke key, int focus) {
        Character character = key.getCharacter();
        if (character == null || key.isCtrlDown() || key.isAltDown()) {
            return new KeyResult(focus, new Action.None());
        }
        if (character >= '1' && character <= '9') {
            int index = character - '1';
            return index < CONFIRM_OPTIONS
                ? new KeyResult(focus, accept(index))
                : new KeyResult(focus, new Action.None());
        }
        return new KeyResult(focus, new Action.None());
    }

    private static Action accept(int focus) {
        return focus == SUBMIT_FOCUS ? new Action.Submit() : new Action.Cancel();
    }

    private static boolean isPrevious(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_UP
            || isChar(key, 'k', false)
            || isChar(key, 'p', true);
    }

    private static boolean isNext(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_DOWN
            || isChar(key, 'j', false)
            || isChar(key, 'n', true);
    }

    private static boolean isChar(KeyStroke key, char expected, boolean withCtrl) {
        return key.getKeyType() == KeyType.CHARACTER
            && key.isCtrlDown() == withCtrl
            && !key.isAltDown()
            && key.getCharacter() != null
            && Character.toLowerCase(key.getCharacter()) == expected;
    }
}
