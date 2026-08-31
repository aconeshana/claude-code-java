package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.permissions.ToolPermissionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;

class AgentToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ToolExecutionContext context;

    @TempDir
    Path projectDir;

    @BeforeEach
    void setUp() {
        context = ToolExecutionContext.of(new AbortController(), "test-session");
    }

    @Test
    void nameIsAgent() {
        AgentTool tool = new AgentTool();
        assertEquals("Agent", tool.name());
    }

    @Test
    void callWithNoOpFactory() {
        AgentTool tool = new AgentTool();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "List all files");

        String result = text(tool.call(input, context));
        assertTrue(Strings.CS.contains(result, "Sub-agent not configured"));
        assertTrue(Strings.CS.contains(result, "List all files"));
    }

    @Test
    void subAgentAtConfiguredDepthLimitCannotInvokeAgentToolRecursively() {
        AgentTool tool = new AgentTool(_ -> SubAgentResult.of("nested agent ran"));
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "spawn another agent");
        ToolExecutionContext childContext = context.toBuilder()
            .agentId("child-agent-id")
            .agentDepth(2)
            .subagentMaxDepthSnapshot(2)
            .build();

        ToolResult result = tool.call(input, childContext);

        assertTrue(result.isError());
        assertTrue(Strings.CS.contains(text(result),
            "Subagent nesting limit reached (depth 2 of 2)"), text(result));
    }

    @Test
    void newTreesReadCurrentLimitWhileExistingTreesKeepTheirSnapshot() {
        AtomicInteger configuredLimit = new AtomicInteger(2);
        List<SubAgentRequest> captured = new ArrayList<>();
        AgentTool tool = new AgentTool(request -> {
            captured.add(request);
            return SubAgentResult.of("done");
        });
        tool.setSubagentMaxDepthSupplier(configuredLimit::get);
        ObjectNode input = MAPPER.createObjectNode().put("prompt", "nested work");

        tool.call(input, context);
        assertEquals(1, captured.getFirst().agentDepth());
        assertEquals(2, captured.getFirst().subagentMaxDepthSnapshot());

        configuredLimit.set(5);
        ToolExecutionContext existingTree = context.toBuilder()
            .agentId("depth-one")
            .agentDepth(1)
            .subagentMaxDepthSnapshot(2)
            .build();
        tool.call(input, existingTree);
        assertEquals(2, captured.get(1).agentDepth());
        assertEquals(2, captured.get(1).subagentMaxDepthSnapshot());

        tool.call(input, context);
        assertEquals(1, captured.get(2).agentDepth());
        assertEquals(5, captured.get(2).subagentMaxDepthSnapshot());
    }

    @Test
    void releasedDepthArithmeticHandlesConfiguredOneAndFiveBoundaries() {
        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        AgentTool tool = new AgentTool(request -> {
            captured.set(request);
            return SubAgentResult.of("done");
        });
        ObjectNode input = MAPPER.createObjectNode().put("prompt", "nested work");

        tool.setSubagentMaxDepthSupplier(() -> 1);
        assertFalse(tool.call(input, context).isError());
        assertEquals(1, captured.get().agentDepth());
        assertEquals(1, captured.get().subagentMaxDepthSnapshot());
        ToolResult limitOne = tool.call(input, context.toBuilder()
            .agentDepth(1)
            .subagentMaxDepthSnapshot(1)
            .build());
        assertTrue(limitOne.isError());

        tool.setSubagentMaxDepthSupplier(() -> 5);
        ToolExecutionContext depthFour = context.toBuilder()
            .agentDepth(4)
            .subagentMaxDepthSnapshot(5)
            .build();
        assertFalse(tool.call(input, depthFour).isError());
        assertEquals(5, captured.get().agentDepth());
        assertEquals(5, captured.get().subagentMaxDepthSnapshot());
        ToolResult limitFive = tool.call(input, context.toBuilder()
            .agentDepth(5)
            .subagentMaxDepthSnapshot(5)
            .build());
        assertTrue(limitFive.isError());
        assertTrue(Strings.CS.contains(text(limitFive), "depth 5 of 5"));
    }

    @Test
    void teammateCanLaunchSynchronousOrdinarySubagentFromDepthZero() {
        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        AgentTool tool = new AgentTool(request -> {
            captured.set(request);
            return SubAgentResult.of("done");
        });
        tool.setSubagentMaxDepthSupplier(() -> 5);
        ObjectNode input = MAPPER.createObjectNode()
            .put("prompt", "inspect")
            .put("subagent_type", "general-purpose");

        TeammateContextHolder.runWithContext(TeammateContext.builder()
            .agentId("teammate-1")
            .teamId("team-1")
            .build(), () -> tool.call(input, context));

        assertNotNull(captured.get());
        assertFalse(captured.get().teammate());
        assertFalse(captured.get().async());
        assertEquals(1, captured.get().agentDepth());
        assertEquals(5, captured.get().subagentMaxDepthSnapshot());
    }

    @Test
    void teammateCannotLaunchNamedTeammateOrBackgroundOrdinaryAgent() {
        AgentTool tool = new AgentTool(_ -> SubAgentResult.of("unexpected"));
        TeammateContext teammate = TeammateContext.builder()
            .agentId("teammate-1")
            .teamId("team-1")
            .build();
        ObjectNode named = MAPPER.createObjectNode()
            .put("prompt", "spawn peer")
            .put("name", "peer")
            .put("team_name", "team-1");
        ObjectNode background = MAPPER.createObjectNode()
            .put("prompt", "inspect")
            .put("run_in_background", true);
        ObjectNode definitionBackground = MAPPER.createObjectNode()
            .put("prompt", "verify")
            .put("subagent_type", "background-test");

        AtomicReference<ToolResult> namedResult = new AtomicReference<>();
        AtomicReference<ToolResult> backgroundResult = new AtomicReference<>();
        AtomicReference<ToolResult> definitionBackgroundResult = new AtomicReference<>();
        AgentDefinitionLoader.setCliAgentsProvider(() -> AgentDefinitionLoader.parseCliAgents(
            "{\"background-test\":{\"description\":\"test\","
                + "\"prompt\":\"background\",\"background\":true}}"));
        try {
            TeammateContextHolder.runWithContext(teammate, () -> {
                namedResult.set(tool.call(named, context));
                backgroundResult.set(tool.call(background, context));
                definitionBackgroundResult.set(tool.call(definitionBackground, context));
            });
        } finally {
            AgentDefinitionLoader.setCliAgentsProvider(null);
        }

        assertTrue(namedResult.get().isError());
        assertTrue(Strings.CS.contains(text(namedResult.get()), "roster is flat"));
        assertTrue(backgroundResult.get().isError());
        assertTrue(Strings.CS.contains(text(backgroundResult.get()),
            "cannot spawn background agents"));
        assertTrue(definitionBackgroundResult.get().isError());
        assertTrue(Strings.CS.contains(text(definitionBackgroundResult.get()),
            "background: true"));
    }

    @Test
    void nonAnthropicPromptDoesNotRecommendUnavailableCodeReviewer() {
        ToolExecutionContext gpt = ToolExecutionContext.builder(
                new AbortController(), "test-session")
            .workingDirectory(projectDir.toString())
            .currentModel("gpt-5.6-sol")
            .build();

        String prompt = new AgentTool().prompt(gpt);

        assertFalse(Strings.CS.contains(prompt, "subagent_type: \"code-reviewer\""));
        assertTrue(Strings.CS.contains(prompt, "subagent_type: \"general-purpose\""));
    }

    @Test
    void anthropicPromptKeepsExactReleasedCodeReviewerExample() {
        ToolExecutionContext claude = ToolExecutionContext.builder(
                new AbortController(), "test-session")
            .workingDirectory(projectDir.toString())
            .currentModel("claude-sonnet-4-6")
            .build();

        String prompt = new AgentTool().prompt(claude);

        assertTrue(Strings.CS.contains(prompt, "subagent_type: \"code-reviewer\""));
    }

    @Test
    void promptReflectsActiveCustomAgents_andFiltersMissingMcpRequirements() throws IOException {
        Path agentsDir = projectDir.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("db-reviewer.md"), """
            ---
            name: db-reviewer
            description: Review database changes
            mcpServers: [postgres]
            ---
            You review database changes.
            """);
        AgentDefinitionLoader.clearCache();

// AgentTool.prompt still computes and filters the MCP-gated agent listing
// (availableMcpServers/hasRequiredMcpServers retained), but by default
// AgentToolPrompt.getPrompt ignores that listing entirely.
        SubprocessEnvironment.updateSettings(Map.of("CLAUDE_CODE_AGENT_LIST_IN_MESSAGES", "false"));
        try {
            ToolExecutionContext noMcp = ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory(projectDir.toString()).build();
            String hidden = new AgentTool().prompt(noMcp);
            assertFalse(Strings.CS.contains(hidden, "db-reviewer"),
                "an agent requiring an unavailable MCP server must not be advertised");

            ToolExecutionContext withMcp = ToolExecutionContext
                .builder(new AbortController(), "test-session")
                .workingDirectory(projectDir.toString())
                .fileStateCache(new FileStateCache())
                .nestedMemoryAttachmentTriggers(ConcurrentHashMap.newKeySet())
                .loadedNestedMemoryPaths(ConcurrentHashMap.newKeySet())
                .enabledTools(List.of("mcp__postgres__query"))
                .build();
            String visible = new AgentTool().prompt(withMcp);
            assertTrue(Strings.CS.contains(visible, "db-reviewer"),
                "an agent whose required MCP server is available must be advertised");
        } finally {
            SubprocessEnvironment.clearSettings();
        }
    }

    @Test
    void permissionDeniedAgentIsHiddenAndCannotBeLaunched() throws IOException {
        Path agentsDir = projectDir.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("blocked-reviewer.md"), """
            ---
            name: blocked-reviewer
            description: Must not be delegated to
            ---
            Inspect the project.
            """);
        AgentDefinitionLoader.clearCache();
        AgentTool tool = new AgentTool(_ -> SubAgentResult.of("must not run"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        registry.setPermissionGate(new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(projectDir)
            .rules(List.of(PermissionRule.withPattern(
                "Agent", PermissionBehavior.DENY, RuleSource.SESSION, "blocked-reviewer")))
            .build()));
        ToolExecutionContext projectContext = ToolExecutionContext.builder(
                new AbortController(), "test-session")
            .workingDirectory(projectDir.toString())
            .build();

        SubprocessEnvironment.updateSettings(Map.of(
            "CLAUDE_CODE_AGENT_LIST_IN_MESSAGES", "false"));
        try {
            assertFalse(Strings.CS.contains(tool.prompt(projectContext), "blocked-reviewer"));
            ObjectNode input = MAPPER.createObjectNode()
                .put("description", "inspect blocked agent")
                .put("prompt", "inspect")
                .put("subagent_type", "blocked-reviewer");
            ToolResult result = registry.execute("Agent", input, projectContext);
            assertTrue(result.isError());
            assertTrue(Strings.CS.contains(text(result), "denied"), text(result));
        } finally {
            SubprocessEnvironment.clearSettings();
        }
    }

    @Test
    void unavailableAgentModelsAreNeitherAdvertisedNorLaunched() throws IOException {
        Path agentsDir = projectDir.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("key-required.md"), """
            ---
            name: key-required
            description: Requires direct Anthropic auth
            model: haiku
            ---
            Inspect the project.
            """);
        AgentDefinitionLoader.clearCache();
        ToolExecutionContext projectContext = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .workingDirectory(projectDir.toString())
            .currentModel("gateway-model")
            .build();
        AgentTool tool = new AgentTool(_ -> SubAgentResult.of("must not run"));
        tool.setModelAvailabilityPredicate(model -> !Strings.CS.equals("haiku", model));

        SubprocessEnvironment.updateSettings(Map.of(
            "CLAUDE_CODE_AGENT_LIST_IN_MESSAGES", "false"));
        try {
            assertFalse(Strings.CS.contains(tool.prompt(projectContext), "key-required"));

            ObjectNode input = MAPPER.createObjectNode();
            input.put("prompt", "inspect");
            input.put("subagent_type", "key-required");
            String result = text(tool.call(input, projectContext));
            assertTrue(Strings.CS.contains(result, "not available with the current model provider"));
        } finally {
            SubprocessEnvironment.clearSettings();
        }
    }

    @Test
    void modelAvailabilityFilteringSupportsDefinitionSnapshotsWithoutExecutionContext() {
        AgentTool tool = new AgentTool();
        tool.setModelAvailabilityPredicate(model -> !Strings.CS.equals("haiku", model));

        assertDoesNotThrow(() -> tool.prompt(null));
    }

    @Test
    void callWithEmptyPromptReturnsError() {
        AgentTool tool = new AgentTool();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "");

        String result = text(tool.call(input, context));
        assertEquals("Error: prompt is required", result);
    }

    @Test
    void callWithMissingPromptReturnsError() {
        AgentTool tool = new AgentTool();
        ObjectNode input = MAPPER.createObjectNode();

        String result = text(tool.call(input, context));
        assertEquals("Error: prompt is required", result);
    }

    @Test
    void callRejectsMissingExecutionContextAtBoundary() {
        AgentTool tool = new AgentTool();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "inspect");

        NullPointerException failure = assertThrows(NullPointerException.class,
            () -> tool.call(input, null));

        assertEquals("context", failure.getMessage());
    }

    @Test
    void callWithCustomFactory() {
        SubAgentFactory factory = request -> SubAgentResult.of("Custom result for: " + request.prompt());
        AgentTool tool = new AgentTool(factory);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "Do something");

        String result = text(tool.call(input, context));

        assertFalse(Strings.CS.startsWith(result, "Done ("), "Done summary must be UI-only, not in wire: " + result);
        assertTrue(Strings.CS.contains(result, "Custom result for: Do something"),
            "expected sub-agent output to be embedded, got: " + result);
        assertTrue(Strings.CS.contains(result, "<usage>"), "expected <usage> trailer, got: " + result);
    }

    @Test
    void subAgentInheritsParentBypassPermissionsAndCannotDowngradeIt() {
        SubAgentRequest[] captured = new SubAgentRequest[1];
        AgentTool tool = new AgentTool(request -> {
            captured[0] = request;
            return SubAgentResult.of("ok");
        });
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.put("mode", "plan");

        tool.call(input, context.withPermissionMode(PermissionModeKind.BYPASS_PERMISSIONS));

        assertNotNull(captured[0]);
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, captured[0].permissionMode(),
            "2.1.197 keeps the parent's bypassPermissions mode even when the spawned agent requests plan");
    }

    @Test
    void subAgentWithoutOverrideInheritsParentPermissionMode() {
        SubAgentRequest[] captured = new SubAgentRequest[1];
        AgentTool tool = new AgentTool(request -> {
            captured[0] = request;
            return SubAgentResult.of("ok");
        });
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");

        tool.call(input, context.withPermissionMode(PermissionModeKind.ACCEPT_EDITS));

        assertNotNull(captured[0]);
        assertEquals(PermissionMode.ACCEPT_EDITS, captured[0].permissionMode());
    }

    @Test
    void parentAcceptEditsAndAutoCannotBeDowngradedBySpawnOverride() {
        SubAgentRequest[] captured = new SubAgentRequest[1];
        AgentTool tool = new AgentTool(request -> {
            captured[0] = request;
            return SubAgentResult.of("ok");
        });
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.put("mode", "plan");

        tool.call(input, context.withPermissionMode(PermissionModeKind.ACCEPT_EDITS));
        assertEquals(PermissionMode.ACCEPT_EDITS, captured[0].permissionMode());

        tool.call(input, context.withPermissionMode(PermissionModeKind.AUTO));
        assertEquals(PermissionMode.AUTO, captured[0].permissionMode());
    }

    @Test
    void ordinaryParentModeAllowsSpawnOverride() {
        SubAgentRequest[] captured = new SubAgentRequest[1];
        AgentTool tool = new AgentTool(request -> {
            captured[0] = request;
            return SubAgentResult.of("ok");
        });
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.put("mode", "plan");

        tool.call(input, context.withPermissionMode(PermissionModeKind.DEFAULT));

        assertNotNull(captured[0]);
        assertEquals(PermissionMode.PLAN, captured[0].permissionMode());
    }

    @Test
    void bubbleAgentUsesDefaultAskBehaviorForOrdinaryParentModes() throws IOException {
        Path agentsDir = projectDir.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("bubble-worker.md"), """
            ---
            name: bubble-worker
            description: Exercise released bubble permissions
            permissionMode: bubble
            ---
            Inspect the requested files.
            """);
        AgentDefinitionLoader.clearCache();

        SubAgentRequest[] captured = new SubAgentRequest[1];
        AgentTool tool = new AgentTool(request -> {
            captured[0] = request;
            return SubAgentResult.of("ok");
        });
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.put("subagent_type", "bubble-worker");

        for (PermissionModeKind parentMode : List.of(
                PermissionModeKind.DEFAULT,
                PermissionModeKind.PLAN,
                PermissionModeKind.DONT_ASK)) {
            ToolExecutionContext parent = ToolExecutionContext
                .builder(new AbortController(), "test-session")
                .workingDirectory(projectDir.toString())
                .currentPermissionMode(parentMode)
                .build();

            tool.call(input, parent);

            assertNotNull(captured[0]);
            assertEquals(PermissionMode.DEFAULT, captured[0].permissionMode(),
                "bubble should use normal ASK behavior instead of " + parentMode);
        }
    }

    @Test
    void protectedParentModesTakePrecedenceOverBubbleAgent() throws IOException {
        Path agentsDir = projectDir.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("bubble-protected.md"), """
            ---
            name: bubble-protected
            description: Exercise protected parent permissions
            permissionMode: bubble
            ---
            Inspect the requested files.
            """);
        AgentDefinitionLoader.clearCache();

        SubAgentRequest[] captured = new SubAgentRequest[1];
        AgentTool tool = new AgentTool(request -> {
            captured[0] = request;
            return SubAgentResult.of("ok");
        });
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.put("subagent_type", "bubble-protected");

        for (PermissionMode expected : List.of(
                PermissionMode.BYPASS_PERMISSIONS,
                PermissionMode.ACCEPT_EDITS,
                PermissionMode.AUTO)) {
            ToolExecutionContext parent = ToolExecutionContext
                .builder(new AbortController(), "test-session")
                .workingDirectory(projectDir.toString())
                .currentPermissionMode(expected.kind())
                .build();

            tool.call(input, parent);

            assertNotNull(captured[0]);
            assertEquals(expected, captured[0].permissionMode(),
                "protected parent mode should win over bubble");
        }
    }

    @Test
    void registryPreservesReleased197CompletedAgentBlocksMetadataAndLifecycle() {
        MessageQueueManager queue = new MessageQueueManager();
        ToolExecutionContext wireContext = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .workingDirectory("/tmp/project")
            .fileStateCache(new FileStateCache())
            .messageQueueManager(queue)
            .nestedMemoryAttachmentTriggers(ConcurrentHashMap.newKeySet())
            .loadedNestedMemoryPaths(ConcurrentHashMap.newKeySet())
            .currentModel("claude-sonnet-4-6")
            .toolUseId("toolu_197_agent_probe")
            .build();
        AgentTool tool = new AgentTool(_ -> SubAgentResult.of("OK", 2, 0.0, 0, 110));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "boot");
        input.put("prompt", "Return exactly OK");
        input.put("subagent_type", "general-purpose");

        ToolResult result = registry.execute("Agent", input, wireContext);

        assertFalse(result.isError());
        assertEquals(2, result.content().size(),
            "2.1.197 keeps the agent response and continuation trailer as separate text blocks");
        assertEquals("OK", ((TextBlock) result.content().getFirst()).text());
        String trailer = ((TextBlock) result.content().get(1)).text();
        assertTrue(Strings.CS.contains(trailer, "<usage>subagent_tokens: 2"), trailer);
        assertFalse(Strings.CS.contains(trailer, "total_tokens:"), trailer);
        assertTrue(Strings.CS.contains(trailer,
            "summary: '<5-10 word recap>' to continue this agent"),
            "released 2.1.197 requires the continuation summary placeholder: " + trailer);

        Map<?, ?> payload = assertInstanceOf(Map.class, result.toolUseResult());
        assertEquals("completed", payload.get("status"));
        assertEquals("Return exactly OK", payload.get("prompt"));
        assertEquals("general-purpose", payload.get("agentType"));
        assertEquals("claude-sonnet-4-6", payload.get("resolvedModel"));
        assertEquals(2L, payload.get("totalTokens"));
        assertEquals(0, payload.get("totalToolUseCount"));
        assertFalse(payload.containsKey("toolStats"),
            "released Agent results omit the optional toolStats bag when no tools ran");
        assertNotNull(payload.get("usage"));
        List<?> structuredContent = assertInstanceOf(List.class, payload.get("content"));
        Map<?, ?> structuredText = assertInstanceOf(Map.class, structuredContent.getFirst());
        assertEquals("text", structuredText.get("type"));
        assertEquals("OK", structuredText.get("text"));

        List<SDKMessage> lifecycle = queue.drainSdkEvents();
        assertEquals(4, lifecycle.size());
        SDKMessage.TaskStarted started = assertInstanceOf(SDKMessage.TaskStarted.class, lifecycle.getFirst());
        assertEquals("toolu_197_agent_probe", started.toolUseId());
        assertEquals("general-purpose", started.subagentType());
        SDKMessage.User child = assertInstanceOf(SDKMessage.User.class, lifecycle.get(1));
        assertEquals("toolu_197_agent_probe", child.parentToolUseId());
        assertFalse(child.message().message().isText(),
            "the released stream-json child prompt is a text-block array, not a scalar string");
        assertEquals(List.of(new TextBlock("Return exactly OK")), child.message().message().blocks());
    }

    @Test
    void registryMapsAgentFailureToReleased197ErrorResultInsteadOfCompletedPayload() {
        AgentTool tool = new AgentTool(_ ->
            SubAgentResult.error("API request failed: x-api-key header is required"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "inspect");
        input.put("prompt", "Inspect the module");
        input.put("subagent_type", "Explore");

        ToolResult result = registry.execute("Agent", input, context);

        assertTrue(result.isError());
        assertEquals(1, result.content().size());
        assertEquals("API request failed: x-api-key header is required",
            ((TextBlock) result.content().getFirst()).text());
        Map<?, ?> payload = assertInstanceOf(Map.class, result.toolUseResult());
        assertEquals("failed", payload.get("status"));
        assertEquals("Inspect the module", payload.get("prompt"));
        assertEquals("Explore", payload.get("agentType"));
        assertNotNull(payload.get("agentId"));
        assertEquals("API request failed: x-api-key header is required", payload.get("error"));
    }

    @Test
    void failedExecutionPrefersNonBlankTerminalError() {
        AgentTool tool = new AgentTool(_ -> AgentExecutionResult.builder("fallback output")
            .termination(SubAgentTermination.FAILED)
            .terminalError("provider request failed")
            .build());
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "inspect");
        input.put("prompt", "Inspect the module");
        input.put("subagent_type", "general-purpose");

        ToolResult result = registry.execute("Agent", input, context);

        assertTrue(result.isError());
        assertEquals("provider request failed", ((TextBlock) result.content().getFirst()).text());
    }

    @Test
    void failedExecutionFallsBackToOutputWhenTerminalErrorIsBlank() {
        AgentTool tool = new AgentTool(_ -> AgentExecutionResult.builder("fallback output")
            .termination(SubAgentTermination.FAILED)
            .terminalError("   ")
            .build());
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "inspect");
        input.put("prompt", "Inspect the module");
        input.put("subagent_type", "general-purpose");

        ToolResult result = registry.execute("Agent", input, context);

        assertTrue(result.isError());
        assertEquals("fallback output", ((TextBlock) result.content().getFirst()).text());
    }

    @Test
    void budgetTerminationCannotBecomeCompletedOrEmptySuccess() {
        AgentTool tool = new AgentTool(_ -> AgentExecutionResult.builder("")
            .termination(SubAgentTermination.MAX_BUDGET)
            .stopReason("tool_use")
            .build());
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "inspect");
        input.put("prompt", "Inspect the module");
        input.put("subagent_type", "general-purpose");

        ToolResult result = registry.execute("Agent", input, context);

        assertTrue(result.isError());
        String text = ((TextBlock) result.content().getFirst()).text();
        assertTrue(Strings.CS.contains(text, "maximum budget"), text);
        assertFalse(Strings.CS.contains(text, "completed but returned no output"), text);
        assertFalse(Strings.CS.contains(text, "SendMessage"), text);
    }

    @Test
    void callWithToolListRestriction() {
        SubAgentFactory factory = request -> {
            assertEquals(List.of("Bash", "FileRead"), request.tools());
            return SubAgentResult.of("ok");
        };
        AgentTool tool = new AgentTool(factory);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.putArray("tools").add("Bash").add("FileRead");

        tool.call(input, context);
    }

    @Test
    void omittedTypeUsesGeneralPurposeWildcardToolSet() {
        SubAgentFactory factory = request -> {
            assertNull(request.subagentType(),
                "omission remains omitted in lifecycle metadata");
            assertTrue(request.tools().isEmpty(),
                "general-purpose wildcard is resolved and frozen by the child factory");
            return SubAgentResult.of("ok");
        };
        AgentTool tool = new AgentTool(factory);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");

        tool.call(input, context);
    }

    @Test
    void agentDisallowedTools_coversInteractiveCoordinationTools() {

        // never be handed these, even if the caller asks for them.
        assertTrue(AgentTool.AGENT_DISALLOWED_TOOLS.contains("AskUserQuestion"));
        assertTrue(AgentTool.AGENT_DISALLOWED_TOOLS.contains("ExitPlanMode"));
        assertTrue(AgentTool.AGENT_DISALLOWED_TOOLS.contains("EnterPlanMode"));
        assertFalse(AgentTool.AGENT_DISALLOWED_TOOLS.contains("Agent"));
        assertTrue(AgentTool.AGENT_DISALLOWED_TOOLS.contains("TaskOutput"));
        assertTrue(AgentTool.AGENT_DISALLOWED_TOOLS.contains("TaskStop"));
        assertTrue(AgentTool.AGENT_DISALLOWED_TOOLS.contains("Workflow"));
    }

    @Test
    void callWithDisallowedTools_stripsThemFromSubAgentToolList() {
        SubAgentFactory factory = request -> {
            assertFalse(request.tools().contains("AskUserQuestion"),
                "AskUserQuestion must be stripped from the sub-agent tool list");
            assertFalse(request.tools().contains("ExitPlanMode"),
                "ExitPlanMode must be stripped from the sub-agent tool list");
            assertTrue(request.tools().contains("Bash"),
                "non-disallowed tools must be preserved");
            return SubAgentResult.of("ok");
        };
        AgentTool tool = new AgentTool(factory);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.putArray("tools").add("AskUserQuestion").add("Bash").add("ExitPlanMode");

        tool.call(input, context);
    }

    @Test
    void callWithOnlyDisallowedTools_fallsBackToDefaultSafeTools() {
        SubAgentFactory factory = request -> {
            assertEquals(AgentTool.DEFAULT_SAFE_TOOLS, request.tools());
            return SubAgentResult.of("ok");
        };
        AgentTool tool = new AgentTool(factory);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.putArray("tools").add("AskUserQuestion").add("ExitPlanMode");

        tool.call(input, context);
    }

    @Test
    void callWithBudgetAllocation() {
        SubAgentFactory factory = request -> {
            assertEquals(0.5, request.budgetUsd(), 0.001);
            return SubAgentResult.of("ok");
        };
        AgentTool tool = new AgentTool(factory);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.put("budget_usd", 0.5);

        tool.call(input, context);
    }

    @Test
    void callWithDefaultBudget() {
        SubAgentFactory factory = request -> {
            assertEquals(-1.0, request.budgetUsd(), 0.001);
            return SubAgentResult.of("ok");
        };
        AgentTool tool = new AgentTool(factory);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");

        tool.call(input, context);
    }

    @Test
    void agentWithoutModelOverrideInheritsTheOwningEnginesCurrentModel() {
        ToolExecutionContext sonnet46Context = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .fileStateCache(new FileStateCache())
            .nestedMemoryAttachmentTriggers(ConcurrentHashMap.newKeySet())
            .loadedNestedMemoryPaths(ConcurrentHashMap.newKeySet())
            .currentModel("claude-sonnet-4-6")
            .build();
        SubAgentRequest[] captured = new SubAgentRequest[1];
        AgentTool tool = new AgentTool(request -> {
            captured[0] = request;
            return SubAgentResult.of("ok");
        });
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");

        tool.call(input, sonnet46Context);

        assertNotNull(captured[0]);
        assertEquals("claude-sonnet-4-6", captured[0].model());
    }

    @Test
    void callWithFactoryException() {
        SubAgentFactory factory = _ -> { throw new RuntimeException("boom"); };
        AgentTool tool = new AgentTool(factory);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");

        ToolResult result = tool.call(input, context);
        assertTrue(result.isError());
        assertEquals("boom", text(result));
        Map<?, ?> payload = assertInstanceOf(Map.class, result.toolUseResult());
        assertEquals("failed", payload.get("status"));
        assertEquals("test", payload.get("prompt"));
        assertNotNull(payload.get("agentId"));
        assertEquals("boom", payload.get("error"));
    }

    @Test
    void schemaHasRequiredFields() {
        AgentTool tool = new AgentTool();
        var schema = tool.inputSchema();
        assertTrue(schema.has("properties"));
        assertTrue(schema.get("properties").has("prompt"));
    }

    @Test
    void schemaOmitsToolsAndBudgetFromModelContract() {


        AgentTool tool = new AgentTool();
        var props = tool.inputSchema().get("properties");
        assertFalse(props.has("tools"));
        assertFalse(props.has("budget_usd"));
    }

    @Test
    void isReadOnly() {

        // itself a mutation; the sub-agent runs its own permission checks.
        assertTrue(new AgentTool().isReadOnly());
    }



    //    returned string, which is deterministic (call returns the
    //    async_launched / Done summary synchronously); the background vthread
    //    that actually runs the sub-agent is irrelevant to the assertion. ──

    @Test
    void runInBackground_forcesAsyncPath() {
        SubAgentFactory factory = _ -> SubAgentResult.of("ok");
        AgentTool tool = new AgentTool(factory);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.put("run_in_background", true);
        String result = text(tool.call(input, context));
        assertTrue(Strings.CS.contains(result, "Async agent launched successfully"),
            "run_in_background must route to the async path, got: " + result);
    }

    @Test
    void asyncAlias_forcesAsyncPath() {
        SubAgentFactory factory = _ -> SubAgentResult.of("ok");
        AgentTool tool = new AgentTool(factory);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.put("async", true);
        String result = text(tool.call(input, context));
        assertTrue(Strings.CS.contains(result, "Async agent launched successfully"),
            "async alias must route to the async path, got: " + result);
    }

    @Test
    void fork_forcesAsyncPath() {

        // per-call `fork` input to that term.
        SubAgentFactory factory = _ -> SubAgentResult.of("ok");
        AgentTool tool = new AgentTool(factory);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.put("fork", true);
        String result = text(tool.call(input, context));
        assertTrue(Strings.CS.contains(result, "Async agent launched successfully"),
            "fork must force the async path, got: " + result);
    }

    @Test
    void btwForkLauncherCarriesTheVisibleExchangeAndDerivesReleasedName() throws Exception {
        SubAgentRequest[] captured = new SubAgentRequest[1];
        AgentTool tool = new AgentTool(request -> {
            captured[0] = request;
            return SubAgentResult.of("done");
        }, new TaskRegistry(TaskStore.inMemory()), projectDir);
        UserMessage parent = new UserMessage("parent", MessageContent.ofText("main turn"));
        UserMessage question = new UserMessage("side-user", MessageContent.ofText("Why is this?"));
        AssistantMessage answer = new AssistantMessage("side-assistant",
            AssistantContent.of(List.of(new TextBlock("Because."))));
        ToolExecutionContext forkContext = context.toBuilder()
            .workingDirectory(projectDir.toString())
            .conversationMessages(List.of(parent))
            .renderedSystemPrompt("system")
            .build();

        AgentTool.SpawnedFork fork = tool.spawnForkFromDirective(
            "Why is this?", List.of(question, answer), forkContext);

        assertNotNull(fork);
        assertEquals("why-is-this", fork.name());
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (captured[0] == null && System.nanoTime() < deadline) Thread.onSpinWait();
        assertNotNull(captured[0]);
        List<Message> prior = captured[0].priorMessages();
        assertEquals(List.of("parent", "side-user", "side-assistant"),
            prior.subList(0, 3).stream().map(Message::uuid).toList());
        assertTrue(captured[0].fork());
        assertTrue(captured[0].async());
    }

    @Test
    void absentAsyncFlags_runSync_notAsync() {
        // Without run_in_background / async / fork / background-def, the spawn
        // must stay synchronous (call returns the result directly, NOT the
        // "Async agent launched" message). The "Done (…)" summary is UI-only
        // and must not appear in the wire content.
        SubAgentFactory factory = _ -> SubAgentResult.of("ok");
        AgentTool tool = new AgentTool(factory);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        String result = text(tool.call(input, context));
        assertFalse(Strings.CS.startsWith(result, "Async agent launched"),
            "absent async triggers must run synchronously, got: " + result);
        assertFalse(Strings.CS.startsWith(result, "Done ("),
            "Done summary is UI-only, must not be in wire, got: " + result);
        assertTrue(Strings.CS.contains(result, "ok"),
            "expected sub-agent output, got: " + result);
    }

    @Test
    void cwdThreadsIntoSubAgentRequest() {
        // Item ③: the optional `cwd` input must reach the sub-engine. Captured
        // on the synchronous path (no async flags).
        SubAgentRequest[] captured = new SubAgentRequest[1];
        SubAgentFactory factory = request -> { captured[0] = request; return SubAgentResult.of("ok"); };
        AgentTool tool = new AgentTool(factory);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "test");
        input.put("cwd", "/tmp/some-agent-cwd");
        tool.call(input, context);
        assertNotNull(captured[0], "sub-agent request should have been captured");
        assertEquals("/tmp/some-agent-cwd", captured[0].cwd());
    }

    private static String text(ToolResult result) {
        return result.content().stream()
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::text)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }
}
