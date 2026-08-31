package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageCollapserTest {

    /** Capture what the downstream dispatcher renders. */
    static class CapturingDispatcher extends LanternaMessageDispatcher {
        final List<SDKMessage> seen = new ArrayList<>();
        final List<String> panelLines = new ArrayList<>();

        @Override
        public void dispatch(SDKMessage msg, MessagePanel panel) {
            seen.add(msg);
        }
    }

    /** Stub MessagePanel that records appendMixed calls. */
    static class StubPanel extends MessagePanel {
        final List<String> lines = new ArrayList<>();

        @Override
        public void appendMixed(List<MessagePanel.Segment> segments) {
            StringBuilder sb = new StringBuilder();
            for (var s : segments) sb.append(s.text());
            lines.add(sb.toString());
        }
    }

    static final class RecordingPanel extends MessagePanel {
        boolean blinkStarted;
        boolean blinkStopped;

        @Override
        public void startBlinkLine(int lineIndex, List<MessagePanel.Segment> segments) {
            blinkStarted = true;
            super.startBlinkLine(lineIndex, segments);
        }

        @Override
        public void stopBlinkLine(int lineIndex, List<MessagePanel.Segment> segments) {
            blinkStopped = true;
            super.stopBlinkLine(lineIndex, segments);
        }
    }

    private static SDKMessage.StreamEvent toolCall(String name, String fileArg) {
        String info = name + "|tool-id-1|{\"file_path\":\"" + fileArg + "\"}";
        return new SDKMessage.StreamEvent("tool_call_start", info);
    }

    private static SDKMessage.StreamEvent toolResult(String name) {
        return new SDKMessage.StreamEvent("tool_result_success", name + "|done");
    }

    @Test
    void verbose_mode_passesAllMessagesThrough() {
        var cap = new CapturingDispatcher();
        var collapser = new MessageCollapser(cap, /* verbose */ true);
        var panel = new StubPanel();

        collapser.dispatch(toolCall("Read", "Foo.java"), panel);
        collapser.dispatch(toolResult("Read"), panel);
        collapser.dispatch(toolCall("Grep", "*.java"), panel);
        collapser.dispatch(toolResult("Grep"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "text"), panel);

        // verbose=true → all messages forwarded unchanged
        assertEquals(5, cap.seen.size(), "verbose must not collapse anything");
        assertTrue(panel.lines.isEmpty(), "verbose must not generate summary lines");
    }

    @Test
    void twoReadTools_collapse_toSummaryLine() {
        var cap = new CapturingDispatcher();
        var collapser = new MessageCollapser(cap, /* verbose */ false);
        var panel = new StubPanel();

        collapser.dispatch(toolCall("Read", "Foo.java"), panel);
        collapser.dispatch(toolResult("Read"), panel);
        collapser.dispatch(toolCall("Read", "Bar.java"), panel);
        collapser.dispatch(toolResult("Read"), panel);
        // Flush by sending a non-tool message
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "done"), panel);


        assertEquals(1, panel.lines.stream()
            .filter(l -> Strings.CS.contains(l, "Read 2 files")).count(),
            "two Read calls must collapse to 'Read 2 files'; panel: " + panel.lines);
        // Downstream should NOT receive the individual tool_call_start events
        long toolCallCount = cap.seen.stream()
            .filter(m -> m instanceof SDKMessage.StreamEvent e && Strings.CS.equals("tool_call_start", e.eventType()))
            .count();
        assertEquals(0, toolCallCount, "collapsed tool calls must not reach downstream");
    }

    @Test
    void collapsedReadSearchGroup_expandsToRealToolUsesAndResults_thenCollapsesBack() {
        var collapser = new MessageCollapser(new LanternaMessageDispatcher(), false);
        var panel = new MessagePanel();
        panel.appendLine("❯ inspect files", LanternaTheme.inputText());
        panel.registerLogicalMessage("user", MessagePanel.LogicalMessageKind.USER,
            0, 0, "inspect files", "inspect files", null, null, false);

        collapser.dispatch(toolCall("Read", "Foo.java"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "Read|first file contents"), panel);
        collapser.dispatch(toolCall("Read", "Bar.java"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "Read|second file contents"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "done"), panel);

        var controller = new MessageActionsController(
            null, panel, new InputPanel(), _ -> {});
        controller.toggle();
        controller.bottom();
        var group = panel.selectedLogicalMessage().orElseThrow();
        assertEquals(MessagePanel.LogicalMessageKind.TOOL, group.kind());
        assertTrue(group.expandable(), "collapsed Read/Search summary must be actionable");
        assertFalse(group.expanded());
        assertEquals(1, panel.searchLines("Read 2 files").size());
        assertTrue(panel.searchLines("Read(Foo.java)").isEmpty());
        assertTrue(panel.searchLines("first file contents").isEmpty());

        controller.edit();
        var expanded = panel.selectedLogicalMessage().orElseThrow();

        assertTrue(expanded.expanded());
        assertTrue(expanded.endLine() > expanded.startLine(),
            "selection must cover all rows introduced by expansion");
        assertTrue(panel.searchLines("Read 2 files").isEmpty());
        assertEquals(1, panel.searchLines("Read(Foo.java)").size());
        assertEquals(1, panel.searchLines("Read(Bar.java)").size());
        assertEquals(1, panel.searchLines("first file contents").size());
        assertEquals(1, panel.searchLines("second file contents").size());

        controller.escape();
        var collapsed = panel.selectedLogicalMessage().orElseThrow();

        assertFalse(collapsed.expanded());
        assertEquals(1, panel.searchLines("Read 2 files").size());
        assertTrue(panel.searchLines("Read(Foo.java)").isEmpty());
        assertTrue(panel.searchLines("first file contents").isEmpty());
    }

    @Test
    void singleReadTool_usesReleasedCollapsedSummary() {
        var cap = new CapturingDispatcher();
        var collapser = new MessageCollapser(cap, /* verbose */ false);
        var panel = new StubPanel();

        collapser.dispatch(toolCall("Read", "Foo.java"), panel);
        collapser.dispatch(toolResult("Read"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "text"), panel);

        assertTrue(panel.lines.stream().anyMatch(l -> Strings.CS.contains(l, "Read 1 file")),
            "released 197 collapses a single Read too: " + panel.lines);
    }

    @Test
    void mixedGrepAndGlob_collapsesTogether() {
        var cap = new CapturingDispatcher();
        var collapser = new MessageCollapser(cap, /* verbose */ false);
        var panel = new StubPanel();

        collapser.dispatch(toolCall("Grep", "*.java"), panel);
        collapser.dispatch(toolResult("Grep"), panel);
        collapser.dispatch(toolCall("Glob", "src/"), panel);
        collapser.dispatch(toolResult("Glob"), panel);
        collapser.dispatch(toolCall("Read", "Foo.java"), panel);
        collapser.dispatch(toolResult("Read"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "text"), panel);

        assertTrue(panel.lines.stream().anyMatch(l ->
                Strings.CS.contains(l, "Read 3 files")),
            "path-only calls remain reads; panel: " + panel.lines);
    }

    @Test
    void mixedParallelReadSearchListAndMcpShareOneReleasedSummary() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        var panel = new RecordingPanel();

        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "Read|read-1|{\"file_path\":\"/repo/A.java\"}"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "Grep|grep-1|{\"pattern\":\"TODO\"}"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "LS|ls-1|{\"path\":\"/repo/src\"}"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "mcp__slack__search|mcp-1|{\"query\":\"release\"}"), panel);

        String active = panel.snapshotStyledLines().stream()
            .map(MessagePanel.StyledLine::text)
            .filter(line -> Strings.CS.contains(line, "Searching for"))
            .findFirst().orElseThrow();
        assertEquals("Searching for 1 pattern, reading 1 file, listing 1 directory, "
            + "calling slack… (ctrl+o to expand)",
            active.substring(active.indexOf("Searching for")));
        assertTrue(panel.blinkStarted, "active collapsed group uses ToolUseLoader blink");

        var activeLine = panel.snapshotStyledLines().stream()
            .filter(line -> Strings.CS.contains(line.text(), "Searching for"))
            .findFirst().orElseThrow();
        long boldCounts = activeLine.segments().stream()
            .filter(segment -> Strings.CS.equals("1", segment.text())
                && segment.modifiers().contains(SGR.BOLD))
            .count();
        assertEquals(3, boldCounts, "search/read/list counts are independently bold");

        collapser.dispatch(toolResult("Read"), panel);
        collapser.dispatch(toolResult("Grep"), panel);
        collapser.dispatch(toolResult("LS"), panel);
        collapser.dispatch(toolResult("mcp__slack__search"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "done"), panel);

        String completed = panel.snapshotStyledLines().stream()
            .map(MessagePanel.StyledLine::text)
            .filter(line -> Strings.CS.contains(line, "Searched for"))
            .findFirst().orElseThrow();
        assertEquals("Searched for 1 pattern, read 1 file, listed 1 directory, called slack "
                + "(ctrl+o to expand)",
            completed.strip());
        assertTrue(Strings.CS.contains(completed, "to expand"),
            "released 2.1.197 keeps the expand affordance after completion");
        assertTrue(panel.blinkStopped, "completion stops the active loader");
    }

    @Test
    void mutatingMcpToolBreaksTheCollapsedGroupAndRendersNormally() {
        var cap = new CapturingDispatcher();
        var collapser = new MessageCollapser(cap, false);
        var panel = new StubPanel();

        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "Read|read-1|{\"file_path\":\"/repo/A.java\"}"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "mcp__slack__send_message|mcp-write|{\"text\":\"hello\"}"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "mcp__slack__send_message|sent"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "answer"), panel);

        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "Read 1 file")),
            "the preceding read group must be finalized before a mutating MCP call");
        assertTrue(cap.seen.stream().anyMatch(message -> message instanceof SDKMessage.StreamEvent event
                && Strings.CS.equals("tool_call_start", event.eventType())
                && Strings.CS.contains(String.valueOf(event.data()), "send_message")),
            "unknown/mutating MCP tools are not swallowed by the read/search collapse");
    }

    @Test
    void releasedMcpClassifierNormalizesCamelCaseToolNames() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        var panel = new StubPanel();

        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "mcp__github__getFileContents|mcp-read|{}"), panel);

        assertEquals("Calling github… (ctrl+o to expand)", collapser.buildActiveGroupPhrase());
    }

    @Test
    void loadingKeepsResolvedTrailingGroupActiveUntilRealContentArrives() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        var panel = new MessagePanel();
        collapser.setLoading(true, panel);
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "Read|read-loading|{\"file_path\":\"/repo/A.java\"}"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent("tool_result_success", "Read|done"), panel);

        assertEquals("Reading 1 file… (ctrl+o to expand)", collapser.buildActiveGroupPhrase());
        assertEquals(1, panel.searchLines("Reading 1 file").size());
        assertTrue(panel.searchLines("Read 1 file").isEmpty());

        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "answer"), panel);
        assertEquals(1, panel.searchLines("Read 1 file").size());
    }

    @Test
    void duplicateParallelReadsCountUniqueFiles() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        var panel = new StubPanel();
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "Read|read-1|{\"file_path\":\"/repo/A.java\"}"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "Read|read-2|{\"file_path\":\"/repo/A.java\"}"), panel);
        collapser.dispatch(toolResult("Read"), panel);
        collapser.dispatch(toolResult("Read"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "done"), panel);

        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "Read 1 file")),
            panel.lines.toString());
        assertFalse(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "Read 2 files")),
            panel.lines.toString());
    }

    @Test
    void realToolResultMessageIsAbsorbedByCollapsedGroup() {
        var collapser = new MessageCollapser(new LanternaMessageDispatcher(), false);
        var panel = new MessagePanel();
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Read|read-real|msg-1"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Read|read-real|{\"file_path\":\"/repo/A.java\"}"), panel);

        UserMessage result = new UserMessage("result-user",
            MessageContent.ofToolResult("read-real",
                List.of(new TextBlock("secret file contents")), false));
        collapser.dispatch(new SDKMessage.User(result), panel);
        assertEquals("", collapser.buildActiveGroupPhrase(),
            "spinner phrase clears as soon as every collapsed tool resolves");
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "done"), panel);

        assertEquals(1, panel.searchLines("Read 1 file").size());
        assertTrue(panel.searchLines("secret file contents").isEmpty(),
            "collapsed results stay hidden until the group is expanded");
    }

    @Test
    void nonSearchTool_notCollapsed() {
        var cap = new CapturingDispatcher();
        var collapser = new MessageCollapser(cap, /* verbose */ false);
        var panel = new StubPanel();

        collapser.dispatch(toolCall("Bash", "ls"), panel);
        collapser.dispatch(toolResult("Bash"), panel);
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "text"), panel);

        // Bash tool must reach downstream unchanged
        long bashCalls = cap.seen.stream()
            .filter(m -> m instanceof SDKMessage.StreamEvent e && Strings.CS.equals("tool_call_start", e.eventType()))
            .count();
        assertEquals(1, bashCalls, "Bash tool_call_start must reach downstream");
    }



    private static SDKMessage.StreamEvent grepCall(String pattern) {
        return new SDKMessage.StreamEvent(
            "tool_call_start",
            "Grep|tool-id-x|{\"pattern\":\"" + pattern + "\"}");
    }

    private static SDKMessage.StreamEvent lsCall(String path) {
        return new SDKMessage.StreamEvent(
            "tool_call_start",
            "LS|tool-id-x|{\"path\":\"" + path + "\"}");
    }

    private static SDKMessage.StreamEvent bashCall(String cmd) {
        return new SDKMessage.StreamEvent(
            "tool_call_start",
            "Bash|tool-id-x|{\"command\":\"" + cmd + "\"}");
    }

    @Test
    void activeGroupPhrase_emptyWhenIdle() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        assertEquals("", collapser.buildActiveGroupPhrase());
    }

    @Test
    void activeGroupPhrase_singleListDirectory() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        collapser.dispatch(lsCall("/tmp"), new StubPanel());
        assertEquals("Listing 1 directory… (ctrl+o to expand)",
            collapser.buildActiveGroupPhrase());
    }

    @Test
    void activeGroupPhrase_multipleReads() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        var panel = new StubPanel();
        collapser.dispatch(toolCall("Read", "a.txt"), panel);
        collapser.dispatch(toolCall("Read", "b.txt"), panel);
        collapser.dispatch(toolCall("Read", "c.txt"), panel);
        assertEquals("Reading 3 files… (ctrl+o to expand)",
            collapser.buildActiveGroupPhrase());
    }

    @Test
    void activeGroupPhrase_grepPatterns() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        var panel = new StubPanel();
        collapser.dispatch(grepCall("foo"), panel);
        collapser.dispatch(grepCall("bar"), panel);
        assertEquals("Searching for 2 patterns… (ctrl+o to expand)",
            collapser.buildActiveGroupPhrase());
    }

    @Test
    void activeGroupPhrase_bashRunning() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        collapser.dispatch(bashCall("ls /tmp"), new StubPanel());
        assertEquals("Running $ ls /tmp… (ctrl+o to expand)",
            collapser.buildActiveGroupPhrase());
    }

    @Test
    void activeGroupPhrase_mixedReadAndGrep() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        var panel = new StubPanel();
        collapser.dispatch(toolCall("Read", "a.txt"), panel);
        collapser.dispatch(grepCall("foo"), panel);

        assertEquals("Searching for 1 pattern, reading 1 file… (ctrl+o to expand)",
            collapser.buildActiveGroupPhrase());
    }

    @Test
    void activeGroupPhrase_listenerFiredOnToolStart() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        var phrases = new ArrayList<String>();
        collapser.setPhraseChangeListener(phrases::add);
        collapser.dispatch(lsCall("/tmp"), new StubPanel());
        assertFalse(phrases.isEmpty(), "listener must be invoked on tool_call_start");
        assertEquals("Listing 1 directory… (ctrl+o to expand)",
            phrases.getLast());
    }

    @Test
    void activeGroupPhrase_clearedAfterBashResult() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        var panel = new StubPanel();
        collapser.dispatch(bashCall("ls /tmp"), panel);
        assertFalse(collapser.buildActiveGroupPhrase().isEmpty());
        collapser.dispatch(toolResult("Bash"), panel);
        assertEquals("", collapser.buildActiveGroupPhrase(),
            "after Bash result, lastNonReadSearchToolName must clear");
    }

    @Test
    void activeGroupPhrase_longBashTruncated() {
        var collapser = new MessageCollapser(new CapturingDispatcher(), false);
        String longCmd = "a".repeat(80);
        collapser.dispatch(bashCall(longCmd), new StubPanel());
        String phrase = collapser.buildActiveGroupPhrase();
        assertTrue(Strings.CS.contains(phrase, "…"), "long command must be truncated with ellipsis");
        assertTrue(phrase.length() < 80 + "Running $ … (ctrl+o to expand)".length() + 10,
            "truncation should bound phrase length");
    }
}
