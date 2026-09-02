package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.message.*;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.tools.tasks.PendingBackgroundWork;
import com.googlecode.lanterna.TextColor;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared replay path behind {@code /resume}'s restore and the picker's
 * session preview: every persisted entry must reach the live-streaming
 * pipeline in transcript order, with the turn buffer flushed at both ends.
 */
class TranscriptReplayTest {

    /** Records what reaches the dispatcher instead of rendering it. */
    private static final class CapturingDispatcher extends LanternaMessageDispatcher {
        final List<SDKMessage> seen = new ArrayList<>();

        @Override
        public void dispatch(SDKMessage msg, MessagePanel panel) {
            seen.add(msg);
        }
    }

    /** Records appended rows so the interrupt sentinel line can be asserted. */
    private static final class StubPanel extends MessagePanel {
        final List<String> lines = new ArrayList<>();

        @Override
        public void appendMixed(List<MessagePanel.Segment> segments) {
            StringBuilder sb = new StringBuilder();
            for (MessagePanel.Segment s : segments) sb.append(s.text());
            lines.add(sb.toString());
        }

        @Override
        public void appendLine(String text, TextColor color) {
            lines.add(text);
        }
    }

    private record Env(CapturingDispatcher dispatcher, MessageCollapser collapser, StubPanel panel) {}

    private static Env env() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        return new Env(dispatcher, new MessageCollapser(dispatcher, true), new StubPanel());
    }

    private static UserMessage user(String uuid, String text) {
        return new UserMessage(uuid, MessageContent.ofText(text));
    }

    private static AssistantMessage assistant(String uuid, String text) {
        return new AssistantMessage(uuid,
            AssistantContent.of(List.<ContentBlock>of(new TextBlock(text))));
    }

    @Test
    void everySupportedMessageKindReachesTheDispatcherInOrder() {
        Env e = env();
        List<Message> msgs = List.of(
            user("u1", "first"),
            assistant("a1", "reply"),
            new SystemMessage("s1", "info", "info", "note"),
            user("u2", "second"));

        TranscriptReplay.replay(msgs, e.collapser, e.panel, null);

        assertEquals(4, e.dispatcher.seen.size(), "all four entries must be dispatched");
        assertInstanceOf(SDKMessage.User.class, e.dispatcher.seen.getFirst());
        assertInstanceOf(SDKMessage.Assistant.class, e.dispatcher.seen.get(1));
        assertInstanceOf(SDKMessage.System.class, e.dispatcher.seen.get(2));
        assertTrue(e.dispatcher.seen.get(3) instanceof SDKMessage.User u
            && Strings.CS.equals("second", u.message().message().text()));
    }

    @Test
    void interruptSentinelBecomesAnInterruptedRowAndSkipsTheDispatcher() {
        Env e = env();
        TranscriptReplay.replay(
            List.of(user("u1", MessageConstants.INTERRUPT_MESSAGE), user("u2", "after")),
            e.collapser, e.panel, null);

        assertTrue(e.panel.lines.stream().anyMatch(l -> Strings.CS.contains(l, "Interrupted")),
            "the sentinel must render as an Interrupted row: " + e.panel.lines);
        assertEquals(1, e.dispatcher.seen.size(),
            "the raw sentinel must not reach the dispatcher (it swallows the text)");
    }

    @Test
    void toolUseInterruptSentinelIsRecognisedToo() {
        assertTrue(TranscriptReplay.isInterruptMessage(
            user("u1", MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE)));
        assertTrue(TranscriptReplay.isInterruptMessage(
            new UserMessage("u2", MessageContent.ofBlocks(
                List.of(new TextBlock(MessageConstants.INTERRUPT_MESSAGE))))),
            "a single-block content carrying the sentinel counts as well");
        assertFalse(TranscriptReplay.isInterruptMessage(user("u3", "ordinary text")));
        assertFalse(TranscriptReplay.isInterruptMessage(new UserMessage("u4", null)));
    }

    @Test
    void recorderSeesEveryWrappedMessageIncludingTheSentinel() {
        Env e = env();
        List<SDKMessage> recorded = new ArrayList<>();
        TranscriptReplay.replay(
            List.of(user("u1", MessageConstants.INTERRUPT_MESSAGE), assistant("a1", "reply")),
            e.collapser, e.panel, recorded::add);

        assertEquals(2, recorded.size(),
            "Ctrl+O's overlay replays the sentinel row too, so it must be recorded");
        assertInstanceOf(SDKMessage.User.class, recorded.getFirst());
        assertInstanceOf(SDKMessage.Assistant.class, recorded.get(1));
    }

    @Test
    void emptyOrNullInputIsANoOp() {
        Env e = env();
        assertDoesNotThrow(() -> TranscriptReplay.replay(null, e.collapser, e.panel, null));
        assertDoesNotThrow(() -> TranscriptReplay.replay(List.of(), e.collapser, e.panel, null));
        assertTrue(e.dispatcher.seen.isEmpty());
        assertTrue(e.panel.lines.isEmpty());
    }

    @Test
    void unsupportedMessageKindsAreSkippedWithoutBreakingTheRest() {
        Env e = env();
        List<Message> msgs = List.of(
            new TombstoneMessage("t1", "u0", null, null),
            user("u1", "kept"));

        TranscriptReplay.replay(msgs, e.collapser, e.panel, null);

        assertEquals(1, e.dispatcher.seen.size(),
            "an unmapped entry is dropped, the following one still renders");
    }

    @Test
    void theTurnBufferIsFlushedAfterTheFinalMessage() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        List<String> resets = new ArrayList<>();
        MessageCollapser collapser = new MessageCollapser(dispatcher, true) {
            @Override
            public void resetTurn() {
                resets.add("reset");
                super.resetTurn();
            }
        };
        TranscriptReplay.replay(List.of(user("u1", "hi")), collapser, new StubPanel(), null);

        assertEquals(2, resets.size(),
            "a trailing tool-use would stay invisible without the closing flush");
    }

    @Test
    void persistedTurnDurationRendersFromDurationMsEvenWithoutContent() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessageCollapser collapser = new MessageCollapser(dispatcher, true);
        StubPanel panel = new StubPanel();

        TranscriptReplay.replay(List.of(MessageFactory.createTurnDurationMessage(
            60_000L, 3)), collapser, panel, null);

        assertTrue(panel.lines.stream().anyMatch(line ->
            Strings.CS.endsWith(line, " for 1m 0s")), panel.lines.toString());
    }

    @Test
    void replayedWaitingCountsAreGatedByCurrentlyPendingWork() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTurnSummaryContext(() -> PendingBackgroundWork.NONE, () -> null);
        MessageCollapser collapser = new MessageCollapser(dispatcher, true);
        StubPanel panel = new StubPanel();

        TranscriptReplay.replay(List.of(MessageFactory.createTurnDurationMessage(
            12_000L, 3, 2, 1)), collapser, panel, null);

        assertTrue(panel.lines.stream().anyMatch(line ->
            Strings.CS.contains(line, " for 12s")), panel.lines.toString());
        assertFalse(panel.lines.stream().anyMatch(line ->
            Strings.CS.contains(line, "Waiting for")), panel.lines.toString());
    }

    @Test
    void replayedDurationUsesPersistedCountsWhenThatWorkIsStillPending() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTurnSummaryContext(
            () -> new PendingBackgroundWork(1, 0), () -> "1 background agent");
        MessageCollapser collapser = new MessageCollapser(dispatcher, true);
        StubPanel panel = new StubPanel();

        TranscriptReplay.replay(List.of(MessageFactory.createTurnDurationMessage(
            12_000L, 3, 2, 1)), collapser, panel, null);

        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line,
            "Waiting for 2 background agents to finish")), panel.lines.toString());
        assertFalse(panel.lines.stream().anyMatch(line ->
            Strings.CS.contains(line, "dynamic workflow")), panel.lines.toString());
    }

    @Test
    void resumedAgentResultHydratesSidechainMessagesBeforeRecordingTheResult() {
        Env e = env();
        List<SDKMessage> recorded = new ArrayList<>();
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("prompt", "Inspect replay");
        input.put("description", "inspect replay");
        input.put("subagent_type", "Explore");
        AssistantMessage use = new AssistantMessage("agent-use",
            AssistantContent.of(List.of(new ToolUseBlock("agent-call", "Agent", input))));
        UserMessage result = new UserMessage("agent-result",
            MessageContent.ofToolResult("agent-call",
                List.of(new TextBlock("finished")), false), false, false,
            Map.of(
                "status", "completed",
                "prompt", "Inspect replay",
                "agentId", "agent-123",
                "agentType", "Explore",
                "content", List.of()),
            MessageOrigin.USER, null, Instant.now(), null, null);
        AssistantMessage child = new AssistantMessage("child-assistant",
            AssistantContent.of(List.of(new TextBlock("sidechain detail"))));

        TranscriptReplay.replay(List.of(use, result), e.collapser, e.panel,
            recorded::add, _ -> List.of(
                new UserMessage("child-prompt", MessageContent.ofText("Inspect replay")),
                child));

        assertEquals(3, recorded.size());
        assertInstanceOf(SDKMessage.Assistant.class, recorded.getFirst());
        SDKMessage.Progress progress = assertInstanceOf(
            SDKMessage.Progress.class, recorded.get(1));
        assertEquals("agent-call", progress.message().toolUseId());
        assertSame(child, progress.message().data().message());
        assertInstanceOf(SDKMessage.User.class, recorded.getLast());
    }

    @Test
    void missingResumedSidechainFallsBackToThePersistedAgentResult() {
        Env e = env();
        List<SDKMessage> recorded = new ArrayList<>();
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("prompt", "Inspect replay");
        AssistantMessage use = new AssistantMessage("agent-use",
            AssistantContent.of(List.of(new ToolUseBlock("agent-call", "Agent", input))));
        UserMessage result = new UserMessage("agent-result",
            MessageContent.ofToolResult("agent-call",
                List.of(new TextBlock("persisted failure")), true), false, false,
            Map.of(
                "status", "failed", "prompt", "Inspect replay",
                "agentId", "missing-agent", "agentType", "Explore",
                "error", "persisted failure"),
            MessageOrigin.USER, null, Instant.now(), null, null);

        assertDoesNotThrow(() -> TranscriptReplay.replay(
            List.of(use, result), e.collapser, e.panel, recorded::add,
            _ -> { throw new IllegalStateException("missing transcript"); }));

        assertEquals(2, recorded.size());
        assertInstanceOf(SDKMessage.Assistant.class, recorded.getFirst());
        assertInstanceOf(SDKMessage.User.class, recorded.getLast());
    }
}
