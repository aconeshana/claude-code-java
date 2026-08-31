package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.Usage;
import com.googlecode.lanterna.TerminalSize;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import java.time.Instant;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentProgressRenderingTest {

    @Test
    void exploreUsesSubtypeAsVisibleNameAndReleasedCompletedSummary() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "调查终端 UI", "Explore", false);

        dispatcher.dispatch(completed("agent-call-1", "Explore",
            "The model-facing exploration report", 37, 101_900, 306_000), panel);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "Explore(调查终端 UI)"), text);
        assertTrue(Strings.CS.contains(text,
            "Done (37 tool uses · 101.9k tokens · 5m 6s)"), text);
        assertTrue(Strings.CS.contains(text, "(ctrl+o to expand)"), text);
        assertFalse(Strings.CS.contains(text, "The model-facing exploration report"), text);
    }

    @Test
    void generalPurposeAndWorkerKeepReleasedAgentLabel() {
        for (String type : List.of("general-purpose", "worker")) {
            LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
            MessagePanel panel = new MessagePanel();
            startAgent(dispatcher, panel, "agent-" + type, "inspect code", type, false);

            String text = text(panel);
            assertTrue(Strings.CS.contains(text, "Agent(inspect code)"), text);
            assertFalse(Strings.CS.contains(text, type + "(inspect code)"), text);
        }
    }

    @Test
    void asyncLaunchUsesReleasedBackgroundedAgentResult() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-async", "trace rendering", "Explore", true);

        Map<String, Object> payload = Map.of(
            "status", "async_launched",
            "agentId", "a1",
            "description", "trace rendering",
            "prompt", "Inspect the rendering pipeline",
            "outputFile", "/tmp/a1.output",
            "canReadOutputFile", true);
        dispatcher.dispatch(toolResult("agent-call-async", "Async agent launched", payload), panel);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "Explore(trace rendering)"), text);
        assertTrue(Strings.CS.contains(text, "Backgrounded agent"), text);
        assertTrue(Strings.CS.contains(text, "↓ manage"), text);
        assertTrue(Strings.CS.contains(text, "ctrl+o to expand"), text);
        assertFalse(Strings.CS.contains(text, "Async agent launched"), text);
    }

    @Test
    void asyncLaunchTranscriptKeepsThePrompt() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTranscriptMode(true);
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-async", "trace rendering", "Explore", true);

        Map<String, Object> payload = Map.of(
            "status", "async_launched",
            "agentId", "a1",
            "description", "trace rendering",
            "prompt", "Inspect the rendering pipeline",
            "outputFile", "/tmp/a1.output");
        dispatcher.dispatch(toolResult("agent-call-async", "Async agent launched", payload), panel);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "Prompt:"), text);
        assertTrue(Strings.CS.contains(text, "Inspect the rendering pipeline"), text);
        assertTrue(Strings.CS.contains(text, "Backgrounded agent"), text);
    }

    @Test
    void transcriptModeShowsPromptAndResponseBeforeTheCompletionSummary() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTranscriptMode(true);
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-transcript", "inspect UI", "Explore", false);
        dispatcher.dispatch(progress("agent-call-transcript", "Read", "alpha.java"), panel);
        dispatcher.dispatch(progressResult("agent-call-transcript", "child-alpha.java",
            "loaded alpha source", false), panel);

        dispatcher.dispatch(completed("agent-call-transcript", "Explore",
            "Found the renderer", 1, 900, 4_000), panel);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "Prompt:"), text);
        assertTrue(Strings.CS.contains(text, "Inspect the rendering pipeline"), text);
        assertTrue(Strings.CS.contains(text, "Read(alpha.java)"), text);
        assertTrue(Strings.CS.contains(text, "loaded alpha source"), text);
        assertTrue(Strings.CS.contains(text, "Response:"), text);
        assertTrue(Strings.CS.contains(text, "Found the renderer"), text);
        assertTrue(Strings.CS.contains(text,
            "Done (1 tool use · 900 tokens · 4s)"), text);
        assertFalse(Strings.CS.contains(text, "(ctrl+o to expand)"), text);
    }

    @Test
    void transcriptModeRendersTheFailedChildToolResultInsteadOfACompletionProjection() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTranscriptMode(true);
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-error", "inspect UI", "Explore", false);
        dispatcher.dispatch(progress("agent-call-error", "Read", "broken.java"), panel);
        dispatcher.dispatch(progressResult("agent-call-error", "child-broken.java",
            "permission denied while reading broken.java", true), panel);

        UserMessage message = new UserMessage("result-agent-call-error",
            MessageContent.ofToolResult("agent-call-error",
                List.of(new TextBlock("Error: sub-agent execution failed")), true));
        dispatcher.dispatch(new SDKMessage.User(message), panel);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "permission denied while reading broken.java"), text);
        assertTrue(Strings.CS.contains(text, "sub-agent execution failed"), text);
        assertFalse(Strings.CS.contains(text, "Done ("), text);
    }

    @Test
    void failedAgentKeepsItsProgressAndUsesTheSpecializedHeader() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-error", "inspect UI", "Explore", false);
        dispatcher.dispatch(progress("agent-call-error", "Read", "alpha.java"), panel);

        UserMessage message = new UserMessage("result-agent-call-error",
            MessageContent.ofToolResult("agent-call-error",
                List.of(new TextBlock("Error: sub-agent execution failed")), true));
        dispatcher.dispatch(new SDKMessage.User(message), panel);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "Explore(inspect UI)"), text);
        assertTrue(Strings.CS.contains(text, "Read(alpha.java)"), text);
        assertTrue(Strings.CS.contains(text, "sub-agent execution failed"), text);
        assertFalse(Strings.CS.contains(text, "Done ("), text);
    }

    @Test
    void keepsRecentThreeActivitiesInsideTheOwningAgentCard() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "first");

        for (int i = 1; i <= 4; i++) {
            dispatcher.dispatch(progress("agent-call-1", "Read", "file-" + i), panel);
        }

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "+1 more tool uses (ctrl+o to expand)"));
        assertFalse(Strings.CS.contains(text, "file-1"));
        assertTrue(Strings.CS.contains(text, "file-2"));
        assertTrue(Strings.CS.contains(text, "file-4"));
        assertFalse(Strings.CS.contains(text, "Initializing"));
    }

    @Test
    void parallelAgentsDoNotOverwriteEachOthersProgress() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "first");
        startAgent(dispatcher, panel, "agent-call-2", "second");

        dispatcher.dispatch(progress("agent-call-1", "Read", "alpha.java"), panel);
        dispatcher.dispatch(progress("agent-call-2", "Grep", "beta"), panel);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "alpha.java"));
        assertTrue(Strings.CS.contains(text, "beta"));
    }

    @Test
    void outOfOrderAgentResultsCompleteTheHeaderWithTheSameToolUseId() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "first", "Explore", false, "msg-1");
        startAgent(dispatcher, panel, "agent-call-2", "second", "Explore", false, "msg-2");

        dispatcher.dispatch(completed("agent-call-2", "Explore",
            "second report", 1, 200, 1_000), panel);

        assertEquals(LanternaTheme.welcomeDim(),
            lineContaining(panel, "Explore(first)").segments().getFirst().color());
        assertEquals(LanternaTheme.toolSuccess(),
            lineContaining(panel, "Explore(second)").segments().getFirst().color());
    }

    @Test
    void ctrlOReplayUngroupsResolvedAgentsAndShowsTheFailedAgentsRawDetail() {
        var firstInput = JsonUtils.getMapper().createObjectNode();
        firstInput.put("description", "first");
        firstInput.put("prompt", "Inspect first");
        firstInput.put("subagent_type", "Explore");
        var secondInput = JsonUtils.getMapper().createObjectNode();
        secondInput.put("description", "second");
        secondInput.put("prompt", "Inspect second");
        secondInput.put("subagent_type", "Explore");
        AssistantMessage uses = new AssistantMessage("assistant-agents",
            AssistantContent.of("shared-message", List.of(
                new ToolUseBlock("agent-call-1", "Agent", firstInput),
                new ToolUseBlock("agent-call-2", "Agent", secondInput)), Usage.EMPTY));
        UserMessage failure = new UserMessage("result-agent-call-2",
            MessageContent.ofToolResult("agent-call-2",
                List.of(new TextBlock("sub-agent execution failed")), true),
            false, false, Map.of(
                "status", "failed", "prompt", "Inspect second",
                "agentId", "agent-2", "agentType", "Explore",
                "error", "sub-agent execution failed"),
            MessageOrigin.USER, null, Instant.now(), null, null);
        List<SDKMessage> history = List.of(
            new SDKMessage.Assistant(uses, Usage.EMPTY),
            progress("agent-call-1", "Read", "alpha.java"),
            progressResult("agent-call-2", "child-second",
                "permission denied in second agent", true),
            completed("agent-call-1", "Explore", "first report", 1, 100, 1_000),
            new SDKMessage.User(failure));
        TranscriptRenderModel model = TranscriptRenderModel.from(history);
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTranscriptMode(true);
        dispatcher.setTranscriptRenderModel(model);
        MessageCollapser collapser = new MessageCollapser(dispatcher, false);
        collapser.setShowAll(true);
        MessagePanel panel = new MessagePanel();

        for (SDKMessage event : model.events()) collapser.dispatch(event, panel);

        String text = text(panel);
        assertFalse(Strings.CS.contains(text, "2 Explore agents finished"), text);
        assertTrue(Strings.CS.contains(text, "Explore(first)"), text);
        assertTrue(Strings.CS.contains(text, "Explore(second)"), text);
        assertTrue(Strings.CS.contains(text, "Read(alpha.java)"), text);
        assertTrue(Strings.CS.contains(text, "permission denied in second agent"), text);
        assertTrue(Strings.CS.contains(text, "sub-agent execution failed"), text);
    }

    @Test
    void parallelExploreAgentsUseTheReleasedGroupedTree() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "inspect renderer", "Explore", false);
        startAgent(dispatcher, panel, "agent-call-2", "inspect tests", "Explore", false);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text,
            "Running 2 Explore agents… (ctrl+o to expand)"), text);
        assertTrue(Strings.CS.contains(text, "├─ inspect renderer · 0 tool uses"), text);
        assertTrue(Strings.CS.contains(text, "└─ inspect tests · 0 tool uses"), text);
        assertFalse(Strings.CS.contains(text, "Explore(inspect renderer)"), text);
        assertFalse(Strings.CS.contains(text, "Explore(inspect tests)"), text);
    }

    @Test
    void aThirdParallelAgentExtendsTheExistingGroup() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "inspect renderer", "Explore", false);
        startAgent(dispatcher, panel, "agent-call-2", "inspect tests", "Explore", false);
        startAgent(dispatcher, panel, "agent-call-3", "inspect wiring", "Explore", false);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text,
            "Running 3 Explore agents… (ctrl+o to expand)"), text);
        assertTrue(Strings.CS.contains(text, "└─ inspect wiring · 0 tool uses"), text);
        assertFalse(Strings.CS.contains(text, "Explore(inspect wiring)"), text);
    }

    @Test
    void groupingLaterSiblingDoesNotResetEarlierAgentUsage() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "inspect renderer",
            "Explore", false, "msg-shared");
        dispatcher.dispatch(progress(
            "agent-call-1", "Read", "alpha.java", 1_400), panel);

        startAgent(dispatcher, panel, "agent-call-2", "inspect tests",
            "Explore", false, "msg-shared");

        String rendered = text(panel);
        assertTrue(Strings.CS.contains(rendered,
            "inspect renderer · 1 tool use · 1.4k tokens"), rendered);
        assertFalse(Strings.CS.contains(rendered,
            "inspect renderer · 0 tool uses"), rendered);
    }

    @Test
    void reverseStreamingDoneOrderKeepsOriginalAgentBlockOrderAndArguments() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        for (String id : List.of("agent-call-1", "agent-call-2", "agent-call-3")) {
            dispatcher.dispatch(new SDKMessage.StreamEvent(
                "tool_streaming_start", "Agent|" + id + "|msg-shared"), panel);
        }
        for (int i : List.of(3, 2, 1)) {
            var input = JsonUtils.getMapper().createObjectNode();
            input.put("description", "agent " + i);
            input.put("prompt", "Inspect " + i);
            input.put("subagent_type", "Explore");
            dispatcher.dispatch(new SDKMessage.StreamEvent("tool_streaming_done",
                "Agent|agent-call-" + i + "|" + input), panel);
        }

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "Running 3 Explore agents…"), text);
        int first = text.indexOf("├─ agent 1");
        int second = text.indexOf("├─ agent 2");
        int third = text.indexOf("└─ agent 3");
        assertTrue(first >= 0 && first < second && second < third, text);
    }

    @Test
    void onlyAgentsFromTheSameAssistantResponseAreGrouped() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "first response",
            "Explore", false, "msg-1");
        startAgent(dispatcher, panel, "agent-call-2", "second response",
            "Explore", false, "msg-2");

        String text = text(panel);
        assertFalse(Strings.CS.contains(text, "Running 2 Explore agents…"), text);
        assertTrue(Strings.CS.contains(text, "Explore(first response)"), text);
        assertTrue(Strings.CS.contains(text, "Explore(second response)"), text);
    }

    @Test
    void agentsFromOneAssistantResponseUseTheGroupedTree() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "inspect renderer",
            "Explore", false, "msg-shared");
        startAgent(dispatcher, panel, "agent-call-2", "inspect tests",
            "Explore", false, "msg-shared");

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "Running 2 Explore agents…"), text);
        assertTrue(Strings.CS.contains(text, "├─ inspect renderer · 0 tool uses"), text);
        assertTrue(Strings.CS.contains(text, "└─ inspect tests · 0 tool uses"), text);
    }

    @Test
    void groupedHeaderKeepsBlinkingUntilEveryAgentResolves() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        RecordingBlinkPanel panel = new RecordingBlinkPanel();
        startAgent(dispatcher, panel, "agent-call-1", "inspect renderer", "Explore", false);
        startAgent(dispatcher, panel, "agent-call-2", "inspect tests", "Explore", false);

        int headerLine = lineIndexContaining(panel, "Running 2 Explore agents…");
        assertTrue(panel.blinkingLines.contains(headerLine), panel.blinkingLines.toString());

        dispatcher.dispatch(completed("agent-call-1", "Explore",
            "first report", 1, 900, 1_000), panel);
        assertTrue(panel.blinkingLines.contains(headerLine), panel.blinkingLines.toString());

        dispatcher.dispatch(completed("agent-call-2", "Explore",
            "second report", 1, 900, 1_000), panel);
        assertFalse(panel.blinkingLines.contains(headerLine), panel.blinkingLines.toString());
    }

    @Test
    void groupedTreePreservesReleasedBoldAndDimStyles() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "inspect renderer", "Explore", false);
        startAgent(dispatcher, panel, "agent-call-2", "inspect tests", "Explore", false);

        var header = lineContaining(panel, "Running 2 Explore agents…");
        assertTrue(header.segments().stream().anyMatch(segment ->
            Strings.CS.equals("2", segment.text()) && segment.modifiers().contains(SGR.BOLD)),
            header.segments().toString());
        var member = lineContaining(panel, "inspect renderer · 0 tool uses");
        assertTrue(member.segments().stream().anyMatch(segment ->
            Strings.CS.equals("inspect renderer", segment.text())
                && segment.modifiers().contains(SGR.BOLD)), member.segments().toString());

        dispatcher.dispatch(completed("agent-call-1", "Explore",
            "first report", 1, 900, 1_000), panel);
        member = lineContaining(panel, "inspect renderer · 1 tool use · 900 tokens");
        assertEquals(LanternaTheme.welcomeDim(),
            member.segments().getFirst().color(), "resolved tree connector remains dim");
        assertTrue(member.segments().stream().anyMatch(segment ->
            Strings.CS.equals("inspect renderer", segment.text())
                && segment.modifiers().contains(SGR.BOLD)), member.segments().toString());
    }

    @Test
    void groupedExpandHintUsesTheLiveKeybinding(@TempDir Path tempDir) throws Exception {
        Path bindings = tempDir.resolve("keybindings.json");
        Files.writeString(bindings, """
            [{"context":"Global","bindings":{"ctrl+x":"app:toggleTranscript"}}]
            """);
        UserKeybindingsStore store = enabledStore(bindings);
        try {
            LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
            dispatcher.setKeybindingsStore(store);
            MessagePanel panel = new MessagePanel();
            startAgent(dispatcher, panel, "agent-call-1", "inspect renderer", "Explore", false);
            startAgent(dispatcher, panel, "agent-call-2", "inspect tests", "Explore", false);

            String text = text(panel);
            assertTrue(Strings.CS.contains(text, "(ctrl+x to expand)"), text);
            assertFalse(Strings.CS.contains(text, "(ctrl+o to expand)"), text);
        } finally {
            store.dispose();
        }
    }

    @Test
    void groupedExploreAgentsFinishInPlace() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "inspect renderer", "Explore", false);
        startAgent(dispatcher, panel, "agent-call-2", "inspect tests", "Explore", false);
        dispatcher.dispatch(progress("agent-call-1", "Read", "alpha.java", 900), panel);

        dispatcher.dispatch(completed("agent-call-1", "Explore",
            "first report", 1, 900, 1_000), panel);
        assertTrue(Strings.CS.contains(text(panel), "Running 2 Explore agents…"), text(panel));
        assertTrue(Strings.CS.contains(text(panel), "inspect renderer · 1 tool use · 900 tokens"),
            text(panel));

        dispatcher.dispatch(completed("agent-call-2", "Explore",
            "second report", 2, 1_200, 2_000), panel);
        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "2 Explore agents finished (ctrl+o to expand)"), text);
        assertTrue(Strings.CS.contains(text, "⎿  Done"), text);
        assertFalse(Strings.CS.contains(text, "first report"), text);
        assertFalse(Strings.CS.contains(text, "second report"), text);
    }

    @Test
    void groupedAsyncAgentsUseTheReleasedManageHeader() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "inspect renderer", "Explore", true);
        startAgent(dispatcher, panel, "agent-call-2", "inspect tests", "Explore", true);

        Map<String, Object> first = Map.of(
            "status", "async_launched", "agentId", "a1",
            "prompt", "Inspect the rendering pipeline", "outputFile", "/tmp/a1");
        Map<String, Object> second = Map.of(
            "status", "async_launched", "agentId", "a2",
            "prompt", "Inspect the rendering pipeline", "outputFile", "/tmp/a2");
        dispatcher.dispatch(toolResult("agent-call-1", "launched", first), panel);
        dispatcher.dispatch(toolResult("agent-call-2", "launched", second), panel);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text,
            "2 background agents launched (↓ manage)"), text);
        assertFalse(Strings.CS.contains(text, "ctrl+o to expand"), text);
        assertFalse(Strings.CS.contains(text, "⎿"), text);
    }

    @Test
    void backgroundHintIsAnIndependentPersistentRow() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "first");
        dispatcher.showAgentBackgroundHint("agent-call-1", panel);
        dispatcher.dispatch(progress("agent-call-1", "Read", "alpha.java"), panel);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text, "Read(alpha.java)"));
        assertTrue(Strings.CS.contains(text, "Press Ctrl+B to run in background"));
    }

    @Test
    void completingOneParallelAgentOnlyRemovesItsOwnProgress() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        startAgent(dispatcher, panel, "agent-call-1", "first");
        startAgent(dispatcher, panel, "agent-call-2", "second");
        dispatcher.dispatch(progress("agent-call-1", "Read", "alpha.java"), panel);
        dispatcher.dispatch(progress("agent-call-2", "Grep", "beta"), panel);

        dispatcher.clearAgentProgress("agent-call-1", panel);

        String text = text(panel);
        assertFalse(Strings.CS.contains(text, "alpha.java"));
        assertTrue(Strings.CS.contains(text, "beta"));

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "Agent|second finished"), panel);
        assertTrue(Strings.CS.contains(text(panel), "2 agents finished"),
            "earlier Agent-group reflow must not corrupt the later member anchor");
    }

    @Test
    void shortTerminalUsesReleasedCondensedProgressSummary() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 12));
        startAgent(dispatcher, panel, "agent-call-1", "first");
        dispatcher.dispatch(progress("agent-call-1", "Read", "alpha.java", 123), panel);

        String text = text(panel);
        assertTrue(Strings.CS.contains(text,
            "In progress… · 1 tool use · 123 tokens · (ctrl+o to expand)"));
        assertFalse(Strings.CS.contains(text, "alpha.java"));
    }

    @Test
    void shortTerminalUsesCompactTokenFormatting() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 12));
        startAgent(dispatcher, panel, "agent-call-compact", "first");
        dispatcher.dispatch(progress(
            "agent-call-compact", "Read", "alpha.java", 101_900), panel);

        assertTrue(Strings.CS.contains(text(panel), "101.9k tokens"), text(panel));
    }

    @Test
    void finalizedUsageRefreshDoesNotDuplicateTheSameChildToolUse() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 12));
        startAgent(dispatcher, panel, "agent-call-finalized", "inspect usage");

        dispatcher.dispatch(progressWithIdentity(
            "agent-call-finalized", "assistant-1", "child-read", 123), panel);
        dispatcher.dispatch(progressWithIdentity(
            "agent-call-finalized", "assistant-1", "child-read", 456), panel);

        String rendered = text(panel);
        assertTrue(Strings.CS.contains(rendered,
            "In progress… · 1 tool use · 456 tokens"), rendered);
        assertFalse(Strings.CS.contains(rendered, "2 tool uses"), rendered);
    }

    @Test
    void startingAnotherStandaloneAgentReflowsExistingProgressWithItsUsage() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 24));
        startAgent(dispatcher, panel, "agent-call-first", "first",
            "Explore", false, "message-first");
        dispatcher.dispatch(progress(
            "agent-call-first", "Read", "alpha.java", 123), panel);

        assertTrue(Strings.CS.contains(text(panel), "Read(alpha.java)"), text(panel));

        startAgent(dispatcher, panel, "agent-call-second", "second",
            "Explore", false, "message-second");

        String rendered = text(panel);
        assertTrue(Strings.CS.contains(rendered,
            "In progress… · 1 tool use · 123 tokens · (ctrl+o to expand)"), rendered);
        assertFalse(Strings.CS.contains(rendered, "Read(alpha.java)"), rendered);
    }

    @Test
    void completingAStandaloneAgentReflowsTheRemainingProgressCard() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 24));
        startAgent(dispatcher, panel, "agent-call-first", "first",
            "Explore", false, "message-first");
        dispatcher.dispatch(progressWithIdentity(
            "agent-call-first", "assistant-first", "child-first", 123), panel);
        startAgent(dispatcher, panel, "agent-call-second", "second",
            "Explore", false, "message-second");
        dispatcher.dispatch(progressWithIdentity(
            "agent-call-second", "assistant-second", "child-second", 456), panel);
        dispatcher.dispatch(progressWithIdentity(
            "agent-call-first", "assistant-first", "child-first", 123), panel);

        assertTrue(Strings.CS.contains(text(panel),
            "In progress… · 1 tool use · 123 tokens"), text(panel));

        dispatcher.clearAgentProgress("agent-call-second", panel);

        String rendered = text(panel);
        assertTrue(Strings.CS.contains(rendered, "Read(alpha.java)"), rendered);
        assertFalse(Strings.CS.contains(rendered, "In progress…"), rendered);
    }

    @Test
    void parallelAgentCardsKeepTheSameUsageProjectionAfterMainTranscriptReplay() {
        String firstInput = "{\"description\":\"first\","
            + "\"prompt\":\"Inspect the rendering pipeline\","
            + "\"subagent_type\":\"general-purpose\"}";
        String secondInput = "{\"description\":\"second\","
            + "\"prompt\":\"Inspect the rendering pipeline\","
            + "\"subagent_type\":\"general-purpose\"}";
        List<SDKMessage> events = List.of(
            agentStream("tool_streaming_start",
                "Agent|agent-call-first|message-first"),
            agentStream("tool_streaming_done",
                "Agent|agent-call-first|" + firstInput),
            agentStream("tool_call_start",
                "Agent|agent-call-first|" + firstInput),
            agentStream("tool_streaming_start",
                "Agent|agent-call-second|message-second"),
            agentStream("tool_streaming_done",
                "Agent|agent-call-second|" + secondInput),
            agentStream("tool_call_start",
                "Agent|agent-call-second|" + secondInput),
            agentStart("agent-call-first", "first"),
            agentStart("agent-call-second", "second"),
            progressWithIdentity(
                "agent-call-first", "assistant-first", "child-first", 123),
            progressWithIdentity(
                "agent-call-second", "assistant-second", "child-second", 456));

        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessageCollapser collapser = new MessageCollapser(dispatcher, false);
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 24));
        MessageHistory history = new MessageHistory();
        for (SDKMessage event : events) {
            history.record(event);
            collapser.dispatch(event, panel);
        }
        String live = text(panel);

        panel.clear();
        collapser.resetTurn();
        for (SDKMessage event : history.events()) collapser.dispatch(event, panel);

        String replayed = text(panel);
        assertEquals(live, replayed);
        assertTrue(Strings.CS.contains(replayed,
            "In progress… · 1 tool use · 123 tokens"), replayed);
        assertTrue(Strings.CS.contains(replayed,
            "In progress… · 1 tool use · 456 tokens"), replayed);
    }

    @Test
    void agentCardHasTheSameProjectionAfterMainTranscriptReplay() {
        List<SDKMessage> events = List.of(
            agentStream("tool_streaming_start", "Agent|agent-call-replay"),
            agentStream("tool_streaming_done",
                "Agent|agent-call-replay|{\"description\":\"inspect replay\","
                    + "\"prompt\":\"Inspect the rendering pipeline\","
                    + "\"subagent_type\":\"general-purpose\"}"),
            agentStream("tool_call_start",
                "Agent|agent-call-replay|{\"description\":\"inspect replay\","
                    + "\"prompt\":\"Inspect the rendering pipeline\","
                    + "\"subagent_type\":\"general-purpose\"}"),
            agentStart("agent-call-replay", "inspect replay"),
            progressWithIdentity("agent-call-replay", "assistant-1", "child-read", 456));

        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessageCollapser collapser = new MessageCollapser(dispatcher, false);
        MessagePanel panel = new MessagePanel();
        panel.setSize(new TerminalSize(80, 12));
        MessageHistory history = new MessageHistory();
        for (SDKMessage event : events) {
            history.record(event);
            collapser.dispatch(event, panel);
        }
        String live = text(panel);

        panel.clear();
        collapser.resetTurn();
        for (SDKMessage event : history.events()) collapser.dispatch(event, panel);

        assertEquals(live, text(panel));
    }

    private static void startAgent(LanternaMessageDispatcher dispatcher,
            MessagePanel panel, String id, String description) {
        startAgent(dispatcher, panel, id, description, null, false);
    }

    private static void startAgent(LanternaMessageDispatcher dispatcher,
            MessagePanel panel, String id, String description, String type,
            boolean background) {
        startAgent(dispatcher, panel, id, description, type, background, null);
    }

    private static void startAgent(LanternaMessageDispatcher dispatcher,
            MessagePanel panel, String id, String description, String type,
            boolean background, String messageId) {
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("description", description);
        input.put("prompt", "Inspect the rendering pipeline");
        if (type != null) input.put("subagent_type", type);
        if (background) input.put("run_in_background", true);
        String json = input.toString();
        String batch = messageId == null ? "" : "|" + messageId;
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Agent|" + id + batch), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent("tool_streaming_done",
            "Agent|" + id + "|" + json), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent("tool_call_start", "Agent|" + id + "|" + json), panel);
    }

    private static MessagePanel.StyledLine lineContaining(MessagePanel panel, String needle) {
        return panel.snapshotStyledLines().stream()
            .filter(line -> Strings.CS.contains(line.text(), needle))
            .findFirst().orElseThrow(() -> new AssertionError("Missing line: " + needle));
    }

    private static int lineIndexContaining(MessagePanel panel, String needle) {
        List<MessagePanel.StyledLine> lines = panel.snapshotStyledLines();
        for (int i = 0; i < lines.size(); i++) {
            if (Strings.CS.contains(lines.get(i).text(), needle)) return i;
        }
        throw new AssertionError("Missing line: " + needle);
    }

    private static UserKeybindingsStore enabledStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    private static final class RecordingBlinkPanel extends MessagePanel {
        private final Set<Integer> blinkingLines = new HashSet<>();

        @Override
        public void startBlinkLine(int lineIndex, List<Segment> segments) {
            blinkingLines.add(lineIndex);
        }

        @Override
        public void stopBlinkLine(int lineIndex, List<Segment> finalSegments) {
            blinkingLines.remove(lineIndex);
            if (finalSegments != null) updateLine(lineIndex, finalSegments);
        }
    }

    private static SDKMessage.User completed(String toolUseId, String type,
            String content, int toolUses, long tokens, long durationMs) {
        Map<String, Object> payload = Map.of(
            "status", "completed",
            "prompt", "Inspect the rendering pipeline",
            "agentId", "a1",
            "agentType", type,
            "content", List.of(Map.of("type", "text", "text", content)),
            "totalDurationMs", durationMs,
            "totalTokens", tokens,
            "totalToolUseCount", toolUses,
            "usage", Usage.EMPTY);
        return toolResult(toolUseId, content, payload);
    }

    private static SDKMessage.User toolResult(String toolUseId, String content,
            Object payload) {
        UserMessage message = new UserMessage("result-" + toolUseId,
            MessageContent.ofToolResult(toolUseId, List.of(new TextBlock(content)), false),
            false, false, payload, MessageOrigin.USER, null, Instant.now(),
            null, null);
        return new SDKMessage.User(message);
    }

    private static SDKMessage.Progress progress(String parentToolUseId, String tool,
            String argument) {
        return progress(parentToolUseId, tool, argument, 0);
    }

    private static SDKMessage.Progress progressWithIdentity(String parentToolUseId,
            String assistantUuid, String childToolUseId, long tokens) {
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("file_path", "alpha.java");
        AssistantMessage message = new AssistantMessage(assistantUuid,
            AssistantContent.of("message-" + assistantUuid,
                List.of(new ToolUseBlock(childToolUseId, "Read", input)),
                new Usage(tokens, 0, 0, 0)));
        ProgressMessage.ProgressData data = new ProgressMessage.ProgressData(
            "agent_progress", null, null, null, null, null, null, null, true,
            message, "", "agent-id");
        return new SDKMessage.Progress(new ProgressMessage(
            "progress-" + tokens, "", null, Instant.now(),
            parentToolUseId, null, data));
    }

    private static SDKMessage agentStart(String toolUseId, String description) {
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("description", description);
        input.put("prompt", "Inspect the rendering pipeline");
        input.put("subagent_type", "general-purpose");
        AssistantMessage message = new AssistantMessage("start-" + toolUseId,
            AssistantContent.of(List.of(new ToolUseBlock(toolUseId, "Agent", input))));
        return new SDKMessage.Assistant(message, Usage.EMPTY);
    }

    private static SDKMessage agentStream(String eventType, String data) {
        return new SDKMessage.StreamEvent(eventType, data);
    }

    private static SDKMessage.Progress progress(String parentToolUseId, String tool,
            String argument, long tokens) {
        var input = JsonUtils.getMapper().createObjectNode();
        if (Strings.CS.equals("Read", tool)) input.put("file_path", argument);
        else input.put("pattern", argument);
        AssistantMessage message = new AssistantMessage("assistant-" + argument,
            AssistantContent.of("message-" + argument,
                List.of(new ToolUseBlock("child-" + argument, tool, input)),
                new Usage(tokens, 0, 0, 0)));
        ProgressMessage.ProgressData data = new ProgressMessage.ProgressData(
            "agent_progress", null, null, null, null, null, null, null, true,
            message, "", "agent-id");
        return new SDKMessage.Progress(new ProgressMessage(
            "progress-" + argument, "", null, Instant.now(),
            parentToolUseId, null, data));
    }

    private static SDKMessage.Progress progressResult(String parentToolUseId,
            String childToolUseId, String content, boolean error) {
        UserMessage message = new UserMessage("child-result-" + childToolUseId,
            MessageContent.ofToolResult(childToolUseId,
                List.of(new TextBlock(content)), error));
        ProgressMessage.ProgressData data = new ProgressMessage.ProgressData(
            "agent_progress", null, null, null, null, null, null, null, false,
            message, "Inspect the rendering pipeline", "agent-id");
        return new SDKMessage.Progress(new ProgressMessage(
            "progress-result-" + childToolUseId, "", null, Instant.now(),
            parentToolUseId, null, data));
    }

    private static String text(MessagePanel panel) {
        return panel.snapshotStyledLines().stream().map(MessagePanel.StyledLine::text)
            .reduce("", (left, right) -> left + "\n" + right);
    }
}
