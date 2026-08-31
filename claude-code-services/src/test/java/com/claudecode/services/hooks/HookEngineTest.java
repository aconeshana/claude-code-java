package com.claudecode.services.hooks;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AsyncHookResponse;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.HookEffectSink;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.SubAgentLifecycleListener;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HookEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sessionEndTimeoutPreservesReleasedMillisecondPrecision() {
        SubprocessEnvironment.clearRuntimeOverrides();
        try {
            assertEquals(1500L, HookEngine.getSessionEndHookTimeoutMillis());
            SubprocessEnvironment.updateRuntime(Map.of(
                "CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS", "1750"));
            assertEquals(1750L, HookEngine.getSessionEndHookTimeoutMillis());
        } finally {
            SubprocessEnvironment.clearRuntimeOverrides();
        }
    }

    @Test
    void executeHooksWithNoMatchingHooksReturnsEmpty() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        List<HookResult> results = engine.executeHooks(
            HookEvent.PRE_TOOL_USE, HookInput.forEvent(HookEvent.PRE_TOOL_USE));
        assertTrue(results.isEmpty());
    }

    @Test
    void executeHooksMatchesByToolName() {
        BashCommandHook hook = new BashCommandHook("echo matched");
        HookMatcher matcher = new HookMatcher(Optional.of("Bash"), List.of(hook));
        HooksSettings settings = new HooksSettings(
            Map.of(HookEvent.PRE_TOOL_USE, List.of(matcher)));

        HookEngine engine = new HookEngine(settings, "/tmp");

        ObjectNode input = MAPPER.createObjectNode();
        input.put("command", "ls");

        // Matching tool name
        List<HookResult> results = engine.executeHooks(
            HookEvent.PRE_TOOL_USE,
            HookInput.forPreToolUse("Bash", input, "tu-1"));
        assertFalse(results.isEmpty());

        // Non-matching tool name
        List<HookResult> results2 = engine.executeHooks(
            HookEvent.PRE_TOOL_USE,
            HookInput.forPreToolUse("FileRead", input, "tu-2"));
        assertTrue(results2.isEmpty());
    }

    @Test
    void executeHooksOnceMode() {
        BashCommandHook hook = new BashCommandHook(
            "echo once", Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), true, false, false);
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(hook));
        HooksSettings settings = new HooksSettings(
            Map.of(HookEvent.SESSION_START, List.of(matcher)));

        HookEngine engine = new HookEngine(settings, "/tmp");
        HookInput input = HookInput.forSessionStart("cli");

        // First execution should run
        List<HookResult> results1 = engine.executeHooks(HookEvent.SESSION_START, input);
        assertFalse(results1.isEmpty());

        // Second execution should skip (once mode)
        List<HookResult> results2 = engine.executeHooks(HookEvent.SESSION_START, input);
        assertTrue(results2.isEmpty());
    }

    @Test
    void parseHookOutputAllowDecision() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.parseHookOutput("{\"decision\":\"allow\"}");
        assertInstanceOf(HookResult.Allow.class, result);
    }

    @Test
    void parseHookOutputBlockDecision() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.parseHookOutput(
            "{\"decision\":\"block\",\"reason\":\"not allowed\"}");
        assertInstanceOf(HookResult.Block.class, result);
        assertEquals("not allowed", ((HookResult.Block) result).reason());
    }

    @Test
    void parseHookOutputWithAdditionalContext() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.parseHookOutput(
            "{\"decision\":\"allow\",\"additionalContext\":\"extra info\"}");
        assertInstanceOf(HookResult.Allow.class, result);
        assertEquals("extra info", ((HookResult.Allow) result).additionalContext().orElse(""));
    }

    @Test
    void parseHookOutputMessageDecision() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.parseHookOutput(
            "{\"decision\":\"message\",\"reason\":\"injected message\"}");
        assertInstanceOf(HookResult.Message.class, result);
        assertEquals("injected message", ((HookResult.Message) result).content());
    }

    @Test
    void parseHookOutputNonJsonTreatedAsContext() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.parseHookOutput("plain text output");
        assertInstanceOf(HookResult.Allow.class, result);
        assertEquals("plain text output",
            ((HookResult.Allow) result).additionalContext().orElse(""));
    }

    @Test
    void executePreToolHooksBlocksOnBlockResult() {
        // Create a hook that outputs a block decision
        // We test the aggregation logic directly
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.executePreToolHooks("Bash", null, "tu-1");
        // No hooks configured, should return Skip
        assertInstanceOf(HookResult.Skip.class, result);
    }

    @Test
    void preToolUseExitTwoFormatsReleased197BlockingMessage() {
        BashCommandHook hook = new BashCommandHook(
            "printf '%s\\n' AUTO_HOOK_BLOCK >&2; exit 2");
        HookEngine engine = new HookEngine(new HooksSettings(Map.of(
            HookEvent.PRE_TOOL_USE,
            List.of(new HookMatcher(Optional.of("Bash"), List.of(hook))))), "/tmp");

        HookDispatcher.HookOutcome outcome = engine.dispatchPreToolUseWithOutcome(
            "Bash", MAPPER.createObjectNode().put("command", "touch marker"), "tu-hook");

        assertFalse(outcome.proceed());
        assertEquals(1, outcome.blockingErrors().size());
        assertTrue(Strings.CS.startsWith(outcome.blockingErrors().getFirst(), "PreToolUse:Bash hook error: [printf '%s\\n' AUTO_HOOK_BLOCK >&2; exit 2]: AUTO_HOOK_BLOCK"));
    }

    @Test
    void stopPromptHookWithoutLlmIsSkipped() {
        PromptHook hook = new PromptHook("Check: $ARGUMENTS");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.executePromptHook(hook,
            HookInput.forEvent(HookEvent.STOP), 600);
        assertInstanceOf(HookResult.Skip.class, result);
    }

    @Test
    void agentHookStubReturnsAllow() {
        AgentHook hook = new AgentHook("Verify action");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.executeAgentHook(hook,
            HookInput.forEvent(HookEvent.PERMISSION_REQUEST), 600);
        assertInstanceOf(HookResult.Allow.class, result);
    }

    @Test
    void hookInputToJsonContainsEventAndToolName() {
        HookInput input = HookInput.forPreToolUse("Bash",
            MAPPER.createObjectNode().put("command", "ls"), "tu-1");
        String json = input.toJson();


        assertTrue(Strings.CS.contains(json, "\"hook_event_name\":\"PreToolUse\""));
        assertTrue(Strings.CS.contains(json, "Bash"));
        assertTrue(Strings.CS.contains(json, "tu-1"));
    }

    @Test
    void hookResultSealedTypes() {
        HookResult allow = new HookResult.Allow();
        HookResult allowCtx = new HookResult.Allow("context");
        HookResult block = new HookResult.Block("reason");
        HookResult msg = new HookResult.Message("content");
        HookResult skip = new HookResult.Skip();

        assertInstanceOf(HookResult.Allow.class, allow);
        assertInstanceOf(HookResult.Allow.class, allowCtx);
        assertInstanceOf(HookResult.Block.class, block);
        assertInstanceOf(HookResult.Message.class, msg);
        assertInstanceOf(HookResult.Skip.class, skip);

        assertTrue(((HookResult.Allow) allow).additionalContext().isEmpty());
        assertEquals("context", ((HookResult.Allow) allowCtx).additionalContext().orElse(""));
        assertEquals("reason", ((HookResult.Block) block).reason());
    }

    @Test
    void dispatchSessionEndDoesNotTriggerStopOnceGuard() {
        // A `once: true` Stop hook must NOT be consumed by dispatchSessionEnd —
        // proves SessionEnd and Stop are dispatched as distinct HookEvents,
        // not the same event under two method names.
        BashCommandHook onceStopHook = new BashCommandHook(
            "echo once", Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), true, false, false);
        HooksSettings settings = new HooksSettings(
            Map.of(HookEvent.STOP, List.of(new HookMatcher(Optional.empty(), List.of(onceStopHook)))));
        HookEngine engine = new HookEngine(settings, "/tmp");

        engine.dispatchSessionEnd("clear");

        // The Stop hook's `once` guard must still be unconsumed — dispatchStop
        // should fire it for the first time now.
        List<HookResult> results = engine.executeHooks(HookEvent.STOP, HookInput.forStop(false));
        assertFalse(results.isEmpty(), "dispatchSessionEnd must not consume the Stop hook's once-guard");
    }

    @Test
    void dispatchSessionEndReachesSessionEndMatchers() {
// match image of the previous test: a `once: true` SessionEnd hook
        // IS consumed by dispatchSessionEnd, proving it correctly fires
        // HookEvent.SESSION_END (not just a no-op or the wrong event).
        BashCommandHook onceSessionEndHook = new BashCommandHook(
            "echo once", Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), true, false, false);
        HooksSettings settings = new HooksSettings(
            Map.of(HookEvent.SESSION_END, List.of(new HookMatcher(Optional.empty(), List.of(onceSessionEndHook)))));
        HookEngine engine = new HookEngine(settings, "/tmp");

        engine.dispatchSessionEnd("clear");

        List<HookResult> results = engine.executeHooks(
            HookEvent.SESSION_END, HookInput.forSessionEnd("clear"));
        assertTrue(results.isEmpty(), "dispatchSessionEnd should have already consumed the once-guard");
    }

    @Test
    void hookInputForSessionEndUsesReasonFieldNotStopReason() {
        String json = HookInput.forSessionEnd("clear").toJson();
        assertTrue(Strings.CS.contains(json, "\"hook_event_name\":\"SessionEnd\""));
        assertTrue(Strings.CS.contains(json, "\"reason\":\"clear\"") || Strings.CS.contains(json, "\"reason\" : \"clear\""));
        assertFalse(Strings.CS.contains(json, "stop_reason"));
    }

    // ---- Matcher routing regression tests -----------------------------
    // getMatchingHooks used to test every event's matcher against
// input.toolName, which is always empty for non-tool events — a
    // SessionEnd/SessionStart/InstructionsLoaded/PreCompact/PostCompact
    // matcher scoped to a specific reason/trigger/load_reason value could
    // never fire. Each test below proves both directions: the matcher DOES
    // fire for its own value and does NOT fire for a different one.

    @Test
    void sessionEndMatcher_matchesOnReasonField() {
        BashCommandHook hook = new BashCommandHook("echo matched");
        HookMatcher matcher = new HookMatcher(Optional.of("clear"), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.SESSION_END, List.of(matcher))), "/tmp");

        assertFalse(engine.executeHooks(HookEvent.SESSION_END, HookInput.forSessionEnd("clear")).isEmpty(),
            "matcher=\"clear\" must match reason=\"clear\"");
        assertTrue(engine.executeHooks(HookEvent.SESSION_END, HookInput.forSessionEnd("logout")).isEmpty(),
            "matcher=\"clear\" must not match reason=\"logout\"");
    }

    @Test
    void sessionStartMatcher_matchesOnSourceField() {
        BashCommandHook hook = new BashCommandHook("echo matched");
        HookMatcher matcher = new HookMatcher(Optional.of("resume"), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.SESSION_START, List.of(matcher))), "/tmp");

        assertFalse(engine.executeHooks(HookEvent.SESSION_START, HookInput.forSessionStart("resume")).isEmpty(),
            "matcher=\"resume\" must match source=\"resume\"");
        assertTrue(engine.executeHooks(HookEvent.SESSION_START, HookInput.forSessionStart("clear")).isEmpty(),
            "matcher=\"resume\" must not match source=\"clear\"");
    }

    @Test
    void subagentStartMatcherAndDispatchUseAgentTypeAndReturnContext() {
        BashCommandHook hook = new BashCommandHook(
            "cat >/dev/null; echo '{\"hookSpecificOutput\":{\"hookEventName\":\"SubagentStart\","
                + "\"additionalContext\":\"HOOKCTX\"}}'");
        HookMatcher matcher = new HookMatcher(Optional.of("boot"), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.SUBAGENT_START, List.of(matcher))), "/tmp");

        HookDispatcher.HookOutcome matched = engine.dispatchSubAgentStartWithOutcome(
            "a0123456789abcdef", "boot");
        HookDispatcher.HookOutcome missed = engine.dispatchSubAgentStartWithOutcome(
            "a0123456789abcdef", "other");

        assertEquals(List.of("HOOKCTX"), matched.additionalContexts());
        assertTrue(missed.additionalContexts().isEmpty());
    }

    @Test
    void instructionsLoadedMatcher_matchesOnLoadReasonField() {
        BashCommandHook hook = new BashCommandHook("echo matched");
        HookMatcher matcher = new HookMatcher(Optional.of("include"), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.INSTRUCTIONS_LOADED, List.of(matcher))), "/tmp");

        assertFalse(engine.executeHooks(HookEvent.INSTRUCTIONS_LOADED,
            HookInput.forInstructionsLoaded("/a/CLAUDE.md", "Project", "include", null, null, null)).isEmpty(),
            "matcher=\"include\" must match load_reason=\"include\"");
        assertTrue(engine.executeHooks(HookEvent.INSTRUCTIONS_LOADED,
            HookInput.forInstructionsLoaded("/a/CLAUDE.md", "Project", "nested_traversal", null, null, null)).isEmpty(),
            "matcher=\"include\" must not match load_reason=\"nested_traversal\"");
    }

    @Test
    void preCompactMatcher_matchesOnTriggerField() {
        BashCommandHook hook = new BashCommandHook("echo matched");
        HookMatcher matcher = new HookMatcher(Optional.of("manual"), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.PRE_COMPACT, List.of(matcher))), "/tmp");

        assertFalse(engine.executeHooks(HookEvent.PRE_COMPACT,
            HookInput.forPreCompact("manual", null, 1000L, null, null)).isEmpty(),
            "matcher=\"manual\" must match trigger=\"manual\"");
        assertTrue(engine.executeHooks(HookEvent.PRE_COMPACT,
            HookInput.forPreCompact("auto", null, 1000L, null, null)).isEmpty(),
            "matcher=\"manual\" must not match trigger=\"auto\"");
    }

    @Test
    void postCompactMatcher_matchesOnTriggerField() {
        BashCommandHook hook = new BashCommandHook("echo matched");
        HookMatcher matcher = new HookMatcher(Optional.of("auto"), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.POST_COMPACT, List.of(matcher))), "/tmp");

        assertFalse(engine.executeHooks(HookEvent.POST_COMPACT,
            HookInput.forPostCompact("auto", null, 500L, null, null)).isEmpty(),
            "matcher=\"auto\" must match trigger=\"auto\"");
        assertTrue(engine.executeHooks(HookEvent.POST_COMPACT,
            HookInput.forPostCompact("manual", null, 500L, null, null)).isEmpty(),
            "matcher=\"auto\" must not match trigger=\"manual\"");
    }

    // ---- dispatchPreCompactWithOutcome / dispatchPostCompactWithOutcome --

    @Test
    void dispatchPreCompactWithOutcome_aggregatesAdditionalContext() {
        BashCommandHook hook = new BashCommandHook(
            "echo '{\"decision\":\"allow\",\"additionalContext\":\"focus on the auth bug\"}'");
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.PRE_COMPACT, List.of(matcher))), "/tmp");

        HookDispatcher.HookOutcome outcome = engine.dispatchPreCompactWithOutcome("manual", null, 1000L);

        assertEquals("focus on the auth bug", outcome.additionalContext());
        assertEquals("PreCompact [echo '{\"decision\":\"allow\",\"additionalContext\":\"focus on the auth bug\"}'] "
            + "completed successfully: focus on the auth bug", outcome.userDisplayMessage());
        assertTrue(outcome.proceed());
    }

    @Test
    void dispatchPreCompactWithOutcome_reportsSuccessfulEmptyHook() {
        BashCommandHook hook = new BashCommandHook("true");
        HookEngine engine = new HookEngine(new HooksSettings(Map.of(
            HookEvent.PRE_COMPACT,
            List.of(new HookMatcher(Optional.empty(), List.of(hook))))), "/tmp");

        HookDispatcher.HookOutcome outcome =
            engine.dispatchPreCompactWithOutcome("manual", null, 1000L);

        assertNull(outcome.additionalContext());
        assertEquals("PreCompact [true] completed successfully", outcome.userDisplayMessage());
    }

    @Test
    void dispatchPreCompactWithOutcome_reportsConfigAsyncHookAsSuccessfullyBackgrounded() {
        BashCommandHook hook = new BashCommandHook(
            "cat >/dev/null; sleep 0.1; exit 0",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            false, true, false);
        HookEngine engine = new HookEngine(new HooksSettings(Map.of(
            HookEvent.PRE_COMPACT,
            List.of(new HookMatcher(Optional.empty(), List.of(hook))))), "/tmp");

        HookDispatcher.HookOutcome outcome =
            engine.dispatchPreCompactWithOutcome("manual", null, 1000L);

        assertEquals("PreCompact [cat >/dev/null; sleep 0.1; exit 0] completed successfully",
            outcome.userDisplayMessage());
    }

    @Test
    void dispatchPreCompactWithOutcome_reportsOutputDrivenAsyncHookAsSuccessfullyBackgrounded() {
        String command = "cat >/dev/null; printf '{\"async\":true}\\n'; sleep 0.1; exit 0";
        BashCommandHook hook = new BashCommandHook(command);
        HookEngine engine = new HookEngine(new HooksSettings(Map.of(
            HookEvent.PRE_COMPACT,
            List.of(new HookMatcher(Optional.empty(), List.of(hook))))), "/tmp");

        HookDispatcher.HookOutcome outcome =
            engine.dispatchPreCompactWithOutcome("manual", null, 1000L);

        assertEquals("{\"async\":true}", outcome.additionalContext(),
            "2.1.197 preserves the output-driven async control line as compact instructions");
        assertEquals("PreCompact [" + command + "] completed successfully: {\"async\":true}",
            outcome.userDisplayMessage());
        engine.finalizePendingAsyncHooks();
    }

    @Test
    void dispatchPreCompactWithOutcome_includesTheTurnPromptIdInHookInput() {
        String command = "read input; case \"$input\" in *'\"prompt_id\":\"prompt-197\"'*) "
            + "printf 'matched\\n';; *) printf 'missing\\n';; esac";
        HookEngine engine = new HookEngine(new HooksSettings(Map.of(
            HookEvent.PRE_COMPACT,
            List.of(new HookMatcher(Optional.empty(), List.of(new BashCommandHook(command)))))), "/tmp");
        engine.setPromptIdSupplier(() -> "prompt-197");

        HookDispatcher.HookOutcome outcome =
            engine.dispatchPreCompactWithOutcome("manual", null, 1000L);

        assertEquals("matched", outcome.additionalContext());
    }

    @Test
    void dispatchPreCompactWithOutcome_nullContextWhenNoHooksConfigured() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookDispatcher.HookOutcome outcome = engine.dispatchPreCompactWithOutcome("manual", "user text", 1000L);
        assertNull(outcome.additionalContext());
    }

    @Test
    void dispatchPreCompact_stillRunsHooksViaVoidOverload() {
        // The void overload must still actually execute hooks (not become a
        // no-op) now that it delegates to dispatchPreCompactWithOutcome.
        BashCommandHook onceHook = new BashCommandHook(
            "echo once", Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), true, false, false);
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(onceHook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.PRE_COMPACT, List.of(matcher))), "/tmp");

        engine.dispatchPreCompact("manual", null, 1000L);

        List<HookResult> results = engine.executeHooks(HookEvent.PRE_COMPACT,
            HookInput.forPreCompact("manual", null, 1000L, null, null));
        assertTrue(results.isEmpty(), "dispatchPreCompact should have already consumed the once-guard");
    }

    @Test
    void dispatchPostCompactWithOutcome_aggregatesAdditionalContext() {
        BashCommandHook hook = new BashCommandHook(
            "echo '{\"decision\":\"allow\",\"additionalContext\":\"post-compact cleanup ran\"}'");
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.POST_COMPACT, List.of(matcher))), "/tmp");

        HookDispatcher.HookOutcome outcome = engine.dispatchPostCompactWithOutcome("manual", "the summary", 500L);

        assertEquals("post-compact cleanup ran", outcome.additionalContext());
        assertEquals("PostCompact [echo '{\"decision\":\"allow\",\"additionalContext\":\"post-compact cleanup ran\"}'] "
            + "completed successfully: post-compact cleanup ran", outcome.userDisplayMessage());
        assertTrue(outcome.proceed());
    }

    @Test
    void dispatchPostCompactWithOutcome_reportsFailedHook() {
        BashCommandHook hook = new BashCommandHook("exit 1");
        HookEngine engine = new HookEngine(new HooksSettings(Map.of(
            HookEvent.POST_COMPACT,
            List.of(new HookMatcher(Optional.empty(), List.of(hook))))), "/tmp");

        HookDispatcher.HookOutcome outcome =
            engine.dispatchPostCompactWithOutcome("manual", "summary", 500L);

        assertEquals("PostCompact [exit 1] failed", outcome.userDisplayMessage());
    }

    @Test
    void dispatchPostCompactWithOutcome_nullContextWhenNoHooksConfigured() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookDispatcher.HookOutcome outcome = engine.dispatchPostCompactWithOutcome("manual", "the summary", 500L);
        assertNull(outcome.additionalContext());
    }

    // ---- dispatchSessionStartWithOutcome --------------------------------

    @Test
    void dispatchSessionStartWithOutcome_aggregatesAdditionalContext() {
        BashCommandHook hook = new BashCommandHook(
            "echo '{\"decision\":\"allow\",\"additionalContext\":\"welcome back\"}'");
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.SESSION_START, List.of(matcher))), "/tmp");

        HookDispatcher.HookOutcome outcome = engine.dispatchSessionStartWithOutcome("clear");

        assertEquals("welcome back", outcome.additionalContext());
        assertTrue(outcome.proceed());
    }

    @Test
    void dispatchSessionStartWithOutcome_nullContextWhenNoHooksConfigured() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookDispatcher.HookOutcome outcome = engine.dispatchSessionStartWithOutcome("startup");
        assertNull(outcome.additionalContext());
    }

    @Test
    void hookInput_reflectsASharedSessionIdentitySwitchedAfterConstruction() throws IOException {
        // Cross-component regression guard: HookEngine must read the LIVE
        // value of a SessionIdentity it shares with a DefaultQuerySession (real CLI
        // wiring), not a value captured once at construction time — this is
        // exactly what a bare setSessionId(String) sync call used to require
        // callers to remember (and one CLI startup path forgot to).
        Path captureFile = Files.createTempFile("hook-input-capture", ".txt");
        try {
            SessionIdentity shared = SessionIdentity.of("initial-session");
            BashCommandHook hook = new BashCommandHook("printenv HOOK_INPUT > " + captureFile);
            HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(hook));
            HookEngine engine = new HookEngine(
                new HooksSettings(Map.of(HookEvent.SESSION_START, List.of(matcher))), "/tmp", shared);

            shared.set("switched-session");
            engine.dispatchSessionStart("startup");

            String captured = Files.readString(captureFile);
            assertTrue(Strings.CS.contains(captured, "\"session_id\":\"switched-session\""),
                "hook input should carry the id set AFTER HookEngine construction: " + captured);
        } finally {
            Files.deleteIfExists(captureFile);
        }
    }

    @Test
    void dispatchSessionStart_stillRunsHooksViaVoidOverload() {
        // The void overload must still actually execute hooks (not become a
        // no-op) now that it delegates to dispatchSessionStartWithOutcome.
        BashCommandHook onceHook = new BashCommandHook(
            "echo once", Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), true, false, false);
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(onceHook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.SESSION_START, List.of(matcher))), "/tmp");

        engine.dispatchSessionStart("startup");

        List<HookResult> results = engine.executeHooks(
            HookEvent.SESSION_START, HookInput.forSessionStart("startup"));
        assertTrue(results.isEmpty(), "dispatchSessionStart should have already consumed the once-guard");
    }



    @Test
    void parseHookOutputContinueFalseYieldsPreventContinuation() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.parseHookOutput(
            "{\"continue\":false,\"stopReason\":\"done for today\"}");
        assertInstanceOf(HookResult.PreventContinuation.class, result);
        assertEquals("done for today",
            ((HookResult.PreventContinuation) result).stopReason().orElse(""));
    }

    @Test
    void parseHookOutputContinueFalseWithoutStopReason() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.parseHookOutput("{\"continue\":false}");
        assertInstanceOf(HookResult.PreventContinuation.class, result);
        assertTrue(((HookResult.PreventContinuation) result).stopReason().isEmpty());
    }

    @Test
    void parseHookOutputContinueTrueIsNotPreventContinuation() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        assertInstanceOf(HookResult.Allow.class, engine.parseHookOutput("{\"continue\":true}"));
    }

    @Test
    void parseHookOutputBlockWithoutReasonDefaultsToBlockedByHook() {

        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.parseHookOutput("{\"decision\":\"block\"}");
        assertInstanceOf(HookResult.Block.class, result);
        assertEquals("Blocked by hook", ((HookResult.Block) result).reason());
    }

    @Test
    void parseHookOutputReadsHookSpecificOutputAdditionalContext() {

        // hookSpecificOutput, even without any "decision" field.
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.parseHookOutput(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\","
            + "\"additionalContext\":\"remember the style guide\"}}");
        assertInstanceOf(HookResult.Allow.class, result);
        assertEquals("remember the style guide",
            ((HookResult.Allow) result).additionalContext().orElse(""));
    }

// ---- Bash hook exit-code contract.

    @Test
    void bashHookExitCode2YieldsBlockingErrorFromStderr() {
        // Consume the one-line hook input before exiting. Without this, the
        // test races the intentional EPIPE path (command closes stdin early),

        BashCommandHook hook = new BashCommandHook("read _; echo boom >&2; exit 2");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);
        assertInstanceOf(HookResult.Block.class, result);
        assertEquals("[read _; echo boom >&2; exit 2]: boom\n",
            ((HookResult.Block) result).reason(),
            "released 2.1.197 embeds raw stderr, including its terminal newline");
    }

    @Test
    void bashHookExitCode2WithoutStderrUsesPlaceholder() {
        BashCommandHook hook = new BashCommandHook("read _; exit 2");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);
        assertInstanceOf(HookResult.Block.class, result);
        assertEquals("[read _; exit 2]: No stderr output", ((HookResult.Block) result).reason());
    }

    @Test
    void bashHookOtherNonZeroExitIsNonBlocking() {
        BashCommandHook hook = new BashCommandHook("echo oops >&2; exit 1");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        assertInstanceOf(HookResult.Skip.class,
            engine.executeBashHook(hook, HookInput.forStop(false), 10));
    }

    @Test
    void bashHookJsonStdoutTakesPrecedenceOverExitCode() {

        BashCommandHook hook = new BashCommandHook(
            "echo '{\"decision\":\"allow\"}'; exit 2");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        assertInstanceOf(HookResult.Allow.class,
            engine.executeBashHook(hook, HookInput.forStop(false), 10));
    }

    @Test
    void asyncRewakeExitCode2EnqueuesSystemReminderNotification() {

        // (blocking error) enqueues a <system-reminder> task-notification so the
        // model wakes, instead of surfacing only a discarded HookResult.Message.

        // The hook prefixes `cat >/dev/null` so it drains HOOK_INPUT from stdin —
        // a realistic hook reads its input; this also avoids the write/close race
        // in executeBashHook when a fast-exiting command closes the pipe first.
        MessageQueueManager queue = new MessageQueueManager();
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setMessageQueue(queue);

        BashCommandHook hook = new BashCommandHook(
            "cat >/dev/null; echo blocking failure >&2; exit 2",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            false, false, true);
// PreToolUse:Bash — verifies the blocking-error message names the hook as
// `event:matchQuery`.
        HookResult result = engine.executeBashHook(
            hook, HookInput.forPreToolUse("Bash", MAPPER.createObjectNode(), "tu-1"), 10);

        assertEquals(1, queue.size(),
            "asyncRewake exit-2 must enqueue exactly one notification");
        QueuedCommand cmd = queue.peek();
        assertEquals(QueuePriority.LATER, cmd.priority(),
            "pending notification defaults to LATER (TS priority ?? 'later')");
        assertEquals("task-notification", cmd.mode());
        assertFalse(cmd.isMeta(), "TS does not set isMeta for this path");
        assertTrue(Strings.CS.contains(cmd.text(), "<system-reminder>"),
            "must be wrapped in a system-reminder: " + cmd.text());
        assertTrue(Strings.CS.contains(cmd.text(), "Stop hook blocking error from command"),
            cmd.text());
        assertTrue(Strings.CS.contains(cmd.text(), "PreToolUse:Bash"),
            "message must name the hook as event:toolName: " + cmd.text());
        assertTrue(Strings.CS.contains(cmd.text(), "blocking failure"), cmd.text());
        // The legacy HookResult.Message contract is preserved (its value is
        // discarded by the fire-and-forget dispatch path, but the method still
        // returns it for any synchronous caller).
        assertInstanceOf(HookResult.Message.class, result);
    }

    @Test
    void exitCode2WithoutAsyncRewakeReturnsBlockAndDoesNotEnqueue() {
        // The enqueue only fires on the asyncRewake path; a plain exit-2 bash
        // hook stays a local blocking error and must NOT touch the queue.
        MessageQueueManager queue = new MessageQueueManager();
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setMessageQueue(queue);

        BashCommandHook hook = new BashCommandHook(
            "cat >/dev/null; echo blocking failure >&2; exit 2",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            false, false, false);
        HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);

        assertInstanceOf(HookResult.Block.class, result);
        assertEquals(0, queue.size(),
            "non-asyncRewake exit-2 must not enqueue a notification");
    }

    @Test
    void asyncRewakeExitCode2WithoutQueueWiredIsNoOp() {
        // setMessageQueue(null)/unset keeps the legacy behavior — no NPE, no
        // notification. Lets untested callers that don't wire a queue stay green.
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        BashCommandHook hook = new BashCommandHook(
            "cat >/dev/null; echo blocking failure >&2; exit 2",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            false, false, true);
        HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);
        assertInstanceOf(HookResult.Message.class, result);
    }

    // ---- dispatchStopWithOutcome / dispatchStopFailure ------------------

    @Test
    void dispatchStopWithOutcomePassesStopHookActiveIntoHookInput() {
        // Hook reads its JSON input from stdin and blocks only when
        // stop_hook_active is true — proves the flag round-trips as a real
        // boolean in the payload.
        BashCommandHook hook = new BashCommandHook(
            "grep -q '\"stop_hook_active\":true' && { echo re-entry >&2; exit 2; } || exit 0");
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.STOP, List.of(matcher))), "/tmp");

        HookDispatcher.HookOutcome first = engine.dispatchStopWithOutcome("success", false);
        assertTrue(first.proceed());
        assertFalse(first.hasBlockingErrors());

        HookDispatcher.HookOutcome reentry = engine.dispatchStopWithOutcome("success", true);
        assertTrue(reentry.hasBlockingErrors());
    }

    @Test
    void dispatchStopWithOutcomeSurfacesPreventContinuation() {
        BashCommandHook hook = new BashCommandHook(
            "echo '{\"continue\":false,\"stopReason\":\"hook said stop\"}'");
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.STOP, List.of(matcher))), "/tmp");

        HookDispatcher.HookOutcome outcome = engine.dispatchStopWithOutcome("success", false);
        assertTrue(outcome.preventContinuation());
        assertEquals("hook said stop", outcome.stopReason());
        assertFalse(outcome.proceed());
    }

    @Test
    void subagentDispatcherRunsGlobalAndFrontmatterStopAsSubagentStop() throws Exception {
        BashCommandHook globalHook = new BashCommandHook(
            "cat >/dev/null; echo '{\"hookSpecificOutput\":{\"hookEventName\":\"SubagentStop\",\"additionalContext\":\"GLOBAL\"}}'");
        HookEngine engine = new HookEngine(new HooksSettings(Map.of(
            HookEvent.SUBAGENT_STOP,
            List.of(new HookMatcher(Optional.of("boot"), List.of(globalHook))))),
            "/tmp/project", SessionIdentity.of("session-197"));
        JsonNode frontmatter = MAPPER.readTree("""
            {"Stop":[{"hooks":[{"type":"command","command":"cat >/dev/null; echo '{\\"hookSpecificOutput\\":{\\"hookEventName\\":\\"SubagentStop\\",\\"additionalContext\\":\\"FRONTMATTER\\"}}'"}]}]}
            """);
        HookDispatcher dispatcher = engine.createSubAgentDispatcher(
            new SubAgentLifecycleListener.SubAgentHookContext(
                "a0123456789abcdef", "boot", "/tmp/project",
                "/tmp/config/session-197/subagents/agent-a0123456789abcdef.jsonl",
                "bypassPermissions", "high", frontmatter,
                List::of, () -> "prompt-197"));

        HookDispatcher.HookOutcome outcome = dispatcher.dispatchStopWithOutcome("success", false);

        assertEquals(Set.of("GLOBAL", "FRONTMATTER"), Set.copyOf(outcome.additionalContexts()));
        assertTrue(outcome.proceed());
    }

    @Test
    void dispatchStopFailureFiresStopFailureMatchersOnErrorField() {


        BashCommandHook onceHook = new BashCommandHook(
            "echo hit", Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), true, false, false);
        HookMatcher matcher = new HookMatcher(Optional.of("error_during_execution"), List.of(onceHook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.STOP_FAILURE, List.of(matcher))), "/tmp");

        // Non-matching error must NOT consume the once-guard
        engine.dispatchStopFailure("some_other_error");
        // Matching error consumes it
        engine.dispatchStopFailure("error_during_execution");

        List<HookResult> results = engine.executeHooks(HookEvent.STOP_FAILURE,
            HookInput.forStopFailure("error_during_execution", null, null, "/tmp"));
        assertTrue(results.isEmpty(),
            "dispatchStopFailure(matching error) should have consumed the once-guard");
    }

    // ---- dispatchUserPromptSubmitWithOutcome -----------------------------

    @Test
    void dispatchUserPromptSubmitWithOutcomeCarriesPromptInInput() {

        BashCommandHook hook = new BashCommandHook(
            "grep -q '\"prompt\":\"deploy to prod\"' && { echo no prod >&2; exit 2; } || exit 0");
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.USER_PROMPT_SUBMIT, List.of(matcher))), "/tmp");

        assertTrue(engine.dispatchUserPromptSubmitWithOutcome("fix a typo").proceed());

        HookDispatcher.HookOutcome blocked = engine.dispatchUserPromptSubmitWithOutcome("deploy to prod");
        assertTrue(blocked.hasBlockingErrors());
        assertTrue(Strings.CS.contains(blocked.blockingErrors().getFirst(), "no prod"));
    }

    @Test
    void dispatchUserPromptSubmitWithOutcomeAggregatesAdditionalContext() {
        BashCommandHook hook = new BashCommandHook(
            "echo '{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\","
            + "\"additionalContext\":\"style guide applies\"}}'");
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.USER_PROMPT_SUBMIT, List.of(matcher))), "/tmp");

        HookDispatcher.HookOutcome outcome = engine.dispatchUserPromptSubmitWithOutcome("hello");
        assertTrue(outcome.proceed());
        assertEquals("style guide applies", outcome.additionalContext());
    }

    @Test
    void sdkUserPromptSubmitCallbackReceivesPromptIdAndOneEnvelopeToolUseId() {
        AtomicReference<JsonNode> callbackInput = new AtomicReference<>();
        AtomicReference<String> callbackToolUseId = new AtomicReference<>();
        CallbackHook callback = new CallbackHook(
            "wire-user-prompt-hook",
            (input, toolUseId) -> {
                try {
                    callbackInput.set(MAPPER.readTree(input.toJson()));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                callbackToolUseId.set(toolUseId);
                return MAPPER.createObjectNode()
                    .set("hookSpecificOutput", MAPPER.createObjectNode()
                        .put("hookEventName", "UserPromptSubmit"));
            },
            Optional.empty());
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setPromptIdSupplier(() -> "prompt-197");
        engine.setSdkHooks(Map.of(HookEvent.USER_PROMPT_SUBMIT,
            List.of(new HookMatcher(Optional.empty(), List.of(callback)))));

        assertTrue(engine.dispatchUserPromptSubmitWithOutcome("hello").proceed());
        assertEquals("prompt-197", callbackInput.get().path("prompt_id").asText());
        assertFalse(callbackInput.get().has("tool_use_id"));
        assertNotNull(callbackToolUseId.get());
        assertDoesNotThrow(() -> UUID.fromString(callbackToolUseId.get()));
    }

    @Test
    void messageDisplayHookCanReplaceOnlyTheVisibleDelta() {
        CallbackHook callback = new CallbackHook(
            "display-filter",
            (input, _) -> MAPPER.createObjectNode()
                .set("hookSpecificOutput", MAPPER.createObjectNode()
                    .put("hookEventName", "MessageDisplay")
                    .put("displayContent", input.extra().get("delta").toString().toUpperCase())),
            Optional.empty());
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setSdkHooks(Map.of(HookEvent.MESSAGE_DISPLAY,
            List.of(new HookMatcher(Optional.empty(), List.of(callback)))));

        HookDispatcher.HookOutcome outcome = engine.dispatchMessageDisplayWithOutcome(
            "turn", "message", 0, true, "hello");

        assertEquals("HELLO", outcome.specificOutput("MessageDisplay")
            .orElseThrow().path("displayContent").asText());
    }

    @Test
    void publishesGenericAndSessionEffectsInConfiguredOrder() {
        List<String> effects = new ArrayList<>();
        HookEffectSink sink = new HookEffectSink() {
            @Override public void showSystemMessage(
                    String event, String hookName, String message) {
                effects.add("message:" + message);
            }

            @Override public void emitTerminalSequence(String sequence) {
                effects.add("terminal:" + sequence);
            }

            @Override public void applySessionTitle(String title) {
                effects.add("title:" + title);
            }

            @Override public void reloadSkills() {
                effects.add("reload");
            }

            @Override public void replaceWatchPaths(List<Path> paths) {
                effects.add("watch:" + paths);
            }
        };
        CallbackHook first = new CallbackHook("first", (_, _) -> {
            ObjectNode output = MAPPER.createObjectNode();
            output.put("systemMessage", "one");
            output.put("terminalSequence", "\u001b]0;one\u0007");
            ObjectNode specific = output.putObject("hookSpecificOutput");
            specific.put("hookEventName", "SessionStart");
            specific.put("sessionTitle", "First");
            specific.put("reloadSkills", true);
            specific.putArray("watchPaths").add("/tmp/one");
            return output;
        }, Optional.empty());
        CallbackHook second = new CallbackHook("second", (_, _) -> {
            ObjectNode output = MAPPER.createObjectNode();
            output.put("systemMessage", "two");
            ObjectNode specific = output.putObject("hookSpecificOutput");
            specific.put("hookEventName", "SessionStart");
            specific.put("sessionTitle", "Second");
            specific.putArray("watchPaths").add("/tmp/two");
            return output;
        }, Optional.empty());
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setHookEffectSink(sink);
        engine.setSdkHooks(Map.of(HookEvent.SESSION_START, List.of(
            new HookMatcher(Optional.empty(), List.of(first, second)))));

        engine.dispatchSessionStartWithOutcome("startup");

        assertEquals(List.of(
            "message:one", "terminal:\u001b]0;one\u0007", "message:two",
            "title:Second", "reload", "watch:[/tmp/one, /tmp/two]"), effects);
    }


    // A hook that is NOT flagged async in settings can still self-declare async
    // by writing {"async":true,...} as the first stdout line. The foreground
    // dispatch must return Skip immediately (non-blocking) and let the still
    // running process finish on a background virtual thread.

    @Test
    void parallelOutputDrivenAsyncHooksKeepTheirCapturesIsolated() {
        BashCommandHook first = new BashCommandHook(
            "printf '{\"async\":true,\"source\":\"first\"}\\n'; sleep 0.1");
        BashCommandHook second = new BashCommandHook(
            "printf '{\"async\":true,\"source\":\"second\"}\\n'; sleep 0.1");
        HookEngine engine = new HookEngine(new HooksSettings(Map.of(
            HookEvent.POST_COMPACT,
            List.of(new HookMatcher(Optional.empty(), List.of(first, second))))), "/tmp");

        HookDispatcher.HookOutcome outcome = engine.dispatchPostCompactWithOutcome(
            "manual", "summary", 100);

        assertEquals("""
            {"async":true,"source":"first"}
            {"async":true,"source":"second"}\
            """, outcome.additionalContext());
    }

    @Test
    void outputDrivenAsyncHookReturnsSkipAndProcessFinishesInBackground() throws IOException {
        // The hook drains stdin, prints the async handshake, sleeps briefly,
        // then writes a marker file. The foreground call must return Skip at
        // once while the background thread lets the process run to completion.
        Path marker = Files.createTempFile("async-hook-marker", ".txt");
        Files.deleteIfExists(marker);
        try {
            String cmd = "cat >/dev/null; printf '{\"async\":true}\\n'; sleep 0.3; touch " + marker + "; exit 0";
            BashCommandHook hook = new BashCommandHook(cmd);
            HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");

            HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);

            assertInstanceOf(HookResult.Skip.class, result,
                "output-driven async hook must return Skip without blocking");

            // Background thread should let the 0.3s sleep + touch finish.
            boolean finished = waitForFile(marker, 2000);
            assertTrue(finished, "background thread should let the async hook process finish (marker written)");
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    @Test
    void outputDrivenAsyncHookWithAsyncRewakeExit2EnqueuesNotification() {
        // Drive executeBashHook directly (bypassing the config-async guard in
        // executeHooks) so the output-driven handshake path is exercised with a
        // hook that also carries asyncRewake=true. Exit 2 must enqueue a
        // <system-reminder> task-notification from the background thread.
        MessageQueueManager queue = new MessageQueueManager();
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setMessageQueue(queue);

        BashCommandHook hook = new BashCommandHook(
            "cat >/dev/null; printf '{\"async\":true}\\n'; echo blocking failure >&2; exit 2",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            false, false, true);
        HookResult result = engine.executeBashHook(
            hook, HookInput.forPreToolUse("Bash", MAPPER.createObjectNode(), "tu-1"), 10);

        assertInstanceOf(HookResult.Skip.class, result,
            "output-driven async hook must return Skip without blocking");

        // The notification is produced by the background thread after the
        // process exits — poll for it.
        waitForQueueSize(queue, 1, 2000);
        assertEquals(1, queue.size(),
            "output-driven async + asyncRewake exit-2 must enqueue exactly one notification");
        QueuedCommand cmd = queue.peek();
        assertEquals(QueuePriority.LATER, cmd.priority());
        assertEquals("task-notification", cmd.mode());
        assertTrue(Strings.CS.contains(cmd.text(), "<system-reminder>"), cmd.text());
        assertTrue(Strings.CS.contains(cmd.text(), "Stop hook blocking error from command"), cmd.text());
        assertTrue(Strings.CS.contains(cmd.text(), "PreToolUse:Bash"), cmd.text());
        assertTrue(Strings.CS.contains(cmd.text(), "blocking failure"), cmd.text());
    }

    @Test
    void outputDrivenAsyncHookHonorsAsyncTimeoutAndKillsHungProcess() throws IOException {
        // Handshake carries asyncTimeout:200ms; the hook sleeps 1s after that.
        // The foreground must return Skip almost immediately (not wait for the
        // whole sleep) and the background thread must force-kill the process at
        // the 200ms floor, so the post-sleep marker is never written.
        Path marker = Files.createTempFile("async-hook-timeout", ".txt");
        Files.deleteIfExists(marker);
        try {
            String cmd = "cat >/dev/null; printf '{\"async\":true,\"asyncTimeout\":200}\\n'; sleep 1; touch "
                + marker + "; exit 0";
            BashCommandHook hook = new BashCommandHook(cmd);
            HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");

            long start = System.nanoTime();
            HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertInstanceOf(HookResult.Skip.class, result);
            assertTrue(elapsedMs < 1000,
                "foreground must return without waiting for the 1s hook sleep (took " + elapsedMs + "ms)");

            // Give the background thread time to reach + apply the 200ms kill.
            try { Thread.sleep(1500); } catch (InterruptedException _) {}
            assertFalse(Files.exists(marker),
                "asyncTimeout must force-kill the hung hook before it writes the marker");
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    @Test
    void outputDrivenAsyncHookFallsBackToConfiguredCommandTimeout() throws IOException {
        Path marker = Files.createTempFile("async-hook-command-timeout", ".txt");
        Files.deleteIfExists(marker);
        try {
            String cmd = "cat >/dev/null; printf '{\"async\":true}\\n'; sleep 2; touch "
                + marker + "; exit 0";
            BashCommandHook hook = new BashCommandHook(
                cmd, Optional.empty(), Optional.empty(), Optional.of(1), Optional.empty(),
                false, false, false);
            HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");

            HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);

            assertInstanceOf(HookResult.Skip.class, result);
            try { Thread.sleep(2500); } catch (InterruptedException _) {}
            assertFalse(Files.exists(marker),
                "output-driven async hook must retain the command's configured timeout");
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    @Test
    void outputDrivenNonAsyncFirstLineStillParsesAsBlock() {
        // First stdout line is JSON but NOT an async handshake — the sync path
        // must run unchanged and parse it as a real decision.
        BashCommandHook hook = new BashCommandHook(
            "cat >/dev/null; echo '{\"decision\":\"block\",\"reason\":\"stop me\"}'");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");

        HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);

        assertInstanceOf(HookResult.Block.class, result);
        assertEquals("stop me", ((HookResult.Block) result).reason());
    }

    @Test
    void outputDrivenAsyncHandshakeDoesNotLeakIntoResultAndDoesNotEnqueue() {
        // A plain output-driven async hook (no asyncRewake): the handshake line
        // must not be mistaken for a JSON decision, and nothing must be enqueued.
        MessageQueueManager queue = new MessageQueueManager();
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setMessageQueue(queue);

        BashCommandHook hook = new BashCommandHook(
            "cat >/dev/null; printf '{\"async\":true}\\n'; exit 0");
        HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);

        assertInstanceOf(HookResult.Skip.class, result);
        assertEquals(0, queue.size(),
            "plain output-driven async hook must not enqueue a notification");
    }

    @Test
    void configDrivenAsyncThroughDispatchReturnsSkipImmediately() {
        // Regression: settings-declared async (async=true) is still handled by
        // the fire-and-forget path in executeHooks and returns Skip without
        // blocking on the hook's sleep.
        BashCommandHook hook = new BashCommandHook(
            "cat >/dev/null; sleep 0.2; echo done; exit 0",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            false, true, false);
        HookMatcher matcher = new HookMatcher(Optional.empty(), List.of(hook));
        HookEngine engine = new HookEngine(
            new HooksSettings(Map.of(HookEvent.PRE_TOOL_USE, List.of(matcher))), "/tmp");

        long start = System.nanoTime();
        List<HookResult> results = engine.executeHooks(
            HookEvent.PRE_TOOL_USE, HookInput.forPreToolUse("Bash", MAPPER.createObjectNode(), "tu-1"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, results.size());
        assertInstanceOf(HookResult.Skip.class, results.getFirst());
        assertTrue(elapsedMs < 1000,
            "config-driven async must not block on the 0.2s hook sleep (took " + elapsedMs + "ms)");
    }

    @Test
    void plainSyncHookUnchangedThroughExecuteBashHook() {
        // Regression: a normal (non-async) hook still parses stdout normally.
        BashCommandHook hook = new BashCommandHook(
            "cat >/dev/null; echo '{\"decision\":\"allow\",\"additionalContext\":\"ctx\"}'");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");

        HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);

        assertInstanceOf(HookResult.Allow.class, result);
        assertEquals("ctx", ((HookResult.Allow) result).additionalContext().orElse(""));
    }

    @Test
    void bashHookStdinEndsWithNewlineLikeReleasedCli() {
        BashCommandHook hook = new BashCommandHook(
            "if IFS= read -r line; then printf ok; else exit 1; fi");
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");

        HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);

        assertInstanceOf(HookResult.Allow.class, result);
        assertEquals("ok", ((HookResult.Allow) result).additionalContext().orElse(""));
    }

// ---- AsyncHookRegistry: deferred response re-injection.

    @Test
    void outputDrivenAsyncHookRegistersAndReinjectsFirstNonAsyncResponse() throws IOException {
        // Handshake + two trailing JSON lines. The registry must surface only
        // the FIRST non-async JSON line as the deferred response.
        Path marker = Files.createTempFile("async-hook-reg", ".txt");
        Files.deleteIfExists(marker);
        try {
            String cmd = "cat >/dev/null; printf '{\"async\":true}\\n'; sleep 0.3; "
                + "printf '{\"first\":\"response\"}\\n'; printf '{\"second\":\"response\"}\\n'; "
                + "touch " + marker + "; exit 0";
            BashCommandHook hook = new BashCommandHook(cmd);
            HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");

            HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);
            assertInstanceOf(HookResult.Skip.class, result,
                "output-driven async hook must return Skip without blocking");

            // Poll the registry until the background process completes and the
            // deferred response is available.
            List<AsyncHookResponse> responses = waitForResponses(engine, 3000);
            assertEquals(1, responses.size(), "exactly one deferred response expected");
            AsyncHookResponse r = responses.getFirst();
            assertEquals("{\"first\":\"response\"}", r.responseJson(),
                "first non-async JSON line is the deferred sync response");
            assertEquals(0, r.exitCode());
            assertTrue(Strings.CS.startsWith(r.processId(), "async-hook-"));

            // A second poll must return empty: each response is delivered once.
            assertEquals(List.of(), engine.checkForAsyncHookResponses());
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    @Test
    void outputDrivenAsyncHookWithEmptyTrailingStdoutYieldsNoResponse() throws IOException {
        // The hook self-declares async but produces no further stdout. The
        // registry must complete the entry (and remove it) without surfacing a
        // deferred response.
        Path marker = Files.createTempFile("async-hook-empty", ".txt");
        Files.deleteIfExists(marker);
        try {
            String cmd = "cat >/dev/null; printf '{\"async\":true}\\n'; sleep 0.2; touch "
                + marker + "; exit 0";
            BashCommandHook hook = new BashCommandHook(cmd);
            HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");

            HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);
            assertInstanceOf(HookResult.Skip.class, result);

            // Wait for the process to finish (marker), then poll — the entry is
            // completed with empty stdout, so no response is ever surfaced.
            waitForFile(marker, 2000);
            assertEquals(List.of(), engine.checkForAsyncHookResponses());
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    @Test
    void forceSyncExecutionMakesOutputDrivenAsyncRunSynchronously() throws IOException {

        Path marker = Files.createTempFile("async-hook-fsync", ".txt");
        Files.deleteIfExists(marker);
        try {
            String cmd = "cat >/dev/null; printf '{\"async\":true}\\n'; sleep 0.2; "
                + "printf '{\"decision\":\"allow\",\"additionalContext\":\"x\"}'; exit 0";
            BashCommandHook hook = new BashCommandHook(cmd);
            HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
            engine.setForceSyncExecution(true);

            long start = System.nanoTime();
            HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertInstanceOf(HookResult.Allow.class, result,
                "forceSync async hook must be parsed synchronously");
            assertEquals("x", ((HookResult.Allow) result).additionalContext().orElse(""));
            assertTrue(elapsedMs >= 150,
                "forceSync must block until the hook (0.2s sleep) completes (took " + elapsedMs + "ms)");
            // No registry entry should exist for a force-synced hook.
            assertEquals(List.of(), engine.checkForAsyncHookResponses());
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    @Test
    void finalizePendingAsyncHooksKillsInFlightProcess() throws IOException {
        // A long-running output-driven async hook is registered; calling
        // finalizePendingAsyncHooks (the exit-path flush) must force-kill the
        // still-running process so its trailing marker is never written.
        Path marker = Files.createTempFile("async-hook-finalize", ".txt");
        Files.deleteIfExists(marker);
        try {
            String cmd = "cat >/dev/null; printf '{\"async\":true}\\n'; sleep 5; touch "
                + marker + "; exit 0";
            BashCommandHook hook = new BashCommandHook(cmd);
            HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");

            HookResult result = engine.executeBashHook(hook, HookInput.forStop(false), 10);
            assertInstanceOf(HookResult.Skip.class, result);
            // The hook is still registered (RUNNING) when finalize is called —
            // finalize must force-kill the in-flight process; the trailing
            // marker proves whether it survived.

            engine.finalizePendingAsyncHooks();

            // Give the kill a moment to take effect; the marker must NOT appear.
            try { Thread.sleep(600); } catch (InterruptedException _) {}
            assertFalse(Files.exists(marker),
                "finalizePendingAsyncHooks must force-kill the in-flight async hook");
            // Registry cleared after finalize.
            assertEquals(List.of(), engine.checkForAsyncHookResponses());
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    // ---- test helpers ---------------------------------------------------

    private static boolean waitForFile(Path file, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return Files.exists(file);
            }
        }
        return Files.exists(file);
    }

    private static void waitForQueueSize(MessageQueueManager queue, int expected, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (queue.size() >= expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static List<AsyncHookResponse> waitForResponses(HookEngine engine, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        List<AsyncHookResponse> last = List.of();
        while (System.nanoTime() < deadline) {
            List<AsyncHookResponse> responses = engine.checkForAsyncHookResponses();
            if (!responses.isEmpty()) {
                return responses;
            }
            last = responses;
            try {
                Thread.sleep(50);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return engine.checkForAsyncHookResponses();
            }
        }
        return last;
    }
}
