package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.feature.FeatureGate;
import com.claudecode.permissions.PermissionDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ValidationResult;


class TodoWriteToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolExecutionContext mainCtx(String sessionId) {
        return ToolExecutionContext.of(new AbortController(), sessionId);
    }

    private ToolExecutionContext subAgentCtx(String sessionId, String agentId) {
        return ToolExecutionContext.builder(new AbortController(), sessionId)
            .agentId(agentId)
            .build();
    }

    private ObjectNode completedTodos(int n) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode todos = root.putArray("todos");
        for (int i = 1; i <= n; i++) {
            ObjectNode t = todos.addObject();
            t.put("content", "task " + i);
            t.put("status", "completed");
            t.put("activeForm", "doing " + i);
        }
        return root;
    }

    private static final String NUDGE = "spawn the verification agent";

    @Test
    void promptAndDescriptionUseTheReleased197ResourceChannels() {
        TodoWriteTool tool = new TodoWriteTool();

        assertEquals(ToolTexts.description("TodoWrite"), tool.description());
        assertEquals(ToolTexts.prompt("TodoWrite", "long"), tool.prompt(null));

        tool.setSimplePromptSupplier(() -> true);
        assertEquals(ToolTexts.prompt("TodoWrite", "harness"), tool.prompt(null));
    }

    @Test
    void nudge_disabledByDefault() {
        TodoWriteTool tool = new TodoWriteTool();
        String result = tool.call(completedTodos(3), mainCtx("s1"));
        assertFalse(Strings.CS.contains(result, NUDGE), result);
    }

    @Test
    void released197DoesNotAddJavaSpecificVerificationNudge() {
        FeatureGate.withFlags(() -> {
            TodoWriteTool tool = new TodoWriteTool();
            String result = tool.call(completedTodos(3), mainCtx("s2"));
            assertFalse(Strings.CS.contains(result, NUDGE), result);
        }, FeatureGate.Flag.VERIFICATION_AGENT_NUDGE);
    }

    @Test
    void nudge_hiddenWhenTodoMentionsVerification() {
        FeatureGate.withFlags(() -> {
            ObjectNode root = completedTodos(3);
            ((ObjectNode) root.get("todos").get(0)).put("content", "verify the output");
            TodoWriteTool tool = new TodoWriteTool();
            String result = tool.call(root, mainCtx("s3"));
            assertFalse(Strings.CS.contains(result, NUDGE), result);
        }, FeatureGate.Flag.VERIFICATION_AGENT_NUDGE);
    }

    @Test
    void nudge_hiddenWhenFewerThan3() {
        FeatureGate.withFlags(() -> {
            TodoWriteTool tool = new TodoWriteTool();
            String result = tool.call(completedTodos(2), mainCtx("s4"));
            assertFalse(Strings.CS.contains(result, NUDGE), result);
        }, FeatureGate.Flag.VERIFICATION_AGENT_NUDGE);
    }

    @Test
    void nudge_hiddenWhenNotAllDone() {
        FeatureGate.withFlags(() -> {
            ObjectNode root = completedTodos(3);
            ((ObjectNode) root.get("todos").get(0)).put("status", "in_progress");
            TodoWriteTool tool = new TodoWriteTool();
            String result = tool.call(root, mainCtx("s5"));
            assertFalse(Strings.CS.contains(result, NUDGE), result);
        }, FeatureGate.Flag.VERIFICATION_AGENT_NUDGE);
    }

    @Test
    void nudge_hiddenWhenSubAgent() {
        FeatureGate.withFlags(() -> {
            TodoWriteTool tool = new TodoWriteTool();
            String result = tool.call(completedTodos(3), subAgentCtx("s6", "agent-1"));
            assertFalse(Strings.CS.contains(result, NUDGE), result);
        }, FeatureGate.Flag.VERIFICATION_AGENT_NUDGE);
    }



    @Test
    void checkPermissions_alwaysAllows() {
        TodoWriteTool tool = new TodoWriteTool();
        PermissionDecision decision = tool.checkPermissions(mapper.createObjectNode(), null);
        assertInstanceOf(PermissionDecision.Allow.class, decision);
    }

    @Test
    void validateInput_preservesReleased197MinLengthSemanticsForWhitespace() {
        TodoWriteTool tool = new TodoWriteTool();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode todos = root.putArray("todos");
        ObjectNode t = todos.addObject();
        t.put("content", "   ");
        t.put("status", "pending");
        t.put("activeForm", "doing");
        ValidationResult r = tool.validateInput(root, mainCtx("s-perm"));
        assertInstanceOf(ValidationResult.Valid.class, r);
    }

    @Test
    void validateInput_allowsNonEmptyContent() {
        TodoWriteTool tool = new TodoWriteTool();
        ValidationResult r = tool.validateInput(completedTodos(1), mainCtx("s-perm2"));
        assertInstanceOf(ValidationResult.Valid.class, r);
    }

    @Test
    void mapResult_preservesTsStructuredTodoPayload() {
        TodoWriteTool tool = new TodoWriteTool();
        ObjectNode input = completedTodos(2);
        ToolExecutionContext context = mainCtx("structured-todos");
        var invocation = tool.callWithResult(input, context);
        String text = invocation.rawResult();
        var mapped = invocation.mappedResult();
        assertNotNull(mapped.toolUseResult());
        var payload = (JsonNode) mapped.toolUseResult();
        assertEquals(0, payload.path("oldTodos").size());

        assertEquals(2, payload.path("newTodos").size());
        assertFalse(payload.has("verificationNudgeNeeded"));
        assertTrue(Strings.CS.contains(mapped.content().getFirst().toString(), "Todos have been modified"));
    }
}
