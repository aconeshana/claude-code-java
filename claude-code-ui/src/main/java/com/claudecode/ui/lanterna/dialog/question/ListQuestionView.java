package com.claudecode.ui.lanterna.dialog.question;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.questions.QuestionPresenter;
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
 *   <li>src/components/permissions/AskUserQuestionPermissionRequest/QuestionView.tsx —
 *       question card layout and the "Other" input option. When the wrapped card exceeds the
 *       overlay height, 197/Ink shows the terminal tail; here the focused row is kept visible
 *       instead (Other focus bottom-anchors the card).</li>
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
 * <p>The view is stateless: it paints a {@link QuestionState} and reports how many rows that would
 * take. Key handling and the answer model belong to the host dialog.
 */
public final class ListQuestionView {

    private static final int MAX_PREVIEW_LINES = 8;

    private ListQuestionView() {}

    /** Focus index of the Submit/Next row, which only multi-select questions render. */
    public static int submitFocus(QuestionPresenter.Question question) {
        return question.options().size() + 1;
    }

    /**
     * How many rows the card wants at {@code columns} wide: header, question, every option with
     * its wrapped description, the Other row, the Submit row when multi-select, the hint, and the
     * focused option's preview tail.
     */
    public static int preferredRows(
            QuestionPresenter.Question question, QuestionState state, int columns) {
        int previewLines = 0;
        String preview = focusedPreview(question, state);
        if (preview != null) {
            previewLines = Math.min(MAX_PREVIEW_LINES, (int) preview.lines().count()) + 1;
        }
        int optionRows = 0;
        for (QuestionPresenter.Option option : question.options()) {
            optionRows += 1 + descriptionLines(option.description(), columns - 3).size();
        }
        return 2 + optionRows + 1 + (question.multiSelect() ? 1 : 0) + 1 + previewLines;
    }

    /**
     * Paints the card into {@code g}.
     *
     * @param questionIndex the question's position, used for the {@code (i/n)} nav hint
     * @param questionCount how many questions the dialog is collecting
     */
    public static void draw(TextGUIGraphics g, QuestionPresenter.Question question,
                            QuestionState state, int questionIndex, int questionCount) {
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fill(' ');
        int width = g.getSize().getColumns();
        int height = g.getSize().getRows();
        int optionCount = question.options().size();

        List<List<String>> optionDesc = new ArrayList<>(optionCount);
        for (QuestionPresenter.Option option : question.options()) {
            optionDesc.add(descriptionLines(option.description(), width - 3));
        }
        String preview = focusedPreview(question, state);
        int y = -scrollOffset(question, state, optionDesc, preview, height);

        y = drawHeader(g, question, questionIndex, questionCount, width, y);
        y = drawOptions(g, question, state, optionDesc, width, y);
        y = drawOtherRow(g, question, state, width, height, y);
        y = drawSubmitRow(g, question, state, questionIndex, questionCount, y);
        y = drawHint(g, question, width, y);
        drawPreview(g, preview, width, y);
    }

    // ── vertical overflow ───────────────────────────────────────────────────

    /**
     * SmartLayout clamps the overlay height, so a card with many wrapped descriptions can exceed
     * the assigned rows. Ink shows the terminal tail in that case; we additionally anchor on the
     * focused row — options keep their label+description block visible, and focusing Other
     * bottom-anchors so Other/text/hint never get cut.
     */
    private static int scrollOffset(QuestionPresenter.Question question, QuestionState state,
                                    List<List<String>> optionDesc, String preview, int height) {
        int optionCount = question.options().size();
        int totalRows = 2;   // header + question
        for (List<String> desc : optionDesc) totalRows += 1 + desc.size();

        int focusRowY = totalRows;   // Other input row (focus == optionCount)
        if (state.focus() < optionCount) {
            focusRowY = 2;
            for (int i = 0; i < state.focus(); i++) focusRowY += 1 + optionDesc.get(i).size();
        }
        totalRows += 2 + (question.multiSelect() ? 1 : 0);   // Other + Submit? + hint
        if (preview != null) {
            totalRows += Math.min(MAX_PREVIEW_LINES, (int) preview.lines().count()) + 1;
        }

        int maxOffset = Math.max(0, totalRows - height);
        if (state.focus() >= optionCount) return maxOffset;
        int focusEndY = focusRowY + optionDesc.get(state.focus()).size();
        return Math.min(Math.max(0, focusEndY - height + 1), maxOffset);
    }

    // ── rows ────────────────────────────────────────────────────────────────

    private static int drawHeader(TextGUIGraphics g, QuestionPresenter.Question question,
                                  int questionIndex, int questionCount, int width, int y) {
        String nav = questionCount > 1
            ? "  (" + (questionIndex + 1) + "/" + questionCount + " — ←/→ to switch)"
            : "";
        g.setForegroundColor(LanternaTheme.planTeal());
        putRow(g, 1, y, "[" + question.header() + "]" + nav);
        g.setForegroundColor(LanternaTheme.inputText());
        putRow(g, 1, y + 1, InlineOverlay.clip(question.question(), width - 2));
        return y + 2;
    }

    private static int drawOptions(TextGUIGraphics g, QuestionPresenter.Question question,
                                   QuestionState state, List<List<String>> optionDesc,
                                   int width, int startY) {
        int optionCount = question.options().size();
        int renderedOptionCount = optionCount + 1;
        int y = startY;
        for (int i = 0; i < optionCount; i++) {
            QuestionPresenter.Option option = question.options().get(i);
            boolean focused = state.focus() == i;
            boolean chosen = state.isSelected(i);
            String pointer = focused ? "❯ " : "  ";
            String index = optionIndex(i, renderedOptionCount);
            int x = 1;

            g.setForegroundColor(
                focused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
            putRow(g, x, y, pointer);
            x += pointer.length();

            g.setForegroundColor(LanternaTheme.welcomeDim());
            putRow(g, x, y, index);
            x += index.length();

            if (question.multiSelect()) {
                String marker = multiSelectMarker(chosen) + " ";
                g.setForegroundColor(
                    chosen ? LanternaTheme.toolSuccess() : LanternaTheme.inputText());
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
            for (String descLine : optionDesc.get(i)) {
                putRow(g, 1, y, InlineOverlay.clip("  " + descLine, width - 2));
                y++;
            }
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
                                    QuestionState state, int width, int height, int y) {
        int optionCount = question.options().size();
        boolean focused = state.focus() == optionCount;
        // 197 updateInputValue parity: in multi-select the Other checkbox mirrors its text live —
        // typing checks it, clearing unchecks it.
        boolean chosen = question.multiSelect() ? !state.textEmpty() : state.otherSelected();
        String pointer = focused ? "❯ " : "  ";
        String index = optionIndex(optionCount, optionCount + 1);
        int x = 1;

        g.setForegroundColor(focused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
        putRow(g, x, y, pointer);
        x += pointer.length();
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
     * otherwise. Enter here (not on an option) submits the toggled set.
     */
    private static int drawSubmitRow(TextGUIGraphics g, QuestionPresenter.Question question,
                                     QuestionState state, int questionIndex, int questionCount,
                                     int y) {
        if (!question.multiSelect()) return y;
        boolean focused = state.focus() == submitFocus(question);
        String label = questionIndex == questionCount - 1 ? "Submit" : "Next";
        g.setForegroundColor(focused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
        g.enableModifiers(SGR.BOLD);
        putRow(g, 1, y, (focused ? "❯    " : "     ") + label);
        g.disableModifiers(SGR.BOLD);
        return y + 1;
    }

    private static int drawHint(
            TextGUIGraphics g, QuestionPresenter.Question question, int width, int y) {
        g.setForegroundColor(LanternaTheme.welcomeDim());
        String hint = question.multiSelect()
            ? "enter to toggle · tab to submit · esc to cancel"
            : "enter to select · esc to cancel";
        putRow(g, 1, y, InlineOverlay.clip(hint, width - 2));
        return y + 1;
    }

    private static void drawPreview(TextGUIGraphics g, String preview, int width, int startY) {
        if (preview == null) return;
        int y = startY;
        g.setForegroundColor(LanternaTheme.welcomeDim());
        putRow(g, 1, y, "── preview ──");
        y++;
        int shown = 0;
        for (String line : preview.split("\n", -1)) {
            if (shown++ >= MAX_PREVIEW_LINES) break;
            putRow(g, 1, y, InlineOverlay.clip(line, width - 2));
            y++;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Draws a row only when it falls inside the assigned height (see {@link #scrollOffset}). */
    private static void putRow(TextGUIGraphics g, int x, int y, String s) {
        if (y >= 0 && y < g.getSize().getRows()) g.putString(x, y, s);
    }

    private static String focusedPreview(
            QuestionPresenter.Question question, QuestionState state) {
        if (state.focus() >= question.options().size()) return null;
        String preview = question.options().get(state.focus()).preview();
        return StringUtils.isNotBlank(preview) ? preview : null;
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
