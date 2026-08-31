package com.claudecode.runtime.query;

import com.claudecode.core.engine.*;

import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers Request 2: a tool Pre/Post hook returning {@code continue:false}
 * (PreventContinuation) must stop the whole query. The signal is surfaced by
 * {@link HookDispatcher.HookOutcome} from the hook dispatch in
 * {@link ToolExecution#execute}, threaded through {@link ToolExecution.StepResult}
 * and folded into {@link ToolRunner.RunOutcome} so the loop can emit
 * {@code Terminal.HookStopped}. Also covers a PreToolUse block skipping execution.
 */
class ToolExecutionPreventContinuationTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    private static DefaultQuerySession newEngine(ToolExecutor executor, HookDispatcher hooks) {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT).toolExecutor(executor).build());
        engine.setHookDispatcher(hooks);
        return engine;
    }

    private static ToolUseBlock editBlock() {
        return new ToolUseBlock("tu-1", "Edit", JsonUtils.getMapper().createObjectNode());
    }

    private static HookDispatcher hooksReturning(boolean atPre, boolean atPost, String reason) {
        return new HookDispatcher() {
            @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
            @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
            @Override public void dispatchUserPromptSubmit(String p) {}
            @Override public void dispatchStop(String r) {}
            @Override public void dispatchSessionStart(String tr) {}

            @Override
            public HookOutcome dispatchPreToolUseWithOutcome(String t, JsonNode i, String id) {
                return atPre ? new HookOutcome(true, null, List.of(), true, reason) : HookOutcome.PROCEED;
            }

            @Override
            public HookOutcome dispatchPostToolUseWithOutcome(String t, JsonNode i, JsonNode o, String id) {
                return atPost ? new HookOutcome(true, null, List.of(), true, reason) : HookOutcome.PROCEED;
            }
        };
    }

    @Test
    void preToolUseStop_propagatesToRunOutcome() {
        AtomicBoolean executed = new AtomicBoolean(false);
        DefaultQuerySession engine = newEngine(
            (_, _, _) -> { executed.set(true); return ToolResult.success("ok"); },
            hooksReturning(true, false, "pre-stop"));

        ToolRunner.RunOutcome outcome = new ConcurrentToolRunner().run(
            List.of(editBlock()), engine, false, 1, _ -> {});

        assertTrue(outcome.preventContinuation(), "pre-hook stop must set preventContinuation");
        assertEquals("pre-stop", outcome.stopReason());
        assertTrue(executed.get(), "tool still runs after a stop hook (result is recorded)");
    }

    @Test
    void postToolUseStop_propagatesToRunOutcome() {
        DefaultQuerySession engine = newEngine(
            (_, _, _) -> ToolResult.success("ok"),
            hooksReturning(false, true, "post-stop"));

        ToolRunner.RunOutcome outcome = new ConcurrentToolRunner().run(
            List.of(editBlock()), engine, false, 1, _ -> {});

        assertTrue(outcome.preventContinuation(), "post-hook stop must set preventContinuation");
        assertEquals("post-stop", outcome.stopReason());
    }

    @Test
    void preventContinuation_survivesConcurrentBatch() {
        // Two Read calls form a concurrent batch (both concurrency-safe); a hook
        // stop on either must still reach RunOutcome, not be dropped by the
        // batched append/emit path.
        DefaultQuerySession engine = newEngine(
            (_, _, _) -> ToolResult.success("ok"),
            hooksReturning(true, false, "concurrent-stop"));

        ToolRunner.RunOutcome outcome = new ConcurrentToolRunner().run(
            List.of(new ToolUseBlock("tu-1", "Read", JsonUtils.getMapper().createObjectNode()),
                    new ToolUseBlock("tu-2", "Read", JsonUtils.getMapper().createObjectNode())),
            engine, false, 1, _ -> {});

        assertTrue(outcome.preventContinuation(),
            "hook stop inside a concurrent batch must not be dropped");
        assertEquals("concurrent-stop", outcome.stopReason());
    }

    @Test
    void postToolBatchFiresOnceAfterAllToolResultsAndCanStopTheTurn() {
        AtomicReference<JsonNode> calls = new AtomicReference<>();
        DefaultQuerySession engine = newEngine(
            (_, _, _) -> ToolResult.success("ok"),
            new HookDispatcher() {
                @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
                @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
                @Override public void dispatchUserPromptSubmit(String p) {}
                @Override public void dispatchStop(String r) {}
                @Override public void dispatchSessionStart(String tr) {}
                @Override public HookOutcome dispatchPostToolBatchWithOutcome(JsonNode toolCalls) {
                    calls.set(toolCalls);
                    return new HookOutcome(true, null, List.of(), true, "batch-stop");
                }
            });

        ToolRunner.RunOutcome outcome = new ConcurrentToolRunner().run(
            List.of(new ToolUseBlock("tu-1", "Read", JsonUtils.getMapper().createObjectNode()),
                    new ToolUseBlock("tu-2", "Read", JsonUtils.getMapper().createObjectNode())),
            engine, false, 1, _ -> {});

        assertEquals(2, calls.get().size());
        assertEquals("tu-1", calls.get().get(0).path("tool_use_id").asText());
        assertTrue(outcome.preventContinuation());
        assertEquals("batch-stop", outcome.stopReason());
    }

    @Test
    void postToolBatchSkipsTextOnlySupplementalMessagesWhenFindingToolResult() {
        AtomicReference<JsonNode> calls = new AtomicReference<>();
        DefaultQuerySession engine = newEngine(
            (_, _, _) -> ToolResult.success("ok").withNewMessages(List.of(
                new UserMessage("image-metadata", MessageContent.ofText("Image Size: 3000x2000")))),
            new HookDispatcher() {
                @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
                @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
                @Override public void dispatchUserPromptSubmit(String p) {}
                @Override public void dispatchStop(String r) {}
                @Override public void dispatchSessionStart(String tr) {}
                @Override public HookOutcome dispatchPostToolBatchWithOutcome(JsonNode toolCalls) {
                    calls.set(toolCalls);
                    return HookOutcome.PROCEED;
                }
            });

        assertDoesNotThrow(() -> new ConcurrentToolRunner().run(
            List.of(new ToolUseBlock("tu-image", "Read", JsonUtils.getMapper().createObjectNode())),
            engine, false, 1, _ -> {}));
        assertEquals("ok", calls.get().get(0).path("tool_response").get(0).path("text").asText());
    }

    @Test
    void preToolUseBlock_skipsExecution() {
        AtomicBoolean executed = new AtomicBoolean(false);
        DefaultQuerySession engine = newEngine(
            (_, _, _) -> { executed.set(true); return ToolResult.success("ok"); },
            new HookDispatcher() {
                @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
                @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
                @Override public void dispatchUserPromptSubmit(String p) {}
                @Override public void dispatchStop(String r) {}
                @Override public void dispatchSessionStart(String tr) {}
                @Override
                public HookOutcome dispatchPreToolUseWithOutcome(String t, JsonNode i, String id) {
                    return new HookOutcome(false, null, List.of("denied by hook"), false, null);
                }
            });

        ToolRunner.RunOutcome outcome = new ConcurrentToolRunner().run(
            List.of(editBlock()), engine, false, 1, _ -> {});

        assertFalse(executed.get(), "blocked PreToolUse hook must skip the tool executor");
        assertTrue(outcome.errorDuringExecution(), "denial surfaces as a tool error result");
    }

    @Test
    void preToolUseBlock_preservesOfficialErrorEnvelopeAndPermissionDenial() {
        AtomicBoolean executed = new AtomicBoolean(false);
        DefaultQuerySession engine = newEngine(
            (_, _, _) -> { executed.set(true); return ToolResult.success("ok"); },
            new HookDispatcher() {
                @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
                @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
                @Override public void dispatchUserPromptSubmit(String p) {}
                @Override public void dispatchStop(String r) {}
                @Override public void dispatchSessionStart(String tr) {}
                @Override
                public HookOutcome dispatchPreToolUseWithOutcome(String t, JsonNode i, String id) {
                    return new HookOutcome(false, null,
                        List.of("PreToolUse:Edit hook error: [deny-hook]: AUTO_HOOK_BLOCK\n"),
                        false, null);
                }
            });

        ToolExecution.ToolStep step = ToolExecution.execute(
            editBlock(), engine, null, _ -> {}, "assistant-1");

        assertFalse(executed.get(), "blocked hook must not invoke the tool executor");
        assertEquals("Error: PreToolUse:Edit hook error: [deny-hook]: AUTO_HOOK_BLOCK\n",
            step.resultMsg().toolUseResult());
        ToolResultBlock result = assertInstanceOf(
            ToolResultBlock.class, step.resultMsg().message().blocks().getFirst());
        assertEquals("PreToolUse:Edit hook error: [deny-hook]: AUTO_HOOK_BLOCK\n",
            assertInstanceOf(TextBlock.class, result.content().getFirst()).text());
        assertEquals(1, engine.getPermissionDenials().size());
        assertEquals("Edit", engine.getPermissionDenials().getFirst().toolName());
        assertEquals("tu-1", engine.getPermissionDenials().getFirst().toolUseId());
        assertTrue(engine.getPermissionDenials().getFirst().toolInput().isEmpty());
    }

    @Test
    void hookSpecificOutputCanUpdateToolInputAndSuccessfulOutput() {
        AtomicReference<JsonNode> executedInput = new AtomicReference<>();
        var updatedInput = JsonUtils.getMapper().createObjectNode().put("path", "fixed");
        var preSpecific = JsonUtils.getMapper().createObjectNode();
        preSpecific.put("hookEventName", "PreToolUse");
        preSpecific.set("updatedInput", updatedInput);
        var postSpecific = JsonUtils.getMapper().createObjectNode();
        postSpecific.put("hookEventName", "PostToolUse");
        postSpecific.put("updatedToolOutput", "rewritten");
        DefaultQuerySession engine = newEngine(
            (_, input, _) -> {
                executedInput.set(input);
                return ToolResult.success("original");
            },
            new HookDispatcher() {
                @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
                @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
                @Override public void dispatchUserPromptSubmit(String p) {}
                @Override public void dispatchStop(String r) {}
                @Override public void dispatchSessionStart(String tr) {}
                @Override public HookOutcome dispatchPreToolUseWithOutcome(String t, JsonNode i, String id) {
                    return new HookOutcome(true, null, List.of(), false, null, null,
                        null, List.of(new HookSpecificOutput("PreToolUse", preSpecific)));
                }
                @Override public HookOutcome dispatchPostToolUseWithOutcome(
                        String t, JsonNode i, JsonNode o, String id) {
                    return new HookOutcome(true, null, List.of(), false, null, null,
                        null, List.of(new HookSpecificOutput("PostToolUse", postSpecific)));
                }
            });

        ToolExecution.ToolStep step = ToolExecution.execute(
            editBlock(), engine, null, _ -> {}, "assistant-1");

        assertEquals("fixed", executedInput.get().path("path").asText());
        ToolResultBlock block = assertInstanceOf(
            ToolResultBlock.class, step.resultMsg().message().blocks().getFirst());
        assertEquals("rewritten",
            assertInstanceOf(TextBlock.class, block.content().getFirst()).text());
    }

    @Test
    void toolHookAdditionalContextIsInjectedAfterTheToolResult() {
        DefaultQuerySession engine = newEngine(
            (_, _, _) -> ToolResult.success("ok"),
            new HookDispatcher() {
                @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
                @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
                @Override public void dispatchUserPromptSubmit(String p) {}
                @Override public void dispatchStop(String r) {}
                @Override public void dispatchSessionStart(String tr) {}
                @Override public HookOutcome dispatchPreToolUseWithOutcome(String t, JsonNode i, String id) {
                    return new HookOutcome(true, "pre context", List.of());
                }
                @Override public HookOutcome dispatchPostToolUseWithOutcome(
                        String t, JsonNode i, JsonNode o, String id) {
                    return new HookOutcome(true, "post context", List.of());
                }
            });

        ToolExecution.ToolStep step = ToolExecution.execute(
            editBlock(), engine, null, _ -> {}, "assistant-1");

        assertEquals(2, step.newMessages().size());
        assertTrue(step.newMessages().getFirst().toString().contains("pre context"));
        assertTrue(step.newMessages().getLast().toString().contains("post context"));
    }

    @Test
    void permissionRequestHookCanResolveAskWithoutShowingTheDialog() {
        AtomicBoolean dialogShown = new AtomicBoolean(false);
        var specific = JsonUtils.getMapper().createObjectNode();
        specific.put("hookEventName", "PermissionRequest");
        specific.putObject("decision").put("behavior", "allow");
        HookDispatcher hooks = new HookDispatcher() {
            @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
            @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
            @Override public void dispatchUserPromptSubmit(String p) {}
            @Override public void dispatchStop(String r) {}
            @Override public void dispatchSessionStart(String tr) {}
            @Override public HookOutcome dispatchPermissionRequestWithOutcome(
                    String t, JsonNode i, String id) {
                return new HookOutcome(true, null, List.of(), false, null, null,
                    null, List.of(new HookSpecificOutput("PermissionRequest", specific)));
            }
        };
        DefaultQuerySession engine = newEngine((_, _, context) -> {
            PermissionAskCallback.Result answer = context.permissionAskCallback().ask(
                PermissionAskContext.simple("Edit",
                    JsonUtils.getMapper().createObjectNode(), "tu-1"));
            return answer.allowed() ? ToolResult.success("ok") : ToolResult.error("denied");
        }, hooks);
        engine.setPermissionAskCallback(_ -> {
            dialogShown.set(true);
            return PermissionAskCallback.Result.deny();
        });

        ToolExecution.ToolStep step = ToolExecution.execute(
            editBlock(), engine, null, _ -> {}, "assistant-1");

        assertFalse(dialogShown.get());
        assertFalse(step.error());
    }

    @Test
    void failedToolDispatchesPostToolUseFailureInsteadOfPostToolUse() {
        AtomicBoolean postSuccess = new AtomicBoolean(false);
        AtomicReference<String> failure = new AtomicReference<>();
        DefaultQuerySession engine = newEngine(
            (_, _, _) -> ToolResult.error("boom"),
            new HookDispatcher() {
                @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
                @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {
                    postSuccess.set(true);
                }
                @Override public void dispatchUserPromptSubmit(String p) {}
                @Override public void dispatchStop(String r) {}
                @Override public void dispatchSessionStart(String tr) {}
                @Override
                public HookOutcome dispatchPostToolUseFailureWithOutcome(
                        String toolName, JsonNode input, String toolUseId,
                        String error, boolean isInterrupt) {
                    failure.set(error);
                    return HookOutcome.PROCEED;
                }
            });

        ToolExecution.ToolStep step = ToolExecution.execute(
            editBlock(), engine, null, _ -> {}, "assistant-1");

        assertTrue(step.error());
        assertFalse(postSuccess.get(), "PostToolUse must not fire for failed tools");
        assertEquals("boom", failure.get());
    }

    @Test
    void thrownToolExceptionAlsoDispatchesPostToolUseFailure() {
        AtomicReference<String> failure = new AtomicReference<>();
        DefaultQuerySession engine = newEngine(
            (_, _, _) -> { throw new IllegalStateException("kaboom"); },
            new HookDispatcher() {
                @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
                @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
                @Override public void dispatchUserPromptSubmit(String p) {}
                @Override public void dispatchStop(String r) {}
                @Override public void dispatchSessionStart(String tr) {}
                @Override
                public HookOutcome dispatchPostToolUseFailureWithOutcome(
                        String toolName, JsonNode input, String toolUseId,
                        String error, boolean isInterrupt) {
                    failure.set(error);
                    return HookOutcome.PROCEED;
                }
            });

        ToolExecution.ToolStep step = ToolExecution.execute(
            editBlock(), engine, null, _ -> {}, "assistant-1");

        assertTrue(step.error());
        assertEquals("Tool execution failed: kaboom", failure.get());
    }
}
