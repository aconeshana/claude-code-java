package com.claudecode.ui.lanterna.dialog.question;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.questions.QuestionPresenter;
import com.claudecode.ui.lanterna.components.TableBorders;
import com.claudecode.ui.lanterna.dialog.DialogText;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * The plain list card of the {@code AskUserQuestion} dialog — the {@code else} branch of the
 * bundle's {@code Nys} selector, taken whenever the question is multi-select or no option carries
 * a preview.
 *
 * <ul>
 *   <li>Covers: {@code Nys}'s list branch — its item set is {@code AjE} (the preset options plus
 *       the {@code Other} input row); the {@code Submit}/{@code Next} row and the footer sit
 *       outside that set. The branch renders no option preview at all; previews belong to the
 *       design card {@code d$c}.</li>
 *   <li>Covers: {@code NZr} — the option window size,
 *       {@code min(visibleOptionCount ?? 5, max(1, floor((rows - 8) / per)))} with
 *       {@code per = 2} for {@code compact-vertical} and {@code 1} for {@code compact}. Single
 *       select passes {@code layout:"compact-vertical"} outright; {@code cxl} derives it from
 *       {@code options.some(o => o.description)}. See {@link #visibleItemCount}.</li>
 *   <li>Covers: {@code iRl}/{@code YRl} — the pointer gutter's priority: focused {@code ❯} in
 *       {@code suggestion}, then a dim {@code ↓} on the last visible row while more follow, then a
 *       dim {@code ↑} on the first visible row while more precede. See {@link #gutter}.</li>
 *   <li>Covers: {@code YjT}'s {@code visibleFromIndex}/{@code visibleToIndex} — the window scrolls
 *       by the minimum needed to keep the focused item inside it. See {@link #windowStart}.</li>
 *   <li>Covers: {@code OjE} — the footer the list branch owns: a {@code K_} rule, the
 *       {@code {options.length + 2}. Chat about this} row, a blank line, and the chord hint.</li>
 *   <li>Covers: {@code it}/{@code qr}/{@code $Xe} with the {@code OgS.default} preset — the chord
 *       spellings and their dim {@code " · "} separator. See {@link #hint}.</li>
 *   <li>src/components/permissions/AskUserQuestionPermissionRequest/QuestionView.tsx —
 *       question card layout and the "Other" input option. When the windowed card still exceeds the
 *       overlay height, Ink shows the terminal tail; {@link #tailOffset} is that behaviour's
 *       fixed-viewport equivalent, so the footer is never the part that gets clipped.</li>
 *   <li>src/components/CustomSelect/select.tsx (compact-vertical layout) — option description
 *       word-wrap; released 2.1.197 relies on Ink's default {@code wrap="wrap"}, so descriptions
 *       wrap instead of clipping in narrow terminals.</li>
 *   <li>src/components/CustomSelect/SelectMulti.tsx — the bold {@code Submit}/{@code Next} row
 *       after the Other option, and the Other checkbox that mirrors its text live.</li>
 *   <li>src/components/CustomSelect/select-input-option.tsx — "Other" free-text input as ONE row:
 *       dimmed index + (multi: checkbox) + typed text or dimmed placeholder
 *       ("Type something." single / "Type something" multi — 197 {@code showLabel} defaults false,
 *       so there is no "Other" label and no separate text row), with an inverse-video cursor like
 *       ink-text-input's {@code showCursor}.</li>
 * </ul>
 *
 * <p>Deviations from {@code Nys}, deliberately: the header is our own
 * {@code [Header] (i/n — ←/→ to switch)} line rather than the bundle's {@code d0t} tab strip and
 * {@code wm} title gutter, and the hint omits {@code ctrl+g to edit in X} because no {@code $EDITOR}
 * is spawned anywhere yet (TODO, tracked on {@code AskUserQuestionDialog}).
 *
 * <p>The view is stateless: it paints a {@link QuestionState} and reports how many rows that would
 * take. Key handling and the answer model belong to the host dialog.
 */
public final class ListQuestionView {

    /** {@code NZr}'s {@code visibleOptionCount} default — {@code Nys} never overrides it. */
    private static final int MAX_VISIBLE_ITEMS = 5;
    /** {@code bGm} — the rows {@code NZr} reserves for everything that is not an option. */
    private static final int RESERVED_ROWS = 8;
    /** {@code qr}'s separator between {@code it} chords. */
    private static final String CHORD_SEPARATOR = " · ";

    private ListQuestionView() {}

    /**
     * What the card needs to lay itself out: the question, its position in the tab strip, and the
     * live terminal size ({@code rows} sizes the option window exactly as {@code NZr} does).
     */
    public record Context(QuestionPresenter.Question question, int questionIndex,
                          int questionCount, int columns, int rows) {}

    /** Focus index of the Submit/Next row, which only multi-select questions render. */
    public static int submitFocus(QuestionPresenter.Question question) {
        return question.options().size() + 1;
    }

    /** {@code AjE} — the windowed item set: every preset option plus the Other input row. */
    public static int itemCount(QuestionPresenter.Question question) {
        return question.options().size() + 1;
    }

    /**
     * {@code NZr}: how many items of {@code AjE} are on screen at once. Single-select passes
     * {@code compact-vertical} outright; {@code cxl} picks {@code compact} only when no option
     * carries a description, which halves the per-option row budget.
     */
    public static int visibleItemCount(QuestionPresenter.Question question, int rows) {
        boolean anyDescription = question.options().stream()
            .anyMatch(option -> StringUtils.isNotEmpty(option.description()));
        int perItem = !question.multiSelect() || anyDescription ? 2 : 1;
        return Math.min(MAX_VISIBLE_ITEMS,
            Math.max(1, Math.floorDiv(rows - RESERVED_ROWS, perItem)));
    }

    /**
     * {@code YjT}'s window arithmetic: clamp the remembered start into range, then scroll by the
     * minimum needed to bring {@code focus} back inside. Recomputing it on every paint is what
     * keeps the window honest across a terminal resize.
     */
    public static int windowStart(int desired, int itemCount, int visible, int focus) {
        int start = Math.clamp(desired, 0, Math.max(0, itemCount - visible));
        if (focus < start) return focus;
        if (focus >= start + visible) return Math.max(0, focus - visible + 1);
        return start;
    }

    /**
     * How many rows the card wants at {@code context.columns()} wide: header, question, the visible
     * slice of the item window with its wrapped descriptions, the Submit row when multi-select, and
     * the {@code OjE} footer.
     */
    public static int preferredRows(Context context, QuestionState state) {
        return totalRows(context, state, descriptionLines(context.question(), context.columns()));
    }

    /**
     * Paints the card into {@code g}.
     */
    public static void draw(TextGUIGraphics g, Context context, QuestionState state) {
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fill(' ');
        int width = g.getSize().getColumns();
        int height = g.getSize().getRows();

        List<List<String>> optionDesc = descriptionLines(context.question(), width);
        int y = -tailOffset(context, state, optionDesc, height);

        y = drawHeader(g, context, width, y);
        y = drawItems(g, context, state, optionDesc, width, height, y);
        y = drawSubmitRow(g, context, state, y);
        drawFooter(g, context, state, width, y);
    }

    // ── geometry ────────────────────────────────────────────────────────────

    private static int firstVisibleItem(Context context, QuestionState state) {
        return windowStart(state.windowStart(), itemCount(context.question()),
            visibleItemCount(context.question(), context.rows()), state.focus());
    }

    private static int totalRows(Context context, QuestionState state,
                                 List<List<String>> optionDesc) {
        QuestionPresenter.Question question = context.question();
        int optionCount = question.options().size();
        int from = firstVisibleItem(context, state);
        int to = Math.min(itemCount(question),
            from + visibleItemCount(question, context.rows()));
        int rows = 2;   // header + question
        for (int index = from; index < to; index++) {
            rows += 1 + (index < optionCount ? optionDesc.get(index).size() : 0);
        }
        // Submit row (multi-select only) + rule + chat row + blank + hint
        return rows + (question.multiSelect() ? 1 : 0) + 4;
    }

    /**
     * SmartLayout clamps the overlay height, so a card whose visible items carry long wrapped
     * descriptions can still exceed the assigned rows. Ink shows the terminal tail in that case;
     * anchoring on the tail here keeps the rule, the chat row and the hint — the parts that tell
     * the user how to get out — on screen.
     */
    private static int tailOffset(Context context, QuestionState state,
                                  List<List<String>> optionDesc, int height) {
        return Math.max(0, totalRows(context, state, optionDesc) - height);
    }

    // ── rows ────────────────────────────────────────────────────────────────

    private static int drawHeader(TextGUIGraphics g, Context context, int width, int y) {
        String nav = context.questionCount() > 1
            ? "  (" + (context.questionIndex() + 1) + "/" + context.questionCount()
                + " — ←/→ to switch)"
            : "";
        g.setForegroundColor(LanternaTheme.planTeal());
        putRow(g, 1, y, "[" + context.question().header() + "]" + nav);
        g.setForegroundColor(LanternaTheme.inputText());
        putRow(g, 1, y + 1, InlineOverlay.clip(context.question().question(), width - 2));
        return y + 2;
    }

    /** Paints the {@code [from, to)} slice of {@code AjE}: preset options, then the Other row. */
    private static int drawItems(TextGUIGraphics g, Context context, QuestionState state,
                                 List<List<String>> optionDesc, int width, int height, int y) {
        QuestionPresenter.Question question = context.question();
        int optionCount = question.options().size();
        int items = itemCount(question);
        int from = firstVisibleItem(context, state);
        int to = Math.min(items, from + visibleItemCount(question, context.rows()));
        for (int index = from; index < to; index++) {
            Gutter gutter = gutter(state, from, to, items, index);
            y = index < optionCount
                ? drawOptionRow(g, question, state, optionDesc.get(index), gutter, index, width, y)
                : drawOtherRow(g, question, state, gutter, width, height, y);
        }
        return y;
    }

    private static int drawOptionRow(TextGUIGraphics g, QuestionPresenter.Question question,
                                     QuestionState state, List<String> description, Gutter gutter,
                                     int index, int width, int y) {
        QuestionPresenter.Option option = question.options().get(index);
        boolean focused = gutter.focused();
        boolean chosen = state.isSelected(index);
        String indexLabel = optionIndex(index, itemCount(question));
        int x = 1;

        g.setForegroundColor(gutter.color());
        putRow(g, x, y, gutter.glyph());
        x += gutter.glyph().length();

        g.setForegroundColor(LanternaTheme.welcomeDim());
        putRow(g, x, y, indexLabel);
        x += indexLabel.length();

        if (question.multiSelect()) {
            String marker = multiSelectMarker(chosen) + " ";
            g.setForegroundColor(chosen ? LanternaTheme.toolSuccess() : LanternaTheme.inputText());
            putRow(g, x, y, marker);
            x += marker.length();
        }

        g.setForegroundColor(chosen
            ? LanternaTheme.toolSuccess()
            : focused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
        putRow(g, x, y, InlineOverlay.clip(option.label(), Math.max(0, width - x - 1)));
        if (!question.multiSelect() && chosen && x + option.label().length() + 2 < width) {
            g.setForegroundColor(LanternaTheme.toolSuccess());
            putRow(g, x + option.label().length(), y, " ✓");
        }
        y++;
        g.setForegroundColor(LanternaTheme.welcomeDim());
        for (String descLine : description) {
            putRow(g, 1, y, InlineOverlay.clip("  " + descLine, width - 2));
            y++;
        }
        return y;
    }

    /**
     * 197 {@code select-input-option} with {@code showLabel=false}: the row IS the input — no
     * "Other" label, no separate text row. Unfocused it shows the typed text or a dimmed
     * placeholder; focused it shows the scroll window with an inverse-video cursor (an inserted
     * glyph would both shift the tail and, being East Asian ambiguous width, render as a phantom
     * double-width space in CJK terminals).
     */
    private static int drawOtherRow(TextGUIGraphics g, QuestionPresenter.Question question,
                                    QuestionState state, Gutter gutter,
                                    int width, int height, int y) {
        int optionCount = question.options().size();
        boolean focused = gutter.focused();
        // 197 updateInputValue parity: in multi-select the Other checkbox mirrors its text live —
        // typing checks it, clearing unchecks it.
        boolean chosen = question.multiSelect() ? !state.textEmpty() : state.otherSelected();
        String index = optionIndex(optionCount, itemCount(question));
        int x = 1;

        g.setForegroundColor(gutter.color());
        putRow(g, x, y, gutter.glyph());
        x += gutter.glyph().length();
        g.setForegroundColor(LanternaTheme.welcomeDim());
        putRow(g, x, y, index);
        x += index.length();
        if (question.multiSelect()) {
            String marker = multiSelectMarker(chosen) + " ";
            g.setForegroundColor(chosen ? LanternaTheme.toolSuccess() : LanternaTheme.inputText());
            putRow(g, x, y, marker);
            x += marker.length();
        }

        int viewWidth = Math.max(1, width - x - 1);
        TextWindow window = TextWindow.of(state.text(), state.cursor(), viewWidth);
        String visible = window.visible();
        boolean rowVisible = y >= 0 && y < height;
        if (visible.isEmpty()) {
            String placeholder = question.multiSelect() ? "Type something" : "Type something.";
            g.setForegroundColor(LanternaTheme.welcomeDim());
            putRow(g, x, y, InlineOverlay.clip(placeholder, viewWidth));
            if (focused && rowVisible) {
                drawCursor(g, x, y, placeholder.substring(0, 1));
            }
        } else {
            g.setForegroundColor(LanternaTheme.inputText());
            putRow(g, x, y, InlineOverlay.clip(visible, viewWidth));
            if (focused && rowVisible) {
                int offset = Math.min(window.cursorColumn(), visible.length());
                int cursorCell =
                    x + TerminalTextUtils.getColumnWidth(visible.substring(0, offset));
                drawCursor(g, cursorCell, y,
                    offset < visible.length() ? String.valueOf(visible.charAt(offset)) : " ");
            }
        }
        return y + 1;
    }

    private static void drawCursor(TextGUIGraphics g, int x, int y, String glyph) {
        g.enableModifiers(SGR.REVERSE);
        g.putString(x, y, glyph);
        g.disableModifiers(SGR.REVERSE);
    }

    /**
     * 197 SelectMulti submit row: pointer + bold label; "Submit" on the last question, "Next"
     * otherwise. Enter here (not on an option) submits the toggled set. It sits below the option
     * window rather than inside it, so it never scrolls out of view.
     */
    private static int drawSubmitRow(TextGUIGraphics g, Context context, QuestionState state,
                                     int y) {
        if (!context.question().multiSelect()) return y;
        boolean focused = !state.chatFocused()
            && state.focus() == submitFocus(context.question());
        String label =
            context.questionIndex() == context.questionCount() - 1 ? "Submit" : "Next";
        g.setForegroundColor(focused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
        g.enableModifiers(SGR.BOLD);
        putRow(g, 1, y, (focused ? Figures.POINTER + "    " : "     ") + label);
        g.disableModifiers(SGR.BOLD);
        return y + 1;
    }

    /**
     * {@code OjE}: the rule, the numbered {@code Chat about this} row, a blank line, and the chord
     * hint. Only the list branch draws this; the design card carries its own footer.
     */
    private static int drawFooter(TextGUIGraphics g, Context context, QuestionState state,
                                  int width, int y) {
        boolean chatFocused = state.chatFocused();
        g.setForegroundColor(LanternaTheme.ghostText());
        putRow(g, 1, y, String.valueOf(TableBorders.HORIZONTAL).repeat(Math.max(0, width - 2)));
        y++;

        g.setForegroundColor(chatFocused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
        String chatRow = (chatFocused ? Figures.POINTER + " " : "  ")
            + chatRowNumber(context.question()) + ". " + DesignQuestionView.CHAT_LABEL;
        putRow(g, 1, y, InlineOverlay.clip(chatRow, width - 2));
        y += 2;   // {@code marginTop:1} before the hint

        g.setForegroundColor(LanternaTheme.ghostText());
        putRow(g, 1, y, InlineOverlay.clip(hint(context), width - 2));
        return y + 1;
    }

    /** {@code Smn} — the chat row's visible index, one past the Other option's. */
    public static int chatRowNumber(QuestionPresenter.Question question) {
        return question.options().size() + 2;
    }

    /**
     * {@code OjE}'s chord list. A single question advertises the arrows; two or more replace them
     * with the bundle's literal {@code Tab/Arrow keys to navigate}, since Tab then also switches
     * questions.
     */
    static String hint(Context context) {
        String navigate = context.questionCount() == 1
            ? Figures.UP_ARROW + "/" + Figures.DOWN_ARROW + " to navigate"
            : "Tab/Arrow keys to navigate";
        return String.join(CHORD_SEPARATOR,
            "Enter to select", navigate, "Esc to cancel");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** One gutter cell: {@code iRl}'s glyph plus the colour that goes with it. */
    private record Gutter(String glyph, TextColor color, boolean focused) {}

    /**
     * {@code iRl}'s priority, minus the hover state this card has no pointer for: the focused
     * pointer wins, then the "more below" arrow on the last visible row, then "more above" on the
     * first. Focusing the chat row takes the pointer off the select, exactly as the design card
     * does.
     */
    private static Gutter gutter(QuestionState state, int from, int to, int itemCount, int index) {
        if (!state.chatFocused() && state.focus() == index) {
            return new Gutter(Figures.POINTER + " ", LanternaTheme.suggestion(), true);
        }
        if (index == to - 1 && to < itemCount) {
            return new Gutter(Figures.DOWN_ARROW + " ", LanternaTheme.welcomeDim(), false);
        }
        if (index == from && from > 0) {
            return new Gutter(Figures.UP_ARROW + " ", LanternaTheme.welcomeDim(), false);
        }
        return new Gutter("  ", LanternaTheme.inputText(), false);
    }

    /** Draws a row only when it falls inside the assigned height (see {@link #tailOffset}). */
    private static void putRow(TextGUIGraphics g, int x, int y, String s) {
        if (y >= 0 && y < g.getSize().getRows()) g.putString(x, y, s);
    }

    private static List<List<String>> descriptionLines(
            QuestionPresenter.Question question, int columns) {
        List<List<String>> lines = new ArrayList<>(question.options().size());
        for (QuestionPresenter.Option option : question.options()) {
            lines.add(descriptionLines(option.description(), columns - 3));
        }
        return lines;
    }

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
}
