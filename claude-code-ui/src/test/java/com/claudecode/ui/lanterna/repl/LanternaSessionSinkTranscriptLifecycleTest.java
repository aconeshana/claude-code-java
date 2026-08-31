package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.tools.tasks.PendingBackgroundWork;
import com.claudecode.ui.lanterna.input.InputPanel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Guards the transcript lifecycle and permission-mode handoff owned by {@link LanternaSessionSink}. */
class LanternaSessionSinkTranscriptLifecycleTest {

    @Test
    void completedArgvStartupPromptIsNotRecachedByExitSoftInterrupt() {
        assertFalse(InterruptedPromptPolicy.shouldCacheSoftInterruptedPrompt(
            "argv prompt", true));
        assertTrue(InterruptedPromptPolicy.shouldCacheSoftInterruptedPrompt(
            "typed prompt", false));
        assertFalse(InterruptedPromptPolicy.shouldCacheSoftInterruptedPrompt(
            "   ", false));
    }

    @Test
    void statusLineRefreshSignalMatchesReleasedLastAssistantMessageTrigger() {
        assertTrue(LanternaSessionSink.shouldRefreshStatusLine(
            new SDKMessage.StreamEvent(
                SDKMessage.ASSISTANT_USAGE_FINALIZED_EVENT, "assistant-1")));
        assertFalse(LanternaSessionSink.shouldRefreshStatusLine(
            new SDKMessage.Assistant(null, null)));
        assertFalse(LanternaSessionSink.shouldRefreshStatusLine(
            new SDKMessage.StreamEvent("tool_result_success", "Bash|done")));
        assertFalse(LanternaSessionSink.shouldRefreshStatusLine(
            new SDKMessage.User(null)));
    }

    @Test
    void pokemonExperienceCountsAllApiTokenClasses() {
        assertEquals(100L, LanternaSessionSink.totalUsageTokens(
            new Usage(10, 20, 30, 40)));
    }

    @Test
    void taskOwnerColorsReserveReleased197RedForTheTeamLead() {
        assertEquals("blue", LanternaSessionSink.teammateColorForOrdinal(0));
        assertEquals("green", LanternaSessionSink.teammateColorForOrdinal(1));
        assertEquals("red", LanternaSessionSink.teammateColorForOrdinal(7));
    }

    @Test
    void taskOwnerColorsUseFullSpawnTimestampPrecisionLikeReleased197() {
        Instant first = Instant.ofEpochSecond(1, 100_000);
        Instant second = Instant.ofEpochSecond(1, 900_000);

        assertTrue(LanternaSessionSink.compareTeammateStartTimes(first, second) < 0,
            "sub-millisecond spawn order must not fall back to alphabetic render order");
    }

    @Test
    void creatingAnotherTeamResetsReleased197TeammateColorAllocation() {
        Map<String, String> colors = new HashMap<>();
        colors.put("old-agent", "orange");
        AtomicInteger index = new AtomicInteger(5);

        String teamId = LanternaSessionSink.synchronizeTeammateColorTeam(
            "team-a", "team-b", colors, index);

        assertEquals("team-b", teamId);
        assertTrue(colors.isEmpty());
        assertEquals(0, index.get());
        assertEquals("blue", LanternaSessionSink.teammateColorForOrdinal(index.get()));
    }

    @Test
    void recreatingTheSameNamedTeamResetsReleased197TeammateColorAllocation() {
        Object oldLifecycle = new Object();
        Object newLifecycle = new Object();
        Map<String, String> colors = new HashMap<>();
        colors.put("old-agent", "orange");
        AtomicInteger index = new AtomicInteger(5);
        var current = new LanternaSessionSink.TeammateColorTeamIdentity(
            "team-a", oldLifecycle);

        var next = LanternaSessionSink.synchronizeTeammateColorTeam(
            current, "team-a", newLifecycle, colors, index);

        assertEquals("team-a", next.teamId());
        assertSame(newLifecycle, next.lifecycle());
        assertTrue(colors.isEmpty());
        assertEquals(0, index.get());
        assertEquals("blue", LanternaSessionSink.teammateColorForOrdinal(index.get()));
    }

    @Test
    void pokemonExperienceCreditsFinalizedToolLoopRoundsWithoutDoubleCounting() {
        LanternaSessionSink.TurnPokemonExperienceLedger ledger =
            new LanternaSessionSink.TurnPokemonExperienceLedger();
        List<Message> messages = List.of(
            assistantWithUsage("assistant-1", new Usage(10, 20, 30, 40)),
            assistantWithUsage("assistant-2", new Usage(11, 22, 33, 44)));

        SDKMessage first = finalizedUsage("assistant-1");
        SDKMessage second = finalizedUsage("assistant-2");
        assertEquals(100L, ledger.creditFinalizedAssistant(first, messages));
        assertEquals(0L, ledger.creditFinalizedAssistant(first, messages),
            "duplicate finalized signals must not add experience twice");
        assertEquals(110L, ledger.creditFinalizedAssistant(second, messages));
        assertEquals(15L, ledger.creditTurnRemainder(225L),
            "turn completion only credits usage not already observed round-by-round");
        assertEquals(0L, ledger.creditTurnRemainder(225L));
    }

    @Test
    void pokemonExperienceTurnCompletionBackfillsMissingFinalizedSignals() {
        LanternaSessionSink.TurnPokemonExperienceLedger ledger =
            new LanternaSessionSink.TurnPokemonExperienceLedger();

        assertEquals(250L, ledger.creditTurnRemainder(250L));
        ledger.reset();
        assertEquals(250L, ledger.creditTurnRemainder(250L));
    }

    @Test
    void completedTurnSynchronizesToolDrivenPermissionModeChange() {
        InputPanel panel = new InputPanel("plan");
        TurnOutcome outcome = new TurnOutcome(false, false, 1L, null, Map.of(), null, "default");

        LanternaSessionSink.syncPermissionMode(panel, outcome);

        assertEquals("default", panel.getPermissionMode());
    }

    @Test
    void closingRowFollowsWhichWayTheTurnEnded() {
        assertEquals(LanternaSessionSink.ClosingRow.TURN_SUMMARY,
            LanternaSessionSink.closingRow(
                new TurnOutcome(false, false, 1L, null, Map.of(), null, "default")));
        assertEquals(LanternaSessionSink.ClosingRow.NONE,
            LanternaSessionSink.closingRow(
                new TurnOutcome(true, true, 1L, "hi", Map.of(), null, "default")),
            "an early Esc restores the draft without leaving an interruption row");
        assertEquals(LanternaSessionSink.ClosingRow.INTERRUPTED,
            LanternaSessionSink.closingRow(
                new TurnOutcome(true, false, 1L, null, Map.of(), null, "default")));
        assertEquals(LanternaSessionSink.ClosingRow.NONE,
            LanternaSessionSink.closingRow(
                new TurnOutcome(false, false, false, true, 1L, null, Map.of(), null, "default")),
            "a permission rejection is already explained by its tool-result row");
    }

    @Test
    void turnDurationEligibilityMatchesReleasedThirtySecondOrBudgetGate() {
        TurnOutcome completed = new TurnOutcome(false, false, 1L, null, Map.of(), null, "default");
        TurnOutcome cancelled = new TurnOutcome(true, false, 1L, null, Map.of(), null, "default");

        // Matches Claude Code 2.1.197 REPL.tsx:2978: a duration row only surfaces
        // for turns that exceeded 30s or carried a token budget.
        assertFalse(LanternaSessionSink.shouldRecordTurnDuration(1L, false, completed),
            "fast turns without a budget do not emit a duration row");
        assertTrue(LanternaSessionSink.shouldRecordTurnDuration(30_001L, false, completed),
            "a turn > 30s records even without a budget");
        assertTrue(LanternaSessionSink.shouldRecordTurnDuration(1L, true, completed),
            "a budget makes a fast turn eligible for the row");
        assertFalse(LanternaSessionSink.shouldRecordTurnDuration(60_000L, true, cancelled),
            "cancelled turns never persist the duration row");
    }

    @Test
    void aRefusalHandedBackForEditingClosesWithNothingAtAll() {
        TurnOutcome edit = new TurnOutcome(true, true, false, false, true, 1L,
            "hi", Map.of(), null, "default");

        assertEquals(LanternaSessionSink.ClosingRow.NONE,
            LanternaSessionSink.closingRow(edit),
            "released suppresses the interruption transcript message for this abort "
                + "reason, so nothing renders the Interrupted row either");
    }

    @Test
    void completedInteractiveTurnRecordsDurationCachesLastPromptAndChangesPermissionMode() {
        DefaultQuerySession engine = newEngine(List.of(
            new UserMessage("u", MessageContent.ofText("hello")),
            new ProgressMessage("progress", "working"),
            new UserMessage("u2", MessageContent.ofText("second"))));
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        UserInput input = UserInput.of("approve plan", "approve plan", Map.of(), "plan");
        TurnOutcome outcome = new TurnOutcome(false, false, 123L, null, Map.of(), null, "default");

        LanternaSessionSink.recordInitialTranscriptMetadata(engine, input);
        LanternaSessionSink.recordTranscriptLifecycle(engine, input, outcome, plainDuration(123L), false);

        assertEquals(List.of("mode", "permission-mode", "message", "last-prompt-cache", "permission-mode"),
            transcript.events);
        SystemMessage duration = (SystemMessage) transcript.messages.getFirst();
        assertEquals("turn_duration", duration.subtype());
        assertEquals(123L, duration.durationMs());
        assertEquals(2, duration.messageCount(),
            "released counts only messages eligible for transcript logging");
        assertEquals("approve plan", transcript.lastPrompt);
        assertEquals("default", transcript.permissionMode);
        assertEquals("normal", transcript.mode);
    }

    @Test
    void approvedPlanExitRestoresLiveModeWithoutPersistingPermissionModeToggle() {
        DefaultQuerySession engine = newEngine(List.of(
            new UserMessage("u", MessageContent.ofText("approve plan")),
            new AssistantMessage("a", AssistantContent.of(List.of(
                new ToolUseBlock("exit-1", "ExitPlanMode", JsonUtils.getMapper().createObjectNode())))),
            new UserMessage("result", MessageContent.ofText("approved"), false, false,
                Map.of("tool", "ExitPlanMode"), null, null, null, null, null)));
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        UserInput input = UserInput.of("approve plan", "approve plan", Map.of(), "plan");
        TurnOutcome outcome = new TurnOutcome(false, false, 123L, null, Map.of(), null, "default");

        LanternaSessionSink.recordInitialTranscriptMetadata(engine, input);
        LanternaSessionSink.recordTranscriptLifecycle(engine, input, outcome, plainDuration(123L), false);

        assertEquals(List.of("mode", "permission-mode", "message", "last-prompt-cache"),
            transcript.events);
        assertEquals("plan", transcript.permissionMode);
    }

    @Test
    void rejectedPermissionCachesLastPromptButRecordsNoTurnDuration() {
        DefaultQuerySession engine = newEngine(List.of(new UserMessage("u", MessageContent.ofText("hello"))));
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        UserInput input = UserInput.of("denied command", "denied command", Map.of(), "default");
        TurnOutcome outcome = new TurnOutcome(false, false, true, 123L,
            null, Map.of(), null, "default");

        LanternaSessionSink.recordTranscriptLifecycle(engine, input, outcome, plainDuration(123L), false);

        assertEquals(List.of("last-prompt-cache"), transcript.events,
            "2.1.197 caches the rejected prompt but omits turn_duration until shutdown metadata flush");
        assertEquals("denied command", transcript.lastPrompt);
    }

    @Test
    void resumedInteractiveTurnDefersInitialMetadataUntilTheCompletedTurnTail() {
        DefaultQuerySession engine = newEngine(List.of(
            new UserMessage("seed-user", MessageContent.ofText("seed")),
            new UserMessage("seed-tail", MessageContent.ofText("seed tail"))));
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        UserInput input = UserInput.of("resume prompt", "resume prompt", Map.of(), "default");
        TurnOutcome outcome = new TurnOutcome(false, false, 123L, null, Map.of(), null, null);

        boolean deferred = LanternaSessionSink.recordInitialTranscriptMetadata(engine, input, true);

        assertTrue(deferred);
        assertEquals(List.of(), transcript.events);

        LanternaSessionSink.recordTranscriptLifecycle(engine, input, outcome, plainDuration(123L), deferred);

        assertEquals(List.of("message", "last-prompt", "mode", "permission-mode"),
            transcript.events);
        assertEquals("normal", transcript.mode);
        assertEquals("default", transcript.permissionMode);
    }

    @Test
    void resumedTtyNativeTurnDoesNotDuplicatePersistedModeOrPermissionMode() {
        DefaultQuerySession engine = newEngine(List.of(
            new UserMessage("seed-user", MessageContent.ofText("seed")),
            new UserMessage("seed-tail", MessageContent.ofText("seed tail"))));
        RecordingTranscript transcript = new RecordingTranscript();
        transcript.persistedMode = true;
        transcript.persistedPermissionMode = true;
        engine.setTranscriptSink(transcript);
        UserInput input = UserInput.of("continue prompt", "continue prompt", Map.of(), "default");
        TurnOutcome outcome = new TurnOutcome(false, false, 123L, null, Map.of(), null, null);

        boolean deferred = LanternaSessionSink.recordInitialTranscriptMetadata(engine, input, true);
        LanternaSessionSink.recordTranscriptLifecycle(engine, input, outcome, plainDuration(123L), deferred);

        assertEquals(List.of("message", "last-prompt"), transcript.events,
            "a native TTY transcript already carries mode metadata at its head");
    }

    @Test
    void freshSimpleArgvTurnLeavesFallbackForFutureContinueRestore() {
        DefaultQuerySession engine = newEngine(List.of(new UserMessage(
            "startup-user", MessageContent.ofText("argv prompt"))));
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        UserInput input = UserInput.of(
            "argv prompt", "argv prompt", Map.of(), "bypassPermissions")
            .asInteractiveStartupPrompt();
        TurnOutcome outcome = new TurnOutcome(false, false, 123L, null, Map.of(), null, null);

        LanternaSessionSink.recordTranscriptLifecycle(engine, input, outcome, plainDuration(123L), false);

        assertEquals(List.of("message"), transcript.events,
            "the next --continue process reconstructs this fallback from the user row");
    }

    @Test
    void interactiveTurnStartsTypedPromptIdentityBeforeTranscriptMessages() {
        DefaultQuerySession engine = newEngine(List.of());
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);

        LanternaSessionSink.recordInteractivePromptStart(engine);

        assertEquals(List.of("prompt-start"), transcript.events);
        assertEquals("typed", transcript.promptSource);
    }

    @Test
    void taskNotificationTurnDoesNotBecomeTypedPromptOrLastPrompt() {
        DefaultQuerySession engine = newEngine(List.of(
            new UserMessage("seed-user", MessageContent.ofText("seed")),
            new UserMessage("seed-tail", MessageContent.ofText("seed tail"))));
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        UserInput input = UserInput.of(
            "<task-notification><summary>Agent finished</summary></task-notification>",
            "<task-notification><summary>Agent finished</summary></task-notification>",
            Map.of(), "default").withQuerySource("task-notification");
        TurnOutcome outcome = new TurnOutcome(false, false, 123L, null, Map.of(), null, null);

        LanternaSessionSink.recordPromptStart(engine, null);
        LanternaSessionSink.recordTranscriptLifecycle(engine, input, outcome, plainDuration(123L), false);

        assertEquals(List.of("prompt-start", "message"), transcript.events);
        assertNull(transcript.promptSource);
        assertNull(transcript.lastPrompt);
    }

    @Test
    void freshTurnMetadataIsPreparedOnlyOnceAfterFastAiTitle() {
        DefaultQuerySession engine = newEngine(List.of());
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        UserInput input = UserInput.of("first prompt", "first prompt", Map.of(), "default");
        LanternaSessionSink sink = newSink(engine);

        transcript.recordAiTitle(engine.getSessionId(), "Fast title");
        sink.prepareFirstTurnTranscriptMetadata(input);
        sink.prepareFirstTurnTranscriptMetadata(input);

        assertEquals(List.of("ai-title", "mode", "permission-mode"), transcript.events,
            "a completed fast title wins the race and fresh metadata must not duplicate");
    }

    @Test
    void startupSystemNoticePreparesModeMetadataBeforeTheNoticeWithoutTurnDuplication() {
        DefaultQuerySession engine = newEngine(List.of());
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        LanternaSessionSink sink = newSink(engine);

        sink.prepareStartupSystemTranscriptMetadata("auto");
        engine.appendTranscriptMessage(new SystemMessage(
            "notice", "informational", "notice", "auto notice"));
        sink.prepareFirstTurnTranscriptMetadata(
            UserInput.of("prompt", "prompt", Map.of(), "auto"));

        assertEquals(List.of("mode", "permission-mode", "message"), transcript.events,
            "released fresh TTY order is mode -> permission-mode -> informational");
    }

    @Test
    void turnEndingWithBackgroundWorkOutstandingRecordsThePendingCountsOnTheRow() {
        DefaultQuerySession engine = newEngine(List.of(
            new UserMessage("u", MessageContent.ofText("hello"))));
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        UserInput input = UserInput.of("dispatch", "dispatch", Map.of(), "default");
        TurnOutcome outcome = new TurnOutcome(false, false, 123L, null, Map.of(), null, null);

        LanternaSessionSink.recordTranscriptLifecycle(engine, input, outcome,
            new PendingBackgroundWork.Resolved(123L, 2, 1, 1_000L), false);

        SystemMessage duration = (SystemMessage) transcript.messages.getFirst();
        assertEquals(2, duration.pendingBackgroundAgentCount());
        assertEquals(1, duration.pendingWorkflowCount());
    }

    @Test
    void ordinaryTurnLeavesThePendingCountFieldsOffTheRowEntirely() {
        DefaultQuerySession engine = newEngine(List.of(
            new UserMessage("u", MessageContent.ofText("hello"))));
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        UserInput input = UserInput.of("hello", "hello", Map.of(), "default");
        TurnOutcome outcome = new TurnOutcome(false, false, 123L, null, Map.of(), null, null);

        LanternaSessionSink.recordTranscriptLifecycle(engine, input, outcome,
            plainDuration(123L), false);

        SystemMessage duration = (SystemMessage) transcript.messages.getFirst();
        assertNull(duration.pendingBackgroundAgentCount(),
            "released omits the field rather than writing 0, so a resumed transcript "
                + "cannot mistake an ordinary turn for one that waited");
        assertNull(duration.pendingWorkflowCount());
    }

    /** A turn that ended with nothing outstanding: just the active elapsed time. */
    private static PendingBackgroundWork.Resolved plainDuration(long durationMs) {
        return new PendingBackgroundWork.Resolved(durationMs, null, null, null);
    }

    private static DefaultQuerySession newEngine(List<Message> initialMessages) {
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return Collections.emptyIterator();
            }
            @Override public String getModel() { return "test"; }
        };
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .initialMessages(initialMessages)
            .build());
    }

    private static AssistantMessage assistantWithUsage(String id, Usage usage) {
        return new AssistantMessage(id,
            AssistantContent.apiResponse(id, List.of(), usage, "test", "end_turn", null));
    }

    private static SDKMessage finalizedUsage(String id) {
        return new SDKMessage.StreamEvent(SDKMessage.ASSISTANT_USAGE_FINALIZED_EVENT, id);
    }

    private static LanternaSessionSink newSink(DefaultQuerySession engine) {
        return new LanternaSessionSink(
            Runnable::run, null, null, null, null, null, null, null, engine,
            () -> { }, () -> "test", () -> 0, null, () -> "", () -> { }, _ -> { });
    }

    private static final class RecordingTranscript implements TranscriptSink {
        final List<String> events = new ArrayList<>();
        final List<Message> messages = new ArrayList<>();
        String lastPrompt;
        String permissionMode;
        String mode;
        String promptSource;
        boolean persistedMode;
        boolean persistedPermissionMode;

        @Override public void record(String sessionId, Message message) {
            events.add("message");
            messages.add(message);
        }

        @Override public void recordLastPrompt(String sessionId, String prompt) {
            events.add("last-prompt");
            lastPrompt = prompt;
        }

        @Override public void cacheLastPrompt(String sessionId, String prompt) {
            events.add("last-prompt-cache");
            lastPrompt = prompt;
        }

        @Override public void recordPromptStart(String sessionId, String source) {
            events.add("prompt-start");
            promptSource = source;
        }

        @Override public void recordAiTitle(String sessionId, String title) {
            events.add("ai-title");
        }

        @Override public void recordMode(String sessionId, String value) {
            events.add("mode");
            mode = value;
        }

        @Override public void recordPermissionMode(String sessionId, String mode) {
            events.add("permission-mode");
            permissionMode = mode;
        }

        @Override public boolean hasPersistedMode(String sessionId) {
            return persistedMode;
        }

        @Override public boolean hasPersistedPermissionMode(String sessionId) {
            return persistedPermissionMode;
        }
    }
}
