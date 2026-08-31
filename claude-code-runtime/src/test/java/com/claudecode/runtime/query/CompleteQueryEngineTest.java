package com.claudecode.runtime.query;

import com.claudecode.core.engine.*;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the complete DefaultQuerySession (Task 6).
 * Covers multi-turn tool_use loop, budget limits, max turns,
 * structured output, processUserInput, systemInitMessage, and more.
 */
class CompleteQueryEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- Helper: mock streaming client ----

    private static StreamingClient mockClient(List<StreamingClient.StreamingEvent> events) {
        return new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return events.iterator();
            }
            @Override
            public String getModel() { return "test-model"; }
        };
    }

    /**
     * Creates a mock client that returns different events on each call.
     */
    private static StreamingClient multiTurnClient(List<List<StreamingClient.StreamingEvent>> turns) {
        AtomicInteger callCount = new AtomicInteger(0);
        return new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                int idx = callCount.getAndIncrement();
                if (idx < turns.size()) {
                    return turns.get(idx).iterator();
                }
                // Fallback: empty response
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("msg-end", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "Done"),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }
            @Override
            public String getModel() { return "test-model"; }
        };
    }

    /**
     * Creates a recording tool executor that tracks calls.
     */
    private static class RecordingToolExecutor implements ToolExecutor {
        final List<String> executedTools = new ArrayList<>();
        final Map<String, ToolResult> results = new HashMap<>();

        RecordingToolExecutor withResult(String toolName, ToolResult result) {
            results.put(toolName, result);
            return this;
        }

        @Override
        public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context) {
            executedTools.add(toolName);
            return results.getOrDefault(toolName, ToolResult.success("Executed: " + toolName));
        }
    }

    private List<SDKMessage> drain(Iterator<SDKMessage> iter) {
        List<SDKMessage> messages = new ArrayList<>();
        while (iter.hasNext()) {
            messages.add(iter.next());
        }
        return messages;
    }

    private <T> T findFirst(List<SDKMessage> messages, Class<T> type) {
        return messages.stream()
            .filter(type::isInstance)
            .map(type::cast)
            .findFirst()
            .orElse(null);
    }

    private <T> List<T> findAll(List<SDKMessage> messages, Class<T> type) {
        return messages.stream()
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    }

    // ========== Task 6.1: processUserInput tests ==========

    @Test
    void processUserInput_slashHelp_returnsLocalCommand() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).build());

        ProcessedInput result = engine.processUserInput("/help");
        assertFalse(result.shouldQuery());
        assertTrue(result.localCommandResult().isPresent());
        assertTrue(Strings.CS.contains(result.localCommandResult().get(), "Available commands"));
    }

    @Test
    void processUserInput_slashExit_returnsLocalCommand() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).build());

        ProcessedInput result = engine.processUserInput("/exit");
        assertFalse(result.shouldQuery());
        assertTrue(Strings.CS.contains(result.localCommandResult().get(), "Goodbye"));
    }

    @Test
    void processUserInput_slashClear_clearsMessages() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).build());

        // Add a message first
        engine.getMutableMessages().add(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("test")));
        assertEquals(1, engine.getMessages().size());

        ProcessedInput result = engine.processUserInput("/clear");
        assertFalse(result.shouldQuery());
        assertEquals(0, engine.getMessages().size());
    }

    @Test
    void processUserInput_slashModel_changesModel() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).model("old-model").build());

        ProcessedInput result = engine.processUserInput("/model new-model");
        assertFalse(result.shouldQuery());
        assertEquals("new-model", engine.getConfig().model());
    }

    @Test
    void processUserInput_slashModelNoArg_showsCurrent() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).model("my-model").build());

        ProcessedInput result = engine.processUserInput("/model");
        assertFalse(result.shouldQuery());
        assertTrue(Strings.CS.contains(result.localCommandResult().get(), "my-model"));
    }

    @Test
    void processUserInput_unknownSlashCommand_treatedAsQuery() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).build());

        ProcessedInput result = engine.processUserInput("/unknown stuff");
        assertTrue(result.shouldQuery());
        assertEquals("/unknown stuff", result.processedPrompt());
    }

    @Test
    void processUserInput_normalText_treatedAsQuery() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).build());

        ProcessedInput result = engine.processUserInput("Hello world");
        assertTrue(result.shouldQuery());
        assertEquals("Hello world", result.processedPrompt());
    }

    @Test
    void processUserInput_normalTextPreservesWireSignificantWhitespace() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).build());

        ProcessedInput result = engine.processUserInput("  first line\nsecond line\n");

        assertTrue(result.shouldQuery());
        assertEquals("  first line\nsecond line\n", result.processedPrompt());
    }

    @Test
    void processUserInput_blankInput_treatedAsQuery() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).build());

        ProcessedInput result = engine.processUserInput("  ");
        assertTrue(result.shouldQuery());
    }

    // ========== Task 6.2: fetchSystemPromptParts tests ==========

    @Test
    void fetchSystemPromptParts_includesBasePrompt() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of()))
            .systemPrompt("You are a helpful assistant")
            .build());

        String prompt = engine.fetchSystemPromptParts();
        assertTrue(Strings.CS.contains(prompt, "You are a helpful assistant"));
    }

    @Test
    void fetchSystemPromptParts_doesNotPlaintextDumpArbitraryToolNames() {
// getSystemPrompt never emits an "Available tools:" plaintext dump of config.tools.
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of()))
            .tools(List.of("FileRead", "Grep"))
            .build());

        String prompt = engine.fetchSystemPromptParts();
        assertFalse(Strings.CS.contains(prompt, "Available tools:"),
            "must never emit an 'Available tools:' plaintext dump");
        assertFalse(Strings.CS.contains(prompt, "FileRead"),
            "arbitrary custom tool names must not leak into the prompt");
    }

    @Test
    void fetchSystemPromptParts_doesNotPlaintextDumpMcpServerNames() {

        // plaintext line. MCP surface area comes only via
        // getMcpInstructionsSection() and requires the server to have
        // advertised `instructions` — bare name lists are not exposed.
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of()))
            .mcpServers(List.of("github-mcp", "slack-mcp"))
            .build());

        String prompt = engine.fetchSystemPromptParts();
        assertFalse(Strings.CS.contains(prompt, "MCP servers:"),
            "TS never emits an 'MCP servers:' plaintext dump");
        assertFalse(Strings.CS.contains(prompt, "github-mcp"),
            "MCP server names alone must not appear in the prompt");
        assertFalse(Strings.CS.contains(prompt, "slack-mcp"),
            "MCP server names alone must not appear in the prompt");
    }

    @Test
    void fetchSystemPromptParts_includesSystemContext() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of()))
            .systemPrompt(null)
            .build());

        String prompt = engine.fetchSystemPromptParts();
        assertTrue(Strings.CS.contains(prompt, "# Environment"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Primary working directory:"), prompt);
    }

    @Test
    void fetchSystemPromptParts_omitsGitStatusWhenGitInstructionsDisabled() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of()))
            .includeGitInstructionsSupplier(() -> false)
            .build());

        String prompt = engine.fetchSystemPromptParts();

        assertFalse(Strings.CS.contains(prompt, "gitStatus:"), prompt);
        assertFalse(Strings.CS.contains(prompt, "This is the git status at the start of the conversation"), prompt);
    }

    // ========== Task 6.3: Multi-turn tool_use loop tests ==========

    @Test
    void multiTurnToolUseLoop_executesToolAndContinues() {
        ObjectNode toolInput = MAPPER.createObjectNode().put("command", "ls");

        // Turn 1: assistant requests tool_use via content_block_start/delta/stop
        var turn1Events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(10, 5, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tu-1", "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"command\":\"ls\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        // Turn 2: assistant responds with text (no more tool_use)
        var turn2Events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-2", "test-model", List.of(), new Usage(20, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "Done!"),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 10, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var toolExecutor = new RecordingToolExecutor()
            .withResult("Bash", ToolResult.success("file1.txt\nfile2.txt"));

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(multiTurnClient(List.of(turn1Events, turn2Events)))
            .toolExecutor(toolExecutor)
            .build());

        List<SDKMessage> messages = drain(engine.submitMessage("List files", SubmitOptions.DEFAULT));

        // Tool was executed
        assertEquals(1, toolExecutor.executedTools.size());
        assertEquals("Bash", toolExecutor.executedTools.getFirst());

        // Should have result with success
        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertNotNull(result);
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());

        // Should have 2 assistant messages (one per turn)
        List<SDKMessage.Assistant> assistants = findAll(messages, SDKMessage.Assistant.class);
        assertEquals(2, assistants.size());
    }

    @Test
    void multiTurnToolUseLoop_multipleToolsInOneTurn() {
        // Turn 1: two tool_use blocks via content_block_start/delta/stop
        var turn1Events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(10, 5, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tu-1", "FileRead"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"path\":\"/tmp/a.txt\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(1, "tool_use", "tu-2", "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(1, "input_json_delta", "{\"command\":\"pwd\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(1),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        // Turn 2: text response
        var turn2Events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-2", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "All done"),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var toolExecutor = new RecordingToolExecutor();
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(multiTurnClient(List.of(turn1Events, turn2Events)))
            .toolExecutor(toolExecutor)
            .build());

        List<SDKMessage> messages = drain(engine.submitMessage("Do stuff", SubmitOptions.DEFAULT));

        // Both tools executed
        assertEquals(2, toolExecutor.executedTools.size());
        assertTrue(toolExecutor.executedTools.contains("FileRead"));
        assertTrue(toolExecutor.executedTools.contains("Bash"));

        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
    }


    @Test
    void maxTurns_countsRoundTripsNotYieldedToolResultRows() {
        var turn1Events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(10, 5, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tu-1", "FileRead"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"path\":\"/tmp/a.txt\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(1, "tool_use", "tu-2", "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(1, "input_json_delta", "{\"command\":\"pwd\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(1),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
        var turn2Events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-2", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "All done"),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(multiTurnClient(List.of(turn1Events, turn2Events)))
            .maxTurns(2)
            .toolExecutor(new RecordingToolExecutor())
            .build());

        List<SDKMessage> messages = drain(engine.submitMessage("Do stuff", SubmitOptions.DEFAULT));

        assertFalse(messages.stream().anyMatch(m -> m instanceof SDKMessage.Attachment a
                && Strings.CS.equals("max_turns_reached", a.attachmentType())),
            "one round-trip with a two-tool batch must not exhaust a ceiling of 2");
        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
        assertEquals(3, result.numTurns(),
            "num_turns still counts both yielded tool_result rows — that counter is"
                + " DefaultQuerySession's, and must stay independent of the gate");
    }

    @Test
    void multiTurnToolUseLoop_toolExecutionError_setsErrorFlag() {
        var turn1Events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tu-1", "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"command\":\"bad\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        // Turn 2: text response after error
        var turn2Events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-2", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "Error handled"),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        // Tool executor that throws
        ToolExecutor throwingExecutor = (_, _, _) -> {
            throw new RuntimeException("Tool crashed");
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(multiTurnClient(List.of(turn1Events, turn2Events)))
            .toolExecutor(throwingExecutor)
            .build());

        List<SDKMessage> messages = drain(engine.submitMessage("Run bad", SubmitOptions.DEFAULT));


        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertNotNull(result);
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());

        // The error surfaced to the model as an is_error tool_result in history.
        boolean hasErrorToolResult = engine.getMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .anyMatch(um -> um.message() != null && um.message().blocks() != null
                && um.message().blocks().stream().anyMatch(b ->
                    b instanceof ToolResultBlock trb && trb.isError()
                    && Strings.CS.equals("tu-1", trb.toolUseId())));
        assertTrue(hasErrorToolResult, "Tool exception must land as is_error tool_result");
    }

    // ========== Task 6.5: Budget and max turns tests ==========

    @Test
    void maxTurns_exceeded_yieldsErrorMaxTurns() {
        ObjectNode toolInput = MAPPER.createObjectNode().put("cmd", "x");

        // Client always returns tool_use via content_block_start/delta/stop
        StreamingClient alwaysToolUse = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                String tuId = "tu-" + UUID.randomUUID();
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-" + UUID.randomUUID(), "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockStartEvent(0, "tool_use", tuId, "Bash"),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"cmd\":\"x\"}"),
                    new StreamingEvent.ContentBlockStopEvent(0),
                    new StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }
            @Override
            public String getModel() { return "test-model"; }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(alwaysToolUse)
            .maxTurns(3)
            .toolExecutor(new RecordingToolExecutor())
            .build());

        List<SDKMessage> messages = drain(engine.submitMessage("Loop forever", SubmitOptions.DEFAULT));

        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertNotNull(result);
        assertEquals(SDKMessage.Result.ERROR_MAX_TURNS, result.resultType());

        assertEquals(4, result.numTurns());
        assertEquals(List.of("Reached maximum number of turns (3)"), result.errors());
    }

    /**
     * Stop hook that blocks the first {@code blockTimes} dispatches and then lets
     * the turn end, recording how many dispatches it saw.
     */
    private static final class BlockingStopHooks implements HookDispatcher {
        private final int blockTimes;
        private final AtomicInteger dispatches = new AtomicInteger();

        BlockingStopHooks(int blockTimes) { this.blockTimes = blockTimes; }

        @Override public boolean dispatchPreToolUse(String n, JsonNode i, String id) { return true; }
        @Override public void dispatchPostToolUse(String n, JsonNode i, JsonNode o, String id) {}
        @Override public void dispatchUserPromptSubmit(String prompt) {}
        @Override public void dispatchSessionStart(String trigger) {}
        @Override public void dispatchStop(String reason) {}

        @Override
        public HookOutcome dispatchStopWithOutcome(String reason, boolean stopHookActive) {
            return dispatches.incrementAndGet() <= blockTimes
                ? new HookOutcome(false, null, List.of("keep going"))
                : HookOutcome.PROCEED;
        }
    }

    private static StreamingClient alwaysTextClient() {
        return new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-" + UUID.randomUUID(), "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "still here"),
                    new StreamingEvent.MessageDeltaEvent("end_turn", Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };
    }


    @Test
    void stopHookBlocking_capOverridesTheHookAndEndsTheTurn() {
        var hooks = new BlockingStopHooks(Integer.MAX_VALUE);
        SubprocessEnvironment.updateSettings(Map.of("CLAUDE_CODE_STOP_HOOK_BLOCK_CAP", "2"));
        try {
            var engine = new DefaultQuerySession(QuerySessionSpec.builder()
                .llmClient(alwaysTextClient())
                .toolExecutor(new RecordingToolExecutor())
                .build());
            engine.setHookDispatcher(hooks);

            List<SDKMessage> messages = drain(engine.submitMessage("Go", SubmitOptions.DEFAULT));

            assertEquals(3, hooks.dispatches.get(),
                "the cap trips on the dispatch after the last allowed re-entry");
            String warning = findAll(messages, SDKMessage.System.class).stream()
                .map(sys -> sys.message().content())
                .filter(text -> Strings.CS.contains(text, "blocked the turn from ending"))
                .findFirst().orElse(null);
            assertNotNull(warning, "released 2.1.197 warns before overriding the hook");
            assertTrue(Strings.CS.contains(warning, "3 consecutive times"), warning);
            assertTrue(Strings.CS.contains(warning, "stop_hook_active"), warning);
            SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
            assertEquals(SDKMessage.Result.SUCCESS, result.resultType(),
                "the override ends the turn with {reason:\"completed\"}, not an error");
        } finally {
            SubprocessEnvironment.clearSettings();
        }
    }

    /**
     * The blocking counter is written back as 0 on every non-{@code
     * stop_hook_blocking} transition, so only *consecutive* blocks count. Two
     * blocks separated by a real tool round-trip must both be honoured even at a
     * cap of 1.
     */
    @Test
    void stopHookBlocking_counterResetsOnAnyOtherTransition() {
        var toolTurn = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-tool", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tu-1", "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"cmd\":\"x\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
        var textTurn = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-text", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "ok"),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        // text → block #1 → re-entry runs a tool → text → block #2 → text → end.
        var hooks = new BlockingStopHooks(2);
        SubprocessEnvironment.updateSettings(Map.of("CLAUDE_CODE_STOP_HOOK_BLOCK_CAP", "1"));
        try {
            var engine = new DefaultQuerySession(QuerySessionSpec.builder()
                .llmClient(multiTurnClient(List.of(textTurn, toolTurn, textTurn, textTurn)))
                .toolExecutor(new RecordingToolExecutor())
                .build());
            engine.setHookDispatcher(hooks);

            List<SDKMessage> messages = drain(engine.submitMessage("Go", SubmitOptions.DEFAULT));

            assertFalse(findAll(messages, SDKMessage.System.class).stream()
                    .anyMatch(sys -> Strings.CS.contains(
                        sys.message().content(), "blocked the turn from ending")),
                "a tool round-trip between blocks resets the consecutive counter");
            assertEquals(3, hooks.dispatches.get(),
                "both blocks were honoured and the third dispatch ended the turn");
            assertEquals(SDKMessage.Result.SUCCESS,
                findFirst(messages, SDKMessage.Result.class).resultType());
        } finally {
            SubprocessEnvironment.clearSettings();
        }
    }

    @Test
    void maxBudget_exceeded_yieldsErrorMaxBudget() {
        // Turn 1: expensive + tool_use to force another turn
        var turn1 = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(1_000_000, 500_000, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tu-1", "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"cmd\":\"x\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(multiTurnClient(List.of(turn1)))
            .maxBudgetUsd(0.001) // Very low budget
            .toolExecutor(new RecordingToolExecutor())
            .build());

        List<SDKMessage> messages = drain(engine.submitMessage("Expensive query", SubmitOptions.DEFAULT));

        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertNotNull(result);
        assertEquals(SDKMessage.Result.ERROR_MAX_BUDGET, result.resultType());
    }

    @Test
    void noBudgetLimit_doesNotTriggerBudgetError() {
        var events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(100, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "OK"),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(events))
            .maxBudgetUsd(-1.0) // No budget limit
            .build());

        List<SDKMessage> messages = drain(engine.submitMessage("Hello", SubmitOptions.DEFAULT));

        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
    }

    // ========== Task 6.6: Structured output tests ==========

    @Test
    void structuredOutput_validOutput_returnsSuccess() throws Exception {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("name", "test");
        payload.put("value", 42);

        // Turn 1: model calls the StructuredOutput tool. Turn 2 falls through
        // to multiTurnClient's fallback (plain text, no tool use) — the
        // natural turn end where the Stop-hook enforcement checks for a
        // successful StructuredOutput call and lets the query terminate.
        var turn1 = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tu-1", "StructuredOutput"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"name\":\"test\",\"value\":42}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        RecordingToolExecutor toolExecutor = new RecordingToolExecutor()
            .withResult("StructuredOutput", ToolResult.success("Output recorded").withToolUseResult(payload));

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(multiTurnClient(List.of(turn1)))
            .toolExecutor(toolExecutor)
            .build());

        SubmitOptions opts = SubmitOptions.withSchema("user", schema);
        List<SDKMessage> messages = drain(engine.submitMessage("Generate JSON", opts));

        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertNotNull(result);
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
        assertNotNull(result.structuredOutput());
        assertEquals(42, result.structuredOutput().get("value").asInt());
        assertEquals(1, toolExecutor.executedTools.size());
        assertEquals("StructuredOutput", toolExecutor.executedTools.getFirst());
    }

    // ========== Task 6.7: Orphaned permission tests ==========

    @Test
    void orphanedPermissions_handledOnlyOnce() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).build());

        engine.addPermissionDenial(new SDKMessage.PermissionDenial(
            "Bash", "test-use-id", Map.of("command", "rm -rf /")));

        Optional<String> first = engine.handleOrphanedPermissions();
        assertTrue(first.isPresent());
        assertTrue(Strings.CS.contains(first.get(), "Bash"));
        assertTrue(Strings.CS.contains(first.get(), "test-use-id"));

        Optional<String> second = engine.handleOrphanedPermissions();
        assertTrue(second.isEmpty());
    }

    @Test
    void orphanedPermissions_emptyDenials_returnsEmpty() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).build());

        Optional<String> result = engine.handleOrphanedPermissions();
        assertTrue(result.isEmpty());
        assertTrue(engine.getHasHandledOrphanedPermission());
    }

    @Test
    void permissionDenialsAreScopedPerSubmitWhileDeferredResultKeepsItsTurnBucket() {
        var turn = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-turn", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "OK"),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent());
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(turn)).model("test-model").build());

        Iterator<SDKMessage> firstIterator = engine.submitMessage("first", SubmitOptions.DEFAULT);
        var firstTurnRecorder = engine.permissionDenialRecorder();
        SDKMessage.Result first = findFirst(drain(firstIterator), SDKMessage.Result.class);
        assertNotNull(first);
        assertTrue(first.permissionDenials().isEmpty());

        firstTurnRecorder.accept(new SDKMessage.PermissionDenial(
            "Bash", "toolu_late_child", Map.of("command", "touch /tmp/marker")));
        assertEquals(1, first.permissionDenials().size(),
            "a held-back result must see a late background-child denial from its own turn");

        SDKMessage.Result second = findFirst(
            drain(engine.submitMessage("notification", SubmitOptions.DEFAULT)),
            SDKMessage.Result.class);
        assertNotNull(second);
        assertTrue(second.permissionDenials().isEmpty(),
            "the notification submit must start with a fresh denial bucket");
        assertEquals(1, first.permissionDenials().size(),
            "starting a new submit must not clear the held result's prior bucket");
    }

    // ========== Task 6.8: systemInitMessage tests ==========

    @Test
    void systemInitMessage_isYieldedBeforeQuery() {
        var events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "Hi"),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(events))
            .model("claude-sonnet-4")
            .tools(List.of("Bash", "FileRead"))
            .build());

        List<SDKMessage> messages = drain(engine.submitMessage("Hello", SubmitOptions.DEFAULT));

        // System init should be second message (after user)
        assertInstanceOf(SDKMessage.System.class, messages.get(1));
        SDKMessage.System initMsg = (SDKMessage.System) messages.get(1);
        String content = initMsg.message().content();
        assertTrue(Strings.CS.contains(content, "Session:"));
        assertTrue(Strings.CS.contains(content, "Model: claude-sonnet-4"));
        assertTrue(Strings.CS.contains(content, "Tools: 2"));
        assertTrue(Strings.CS.contains(content, "Source: user"));
    }

    @Test
    void systemInitMessage_containsSessionId() {
        var events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(events)).build());

        List<SDKMessage> messages = drain(engine.submitMessage("Hi", SubmitOptions.DEFAULT));

        // System init is second message
        assertInstanceOf(SDKMessage.System.class, messages.get(1));
        SDKMessage.System initMsg = (SDKMessage.System) messages.get(1);
        assertTrue(Strings.CS.contains(initMsg.message().content(), engine.getSessionId()));
    }

    // ========== Task 6.9: Result message construction tests ==========

    @Test
    void resultMessage_success_normalCompletion() {
        var events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(50, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "Hello"),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 25, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(events)).build());

        List<SDKMessage> messages = drain(engine.submitMessage("Hi", SubmitOptions.DEFAULT));

        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertNotNull(result);
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
        assertNotNull(result.sessionId());
        assertNotNull(result.messages());
        assertFalse(result.messages().isEmpty());
        assertEquals(50, result.totalUsage().inputTokens());
        assertEquals(25, result.totalUsage().outputTokens());
    }

    @Test
    void resultMessage_localCommand_success() {
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(List.of())).build());

        List<SDKMessage> messages = drain(engine.submitMessage("/help", SubmitOptions.DEFAULT));

        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertNotNull(result);
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());

        // Should have system messages: system_init + local_command
        List<SDKMessage.System> systemMsgs = findAll(messages, SDKMessage.System.class);
        assertTrue(systemMsgs.size() >= 2);
        boolean hasLocalCommand = systemMsgs.stream()
            .anyMatch(s -> Strings.CS.equals("local_command", s.message().subtype()));
        assertTrue(hasLocalCommand);
    }

    @Test
    void resultMessage_apiError_yieldsErrorDuringExecution() {
        var events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.ErrorEvent(new RuntimeException("API down"))
        );

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(events)).build());

        List<SDKMessage> messages = drain(engine.submitMessage("Hello", SubmitOptions.DEFAULT));

        // Should have both an Error message and a Result with error type
        boolean hasError = messages.stream().anyMatch(SDKMessage.Error.class::isInstance);
        assertTrue(hasError);

        SDKMessage.Result result = findFirst(messages, SDKMessage.Result.class);
        assertNotNull(result);
        assertEquals(SDKMessage.Result.ERROR_DURING_EXECUTION, result.resultType());
    }

    // ========== CostCalculator tests ==========

    @Test
    void costCalculator_defaultPricing() {
        CostCalculator calc = new CostCalculator();
        Usage usage = new Usage(1_000_000, 1_000_000, 0, 0);
        double cost = calc.calculateCost(usage);
        // 1M input * $3/M + 1M output * $15/M = $18
        assertEquals(18.0, cost, 0.001);
    }

    @Test
    void costCalculator_withCacheTokens() {
        CostCalculator calc = new CostCalculator();
        Usage usage = new Usage(0, 0, 1_000_000, 1_000_000);
        double cost = calc.calculateCost(usage);
        // 1M cache_write * $3.75/M + 1M cache_read * $0.30/M = $4.05
        assertEquals(4.05, cost, 0.001);
    }

    @Test
    void costCalculator_nullUsage_returnsZero() {
        CostCalculator calc = new CostCalculator();
        assertEquals(0.0, calc.calculateCost(null));
    }

    @Test
    void costCalculator_forModel_opusPricing() {
        CostCalculator calc = CostCalculator.forModel("claude-opus-4");
        Usage usage = new Usage(1_000_000, 1_000_000, 0, 0);
        double cost = calc.calculateCost(usage);
        // 1M * $15 + 1M * $75 = $90
        assertEquals(90.0, cost, 0.001);
    }

    @Test
    void costCalculator_unregisteredLegacyHaikuUsesReleasedDefaultPricing() {
        CostCalculator calc = CostCalculator.forModel("claude-haiku-3");
        Usage usage = new Usage(1_000_000, 1_000_000, 0, 0);
        double cost = calc.calculateCost(usage);

        assertEquals(30.0, cost, 0.001);
    }

    // ========== NoOpToolExecutor tests ==========

    @Test
    void noOpToolExecutor_returnsPlaceholder() {
        NoOpToolExecutor executor = new NoOpToolExecutor();
        ToolResult result = executor.execute("Bash", null, null);
        assertFalse(result.isError());
        assertEquals(1, result.content().size());
        assertInstanceOf(TextBlock.class, result.content().getFirst());
        assertTrue(Strings.CS.contains(((TextBlock) result.content().getFirst()).text(), "Bash"));
        assertTrue(Strings.CS.contains(((TextBlock) result.content().getFirst()).text(), "not yet implemented"));
    }

    // ========== ProcessedInput tests ==========

    @Test
    void processedInput_forQuery() {
        ProcessedInput pi = ProcessedInput.forQuery("hello");
        assertTrue(pi.shouldQuery());
        assertTrue(pi.localCommandResult().isEmpty());
        assertEquals("hello", pi.processedPrompt());
    }

    @Test
    void processedInput_forLocalCommand() {
        ProcessedInput pi = ProcessedInput.forLocalCommand("result");
        assertFalse(pi.shouldQuery());
        assertTrue(pi.localCommandResult().isPresent());
        assertEquals("result", pi.localCommandResult().get());
    }

    // ========== ToolResult tests ==========

    @Test
    void toolResult_success() {
        ToolResult result = ToolResult.success("output");
        assertFalse(result.isError());
        assertEquals(1, result.content().size());
    }

    @Test
    void toolResult_error() {
        ToolResult result = ToolResult.error("failed");
        assertTrue(result.isError());
        assertEquals(1, result.content().size());
    }

    // ========== Integration: full flow with slash command ==========

    @Test
    void fullFlow_slashCommand_noApiCall() {
        // The LLM client should NOT be called for slash commands
        AtomicInteger apiCalls = new AtomicInteger(0);
        StreamingClient countingClient = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                apiCalls.incrementAndGet();
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("msg-1", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }
            @Override
            public String getModel() { return "test-model"; }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(countingClient).build());

        List<SDKMessage> messages = drain(engine.submitMessage("/help", SubmitOptions.DEFAULT));

        assertEquals(0, apiCalls.get(), "API should not be called for slash commands");
    }

    // ========== Integration: message ordering ==========

    @Test
    void messageOrdering_userThenInitThenAssistantThenResult() {
        var events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "Reply"),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(mockClient(events)).build());

        List<SDKMessage> messages = drain(engine.submitMessage("Hello", SubmitOptions.DEFAULT));

        // Expected order: User, System(init), StreamEvent(delta), Assistant, Result
        assertTrue(messages.size() >= 4);
        assertInstanceOf(SDKMessage.User.class, messages.getFirst());
        assertInstanceOf(SDKMessage.System.class, messages.get(1)); // system_init
        // Last message is always Result
        SDKMessage last = messages.getLast();
        assertInstanceOf(SDKMessage.Result.class, last);
    }
}
