package com.claudecode.ui.lanterna.dialog;

import com.claudecode.tools.questions.QuestionPresenter;
import com.claudecode.ui.lanterna.dialog.question.QuestionOutcome;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import org.apache.commons.lang3.Strings;

/**
 * {@link AskUserQuestionDialog} interaction state machine, driven through
 * {@code handleKey} on a SameTextGUIThread virtual terminal: single-select via
 * Enter (empty/whitespace Other cancels), digits 1-9 addressing options by index
 * (preset submits, Other's digit focuses/submits), typing on a preset ignored;
 * multi-select 197 parity — Enter/Space/digits toggle the focused option, a
 * Submit/Next row records the question (its key focus still feeds the Other text),
 * the Other checkbox mirrors its text live; "Other" free text on a single placeholder
 * row ("Type something." / "Type something") with cursor-based editing (mid-text
 * insert/backspace/paste, Ctrl+A/E, Home/End/Delete, inverse-video cursor,
 * cursor-anchored scroll window), preview propagation, multi-question flow, and
 * Esc cancel.
 *
 * <p>Also covers the list card's focus walk: the option window shrinks with the terminal and puts
 * dim {@code ↑}/{@code ↓} on its edges, {@code ↑} from the first item wraps to the Other row while
 * downward never wraps (Other → Submit → {@code Chat about this}), and the chat row clarifies on
 * Enter or on its own digit.
 *
 * <p>Only a lone single-select question auto-submits ({@code I$c}); everything else
 * lands on {@code ReviewScreen} first, so those flows carry one closing Enter.
 * Questions routed to the design card have their own coverage in
 * {@code DesignQuestionViewTest}.
 */
class AskUserQuestionDialogTest {

    private static QuestionPresenter.Question q(String text, boolean multi,
                                                QuestionPresenter.Option... opts) {
        return new QuestionPresenter.Question(text, "Hdr", List.of(opts), multi);
    }

    private static QuestionPresenter.Option opt(String label, String preview) {
        return new QuestionPresenter.Option(label, "desc of " + label, preview);
    }

    /** Drives showAndWait on a worker thread; keys are fed through handleKey. */
    private static final class Harness {
        final AskUserQuestionDialog dialog = new AskUserQuestionDialog();
        final MultiWindowTextGUI gui;
        final CompletableFuture<QuestionOutcome> result = new CompletableFuture<>();

        Harness(List<QuestionPresenter.Question> questions) throws Exception {
            var term = new DefaultVirtualTerminal(new TerminalSize(100, 40));
            var screen = new TerminalScreen(term);
            screen.startScreen();
            gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
            Thread.ofVirtual().start(() ->
                result.complete(dialog.showAndWait(gui, questions, () -> {})));
            // SameTextGUIThread: invokeLater runs when the GUI thread processes —
            // pump until the dialog activates.
            long deadline = System.currentTimeMillis() + 2000;
            while (!dialog.isActive() && System.currentTimeMillis() < deadline) {
                gui.getGUIThread().processEventsAndUpdate();
                Thread.sleep(5);
            }
            assertTrue(dialog.isActive(), "dialog must activate");
        }

        void key(KeyStroke k) {
            dialog.handleKey(k, new AtomicBoolean(true));
        }

        void type(String s) {
            for (char c : s.toCharArray()) key(new KeyStroke(c, false, false));
        }

        /** The submitted answers, or null when the dialog was cancelled. */
        Map<String, QuestionPresenter.Answer> await() throws Exception {
            return result.get(2, TimeUnit.SECONDS) instanceof QuestionOutcome.Submitted submitted
                ? submitted.answers() : null;
        }

        QuestionOutcome outcome() throws Exception {
            return result.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void singleSelectEnterPicksFocusedOption() throws Exception {
        Harness h = new Harness(List.of(
            q("Pick one?", false, opt("Alpha", null), opt("Beta", "BETA-PREVIEW"))));
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // focus Beta
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("Beta", answers.get("Pick one?").answer());
        assertEquals("BETA-PREVIEW", answers.get("Pick one?").preview());
        assertNull(answers.get("Pick one?").notes());
        assertFalse(h.dialog.isActive());
    }

    @Test
    void multiSelectSpaceTogglesAndEnterSubmits() throws Exception {
        Harness h = new Harness(List.of(
            q("Pick many?", true, opt("A", null), opt("B", null), opt("C", null))));
        h.key(new KeyStroke(' ', false, false));    // toggle A
        h.key(new KeyStroke(KeyType.ARROW_DOWN));
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // focus C
        h.key(new KeyStroke(' ', false, false));    // toggle C
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Submit row
        h.key(new KeyStroke(KeyType.ENTER));        // records (197: only the Submit row does)
        h.key(new KeyStroke(KeyType.ENTER));        // review screen → Submit answers
        var answers = h.await();
        assertEquals("A, C", answers.get("Pick many?").answer());
    }

    @Test
    void enterTogglesInsteadOfSubmittingInMultiSelect() throws Exception {
        // 197 SelectMulti with a submit button: Enter on an option toggles it, exactly
        // like Space — submitting happens only from the Submit row.
        Harness h = new Harness(List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ENTER));        // toggle A on
        assertTrue(h.dialog.isActive(), "Enter must toggle, not submit");
        h.key(new KeyStroke(KeyType.ENTER));        // toggle A back off
        assertTrue(h.dialog.isActive(), "second Enter toggles off again");
        h.key(new KeyStroke(KeyType.ENTER));        // toggle A on
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wraps inside AjE → the Other row
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other → Submit row
        h.key(new KeyStroke(KeyType.ENTER));        // records the question
        h.key(new KeyStroke(KeyType.ENTER));        // review screen → Submit answers
        assertEquals("A", h.await().get("Q?").answer());
    }

    @Test
    void enterOnOtherInputDoesNotSubmitInMultiSelect() throws Exception {
        // 197: Enter inside the Other TextInput submits the input value (which
        // auto-selects Other), never the whole question.
        Harness h = new Harness(List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ARROW_DOWN));
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // focus Other
        h.type("自定义答案");
        h.key(new KeyStroke(KeyType.ENTER));        // re-affirms selection, stays open
        assertTrue(h.dialog.isActive(), "Enter on Other must not submit the question");
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Submit row
        h.key(new KeyStroke(KeyType.ENTER));
        h.key(new KeyStroke(KeyType.ENTER));        // review screen → Submit answers
        assertEquals("自定义答案", h.await().get("Q?").answer());
    }

    @Test
    void typingInOtherChecksItLiveAndClearingUnchecksIt() throws Exception {
        // 197 updateInputValue: the Other checkbox mirrors text non-emptiness on
        // every keystroke — no explicit select gesture exists.
        Rendered r = new Rendered(60, 30, List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        r.key(new KeyStroke(KeyType.ARROW_DOWN));
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // focus Other
        r.type("海边");
        assertTrue(Strings.CS.contains(r.render(), "[✓] 海边"),
            "typing must check Other immediately");
        r.key(new KeyStroke(KeyType.BACKSPACE));
        r.key(new KeyStroke(KeyType.BACKSPACE));
        assertTrue(Strings.CS.contains(r.render(), "[ ] 【T】ype something"),
            "clearing the text must uncheck Other and show the dimmed placeholder");
        r.close();
    }

    @Test
    void otherRowShows197PlaceholderPunctuationPerMode() throws Exception {
        // 197 QuestionView.tsx: the input placeholder is "Type something." (with a
        // period) for single-select and "Type something" for multi-select, rendered
        // dimmed in place of a label — there is no "Other" caption row.
        Rendered single = new Rendered(60, 30, List.of(
            q("Q?", false, opt("A", null), opt("B", null))));
        single.key(new KeyStroke(KeyType.ARROW_UP));    // focus Other
        String singleScreen = single.render();
        assertTrue(Strings.CS.contains(singleScreen, "【T】ype something."),
            "single-select placeholder keeps its period, cursor inverts its first char; "
                + "screen was:\n" + singleScreen);
        assertFalse(Strings.CS.contains(singleScreen, "Other"), "no Other label row in 197");
        single.close();

        Rendered multi = new Rendered(60, 30, List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        multi.key(new KeyStroke(KeyType.ARROW_DOWN));
        multi.key(new KeyStroke(KeyType.ARROW_DOWN));   // focus Other
        String multiScreen = multi.render();
        assertTrue(Strings.CS.contains(multiScreen, "[ ] 【T】ype something"),
            "multi-select placeholder has no period and sits after the checkbox; "
                + "screen was:\n" + multiScreen);
        assertFalse(Strings.CS.contains(multiScreen, "Type something."),
            "multi placeholder must drop the period");
        multi.close();
    }

    @Test
    void submitRowShowsNextThenSubmitAcrossQuestions() throws Exception {
        Rendered r = new Rendered(60, 30, List.of(
            q("First?", true, opt("F1", null), opt("F2", null)),
            q("Second?", true, opt("S1", null), opt("S2", null))));
        assertTrue(Strings.CS.contains(r.render(), "Next"), "non-last question shows Next");
        r.key(new KeyStroke(KeyType.ARROW_UP));     // F1 wraps to the Other row
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other → Submit row
        String focused = r.render();
        assertTrue(Strings.CS.contains(focused, "❯    Next"), "submit row takes the pointer");
        r.key(new KeyStroke(KeyType.ENTER));        // nothing selected → ignored
        assertTrue(r.dialog.isActive(), "empty submit must be ignored");
        r.key(new KeyStroke(KeyType.ARROW_UP));     // Submit → Other
        r.key(new KeyStroke(KeyType.ARROW_UP));     // Other → F2
        r.key(new KeyStroke(KeyType.ARROW_UP));     // F2 → F1
        r.key(new KeyStroke(KeyType.ENTER));        // toggle F1
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // F2
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // Submit row
        r.key(new KeyStroke(KeyType.ENTER));        // advances to question 2
        assertTrue(Strings.CS.contains(r.render(), "Submit"), "last question shows Submit");
        r.close();
    }

    @Test
    void otherFreeTextBecomesAnswer() throws Exception {
        Harness h = new Harness(List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wrap to Other (index 2)
        h.type("custom answer");
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("custom answer", answers.get("Which?").answer());
    }

    @Test
    void bracketedPasteAppendsToOtherText() throws Exception {
        Harness h = new Harness(List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wrap to Other (index 2)
        h.key(new PasteKeyStroke("pasted content"));
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("pasted content", answers.get("Which?").answer());
    }

    @Test
    void bracketedPasteNormalizesNewlinesToSpaces() throws Exception {
        Harness h = new Harness(List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wrap to Other (index 2)
        h.key(new PasteKeyStroke("line one\r\nline two\nline three"));
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("line one line two line three", answers.get("Which?").answer());
    }

    @Test
    void aPlainListCardNeverProducesNotesFromItsLeftoverOtherText() throws Exception {
        // zys / yCf gate annotations on the design predicate (!multiSelect && some preview), so a
        // plain list card's leftover Other buffer is dropped rather than promoted to notes.
        Harness h = new Harness(List.of(
            q("Choose?", false, opt("Preset", null), opt("Alt", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // focus Other (index 2)
        h.key(new PasteKeyStroke("pasted notes"));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // Alt
        h.key(new KeyStroke(KeyType.ARROW_UP));     // Preset
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("Preset", answers.get("Choose?").answer());
        assertNull(answers.get("Choose?").notes());
    }

    // ── cursor-based editing of the Other free text (197 TextInput parity) ──

    private static KeyStroke ctrl(char c) {
        return new KeyStroke(c, true, false);
    }

    @Test
    void arrowKeysInsertMidTextOnOther() throws Exception {
        Harness h = new Harness(List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // focus Other
        h.type("helo");
        h.key(new KeyStroke(KeyType.ARROW_LEFT));
        h.type("l");
        h.key(new KeyStroke(KeyType.ENTER));
        assertEquals("hello", h.await().get("Which?").answer());
    }

    @Test
    void backspaceDeletesBeforeCursorMidText() throws Exception {
        Harness h = new Harness(List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));
        h.type("abc");
        h.key(new KeyStroke(KeyType.ARROW_LEFT));   // cursor between b and c
        h.key(new KeyStroke(KeyType.BACKSPACE));    // deletes b
        h.key(new KeyStroke(KeyType.ENTER));
        assertEquals("ac", h.await().get("Which?").answer());
    }

    @Test
    void ctrlAMovesCursorToStartAndCtrlEToEnd() throws Exception {
        Harness h = new Harness(List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));
        h.type("world");
        h.key(ctrl('a'));
        h.type("hello ");
        h.key(ctrl('e'));
        h.type("!");
        h.key(new KeyStroke(KeyType.ENTER));
        assertEquals("hello world!", h.await().get("Which?").answer());
    }

    @Test
    void homeEndAndDeleteKeysEditAtCursor() throws Exception {
        Harness h = new Harness(List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));
        h.type("abc");
        h.key(new KeyStroke(KeyType.HOME));
        h.key(new KeyStroke(KeyType.DELETE));       // forward-delete a
        h.key(new KeyStroke(KeyType.END));
        h.type("z");
        h.key(new KeyStroke(KeyType.ENTER));
        assertEquals("bcz", h.await().get("Which?").answer());
    }

    @Test
    void pasteInsertsAtCursorMidText() throws Exception {
        Harness h = new Harness(List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));
        h.type("ad");
        h.key(new KeyStroke(KeyType.ARROW_LEFT));
        h.key(new PasteKeyStroke("bc"));
        h.key(new KeyStroke(KeyType.ENTER));
        assertEquals("abcd", h.await().get("Which?").answer());
    }

    @Test
    void arrowLeftOnOtherMovesCursorInsteadOfSwitchingQuestion() throws Exception {
        Harness h = new Harness(List.of(
            q("First?", false, opt("F1", null), opt("F2", null)),
            q("Second?", false, opt("S1", null), opt("S2", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // focus Other on question 1
        h.type("ab");
        h.key(new KeyStroke(KeyType.ARROW_LEFT));   // must move cursor, not switch question
        h.type("X");
        h.key(new KeyStroke(KeyType.ENTER));        // records Other for First?, advances
        h.key(new KeyStroke(KeyType.ENTER));        // S1 → advances to the review screen
        h.key(new KeyStroke(KeyType.ENTER));        // Submit answers
        var answers = h.await();
        assertEquals("aXb", answers.get("First?").answer());
        assertEquals("S1", answers.get("Second?").answer());
    }

    @Test
    void arrowKeysStillSwitchQuestionsWhenOtherNotFocused() throws Exception {
        Harness h = new Harness(List.of(
            q("First?", false, opt("F1", null), opt("F2", null)),
            q("Second?", false, opt("S1", null), opt("S2", null))));
        h.key(new KeyStroke(KeyType.ARROW_RIGHT));  // question 2
        h.key(new KeyStroke(KeyType.ARROW_DOWN));
        h.key(new KeyStroke(KeyType.ENTER));        // S2 → review screen
        h.key(new KeyStroke(KeyType.ENTER));        // Submit answers
        var answers = h.await();
        // zys only carries answered questions; First? was never touched.
        assertFalse(answers.containsKey("First?"));
        assertEquals("S2", answers.get("Second?").answer());
    }

    @Test
    void theTabIndexClampsAtBothEndsInsteadOfWrapping() throws Exception {
        // p2g: prev stops at 0 and next stops at the Submit tab — the 197 list card's
        // wrapping ←/→ was never the bundle's behaviour.
        Harness h = new Harness(List.of(
            q("First?", false, opt("F1", null), opt("F2", null)),
            q("Second?", false, opt("S1", null), opt("S2", null))));
        h.key(new KeyStroke(KeyType.ARROW_LEFT));   // already on question 1 → stays
        h.key(new KeyStroke(KeyType.ENTER));        // F1, advances to question 2
        h.key(new KeyStroke(KeyType.ARROW_RIGHT));  // review screen
        h.key(new KeyStroke(KeyType.ARROW_RIGHT));  // clamped — still the review screen
        h.key(new KeyStroke(KeyType.ENTER));        // Submit answers
        var answers = h.await();
        assertEquals("F1", answers.get("First?").answer());
        assertFalse(answers.containsKey("Second?"));
    }

    /** Mounts the dialog in a real FULL_SCREEN window on a virtual terminal. */
    private static final class Rendered {
        final DefaultVirtualTerminal term;
        final MultiWindowTextGUI gui;
        final AskUserQuestionDialog dialog = new AskUserQuestionDialog();
        final CompletableFuture<QuestionOutcome> result = new CompletableFuture<>();

        Rendered(int columns, int rows, List<QuestionPresenter.Question> questions)
                throws Exception {
            term = new DefaultVirtualTerminal(new TerminalSize(columns, rows));
            var screen = new TerminalScreen(term);
            screen.startScreen();
            gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
            dialog.setTerminalColumnsSupplier(() -> columns);
            // NZr sizes the option window from the terminal height, so the harness must report the
            // virtual terminal's rows rather than leave the 40-row default in place.
            dialog.setTerminalRowsSupplier(() -> rows);
            var window = new BasicWindow();
            window.setHints(Set.of(
                Window.Hint.FULL_SCREEN,
                Window.Hint.NO_DECORATIONS,
                Window.Hint.FIT_TERMINAL_WINDOW));
            window.setComponent(dialog);
            gui.addWindow(window);
            Thread.ofVirtual().start(() ->
                result.complete(dialog.showAndWait(gui, questions, () -> {})));
            long deadline = System.currentTimeMillis() + 2000;
            while (!dialog.isActive() && System.currentTimeMillis() < deadline) {
                gui.getGUIThread().processEventsAndUpdate();
                Thread.sleep(5);
            }
            assertTrue(dialog.isActive(), "dialog must activate");
        }

        void key(KeyStroke k) {
            dialog.handleKey(k, new AtomicBoolean(true));
        }

        void type(String s) {
            for (char c : s.toCharArray()) key(new KeyStroke(c, false, false));
        }

        String render() throws IOException {
            gui.getGUIThread().processEventsAndUpdate();
            return screenText(term, term.getTerminalSize().getColumns());
        }

        void close() throws Exception {
            key(new KeyStroke(KeyType.ESCAPE));
            assertInstanceOf(QuestionOutcome.Cancelled.class, result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void typedTailStaysVisibleWhenTextExceedsTerminalWidth() throws Exception {
        // Render-level: type past the visible width — the scroll window must keep the
        // typed tail and cursor on screen (the old prefix-clip hid everything past
        // the right edge).
        Rendered r = new Rendered(40, 30, List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        r.key(new KeyStroke(KeyType.ARROW_UP));
        r.type("abcdefghijklmnopqrstuvwxyz0123456789AB"); // 38 chars > 33 visible

        String rendered = r.render();
        assertTrue(Strings.CS.contains(rendered, "fghijklmnopqrstuvwxyz0123456789AB【 】"),
            "typed tail must stay visible with an inverse-blank cursor; screen was:\n"
                + rendered);
        assertFalse(Strings.CS.contains(rendered, "abcde"),
            "scrolled-off prefix must not be drawn; screen was:\n" + rendered);

        // Backspace at the tail must visibly delete the last character
        r.key(new KeyStroke(KeyType.BACKSPACE));
        String afterBackspace = r.render();
        assertTrue(Strings.CS.contains(afterBackspace, "efghijklmnopqrstuvwxyz0123456789A【 】"),
            "backspace deletion must be visible; screen was:\n" + afterBackspace);
        r.close();
    }

    @Test
    void midTextCursorIsInverseVideoWithoutShiftingTheTail() throws Exception {
        // User-reported: a glyph cursor (▏) inserted mid-text shows a phantom space
        // before the following character (East Asian ambiguous width). The cursor
        // must be inverse video on the character at the insertion point instead.
        Rendered r = new Rendered(40, 30, List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        r.key(new KeyStroke(KeyType.ARROW_UP));
        r.type("459436");
        r.key(new KeyStroke(KeyType.ARROW_LEFT));   // cursor between 3 and 6
        String rendered = r.render();
        assertTrue(Strings.CS.contains(rendered, "45943【6】"),
            "mid-text cursor must invert the character under it; screen was:\n" + rendered);
        assertFalse(Strings.CS.contains(rendered, "45943 6"),
            "no phantom space may appear before the tail; screen was:\n" + rendered);
        r.close();
    }

    @Test
    void typedCjkTailStaysVisibleWhenTextExceedsTerminalWidth() throws Exception {
        // Same scroll-window guarantee for double-width input: 20 CJK chars occupy
        // 40 columns — more than the whole 40-column terminal row leaves for text.
        Rendered r = new Rendered(40, 30, List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        r.key(new KeyStroke(KeyType.ARROW_UP));
        r.type("天地玄黄宇宙洪荒日月盈昃辰宿列张律吕调阳"); // 20 chars = 40 columns

        String rendered = r.render();
        assertTrue(Strings.CS.contains(rendered, "列张律吕调阳【 】"),
            "CJK typed tail must stay visible with an inverse-blank cursor; screen was:\n"
                + rendered);
        assertFalse(Strings.CS.contains(rendered, "天地玄"),
            "scrolled-off prefix must not be drawn; screen was:\n" + rendered);

        r.key(new KeyStroke(KeyType.BACKSPACE));
        String afterBackspace = r.render();
        assertTrue(Strings.CS.contains(afterBackspace, "列张律吕调【 】"),
            "backspace deletion must be visible; screen was:\n" + afterBackspace);
        assertFalse(Strings.CS.contains(afterBackspace, "调阳"),
            "deleted char must be gone; screen was:\n" + afterBackspace);
        r.close();
    }

    @Test
    void theOptionWindowShrinksAndShowsEdgeArrowsInAShortTerminal() throws Exception {
        // NZr: a 14-row terminal leaves floor((14-8)/2) = 3 of the 5 AjE items on screen, and the
        // window edges take iRl's dim arrows while more items sit beyond them.
        Rendered r = new Rendered(40, 14, List.of(q("Where to?", true,
            new QuestionPresenter.Option("Alpha", "desc", null),
            new QuestionPresenter.Option("Bravo", "desc", null),
            new QuestionPresenter.Option("Charlie", "desc", null),
            new QuestionPresenter.Option("Delta", "desc", null))));

        String top = r.render();
        assertTrue(Strings.CS.contains(top, "❯ 1. [ ] Alpha"), "focus pointer on item 1");
        assertTrue(Strings.CS.contains(top, "↓ 3. [ ] Charlie"),
            "last visible row advertises more below; screen was:\n" + top);
        assertFalse(Strings.CS.contains(top, "Delta"),
            "the 4th item is outside the window; screen was:\n" + top);

        r.key(new KeyStroke(KeyType.ARROW_DOWN));
        r.key(new KeyStroke(KeyType.ARROW_DOWN));
        r.key(new KeyStroke(KeyType.ARROW_DOWN));    // focus Delta → window scrolls by one
        String scrolled = r.render();
        assertTrue(Strings.CS.contains(scrolled, "↑ 2. [ ] Bravo"),
            "first visible row advertises more above; screen was:\n" + scrolled);
        assertTrue(Strings.CS.contains(scrolled, "❯ 4. [ ] Delta"),
            "the focused row keeps the pointer over the down arrow; screen was:\n" + scrolled);
        assertFalse(Strings.CS.contains(scrolled, "Alpha"),
            "the scrolled-off item must not be drawn; screen was:\n" + scrolled);

        r.key(new KeyStroke(KeyType.ARROW_DOWN));    // focus the Other row — the last AjE item
        String onOther = r.render();
        assertTrue(Strings.CS.contains(onOther, "❯ 5. [ ] 【T】ype something"),
            "Other is reachable as the last window item; screen was:\n" + onOther);
        assertTrue(Strings.CS.contains(onOther, "↑ 3. [ ] Charlie"),
            "the window moved on by one and still advertises more above; screen was:\n" + onOther);
        assertFalse(Strings.CS.contains(onOther, "Bravo"),
            "Bravo scrolled out of the window; screen was:\n" + onOther);
        r.close();
    }

    @Test
    void theFooterSurvivesACardTallerThanItsAssignedHeight() throws Exception {
        // Descriptions wrap to more rows than NZr's per-item estimate, so even a windowed card can
        // outgrow the overlay. Ink would show the terminal tail; anchoring on the tail keeps the
        // rule, the Chat about this row and the chord hint — the ways out — on screen.
        String desc = "去郊区森林公园走一条八公里左右的环线步道，沿途有溪流和开阔山顶草甸，天气好能看到天际线";
        Rendered r = new Rendered(40, 12, List.of(q("周末想去哪里玩？", true,
            new QuestionPresenter.Option("山野徒步", desc, null),
            new QuestionPresenter.Option("城市美术馆", desc, null),
            new QuestionPresenter.Option("夜市美食", desc, null),
            new QuestionPresenter.Option("短途露营", desc, null))));

        String initial = r.render();
        assertTrue(Strings.CS.contains(initial, "6. Chat about this"),
            "the chat row must never be the clipped part; screen was:\n" + initial);
        assertTrue(Strings.CS.contains(initial, "Enter to select"),
            "the chord hint must stay visible; screen was:\n" + initial);
        assertTrue(Strings.CS.contains(initial, "     Submit"),
            "the submit row sits outside the window and stays visible; screen was:\n" + initial);

        // the window holds 2 items here, so Other is two rows down
        r.key(new KeyStroke(KeyType.ARROW_UP));     // wraps straight onto the Other row
        String onOther = r.render();
        assertTrue(Strings.CS.contains(onOther, "【T】ype something"),
            "Other input row must be reachable and visible (dimmed placeholder, "
                + "inverse cursor on its first char); screen was:\n" + onOther);
        r.type("想去海边");
        assertTrue(Strings.CS.contains(r.render(), "想去海边【 】"),
            "typed text on Other must be visible at the bottom");
        r.close();
    }

    private static String screenText(DefaultVirtualTerminal term, int columns) {
        StringBuilder sb = new StringBuilder();
        TerminalSize size = term.getTerminalSize();
        for (int row = 0; row < size.getRows(); row++) {
            for (int col = 0; col < Math.min(columns, size.getColumns()); col++) {
                var cell = term.getCharacter(col, row);
                char ch = cell.getCharacter();
                boolean reversed = cell.getModifiers().contains(
                    SGR.REVERSE);
                if (reversed) sb.append('【');
                sb.append(ch);
                if (reversed) sb.append('】');
                // a double-width char occupies two cells reporting the same glyph
                if (TerminalTextUtils.isCharDoubleWidth(ch)) col++;
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void preferredSizeGrowsWithWrappedDescriptionsInNarrowTerminal() throws Exception {
        Harness h = new Harness(List.of(q("Pick?", false,
            new QuestionPresenter.Option("A",
                "alpha beta gamma delta epsilon zeta eta theta iota kappa", null))));
        h.dialog.setTerminalColumnsSupplier(() -> 80);
        int wideRows = h.dialog.calculatePreferredSize().getRows();
        h.dialog.setTerminalColumnsSupplier(() -> 20);
        int narrowRows = h.dialog.calculatePreferredSize().getRows();
        assertTrue(narrowRows > wideRows,
            "narrow terminal must grow rows for wrapped descriptions, got wide="
                + wideRows + " narrow=" + narrowRows);
        h.key(new KeyStroke(KeyType.ESCAPE));
        h.await();
    }

    @Test
    void aDesignCardPromotesItsNotesBufferIntoTheAnswer() throws Exception {
        // The mirror image of the plain list card: the design predicate holds, so the notes
        // editor's buffer rides along with the chosen option.
        Harness h = new Harness(List.of(
            q("Choose?", false, opt("Preset", "PRESET-PREVIEW"), opt("Alt", null))));
        h.type("n");                                // open the notes editor
        h.type("some context");
        h.key(new KeyStroke(KeyType.ESCAPE));       // leave the editor, keep the buffer
        h.key(new KeyStroke(KeyType.ENTER));        // choose Preset — a lone card auto-submits
        var answers = h.await();
        assertEquals("Preset", answers.get("Choose?").answer());
        assertEquals("some context", answers.get("Choose?").notes());
        assertEquals("PRESET-PREVIEW", answers.get("Choose?").preview());
    }

    @Test
    void typingOnPresetOptionIsIgnoredSingle() throws Exception {
        // 197 use-select-input: outside the input row, character keys are no-ops.
        Harness h = new Harness(List.of(
            q("Choose?", false, opt("Preset", null), opt("Alt", null))));
        h.type("ignored");                          // focus on Preset — nowhere to type
        h.key(new KeyStroke(KeyType.ENTER));
        var answer = h.await().get("Choose?");
        assertEquals("Preset", answer.answer());
        assertNull(answer.notes(), "typing on a preset must not create notes");
    }

    @Test
    void enterOnEmptyOtherCancelsSingleSelect() throws Exception {
        // 197 select-input-option onSubmit: empty input value → onCancel — Enter on an
        // untouched Other cancels the whole dialog (verified against the bundle).
        Harness h = new Harness(List.of(
            q("Q?", false, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // focus Other
        h.key(new KeyStroke(KeyType.ENTER));
        assertNull(h.await(), "Enter on an empty Other must cancel the dialog");
        assertFalse(h.dialog.isActive());
    }

    @Test
    void enterOnWhitespaceOnlyOtherCancelsSingleSelect() throws Exception {
        // 197 trims the input value before the empty check.
        Harness h = new Harness(List.of(
            q("Q?", false, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));
        h.type("   ");
        h.key(new KeyStroke(KeyType.ENTER));
        assertNull(h.await(), "whitespace-only Other must cancel like an empty one");
    }

    @Test
    void digitSelectsPresetImmediatelySingle() throws Exception {
        // 197 use-select-input digits branch: 1-9 address options by visible index.
        Harness h = new Harness(List.of(
            q("Q?", false, opt("A", null), opt("B", null))));
        h.key(new KeyStroke('2', false, false));    // digit 2 = B → submits right away
        assertEquals("B", h.await().get("Q?").answer());
    }

    @Test
    void digitFocusesOtherThenSubmitsWhenPrefilledSingle() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", false, opt("A", null), opt("B", null))));
        h.key(new KeyStroke('3', false, false));    // Other's own digit: focuses the input
        assertTrue(h.dialog.isActive(), "empty Other's digit must focus, not submit");
        h.type("xyz");
        h.key(new KeyStroke(KeyType.ARROW_UP));     // leave the input (focus B)
        h.key(new KeyStroke('3', false, false));    // pre-filled Other submits on its digit
        assertEquals("xyz", h.await().get("Q?").answer());
    }

    @Test
    void digitTogglesPresetInMultiSelect() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", true, opt("A", null), opt("B", null), opt("C", null))));
        h.key(new KeyStroke('2', false, false));    // toggle B
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wraps to the Other row
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other → Submit row
        h.key(new KeyStroke(KeyType.ENTER));
        h.key(new KeyStroke(KeyType.ENTER));        // review screen → Submit answers
        assertEquals("B", h.await().get("Q?").answer());
    }

    @Test
    void typingOnSubmitRowEditsOtherTextInMultiSelect() throws Exception {
        // 197 isInInput parity: the Submit row keeps the input's key focus, so
        // characters typed while it is highlighted still land in the Other text.
        Harness h = new Harness(List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wraps to the Other row
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other → Submit row
        h.type("typed from submit");
        h.key(new KeyStroke(KeyType.ENTER));        // Other text auto-selected → records
        h.key(new KeyStroke(KeyType.ENTER));        // review screen → Submit answers
        assertEquals("typed from submit", h.await().get("Q?").answer());
    }

    @Test
    void multiQuestionFlowCollectsAll() throws Exception {
        Harness h = new Harness(List.of(
            q("First?", false, opt("F1", null), opt("F2", null)),
            q("Second?", false, opt("S1", null), opt("S2", null))));
        h.key(new KeyStroke(KeyType.ENTER));        // F1 → advances
        assertTrue(h.dialog.isActive(), "still active on question 2");
        h.key(new KeyStroke(KeyType.ARROW_DOWN));
        h.key(new KeyStroke(KeyType.ENTER));        // S2 → review screen
        h.key(new KeyStroke(KeyType.ENTER));        // Submit answers
        var answers = h.await();
        assertEquals("F1", answers.get("First?").answer());
        assertEquals("S2", answers.get("Second?").answer());
    }

    @Test
    void escapeCancelsWithNull() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", false, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ESCAPE));
        assertNull(h.await());
        assertFalse(h.dialog.isActive());
    }

    // ── the Chat about this row (C51) and the non-wrapping downward walk ─────

    @Test
    void downFromTheLastItemWalksIntoSubmitThenChatWithoutWrapping() throws Exception {
        Rendered r = new Rendered(60, 30, List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // B
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other — the last AjE item
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // Submit row
        assertTrue(Strings.CS.contains(r.render(), "❯    Submit"),
            "down from the last item lands on Submit, not back on option 1");
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // Chat about this
        String onChat = r.render();
        assertTrue(Strings.CS.contains(onChat, "❯ 4. Chat about this"),
            "down from Submit lands on the chat row; screen was:\n" + onChat);
        assertFalse(Strings.CS.contains(onChat, "❯ 1. "),
            "the pointer leaves the select entirely; screen was:\n" + onChat);
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // nowhere left to go
        assertTrue(Strings.CS.contains(r.render(), "❯ 4. Chat about this"),
            "the chat row is the end of the walk — it never wraps to the top");
        r.key(new KeyStroke(KeyType.ARROW_UP));     // back into the select, focus untouched
        assertTrue(Strings.CS.contains(r.render(), "❯    Submit"),
            "up from the chat row restores the selector focus where it was");
        r.close();
    }

    @Test
    void singleSelectDownFromTheLastItemSkipsStraightToTheChatRow() throws Exception {
        Rendered r = new Rendered(60, 30, List.of(
            q("Q?", false, opt("A", null), opt("B", null))));
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // B
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // no Submit row → the chat row
        assertTrue(Strings.CS.contains(r.render(), "❯ 4. Chat about this"),
            "a single-select card has no Submit row between Other and the chat row");
        r.close();
    }

    @Test
    void enterOnTheChatRowClarifiesInsteadOfAnswering() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", false, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // B
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // chat row
        h.key(new KeyStroke(KeyType.ENTER));
        var clarify = assertInstanceOf(QuestionOutcome.Clarify.class, h.outcome());
        assertTrue(Strings.CS.contains(clarify.feedback(), "Q?"),
            "the clarification feedback carries the question; was:\n" + clarify.feedback());
        assertFalse(h.dialog.isActive());
    }

    @Test
    void theChatRowsOwnDigitClarifiesFromAnywhereOnTheCard() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", false, opt("A", null), opt("B", null))));
        h.key(new KeyStroke('4', false, false));    // options.length + 2 — the chat row
        assertInstanceOf(QuestionOutcome.Clarify.class, h.outcome());
        assertFalse(h.dialog.isActive());
    }

    @Test
    void remoteResolutionCancelsAndUnblocksTheLocalDialog() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", false, opt("A", null), opt("B", null))));

        h.gui.getGUIThread().invokeLater(h.dialog::cancelPending);
        long deadline = System.currentTimeMillis() + 2000;
        while (h.dialog.isActive() && System.currentTimeMillis() < deadline) {
            h.gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }

        assertNull(h.await());
        assertFalse(h.dialog.isActive());
    }

    @Test
    void remoteResolutionBeforeMountSkipsTheQuestionDialog() throws Exception {
        var term = new DefaultVirtualTerminal(new TerminalSize(100, 40));
        var screen = new TerminalScreen(term);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        var dialog = new AskUserQuestionDialog();
        var cancelled = new AtomicBoolean(true);
        var result = new CompletableFuture<QuestionOutcome>();

        Thread.ofVirtual().start(() -> result.complete(dialog.showAndWait(gui,
            List.of(q("Q?", false, opt("A", null), opt("B", null))), () -> {},
            cancelled::get)));

        long deadline = System.currentTimeMillis() + 2000;
        while (!result.isDone() && System.currentTimeMillis() < deadline) {
            gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }

        assertInstanceOf(QuestionOutcome.Cancelled.class, result.get(2, TimeUnit.SECONDS));
        assertFalse(dialog.isActive());
    }

    @Test
    void enterWithoutChoiceIsIgnored() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wraps to the Other row
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other → Submit row
        h.key(new KeyStroke(KeyType.ENTER));        // nothing selected → ignored
        assertTrue(h.dialog.isActive(), "empty multi-select submit must be ignored");
        h.key(new KeyStroke(KeyType.ARROW_UP));     // Submit → Other
        h.key(new KeyStroke(KeyType.ARROW_UP));     // Other → B
        h.key(new KeyStroke(KeyType.ARROW_UP));     // B → A
        h.key(new KeyStroke(' ', false, false));    // toggle A
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wraps to the Other row
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Other → Submit row
        h.key(new KeyStroke(KeyType.ENTER));
        h.key(new KeyStroke(KeyType.ENTER));        // review screen → Submit answers
        assertEquals("A", h.await().get("Q?").answer());
    }
}
