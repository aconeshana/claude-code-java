package com.claudecode.runtime.query;

import com.claudecode.core.engine.*;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link DefaultQuerySession#startNewSession} — the engine-owned reset used by {@code
 * /clear}.
 */
class QueryEngineTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    private static DefaultQuerySession newEngine() {
        return new DefaultQuerySession(QuerySessionSpec.builder().llmClient(NOOP_CLIENT).build());
    }

    @Test
    void softInterruptSuppressesVisibleSentinelEvenWhenAnotherAbortReasonWon() {
        DefaultQuerySession engine = newEngine();

        engine.interrupt();
        engine.softInterrupt();

        assertEquals("user-cancel", engine.getAbortController().getReason());
        assertTrue(engine.isSoftInterruptRequested());

        engine.submitMessage("next turn", SubmitOptions.DEFAULT);
        assertFalse(engine.isSoftInterruptRequested());
        assertFalse(engine.getAbortController().isAborted());
    }

    @Test
    void contextUsageCountsContextManagementAndActionGuidanceSeparately() {
        DefaultQuerySession engine = newEngine();

        List<String> parts = engine.assembleSystemPromptParts(null);

        String context = parts.stream()
            .filter(part -> Strings.CS.startsWith(part, "# Context management\n"))
            .findFirst().orElseThrow();
        assertFalse(Strings.CS.contains(
            context, "When you have enough information to act"));
        assertTrue(parts.contains(
            "When you have enough information to act, act. Do not re-derive facts already "
            + "established in the conversation, re-litigate a decision the user has already "
            + "made, or narrate options you will not pursue. If you are weighing a choice, "
            + "give a recommendation, not an exhaustive survey"));
    }

    @Test
    void deferredSessionStartDoesNotBlockSetupButCompletesBeforeFirstSubmission() throws Exception {
        DefaultQuerySession engine = newEngine();
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        CountDownLatch setterReturned = new CountDownLatch(1);
        CountDownLatch submissionReturned = new CountDownLatch(1);
        HookDispatcher hooks = new HookDispatcher() {
            @Override public boolean dispatchPreToolUse(
                    String toolName, JsonNode input, String toolUseId) { return true; }
            @Override public void dispatchPostToolUse(
                    String toolName, JsonNode input, JsonNode output, String toolUseId) {}
            @Override public void dispatchUserPromptSubmit(String prompt) {}
            @Override public void dispatchSessionStart(String trigger) {}
            @Override public HookOutcome dispatchSessionStartWithOutcome(String trigger) {
                hookEntered.countDown();
                try {
                    releaseHook.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return new HookOutcome(true, "DEFERRED_START_CONTEXT", List.of());
            }
            @Override public void dispatchStop(String reason) {}
        };

        Thread.startVirtualThread(() -> {
            engine.setHookDispatcherDeferred(hooks);
            setterReturned.countDown();
        });

        try {
            assertTrue(hookEntered.await(2, TimeUnit.SECONDS));
            assertTrue(setterReturned.await(500, TimeUnit.MILLISECONDS),
                "installing hooks must not keep startup waiting for SessionStart");

            Thread.startVirtualThread(() -> {
                engine.submitMessage("hello", SubmitOptions.DEFAULT);
                submissionReturned.countDown();
            });
            assertFalse(submissionReturned.await(100, TimeUnit.MILLISECONDS),
                "the first submission must wait for startup hook context");
        } finally {
            releaseHook.countDown();
        }

        assertTrue(submissionReturned.await(2, TimeUnit.SECONDS));
        UserMessage reminder = assertInstanceOf(UserMessage.class, engine.getMessages().getFirst());
        assertTrue(Strings.CS.contains(reminder.message().text(), "DEFERRED_START_CONTEXT"));
    }

    @Test
    void sealedStartupReadinessIsIdempotentAndRejectsLateBarriers() {
        DefaultQuerySession engine = newEngine();
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        engine.addStartupBarrier(barrier);

        CompletionStage<Void> first = engine.sealStartupReadiness();
        CompletionStage<Void> second = engine.sealStartupReadiness();

        assertSame(first, second);
        assertFalse(first.toCompletableFuture().isDone());
        assertThrows(IllegalStateException.class,
            () -> engine.addStartupBarrier(CompletableFuture.completedFuture(null)));
        barrier.complete(null);
        first.toCompletableFuture().join();
    }

    @Test
    void sealingDoesNotHoldTheReadinessLockWhileWaiting() throws Exception {
        DefaultQuerySession engine = newEngine();
        CompletableFuture<Void> source = new CompletableFuture<>();
        CompletableFuture<Void> callbackEntered = new CompletableFuture<>();
        CompletionStage<Void> barrier = source.thenRun(() -> {
            callbackEntered.complete(null);
            assertThrows(IllegalStateException.class,
                () -> engine.addStartupBarrier(CompletableFuture.completedFuture(null)));
        });
        engine.addStartupBarrier(barrier);
        CompletionStage<Void> sealed = engine.sealStartupReadiness();

        source.complete(null);

        callbackEntered.get(1, TimeUnit.SECONDS);
        sealed.toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    void sealedReadinessCompletesOnlyAfterSessionStartOutcomeIsApplied() throws Exception {
        DefaultQuerySession engine = newEngine();
        CountDownLatch release = new CountDownLatch(1);
        HookDispatcher hooks = new HookDispatcher() {
            @Override public boolean dispatchPreToolUse(
                    String toolName, JsonNode input, String toolUseId) { return true; }
            @Override public void dispatchPostToolUse(
                    String toolName, JsonNode input, JsonNode output, String toolUseId) {}
            @Override public void dispatchUserPromptSubmit(String prompt) {}
            @Override public void dispatchSessionStart(String trigger) {}
            @Override public HookOutcome dispatchSessionStartWithOutcome(String trigger) {
                try {
                    release.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return new HookOutcome(true, "SEALED_CONTEXT", List.of());
            }
            @Override public void dispatchStop(String reason) {}
        };
        engine.setHookDispatcherDeferred(hooks);
        CompletionStage<Void> readiness = engine.sealStartupReadiness();
        assertFalse(readiness.toCompletableFuture().isDone());

        release.countDown();
        readiness.toCompletableFuture().get(1, TimeUnit.SECONDS);

        UserMessage reminder = assertInstanceOf(UserMessage.class, engine.getMessages().getFirst());
        assertTrue(Strings.CS.contains(reminder.message().text(), "SEALED_CONTEXT"));
    }

    @Test
    void promptCommandModelAndEffortOverridesAreTurnScoped() {
        AtomicReference<StreamingClient.StreamRequest> captured = new AtomicReference<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.set(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("m", request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageDeltaEvent("end_turn", Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "session-model"; }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("session-model")
            .build());
        SubmitOptions options = SubmitOptions.DEFAULT
            .withPromptOverrides("command-model", "medium")
            .asSlashCommand();

        engine.submitMessage("run", options).forEachRemaining(_ -> {});

        assertEquals("command-model", captured.get().model());
        assertEquals("medium", captured.get().effort());
        assertEquals("session-model", engine.getConfig().model(),
            "one command must not mutate the session model");
    }

    @Test
    void sharedFastModeControllerDrivesSubsequentWireRequests() {
        AtomicReference<StreamingClient.StreamRequest> captured = new AtomicReference<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.set(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("m", request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageDeltaEvent("end_turn", Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "opus"; }
        };
        FastModeController controller = new FastModeController(true, false, () -> 0L);
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("opus")
            .fastModeController(controller)
            .build());

        controller.setEnabled(true);
        engine.submitMessage("fast", SubmitOptions.DEFAULT).forEachRemaining(_ -> {});
        assertTrue(captured.get().fastMode());

        controller.setEnabled(false);
        engine.submitMessage("standard", SubmitOptions.DEFAULT).forEachRemaining(_ -> {});
        assertFalse(captured.get().fastMode());
    }

    @Test
    void explicitThinkingBudgetReachesTheWireRequest() {
        AtomicReference<StreamingClient.StreamRequest> captured = new AtomicReference<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.set(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("m", request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageDeltaEvent("end_turn", Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "claude-sonnet-4-5-20250929"; }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-4-5-20250929")
            .build());
        engine.getConfig().setThinkingBudgetTokens(5_000);

        engine.submitMessage("run", SubmitOptions.DEFAULT).forEachRemaining(_ -> {});

        assertTrue(captured.get().thinkingEnabled());
        assertEquals(5_000, captured.get().thinkingBudgetTokens());
    }

    @Test
    void preparedQueryCanSkipWritingThePromptCache() {
        AtomicReference<StreamingClient.StreamRequest> captured = new AtomicReference<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.set(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("m", request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageDeltaEvent("end_turn", Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }

            @Override public String getModel() { return "test-model"; }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("test-model")
            .build());
        PreparedQueryRequest request = new PreparedQueryRequest(
            List.<Message>of(new UserMessage("user-1", MessageContent.ofText("summarize"))),
            "Be concise",
            "test-model",
            null,
            "compact",
            null,
            1,
            null,
            null,
            true,
            SubmitOptions.DEFAULT);

        engine.submitPrepared(request).forEachRemaining(_ -> {});

        assertTrue(captured.get().skipCacheWrite());
    }

    @Test
    void startNewSession_mintsANewSessionId() {
        DefaultQuerySession engine = newEngine();
        String before = engine.getSessionId();

        String returned = engine.startNewSession();

        assertNotEquals(before, engine.getSessionId());
        assertEquals(engine.getSessionId(), returned);
    }

    @Test
    void startNewSession_restoresTheWriterThatPrecededRefusalFallback() {
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("settings-model")
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);
        config.setMainLoopModelOverride("pre-fallback-writer");
        config.activateRefusalFallback("fallback-writer");
        assertEquals("fallback-writer", config.model());

        engine.startNewSession();

        assertEquals("pre-fallback-writer", config.model());
        assertEquals("pre-fallback-writer", config.mainLoopModelOverride());
    }

    @Test
    void startNewSession_keepsARewindWriterThatReplacedRefusalFallback() {
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("settings-model")
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);
        config.activateRefusalFallback("fallback-writer");
        config.setMainLoopModelOverride("rewound-writer");

        engine.startNewSession();

        assertEquals("rewound-writer", config.model());
        assertEquals("rewound-writer", config.mainLoopModelOverride());
    }

    @Test
    void switchingSessions_restoresTheWriterThatPrecededRefusalFallback() {
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("settings-model")
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);
        config.activateRefusalFallback("fallback-writer");

        engine.switchToSession("resumed-session");

        assertEquals("settings-model", config.model());
        assertNull(config.mainLoopModelOverride());
    }



    @Test
    void applyContextModifier_setsModelAndEffortOverride() {
        DefaultQuerySession engine = newEngine();
        assertNull(engine.getModelOverride());
        assertNull(engine.getEffortOverride());
        assertNull(engine.getAttributionSkill());
        assertNull(engine.getAttributionPlugin());

        engine.applyContextModifier(new ToolContextModifier(
            List.of("Bash"), "opus", "high", "demo:deploy", "demo"));
        assertEquals("opus", engine.getModelOverride());
        assertEquals("high", engine.getEffortOverride());
        assertEquals("demo:deploy", engine.getAttributionSkill());
        assertEquals("demo", engine.getAttributionPlugin());

        // Last skill wins (persisted for the session).
        engine.applyContextModifier(new ToolContextModifier(null, "sonnet", "low"));
        assertEquals("sonnet", engine.getModelOverride());
        assertEquals("low", engine.getEffortOverride());
        assertEquals("demo:deploy", engine.getAttributionSkill());
        assertEquals("demo", engine.getAttributionPlugin());

        // Null modifier must not clobber existing overrides.
        engine.applyContextModifier(null);
        assertEquals("sonnet", engine.getModelOverride());
        assertEquals("low", engine.getEffortOverride());
    }

    @Test
    void startNewSession_clearsMessages() {
        DefaultQuerySession engine = newEngine();
        engine.loadMessages(List.of());
        engine.getMutableMessages().add(new UserMessage("u1", MessageContent.ofText("hi")));
        engine.setLastCacheSafeForkRequest(new StreamingClient.StreamRequest(
            "model", 100, "system", List.of(), true));
        assertFalse(engine.getMessages().isEmpty());
        assertNotNull(engine.getLastCacheSafeForkRequest());

        engine.startNewSession();

        assertTrue(engine.getMessages().isEmpty());
        assertNull(engine.getLastCacheSafeForkRequest());
    }

    @Test
    void startNewSession_clearsReadFileStateAndBashTools() {
        DefaultQuerySession engine = newEngine();
        engine.putReadFilePaths(Set.of("/tmp/a.txt"));
        engine.putBashTools(Set.of("git"));
        assertFalse(engine.getReadFileState().isEmpty());
        assertFalse(engine.getBashTools().isEmpty());

        engine.startNewSession();

        assertTrue(engine.getReadFileState().isEmpty());
        assertTrue(engine.getBashTools().isEmpty());
    }

    @Test
    void startNewSession_clearsDiscoveredSkillNames() {
        DefaultQuerySession engine = newEngine();
        engine.getDiscoveredSkillNames().add("commit-helper");
        assertFalse(engine.getDiscoveredSkillNames().isEmpty());

        engine.startNewSession();

        assertTrue(engine.getDiscoveredSkillNames().isEmpty());
    }

    @Test
    void startNewSession_resetsTurnDeltaAndCompactionState() {
        DefaultQuerySession engine = newEngine();
        engine.getNestedMemoryAttachmentTriggers().add("/tmp/CLAUDE.md");
        engine.setCompactionOccurred(true);
        engine.setPreviousTurnTools(List.of("Read", "Bash"));
        engine.applyContextModifier(new ToolContextModifier(List.of(), "opus", "high"));

        engine.startNewSession();

        assertTrue(engine.getNestedMemoryAttachmentTriggers().isEmpty());
        assertFalse(engine.hasCompactionOccurred());
        assertTrue(engine.getPreviousTurnTools().isEmpty());
        assertNull(engine.getModelOverride());
        assertNull(engine.getEffortOverride());
        assertNull(engine.getAttributionSkill());
        assertNull(engine.getAttributionPlugin());
    }

    @Test
    void startNewSession_preservesUsage_resetsPermissionState() {
        DefaultQuerySession engine = newEngine();
        Usage usage = new Usage(100, 50, 0, 0);
        engine.setTotalUsage(usage);
        engine.addPermissionDenial(new SDKMessage.PermissionDenial("Bash", "tu-1", Map.of()));
        engine.handleOrphanedPermissions();
        assertNotEquals(Usage.EMPTY, engine.getTotalUsage());
        assertFalse(engine.getPermissionDenials().isEmpty());
        assertTrue(engine.getHasHandledOrphanedPermission());

        engine.startNewSession();

        assertEquals(usage, engine.getTotalUsage(),
            "TS cost-tracker state is process-level — clearConversation() never resets it, "
                + "so /cost after /clear must still show the whole session's accumulated spend");
        assertTrue(engine.getPermissionDenials().isEmpty());
        assertFalse(engine.getHasHandledOrphanedPermission());
    }



    @Test
    void withDenialRecording_recordsDenialOnNonAllow() {
        DefaultQuerySession engine = newEngine();
        JsonNode input = JsonUtils.getMapper().createObjectNode().put("command", "rm -rf /");
        PermissionAskContext ctx = PermissionAskContext.simple("Bash", input, "tu-7");
        PermissionAskCallback wrapped = engine.withDenialRecording(_ -> PermissionAskCallback.Result.deny());

        wrapped.ask(ctx);

        List<SDKMessage.PermissionDenial> denials = engine.getPermissionDenials();
        assertEquals(1, denials.size());
        assertEquals("Bash", denials.getFirst().toolName());
        assertEquals("tu-7", denials.getFirst().toolUseId());
        assertEquals(Map.of("command", "rm -rf /"), denials.getFirst().toolInput());
    }

    @Test
    void withDenialRecording_mapsAgentToolToTask() {
        DefaultQuerySession engine = newEngine();
        JsonNode input = JsonUtils.getMapper().createObjectNode().put("prompt", "x");
        PermissionAskContext ctx = PermissionAskContext.simple("Agent", input, "tu-8");
        PermissionAskCallback wrapped = engine.withDenialRecording(_ -> PermissionAskCallback.Result.denyWithFeedback("nope"));

        wrapped.ask(ctx);

        List<SDKMessage.PermissionDenial> denials = engine.getPermissionDenials();
        assertEquals(1, denials.size());
        assertEquals("Task", denials.getFirst().toolName(), "SDK expects the legacy Task name for the agent tool");
    }

    @Test
    void withDenialRecording_doesNotRecordOnAllow() {
        DefaultQuerySession engine = newEngine();
        PermissionAskContext ctx = PermissionAskContext.simple("Bash", null, "tu-9");
        PermissionAskCallback wrapped = engine.withDenialRecording(_ -> PermissionAskCallback.Result.allow());

        wrapped.ask(ctx);

        assertTrue(engine.getPermissionDenials().isEmpty());
    }

    @Test
    void permissionDenial_serializesSnakeCase() throws Exception {
        SDKMessage.PermissionDenial d =
            new SDKMessage.PermissionDenial("Bash", "tu-1", Map.of("command", "ls"));
        String json = JsonUtils.getMapper().writeValueAsString(d);
        assertTrue(Strings.CS.contains(json, "\"tool_name\""), json);
        assertTrue(Strings.CS.contains(json, "\"tool_use_id\""), json);
        assertTrue(Strings.CS.contains(json, "\"tool_input\""), json);
    }

    @Test
    void startNewSession_clearsFileHistoryManager(@TempDir Path backupRoot) throws IOException {

        SessionIdentity identity = SessionIdentity.newRandom();
        FileHistoryManager fhm = new FileHistoryManager(identity, backupRoot, backupRoot);
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .sessionIdentity(identity)
            .initialFileHistoryManager(fhm)
            .build());
        fhm.makeSnapshot("msg-1");
        assertFalse(engine.getFileHistoryManager().snapshotsView().isEmpty());

        engine.startNewSession();

        assertTrue(engine.getFileHistoryManager().snapshotsView().isEmpty(),
            "a pre-/clear snapshot must not remain restorable after the new session starts");
    }

    @Test
    void startNewSession_fileHistoryDisabled_doesNotThrow() {
// getFileHistoryManager == null (default) — must not NPE.
        DefaultQuerySession engine = newEngine();
        assertDoesNotThrow(engine::startNewSession);
    }

    @Test
    void switchToSession_updatesIdWithoutTouchingConversationState() {
        DefaultQuerySession engine = newEngine();
        Usage usage = new Usage(100, 50, 0, 0);
        engine.setTotalUsage(usage);
        engine.injectSystemReminder("keep me");
        String oldId = engine.getSessionId();

        String result = engine.switchToSession("existing-session-id");

        assertEquals("existing-session-id", result);
        assertEquals("existing-session-id", engine.getSessionId());
        assertNotEquals(oldId, engine.getSessionId());
        assertEquals(usage, engine.getTotalUsage(), "switching sessions must not reset usage");
        assertEquals(1, engine.getMessages().size(),
            "switchToSession is paired with a separate loadMessages() call — it must not itself "
                + "clear whatever the caller already loaded");
    }

    @Test
    void switchToSession_rejectsBlankId() {
        DefaultQuerySession engine = newEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.switchToSession(""));
        assertThrows(IllegalArgumentException.class, () -> engine.switchToSession(null));
    }

    @Test
    void twoEnginesWithoutExplicitIdentity_getIndependentSessionIds() {
        // Default (no sessionIdentity passed to the builder) — each engine
        // must mint its own, unrelated id. Guards against ever falling back
        // to a shared/JVM-static default that would bleed across unrelated
        // DefaultQuerySession instances (sub-agents, unit tests).
        DefaultQuerySession a = newEngine();
        DefaultQuerySession b = newEngine();
        assertNotEquals(a.getSessionId(), b.getSessionId());
    }

    @Test
    void sharedSessionIdentity_isVisibleToOtherHoldersWithoutASetterCall() {
// matches the real CLI/UI wiring: DefaultQuerySession and some other
        // component (HookEngine, InputPanel, ShellVariableInjector in
        // production) are handed the SAME SessionIdentity instance. A single
// switchToSession call must be visible to the other holder on its
// next read — no setSessionId-style sync call should be needed.
        SessionIdentity shared = SessionIdentity.newRandom();
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder().llmClient(NOOP_CLIENT).sessionIdentity(shared).build());

        engine.switchToSession("new-id");

        assertEquals("new-id", shared.get(), "the shared holder must see the switch directly");
        assertEquals("new-id", engine.getSessionId());
    }

    @Test
    void injectSystemReminder_appendsHiddenUserMessage() {
        DefaultQuerySession engine = newEngine();

        engine.injectSystemReminder("project uses Java 21");

        assertEquals(1, engine.getMessages().size());
        Message m = engine.getMessages().getFirst();
        assertInstanceOf(UserMessage.class, m);
        var um = (UserMessage) m;
        assertTrue(um.isMeta(), "hook context must be hidden from the visible transcript (isMeta=true)");
        assertTrue(Strings.CS.contains(um.message().text(), "<system-reminder>"));
        assertTrue(Strings.CS.contains(um.message().text(), "project uses Java 21"));
    }

    @Test
    void injectSystemReminder_nullOrBlankIsNoOp() {
        DefaultQuerySession engine = newEngine();

        engine.injectSystemReminder(null);
        engine.injectSystemReminder("   ");

        assertTrue(engine.getMessages().isEmpty());
    }

    @Test
    void fullCompactionRetainsExactlyOneIntervalForTheRewindPicker() {
        UserMessage ancient = new UserMessage("ancient", MessageContent.ofText("ancient"));
        SystemMessage previousBoundary = new SystemMessage(
            "previous-boundary", "compact_boundary", "info", "previous");
        UserMessage previousPrompt = new UserMessage(
            "previous-prompt", MessageContent.ofText("previous prompt"));
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(ancient, previousBoundary, previousPrompt))
            .build());
        SystemMessage boundary = new SystemMessage(
            "boundary", "compact_boundary", "info", "current");
        UserMessage summary = new UserMessage("summary", MessageContent.ofText("summary"));

        engine.loadCompactedMessages(List.of(boundary, summary));

        assertEquals(List.of(previousBoundary, previousPrompt, boundary, summary),
            engine.getMessagesForRewind());
        assertTrue(engine.hasCompactionOccurred());

        UserMessage currentPrompt = new UserMessage(
            "current-prompt", MessageContent.ofText("current prompt"));
        engine.getMutableMessages().add(currentPrompt);
        SystemMessage nextBoundary = new SystemMessage(
            "next-boundary", "compact_boundary", "info", "next");
        UserMessage nextSummary = new UserMessage(
            "next-summary", MessageContent.ofText("next summary"));

        engine.loadCompactedMessages(List.of(nextBoundary, nextSummary));

        assertEquals(List.of(boundary, summary, currentPrompt, nextBoundary, nextSummary),
            engine.getMessagesForRewind());
    }

    @Test
    void partialCompactionCanRetainTheExactFullscreenRawPrefixForRewind() {
        UserMessage first = new UserMessage("first", MessageContent.ofText("first"));
        UserMessage selected = new UserMessage("selected", MessageContent.ofText("selected"));
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(first, selected))
            .build());
        SystemMessage boundary = new SystemMessage(
            "boundary", "compact_boundary", "info", "partial");
        UserMessage summary = new UserMessage("summary", MessageContent.ofText("summary"));

        engine.loadCompactedMessages(
            List.of(boundary, first, summary),
            List.of(first));

        assertEquals(List.of(boundary, first, summary), engine.getMessages());
        assertEquals(List.of(first, boundary, first, summary), engine.getMessagesForRewind());
        assertTrue(engine.hasCompactionOccurred());

        engine.loadCompactedMessages(List.of(boundary, summary), List.of());

        assertEquals(List.of(boundary, summary), engine.getMessagesForRewind());
    }
}
