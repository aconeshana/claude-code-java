package com.claudecode.tools.workflows;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.agent.SubAgentResult;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentWorkflowExecutorTest {

    @Test
    void mapsReleasedWorkflowAgentOptionsOntoExistingSubAgentRequest() {
        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        SubAgentFactory factory = request -> {
            captured.set(request);
            return SubAgentResult.of("{\"ok\":true}", 17, 0.01, 2, 123);
        };
        ToolExecutionContext parent = ToolExecutionContext.builder(new AbortController(), "session").workingDirectory("/repo").build();
        WorkflowAgentOptions options = new WorkflowAgentOptions(
            "inspect auth", "Review", JsonUtils.parseTree("""
                {"type":"object","properties":{"ok":{"type":"boolean"}}}
                """), "haiku", "low", "worktree", "Explore", 30_000L);

        WorkflowAgentResult result = new SubAgentWorkflowExecutor(factory).execute(
            new WorkflowAgentRequest("inspect the auth flow", options, parent));

        SubAgentRequest request = captured.get();
        assertEquals("inspect the auth flow", request.prompt());
        assertEquals("Explore", request.subagentType());
        assertEquals("haiku", request.model());
        assertEquals("low", request.effort());
        assertTrue(request.worktreeIsolation());
        assertEquals(options.schema(), request.jsonSchema());
        assertEquals("inspect auth", request.description());
        assertSame(parent, request.parentContext());
        assertSame(parent.abortController(), request.abortController(),
            "workflow retries must abort the QuerySession controller used by the subagent");
        assertEquals(SubAgentWorkflowExecutor.STRUCTURED_CUSTOM_AGENT_REMINDER,
            request.criticalSystemReminder());
        assertNull(request.systemPromptOverride());
        assertEquals("{\"ok\":true}", result.output());
        assertEquals(17, result.tokensUsed());
        assertEquals(2, result.toolUseCount());
        assertEquals(123, result.durationMs());
    }

    @Test
    void usesReleasedDefaultWorkflowAgentPromptsForTextAndStructuredCalls() {
        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        SubAgentFactory factory = request -> {
            captured.set(request);
            return SubAgentResult.of(request.jsonSchema() == null ? "text" : "{\"ok\":true}");
        };
        SubAgentWorkflowExecutor executor = new SubAgentWorkflowExecutor(factory);

        executor.execute(new WorkflowAgentRequest("plain", new WorkflowAgentOptions(
            null, null, null, null, null, null, null, null), null));
        assertEquals(SubAgentWorkflowExecutor.TEXT_SYSTEM_PROMPT,
            captured.get().systemPromptOverride());
        assertNull(captured.get().criticalSystemReminder());

        WorkflowAgentOptions structured = new WorkflowAgentOptions(
            null, null, JsonUtils.parseTree("{\"type\":\"object\"}"),
            null, null, null, null, null);
        executor.execute(new WorkflowAgentRequest("structured", structured, null));
        assertEquals(SubAgentWorkflowExecutor.STRUCTURED_SYSTEM_PROMPT,
            captured.get().systemPromptOverride());
        assertNull(captured.get().criticalSystemReminder());
    }

    @Test
    void rejectsUnknownAndPermissionDeniedCustomAgentTypesBeforeSpawning() {
        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        SubAgentFactory factory = request -> {
            captured.set(request);
            return SubAgentResult.of("unexpected");
        };
        ToolExecutionContext parent = ToolExecutionContext.builder(new AbortController(), "session").workingDirectory(".").build();

        WorkflowRuntimeException unknown = assertThrows(WorkflowRuntimeException.class,
            () -> new SubAgentWorkflowExecutor(factory).execute(new WorkflowAgentRequest(
                "work", new WorkflowAgentOptions(null, null, null, null, null,
                    null, "definitely-missing", null), parent)));
        assertTrue(Strings.CS.startsWith(unknown.getMessage(), "agent({agentType}): agent type 'definitely-missing' not found. Available agents: "));
        assertNull(captured.get());

        PermissionGate gate = new PermissionGate();
        gate.addRules(List.of(PermissionRule.withPattern("Agent", PermissionBehavior.DENY,
            RuleSource.USER_SETTINGS, "Explore")));
        WorkflowRuntimeException denied = assertThrows(WorkflowRuntimeException.class,
            () -> new SubAgentWorkflowExecutor(factory, gate).execute(new WorkflowAgentRequest(
                "work", new WorkflowAgentOptions(null, null, null, null, null,
                    null, "Explore", null), parent)));
        assertEquals("agent({agentType}): 'Explore' is denied by permission rule "
            + "'Agent(Explore)' from user settings.", denied.getMessage());
        assertNull(captured.get());
    }
}
