package com.claudecode.ui.lanterna.dialog.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.tools.questions.QuestionPresenter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The list card's pure layout helpers: 197's glyphs, its right-aligned option numbering, the
 * two-level description wrap Ink gets for free from {@code wrap="wrap"}, {@code NZr}'s option-window
 * size, {@code YjT}'s minimum-scroll window start, and the {@code OjE} chord hint.
 */
class ListQuestionViewTest {

    private static QuestionPresenter.Question question(boolean multiSelect, String description,
                                                       int optionCount) {
        List<QuestionPresenter.Option> options = new ArrayList<>(optionCount);
        for (int index = 0; index < optionCount; index++) {
            options.add(new QuestionPresenter.Option("opt" + index, description, null));
        }
        return new QuestionPresenter.Question("Q?", "Hdr", List.copyOf(options), multiSelect);
    }

    @Test
    void selectionGlyphsMatchClaudeCode197() {
        assertEquals("[ ]", ListQuestionView.multiSelectMarker(false));
        assertEquals("[✓]", ListQuestionView.multiSelectMarker(true));
        assertEquals("1. ", ListQuestionView.optionIndex(0, 4));
        assertEquals("10. ", ListQuestionView.optionIndex(9, 12));
        assertEquals(" 1. ", ListQuestionView.optionIndex(0, 12));
    }

    @Test
    void theItemSetIsTheOptionsPlusTheOtherRow() {
        // AjE: the Submit row sits outside the window, so it is not an item.
        assertEquals(5, ListQuestionView.itemCount(question(true, "d", 4)));
        assertEquals(5, ListQuestionView.submitFocus(question(true, "d", 4)));
        assertEquals(6, ListQuestionView.chatRowNumber(question(true, "d", 4)));
    }

    @Test
    void visibleItemCountFollowsNZr() {
        // NZr: min(5, max(1, floor((rows - 8) / per))), per = 2 for compact-vertical.
        var described = question(true, "d", 12);
        assertEquals(5, ListQuestionView.visibleItemCount(described, 40));
        assertEquals(3, ListQuestionView.visibleItemCount(described, 14));
        assertEquals(1, ListQuestionView.visibleItemCount(described, 10));
        assertEquals(1, ListQuestionView.visibleItemCount(described, 4),
            "max(1, …) keeps at least one item on screen on a tiny terminal");
    }

    @Test
    void onlyADescriptionlessMultiSelectGetsTheCompactSingleRowBudget() {
        // cxl derives the layout from options.some(o => o.description); single select passes
        // compact-vertical outright, so it keeps per = 2 even without descriptions.
        assertEquals(5, ListQuestionView.visibleItemCount(question(true, null, 12), 14),
            "compact halves the per-item budget: floor((14-8)/1) = 6, capped at 5");
        assertEquals(3, ListQuestionView.visibleItemCount(question(false, null, 12), 14),
            "single select stays compact-vertical: floor((14-8)/2) = 3");
    }

    @Test
    void windowStartScrollsByTheMinimumNeededToShowTheFocus() {
        assertEquals(0, ListQuestionView.windowStart(0, 5, 3, 0));
        assertEquals(0, ListQuestionView.windowStart(0, 5, 3, 2), "already inside the window");
        assertEquals(1, ListQuestionView.windowStart(0, 5, 3, 3), "scrolls down by exactly one");
        assertEquals(2, ListQuestionView.windowStart(0, 5, 3, 4));
        assertEquals(2, ListQuestionView.windowStart(3, 5, 3, 4), "clamped to itemCount - visible");
        assertEquals(1, ListQuestionView.windowStart(2, 5, 3, 1), "scrolls up to the focus");
        assertEquals(0, ListQuestionView.windowStart(4, 5, 5, 0),
            "a window that holds everything always starts at the top");
    }

    @Test
    void windowStartHealsItselfAfterTheTerminalGrows() {
        // A remembered start of 2 is out of range once 5 items fit again.
        assertEquals(0, ListQuestionView.windowStart(2, 5, 5, 4));
    }

    @Test
    void theHintSpellsOutOjEsChords() {
        var single = new ListQuestionView.Context(question(true, "d", 2), 0, 1, 80, 40);
        assertEquals("Enter to select · ↑/↓ to navigate · Esc to cancel",
            ListQuestionView.hint(single));

        var many = new ListQuestionView.Context(question(true, "d", 2), 0, 3, 80, 40);
        assertEquals("Enter to select · Tab/Arrow keys to navigate · Esc to cancel",
            ListQuestionView.hint(many));
    }

    @Test
    void preferredRowsCountsTheWindowAndTheFooterButNoPreview() {
        // 2 (header + question) + 3 windowed items × (label + 1 description line)
        // + 1 Submit + 4 footer (rule, chat row, blank, hint) = 13.
        var context = new ListQuestionView.Context(question(true, "short", 4), 0, 1, 80, 14);
        var state = new QuestionState();
        assertEquals(13, ListQuestionView.preferredRows(context, state));

        // Growing the terminal widens the window to all 5 items: +2 rows for the 4th option,
        // +1 for the Other row. Nothing here is a preview tail.
        var tall = new ListQuestionView.Context(question(true, "short", 4), 0, 1, 80, 40);
        assertEquals(16, ListQuestionView.preferredRows(tall, state));
    }

    @Test
    void descriptionWrapsAtWordBoundariesLikeReleased236() {
        // Ink's default wrap="wrap" keeps long descriptions visible by wrapping instead of
        // clipping. Released 2.1.236 resolves it to Bun.wrapAnsi(..., {trim:false, hard:true}),
        // which keeps the trailing joining space on each wrapped line.
        List<String> lines = ListQuestionView.descriptionLines(
            "alpha beta gamma delta epsilon zeta", 12);
        assertEquals(List.of("alpha beta ", "gamma delta ", "epsilon zeta"), lines);
    }

    @Test
    void descriptionHardWrapsOverlongWords() {
        List<String> lines = ListQuestionView.descriptionLines("abcdefghijklmnop", 6);
        assertEquals(List.of("abcdef", "ghijkl", "mnop"), lines);
    }

    @Test
    void theStateRemembersAWindowStartTheViewCanRederive() {
        var state = new QuestionState();
        assertEquals(0, state.windowStart());
        state.setWindowStart(3);
        assertEquals(3, state.windowStart());
        state.setWindowStart(-1);
        assertEquals(0, state.windowStart(), "a negative start is meaningless");
        assertFalse(state.chatFocused());
        state.setChatFocused(true);
        assertTrue(state.chatFocused());
    }
}
