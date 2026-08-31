package com.claudecode.runtime.query;

import com.claudecode.runtime.turn.*;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the headless {@link TurnEngine} — the payoff of the extraction:
 * a whole turn driven <b>synchronously</b> ({@code onUi}/{@code background} =
 * {@code Runnable::run}) with a {@link RecordingSink}, no Lanterna, no GUI thread.
 */
class TurnEngineTest {

    // ── Fakes (no Mockito in this project) ──────────────────────────────────────

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override public String getModel() { return "test-model"; }
    };

    /** A DefaultQuerySession whose {@code submitMessage} yields canned messages; abort reason is
     *  driven via {@code getAbortController.abort(...)}. */
    private static final class FakeQueryEngine extends DefaultQuerySession {
        private final List<SDKMessage> canned;
        private Object submittedPrompt;
        private SubmitOptions submittedOptions;
        private Runnable duringSubmit = () -> {};
        FakeQueryEngine(List<SDKMessage> canned) {
            this(canned, null);
        }
        FakeQueryEngine(List<SDKMessage> canned, FileHistoryManager fileHistoryManager) {
            super(QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialFileHistoryManager(fileHistoryManager)
                .build());
            this.canned = canned;
        }
        @Override public Iterator<SDKMessage> submitMessage(Object prompt, SubmitOptions options) {
            submittedPrompt = prompt;
            submittedOptions = options;
            duringSubmit.run();
            return canned.iterator();
        }
    }

    private static final class RecordingOps implements ConversationOps {
        int dropped = 0;
        int rewound = 0;
        UserMessage toReturn;
        @Override public void dropLastPromptHistoryEntry() { dropped++; }
        @Override public UserMessage rewindBeforeLastRealUser() { rewound++; return toReturn; }
    }

    private static UserInput input(String text) {
        return UserInput.of(text, text, Map.of(), "default");
    }

    private static SDKMessage streamEvent(String type) {
        return new SDKMessage.StreamEvent(type, "x");
    }

    private TurnEngine engine(DefaultQuerySession qe, SessionSink sink, ConversationOps ops,
                              List<QueuedCommand> drained, List<String> submitted) {
        return engine(qe, () -> null, sink, ops, drained, submitted);
    }

    /** {@code drained} collects the batches flattened — batch boundaries are asserted
     *  through {@link #batchingEngine} instead. */
    private TurnEngine engine(DefaultQuerySession qe, Supplier<PermissionGate> gate,
                              SessionSink sink, ConversationOps ops,
                              List<QueuedCommand> drained, List<String> submitted) {
        return engine(qe, gate, sink, ops, drained::addAll, submitted);
    }

    /** Captures each {@code onDrain} call as its own list, so a test can tell one batch of
     *  three from three batches of one. */
    private TurnEngine batchingEngine(DefaultQuerySession qe, List<List<QueuedCommand>> batches) {
        return engine(qe, () -> null, new RecordingSink(), new RecordingOps(),
            batches::add, new ArrayList<>());
    }

    private TurnEngine engine(DefaultQuerySession qe, Supplier<PermissionGate> gate,
                              SessionSink sink, ConversationOps ops,
                              Consumer<List<QueuedCommand>> onDrain, List<String> submitted) {
        return new TurnEngine(
            qe, gate, sink, ops,
            onDrain,
            Runnable::run,    // onUi — synchronous
            Runnable::run,    // background — synchronous
            submitted::add,   // recordLastSubmitted
            TurnAwakeGuard.noop(),
            () -> {});        // no service-owned hooks in unit tests
    }

    @Test
    void popAllEditableCombinesHumanPromptsAndLeavesSystemNotificationsQueued() {
        var qe = new FakeQueryEngine(List.of());
        var engine = engine(qe, new RecordingSink(), new RecordingOps(),
            new ArrayList<>(), new ArrayList<>());
        List<List<QueuedCommand>> snapshots = new ArrayList<>();
        engine.setInputQueueListener(snapshots::add);
        PastedContent image = PastedContent.image(7, "base64", "image/png", null, null);
        engine.enqueue(new QueuedCommand("first [Image #7]", Map.of(7, image)));
        engine.enqueue(QueuedCommand.notification("task finished"));

        QueuedInputDraft restored = engine.popAllEditable("draft", 2);

        assertNotNull(restored);
        assertEquals("first [Image #7]\ndraft", restored.text());
        assertEquals("first [Image #7]".length() + 1 + 2, restored.cursorOffset());
        assertEquals(image, restored.pastedContents().get(7));
        assertEquals(1, engine.countQueued(_ -> true));
        assertEquals(1, engine.countQueued(c -> Strings.CS.equals("task-notification", c.mode())));
        assertEquals(List.of(), snapshots.getFirst());
        assertEquals(List.of(QueuedCommand.notification("task finished")), snapshots.getLast());
    }

    @Test
    void popAllEditableDoesNotExposeMetaPromptXmlToTheDraft() {
        var engine = engine(new FakeQueryEngine(List.of()), new RecordingSink(),
            new RecordingOps(), new ArrayList<>(), new ArrayList<>());
        engine.enqueue(new QueuedCommand("<system>hidden</system>", null, "prompt",
            null, true, null, false, false, null, null, null));

        assertNull(engine.popAllEditable("draft", 3));
        assertEquals(1, engine.countQueued(_ -> true));
    }

    @Test
    void metaSubmissionPropagatesToQueryWithoutBecomingHumanInput() {
        var qe = new FakeQueryEngine(List.of());
        var submitted = new ArrayList<String>();
        var sink = new RecordingSink();
        var engine = engine(qe, sink, new RecordingOps(), new ArrayList<>(), submitted);
        UserInput scheduled = UserInput.builder("hello", "hello")
            .permissionMode("bypassPermissions")
            .meta(true)
            .build();

        engine.submit(scheduled);

        assertTrue(qe.submittedOptions.isMeta());
        assertEquals(List.of(), submitted,
            "scheduled prompts must not replace the last human input used by restore/undo");
        assertEquals(scheduled, sink.starts.getFirst());
    }

    @Test
    void promptInvocationPreservesBlocksOverridesAndTurnScopedCommandPermissions() {
        var qe = new FakeQueryEngine(List.of());
        var gate = new PermissionGate();
        qe.duringSubmit = () -> assertEquals(List.of("Read", "Edit"),
            gate.currentContext().rules().stream()
                .filter(rule -> rule.source() == RuleSource.COMMAND)
                .map(PermissionRule::toolName)
                .toList());
        var turnEngine = engine(qe, () -> gate, new RecordingSink(), new RecordingOps(),
            new ArrayList<>(), new ArrayList<>());
        MessageContent content = MessageContent.ofBlocks(List.of(new TextBlock("structured")));
        UserInput input = UserInput.forPrompt("/plugin:x", content, Map.of(), "default",
            "running", List.of("Read", "Edit(~/.claude/settings.json)"),
            "claude-sonnet-4-5", "medium", List.of(), false, false);

        turnEngine.submit(input);

        assertSame(content, qe.submittedPrompt);
        assertEquals("claude-sonnet-4-5", qe.submittedOptions.modelOverride());
        assertEquals("medium", qe.submittedOptions.effortOverride());
        assertTrue(qe.submittedOptions.isSlashCommand());
        assertTrue(gate.currentContext().rules().stream()
            .noneMatch(rule -> rule.source() == RuleSource.COMMAND),
            "command permissions must be removed at turn completion");
    }

    // ── isMeaningful (the auto-restore gate, moved out of SpinnerStateMachine) ──

    @Test
    void isMeaningful_countsToolUseAndTextStart_notOthers() {
        assertTrue(TurnEngine.isMeaningful(streamEvent("content_block_delta")), "assistant text start");
        assertTrue(TurnEngine.isMeaningful(streamEvent("tool_streaming_start")), "tool_use block start");
        assertFalse(TurnEngine.isMeaningful(streamEvent("tool_call_start")));
        assertFalse(TurnEngine.isMeaningful(streamEvent("content_block_stop")));
    }

    // ── Normal turn ─────────────────────────────────────────────────────────────

    @Test
    void normalTurn_emitsStartThenMessagesThenCompleteThenIdle() {
        var qe = new FakeQueryEngine(List.of(
            streamEvent("content_block_delta"), streamEvent("content_block_stop")));
        var sink = new RecordingSink();
        var submitted = new ArrayList<String>();
        var engine = engine(qe, sink, new RecordingOps(), new ArrayList<>(), submitted);

        engine.submit(input("hello"));

        assertEquals(List.of("start", "message", "message", "complete", "idle"), sink.events);
        assertEquals(List.of("hello"), submitted, "records the submitted text for undo");
        assertFalse(engine.isInFlight(), "released after the turn");
        TurnOutcome o = sink.lastCompletion();
        assertFalse(o.userCancel());
        assertFalse(o.restored());
    }

    @Test
    void taskNotificationTurnPreservesItsQuerySourceForTheModel() {
        var qe = new FakeQueryEngine(List.of());
        var engine = engine(qe, new RecordingSink(), new RecordingOps(),
            new ArrayList<>(), new ArrayList<>());

        engine.submit(input("<task-notification><summary>done</summary></task-notification>")
            .withQuerySource("task-notification"));

        assertEquals("task-notification", qe.submittedOptions.querySource());
    }

    @Test
    void interactiveStartupPromptCreatesInitialMessageSnapshotBeforeQuerySubmission() {
        var fileHistory = new FileHistoryManager(
            SessionIdentity.newRandom(), Path.of("."), Path.of("target", "test-file-history"));
        var qe = new FakeQueryEngine(List.of(), fileHistory);
        qe.duringSubmit = () -> assertEquals(1, fileHistory.snapshotsView().size(),
            "the argv initialMessage checkpoint must exist before DefaultQuerySession processes the prompt");
        var engine = engine(qe, new RecordingSink(), new RecordingOps(),
            new ArrayList<>(), new ArrayList<>());

        engine.submit(input("startup prompt").asInteractiveStartupPrompt());

        assertEquals(1, fileHistory.snapshotsView().size());
        assertFalse(StringUtils.isBlank(fileHistory.snapshotsView().getFirst().messageId()));
    }

    @Test
    void normalTurn_bracketsAwakeGuardAndClearsTurnScopedHooks() {
        var lifecycle = new ArrayList<String>();
        TurnAwakeGuard awake = new TurnAwakeGuard() {
            @Override public void preventSleep() { lifecycle.add("prevent"); }
            @Override public void allowSleep() { lifecycle.add("allow"); }
        };
        var engine = new TurnEngine(
            new FakeQueryEngine(List.of()), () -> null, new RecordingSink(), new RecordingOps(),
            _ -> {}, Runnable::run, Runnable::run, _ -> {}, awake,
            () -> lifecycle.add("clear-hooks"));

        engine.submit(input("hello"));

        assertEquals(List.of("prevent", "allow", "clear-hooks"), lifecycle);
    }

    @Test
    void completionPublishesPermissionModeChangedDuringTheTurn() {
        var gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        var qe = new FakeQueryEngine(List.of());

        qe.duringSubmit = () -> gate.setMode(PermissionMode.DEFAULT);
        var sink = new RecordingSink();
        var engine = engine(qe, () -> gate, sink, new RecordingOps(),
            new ArrayList<>(), new ArrayList<>());

        engine.submit(input("approve plan"));

        assertEquals("default", sink.lastCompletion().effectivePermissionMode());
    }

    @Test
    void concurrentSubmitIsRejectedWithoutStartingASecondTurn() {
        var pending = new ArrayList<Runnable>();
        var sink = new RecordingSink();
        var engine = new TurnEngine(
            new FakeQueryEngine(List.of()), () -> null, sink, new RecordingOps(),
            _ -> {}, Runnable::run, pending::add, _ -> {},
            TurnAwakeGuard.noop(), () -> {});

        engine.submit(input("first"));

        assertTrue(engine.isInFlight());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> engine.submit(input("second")));
        assertTrue(Strings.CS.contains(failure.getMessage(), "already in flight"));
        assertEquals(1, pending.size(), "only the first turn may reach the background executor");
        assertEquals(List.of("start"), sink.events, "the rejected turn must not echo");

        pending.getFirst().run();
        assertFalse(engine.isInFlight());
    }

    @Test
    void runWhenIdleDefersRewindUntilTurnCleanupAndBeforeQueueDrain() {
        var pending = new ArrayList<Runnable>();
        var ordering = new ArrayList<String>();
        var engine = new TurnEngine(
            new FakeQueryEngine(List.of()), () -> null, new RecordingSink(), new RecordingOps(),
            _ -> ordering.add("drain"), Runnable::run, pending::add, _ -> {},
            TurnAwakeGuard.noop(), () -> {});
        engine.enqueue(new QueuedCommand("later", Map.of()));

        engine.submit(input("first"));
        engine.runWhenIdle(() -> ordering.add("rewind"));

        assertEquals(List.of(), ordering);
        pending.getFirst().run();
        assertEquals(List.of("rewind", "drain"), ordering);
    }

    @Test
    void asyncIdleRewindHoldsQueuedPromptsUntilCompactionCompletes() {
        var pendingTurn = new ArrayList<Runnable>();
        var ordering = new ArrayList<String>();
        var compactFinished = new CompletableFuture<Void>();
        var engine = new TurnEngine(
            new FakeQueryEngine(List.of()), () -> null, new RecordingSink(), new RecordingOps(),
            _ -> ordering.add("drain"), Runnable::run, pendingTurn::add, _ -> {},
            TurnAwakeGuard.noop(), () -> {});
        engine.enqueue(new QueuedCommand("later", Map.of()));

        engine.submit(input("first"));
        engine.runWhenIdleAsync(() -> {
            ordering.add("compact-start");
            return compactFinished;
        });

        pendingTurn.getFirst().run();
        assertEquals(List.of("compact-start"), ordering);
        assertTrue(engine.isInFlight(),
            "the async rewind owns the session until its compact future completes");
        assertThrows(IllegalStateException.class, () -> engine.submit(input("racing prompt")));

        compactFinished.complete(null);

        assertEquals(List.of("compact-start", "drain"), ordering);
        assertFalse(engine.isInFlight());
    }

    @Test
    void idleOperationQueuedDuringDrainHandoffCannotPreemptTheDrainedPrompt() {
        var pendingTurn = new ArrayList<Runnable>();
        var ordering = new ArrayList<String>();
        var dispatchFailure = new AtomicReference<Throwable>();
        var engineRef = new AtomicReference<TurnEngine>();
        var engine = new TurnEngine(
            new FakeQueryEngine(List.of()), () -> null, new RecordingSink(), new RecordingOps(),
            _ -> {
                ordering.add("drain");
                engineRef.get().runWhenIdleAsync(() -> {
                    ordering.add("late-idle-operation");
                    return CompletableFuture.completedFuture(null);
                });
                try {
                    engineRef.get().submit(input("drained prompt"));
                } catch (Throwable failure) {
                    dispatchFailure.set(failure);
                }
            }, Runnable::run, pendingTurn::add, _ -> {},
            TurnAwakeGuard.noop(), () -> {});
        engineRef.set(engine);
        engine.enqueue(new QueuedCommand("later", Map.of()));

        engine.runWhenIdleAsync(() -> {
            ordering.add("initial-idle-operation");
            return CompletableFuture.completedFuture(null);
        });

        assertNull(dispatchFailure.get());
        assertEquals(List.of("initial-idle-operation", "drain"), ordering);
        assertEquals(1, pendingTurn.size());
        assertTrue(engine.isInFlight());

        pendingTurn.getFirst().run();

        assertEquals(List.of("initial-idle-operation", "drain", "late-idle-operation"), ordering);
        assertFalse(engine.isInFlight());
    }

    @Test
    void backgroundDispatchFailureReportsAndCompletesInsteadOfLeakingInFlight() {
        var lifecycle = new ArrayList<String>();
        var sink = new RecordingSink();
        var engine = new TurnEngine(
            new FakeQueryEngine(List.of()), () -> null, sink, new RecordingOps(),
            _ -> {}, Runnable::run,
            _ -> { throw new RejectedExecutionException("background unavailable"); },
            _ -> {}, new TurnAwakeGuard() {
                @Override public void preventSleep() { lifecycle.add("prevent"); }
                @Override public void allowSleep() { lifecycle.add("allow"); }
            }, () -> lifecycle.add("clear-hooks"));

        assertDoesNotThrow(() -> engine.submit(input("hello")));

        assertEquals(List.of("start", "error", "complete", "idle"), sink.events);
        assertInstanceOf(RejectedExecutionException.class, sink.lastError);
        assertEquals(List.of("prevent", "allow", "clear-hooks"), lifecycle);
        assertFalse(engine.isInFlight());
    }

    @Test
    void sinkCompletionFailureStillReleasesAndDrainsQueue() {
        var delegate = new RecordingSink();
        SessionSink throwingSink = new SessionSink() {
            @Override public void onTurnStart(UserInput input) { delegate.onTurnStart(input); }
            @Override public void onMessage(SDKMessage msg) { delegate.onMessage(msg); }
            @Override public void onError(Throwable error, boolean userCancel) {
                delegate.onError(error, userCancel);
            }
            @Override public void onTurnComplete(TurnOutcome outcome) {
                delegate.onTurnComplete(outcome);
                throw new IllegalStateException("renderer failed");
            }
            @Override public void onIdle() { delegate.onIdle(); }
        };
        var drained = new ArrayList<QueuedCommand>();
        var engine = engine(new FakeQueryEngine(List.of()), throwingSink,
            new RecordingOps(), drained, new ArrayList<>());
        var queued = new QueuedCommand("later", Map.of());
        engine.enqueue(queued);

        assertDoesNotThrow(() -> engine.submit(input("now")));

        assertEquals(List.of(queued), drained);
        assertFalse(engine.isInFlight());
        assertEquals(List.of("start", "complete"), delegate.events);
    }

    @Test
    void publishAndCleanupFailuresFallBackToInlineRelease() {
        var lifecycle = new ArrayList<String>();
        var sink = new RecordingSink();
        var engine = new TurnEngine(
            new FakeQueryEngine(List.of()), () -> null, sink, new RecordingOps(),
            _ -> {},
            _ -> { throw new RejectedExecutionException("publisher unavailable"); },
            Runnable::run, _ -> {}, new TurnAwakeGuard() {
                @Override public void preventSleep() { lifecycle.add("prevent"); }
                @Override public void allowSleep() {
                    lifecycle.add("allow");
                    throw new IllegalStateException("sleep adapter failed");
                }
            }, () -> {
                lifecycle.add("clear-hooks");
                throw new IllegalStateException("hook cleanup failed");
            });

        assertDoesNotThrow(() -> engine.submit(input("hello")));

        assertFalse(engine.isInFlight());
        assertEquals(List.of("prevent", "allow", "clear-hooks"), lifecycle);
        assertEquals(1, sink.idleCount, "fallback cleanup must still publish idle");
    }

    // ── Interrupt auto-restore ───────────────────────────────────────────────────

    @Test
    void userCancel_withNoMeaningfulContent_restoresAndRewinds() {
        var qe = new FakeQueryEngine(List.of());
        qe.conversation().loadMessages(List.of(
            new UserMessage("u-hi", MessageContent.ofText("hi"))));
        qe.getAbortController().abort("user-cancel");   // simulate Esc mid-turn
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        ops.toReturn = new UserMessage("u-hi", MessageContent.ofText("hi"));
        var engine = engine(qe, sink, ops, new ArrayList<>(), new ArrayList<>());

        engine.submit(input("hi"));

        TurnOutcome o = sink.lastCompletion();
        assertTrue(o.userCancel());
        assertTrue(o.restored(), "user-cancel + no meaningful + empty queue → restore");
        assertEquals("hi", o.restoredInput());
        assertFalse(o.refusalFallbackEdit(),
            "ordinary Esc remains distinct from the refusal fallback abort reason");
        assertEquals(1, ops.dropped, "dropped the just-added history entry");
        assertEquals(1, ops.rewound, "rewound the conversation");
    }

    @Test
    void refusalFallbackEdit_restoresThePromptWithoutClaimingAnInterruption() {
        var qe = new FakeQueryEngine(List.of());
        qe.conversation().loadMessages(List.of(
            new UserMessage("u-hi", MessageContent.ofText("hi"))));
        qe.getAbortController().abort("refusal-fallback-edit");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        ops.toReturn = new UserMessage("u-hi", MessageContent.ofText("hi"));
        var engine = engine(qe, sink, ops, new ArrayList<>(), new ArrayList<>());

        engine.submit(input("hi"));

        TurnOutcome o = sink.lastCompletion();
        assertTrue(o.userCancel(), "released takes the same restore path as Esc for this reason");
        assertTrue(o.restored());
        assertEquals("hi", o.restoredInput());
        assertTrue(o.refusalFallbackEdit(),
            "the front-end must not paint an interrupted row: released suppressed the "
                + "transcript message that renders it");
        assertEquals(1, ops.dropped);
        assertEquals(1, ops.rewound);
    }

    @Test
    void refusalFallbackEdit_afterStreamedTextStillRestoresBecauseTheRowWasWithdrawn() {
        var qe = new FakeQueryEngine(List.of(
            streamEvent("content_block_delta"),          // the refused model started answering
            new SDKMessage.Tombstone("assistant-1")));   // …and the refusal sweep took it back
        qe.conversation().loadMessages(List.of(
            new UserMessage("u-hi", MessageContent.ofText("hi"))));
        qe.getAbortController().abort("refusal-fallback-edit");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        ops.toReturn = new UserMessage("u-hi", MessageContent.ofText("hi"));
        var engine = engine(qe, sink, ops, new ArrayList<>(), new ArrayList<>());

        engine.submit(input("hi"));

        TurnOutcome o = sink.lastCompletion();
        assertTrue(o.restored(),
            "a withdrawn row is no longer a meaningful response, so the prompt comes back");
        assertEquals("hi", o.restoredInput());
    }

    @Test
    void userCancel_withMeaningfulContent_doesNotRestore() {
        var qe = new FakeQueryEngine(List.of(streamEvent("content_block_delta"))); // meaningful arrived
        qe.getAbortController().abort("user-cancel");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        var engine = engine(qe, sink, ops, new ArrayList<>(), new ArrayList<>());

        engine.submit(input("hi"));

        TurnOutcome o = sink.lastCompletion();
        assertTrue(o.userCancel());
        assertFalse(o.restored(), "meaningful content means no auto-restore");
        assertEquals(0, ops.dropped);
        assertEquals(0, ops.rewound);
    }

    @Test
    void autoRestoreUsesFinalSyntheticTailInsteadOfStreamTelemetry() {
        var qe = new FakeQueryEngine(List.of(streamEvent("content_block_delta")));
        qe.conversation().loadMessages(List.of(
            new UserMessage("u-hi", MessageContent.ofText("hi"))));
        qe.getAbortController().abort("user-cancel");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        ops.toReturn = new UserMessage("u-hi", MessageContent.ofText("hi"));
        var engine = engine(qe, sink, ops, new ArrayList<>(), new ArrayList<>());

        engine.submit(input("hi"));

        assertTrue(sink.lastCompletion().restored(),
            "197 rechecks the live message tail; withdrawn/non-persisted stream telemetry is irrelevant");
    }

    @Test
    void autoRestoreIsBlockedByMeaningfulAssistantContentInFinalMessages() {
        var qe = new FakeQueryEngine(List.of());
        qe.conversation().loadMessages(List.of(
            new UserMessage("u-hi", MessageContent.ofText("hi")),
            new AssistantMessage("a-hi", AssistantContent.of(List.of(new TextBlock("answer"))))));
        qe.getAbortController().abort("user-cancel");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        var engine = engine(qe, sink, ops, new ArrayList<>(), new ArrayList<>());

        engine.submit(input("hi"));

        assertFalse(sink.lastCompletion().restored());
        assertEquals(0, ops.rewound);
    }

    @Test
    void emptyTextPromptStillAutoRestoresWhenTheFinalUserMessageIsSelectable() {
        var qe = new FakeQueryEngine(List.of());
        qe.conversation().loadMessages(List.of(
            new UserMessage("u-empty", MessageContent.ofText(""))));
        qe.getAbortController().abort("user-cancel");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        ops.toReturn = new UserMessage("u-empty", MessageContent.ofText(""));
        var engine = engine(qe, sink, ops, new ArrayList<>(), new ArrayList<>());

        engine.submit(input(""));

        assertTrue(sink.lastCompletion().restored());
        assertEquals("", sink.lastCompletion().restoredInput());
    }

    @Test
    void autoRestoreUsesTheRewoundMessageInsteadOfTheTurnSubmissionDisplayText() {
        UserMessage selected = new UserMessage(
            "u-later", MessageContent.ofText("later queued prompt"));
        var qe = new FakeQueryEngine(List.of());
        qe.conversation().loadMessages(List.of(selected));
        qe.getAbortController().abort("user-cancel");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        ops.toReturn = selected;
        var engine = engine(qe, sink, ops, new ArrayList<>(), new ArrayList<>());

        engine.submit(input("first prompt in the submitted batch"));

        assertTrue(sink.lastCompletion().restored());
        assertEquals("later queued prompt", sink.lastCompletion().restoredInput(),
            "2.1.197 restores the last selectable message it actually rewound");
    }

    @Test
    void permissionReject_isDistinctFromEscAndNeverRestoresThePrompt() {
        var qe = new FakeQueryEngine(List.of(streamEvent("tool_streaming_start")));
        qe.getAbortController().abort("user_reject_permission");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        var engine = engine(qe, sink, ops, new ArrayList<>(), new ArrayList<>());

        engine.submit(input("hi"));

        TurnOutcome o = sink.lastCompletion();
        assertFalse(o.userCancel());
        assertTrue(o.permissionRejected());
        assertFalse(o.restored(), "permission rejection interrupts the tool turn but does not restore the submitted prompt");
        assertEquals(0, ops.dropped);
        assertEquals(0, ops.rewound);
    }

    // ── Queue drain ──────────────────────────────────────────────────────────────

    @Test
    void queuedCommand_drainsThroughOnDrainAfterTurn() {
        var qe = new FakeQueryEngine(List.of());
        var sink = new RecordingSink();
        var drained = new ArrayList<QueuedCommand>();
        var engine = engine(qe, sink, new RecordingOps(), drained, new ArrayList<>());
        var queued = new QueuedCommand("later", Map.of());

        engine.enqueue(queued);
        assertEquals(1, engine.countQueued(_ -> true));
        engine.submit(input("now"));

        assertEquals(List.of(queued), drained, "the queued command is routed to onDrain after the turn");
        assertEquals(0, sink.idleCount, "not idle — a queued command was drained");
    }

    @Test
    void drainIfIdle_skipsSubAgentCommand_andLeavesItQueued() {
        // (agentId routing): the REPL's boundary drain must NOT pull a
        // command addressed to a sub-agent (non-null agentId) into the main
        // session — it must stay queued for that sub-agent's own loop to take.

        var qe = new FakeQueryEngine(List.of());
        var drained = new ArrayList<QueuedCommand>();
        var engine = engine(qe, new RecordingSink(), new RecordingOps(), drained, new ArrayList<>());
        var subCmd = new QueuedCommand("notify", null, "task-notification",
            QueuePriority.LATER, true, null, false, false, null, null, "sub-agent-7");

        qe.getMessageQueue().enqueue(subCmd);
        engine.drainIfIdle();

        assertTrue(drained.isEmpty(), "REPL must not drain a sub-agent command");
        QueuedCommand stillQueued = qe.getMessageQueue().dequeue(cmd -> Strings.CS.equals("sub-agent-7", cmd.agentId()));
        assertEquals(subCmd, stillQueued, "sub-agent command remains in the queue for its own engine");
    }

    @Test
    void drainIfIdle_takesMainThreadCommand_asBefore() {
        // Regression guard: a command with agentId == null (the coordinator)
        // is still drained by the REPL exactly as before.
        var qe = new FakeQueryEngine(List.of());
        var drained = new ArrayList<QueuedCommand>();
        var engine = engine(qe, new RecordingSink(), new RecordingOps(), drained, new ArrayList<>());
        var mainCmd = new QueuedCommand("hi", null, "prompt",
            QueuePriority.NEXT, false, null, false, false, null, null, null);

        qe.getMessageQueue().enqueue(mainCmd);
        engine.drainIfIdle();

        assertEquals(List.of(mainCmd), drained, "REPL still drains coordinator (agentId==null) commands");
    }



    @Test
    void idleWakeup_drainsACommandEnqueuedWhileTheReplSitsIdle() {
        // The reported gap: a background agent finishing AFTER the dispatching turn
        // ended enqueues its <task_notification> into an idle session. Without the
        // queue subscription nothing consumes it until the user types.
        var qe = new FakeQueryEngine(List.of());
        var drained = new ArrayList<QueuedCommand>();
        var engine = engine(qe, new RecordingSink(), new RecordingOps(), drained, new ArrayList<>());
        engine.bindIdleQueueWakeup(() -> false);
        var notification = new QueuedCommand("agent done", null, "task-notification",
            QueuePriority.LATER, true, null, false, false, null, null, null);

        qe.getMessageQueue().enqueuePendingNotification(notification);

        assertEquals(1, drained.size(), "the enqueue itself must wake the idle REPL");
        assertEquals("agent done", drained.getFirst().text());
    }

    @Test
    void idleWakeup_isSuppressedWhileAnExternalLongRunningCommandOwnsTheTranscript() {

        var qe = new FakeQueryEngine(List.of());
        var drained = new ArrayList<QueuedCommand>();
        var engine = engine(qe, new RecordingSink(), new RecordingOps(), drained, new ArrayList<>());
        engine.bindIdleQueueWakeup(() -> true);

        qe.getMessageQueue().enqueuePendingNotification(
            QueuedCommand.notification("agent done"));

        assertTrue(drained.isEmpty(), "a busy external command blocks the idle wake-up");
        assertNotNull(qe.getMessageQueue().dequeue(cmd -> cmd.agentId() == null),
            "the command stays queued for the next drain edge");
    }

    @Test
    void idleWakeup_leavesASubAgentCommandForItsOwnLoop() {
        var qe = new FakeQueryEngine(List.of());
        var drained = new ArrayList<QueuedCommand>();
        var engine = engine(qe, new RecordingSink(), new RecordingOps(), drained, new ArrayList<>());
        engine.bindIdleQueueWakeup(() -> false);
        var subCmd = new QueuedCommand("notify", null, "task-notification",
            QueuePriority.LATER, true, null, false, false, null, null, "sub-agent-7");

        qe.getMessageQueue().enqueuePendingNotification(subCmd);

        assertTrue(drained.isEmpty(), "the wake-up honors the main-thread (agentId==null) filter");
    }

    @Test
    void idleWakeup_doesNotDoubleDrainACommandEnqueuedDuringATurn() {
        // A background agent that finishes WHILE the turn is still running must be
        // picked up exactly once, by the turn-boundary drain — not also by a
        // re-entrant wake-up firing from inside the in-flight turn.
        var qe = new FakeQueryEngine(List.of());
        var drained = new ArrayList<QueuedCommand>();
        var engine = engine(qe, new RecordingSink(), new RecordingOps(), drained, new ArrayList<>());
        engine.bindIdleQueueWakeup(() -> false);
        qe.duringSubmit = () -> qe.getMessageQueue().enqueuePendingNotification(
            QueuedCommand.notification("agent done"));

        engine.submit(input("hi"));

        assertEquals(1, drained.size(), "drained exactly once");
        assertEquals(0, qe.getMessageQueue().size(), "and nothing is left stranded");
    }



    private static QueuedCommand cmd(String text, String mode) {
        return new QueuedCommand(text, null, mode,
            QueuePriority.LATER, false, null, false, false, null, null, null);
    }

    @Test
    void batchDrain_collapsesEverySameModeCommandIntoOneTurn() {

        // && !isSlashCommand(cmd) && cmd.mode === targetMode) → executeInput(commands).
        // Three agents finishing at once must produce ONE turn, not three.
        var qe = new FakeQueryEngine(List.of());
        var batches = new ArrayList<List<QueuedCommand>>();
        var engine = batchingEngine(qe, batches);
        qe.getMessageQueue().enqueue(cmd("a done", "task-notification"));
        qe.getMessageQueue().enqueue(cmd("b done", "task-notification"));
        qe.getMessageQueue().enqueue(cmd("c done", "task-notification"));

        engine.drainIfIdle();

        assertEquals(1, batches.size(), "one batch, one turn");
        assertEquals(List.of("a done", "b done", "c done"),
            batches.getFirst().stream().map(QueuedCommand::text).toList(),
            "dequeueAllMatching preserves insertion order");
        assertEquals(0, qe.getMessageQueue().size());
    }

    @Test
    void batchDrain_neverMixesModes() {

        // downstream". The head command's mode is the batching key.
        var qe = new FakeQueryEngine(List.of());
        var batches = new ArrayList<List<QueuedCommand>>();
        var engine = batchingEngine(qe, batches);
        qe.getMessageQueue().enqueue(cmd("first", "prompt"));
        qe.getMessageQueue().enqueue(cmd("notify", "task-notification"));
        qe.getMessageQueue().enqueue(cmd("second", "prompt"));

        engine.drainIfIdle();

        assertEquals(List.of(List.of("first", "second")),
            batches.stream().map(b -> b.stream().map(QueuedCommand::text).toList()).toList());
        assertEquals(1, qe.getMessageQueue().size(), "the other mode waits for its own turn");
    }

    @Test
    void batchDrain_takesABashCommandAlone() {

        // isolation, exit codes, and progress UI".
        var qe = new FakeQueryEngine(List.of());
        var batches = new ArrayList<List<QueuedCommand>>();
        var engine = batchingEngine(qe, batches);
        qe.getMessageQueue().enqueue(cmd("ls", "bash"));
        qe.getMessageQueue().enqueue(cmd("pwd", "bash"));

        engine.drainIfIdle();

        assertEquals(List.of(List.of("ls")),
            batches.stream().map(b -> b.stream().map(QueuedCommand::text).toList()).toList());
        assertEquals(1, qe.getMessageQueue().size());
    }

    @Test
    void batchDrain_takesASlashCommandAlone() {

        var qe = new FakeQueryEngine(List.of());
        var batches = new ArrayList<List<QueuedCommand>>();
        var engine = batchingEngine(qe, batches);
        qe.getMessageQueue().enqueue(cmd("/compact", "prompt"));
        qe.getMessageQueue().enqueue(cmd("hello", "prompt"));

        engine.drainIfIdle();

        assertEquals(List.of(List.of("/compact")),
            batches.stream().map(b -> b.stream().map(QueuedCommand::text).toList()).toList());
        assertEquals(1, qe.getMessageQueue().size(), "the plain prompt is not swept up with the slash command");
    }

    @Test
    void batchDrain_skipsASlashCommandSittingBehindThePlainHead() {
        // The batch predicate excludes slash commands even when they share the head's
        // mode, so a queued `/foo` keeps its own turn.
        var qe = new FakeQueryEngine(List.of());
        var batches = new ArrayList<List<QueuedCommand>>();
        var engine = batchingEngine(qe, batches);
        qe.getMessageQueue().enqueue(cmd("hello", "prompt"));
        qe.getMessageQueue().enqueue(cmd("/compact", "prompt"));
        qe.getMessageQueue().enqueue(cmd("world", "prompt"));

        engine.drainIfIdle();

        assertEquals(List.of(List.of("hello", "world")),
            batches.stream().map(b -> b.stream().map(QueuedCommand::text).toList()).toList());
        assertEquals(1, qe.getMessageQueue().size());
    }

    @Test
    void batchDrain_leavesSubAgentCommandsOutOfAMainThreadBatch() {
        var qe = new FakeQueryEngine(List.of());
        var batches = new ArrayList<List<QueuedCommand>>();
        var engine = batchingEngine(qe, batches);
        qe.getMessageQueue().enqueue(cmd("main", "task-notification"));
        qe.getMessageQueue().enqueue(new QueuedCommand("sub", null, "task-notification",
            QueuePriority.LATER, false, null, false, false, null, null, "sub-agent-7"));

        engine.drainIfIdle();

        assertEquals(List.of(List.of("main")),
            batches.stream().map(b -> b.stream().map(QueuedCommand::text).toList()).toList());
        assertEquals(1, qe.getMessageQueue().size(), "the sub-agent command stays for its own loop");
    }

    @Test
    void batchDrain_appliesToTheAdapterInputQueueToo() {
        // The UI-side inputQueue (TurnEngine.enqueue) follows the same rule so a burst of
        // prompts typed while a turn ran becomes one turn.
        var qe = new FakeQueryEngine(List.of());
        var batches = new ArrayList<List<QueuedCommand>>();
        var engine = batchingEngine(qe, batches);
        engine.enqueue(cmd("one", "prompt"));
        engine.enqueue(cmd("two", "prompt"));

        engine.drainIfIdle();

        assertEquals(List.of(List.of("one", "two")),
            batches.stream().map(b -> b.stream().map(QueuedCommand::text).toList()).toList());
        assertEquals(0, engine.countQueued(_ -> true));
    }

    @Test
    void userCancel_withQueuedCommand_suppressesRestore() {
        var qe = new FakeQueryEngine(List.of());
        qe.conversation().loadMessages(List.of(
            new UserMessage("u-hi", MessageContent.ofText("hi"))));
        qe.getAbortController().abort("user-cancel");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        var engine = engine(qe, sink, ops, new ArrayList<>(), new ArrayList<>());
        engine.enqueue(new QueuedCommand("queued", Map.of()));

        engine.submit(input("hi"));

        assertFalse(sink.lastCompletion().restored(), "a non-empty queue blocks auto-restore");
        assertEquals(0, ops.dropped);
    }

    @Test
    void userCancel_withNonEmptyInputLeavesTheConversationUntouchedWithoutSalvage() {

        // Typed text during loading is not clobbered. Released does not add another prompt-history
        // row when this guard blocks its normal auto-restore path.
        var qe = new FakeQueryEngine(List.of());
        qe.conversation().loadMessages(List.of(
            new UserMessage("u-hi", MessageContent.ofText("hi"))));
        qe.getAbortController().abort("user-cancel");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        ops.toReturn = new UserMessage("u-hi", MessageContent.ofText("hi"));
        var engine = new TurnEngine(
            qe, () -> null, sink, ops,
            _ -> {}, Runnable::run, Runnable::run, _ -> {},
            TurnAwakeGuard.noop(), () -> {},
            () -> false,   // input box NOT empty (user typed during loading)
            () -> false);

        engine.submit(input("hi"));

        TurnOutcome o = sink.lastCompletion();
        assertTrue(o.userCancel());
        assertFalse(o.restored(), "non-empty input blocks the auto-restore");
        assertFalse(o.restoreEligible());
        assertNull(o.restoredInput());
        assertEquals(0, ops.dropped, "history entry kept — rewind skipped");
        assertEquals(0, ops.rewound, "conversation kept — rewind skipped");
    }

    @Test
    void userCancel_whileViewingAgentTaskLeavesTheConversationUntouchedWithoutSalvage() {

        // at a teammate's transcript, so rewinding the main conversation behind their
        // back would corrupt what they see.
        var qe = new FakeQueryEngine(List.of());
        qe.conversation().loadMessages(List.of(
            new UserMessage("u-hi", MessageContent.ofText("hi"))));
        qe.getAbortController().abort("user-cancel");
        var sink = new RecordingSink();
        var ops = new RecordingOps();
        var engine = new TurnEngine(
            qe, () -> null, sink, ops,
            _ -> {}, Runnable::run, Runnable::run, _ -> {},
            TurnAwakeGuard.noop(), () -> {},
            () -> true,    // input box empty
            () -> true);   // viewing an agent task

        engine.submit(input("hi"));

        TurnOutcome o = sink.lastCompletion();
        assertTrue(o.userCancel());
        assertFalse(o.restored(), "viewing an agent task blocks the auto-restore");
        assertFalse(o.restoreEligible());
        assertNull(o.restoredInput());
        assertEquals(0, ops.dropped);
        assertEquals(0, ops.rewound);
    }

    @Test
    void userCancel_withMeaningfulContent_isNotRestoreEligible() {
        // A non-guard suppression reason (meaningful content arrived) must NOT be
        // flagged salvageable — there is nothing worth restoring into the history.
        var qe = new FakeQueryEngine(List.of(streamEvent("content_block_delta")));
        qe.getAbortController().abort("user-cancel");
        var sink = new RecordingSink();
        var engine = engine(qe, sink, new RecordingOps(), new ArrayList<>(), new ArrayList<>());

        engine.submit(input("hi"));

        TurnOutcome o = sink.lastCompletion();
        assertFalse(o.restored());
        assertFalse(o.restoreEligible(), "meaningful-content suppression is not salvageable");
        assertNull(o.restoredInput());
    }
}
