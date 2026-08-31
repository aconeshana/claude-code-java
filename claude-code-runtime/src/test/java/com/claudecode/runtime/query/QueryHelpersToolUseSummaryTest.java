package com.claudecode.runtime.query;

import com.claudecode.core.engine.*;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the tool-use-summary wiring now owned by {@link QueryHelpers}: the fire-point gate ({@link
 * QueryHelpers#shouldFireToolUseSummary}), the tool-batch/last-assistant-text extraction helpers,
 * and the end-to-end default-off contract.
 */
class QueryHelpersToolUseSummaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── shouldFireToolUseSummary: pure gate predicate ─────────────────────

    private static final ToolBatchSummarizer STUB_SUMMARIZER =
        (_, _, _) -> CompletableFuture.completedFuture("Fixed NPE");

    private static Function<String, String> envWith(String value) {
        return key -> Strings.CS.equals("CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES", key) ? value : null;
    }

    @Test
    void fires_whenEnabledNoAgentIdNotAborted() {
        assertTrue(QueryHelpers.shouldFireToolUseSummary(
            STUB_SUMMARIZER, null, false, envWith("1")));
    }

    @Test
    void doesNotFire_whenEnvVarUnset() {
        assertFalse(QueryHelpers.shouldFireToolUseSummary(
            STUB_SUMMARIZER, null, false, envWith(null)));
    }

    @Test
    void doesNotFire_whenNoSummarizerWired() {
        assertFalse(QueryHelpers.shouldFireToolUseSummary(
            null, null, false, envWith("1")));
    }

    @Test
    void doesNotFire_forSubAgents() {

        assertFalse(QueryHelpers.shouldFireToolUseSummary(
            STUB_SUMMARIZER, "a1234567890abcde", false, envWith("1")));
    }

    @Test
    void doesNotFire_whenAborted() {
        assertFalse(QueryHelpers.shouldFireToolUseSummary(
            STUB_SUMMARIZER, null, true, envWith("1")));
    }

    @Test
    void envTruthyVariants_caseInsensitive() {
        for (String truthy : List.of("1", "true", "TRUE", "yes", "YES", "on", "On")) {
            assertTrue(QueryHelpers.shouldFireToolUseSummary(
                STUB_SUMMARIZER, null, false, envWith(truthy)), "expected truthy: " + truthy);
        }
        for (String falsy : List.of("0", "false", "no", "off", "", "  ")) {
            assertFalse(QueryHelpers.shouldFireToolUseSummary(
                STUB_SUMMARIZER, null, false, envWith(falsy)), "expected falsy: " + falsy);
        }
    }

    // ── extractLastAssistantText ───────────────────────────────────────────

    @Test
    void extractLastAssistantText_returnsLastTextBlockOnly() {
        AssistantMessage msg = new AssistantMessage("uuid-1", AssistantContent.of(
            "msg-1", List.of(new TextBlock("first"), new TextBlock("last"))));
        assertEquals("last", QueryHelpers.extractLastAssistantText(msg));
    }

    @Test
    void extractLastAssistantText_nullForNullMessage() {
        assertNull(QueryHelpers.extractLastAssistantText(null));
    }

    @Test
    void extractLastAssistantText_nullWhenNoTextBlocks() {
        AssistantMessage msg = new AssistantMessage("uuid-1", AssistantContent.of(
            "msg-1", List.of(new ToolUseBlock("tu-1", "Bash", MAPPER.createObjectNode()))));
        assertNull(QueryHelpers.extractLastAssistantText(msg));
    }

    // ── buildToolCallInfo ───────────────────────────────────────────────────

    @Test
    void buildToolCallInfo_pairsToolUseWithItsResult() {
        ObjectNode input = MAPPER.createObjectNode().put("command", "ls");
        ToolUseBlock toolUse = new ToolUseBlock("tu-1", "Bash", input);

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return Collections.emptyIterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .build());
        engine.getMutableMessages().add(new UserMessage("u-1",
            MessageContent.ofToolResult("tu-1", List.of(new TextBlock("file1.txt")), false)));

        List<ToolCallInfo> result = QueryHelpers.buildToolCallInfo(List.of(toolUse), engine);

        assertEquals(1, result.size());
        assertEquals("Bash", result.getFirst().name());
        assertEquals(input, result.getFirst().input());
        assertEquals(List.of(new TextBlock("file1.txt")), result.getFirst().output());
    }

    @Test
    void buildToolCallInfo_nullOutputWhenNoResultFound() {
        ToolUseBlock toolUse = new ToolUseBlock("tu-1", "Bash", MAPPER.createObjectNode());
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return Collections.emptyIterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .build());

        List<ToolCallInfo> result = QueryHelpers.buildToolCallInfo(List.of(toolUse), engine);

        assertEquals(1, result.size());
        assertNull(result.getFirst().output());
    }

    // ── End-to-end: default-off contract ────────────────────────────────────

    private static StreamingClient multiTurnClient(List<List<StreamingClient.StreamingEvent>> turns) {
        AtomicInteger callCount = new AtomicInteger(0);
        return new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                int idx = callCount.getAndIncrement();
                return idx < turns.size() ? turns.get(idx).iterator() : Collections.emptyIterator();
            }
            @Override
            public String getModel() { return "test-model"; }
        };
    }

    private static class RecordingToolExecutor implements ToolExecutor {
        @Override
        public ToolResult execute(String toolName, JsonNode input,
                                   ToolExecutionContext context) {
            return ToolResult.success("Executed: " + toolName);
        }
    }

    private List<SDKMessage> drain(Iterator<SDKMessage> iter) {
        List<SDKMessage> messages = new ArrayList<>();
        while (iter.hasNext()) messages.add(iter.next());
        return messages;
    }

    /**
     * The test JVM never has {@code CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES} set,
     * so this exercises the real default-off path end-to-end even though a
     * {@link ToolBatchSummarizer} is wired — a regression guard against the
     * feature silently defaulting to on. The "on" path's fire→consume
     * mechanics are covered by {@link #shouldFireToolUseSummary} above plus
     * manual verification (see plan's validation section).
     */
    @Test
    void toolUseSummaryNeverEmitted_whenEnvVarUnset() {
        var turn1Events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(10, 5, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tu-1", "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"command\":\"ls\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
        var turn2Events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-2", "test-model", List.of(), new Usage(20, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "Done!"),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 10, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(multiTurnClient(List.of(turn1Events, turn2Events)))
            .toolExecutor(new RecordingToolExecutor())
            .toolBatchSummarizer(STUB_SUMMARIZER)
            .build());

        List<SDKMessage> messages = drain(engine.submitMessage("List files", SubmitOptions.DEFAULT));

        assertTrue(messages.stream().noneMatch(SDKMessage.ToolUseSummary.class::isInstance),
            "CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES is unset in the test env — no summary should be emitted");
        // Sanity: the batch actually ran (proves the gate, not a broken loop, suppressed it).
        assertEquals(2, messages.stream().filter(SDKMessage.Assistant.class::isInstance).count());
    }
}
