package com.claudecode.tools;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolContextModifier;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolSearchGate;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.engine.ToolExecutionContext.ProgressSink;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.permissions.AutoModeClassifier;
import com.claudecode.permissions.DecisionReason;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.PermissionDecisionResult;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.RuleSource;
import com.claudecode.permissions.ToolPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.bash.BashPermissions;
import com.claudecode.tools.bash.BashTool;
import com.claudecode.tools.files.FileReadTool;
import com.claudecode.tools.files.FileWriteTool;
import com.claudecode.tools.files.GrepTool;
import com.claudecode.tools.output.SyntheticOutputTool;
import com.claudecode.tools.agent.AgentTool;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.agent.SubAgentResult;

class ToolRegistryTest {

    private ToolRegistry registry;
    private ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path projectDir;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @AfterEach
    void resetToolSearchProtocolResolver() {
        ToolSearchGate.configureProtocolResolver(null);
    }

    @Test
    void registerAndGetTool() {
        Tool<JsonNode, String> tool = new ToolBuilder<JsonNode, String>()
                .name("test-tool")
                .description("A test tool")
                .call((_, _) -> "result")
                .build();

        registry.register(tool);

        assertTrue(registry.get("test-tool").isPresent());
        assertEquals("test-tool", registry.get("test-tool").get().name());
    }

    @Test
    void aliasesResolveForExecutionWithoutDuplicatingWireDefinitions() {
        Tool<JsonNode, String> tool = new Tool<>() {
            @Override public ToolIdentity identity() {
                return new ToolIdentity("Canonical", List.of("LegacyAlias"));
            }
            @Override public String description() { return "test"; }
            @Override public JsonNode inputSchema() { return mapper.createObjectNode().put("type", "object"); }
            @Override public String call(JsonNode input, ToolExecutionContext context) { return "ok"; }
            @Override public PermissionDecision checkPermissions(JsonNode input,
                    ToolPermissionContext context) {
                return new PermissionDecision.Allow();
            }
        };
        registry.register(tool);

        assertSame(tool, registry.get("LegacyAlias").orElseThrow());
        assertEquals("ok", firstText(registry.execute("LegacyAlias", mapper.createObjectNode(),
            ToolExecutionContext.of(new AbortController(), "session"))));
        assertEquals(List.of("Canonical"), registry.getToolDefinitions().stream()
            .map(StreamingClient.StreamRequest.ToolDef::name).toList());
    }

    @Test
    void agentToolAutoAllowsLaunchWithoutOpeningPermissionPrompt() {
        AtomicBoolean asked = new AtomicBoolean(false);
        registry.register(new AgentTool(_ -> SubAgentResult.of("explored")));
        registry.setPermissionGate(new PermissionGate());

        ObjectNode input = mapper.createObjectNode();
        input.put("description", "inspect module");
        input.put("prompt", "inspect module");
        input.put("subagent_type", "Explore");
        ToolExecutionContext context = ToolExecutionContext
            .builder(new AbortController(), "session-197")
            .workingDirectory(System.getProperty("user.dir"))
            .currentModel("claude-sonnet-4-6")
            .permissionAskCallback(_ -> {
                asked.set(true);
                return PermissionAskCallback.Result.deny();
            })
            .build();

        ToolResult result = registry.execute("Agent", input, context);

        assertFalse(asked.get(), "released 2.1.197 auto-approves foreground Agent launches");
        assertFalse(result.isError(), firstText(result));
        assertTrue(Strings.CS.contains(firstText(result), "explored"));
    }

    @Test
    void getUnknownToolReturnsEmpty() {
        assertTrue(registry.get("nonexistent").isEmpty());
    }

    @Test
    void getAllReturnsAllRegistered() {
        registry.register(new BashTool());
        registry.register(new FileReadTool());
        assertEquals(2, registry.getAll().size());
    }

    @Test
    void toolDefinitionsAreSortedByNameForStableWireCacheOrder() {
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("zeta").description("z").call((_, _) -> "z").build());
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Alpha").description("a").call((_, _) -> "a").build());
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Beta").description("b").call((_, _) -> "b").build());

        assertEquals(List.of("Alpha", "Beta", "zeta"),
            registry.getToolDefinitions().stream()
                .map(StreamingClient.StreamRequest.ToolDef::name).toList());
    }

    @Test
    void filterByReadOnly() {
        registry.register(new BashTool());
        registry.register(new FileReadTool());
        registry.register(new GrepTool());

        var readOnlyTools = registry.filter(Tool::isReadOnly);
        assertEquals(2, readOnlyTools.size());
    }

    @Test
    void executeUnknownToolReturnsError() {
        ObjectNode input = mapper.createObjectNode();
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("unknown", input, ctx);
        assertTrue(result.isError());

        assertEquals("<tool_use_error>Error: No such tool available: unknown</tool_use_error>",
            firstText(result));
    }



    @Test
    void executeMissingRequiredParamReturnsInputValidationError() {
        registry.register(new FileReadTool());
        ObjectNode input = mapper.createObjectNode(); // no file_path
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("Read", input, ctx);
        assertTrue(result.isError());

        assertEquals("""
                <tool_use_error>InputValidationError: Read failed due to the following issue:
                The required parameter `file_path` is missing</tool_use_error>""",
            firstText(result));
    }

    @Test
    void executeWrongParamTypeReturnsInputValidationError() {
        registry.register(new FileReadTool());
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", 42); // should be string
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("Read", input, ctx);
        assertTrue(result.isError());
        assertEquals("""
                <tool_use_error>InputValidationError: Read failed due to the following issue:
                The parameter `file_path` type is expected as `string` but provided as `number`</tool_use_error>""",
            firstText(result));
    }

    @Test
    void executeUnexpectedParamReturnsInputValidationError() {
        registry.register(new FileReadTool());
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "/tmp/x.txt");
        input.put("bogus_param", "y"); // strictObject: unrecognized key
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("Read", input, ctx);
        assertTrue(result.isError());
        assertEquals("""
                <tool_use_error>InputValidationError: Read failed due to the following issue:
                An unexpected parameter `bogus_param` was provided</tool_use_error>""",
            firstText(result));
    }

    @Test
    void executeMultipleValidationIssuesListsAll() {
        registry.register(new FileReadTool());
        ObjectNode input = mapper.createObjectNode();
        input.put("bogus_param", "y");
        input.put("offset", "not-a-number");
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("Read", input, ctx);
        assertTrue(result.isError());
        String text = firstText(result);
        // Multiple problems → "issues" plural, missing first, then unexpected, then type
        assertTrue(Strings.CS.startsWith(text, "<tool_use_error>InputValidationError: Read failed due to the following issues:\n"), text);
        assertTrue(Strings.CS.contains(text, "The required parameter `file_path` is missing"), text);
        assertTrue(Strings.CS.contains(text, "An unexpected parameter `bogus_param` was provided"), text);
        assertTrue(Strings.CS.contains(text, "The parameter `offset` type is expected as `number` but provided as `string`"), text);
    }

    @Test
    void mcpToolsSkipInputValidation() {

        Tool<JsonNode, String> mcpTool = new ToolBuilder<JsonNode, String>()
                .name("mcp__server__thing")
                .description("mcp tool")
                .call((_, _) -> "ok")
                .build();
        registry.register(mcpTool);
        ObjectNode input = mapper.createObjectNode();
        input.put("whatever", "value");
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("mcp__server__thing", input, ctx);
        assertFalse(result.isError());
        assertEquals("ok", firstText(result));
    }

    @Test
    void structuredOutputToolSkipsStructuralValidation() {

        // the base passthrough inputSchema safeParse gate — so the structural
        // check must never run against the caller-supplied schema. If it did,
// this would fail here as an InputValidationError before call runs.
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("name").put("type", "string");
        schema.putArray("required").add("name");

        registry.register(new SyntheticOutputTool(schema));
        ObjectNode input = mapper.createObjectNode(); // missing required "name"
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("StructuredOutput", input, ctx);

        assertTrue(result.isError());
// The mismatch is caught by the tool's own call (SchemaValidator), not
        // the structural gate — so it's a plain formatError(e) message, not the
        // "<tool_use_error>InputValidationError: ..." wrapper.
        String text = firstText(result);
        assertFalse(Strings.CS.contains(text, "InputValidationError"), text);
        assertTrue(Strings.CS.startsWith(text, "Output does not match required schema:"), text);
    }

    @Test
    void structuredOutputToolAcceptsValidInputThroughFullPipeline() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        registry.register(new SyntheticOutputTool(schema));
        ObjectNode input = mapper.createObjectNode();
        input.put("name", "test");
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("StructuredOutput", input, ctx);

        assertFalse(result.isError());
        assertEquals(input, result.toolUseResult());
    }

    @Test
    void executeRegisteredTool() {
        registry.register(new FileReadTool());
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "nonexistent-file.txt");

        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");
        ToolResult result = registry.execute("Read", input, ctx);
        // Should return error since file doesn't exist, but not a "Unknown tool" error
        assertFalse(result.content().isEmpty());
    }

    @Test
    void registerReplacesExisting() {
        Tool<JsonNode, String> tool1 = new ToolBuilder<JsonNode, String>()
                .name("test")
                .description("first")
                .call((_, _) -> "first")
                .build();
        Tool<JsonNode, String> tool2 = new ToolBuilder<JsonNode, String>()
                .name("test")
                .description("second")
                .call((_, _) -> "second")
                .build();

        registry.register(tool1);
        registry.register(tool2);

        assertEquals(1, registry.size());
        assertEquals("second", registry.get("test").get().description());
    }

    // ── StructuredToolOutput dual-channel flow ──────────────────────────────

    @Test
    void structuredToolOutputSplitsIntoTextAndToolUseResult() {
        FileChangeResult payload = FileChangeResult.created("/tmp/x.txt", "hello");
        Tool<JsonNode, Object> tool = new ToolBuilder<JsonNode, Object>()
                .name("fake-structured")
                .description("returns a StructuredToolOutput")
                .call((_, _) -> new StructuredToolOutput("text for the model", payload))
                .build();
        registry.register(tool);
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("fake-structured", mapper.createObjectNode(), ctx);

        assertFalse(result.isError());
        assertEquals("text for the model", firstText(result));
        assertSame(payload, result.toolUseResult());
    }

    @Test
    void plainStringOutputKeepsNullToolUseResult() {
        Tool<JsonNode, String> tool = new ToolBuilder<JsonNode, String>()
                .name("fake-plain")
                .description("returns a plain string")
                .call((_, _) -> "plain text")
                .build();
        registry.register(tool);
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("fake-plain", mapper.createObjectNode(), ctx);

        assertFalse(result.isError());
        assertEquals("plain text", firstText(result));
        assertNull(result.toolUseResult());
    }

    @Test
    void registryConsumesAtomicCallResultWithoutReinvokingLegacyHooks() {
        AtomicInteger atomicCalls = new AtomicInteger();
        Tool<JsonNode, String> tool = new Tool<>() {
            @Override public ToolIdentity identity() { return new ToolIdentity("atomic-result"); }
            @Override public String description() { return "test"; }
            @Override public JsonNode inputSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            @Override public String call(JsonNode input, ToolExecutionContext context) {
                fail("registry must use callWithResult");
                return "unreachable";
            }
            @Override public ToolResult mapResult(
                    Object rawResult, JsonNode input, ToolExecutionContext context) {
                fail("atomic result must not be mapped a second time");
                return null;
            }
            @Override public ToolCallResult<String> callWithResult(
                    JsonNode input, ToolExecutionContext context) {
                atomicCalls.incrementAndGet();
                ObjectNode payload = mapper.createObjectNode().put("invocation", "same-call");
                return new ToolCallResult<>("raw",
                    ToolResult.success("model text").withToolUseResult(payload));
            }
        };
        registry.register(tool);

        ToolResult result = registry.execute("atomic-result", mapper.createObjectNode(),
            ToolExecutionContext.of(new AbortController(), "test-session"));

        assertEquals(1, atomicCalls.get());
        assertEquals("model text", firstText(result));
        assertEquals("same-call", ((JsonNode) result.toolUseResult()).path("invocation").asText());
    }

    @Test
    void defaultAtomicCallResultPreservesLegacyMapResultTools() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger mappings = new AtomicInteger();
        Tool<JsonNode, String> tool = new Tool<>() {
            @Override public ToolIdentity identity() { return new ToolIdentity("legacy-mapper"); }
            @Override public String description() { return "test"; }
            @Override public JsonNode inputSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            @Override public String call(JsonNode input, ToolExecutionContext context) {
                calls.incrementAndGet();
                return input.path("value").asText();
            }
            @Override public ToolResult mapResult(
                    Object rawResult, JsonNode input, ToolExecutionContext context) {
                mappings.incrementAndGet();
                return ToolResult.success("mapped " + rawResult);
            }
        };
        registry.register(tool);
        ObjectNode input = mapper.createObjectNode().put("value", "once");

        ToolResult result = registry.execute("legacy-mapper", input,
            ToolExecutionContext.of(new AbortController(), "test-session"));

        assertEquals(1, calls.get());
        assertEquals(1, mappings.get());
        assertEquals("mapped once", firstText(result));
    }

    // ── Permission gate wiring ──────────────────────────────────────────────

    @Test
    void permissionGate_planModeHeadlessWriteAutoRejects() {
        registry.register(new FileWriteTool());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        registry.setPermissionGate(gate);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "/tmp/x.txt");
        input.put("content", "hi");
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("Write", input, ctx);
        assertTrue(result.isError(), "headless PLAN mode should reject when it cannot prompt");
        String text = firstText(result);
        assertEquals(MessageConstants.autoRejectMessage("Write"), text);
        assertEquals("Error: " + text, result.toolUseResult());
    }

    @Test
    void permissionGate_planModeBashUsesInteractiveCallback() {
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash")
            .description("test")
            .call((_, _) -> "TOOL-RAN")
            .build());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        registry.setPermissionGate(gate);

        boolean[] asked = {false};
        PermissionAskCallback askCallback = askContext -> {
            asked[0] = true;
            assertEquals("Bash", askContext.toolName());
            return PermissionAskCallback.Result.allow();
        };
        ToolExecutionContext context = ToolExecutionContext.of(
            new AbortController(), "test-session", ProgressSink.NOOP, askCallback);

        ToolResult result = registry.execute("Bash",
            mapper.createObjectNode().put("command", "touch /tmp/plan-probe"), context);

        assertTrue(asked[0], "released 2.1.197 shows the normal permission dialog in plan mode");
        assertFalse(result.isError());
        assertEquals("TOOL-RAN", firstText(result));
    }

    @Test
    void toolDurationStartsAfterPermissionAndCoversOnlyTheActualCall() {
        AtomicLong clockNanos = new AtomicLong();
        AtomicLong recordedMs = new AtomicLong(-1L);
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash")
            .description("test")
            .call((_, _) -> {
                clockNanos.set(107_000_000L);
                return "TOOL-RAN";
            })
            .build());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        registry.setPermissionGate(gate);
        ToolExecutionContext.ToolDurationTiming timing =
            new ToolExecutionContext.ToolDurationTiming(recordedMs::set, clockNanos::get);
        ToolExecutionContext context = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .permissionAskCallback(_ -> {
                clockNanos.set(100_000_000L);
                return PermissionAskCallback.Result.allow();
            })
            .toolDurationTiming(timing)
            .build();

        ToolResult result = registry.execute("Bash",
            mapper.createObjectNode().put("command", "true"), context);

        assertFalse(result.isError());
        assertEquals(7L, recordedMs.get(),
            "the released timer excludes validation and time blocked on permission");
    }

    @Test
    void deniedToolDoesNotReportToolExecutionDuration() {
        AtomicLong recordedMs = new AtomicLong(-1L);
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash")
            .description("test")
            .call((_, _) -> "must not run")
            .build());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.DONT_ASK);
        registry.setPermissionGate(gate);
        ToolExecutionContext.ToolDurationTiming timing =
            new ToolExecutionContext.ToolDurationTiming(recordedMs::set);
        ToolExecutionContext context = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .toolDurationTiming(timing)
            .build();

        ToolResult result = registry.execute("Bash", mapper.createObjectNode(), context);

        assertTrue(result.isError());
        assertFalse(timing.reported());
        assertEquals(-1L, recordedMs.get());
    }

    @Test
    void permissionGate_dontAskUsesReleasedModeSpecificGuidance() {
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash")
            .description("test")
            .call((_, _) -> "must not run")
            .build());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.DONT_ASK);
        registry.setPermissionGate(gate);

        ToolResult result = registry.execute("Bash", mapper.createObjectNode(),
            ToolExecutionContext.of(new AbortController(), "test-session"));
        String expected = MessageConstants.dontAskRejectMessage("Bash");

        assertTrue(result.isError());
        assertEquals(expected, firstText(result));
        assertEquals("Error: " + expected, result.toolUseResult());
    }

    @Test
    void permissionGate_autoClassifierBlockUsesReleased197MessageAndCompactTranscript() {
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash")
            .description("test")
            .autoClassifierProjection(input -> input.path("command").asText(""))
            .call((_, _) -> "must not run")
            .build());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.AUTO);
        registry.setPermissionGate(gate);

        ObjectNode previous = mapper.createObjectNode().put("command", "echo previous");
        ObjectNode current = mapper.createObjectNode()
            .put("command", "rm -rf /private/tmp/cc-auto-classifier-victim-v1");
        registry.setMessagesSupplier(() -> List.of(
            new UserMessage("u1", MessageContent.ofText("AUTO_CLASSIFIER_DENY")),
            new AssistantMessage("a1", AssistantContent.of(List.of(
                new ToolUseBlock("toolu_previous", "Bash", previous),
                new ToolUseBlock("toolu_current", "Bash", current))))));

        AtomicReference<AutoModeClassifier.Request> captured = new AtomicReference<>();
        registry.setAutoModeClassifier(request -> {
            captured.set(request);
            return AutoModeClassifier.Decision.block("[Irreversible Local Destruction] fixture");
        });
        List<SDKMessage.PermissionDenial> denials = new ArrayList<>();

        ToolResult result = registry.execute("Bash", current,
            autoClassifierContext("toolu_current").withPermissionDenialSink(denials::add));

        String expected = "Permission for this action was denied by the Claude Code auto mode "
            + "classifier. Reason: [Irreversible Local Destruction] fixture. "
            + "If you have other tasks that don't depend on this action, continue working on those. "
            + MessageConstants.DENIAL_WORKAROUND_GUIDANCE + " "
            + "To allow this type of action in the future, the user can add a Bash permission rule to their settings.";
        assertTrue(result.isError());
        assertEquals(expected, firstText(result));
        assertEquals("Error: " + expected, result.toolUseResult());
        assertEquals(1, denials.size());
        assertEquals("toolu_current", denials.getFirst().toolUseId());

        AutoModeClassifier.Request request = captured.get();
        assertNotNull(request);
        assertEquals("claude-sonnet-4-6", request.model());
        assertEquals("session-197", request.sessionId());
        assertEquals("/private/tmp/cc-auto-classifier-project", request.workingDirectory());
        assertEquals(List.of(
            "User: AUTO_CLASSIFIER_DENY\n",
            "Bash echo previous\n",
            "Bash rm -rf /private/tmp/cc-auto-classifier-victim-v1\n"),
            request.compactTranscriptBlocks(),
            "the current tool_use already present in history must not be duplicated");
    }

    @Test
    void permissionGate_autoClassifierAllowExecutesWithoutAskingUser() {
        AtomicBoolean ran = new AtomicBoolean();
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash")
            .description("test")
            .autoClassifierProjection(input -> input.path("command").asText(""))
            .call((_, _) -> {
                ran.set(true);
                return "TOOL-RAN";
            })
            .build());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.AUTO);
        registry.setPermissionGate(gate);
        registry.setMessagesSupplier(() -> List.of(
            new UserMessage("u1", MessageContent.ofText("run it"))));
        registry.setAutoModeClassifier(_ -> AutoModeClassifier.Decision.allow("safe"));

        ToolResult result = registry.execute("Bash",
            mapper.createObjectNode().put("command", "echo ok"),
            autoClassifierContext("toolu_allow"));

        assertTrue(ran.get());
        assertFalse(result.isError());
        assertEquals("TOOL-RAN", firstText(result));
    }

    @Test
    void permissionGate_planAutoClassifierRunsWhileVisibleModeRemainsPlan() {
        AtomicBoolean ran = new AtomicBoolean();
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash")
            .description("test")
            .autoClassifierProjection(input -> input.path("command").asText(""))
            .call((_, _) -> { ran.set(true); return "TOOL-RAN"; })
            .build());
        PermissionGate gate = new PermissionGate();
        gate.configurePlanAutoMode(() -> true, () -> true, () -> true);
        gate.setMode(PermissionMode.PLAN);
        registry.setPermissionGate(gate);
        registry.setAutoModeClassifier(_ -> AutoModeClassifier.Decision.allow("safe"));

        ToolResult result = registry.execute("Bash",
            mapper.createObjectNode().put("command", "echo ok"),
            autoClassifierContext("toolu_plan_auto"));

        assertEquals(PermissionMode.PLAN, gate.currentMode());
        assertTrue(ran.get());
        assertFalse(result.isError());
    }

    @Test
    void permissionGate_autoToolWithEmptyClassifierProjectionSkipsApiAndExecutes() {
        AtomicBoolean classifierCalled = new AtomicBoolean();
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("NoSecurityAction")
            .description("test")
            .autoClassifierProjection(_ -> "")
            .call((_, _) -> "TOOL-RAN")
            .build());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.AUTO);
        registry.setPermissionGate(gate);
        registry.setAutoModeClassifier(_ -> {
            classifierCalled.set(true);
            return AutoModeClassifier.Decision.block("must not be called");
        });

        ToolResult result = registry.execute("NoSecurityAction", mapper.createObjectNode(),
            autoClassifierContext("toolu_empty"));

        assertFalse(classifierCalled.get());
        assertFalse(result.isError());
        assertEquals("TOOL-RAN", firstText(result));
    }

    @Test
    void permissionGate_bypassModeAllowsWriteTool() {
        registry.register(new FileWriteTool());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        registry.setPermissionGate(gate);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "/tmp/__claude_permission_test.txt");
        input.put("content", "hi");
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("Write", input, ctx);
        // Whatever the underlying write does, it must not be the permission denial.
        if (result.isError()) {
            String text = firstText(result);
            assertFalse(Strings.CS.contains(text, "has been denied"),
                "BYPASS mode must not produce a permission denial, got: " + text);
        }
    }

    @Test
    void permissionGate_modeSwitchTakesEffectImmediately() {
        registry.register(new FileWriteTool());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        registry.setPermissionGate(gate);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "/tmp/__claude_permission_test.txt");
        input.put("content", "hi");
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        // PLAN denies
        ToolResult denied = registry.execute("Write", input, ctx);
        assertTrue(denied.isError());

        // Flip mode, same gate instance
        gate.setMode("bypassPermissions");

        ToolResult after = registry.execute("Write", input, ctx);
        // No permission-denial after mode flip
        if (after.isError()) {
            assertFalse(Strings.CS.contains(firstText(after), "has been denied"));
        }
    }

    /** Extracts the text of the first content block. */
    private static String firstText(ToolResult result) {
        return ((TextBlock) result.content().getFirst()).text();
    }

    private static ToolExecutionContext autoClassifierContext(String toolUseId) {
        return ToolExecutionContext.builder(new AbortController(), "session-197")
            .workingDirectory("/private/tmp/cc-auto-classifier-project")
            .fileStateCache(new FileStateCache())
            .nestedMemoryAttachmentTriggers(ConcurrentHashMap.newKeySet())
            .loadedNestedMemoryPaths(ConcurrentHashMap.newKeySet())
            .currentModel("claude-sonnet-4-6")
            .toolUseId(toolUseId)
            .build();
    }

    @Test
    void buildAskContextPropagatesToolUseIdFromExecutionContext() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "rm -f /private/tmp/cc197-can-use-tool-nonexistent");
        PermissionDecisionResult result = new PermissionDecisionResult(
            new PermissionDecision.Ask("/private/tmp/cc197-can-use-tool-nonexistent"),
            new DecisionReason.Mode(PermissionMode.DEFAULT));

        PermissionAskContext context = registry.buildAskContext(
            "Bash", input, result, false, null, "toolu_197_bash_probe");

        assertEquals("toolu_197_bash_probe", context.toolUseId());
        assertEquals("/private/tmp/cc197-can-use-tool-nonexistent", context.blockedPath());
    }

    @Test
    void buildAskContextPreservesTypedPermissionSuggestionsInsteadOfInventingCommandRule() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "touch /private/tmp/cc197-tty-permission-approve-marker");
        List<PermissionUpdate> suggestions = List.of(
            new PermissionUpdate.AddDirectories(
                List.of("/private/tmp"), PermissionUpdate.Destination.SESSION),
            new PermissionUpdate.SetMode(
                PermissionModeKind.ACCEPT_EDITS, PermissionUpdate.Destination.SESSION));
        PermissionDecisionResult result = new PermissionDecisionResult(
            new PermissionDecision.Ask(
                "/private/tmp/cc197-tty-permission-approve-marker",
                null, null, null, null, suggestions),
            new DecisionReason.Mode(PermissionMode.DEFAULT));

        PermissionAskContext context = registry.buildAskContext(
            "Bash", input, result, false, null, "toolu_tty_perm_approve");

        assertEquals(suggestions, context.suggestions());
        assertNull(context.suggestionRuleContent(),
            "path suggestions must not be replaced with Bash(touch:*)");
    }

    @Test
    void bashOutsideWorkingDirectoryPropagatesBlockedPathThroughPermissionCallback() {
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash")
            .description("test Bash")
            .permissions((input, permissionContext) ->
                BashPermissions.check(input.path("command").asText(""), permissionContext))
            .call((_, _) -> "")
            .build());
        registry.setPermissionGate(new PermissionGate(
            ToolPermissionContext.of(
                Path.of("/Users/test/project"))));

        ObjectNode input = mapper.createObjectNode();
        input.put("command", "rm -f /private/tmp/cc197-can-use-tool-nonexistent");
        AtomicReference<PermissionAskContext> captured = new AtomicReference<>();
        ToolExecutionContext executionContext = ToolExecutionContext.of(
                new AbortController(), "session", ProgressSink.NOOP,
                askContext -> {
                    captured.set(askContext);
                    return PermissionAskCallback.Result.allow();
                })
            .withToolUseId("toolu_197_bash_probe");

        ToolResult result = registry.execute("Bash", input, executionContext);

        assertFalse(result.isError());
        assertNotNull(captured.get());
        assertEquals("toolu_197_bash_probe", captured.get().toolUseId());
        assertEquals("/private/tmp/cc197-can-use-tool-nonexistent",
            captured.get().blockedPath());
    }

    @Test
    void bashSilentSuccessCarriesReleased197ToolResultEnvelope() {
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash")
            .description("test Bash")
            .call((_, _) -> "")
            .build());
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "rm -f /private/tmp/cc197-can-use-tool-nonexistent");

        ToolResult result = registry.execute("Bash", input,
            ToolExecutionContext.of(new AbortController(), "session"));

        assertFalse(result.isError());
        assertTrue(result.includeIsErrorField(), "Bash success must serialize is_error:false");
        assertEquals("(Bash completed with no output)", firstText(result));
        assertInstanceOf(Map.class, result.toolUseResult());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.toolUseResult();
        assertEquals("", payload.get("stdout"));
        assertEquals("", payload.get("stderr"));
        assertEquals(false, payload.get("interrupted"));
        assertEquals(false, payload.get("isImage"));
        assertEquals(true, payload.get("noOutputExpected"));
    }

    // ── Interactive tools (requiresUserInteraction) — permission-flow unified ──

    /**
     * An interactive tool must reach the human even under BYPASS: an automatic Allow would skip answer
     * collection.
     */
    @Test
    void requiresUserInteraction_forcesAskEvenUnderBypass() {
        Tool<JsonNode, String> interactive = new ToolBuilder<JsonNode, String>()
                .name("AskUserQuestion")
                .description("d")
                .requiresUserInteraction(true)
                .call((_, _) -> "TOOL-RAN")
                .build();
        registry.register(interactive);

        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        registry.setPermissionGate(gate);

        boolean[] asked = {false};
        PermissionAskCallback askCb = _ -> {
            asked[0] = true;
            return PermissionAskCallback.Result.allow();
        };
        ToolExecutionContext ctx =
            ToolExecutionContext.of(new AbortController(), "test-session", ProgressSink.NOOP, askCb);

        ToolResult result = registry.execute("AskUserQuestion", mapper.createObjectNode(), ctx);

        assertTrue(asked[0], "interactive tool must consult the permission callback under BYPASS");
        assertFalse(result.isError());
        assertEquals("TOOL-RAN", firstText(result));
    }

    /**
     * An interactive tool that rewrites the input during the prompt (AskUserQuestion
     * folds collected answers into updatedInput) must be re-invoked with the
     * rewritten input, never the original API-bound node.
     */
    @Test
    void updatedInput_takesEffectForInteractiveTool() {
        Tool<JsonNode, String> interactive = new ToolBuilder<JsonNode, String>()
                .name("AskUserQuestion")
                .description("d")
                .requiresUserInteraction(true)
                // Echo whatever answers are present so we can detect which input arrived.
                .call((input, _) -> input.has("answers") ? "GOT-ANSWERS" : "NO-ANSWERS")
                .build();
        registry.register(interactive);

        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        registry.setPermissionGate(gate);

        ObjectNode rewritten = mapper.createObjectNode();
        rewritten.putObject("answers").put("q", "a");
        PermissionAskCallback askCb = _ -> PermissionAskCallback.Result.allowWithInput(rewritten);
        ToolExecutionContext ctx =
            ToolExecutionContext.of(new AbortController(), "test-session", ProgressSink.NOOP, askCb);

        // Original input carries no answers.
        ToolResult result = registry.execute("AskUserQuestion", mapper.createObjectNode(), ctx);

        assertFalse(result.isError());
        assertEquals("GOT-ANSWERS", firstText(result),
            "tool should have been re-invoked with the permission-rewritten input");
    }

    @Test
    void permissionCallbackUpdatedPermissionsApplyToLaterToolCalls() {
        Tool<JsonNode, String> guarded = new ToolBuilder<JsonNode, String>()
                .name("HookPermissionProbe")
                .description("d")
                .permissions((_, _) -> PermissionDecision.ask())
                .call((_, _) -> "TOOL-RAN")
                .build();
        registry.register(guarded);

        PermissionGate gate = new PermissionGate();
        registry.setPermissionGate(gate);

        int[] askCount = {0};
        PermissionAskCallback askCallback = _ -> {
            askCount[0]++;
            return PermissionAskCallback.Result.allowWithInputAndPermissions(null, List.of(
                new PermissionUpdate.AddRules(
                    List.of(new PermissionUpdate.RuleValue("HookPermissionProbe", null)),
                    PermissionUpdate.Behavior.ALLOW,
                    PermissionUpdate.Destination.SESSION)));
        };
        ToolExecutionContext context = ToolExecutionContext.of(
            new AbortController(), "test-session", ProgressSink.NOOP, askCallback);

        ToolResult first = registry.execute(
            "HookPermissionProbe", mapper.createObjectNode(), context);
        ToolResult second = registry.execute(
            "HookPermissionProbe", mapper.createObjectNode(), context);

        assertFalse(first.isError());
        assertFalse(second.isError());
        assertEquals(1, askCount[0],
            "the session allow rule returned by PermissionRequest must suppress the next prompt");
    }


    @Test
    void interactiveTool_withoutAskCallback_autoRejects() {
        Tool<JsonNode, String> interactive = new ToolBuilder<JsonNode, String>()
                .name("AskUserQuestion")
                .description("d")
                .requiresUserInteraction(true)
                .call((_, _) -> "TOOL-RAN")
                .build();
        registry.register(interactive);

        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        registry.setPermissionGate(gate);

        // No askCallback wired (null) — headless session.
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");

        ToolResult result = registry.execute("AskUserQuestion", mapper.createObjectNode(), ctx);

        assertTrue(result.isError());
        assertTrue(Strings.CS.startsWith(firstText(result), "Permission to use AskUserQuestion has been denied"),
            firstText(result));
        assertEquals("Error: " + firstText(result), result.toolUseResult());
    }

    /** In the default mode with no allow rule, an interactive tool also reaches the prompt. */
    @Test
    void interactiveTool_defaultMode_triggersAskCallback() {
        Tool<JsonNode, String> interactive = new ToolBuilder<JsonNode, String>()
                .name("AskUserQuestion")
                .description("d")
                .requiresUserInteraction(true)
                .call((_, _) -> "TOOL-RAN")
                .build();
        registry.register(interactive);

        PermissionGate gate = new PermissionGate(); // default mode
        registry.setPermissionGate(gate);

        boolean[] asked = {false};
        PermissionAskCallback askCb = _ -> {
            asked[0] = true;
            return PermissionAskCallback.Result.allow();
        };
        ToolExecutionContext ctx =
            ToolExecutionContext.of(new AbortController(), "test-session", ProgressSink.NOOP, askCb);

        ToolResult result = registry.execute("AskUserQuestion", mapper.createObjectNode(), ctx);

        assertTrue(asked[0]);
        assertFalse(result.isError());
        assertEquals("TOOL-RAN", firstText(result));
    }

    @Test
    void sdkDirectPermissionDenial_isPassedThroughAndPersistedAsErrorResult() {
        Tool<JsonNode, String> interactive = new ToolBuilder<JsonNode, String>()
                .name("Bash")
                .description("d")
                .requiresUserInteraction(true)
                .call((_, _) -> "TOOL-RAN")
                .build();
        registry.register(interactive);

        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        registry.setPermissionGate(gate);
        PermissionAskCallback askCb = _ ->
            PermissionAskCallback.Result.denyWithDirectMessage("host denied");

        ToolResult result = registry.execute("Bash", mapper.createObjectNode(),
            ToolExecutionContext.of(new AbortController(), "test-session", ProgressSink.NOOP, askCb));

        assertTrue(result.isError());
        assertEquals("host denied", firstText(result));
        assertEquals("Error: host denied", result.toolUseResult());
    }

    @Test
    void sdkDirectPermissionDenialWithInterrupt_usesStableUserRejectResult() {
        Tool<JsonNode, String> interactive = new ToolBuilder<JsonNode, String>()
                .name("Bash")
                .description("d")
                .requiresUserInteraction(true)
                .call((_, _) -> "TOOL-RAN")
                .build();
        registry.register(interactive);

        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        registry.setPermissionGate(gate);
        AbortController abortController = new AbortController();
        PermissionAskCallback askCb = _ -> {
            abortController.abort("user-cancel");
            return PermissionAskCallback.Result.denyWithDirectMessage(
                "Permission denied by wire fixture.");
        };

        ToolResult result = registry.execute("Bash", mapper.createObjectNode(),
            ToolExecutionContext.of(abortController, "test-session", ProgressSink.NOOP, askCb));

        assertTrue(result.isError());
        assertEquals(MessageConstants.REJECT_MESSAGE, firstText(result));
        assertEquals("User rejected tool use", result.toolUseResult());
        assertEquals("user-cancel", abortController.getReason());
    }

    @Test
    void interactivePermissionReject_usesReleasedTranscriptMarkerAndAbortReason() {
        Tool<JsonNode, String> interactive = new ToolBuilder<JsonNode, String>()
                .name("Bash")
                .description("d")
                .requiresUserInteraction(true)
                .call((_, _) -> "TOOL-RAN")
                .build();
        registry.register(interactive);

        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        registry.setPermissionGate(gate);
        AbortController abortController = new AbortController();
        PermissionAskCallback askCb = _ -> PermissionAskCallback.Result.deny();

        ToolResult result = registry.execute("Bash", mapper.createObjectNode(),
            ToolExecutionContext.of(abortController, "test-session", ProgressSink.NOOP, askCb));

        assertTrue(result.isError());
        assertEquals(MessageConstants.REJECT_MESSAGE, firstText(result));
        assertEquals("User rejected tool use", result.toolUseResult(),
            "2.1.197 persists the stable short marker, not an Error-prefixed copy of the model text");
        assertEquals("user_reject_permission", abortController.getReason());
    }

    @Test
    void subagentPermissionReject_noFeedback_usesSubagentMessageAndDoesNotAbort() {
        Tool<JsonNode, String> interactive = new ToolBuilder<JsonNode, String>()
                .name("Bash")
                .description("d")
                .requiresUserInteraction(true)
                .call((_, _) -> "TOOL-RAN")
                .build();
        registry.register(interactive);

        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        registry.setPermissionGate(gate);
        AbortController abortController = new AbortController();
        PermissionAskCallback askCb = _ -> PermissionAskCallback.Result.deny();

        ToolExecutionContext context = ToolExecutionContext
            .builder(abortController, "test-session")
            .progressSink(ProgressSink.NOOP)
            .permissionAskCallback(askCb)
            .agentId("subagent-1")
            .build();

        ToolResult result = registry.execute("Bash", mapper.createObjectNode(), context);

        assertTrue(result.isError());
        assertEquals(MessageConstants.SUBAGENT_REJECT_MESSAGE, firstText(result));
        assertEquals("User rejected tool use", result.toolUseResult());
        assertFalse(abortController.isAborted(),
            "TS cancelAndAbort only auto-aborts the main-thread agent (sub=false); "
                + "a subagent gets SUBAGENT_REJECT_MESSAGE back and keeps running");
    }

    @Test
    void subagentPermissionReject_withFeedback_usesSubagentReasonPrefix() {
        Tool<JsonNode, String> interactive = new ToolBuilder<JsonNode, String>()
                .name("Bash")
                .description("d")
                .requiresUserInteraction(true)
                .call((_, _) -> "TOOL-RAN")
                .build();
        registry.register(interactive);

        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        registry.setPermissionGate(gate);
        AbortController abortController = new AbortController();
        PermissionAskCallback askCb = _ -> PermissionAskCallback.Result.denyWithFeedback("use a different tool");

        ToolExecutionContext context = ToolExecutionContext
            .builder(abortController, "test-session")
            .progressSink(ProgressSink.NOOP)
            .permissionAskCallback(askCb)
            .agentId("subagent-1")
            .build();

        ToolResult result = registry.execute("Bash", mapper.createObjectNode(), context);

        assertTrue(result.isError());
        assertEquals(MessageConstants.SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX + "use a different tool",
            firstText(result));
        assertFalse(abortController.isAborted());
    }

    @Test
    void permissionReject_preservesImageFeedbackBlocksForTheUserEnvelope() {
        Tool<JsonNode, String> interactive = new ToolBuilder<JsonNode, String>()
            .name("ExitPlanMode").description("d").requiresUserInteraction(true)
            .call((_, _) -> "TOOL-RAN").build();
        registry.register(interactive);
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        registry.setPermissionGate(gate);
        var source = mapper.createObjectNode()
            .put("type", "base64").put("media_type", "image/png").put("data", "aW1hZ2U=");
        PermissionAskCallback askCb = _ -> PermissionAskCallback.Result.denyWithFeedback(
            "(See attached image)", List.of(new ImageBlock(source)));
        ToolExecutionContext context = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .progressSink(ProgressSink.NOOP).permissionAskCallback(askCb).build();

        ToolResult result = registry.execute("ExitPlanMode", mapper.createObjectNode(), context);

        assertEquals(1, result.userFeedbackBlocks().size());
        assertInstanceOf(ImageBlock.class, result.userFeedbackBlocks().getFirst());
    }

    // ── getToolDefinitions(Set<String>) — ToolSearch deferred-schema filtering ──

    private Tool<JsonNode, String> deferredTool(String name) {
        return new ToolBuilder<JsonNode, String>()
            .name(name).description("d").shouldDefer(true)
            .call((_, _) -> "ok").build();
    }

    private Tool<JsonNode, String> nonDeferredTool(String name) {
        return new ToolBuilder<JsonNode, String>()
            .name(name).description("d")
            .call((_, _) -> "ok").build();
    }

    @Test
    void getToolDefinitions_withDiscoveredSet_omitsUndiscoveredDeferredTools() {
        registry.register(nonDeferredTool("Bash"));
        registry.register(deferredTool("WebFetch"));
        registry.register(deferredTool("CronCreate"));

        var defs = registry.getToolDefinitions(Set.of("WebFetch"));

        var names = defs.stream().map(StreamingClient.StreamRequest.ToolDef::name).toList();
        assertTrue(names.contains("Bash"), "non-deferred tool always present");
        assertTrue(names.contains("WebFetch"), "discovered deferred tool present");
        assertFalse(names.contains("CronCreate"), "undiscovered deferred tool omitted entirely");
    }

    @Test
    void getToolDefinitions_withDiscoveredSet_tagsDeferLoadingCorrectly() {
        registry.register(nonDeferredTool("Bash"));
        registry.register(deferredTool("WebFetch"));

        var defs = registry.getToolDefinitions(Set.of("WebFetch"));

        var bash = defs.stream().filter(d -> Strings.CS.equals(d.name(), "Bash")).findFirst().orElseThrow();
        var webFetch = defs.stream().filter(d -> Strings.CS.equals(d.name(), "WebFetch")).findFirst().orElseThrow();
        assertFalse(bash.deferLoading());
        assertTrue(webFetch.deferLoading());
    }

    @Test
    void getToolDefinitions_emptyDiscoveredSet_keepsOnlyNonDeferredTools() {
        registry.register(nonDeferredTool("Bash"));
        registry.register(deferredTool("WebFetch"));

        var defs = registry.getToolDefinitions(Set.of());

        var names = defs.stream().map(StreamingClient.StreamRequest.ToolDef::name).toList();
        assertEquals(List.of("Bash"), names);
    }

    @Test
    void getToolDefinitions_toolSearchToolItself_alwaysIncludedRegardlessOfDiscovery() {
        registry.register(new ToolSearchTool(registry));
        registry.register(deferredTool("WebFetch"));

        var defs = registry.getToolDefinitions(Set.of());

        var names = defs.stream().map(StreamingClient.StreamRequest.ToolDef::name).toList();
        assertTrue(names.contains(ToolSearchTool.NAME));
        assertFalse(names.contains("WebFetch"));
    }

    @Test
    void getDeferredToolNames_returnsAllDeferredRegardlessOfDiscovery() {
        registry.register(nonDeferredTool("Bash"));
        registry.register(deferredTool("WebFetch"));
        registry.register(deferredTool("CronCreate"));

        var names = registry.getDeferredToolNames();

        assertEquals(2, names.size());
        assertTrue(names.containsAll(List.of("WebFetch", "CronCreate")));
    }

    @Test
    void getToolDefinitions_noArgOverload_unaffectedByDeferral() {
        // The pre-existing no-arg overload must keep sending every enabled tool's
        // full schema unconditionally — callers that don't opt into tool search
        // filtering (tests, non-ToolSearch-aware executors) see no behavior change.
        registry.register(deferredTool("WebFetch"));

        var defs = registry.getToolDefinitions();

        assertEquals(1, defs.size());
        assertEquals("WebFetch", defs.getFirst().name());
    }

    @Test
    void getToolDefinitions_openAiResponsesUsesFullPromptsWithoutToolSearch() {
        Tool<JsonNode, String> deferred = new Tool<>() {
            @Override public ToolIdentity identity() { return new ToolIdentity("DeferredTool"); }
            @Override public String description() { return "short UI description"; }
            @Override public String prompt(ToolExecutionContext context) { return "full model prompt"; }
            @Override public JsonNode inputSchema() { return mapper.createObjectNode(); }
            @Override public String call(JsonNode input, ToolExecutionContext context) { return "ok"; }
            @Override public boolean shouldDefer() { return true; }
        };
        registry.register(new ToolSearchTool(registry));
        registry.register(deferred);
        ToolSearchGate.configureProtocolResolver(_ -> ModelApiProtocol.OPENAI_RESPONSES);
        ToolExecutionContext context = ToolExecutionContext.builder(
                new AbortController(), "responses-session")
            .currentModel("gpt-5.6-sol")
            .build();

        var definitions = registry.getToolDefinitions(context);

        assertFalse(definitions.stream().anyMatch(def -> ToolSearchTool.NAME.equals(def.name())));
        var deferredDefinition = definitions.stream()
            .filter(def -> "DeferredTool".equals(def.name()))
            .findFirst()
            .orElseThrow();
        assertEquals("full model prompt", deferredDefinition.description());
        assertFalse(deferredDefinition.deferLoading());
    }

    @Test
    void getToolDefinitions_usesDynamicPromptHookForWireDescription() {
        Tool<JsonNode, String> dynamic = new Tool<>() {
            @Override public ToolIdentity identity() { return new ToolIdentity("Dynamic"); }
            @Override public String description() { return "static"; }
            @Override public String prompt(ToolExecutionContext context) { return "dynamic"; }
            @Override public JsonNode inputSchema() { return mapper.createObjectNode(); }
            @Override public String call(JsonNode input, ToolExecutionContext context) { return "ok"; }
        };
        registry.register(dynamic);

        assertEquals("dynamic", registry.getToolDefinitions().getFirst().description());
    }

    @Test
    void getToolDefinitions_usesRequestContextForProviderCompatibleAgentPrompt() {
        registry.register(new AgentTool());
        ToolExecutionContext gpt = ToolExecutionContext
            .builder(new AbortController(), "gpt-session")
            .workingDirectory(projectDir.toString())
            .currentModel("gpt-5.6-sol")
            .build();
        ToolExecutionContext claude = ToolExecutionContext
            .builder(new AbortController(), "claude-session")
            .workingDirectory(projectDir.toString())
            .currentModel("claude-sonnet-4-6")
            .build();

        String gptPrompt = registry.getToolDefinitions(gpt).getFirst().description();
        String claudePrompt = registry.getToolDefinitions(claude).getFirst().description();

        assertFalse(Strings.CS.contains(gptPrompt, "code-reviewer"), gptPrompt);
        assertTrue(Strings.CS.contains(gptPrompt, "general-purpose"), gptPrompt);
        assertTrue(Strings.CS.contains(claudePrompt, "code-reviewer"), claudePrompt);
        assertEquals(claudePrompt.replace("code-reviewer", "general-purpose"),
            gptPrompt, "provider compatibility must be the only wire-text delta");
    }

    @Test
    void getToolDefinitions_toolSearchPathUsesRequestContextForAgentPrompt() {
        registry.register(new AgentTool());
        ToolExecutionContext gpt = ToolExecutionContext
            .builder(new AbortController(), "gpt-session")
            .workingDirectory(projectDir.toString())
            .currentModel("gpt-5.6-sol")
            .build();

        String prompt = registry.getToolDefinitions(Set.of(), gpt)
            .getFirst().description();

        assertFalse(Strings.CS.contains(prompt, "code-reviewer"), prompt);
        assertTrue(Strings.CS.contains(prompt, "general-purpose"), prompt);
    }

    @Test
    void getToolDefinitions_keepsAvailableCustomCodeReviewerForCompatibleProvider()
            throws Exception {
        Path agentsDir = Files.createDirectories(projectDir.resolve(".claude/agents"));
        Files.writeString(agentsDir.resolve("code-reviewer.md"), """
            ---
            name: code-reviewer
            description: Reviews code changes
            tools: Read
            ---
            Review the requested code changes.
            """);
        AgentDefinitionLoader.clearCache();
        try {
            registry.register(new AgentTool());
            ToolExecutionContext gpt = ToolExecutionContext
                .builder(new AbortController(), "gpt-session")
                .workingDirectory(projectDir.toString())
                .currentModel("gpt-5.6-sol")
                .build();

            String prompt = registry.getToolDefinitions(gpt).getFirst().description();

            assertTrue(Strings.CS.contains(prompt,
                "subagent_type: \"code-reviewer\""), prompt);
        } finally {
            AgentDefinitionLoader.clearCache();
        }
    }

    @Test
    void modelSchemaHidesSwarmFieldsWhenTeamsAreDisabled() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("description").put("type", "string");
        properties.putObject("name").put("type", "string");
        properties.putObject("team_name").put("type", "string");
        properties.putObject("mode").put("type", "string");
        var agent = new ToolBuilder<JsonNode, String>()
            .name("Agent").description("agent").inputSchema(schema)
            .call((_, _) -> "ok").build();
        registry.register(agent);

        AgentTeamsEnabled.setEnabledForTest(false);
        try {
            JsonNode visible = (JsonNode) registry.getToolDefinitions().getFirst().inputSchema();
            assertTrue(visible.has("properties"));
            assertTrue(visible.get("properties").has("description"));
            assertFalse(visible.get("properties").has("name"));
            assertFalse(visible.get("properties").has("team_name"));
            assertFalse(visible.get("properties").has("mode"));
        } finally {
            AgentTeamsEnabled.resetForTest();
        }
    }

    // ── schema-not-sent hint (buildSchemaNotSentHint) ──────────────────────────

    /** A deferred tool requiring a "count" integer field — trivially fails structural validation on a string. */
    private Tool<JsonNode, String> deferredToolWithRequiredIntField(String name) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("count").put("type", "integer");
        schema.putArray("required").add("count");
        return new ToolBuilder<JsonNode, String>()
            .name(name).description("d").inputSchema(schema).shouldDefer(true)
            .call((_, _) -> "ok").build();
    }

    @Test
    void schemaNotSentHint_appendedWhenToolIsDeferredAndUndiscoveredAndToolSearchRegistered() {
        registry.register(new ToolSearchTool(registry));
        registry.register(deferredToolWithRequiredIntField("WebFetch"));
        registry.setMessagesSupplier(List::of); // no messages → nothing discovered

        ObjectNode badInput = mapper.createObjectNode();
        badInput.put("count", "not-an-integer");
        ToolResult result = registry.execute("WebFetch", badInput,
            ToolExecutionContext.of(new AbortController(), "test-session"));

        assertTrue(result.isError());
        assertTrue(Strings.CS.contains(firstText(result), "Load the tool first: call ToolSearch"), firstText(result));
    }

    @Test
    void schemaNotSentHint_omittedWhenToolSearchNotRegistered() {
        registry.register(deferredToolWithRequiredIntField("WebFetch"));
        registry.setMessagesSupplier(List::of);

        ObjectNode badInput = mapper.createObjectNode();
        badInput.put("count", "not-an-integer");
        ToolResult result = registry.execute("WebFetch", badInput,
            ToolExecutionContext.of(new AbortController(), "test-session"));

        assertTrue(result.isError());
        assertFalse(Strings.CS.contains(firstText(result), "Load the tool first"), firstText(result));
    }

    @Test
    void schemaNotSentHint_omittedWhenNoMessagesSupplierWired() {
        registry.register(new ToolSearchTool(registry));
        registry.register(deferredToolWithRequiredIntField("WebFetch"));
        // messagesSupplier left unwired (null)

        ObjectNode badInput = mapper.createObjectNode();
        badInput.put("count", "not-an-integer");
        ToolResult result = registry.execute("WebFetch", badInput,
            ToolExecutionContext.of(new AbortController(), "test-session"));

        assertTrue(result.isError());
        assertFalse(Strings.CS.contains(firstText(result), "Load the tool first"), firstText(result));
    }

    @Test
    void schemaNotSentHint_omittedForNonDeferredTool() {
        registry.register(new ToolSearchTool(registry));
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("count").put("type", "integer");
        schema.putArray("required").add("count");
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash").description("d").inputSchema(schema)
            .call((_, _) -> "ok").build());
        registry.setMessagesSupplier(List::of);

        ObjectNode badInput = mapper.createObjectNode();
        badInput.put("count", "not-an-integer");
        ToolResult result = registry.execute("Bash", badInput,
            ToolExecutionContext.of(new AbortController(), "test-session"));

        assertTrue(result.isError());
        assertFalse(Strings.CS.contains(firstText(result), "Load the tool first"), firstText(result));
    }

    // ── H7: destructive-command warning is gated by the setting ──

    @Test
    void destructiveWarningGatedBySetting() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "git reset --hard");
        PermissionDecisionResult result = new PermissionDecisionResult(
            new PermissionDecision.Ask(), new DecisionReason.Mode(PermissionMode.DEFAULT));


        PermissionAskContext off = registry.buildAskContext("Bash", input, result, false);
        assertNull(off.destructiveWarning(), "warning must be suppressed when the setting is off");

        // Enabled → warning surfaced.
        PermissionAskContext on = registry.buildAskContext("Bash", input, result, true);
        assertNotNull(on.destructiveWarning(), "warning must surface when the setting is on");
        assertTrue(Strings.CS.contains(on.destructiveWarning(), "discard uncommitted"), on.destructiveWarning());
    }

    @Test
    void powershellDestructiveWarningSurfaced() {

// distinct from Bash.
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "Remove-Item ./build -Recurse -Force");
        PermissionDecisionResult result = new PermissionDecisionResult(
            new PermissionDecision.Ask(), new DecisionReason.Mode(PermissionMode.DEFAULT));

        PermissionAskContext off = registry.buildAskContext("PowerShell", input, result, false);
        assertNull(off.destructiveWarning(), "warning must be suppressed when the setting is off");

        PermissionAskContext on = registry.buildAskContext("PowerShell", input, result, true);
        assertNotNull(on.destructiveWarning(), "PowerShell warning must surface when the setting is on");
        assertTrue(Strings.CS.contains(on.destructiveWarning(), "recursively force-remove"), on.destructiveWarning());

        // A benign PowerShell command must not warn even when enabled.
        ObjectNode benign = mapper.createObjectNode();
        benign.put("command", "Get-ChildItem ./src");
        PermissionAskContext benignOn = registry.buildAskContext("PowerShell", benign, result, true);
        assertNull(benignOn.destructiveWarning(), "benign PowerShell command must not warn");
    }



    @Test
    void contextModifier_allowedTools_addedToGateAsSkillRules() {
        // A tool returning a contextModifier that declares allowedTools must
        // auto-allow those tools for the rest of the session, via SKILL rules
        // that survive settings hot-reload.
        Tool<JsonNode, ToolResult> skillTool = new ToolBuilder<JsonNode, ToolResult>()
            .name("Skill")
            .description("d")
            .permissions((_, _) -> new PermissionDecision.Allow())
            .call((_, _) -> ToolResult.success("Launching skill: x")
                .withContextModifier(new ToolContextModifier(List.of("Bash", "Write"), null, null)))
            .build();
        registry.register(skillTool);

        PermissionGate gate = new PermissionGate();
        registry.setPermissionGate(gate);

        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");
        ToolResult result = registry.execute("Skill", mapper.createObjectNode(), ctx);
        assertFalse(result.isError(), "skill tool must succeed");

        // Declared tools now auto-allowed.
        assertInstanceOf(PermissionDecision.Allow.class, gate.checkDetailed("Bash", mapper.createObjectNode()).decision(), "Bash should be allowed via skill modifier");
        assertInstanceOf(PermissionDecision.Allow.class, gate.checkDetailed("Write", mapper.createObjectNode()).decision(), "Write should be allowed via skill modifier");
        // An undeclared tool is still gated (default Ask, not auto-allowed).
        assertFalse(gate.checkDetailed("Read", mapper.createObjectNode()).decision() instanceof PermissionDecision.Allow,
            "undeclared tool must not be auto-allowed by the skill modifier");

        // The added rule is a SKILL-sourced allow rule (survives disk sync).
        boolean hasSkillRule = gate.currentContext().rules().stream()
            .anyMatch(r -> r.source() == RuleSource.SKILL
                && r.behavior() == PermissionBehavior.ALLOW
                && Strings.CS.equals("Bash", r.toolName()));
        assertTrue(hasSkillRule, "a SKILL allow rule for Bash must be present");
    }

    @Test
    void contextModifier_nullDoesNotTouchGate() {
        Tool<JsonNode, ToolResult> skillTool = new ToolBuilder<JsonNode, ToolResult>()
            .name("Skill")
            .description("d")
            .permissions((_, _) -> new PermissionDecision.Allow())
            .call((_, _) -> ToolResult.success("ok")) // no modifier attached
            .build();
        registry.register(skillTool);

        PermissionGate gate = new PermissionGate();
        registry.setPermissionGate(gate);
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");
        registry.execute("Skill", mapper.createObjectNode(), ctx);

        assertTrue(gate.currentContext().rules().isEmpty(),
            "no modifier → gate rules must be untouched");
    }

    // ── Built-in registration completeness (archive-coverage-review-decisions.md A) ──

    @Test
    void builtInRegistry_wiresGlobGrepAndTodoWrite() {
        ToolRegistry reg = ToolBootstrap.buildBuiltInRegistry();





        assertTrue(reg.get("Glob").isPresent(), "Glob must be registered");
        assertTrue(reg.get("Glob").get().isEnabled(), "Glob must be enabled");
        assertTrue(reg.get("Grep").isPresent(), "Grep must be registered");
        assertTrue(reg.get("Grep").get().isEnabled(), "Grep must be enabled");

// TodoWrite is registered unconditionally.
        assertTrue(reg.get("TodoWrite").isPresent(), "TodoWrite must be registered");
        assertFalse(reg.get("TodoWrite").get().isEnabled(),
            "TodoWrite is gated off at the v2 default (mutually exclusive with Task* tools)");

// Enabled-tool set must reflect the gating: Glob/Grep exposed, dormant TodoWrite

        var enabledNames = reg.getToolDefinitions().stream()
            .map(StreamingClient.StreamRequest.ToolDef::name).toList();
        assertTrue(enabledNames.contains("Glob"), "Glob in enabled tool set");
        assertTrue(enabledNames.contains("Grep"), "Grep in enabled tool set");
        assertFalse(enabledNames.contains("TodoWrite"),
            "dormant TodoWrite must not appear in the enabled tool definitions");
    }
}
