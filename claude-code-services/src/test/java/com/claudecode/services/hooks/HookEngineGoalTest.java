package com.claudecode.services.hooks;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.api.*;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.HookNonBlockingErrorAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.services.model.ModelOutputTokens;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

class HookEngineGoalTest {

    @Test
    void unmetGoalBlocksStopAndIncrementsActiveState() {
        CapturingClient client = new CapturingClient("{\"ok\":false,\"reason\":\"tests still fail\"}");
        HookEngine engine = engine(client);
        engine.setGoal("all tests pass", 100);

        HookDispatcher.HookOutcome outcome = engine.dispatchStopWithOutcome("success", false);

        assertTrue(outcome.hasBlockingErrors());
        assertEquals("[all tests pass]: tests still fail", outcome.blockingErrors().getFirst());
        HookDispatcher.ActiveGoal active = engine.activeGoal().orElseThrow();
        assertEquals(1, active.iterations());
        assertEquals("tests still fail", active.lastReason());
        HookDispatcher.GoalTransition transition = engine.consumeGoalTransition().orElseThrow();
        assertEquals(HookDispatcher.GoalTransitionKind.PENDING, transition.kind());
        assertEquals("tests still fail", transition.reason());
    }

    @Test
    void fencedUnmetGoalBlocksStopInsteadOfFailingOpen() {
        CapturingClient client = new CapturingClient(
            "  ```json\n{\"ok\":false,\"reason\":\"tests still fail\"}\n```  ");
        HookEngine engine = engine(client);
        engine.setGoal("all tests pass", 100);

        HookDispatcher.HookOutcome outcome = engine.dispatchStopWithOutcome("success", false);

        assertTrue(outcome.hasBlockingErrors());
        assertEquals("[all tests pass]: tests still fail", outcome.blockingErrors().getFirst());
        assertTrue(engine.consumeHookMessages().isEmpty());
        assertEquals(HookDispatcher.GoalTransitionKind.PENDING,
            engine.consumeGoalTransition().orElseThrow().kind());
    }

    @Test
    void metGoalClearsSessionHookAndPublishesFinalStats() {
        HookEngine engine = engine(new CapturingClient("{\"ok\":true,\"reason\":\"suite is green\"}"));
        engine.setGoal("all tests pass", 100);

        HookDispatcher.HookOutcome outcome = engine.dispatchStopWithOutcome("success", false);

        assertTrue(outcome.proceed());
        assertTrue(engine.activeGoal().isEmpty());
        HookDispatcher.GoalTransition transition = engine.consumeGoalTransition().orElseThrow();
        assertEquals(HookDispatcher.GoalTransitionKind.MET, transition.kind());
        assertEquals(1, transition.iterations());
        assertEquals(50, transition.tokens());
        assertEquals("suite is green", transition.reason());
    }

    @Test
    void impossibleGoalClearsWithoutBlockingAndMarksFailure() {
        HookEngine engine = engine(new CapturingClient(
            "{\"ok\":false,\"impossible\":true,\"reason\":\"resource unavailable\"}"));
        engine.setGoal("deploy to missing account", 100);

        HookDispatcher.HookOutcome outcome = engine.dispatchStopWithOutcome("success", false);

        assertTrue(outcome.proceed());
        assertTrue(engine.activeGoal().isEmpty());
        HookDispatcher.GoalTransition transition = engine.consumeGoalTransition().orElseThrow();
        assertEquals(HookDispatcher.GoalTransitionKind.FAILED, transition.kind());
        assertEquals("resource unavailable", transition.reason());
    }

    @Test
    void evaluatorRequestUsesReleasedStopConditionProtocolAndTranscript() {
        CapturingClient client = new CapturingClient("{\"ok\":true,\"reason\":\"done\"}");
        HookEngine engine = engine(client);
        engine.setGoal("feature is complete", 100);
        engine.dispatchStopWithOutcome("success", false);

        CreateMessageRequest request = client.request;
        assertNotNull(request);
        assertEquals("hook_prompt", request.querySource());
        assertTrue(request.stream());
        assertTrue(request.promptCachingEnabled());
        assertEquals((int) ModelOutputTokens.getMaxOutputTokensForModel("claude-sonnet-4-6"),
            request.maxTokens());
        assertEquals("disabled", request.thinking().type());
        assertEquals("high", request.outputConfig().effort());
        assertEquals("json_schema", request.outputConfig().format().path("type").asText());
        assertEquals("wire-user", request.metadata().path("user_id").asText());
        assertEquals(SystemPromptConstants.AGENT_SDK_SYSPROMPT_PREFIX + "\n\n"
            + "You are evaluating a stop-condition hook in Claude Code. Read the "
            + "conversation transcript carefully, then judge whether the user-provided "
            + "condition is satisfied.\n\n"
            + "Your response must be a JSON object with one of these shapes:\n"
            + "- {\"ok\": true, \"reason\": \"<quote evidence from the transcript that "
            + "satisfies the condition>\"}\n"
            + "- {\"ok\": false, \"reason\": \"<quote what is missing or what blocks the "
            + "condition>\"}\n"
            + "- {\"ok\": false, \"impossible\": true, \"reason\": \"<explain why the "
            + "condition can never be satisfied>\"}\n\n"
            + "Always include a \"reason\" field, quoting specific text from the transcript "
            + "whenever possible. If the transcript does not contain clear evidence that the "
            + "condition is satisfied, return {\"ok\": false, \"reason\": \"insufficient "
            + "evidence in transcript\"}.\n\n"
            + "Only use {\"ok\": false, \"impossible\": true} when the condition is genuinely "
            + "unachievable in this session — for example: the condition is self-contradictory, "
            + "it depends on a resource or capability that is unavailable, or the assistant has "
            + "explicitly tried, exhausted reasonable approaches, and stated it cannot be done. "
            + "Apply your own judgment when deciding this — the assistant claiming the goal is "
            + "impossible is evidence, not proof; independently confirm the condition is genuinely "
            + "unachievable rather than deferring to the assistant's self-assessment. Do not use "
            + "it just because the goal has not been reached yet or because progress is slow. "
            + "When in doubt, return {\"ok\": false} without \"impossible\".",
            request.systemPrompt());
        assertEquals("user", request.messages().getFirst().role());
        assertEquals("assistant", request.messages().get(1).role());
        String finalPrompt = request.messages().getLast().content().toString();
        assertTrue(Strings.CS.contains(finalPrompt, "Based on the conversation transcript above"));
        assertTrue(Strings.CS.contains(finalPrompt, "Condition: feature is complete"));
        assertTrue(Strings.CS.contains(finalPrompt, "\n\nARGUMENTS: {\"session_id\":\"session-197\""));
        assertTrue(Strings.CS.contains(finalPrompt, "\"prompt_id\":\"prompt-197\""));
        assertTrue(Strings.CS.contains(finalPrompt, "\"permission_mode\":\"bypassPermissions\""));
        assertTrue(Strings.CS.contains(finalPrompt, "\"effort\":{\"level\":\"high\"}"));
        assertTrue(Strings.CS.contains(finalPrompt, "\"background_tasks\":[]"));
        assertTrue(Strings.CS.contains(finalPrompt, "\"session_crons\":[]"));
    }

    @Test
    void releasedClaudeEvaluatorDefaultsMissingSessionEffortToHigh() {
        CapturingClient client = new CapturingClient("{\"ok\":true,\"reason\":\"done\"}");
        HookEngine engine = engine(client);
        engine.setGoalEffortSupplier(() -> null);
        engine.setGoal("feature is complete", 100);

        engine.dispatchStopWithOutcome("success", false);

        assertEquals("high", client.request.outputConfig().effort());
        assertTrue(Strings.CS.contains(
            client.request.messages().getLast().content().toString(),
            "\"effort\":{\"level\":\"high\"}"));
    }

    @Test
    void evaluatorMergesConsecutiveGoalCommandUserMessagesLikeTheMainWirePath() {
        CapturingClient client = new CapturingClient("{\"ok\":true,\"reason\":\"done\"}");
        HookEngine engine = engine(client);
        engine.setMessagesSupplier(() -> List.of(
            MessageFactory.createUserMessage("<command-name>/goal</command-name>"),
            MessageFactory.createUserMessage(
                "<local-command-stdout>Goal set: ship</local-command-stdout>"),
            MessageFactory.createUserMessage("activation"),
            new AssistantMessage("a1", AssistantContent.of(
                "response-1", List.of(new TextBlock("OK")), null))));
        engine.setGoal("ship", 0L);

        engine.dispatchStopWithOutcome("success", false);

        Object content = client.request.messages().getFirst().content();
        assertInstanceOf(List.class, content);
        JsonNode blocks = JsonUtils.getMapper().valueToTree(content);
        assertEquals(List.of(
                "<command-name>/goal</command-name>\n",
                "<local-command-stdout>Goal set: ship</local-command-stdout>\n",
                "activation"),
            StreamSupport.stream(blocks.spliterator(), false)
                .map(block -> block.path("text").asText())
                .toList());
    }

    @Test
    void backgroundWorkDefersOnlyGoalEvaluator() {
        CapturingClient client = new CapturingClient("{\"ok\":true,\"reason\":\"done\"}");
        HookEngine engine = engine(client);
        engine.setBackgroundTasksRunningSupplier(() -> true);
        engine.setGoal("background task finishes", 100);

        HookDispatcher.HookOutcome outcome = engine.dispatchStopWithOutcome("success", false);

        assertTrue(outcome.proceed());
        assertNull(client.request);
        assertTrue(engine.activeGoal().isPresent());
        assertTrue(engine.consumeGoalTransition().isEmpty());
    }

    @Test
    void restoreUsesLatestGoalStatusAndTerminalRowsSuppressOlderGoals() {
        HookEngine engine = engine(new CapturingClient("{\"ok\":true,\"reason\":\"done\"}"));
        List<Message> unresolved = List.of(
            new AttachmentMessage("a", GoalStatusAttachment.sentinel(false, "older")),
            new AttachmentMessage("b", GoalStatusAttachment.pending("new goal", "not yet")));

        engine.restoreGoalFromTranscript(unresolved, 150);
        assertEquals("new goal", engine.activeGoal().orElseThrow().condition());
        assertEquals(0, engine.activeGoal().orElseThrow().iterations());

        List<Message> resolved = List.of(
            new AttachmentMessage("a", GoalStatusAttachment.sentinel(false, "older")),
            new AttachmentMessage("b", GoalStatusAttachment.achieved(
                "older", "done", 3, 1000, 200)));
        engine.restoreGoalFromTranscript(resolved, 200);
        assertTrue(engine.activeGoal().isEmpty());
    }

    @Test
    void evaluatorTruncatesOldResponseGroupsPastHalfContextWindow() {
        CapturingClient client = new CapturingClient("{\"ok\":true,\"reason\":\"done\"}");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setLlmClient(client);
        engine.setLlmModel("claude-sonnet-4-6");
        String old = "OLD-EVIDENCE " + "x".repeat(260_000);
        String recent = "RECENT-EVIDENCE " + "y".repeat(260_000);
        engine.setMessagesSupplier(() -> List.of(
            MessageFactory.createUserMessage(old),
            new AssistantMessage("a1", AssistantContent.of(
                "response-1", List.of(new TextBlock("old response")), null)),
            MessageFactory.createUserMessage(recent),
            new AssistantMessage("a2", AssistantContent.of(
                "response-2", List.of(new TextBlock("recent response")),
                new Usage(150_000, 1_000, 0, 0)))));
        engine.setGoal("feature complete", 0L);

        engine.dispatchStopWithOutcome("success", false);

        List<CreateMessageRequest.RequestMessage> messages = client.request.messages();
        assertTrue(Strings.CS.contains(messages.getFirst().content().toString(), 
            "Earlier conversation truncated to fit the hook evaluator's context window"));
        assertFalse(messages.stream().anyMatch(message ->
            Strings.CS.contains(message.content().toString(), "OLD-EVIDENCE")));
        assertTrue(messages.stream().anyMatch(message ->
            Strings.CS.contains(message.content().toString(), "RECENT-EVIDENCE")));
    }

    @Test
    void impossibleIsTerminalOnlyForStopNotSubagentStop() {
        HookEngine engine = engine(new CapturingClient(
            "{\"ok\":false,\"impossible\":true,\"reason\":\"cannot be done\"}"));
        HookInput input = new HookInput(HookEvent.SUBAGENT_STOP,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of());

        HookResult result = engine.executePromptHook(new PromptHook("finish plan"), input, 30);

        assertInstanceOf(HookResult.ConditionNotMet.class, result);
    }

    @Test
    void promptTooLongRetriesOnceWithQuarterWindow() {
        CapturingClient client = new CapturingClient(
            new PromptTooLongException("Prompt is too long", 400,
                "invalid_request_error", null),
            "{\"ok\":true,\"reason\":\"done after truncation\"}");
        HookEngine engine = engine(client);
        engine.setGoal("feature complete", 0L);

        HookDispatcher.HookOutcome outcome = engine.dispatchStopWithOutcome("success", false);

        assertTrue(outcome.proceed());
        assertEquals(2, client.requests.size());
        assertTrue(engine.consumeHookMessages().isEmpty());
        assertEquals(HookDispatcher.GoalTransitionKind.MET,
            engine.consumeGoalTransition().orElseThrow().kind());
    }

    @Test
    void ordinaryApiErrorDoesNotRetryAndEmitsOfficialAttachment() {
        CapturingClient client = new CapturingClient(
            new ApiException("API Error: overloaded", 529));
        HookEngine engine = engine(client);
        engine.setGoal("feature complete", 0L);

        HookDispatcher.HookOutcome outcome = engine.dispatchStopWithOutcome("success", false);

        assertTrue(outcome.proceed());
        assertEquals(1, client.requests.size());
        assertTrue(engine.activeGoal().isPresent());
        assertTrue(engine.consumeGoalTransition().isEmpty());
        AttachmentMessage message = (AttachmentMessage)
            engine.consumeHookMessages().getFirst();
        HookNonBlockingErrorAttachment error =
            (HookNonBlockingErrorAttachment) message.payload();
        assertEquals("Stop", error.hookName());
        assertEquals("Stop", error.hookEvent());
        assertEquals("feature complete", error.command());
        assertEquals("Hook evaluator API error: API Error: overloaded", error.stderr());
        assertEquals(1, error.exitCode());
    }

    @Test
    void blankSuccessfulResponseIsValidationErrorNotRetry() {
        CapturingClient client = new CapturingClient("");
        HookEngine engine = engine(client);
        engine.setGoal("feature complete", 0L);

        engine.dispatchStopWithOutcome("success", false);

        assertEquals(1, client.requests.size());
        AttachmentMessage message = (AttachmentMessage)
            engine.consumeHookMessages().getFirst();
        HookNonBlockingErrorAttachment error =
            (HookNonBlockingErrorAttachment) message.payload();
        assertEquals("JSON validation failed", error.stderr());
        assertEquals("", error.stdout());
    }

    @Test
    void malformedJsonIsValidationErrorNotRetry() {
        CapturingClient client = new CapturingClient("not-json");
        HookEngine engine = engine(client);
        engine.setGoal("feature complete", 0L);

        engine.dispatchStopWithOutcome("success", false);

        assertEquals(1, client.requests.size());
        HookNonBlockingErrorAttachment error = (HookNonBlockingErrorAttachment)
            ((AttachmentMessage) engine.consumeHookMessages().getFirst()).payload();
        assertEquals("JSON validation failed", error.stderr());
        assertEquals("not-json", error.stdout());
    }

    @Test
    void invalidResultShapeIsSchemaValidationErrorNotRetry() {
        CapturingClient client = new CapturingClient("{\"ok\":false}");
        HookEngine engine = engine(client);
        engine.setGoal("feature complete", 0L);

        engine.dispatchStopWithOutcome("success", false);

        assertEquals(1, client.requests.size());
        HookNonBlockingErrorAttachment error = (HookNonBlockingErrorAttachment)
            ((AttachmentMessage) engine.consumeHookMessages().getFirst()).payload();
        assertTrue(Strings.CS.startsWith(error.stderr(), "Schema validation failed:"));
        assertEquals("{\"ok\":false}", error.stdout());
    }

    @Test
    void injectedNativeOneMillionWindowAvoidsTwoHundredKTruncation() {
        CapturingClient client = new CapturingClient("{\"ok\":true,\"reason\":\"done\"}");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setLlmClient(client);
        engine.setLlmModel("claude-opus-4-8");
        engine.setGoalContextWindowResolver(_ -> 1_000_000L);
        engine.setMessagesSupplier(() -> List.of(
            MessageFactory.createUserMessage("OLD-EVIDENCE " + "x".repeat(260_000)),
            new AssistantMessage("a1", AssistantContent.of(
                "response-1", List.of(new TextBlock("old response")), null)),
            MessageFactory.createUserMessage("RECENT-EVIDENCE " + "y".repeat(260_000)),
            new AssistantMessage("a2", AssistantContent.of(
                "response-2", List.of(new TextBlock("recent response")),
                new Usage(150_000, 1_000, 0, 0)))));
        engine.setGoal("feature complete", 0L);

        engine.dispatchStopWithOutcome("success", false);

        assertTrue(client.request.messages().stream().anyMatch(message ->
            Strings.CS.contains(message.content().toString(), "OLD-EVIDENCE")));
        assertFalse(Strings.CS.contains(client.request.messages().getFirst().content().toString(), 
            "Earlier conversation truncated"));
    }

    @Test
    void liveModelAndToolsReachGoalEvaluatorRequest() {
        CapturingClient client = new CapturingClient("{\"ok\":true,\"reason\":\"done\"}");
        HookEngine engine = engine(client);
        AtomicReference<String> model = new AtomicReference<>("claude-opus-4-8");
        engine.setLlmModelSupplier(model::get);
        engine.setGoalToolsSupplier(() -> List.of(
            new CreateMessageRequest.ToolDefinition(
                "Read", "Read a file", JsonUtils.getMapper().createObjectNode())));
        engine.setGoal("feature complete", 0L);

        engine.dispatchStopWithOutcome("success", false);

        assertEquals("claude-opus-4-8", client.request.model());
        assertEquals(List.of("Read"), client.request.tools().stream()
            .map(CreateMessageRequest.ToolDefinition::name)
            .toList());
    }

    private static HookEngine engine(CapturingClient client) {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp",
            SessionIdentity.of("session-197"));
        engine.setLlmClient(client);
        engine.setLlmModel("claude-sonnet-4-6");
        engine.setPermissionMode("bypassPermissions");
        engine.setPromptIdSupplier(() -> "prompt-197");
        engine.setGoalSystemPromptIdentitySupplier(
            () -> SystemPromptConstants.AGENT_SDK_SYSPROMPT_PREFIX);
        engine.setGoalMetadataSupplier(
            () -> JsonUtils.getMapper().createObjectNode().put("user_id", "wire-user"));
        engine.setGoalEffortSupplier(() -> "high");
        engine.setMessagesSupplier(() -> List.of(
            MessageFactory.createUserMessage("implement the feature"),
            MessageFactory.createAssistantMessage("implementation and tests are complete")));
        engine.setTokenCountSupplier(() -> 150L);
        return engine;
    }

    private static final class CapturingClient implements LlmClient {
        private final List<Object> outcomes;
        private final List<CreateMessageRequest> requests = new ArrayList<>();
        private int outcomeIndex;
        private CreateMessageRequest request;

        private CapturingClient(Object... outcomes) { this.outcomes = List.of(outcomes); }

        @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            this.request = request;
            this.requests.add(request);
            Object outcome = outcomes.get(Math.min(outcomeIndex++, outcomes.size() - 1));
            if (outcome instanceof RuntimeException failure) throw failure;
            return List.<StreamEvent>of(
                new StreamEvent.MessageStart(ApiMessage.builder()
                    .id("msg-goal").model("goal-test-model").usage(Usage.EMPTY).build()),
                new StreamEvent.ContentBlockStart(0, new TextBlock("")),
                new StreamEvent.ContentBlockDelta(0,
                    new Delta.TextDelta(String.valueOf(outcome))),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.MessageDelta(
                    new MessageDeltaData("end_turn", null), Usage.EMPTY),
                new StreamEvent.MessageStop()).iterator();
        }

        @Override public ApiMessage createMessage(CreateMessageRequest request) {
            this.request = request;
            this.requests.add(request);
            Object outcome = outcomes.get(Math.min(outcomeIndex++, outcomes.size() - 1));
            if (outcome instanceof RuntimeException failure) throw failure;
            return ApiMessage.stub("goal-test-model", String.valueOf(outcome));
        }

        @Override public String getModel() { return "goal-test-model"; }
    }
}
