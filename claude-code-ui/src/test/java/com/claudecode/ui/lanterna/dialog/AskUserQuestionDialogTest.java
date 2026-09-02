package com.claudecode.ui.lanterna.dialog;

import com.claudecode.tools.questions.QuestionPresenter;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AskUserQuestionDialog} interaction state machine, driven through
 * {@code handleKey} on a SameTextGUIThread virtual terminal: single-select via
 * Enter (empty/whitespace Other cancels), digits 1-9 addressing options by index
 * (preset submits, Other's digit focuses/submits), typing on a preset ignored;
 * multi-select 197 parity — Enter/Space/digits toggle the focused option, a
 * Submit/Next row submits (its key focus still feeds the Other text), the Other
 * checkbox mirrors its text live; "Other" free text on a single placeholder row
 * ("Type something." / "Type something") with cursor-based editing (mid-text
 * insert/backspace/paste, Ctrl+A/E, Home/End/Delete, inverse-video cursor,
 * cursor-anchored scroll window), notes from leftover Other text on a preset
 * selection, preview propagation, multi-question flow, and Esc cancel.
 */
class AskUserQuestionDialogTest {

    @Test
    void selectionGlyphsMatchClaudeCode197() {
        assertEquals("[ ]", AskUserQuestionDialog.multiSelectMarker(false));
        assertEquals("[✓]", AskUserQuestionDialog.multiSelectMarker(true));
        assertEquals("1. ", AskUserQuestionDialog.optionIndex(0, 4));
        assertEquals("10. ", AskUserQuestionDialog.optionIndex(9, 12));
        assertEquals(" 1. ", AskUserQuestionDialog.optionIndex(0, 12));
    }

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
        final CompletableFuture<Map<String, QuestionPresenter.Answer>> result =
            new CompletableFuture<>();

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

        Map<String, QuestionPresenter.Answer> await() throws Exception {
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
        h.key(new KeyStroke(KeyType.ENTER));        // submits (197: only the Submit row does)
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
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wrap to Submit row
        h.key(new KeyStroke(KeyType.ENTER));        // submits
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
        assertTrue(r.render().contains("[✓] 海边"),
            "typing must check Other immediately");
        r.key(new KeyStroke(KeyType.BACKSPACE));
        r.key(new KeyStroke(KeyType.BACKSPACE));
        assertTrue(r.render().contains("[ ] 【T】ype something"),
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
        assertTrue(singleScreen.contains("【T】ype something."),
            "single-select placeholder keeps its period, cursor inverts its first char; "
                + "screen was:\n" + singleScreen);
        assertFalse(singleScreen.contains("Other"), "no Other label row in 197");
        single.close();

        Rendered multi = new Rendered(60, 30, List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        multi.key(new KeyStroke(KeyType.ARROW_DOWN));
        multi.key(new KeyStroke(KeyType.ARROW_DOWN));   // focus Other
        String multiScreen = multi.render();
        assertTrue(multiScreen.contains("[ ] 【T】ype something"),
            "multi-select placeholder has no period and sits after the checkbox; "
                + "screen was:\n" + multiScreen);
        assertFalse(multiScreen.contains("Type something."),
            "multi placeholder must drop the period");
        multi.close();
    }

    @Test
    void submitRowShowsNextThenSubmitAcrossQuestions() throws Exception {
        Rendered r = new Rendered(60, 30, List.of(
            q("First?", true, opt("F1", null), opt("F2", null)),
            q("Second?", true, opt("S1", null), opt("S2", null))));
        assertTrue(r.render().contains("Next"), "non-last question shows Next");
        r.key(new KeyStroke(KeyType.ARROW_UP));     // wrap to Submit row
        String focused = r.render();
        assertTrue(focused.contains("❯    Next"), "submit row takes the pointer");
        r.key(new KeyStroke(KeyType.ENTER));        // nothing selected → ignored
        assertTrue(r.dialog.isActive(), "empty submit must be ignored");
        r.key(new KeyStroke(KeyType.ARROW_DOWN));   // wrap to F1
        r.key(new KeyStroke(KeyType.ENTER));        // toggle F1
        r.key(new KeyStroke(KeyType.ARROW_UP));     // back to Submit row
        r.key(new KeyStroke(KeyType.ENTER));        // advances to question 2
        assertTrue(r.render().contains("Submit"), "last question shows Submit");
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
    void bracketedPasteOnPresetSelectionBecomesNotes() throws Exception {
        // 197 flow: text can only be entered on the Other row; a leftover buffer
        // becomes notes when a preset option is submitted.
        Harness h = new Harness(List.of(
            q("Choose?", false, opt("Preset", null), opt("Alt", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // focus Other (index 2)
        h.key(new PasteKeyStroke("pasted notes"));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // Alt
        h.key(new KeyStroke(KeyType.ARROW_UP));     // Preset
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("Preset", answers.get("Choose?").answer());
        assertEquals("pasted notes", answers.get("Choose?").notes());
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
        h.key(new KeyStroke(KeyType.ENTER));        // S1 → submits
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
        h.key(new KeyStroke(KeyType.ENTER));        // S2 → submits; First? stays unanswered
        var answers = h.await();
        assertEquals("", answers.get("First?").answer());
        assertEquals("S2", answers.get("Second?").answer());
    }

    @Test
    void textWindowKeepsCursorVisibleInLongText() {
        // short text: everything visible
        var shortWindow = AskUserQuestionDialog.textWindow("abc", 3, 10);
        assertEquals(0, shortWindow.start());
        assertEquals("abc", shortWindow.visible());
        assertEquals(3, shortWindow.cursorColumn());

        // long pasted text, cursor at end (post-paste state): tail visible, not the prefix
        var end = AskUserQuestionDialog.textWindow("0123456789", 10, 5);
        assertEquals("56789", end.visible());
        assertEquals(5, end.cursorColumn());

        // Ctrl+A: window snaps back to the start
        var home = AskUserQuestionDialog.textWindow("0123456789", 0, 5);
        assertEquals("01234", home.visible());
        assertEquals(0, home.cursorColumn());

        // mid-text backspace target stays on screen
        var mid = AskUserQuestionDialog.textWindow("0123456789", 8, 5);
        assertEquals("45678", mid.visible());
        assertEquals(4, mid.cursorColumn());

        // double-width (CJK) text: the window measures display columns, not chars —
        // 10 chars = 20 columns, so a 6-column window shows only the last 3 chars
        var cjk = AskUserQuestionDialog.textWindow("一二三四五六七八九十", 10, 6);
        assertEquals("八九十", cjk.visible());
        assertEquals(3, cjk.cursorColumn());

        // mixed narrow/wide text scrolls on column boundaries
        var mixed = AskUserQuestionDialog.textWindow("ab天地cd", 4, 6);
        assertEquals("b天地c", mixed.visible());
        assertEquals(3, mixed.cursorColumn());
    }

    /** Mounts the dialog in a real FULL_SCREEN window on a virtual terminal. */
    private static final class Rendered {
        final DefaultVirtualTerminal term;
        final MultiWindowTextGUI gui;
        final AskUserQuestionDialog dialog = new AskUserQuestionDialog();
        final CompletableFuture<Map<String, QuestionPresenter.Answer>> result =
            new CompletableFuture<>();

        Rendered(int columns, int rows, List<QuestionPresenter.Question> questions)
                throws Exception {
            term = new DefaultVirtualTerminal(new TerminalSize(columns, rows));
            var screen = new TerminalScreen(term);
            screen.startScreen();
            gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
            dialog.setTerminalColumnsSupplier(() -> columns);
            var window = new com.googlecode.lanterna.gui2.BasicWindow();
            window.setHints(java.util.Set.of(
                com.googlecode.lanterna.gui2.Window.Hint.FULL_SCREEN,
                com.googlecode.lanterna.gui2.Window.Hint.NO_DECORATIONS,
                com.googlecode.lanterna.gui2.Window.Hint.FIT_TERMINAL_WINDOW));
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

        String render() throws java.io.IOException {
            gui.getGUIThread().processEventsAndUpdate();
            return screenText(term, term.getTerminalSize().getColumns());
        }

        void close() throws Exception {
            key(new KeyStroke(KeyType.ESCAPE));
            assertNull(result.get(2, TimeUnit.SECONDS));
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
        assertTrue(rendered.contains("fghijklmnopqrstuvwxyz0123456789AB【 】"),
            "typed tail must stay visible with an inverse-blank cursor; screen was:\n"
                + rendered);
        assertFalse(rendered.contains("abcde"),
            "scrolled-off prefix must not be drawn; screen was:\n" + rendered);

        // Backspace at the tail must visibly delete the last character
        r.key(new KeyStroke(KeyType.BACKSPACE));
        String afterBackspace = r.render();
        assertTrue(afterBackspace.contains("efghijklmnopqrstuvwxyz0123456789A【 】"),
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
        assertTrue(rendered.contains("45943【6】"),
            "mid-text cursor must invert the character under it; screen was:\n" + rendered);
        assertFalse(rendered.contains("45943 6"),
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
        assertTrue(rendered.contains("列张律吕调阳【 】"),
            "CJK typed tail must stay visible with an inverse-blank cursor; screen was:\n"
                + rendered);
        assertFalse(rendered.contains("天地玄"),
            "scrolled-off prefix must not be drawn; screen was:\n" + rendered);

        r.key(new KeyStroke(KeyType.BACKSPACE));
        String afterBackspace = r.render();
        assertTrue(afterBackspace.contains("列张律吕调【 】"),
            "backspace deletion must be visible; screen was:\n" + afterBackspace);
        assertFalse(afterBackspace.contains("调阳"),
            "deleted char must be gone; screen was:\n" + afterBackspace);
        r.close();
    }

    @Test
    void otherStaysVisibleWhenCardExceedsAssignedHeight() throws Exception {
        // 4 options × (label + 2 wrapped CJK description lines) + header/question +
        // Other/submit/hint = 17 rows squeezed into a 12-row window: SmartLayout clamps
        // the overlay height, and the unfocused tail must not swallow Other (Ink shows
        // the terminal tail; we anchor on the focused row instead).
        String desc = "去郊区森林公园走一条八公里左右的环线步道，沿途有溪流和开阔山顶草甸，天气好能看到天际线";
        Rendered r = new Rendered(40, 12, List.of(q("周末想去哪里玩？", true,
            new QuestionPresenter.Option("山野徒步", desc, null),
            new QuestionPresenter.Option("城市美术馆", desc, null),
            new QuestionPresenter.Option("夜市美食", desc, null),
            new QuestionPresenter.Option("短途露营", desc, null))));

        // focus starts on option 1 → top of the card is shown
        assertTrue(r.render().contains("1. [ ] 山野徒步"),
            "top of the card must be visible initially");

        // arrow to option 4 → its label+description block stays visible
        r.key(new KeyStroke(KeyType.ARROW_DOWN));
        r.key(new KeyStroke(KeyType.ARROW_DOWN));
        r.key(new KeyStroke(KeyType.ARROW_DOWN));
        assertTrue(r.render().contains("4. [ ] 短途露营"),
            "focused option must stay visible while moving down");

        // arrow to Other → bottom-anchored: Other input row and hint all visible
        r.key(new KeyStroke(KeyType.ARROW_DOWN));
        String onOther = r.render();
        assertTrue(onOther.contains("【T】ype something"),
            "Other input row must be reachable and visible (dimmed placeholder, "
                + "inverse cursor on its first char)");
        // the multi-select hint exceeds 40 columns and clips its tail; assert its head
        assertTrue(onOther.contains("tab to submit"), "hint row must be visible");
        r.type("想去海边");
        assertTrue(r.render().contains("想去海边【 】"),
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
                    com.googlecode.lanterna.SGR.REVERSE);
                if (reversed) sb.append('【');
                sb.append(ch);
                if (reversed) sb.append('】');
                // a double-width char occupies two cells reporting the same glyph
                if (com.googlecode.lanterna.TerminalTextUtils.isCharDoubleWidth(ch)) col++;
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void descriptionWrapsAtWordBoundariesLikeReleased197() {
        // Ink's default wrap="wrap" keeps long descriptions visible by wrapping
        // instead of clipping (released 2.1.197 behavior in narrow terminals).
        List<String> lines = AskUserQuestionDialog.descriptionLines(
            "alpha beta gamma delta epsilon zeta", 12);
        assertEquals(List.of("alpha beta", "gamma delta", "epsilon zeta"), lines);
    }

    @Test
    void descriptionHardWrapsOverlongWords() {
        List<String> lines = AskUserQuestionDialog.descriptionLines("abcdefghijklmnop", 6);
        assertEquals(List.of("abcdef", "ghijkl", "mnop"), lines);
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
    void typedTextOnPresetSelectionBecomesNotes() throws Exception {
        // 197: typing on a preset option is a no-op — notes come from leftover text
        // typed on the Other row before submitting a preset (annotations.notes).
        Harness h = new Harness(List.of(
            q("Choose?", false, opt("Preset", null), opt("Alt", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // focus Other (index 2)
        h.type("some context");
        h.key(new KeyStroke(KeyType.ARROW_UP));     // Alt
        h.key(new KeyStroke(KeyType.ARROW_UP));     // Preset
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("Preset", answers.get("Choose?").answer());
        assertEquals("some context", answers.get("Choose?").notes());
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
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wrap to Submit row
        h.key(new KeyStroke(KeyType.ENTER));
        assertEquals("B", h.await().get("Q?").answer());
    }

    @Test
    void typingOnSubmitRowEditsOtherTextInMultiSelect() throws Exception {
        // 197 isInInput parity: the Submit row keeps the input's key focus, so
        // characters typed while it is highlighted still land in the Other text.
        Harness h = new Harness(List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wrap to Submit row
        h.type("typed from submit");
        h.key(new KeyStroke(KeyType.ENTER));        // Other text auto-selected → submits
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
        h.key(new KeyStroke(KeyType.ENTER));        // S2 → submits
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
        var result = new CompletableFuture<Map<String, QuestionPresenter.Answer>>();

        Thread.ofVirtual().start(() -> result.complete(dialog.showAndWait(gui,
            List.of(q("Q?", false, opt("A", null), opt("B", null))), () -> {},
            cancelled::get)));

        long deadline = System.currentTimeMillis() + 2000;
        while (!result.isDone() && System.currentTimeMillis() < deadline) {
            gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }

        assertNull(result.get(2, TimeUnit.SECONDS));
        assertFalse(dialog.isActive());
    }

    @Test
    void enterWithoutChoiceIsIgnored() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wrap to Submit row
        h.key(new KeyStroke(KeyType.ENTER));        // nothing selected → ignored
        assertTrue(h.dialog.isActive(), "empty multi-select submit must be ignored");
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // wrap to option A
        h.key(new KeyStroke(' ', false, false));    // toggle A
        h.key(new KeyStroke(KeyType.ARROW_UP));     // back to Submit row
        h.key(new KeyStroke(KeyType.ENTER));
        assertEquals("A", h.await().get("Q?").answer());
    }
}
