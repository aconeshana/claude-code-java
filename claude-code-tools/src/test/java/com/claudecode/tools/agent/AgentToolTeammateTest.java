package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the in-process teammate branch in {@link AgentTool} (the
 * agent-teams subsystem, opt-in behind {@link AgentTeamsEnabled}).
 *
 * <p>Verifies both sides of the opt-in gate:
 * <ul>
 *   <li>gate OFF → the teammate branch is never taken (the spawn falls through
 *       to the normal sub-agent path, so behavior is unchanged for existing
 *       callers);</li>
 *   <li>gate ON + {@code subagent_type=in_process_teammate} →
 *       {@code handleTeammateExecution} runs, registers a live teammate handle
 *       in the {@link TaskRegistry}, and returns the launch message.</li>
 * </ul>
 */
@Timeout(20)
class AgentToolTeammateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TeammateMailbox mailbox = TeammateMailbox.instance();

    @AfterEach
    void reset() {
        AgentTeamsEnabled.resetForTest();
        mailbox.clearAll();
        // Restore the shared singleton so other tests aren't polluted by the
        // global-registry test below.
        TaskRegistry.resetGlobalForTest();
    }

    private static ObjectNode teammateInput() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "research");
        input.put("prompt", "explore the repo");
        input.put("subagent_type", "in_process_teammate");
        return input;
    }

    private static ToolExecutionContext context() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    @Test
    void gateOffDoesNotEnterTeammateBranch() {
        // Force the gate OFF (independent of the ambient env).
        AgentTeamsEnabled.setEnabledForTest(false);

        AgentTool tool = new AgentTool(new NoOpSubAgentFactory());
        String result = text(tool.call(teammateInput(), context()));

        // Falls through to the normal (NoOp) sub-agent path.
        assertTrue(Strings.CS.contains(result, "Sub-agent not configured"),
            "gate-off spawn must use the normal sub-agent path, got: " + result);
        assertFalse(Strings.CS.contains(result, "In-process teammate launched"),
            "gate-off spawn must NOT enter the teammate branch");
    }

    @Test
    void gateOnSpawnsAndRegistersTeammate() {
        AgentTeamsEnabled.setEnabledForTest(true);

        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(new NoOpSubAgentFactory(), registry, null);

        String result = text(tool.call(teammateInput(), context()));

        assertTrue(Strings.CS.contains(result, "In-process teammate launched successfully"),
            "gate-on teammate spawn must return the launch message, got: " + result);

        // The launch message carries the task id; the live handle is registered.
        int idx = result.indexOf("taskId: ");
        assertTrue(idx >= 0, "launch message must include the taskId");
        String taskId = result.substring(idx + "taskId: ".length()).split("\n")[0].trim();

        var handle = registry.getTeammateHandle(taskId);
        assertTrue(handle.isPresent(), "teammate handle must be registered in the registry");

        // Clean up the started teammate so its threads don't leak.
        handle.get().stop();
    }

    @Test
    void canonicalNamedTeamSpawnUsesDisplayName() {
        AgentTeamsEnabled.setEnabledForTest(true);
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(new NoOpSubAgentFactory(), registry, null);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "research");
        input.put("prompt", "explore the repo");
        input.put("subagent_type", "general-purpose");
        input.put("name", "researcher");
        input.put("team_name", "ui-review");

        String result = text(tool.call(input, context()));
        String taskId = result.substring(result.indexOf("taskId: ") + "taskId: ".length())
            .split("\n")[0].trim();
        var handle = registry.getTeammateHandle(taskId).orElseThrow();

        assertEquals("researcher", handle.name());
        handle.stop();
    }

    @Test
    void gateOnWithoutExplicitRegistryUsesGlobal() {
        AgentTeamsEnabled.setEnabledForTest(true);
        // Give the test a private global singleton so we don't pollute the real one.
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));

// No explicit registry → AgentTool falls back to TaskRegistry.global.
        AgentTool tool = new AgentTool(new NoOpSubAgentFactory(), null, null);
        String result = text(tool.call(teammateInput(), context()));

        assertTrue(Strings.CS.contains(result, "In-process teammate launched successfully"));

        int idx = result.indexOf("taskId: ");
        String taskId = result.substring(idx + "taskId: ".length()).split("\n")[0].trim();
        var handle = TaskRegistry.global().getTeammateHandle(taskId);
        assertTrue(handle.isPresent());
        handle.get().stop();
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
