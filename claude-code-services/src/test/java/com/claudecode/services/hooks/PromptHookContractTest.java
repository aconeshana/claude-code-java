package com.claudecode.services.hooks;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.Delta;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.agent.SubAgentResult;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;


class PromptHookContractTest {

    @Test
    void promptHookFalseIsBlockInsteadOfDecisionLessAllow() {
        HookEngine engine = engineWithResponse("{\"ok\":false,\"reason\":\"unsafe\"}");

        HookResult result = engine.executePromptHook(
            new PromptHook("check the operation"),
            HookInput.forEvent(HookEvent.PERMISSION_REQUEST), 30);

        HookResult.Block block = assertInstanceOf(HookResult.Block.class, result);
        assertEquals("unsafe", block.reason());
    }

    @Test
    void promptHookTrueIsAllow() {
        HookResult result = engineWithResponse("{\"ok\":true}").executePromptHook(
            new PromptHook("check the operation"),
            HookInput.forEvent(HookEvent.PERMISSION_REQUEST), 30);

        assertInstanceOf(HookResult.Allow.class, result);
    }

    @Test
    void missingOkWrongTypeExtraFieldAndLegacyDecisionAreNonBlockingErrors() {
        for (String response : new String[] {
            "{}", "{\"ok\":\"false\"}",
            "{\"ok\":true,\"extra\":1}", "{\"decision\":\"allow\"}"
        }) {
            HookEngine engine = engineWithResponse(response);
            HookResult result = engine.executePromptHook(
                new PromptHook("check the operation"),
                HookInput.forEvent(HookEvent.PERMISSION_REQUEST), 30);
            assertInstanceOf(HookResult.Skip.class, result, response);
            assertEquals(1, engine.consumeHookMessages().size(), response);
        }
    }

    @Test
    void agentHookUsesSameStrictContract() {
        HookResult result = engineWithResponse("{\"ok\":false,\"reason\":\"incorrect\"}")
            .executeAgentHook(new AgentHook("verify the operation"),
                HookInput.forEvent(HookEvent.POST_TOOL_USE), 30);

        HookResult.Block block = assertInstanceOf(HookResult.Block.class, result);
        assertEquals("incorrect", block.reason());
    }

    @Test
    void agentHookRunsARealToolUsingVerifierAgent() {
        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setAgentHookFactory(request -> {
            captured.set(request);
            return SubAgentResult.of("{\"ok\":false,\"reason\":\"not verified\"}");
        });

        HookResult result = engine.executeAgentHook(new AgentHook("verify $ARGUMENTS"),
            HookInput.forEvent(HookEvent.POST_TOOL_USE, "session", "/tmp"), 30);

        assertEquals("not verified", assertInstanceOf(HookResult.Block.class, result).reason());
        assertEquals(50, captured.get().maxTurns());
        assertEquals("dontAsk", captured.get().permissionMode().external());
        assertTrue(captured.get().systemPromptOverride().contains("conversation transcript"));
        assertTrue(captured.get().jsonSchema().path("required").toString().contains("ok"));
    }

    private static HookEngine engineWithResponse(String response) {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setLlmClient(new FixedResponseClient(response));
        engine.setLlmModel("claude-sonnet-4-6");
        return engine;
    }

    private static final class FixedResponseClient implements LlmClient {
        private final String response;

        private FixedResponseClient(String response) {
            this.response = response;
        }

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            assertTrue(Strings.CS.contains(request.outputConfig().format().path("schema").path("required")
                .toString(), "ok"));
            return List.<StreamEvent>of(
                new StreamEvent.ContentBlockDelta(0, new Delta.TextDelta(response)),
                new StreamEvent.MessageStop()).iterator();
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            return ApiMessage.stub(request.model(), response);
        }

        @Override
        public String getModel() {
            return "claude-sonnet-4-6";
        }
    }
}
