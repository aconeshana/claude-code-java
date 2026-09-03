package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.render.RenderingContext;
import com.googlecode.lanterna.SGR;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

class StreamingMarkdownDispatcherTest {

    @Test
    void streamedStrongEmphasisNeverLeaksAnsiPayloadAcrossVisualWrapping() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        String markdown = """
            没有现成单测,该脚本是一次性维护工具,已用 dry-run 在真实 coverage.yml 上验证过修复效果,不再补测试。

            **修复内容**（`scripts/coverage_gap_fill.py`）:
            1. `find_recorded_symbols`：原来找到第一个匹配的 `ts_key:` 块就 `break` 掉整个函数。""";

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", markdown.substring(0, markdown.indexOf("**\u4fee\u590d") + 4)), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", markdown.substring(markdown.indexOf("**\u4fee\u590d") + 4)), panel);

        List<MessagePanel.StyledLine> rows = panel.displayRowsForTest(100);
        String text = rows.stream().map(MessagePanel.StyledLine::text)
            .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(Strings.CS.contains(text, "[1m"),
            "bold SGR payload must not become visible text");
        assertTrue(Strings.CS.contains(text, "\u4fee\u590d\u5185\u5bb9"));
    }

    @Test
    void completedTopLevelBlocksStayStableWhileOnlyTheTailReflows() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "# Stable heading\n\n"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "Growing"), panel);

        MessagePanel.StyledLine stableHeading = panel.snapshotStyledLines().getFirst();
        assertEquals("⏺ Stable heading", panel.displayRowsForTest(100).getFirst().text());

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", " paragraph"), panel);

        assertSame(stableHeading, panel.snapshotStyledLines().getFirst(),
            "a completed heading must not be rebuilt when the paragraph tail grows");
    }


    @Test
    void laterDeltasParseOnlyTheUnstableMarkdownSuffix() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        String stablePrefix = "## Stable block\n\n" + "Already complete.\n\n".repeat(2_000);
        String initialTail = "Growing **tail";
        String secondDelta = " text";
        String thirdDelta = "** continues";

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", stablePrefix + initialTail), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", secondDelta), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", thirdDelta), panel);

        assertEquals(List.of(
                stablePrefix.length() + initialTail.length(),
                initialTail.length() + secondDelta.length(),
                initialTail.length() + secondDelta.length() + thirdDelta.length()),
            dispatcher.streamingBoundaryParseInputLengthsForTest(),
            "after the first delta establishes a stable boundary, CommonMark must only parse "
                + "the unfinished suffix; reparsing the stable prefix makes streaming O(n²)");
    }

    @Test
    void finalAssistantMessageDoesNotReplaceAnEquivalentStreamingProjection() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        String markdown = "# Stable heading\n\nGrowing **bold** paragraph";

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "# Stable heading\n\n"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "Growing **bold** paragraph"), panel);
        List<MessagePanel.StyledLine> beforeFinal = panel.snapshotStyledLines();

        AssistantMessage message = new AssistantMessage("assistant-1",
            AssistantContent.of(List.of(new TextBlock(markdown))));
        dispatcher.dispatch(new SDKMessage.Assistant(message, Usage.EMPTY), panel);

        assertEquals(beforeFinal, panel.snapshotStyledLines(),
            "the final event must commit the existing streaming markdown without a snap");
    }

    @Test
    void stableAndGrowingMarkdownBlocksKeepOfficialOneRowGap() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "# Stable heading\n\n"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "Growing paragraph"), panel);

        assertEquals(List.of("⏺ Stable heading", "", "  Growing paragraph"),
            panel.displayRowsForTest(100).stream().map(MessagePanel.StyledLine::text).toList());
    }

    @Test
    void partialTableRowNeverFreezesAHeaderOnlyTable() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(new SDKMessage.StreamEvent("content_block_delta",
            "PMD与重构\n\n| 模块 | Gap 数 | 占比 |\n|---|---:|---:|\n"), panel);
        // Reproduces an Anthropic delta boundary observed in the UI: the next
        // table row initially consists of only its opening pipe.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "|"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent("content_block_delta",
            """
             claude-code-commands | 258 | 29.4% |
            | claude-code-core | 216 | 24.6% |
            | claude-code-ui | 188 | 21.4% |"""), panel);

        List<String> rows = panel.displayRowsForTest(100).stream()
            .map(MessagePanel.StyledLine::text).toList();
        assertTrue(rows.stream().anyMatch(row -> Strings.CS.contains(row, "claude-code-commands")),
            rows.toString());
        assertTrue(rows.stream().anyMatch(row -> Strings.CS.startsWith(row, "  └")), rows.toString());
        assertFalse(rows.stream().anyMatch(row -> Strings.CS.startsWith(row, "  | claude-code")),
            rows.toString());
    }

    @Test
    void closingInternalPromptTagResetsStablePrefixAndRemovesHiddenContent() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "# Visible\n\n<context>secret\n\n# hidden\n\nmore"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "</context>\n\nAfter"), panel);

        String text = panel.displayRowsForTest(100).stream()
            .map(MessagePanel.StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(Strings.CS.contains(text, "Visible"), text);
        assertTrue(Strings.CS.contains(text, "After"), text);
        assertFalse(Strings.CS.contains(text, "secret"), text);
        assertFalse(Strings.CS.contains(text, "hidden"), text);
        assertFalse(Strings.CS.contains(text, "more"), text);
    }

    @Test
    void expandedThinkingMatchesOfficialDimItalicGutterAndDimmedMarkdown() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setVerbose(true);
        MessagePanel panel = new MessagePanel();

        dispatcher.renderThinking(new ThinkingBlock("**reasoning** and `code`"), panel,
            RenderingContext.NORMAL);

        // 197's Fzn puts a minWidth:2 dim+italic glyph beside the body — no label word
        // and no gap row, so the very first row already carries the reasoning text.
        List<MessagePanel.StyledLine> rows = panel.displayRowsForTest(100);
        MessagePanel.StyledLine first = rows.getFirst();
        assertEquals("∴ ", first.segments().getFirst().text());
        assertTrue(first.segments().getFirst().modifiers().contains(SGR.ITALIC));
        assertEquals("∴ reasoning and code", first.text());
        assertTrue(rows.stream()
            .flatMap(row -> row.segments().stream())
            .allMatch(segment -> segment.color().equals(
                LanternaTheme.welcomeDim())));
    }

    @Test
    void visibleStreamingTextWindow_notifiesListenerOpen() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        List<Boolean> windows = new ArrayList<>();
        dispatcher.onStreamTextVisibility(windows::add);
        assertEquals(0, windows.size(), "no window before any streaming");

        // First content_block_delta opens the visible-streaming-text window.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "text"), panel);
        assertEquals(List.of(Boolean.TRUE), windows,
            "streaming text opens the window exactly once");

        // A subsequent delta while the window is already open must not fire again.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", " more"), panel);
        assertEquals(List.of(Boolean.TRUE), windows,
            "the window stays open across deltas without duplicate notifications");
    }

    @Test
    void toolStreamingStart_closesVisibilityButKeepsRollbackWindowOpen() {
        // 197 clears streamingText on EVERY content_block_start (2.1.197 bundle:
        // a?.(()=>null) before the content_block.type dispatch), so a tool_use block
        // start ends the visible-text phase and the spinner returns for the whole
        // tool execution — blocking Bash included. The dispatcher's rollback window
        // (streamedThisTurn) must nevertheless stay open until the RESULT commits.
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        List<Boolean> windows = new ArrayList<>();
        dispatcher.onStreamTextVisibility(windows::add);

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "Let me run that."), panel);
        assertEquals(List.of(Boolean.TRUE), windows);

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Bash|tool-1|msg-1"), panel);
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), windows,
            "tool stream start closes the visible-text phase immediately");
        assertFalse(dispatcher.isIdle(),
            "rollback window and pending tool stay open during execution");

        // The result commit must not fire a duplicate close — visibility already ended.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "Bash|ok"), panel);
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), windows,
            "notifications fire only on actual transitions");
        assertTrue(dispatcher.isIdle(), "result commit closes the rollback window");
    }

    @Test
    void stragglerDeltaAfterToolStart_doesNotReopenVisibility() {
        // Some providers emit a trailing text delta (a newline, a late batched flush)
        // AFTER tool_streaming_start. 197's streamingText stays null for the whole
        // tool execution (content_block_start cleared it and no text can stream until
        // the result), so such deltas must not re-open the visible-text phase —
        // otherwise the spinner vanishes for the entire blocking command.
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        List<Boolean> windows = new ArrayList<>();
        dispatcher.onStreamTextVisibility(windows::add);

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "first"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Bash|tool-1|msg-1"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "\n"), panel);
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), windows,
            "a straggler delta while a tool is unresolved must not re-open visibility");

        // After the result resolves the tool, the next model round's text re-opens.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "Bash|ok"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "next round"), panel);
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE), windows,
            "text streamed after the tool resolved is visible again");
    }

    @Test
    void whitespaceOnlyDeltaNeverOpensWindowNorErasesToolHeader() {
        // Regression: a stray "\n" delta opened the rollback window before
        // tool_streaming_start; the next delta's re-render truncated the panel to
        // the stream snapshots and erased the freshly drawn tool header (observed
        // with an OpenAI-compatible provider: Bash(sleep N) header vanished, only
        // the ⎿ result body remained).
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        List<Boolean> windows = new ArrayList<>();
        dispatcher.onStreamTextVisibility(windows::add);

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "\n"), panel);
        assertEquals(0, panel.snapshotLineCount(),
            "a whitespace-only first delta must not open the streaming window");
        assertEquals(0, windows.size(), "no visibility transition for invisible text");

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Bash|tool-1|msg-1"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Bash|tool-1|{\"command\":\"sleep 20\"}"), panel);
        int linesAfterHeader = panel.snapshotLineCount();
        assertTrue(panel.displayRowsForTest(100).stream()
                        .anyMatch(row -> row.text().contains("Bash(sleep 20)")),
            "tool header must render");

        // The second stray delta must not truncate the header away.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "\n"), panel);
        assertEquals(linesAfterHeader, panel.snapshotLineCount(),
            "stray whitespace delta must not shrink the panel");
        assertTrue(panel.displayRowsForTest(100).stream()
                        .anyMatch(row -> row.text().contains("Bash(sleep 20)")),
            "tool header must survive stray whitespace deltas");
    }
}
