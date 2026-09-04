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
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * The design-question card: a narrow option column on the left, a bordered preview of the focused
 * option on the right, an inline notes editor beneath it, and a {@code Chat about this} row.
 *
 * <p>Authority is the {@code 2.1.236} bundle, where this component is {@code d$c}. It is chosen
 * over the plain list card by {@link QuestionSanitizer#isDesignVariant(DisplayQuestion)}.
 *
 * <ul>
 *   <li>Covers: {@code d$c}'s layout — the top rule, the tab strip, the gutter-wrapped title, the
 *       {@code 30}-column option list beside the preview box, the {@code Notes:} row, the second
 *       rule, the chat row, and the chord footer. See {@link #render(Context, QuestionState)}.</li>
 *   <li>Covers: {@code d$c}'s key handler {@code F} together with the helpers it calls
 *       ({@code W}, {@code B}, {@code Y}, {@code ee}, {@code J}, {@code G}) — navigation clamps
 *       instead of wrapping, digits only move the focus, and {@code escape} inside the notes editor
 *       leaves the editor rather than the card. See {@link #handleKey}.</li>
 *   <li>Covers: the host's {@code Tabs} keybinding context, whose {@code left}/{@code right} reach
 *       this card because {@code F} claims neither — the notes editor does, so they only move the
 *       caret while it is open.</li>
 *   <li>Covers: {@code K_} (the full-width rule), {@code wm} (the multi-line title gutter), and
 *       {@code qFe} (the bold title).</li>
 *   <li>Covers: {@code jEi} — the answer recorded when notes are submitted with nothing selected.
 *       See {@link #NOTES_ONLY}.</li>
 *   <li>Covers: {@code qr} / {@code it} / {@code $Xe} — the footer's chord list, its {@code " · "}
 *       separator, and the chord spellings from {@code LgS}.</li>
 * </ul>
 *
 * <p>Deviation: the bundle computes the preview width as {@code columns - 30 - 4} with no floor, so
 * a terminal narrower than {@link #MIN_TERMINAL_COLUMNS} would give it a negative width. This port
 * reports that case through {@link #fitsTerminal(int)} so the host can fall back to
 * {@link ListQuestionView} instead of laying out a broken card.
 *
 * <p>The view is stateless: it paints a {@link QuestionState} and interprets keys against it. The
 * answer model and the tool result belong to the host dialog.
 */
public final class DesignQuestionView {

    /** {@code U} — the fixed width of the option column. */
    public static final int OPTION_COLUMN_WIDTH = 30;

    /** {@code j} — the gap between the option column and the preview column. */
    public static final int COLUMN_GAP = 4;

    /** The rows {@code ie} reserves for everything that is not the preview box. */
    static final int PREVIEW_ROWS_RESERVE = 26;

    /** Below this the preview column cannot reach {@link PreviewBox#MIN_WIDTH}. */
    public static final int MIN_TERMINAL_COLUMNS =
        OPTION_COLUMN_WIDTH + COLUMN_GAP + PreviewBox.MIN_WIDTH;

    /** {@code jEi} — recorded as the answer when notes are submitted with no option selected. */
    public static final String NOTES_ONLY = "(notes only)";

    static final String CHAT_LABEL = "Chat about this";
    static final String NOTES_LABEL = "Notes:";
    static final String NOTES_PLACEHOLDER = "press n to add notes";

    /** {@code Rl}'s {@code columns} — the notes editor's own scroll window, not the card's width. */
    static final int NOTES_EDITOR_COLUMNS = 60;

    /** {@code LgS}'s spellings for the arrow chords, joined by {@code $Xe}'s {@code arrowSep}. */
    private static final String ARROW_CHORDS = "↑/↓";

    /** {@code qr}'s separator between chords. */
    private static final String CHORD_SEPARATOR = " · ";

    private DesignQuestionView() {}

    /**
     * Everything the card needs that does not live in {@link QuestionState}.
     *
     * @param questions       every question in the request, for the tab strip
     * @param currentIndex    which of them this card is showing
     * @param answeredKeys    the keys already answered, for the tab strip's checkboxes
     * @param hideSubmitTab   whether the tab strip omits its {@code Submit} chip
     * @param minContentWidth {@code XFg} — {@link PreviewBox#sharedMinWidth(List)}
     * @param columns         the terminal width
     * @param rows            the terminal height
     * @param editorName      {@code z1(cne())}, or {@code null} when no editor is configured
     */
    public record Context(
        List<DisplayQuestion> questions,
        int currentIndex,
        Set<String> answeredKeys,
        boolean hideSubmitTab,
        int minContentWidth,
        int columns,
        int rows,
        String editorName) {

        public Context {
            questions = List.copyOf(questions);
            answeredKeys = Set.copyOf(answeredKeys);
        }

        DisplayQuestion question() {
            return questions.get(currentIndex);
        }
    }

    /** What a key press asks the host to do; the view has already applied any state change. */
    public sealed interface Action {

        /** The key was consumed without changing the dialog's flow. */
        record None() implements Action {}

        /** {@code onAnswer(key, value)} — the raw option value the user picked. */
        record Answer(String value) implements Action {}

        /** {@code onCancel} — dismiss the whole dialog. */
        record Cancel() implements Action {}

        /** {@code onRespondToClaude} — turn the request into a clarification. */
        record RespondToClaude() implements Action {}

        /** {@code tabs:previous} / {@code tabs:next}. */
        record SwitchTab(int delta) implements Action {}
    }

    // ── rendering ───────────────────────────────────────────────────────────

    /** Lays the whole card out as styled lines, top to bottom. */
    public static List<List<Segment>> render(Context context, QuestionState state) {
        DisplayQuestion question = context.question();
        List<List<Segment>> lines = new ArrayList<>();

        lines.add(rule(context.columns()));
        lines.add(QuestionTabStrip.render(context.questions(), context.currentIndex(),
            context.answeredKeys(), context.hideSubmitTab(), context.columns()));
        lines.addAll(titleLines(question, context.columns()));
        lines.add(List.of());
        lines.addAll(twoColumnLines(context, state, question));
        lines.add(List.of());
        lines.add(rule(context.columns()));
        lines.add(chatRow(state));
        lines.add(List.of());
        lines.add(footer(context, state));
        return List.copyOf(lines);
    }

    /** Paints {@link #render} into a component's graphics. */
    public static void draw(TextGUIGraphics graphics, Context context, QuestionState state) {
        SegmentPainter.paint(graphics, render(context, state));
    }

    /** How many rows {@link #render} produces at this size. */
    public static int preferredRows(Context context, QuestionState state) {
        return render(context, state).size();
    }

    /** Whether the preview column can still reach its minimum width at {@code columns}. */
    public static boolean fitsTerminal(int columns) {
        return columns >= MIN_TERMINAL_COLUMNS;
    }

    /**
     * {@code d$c}'s question-change effect: the focus lands on the option already selected for this
     * question, or on the first option when none is.
     */
    public static void syncFocusToSelection(QuestionState state) {
        List<Integer> selected = state.selectedIndices();
        state.setFocus(selected.isEmpty() ? 0 : selected.getFirst());
        state.setChatFocused(false);
        state.setNotesEditing(false);
    }

    // ── rows ────────────────────────────────────────────────────────────────

    /** {@code K_} with no title — one full-width rule in the inactive colour. */
    private static List<Segment> rule(int columns) {
        return SegmentLines.plain(
            String.valueOf(TableBorders.HORIZONTAL).repeat(Math.max(0, columns)), inactive());
    }

    /**
     * {@code qFe} inside {@code wm}: a bold title, prefixed line by line with a dim vertical rule
     * when {@code needsGutter} says it claims a multi-line slot.
     */
    private static List<List<Segment>> titleLines(DisplayQuestion question, int columns) {
        DisplayQuestion.DisplayText title = question.displayQuestion();
        Segment styled = new Segment(title.text(), LanternaTheme.inputText(), null, null,
            Set.of(SGR.BOLD));
        if (!title.needsGutter()) {
            return List.of(SegmentLines.sliceToWidth(List.of(styled), columns));
        }
        String gutter = TableBorders.VERTICAL + " ";
        int available = Math.max(1, columns - gutter.length());
        List<List<Segment>> lines = new ArrayList<>();
        for (String source : title.text().split("\n", -1)) {
            Segment line = new Segment(source, LanternaTheme.inputText(), null, null,
                Set.of(SGR.BOLD));
            for (List<Segment> wrapped : SegmentLines.wrap(List.of(line), available)) {
                List<Segment> row = new ArrayList<>(wrapped.size() + 1);
                row.add(new Segment(gutter, inactive()));
                row.addAll(wrapped);
                lines.add(List.copyOf(row));
            }
        }
        return lines;
    }

    /** The option column beside the preview box, the blank row, and the notes row. */
    private static List<List<Segment>> twoColumnLines(
            Context context, QuestionState state, DisplayQuestion question) {
        List<List<Segment>> left = optionRows(question, state);

        List<List<Segment>> right = new ArrayList<>(PreviewBox.render(
            previewContent(question, state),
            Math.max(1, context.rows() - PREVIEW_ROWS_RESERVE),
            0,
            context.minContentWidth(),
            context.columns() - OPTION_COLUMN_WIDTH - COLUMN_GAP).rows());
        right.add(List.of());
        right.add(notesRow(state));

        int height = Math.max(left.size(), right.size());
        List<List<Segment>> merged = new ArrayList<>(height);
        for (int index = 0; index < height; index++) {
            List<Segment> leftRow = index < left.size() ? left.get(index) : List.of();
            List<Segment> rightRow = index < right.size() ? right.get(index) : List.of();
            merged.add(joinColumns(leftRow, rightRow));
        }
        return merged;
    }

    private static List<Segment> joinColumns(List<Segment> left, List<Segment> right) {
        if (right.isEmpty()) return left;
        int pad = Math.max(0, OPTION_COLUMN_WIDTH - SegmentLines.width(left)) + COLUMN_GAP;
        List<Segment> row = new ArrayList<>(left.size() + right.size() + 1);
        row.addAll(left);
        row.add(new Segment(" ".repeat(pad), LanternaTheme.inputText()));
        row.addAll(right);
        return List.copyOf(row);
    }

    /**
     * One row per option: the pointer, a dim ordinal, the label, and a tick once it is selected.
     * The design variant deliberately omits descriptions — only the list card shows them.
     */
    private static List<List<Segment>> optionRows(DisplayQuestion question, QuestionState state) {
        List<DisplayQuestion.DisplayOption> options = question.options();
        List<List<Segment>> rows = new ArrayList<>(options.size());
        for (int index = 0; index < options.size(); index++) {
            boolean focused = !state.chatFocused() && state.focus() == index;
            boolean selected = state.isSelected(index);
            List<Segment> row = new ArrayList<>(4);
            row.add(focused
                ? new Segment(Figures.POINTER, LanternaTheme.suggestion())
                : new Segment(" ", LanternaTheme.inputText()));
            row.add(new Segment(" " + (index + 1) + ".", inactive()));
            row.add(new Segment(" " + options.get(index).displayLabel(),
                labelColor(selected, focused), null, null,
                focused ? Set.of(SGR.BOLD) : Set.of()));
            if (selected) row.add(new Segment(" " + Figures.TICK, LanternaTheme.toolSuccess()));
            rows.add(SegmentLines.sliceToWidth(List.copyOf(row), OPTION_COLUMN_WIDTH));
        }
        return rows;
    }

    private static TextColor labelColor(boolean selected, boolean focused) {
        if (selected) return LanternaTheme.toolSuccess();
        return focused ? LanternaTheme.suggestion() : LanternaTheme.inputText();
    }

    /** {@code te} — the focused option's preview, or the standing notice for a withheld one. */
    private static String previewContent(DisplayQuestion question, QuestionState state) {
        List<DisplayQuestion.DisplayOption> options = question.options();
        if (state.focus() < 0 || state.focus() >= options.size()) return PreviewBox.NO_PREVIEW;
        return switch (options.get(state.focus()).preview()) {
            case DisplayQuestion.Preview.Full full -> full.markdown();
            case DisplayQuestion.Preview.Withheld _ -> QuestionSanitizer.WITHHELD_PREVIEW_NOTICE;
            case null -> PreviewBox.NO_PREVIEW;
        };
    }

    /** {@code Notes:} plus either the live editor or the dim, italic hint. */
    private static List<Segment> notesRow(QuestionState state) {
        List<Segment> row = new ArrayList<>(4);
        row.add(new Segment(NOTES_LABEL + " ", LanternaTheme.suggestion()));
        if (!state.notesEditing()) {
            String hint = state.textEmpty() ? NOTES_PLACEHOLDER : state.text();
            row.add(new Segment(hint, inactive(), null, null, Set.of(SGR.ITALIC)));
            return List.copyOf(row);
        }
        TextWindow window = TextWindow.of(state.text(), state.cursor(), NOTES_EDITOR_COLUMNS);
        String visible = window.visible();
        int caret = Math.min(window.cursorColumn(), visible.length());
        if (caret > 0) {
            row.add(new Segment(visible.substring(0, caret), LanternaTheme.inputText()));
        }
        String under = caret < visible.length() ? visible.substring(caret, caret + 1) : " ";
        row.add(new Segment(under, LanternaTheme.inputText(), null, null, Set.of(SGR.REVERSE)));
        if (caret + 1 < visible.length()) {
            row.add(new Segment(visible.substring(caret + 1), LanternaTheme.inputText()));
        }
        return List.copyOf(row);
    }

    private static List<Segment> chatRow(QuestionState state) {
        return List.of(
            state.chatFocused()
                ? new Segment(Figures.POINTER + " ", LanternaTheme.suggestion())
                : new Segment("  ", LanternaTheme.inputText()),
            new Segment(CHAT_LABEL,
                state.chatFocused() ? LanternaTheme.suggestion() : LanternaTheme.inputText()));
    }

    /** {@code qr} joins {@code it} chords with a dim {@code " · "}; the whole row is inactive. */
    private static List<Segment> footer(Context context, QuestionState state) {
        List<String> chords = new ArrayList<>(6);
        chords.add("Enter to select");
        chords.add(ARROW_CHORDS + " to navigate");
        chords.add("n to add notes");
        if (context.questions().size() > 1) chords.add("Tab to switch questions");
        if (state.notesEditing() && StringUtils.isNotBlank(context.editorName())) {
            chords.add("ctrl+g to edit in " + context.editorName());
        }
        chords.add("Esc to cancel");
        return SegmentLines.plain(String.join(CHORD_SEPARATOR, chords), inactive());
    }

    private static TextColor inactive() {
        return LanternaTheme.ghostText();
    }

    // ── keys ────────────────────────────────────────────────────────────────

    /**
     * {@code F} — the card's key handler. Any state change is applied in place; the return value is
     * only what the host still has to do.
     */
    public static Action handleKey(
            KeyStroke key, DisplayQuestion question, QuestionState state) {
        if (state.chatFocused()) return chatKey(key, state);
        if (state.notesEditing()) return notesKey(key, question, state);
        return optionKey(key, question, state);
    }

    private static Action chatKey(KeyStroke key, QuestionState state) {
        if (isUp(key)) {
            state.setChatFocused(false);
            return new Action.None();
        }
        return switch (key.getKeyType()) {
            case ENTER -> new Action.RespondToClaude();
            case ESCAPE -> new Action.Cancel();
            case ARROW_LEFT -> new Action.SwitchTab(-1);
            case ARROW_RIGHT -> new Action.SwitchTab(+1);
            default -> new Action.None();
        };
    }

    /**
     * Inside the notes editor only {@code escape} and {@code enter} are the card's; everything else
     * is the {@code TextInput}'s, so {@code escape} leaves the editor rather than the dialog.
     */
    private static Action notesKey(KeyStroke key, DisplayQuestion question, QuestionState state) {
        switch (key.getKeyType()) {
            case ESCAPE -> {
                state.setNotesEditing(false);
                return new Action.None();
            }
            case ENTER -> {
                state.setNotesEditing(false);
                return submitNotes(question, state);
            }
            case ARROW_LEFT -> state.moveCursor(-1);
            case ARROW_RIGHT -> state.moveCursor(+1);
            case HOME -> state.cursorToStart();
            case END -> state.cursorToEnd();
            case DELETE -> state.deleteForward();
            default -> state.applyEdit(key);
        }
        return new Action.None();
    }

    /** {@code ee} — an already-selected option still wins; bare notes answer with {@code jEi}. */
    private static Action submitNotes(DisplayQuestion question, QuestionState state) {
        List<Integer> selected = state.selectedIndices();
        if (!selected.isEmpty()) {
            return new Action.Answer(question.options().get(selected.getFirst()).value());
        }
        return StringUtils.isBlank(state.text())
            ? new Action.None() : new Action.Answer(NOTES_ONLY);
    }

    private static Action optionKey(KeyStroke key, DisplayQuestion question, QuestionState state) {
        int count = question.options().size();
        if (isUp(key)) {
            if (state.focus() > 0) state.setFocus(state.focus() - 1);
            return new Action.None();
        }
        if (isDown(key)) {
            if (state.focus() >= count - 1) state.setChatFocused(true);
            else state.setFocus(state.focus() + 1);
            return new Action.None();
        }
        return switch (key.getKeyType()) {
            case ENTER -> select(question, state);
            case ESCAPE -> new Action.Cancel();
            case TAB -> new Action.SwitchTab(key.isShiftDown() ? -1 : +1);
            case ARROW_LEFT -> new Action.SwitchTab(-1);
            case ARROW_RIGHT -> new Action.SwitchTab(+1);
            case CHARACTER -> characterKey(key, state, count);
            default -> new Action.None();
        };
    }

    /** {@code B} — Enter records the focused option and answers with its raw value. */
    private static Action select(DisplayQuestion question, QuestionState state) {
        int focus = state.focus();
        if (focus < 0 || focus >= question.options().size()) return new Action.None();
        state.selectOnly(focus);
        return new Action.Answer(question.options().get(focus).value());
    }

    /** {@code n} opens the notes editor; {@code 1}-{@code 9} only move the focus. */
    private static Action characterKey(KeyStroke key, QuestionState state, int count) {
        Character character = key.getCharacter();
        if (character == null || key.isCtrlDown() || key.isAltDown()) return new Action.None();
        if (character == 'n') {
            state.setNotesEditing(true);
            state.cursorToEnd();
            return new Action.None();
        }
        if (character >= '1' && character <= '9') {
            int index = character - '1';
            if (index < count) state.setFocus(index);
        }
        return new Action.None();
    }

    private static boolean isUp(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_UP || isCtrlChar(key, 'p');
    }

    private static boolean isDown(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_DOWN || isCtrlChar(key, 'n');
    }

    private static boolean isCtrlChar(KeyStroke key, char expected) {
        return key.getKeyType() == KeyType.CHARACTER
            && key.isCtrlDown()
            && key.getCharacter() != null
            && Character.toLowerCase(key.getCharacter()) == expected;
    }
}
