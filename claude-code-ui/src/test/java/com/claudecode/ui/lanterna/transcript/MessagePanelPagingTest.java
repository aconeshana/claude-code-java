package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.claudecode.ui.MarkdownRenderer;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessagePanelPagingTest {

    @Test
    void pageUpUsesHalfViewport() throws Exception {
        MessagePanel panel = populated(80, 80, 30);
        panel.pageUp();
        assertEquals(15, scrollOffset(panel));
    }

    @Test
    void maximumOffsetUsesWrappedDisplayRows() throws Exception {
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(10, 5));
        panel.appendLine("x".repeat(30), TextColor.ANSI.DEFAULT);
        panel.appendLine("tail", TextColor.ANSI.DEFAULT);

        panel.scrollToTop();

        assertEquals(0, scrollOffset(panel), "four wrapped rows fit in five viewport rows");
        panel.appendLine("y".repeat(30), TextColor.ANSI.DEFAULT);
        panel.scrollToTop();
        assertEquals(2, scrollOffset(panel), "seven display rows minus five visible rows");
    }

    @Test
    void planApprovalPreviewIsScrollableButDoesNotEnterPersistentHistory() throws Exception {
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(40, 5));
        panel.appendLine("existing history", TextColor.ANSI.DEFAULT);
        int persistentLines = panel.snapshotLineCount();

        panel.showPlanApprovalPreview(
            "# Plan\n\n" + "- implementation detail\n".repeat(20), new MarkdownRenderer());

        assertEquals(persistentLines, panel.snapshotLineCount());
        assertTrue(panel.displayRowsForTest(40).stream()
            .map(MessagePanel.StyledLine::text)
            .anyMatch("Here is Claude's plan:"::equals));
        panel.scrollToTop();
        assertTrue(scrollOffset(panel) > 0, "the long preview participates in transcript paging");

        panel.clearTransientTail();

        assertEquals(List.of("existing history"), texts(panel.displayRowsForTest(40)));
        assertEquals(0, scrollOffset(panel));
    }

    @Test
    void detachedWelcomeReturnsOnlyWhenHistoryBrowsingReachesTop() {
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 5));
        List<List<MessagePanel.Segment>> welcome = List.of(
            List.of(new MessagePanel.Segment("Pikachu", TextColor.ANSI.YELLOW)),
            List.of(new MessagePanel.Segment("Claude Code", TextColor.ANSI.DEFAULT)));
        for (List<MessagePanel.Segment> row : welcome) panel.appendMixed(row);
        panel.setHistoryTopAnchor(0, welcome);
        for (int i = 0; i < 10; i++) panel.appendLine("old " + i, TextColor.ANSI.DEFAULT);

        panel.clear();
        for (int i = 0; i < 10; i++) panel.appendLine("restored " + i, TextColor.ANSI.DEFAULT);

        List<String> bottom = texts(panel.viewportRowsForTest(80, 5));
        assertFalse(bottom.contains("Pikachu"), "normal bottom-follow must not pin welcome");

        panel.scrollToTop();
        List<String> top = texts(panel.viewportRowsForTest(80, 5));
        assertEquals(List.of("Pikachu", "Claude Code"), top.subList(0, 2));
        assertTrue(top.contains("restored 0"), "history begins below the frozen welcome");
    }

    @Test
    void shortResumedTranscriptShowsDetachedWelcomeWhileFollowingBottom() {
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 6));
        List<List<MessagePanel.Segment>> welcome = List.of(
            List.of(new MessagePanel.Segment("Pikachu", TextColor.ANSI.YELLOW)),
            List.of(new MessagePanel.Segment("Claude Code", TextColor.ANSI.DEFAULT)));
        for (List<MessagePanel.Segment> row : welcome) panel.appendMixed(row);
        panel.setHistoryTopAnchor(0, welcome);

        panel.clear();
        panel.appendLine("[Resumed session]", TextColor.ANSI.DEFAULT);
        panel.appendLine("restored prompt", TextColor.ANSI.DEFAULT);
        panel.scrollToBottom();

        assertEquals(List.of(
            "Pikachu", "Claude Code", "[Resumed session]", "restored prompt"),
            texts(panel.viewportRowsForTest(80, 6)));
    }

    @Test
    void originalWelcomeIsNotDuplicatedAtHistoryTop() {
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 4));
        List<List<MessagePanel.Segment>> welcome = List.of(
            List.of(new MessagePanel.Segment("Pikachu", TextColor.ANSI.YELLOW)));
        panel.appendMixed(welcome.getFirst());
        panel.setHistoryTopAnchor(0, welcome);
        for (int i = 0; i < 6; i++) panel.appendLine("line " + i, TextColor.ANSI.DEFAULT);

        panel.scrollToTop();

        assertEquals(1, texts(panel.viewportRowsForTest(80, 4)).stream()
            .filter("Pikachu"::equals).count());
    }

    @Test
    void tailTruncationDoesNotDetachAndDuplicateUpdatedWelcome() {
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 5));
        List<List<MessagePanel.Segment>> original = List.of(
            List.of(new MessagePanel.Segment("Pikachu 1.4%", TextColor.ANSI.YELLOW)));
        panel.appendMixed(original.getFirst());
        panel.setHistoryTopAnchor(0, original);
        for (int i = 0; i < 8; i++) panel.appendLine("line " + i, TextColor.ANSI.DEFAULT);

        panel.truncateLinesTo(7);
        List<List<MessagePanel.Segment>> updated = List.of(
            List.of(new MessagePanel.Segment("Pikachu 1.6%", TextColor.ANSI.YELLOW)));

        assertEquals(0, panel.replaceHistoryTopAnchor(updated),
            "tail-only truncation must keep the tracked welcome source range");
        panel.scrollToTop();

        List<String> top = texts(panel.viewportRowsForTest(80, 5));
        assertEquals(1, top.stream().filter(line -> Strings.CS.startsWith(line, "Pikachu")).count());
        assertTrue(top.contains("Pikachu 1.6%"));
        assertFalse(top.contains("Pikachu 1.4%"));
    }

    @Test
    void selectionTracksContentWhenScrollingUpAndCapturesBottomRows() throws Exception {
        MessagePanel panel = populated(80, 10, 5);
        Selection selection = new Selection();
        panel.setSelection(selection);
        selection.startSelection(0, 3);
        selection.updateSelection(5, 4);
        captureLastFrame(panel, 80, 5);

        panel.scrollUp(1);

        Selection.Bounds bounds = selection.getSelectionBounds();
        assertEquals(4, bounds.start().row());
        assertEquals(4, bounds.end().row(), "off-bottom focus is clamped to the viewport edge");
        assertEquals("line 8\nline 9", selection.getSelectedText(
            row -> texts(panel.viewportRowsForTest(80, 5)).get(row)));
    }

    @Test
    void selectionTracksContentWhenScrollingDownAndCapturesTopRows() throws Exception {
        MessagePanel panel = populated(80, 10, 5);
        panel.scrollUp(1);
        Selection selection = new Selection();
        panel.setSelection(selection);
        selection.startSelection(0, 0);
        selection.updateSelection(5, 1);
        captureLastFrame(panel, 80, 5);

        panel.scrollDown(1);

        Selection.Bounds bounds = selection.getSelectionBounds();
        assertEquals(0, bounds.start().row(), "off-top anchor is clamped to the viewport edge");
        assertEquals(0, bounds.end().row());
        assertEquals("line 4\nline 5", selection.getSelectedText(
            row -> texts(panel.viewportRowsForTest(80, 5)).get(row)));
    }

    @Test
    void dragAutoscrollMovesAnchorButLeavesFocusAtMouseEdge() throws Exception {
        MessagePanel panel = populated(80, 10, 5);
        Selection selection = new Selection();
        panel.setSelection(selection);
        selection.startSelection(0, 2);
        selection.updateSelection(5, 4);
        captureLastFrame(panel, 80, 5);

        panel.scrollSelectionDragUp(1);

        assertEquals(3, selection.getAnchor().row());
        assertEquals(4, selection.getFocusOrAnchor().row(),
            "drag focus must remain at the live mouse edge");
    }

    private static MessagePanel populated(int width, int count, int height) {
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(width, height));
        for (int i = 0; i < count; i++) panel.appendLine("line " + i, TextColor.ANSI.DEFAULT);
        return panel;
    }

    private static int scrollOffset(MessagePanel panel) throws Exception {
        Field field = MessagePanel.class.getDeclaredField("scrollOffset");
        field.setAccessible(true);
        return field.getInt(panel);
    }

    private static void captureLastFrame(MessagePanel panel, int width, int height) throws Exception {
        Field field = MessagePanel.class.getDeclaredField("lastFrameRowTexts");
        field.setAccessible(true);
        field.set(panel, texts(panel.viewportRowsForTest(width, height)));
    }

    private static List<String> texts(List<MessagePanel.StyledLine> rows) {
        return rows.stream().map(MessagePanel.StyledLine::text).toList();
    }
}
