package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TerminalSize;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessagePanelLogicalMessagesTest {

    @Test
    void markdownProjectionUsesActualSubTwentyColumnViewport() {
        MessagePanel panel = new MessagePanel();
        panel.appendMarkdown("| A | B |\n|---|---|\n| x | y |",
            new MarkdownRenderer(), true);

        List<String> rows = panel.displayRowsForTest(14).stream()
            .map(MessagePanel.StyledLine::text).toList();
        assertTrue(rows.stream().noneMatch(row -> Strings.CS.contains(row, "┌")), rows.toString());
        assertTrue(rows.stream().allMatch(row ->
            FormatUtils.displayWidth(row) <= 14), rows.toString());
    }

    @Test
    void markdownProjectionReflowsHistoricalTableWhenTerminalWidthChanges() {
        MessagePanel panel = new MessagePanel();
        panel.appendMarkdown("""
            | name | description |
            |---|---|
            | alpha | content stays visible after terminal resize |""",
            new MarkdownRenderer(), true);

        List<String> wide = panel.displayRowsForTest(80).stream()
            .map(MessagePanel.StyledLine::text).toList();
        List<String> narrow = panel.displayRowsForTest(28).stream()
            .map(MessagePanel.StyledLine::text).toList();

        assertNotEquals(wide, narrow, "terminal resize must re-render stored Markdown");
        assertTrue(Strings.CS.contains(String.join(" ", narrow).replaceAll("\\s+", " "),
            "content stays visible"));
        assertTrue(narrow.stream().allMatch(line -> line.length() <= 28), narrow.toString());
    }

    @Test
    void navigatesLogicalMessagesAndUserOnlyJumpsByIdentityNotLine() {
        MessagePanel panel = new MessagePanel();
        int userStart = panel.snapshotLineCount();
        panel.appendLine("❯ first line", LanternaTheme.inputText());
        panel.appendLine("  second line", LanternaTheme.inputText());
        panel.registerLogicalMessage("u1", MessagePanel.LogicalMessageKind.USER,
            userStart, panel.snapshotLineCount() - 1, "first line\nsecond line",
            "first line\nsecond line", null, null, false);

        int assistantStart = panel.snapshotLineCount();
        panel.appendLine("assistant", LanternaTheme.inputText());
        panel.registerLogicalMessage("a1", MessagePanel.LogicalMessageKind.ASSISTANT,
            assistantStart, assistantStart, "assistant", null, null, null, false);

        int toolStart = panel.snapshotLineCount();
        panel.appendLine("● Bash(ls)", LanternaTheme.inputText());
        panel.appendLine("  ⎿ output", LanternaTheme.welcomeDim());
        panel.registerLogicalMessage("t1", MessagePanel.LogicalMessageKind.TOOL,
            toolStart, panel.snapshotLineCount() - 1, "ls", null,
            "command", "ls", false);

        assertEquals("u1", panel.enterMessageActions().orElseThrow().id(),
            "the released overlay opens on the most recent real user prompt");
        assertTrue(panel.isLogicalLineSelectedForTest(userStart));
        assertTrue(panel.isLogicalLineSelectedForTest(userStart + 1));
        assertFalse(panel.isLogicalLineSelectedForTest(assistantStart));

        assertEquals("a1", panel.selectNextLogicalMessage().orElseThrow().id());
        assertEquals("u1", panel.selectPreviousUserMessage().orElseThrow().id());
        assertEquals("t1", panel.selectBottomLogicalMessage().orElseThrow().id());
        assertEquals("u1", panel.selectTopLogicalMessage().orElseThrow().id());
    }

    @Test
    void truncationRemovesLogicalMessagesWhoseRenderedRowsWereRolledBack() {
        MessagePanel panel = new MessagePanel();
        panel.appendLine("kept", LanternaTheme.inputText());
        panel.registerLogicalMessage("kept", MessagePanel.LogicalMessageKind.USER,
            0, 0, "kept", "kept", null, null, false);
        int snapshot = panel.snapshotLineCount();
        panel.appendLine("rolled back", LanternaTheme.inputText());
        panel.registerLogicalMessage("gone", MessagePanel.LogicalMessageKind.ASSISTANT,
            1, 1, "rolled back", null, null, null, false);

        panel.truncateLinesTo(snapshot);

        assertEquals("kept", panel.enterMessageActions().orElseThrow().id());
        assertEquals(1, panel.logicalMessageCountForTest());
    }

    @Test
    void sourceUuidCanBeBoundAfterLiveEchoAndUsedToTruncateFromThatPrompt() {
        MessagePanel panel = new MessagePanel();
        panel.appendLine("kept", LanternaTheme.inputText());
        panel.registerLogicalMessage("old-render", "old-raw",
            MessagePanel.LogicalMessageKind.USER, 0, 0,
            "kept", "kept", null, null, false);
        panel.appendLine("live prompt", LanternaTheme.inputText());
        panel.registerLogicalMessage("live-render", null,
            MessagePanel.LogicalMessageKind.USER, 1, 1,
            "live prompt", "live prompt", null, null, false);
        panel.appendLine("answer", LanternaTheme.inputText());
        panel.registerLogicalMessage("answer", MessagePanel.LogicalMessageKind.ASSISTANT,
            2, 2, "answer", null, null, null, false);

        panel.bindLatestUnboundUserSourceUuid("new-raw");
        panel.truncateFromSourceUuid("new-raw");

        assertEquals(1, panel.snapshotLineCount());
        assertEquals("old-raw", panel.enterMessageActions().orElseThrow().sourceUuid());
    }

    @Test
    void expandingLogicalMessageReplacesRowsAndShiftsFollowingMessageRange() {
        MessagePanel panel = new MessagePanel();
        panel.appendLine("collapsed", LanternaTheme.toolSuccess());
        panel.registerExpandableLogicalMessage(
            "group", MessagePanel.LogicalMessageKind.TOOL, 0, 0, "result",
            List.of(
                new MessagePanel.StyledLine("Read(a.txt)", LanternaTheme.toolSuccess(), false),
                new MessagePanel.StyledLine("  ⎿  result", LanternaTheme.welcomeDim(), false)));
        panel.appendLine("answer", LanternaTheme.inputText());
        panel.registerLogicalMessage("answer", MessagePanel.LogicalMessageKind.ASSISTANT,
            1, 1, "answer", null, null, null, false);

        panel.selectTopLogicalMessage();
        var expanded = panel.toggleSelectedLogicalMessageExpanded().orElseThrow();

        assertEquals(1, expanded.endLine());
        assertEquals("answer", panel.selectNextLogicalMessage().orElseThrow().id());
        assertEquals(2, panel.selectedLogicalMessage().orElseThrow().startLine());

        panel.selectPreviousLogicalMessage();
        panel.collapseSelectedLogicalMessage();
        assertEquals("answer", panel.selectNextLogicalMessage().orElseThrow().id());
        assertEquals(1, panel.selectedLogicalMessage().orElseThrow().startLine());
    }

    @Test
    void viewportClickTogglesOnlyExpandableLogicalMessage() {
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(40, 6));
        panel.appendLine("collapsed group", LanternaTheme.toolSuccess());
        panel.registerExpandableLogicalMessage(
            "group", MessagePanel.LogicalMessageKind.TOOL, 0, 0, "result",
            List.of(
                new MessagePanel.StyledLine("Read(a.txt)", LanternaTheme.toolSuccess(), false),
                new MessagePanel.StyledLine("  ⎿  result", LanternaTheme.welcomeDim(), false)));
        panel.appendLine("ordinary answer", LanternaTheme.inputText());
        panel.registerLogicalMessage("answer", MessagePanel.LogicalMessageKind.ASSISTANT,
            1, 1, "answer", null, null, null, false);

        var expanded = panel.toggleExpandableLogicalMessageAt(2, 0).orElseThrow();
        assertTrue(expanded.expanded());
        assertEquals(1, expanded.endLine());

        assertTrue(panel.toggleExpandableLogicalMessageAt(2, 2).isEmpty(),
            "ordinary rows are not clickable");
        var collapsed = panel.toggleExpandableLogicalMessageAt(2, 0).orElseThrow();
        assertFalse(collapsed.expanded());
    }

    @Test
    void truncatedToolOutputIsClickableButShortOutputIsNot() {
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(30, 8));
        panel.appendToolOutput(String.join("\n", Collections.nCopies(8, "long output row")),
            LanternaTheme.welcomeDim(), false);

        var expanded = panel.toggleExpandableLogicalMessageAt(2, 0).orElseThrow();
        assertTrue(expanded.expanded());

        MessagePanel shortPanel = new MessagePanel();
        shortPanel.setSize(new TerminalSize(30, 8));
        shortPanel.appendToolOutput("short", LanternaTheme.welcomeDim(), false);
        assertTrue(shortPanel.toggleExpandableLogicalMessageAt(2, 0).isEmpty());
    }
}
