package com.claudecode.runtime.turn;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.message.HumanTurns;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.runtime.query.QuerySession;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless orchestrator for one session's turns — the front-end-agnostic core that every adapter
 * (TUI, and future WebUI / API) drives.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code utils/handlePromptSubmit.ts} — the busy-turn queue-steer branch: reading
 *       {@code hasInterruptibleToolInProgress} and aborting with reason {@code 'interrupt'}
 *       before enqueuing ({@link #hasInterruptibleToolInProgress},
 *       {@link #interruptForQueuedSubmit}).</li>
 *   <li>{@code utils/messageQueueManager.ts} — the single unified command queue: every
 *       source (user input, task notifications, SDK) lands in the same session queue, so
 *       the mid-turn drain in {@code query.ts} sees user prompts as queued-command
 *       attachments and the between-turn {@code utils/queueProcessor.ts} drain orders
 *       strictly by priority ({@link #enqueue}, {@link #takeNextBatch},
 *       {@link #popAllEditable}).</li>
 *   <li>{@code screens/REPL.tsx} — the queue effect that aborts the in-flight turn
 *       whenever a {@code priority:'now'} entry is present in the queue (the
 *       subscription registered by this engine, see {@link #enqueue}).</li>
 * </ul>
 */
public final class TurnEngine {

    private static final Logger log = LoggerFactory.getLogger(TurnEngine.class);

    private final QuerySession queryEngine;
    private final Supplier<PermissionGate> permissionGate;
    private final SessionSink sink;
    private final ConversationOps conversation;
    /** Routes a drained batch of queued commands back through the adapter's parse/submit path
     *  (they may be bash-wrapped or a slash command). One batch = one turn; see
     *  {@link #takeNextBatch}. */
    private final Consumer<List<QueuedCommand>> onDrain;
    /** Publish executor for the post-turn continuation (prod TUI: GUI thread; test/web: direct). */
    private final Consumer<Runnable> onUi;
    /** Runs the blocking query loop off the caller thread (prod: one virtual thread per turn). */
    private final Executor background;
/** Records the submitted display text for the adapter's undo (matches {@code lastSubmittedInput}). */
    private final Consumer<String> recordLastSubmitted;
    /** Platform adapter for the active-turn sleep-prevention lifecycle. */
    private final TurnAwakeGuard awakeGuard;
    /** Assembly callback for clearing service-owned, turn-scoped hooks. */
    private final Runnable clearTurnScopedHooks;

    private final BooleanSupplier inputEmptyForRestore;
    private final BooleanSupplier viewingAgentTask;

    private final AtomicBoolean turnInFlight = new AtomicBoolean(false);
    /**
     * True only while a submitted turn's actual query/tool execution is running — the span in
     * which an abort request (Ctrl+C) can meaningfully act on something. Unlike
     * {@link #turnInFlight}, this does NOT cover the post-turn idle tail (deferred
     * rewind/compact), so a Ctrl+C pressed during that tail falls through instead of firing a
     * no-op abort and swallowing the keystroke.
     */
    private final AtomicBoolean realTurnActive = new AtomicBoolean(false);
    /**
     * Re-entrancy latch for {@link #drainIfIdle}. The queue notifies its listeners
     * from inside its own {@code dequeue}, so the wake-up subscription can fire while
     * a drain is mid-flight; taking a second command there would submit it ahead of
     * the one the outer drain is still about to dispatch.
     */
    private final AtomicBoolean draining = new AtomicBoolean(false);
    /** Operations such as message-action rewind that must run after the active stream has fully
     *  unwound, but before a queued prompt starts the next turn. */
    private final ConcurrentLinkedQueue<Supplier<? extends CompletionStage<?>>> idleOperations =
        new ConcurrentLinkedQueue<>();
    /** Prevents an idle operation from claiming the session while ownership is being handed to a
     *  drained prompt. Nested release is possible when a completed stage publishes inline. */
    private final AtomicInteger idleReleaseDepth = new AtomicInteger();
    /** Reactive queue snapshot consumed by prompt-area previews. */
    private volatile Consumer<List<QueuedCommand>> inputQueueListener = _ -> {};

    public TurnEngine(QuerySession queryEngine, Supplier<PermissionGate> permissionGate,
                      SessionSink sink, ConversationOps conversation,
                      Consumer<List<QueuedCommand>> onDrain, Consumer<Runnable> onUi,
                      Executor background, Consumer<String> recordLastSubmitted,
                      TurnAwakeGuard awakeGuard, Runnable clearTurnScopedHooks) {
        this(queryEngine, permissionGate, sink, conversation, onDrain, onUi, background,
            recordLastSubmitted, awakeGuard, clearTurnScopedHooks,
            () -> true, () -> false);
    }

    public TurnEngine(QuerySession queryEngine, Supplier<PermissionGate> permissionGate,
                      SessionSink sink, ConversationOps conversation,
                      Consumer<List<QueuedCommand>> onDrain, Consumer<Runnable> onUi,
                      Executor background, Consumer<String> recordLastSubmitted,
                      TurnAwakeGuard awakeGuard, Runnable clearTurnScopedHooks,
                      BooleanSupplier inputEmptyForRestore, BooleanSupplier viewingAgentTask) {
        this.queryEngine = queryEngine;
        this.permissionGate = permissionGate;
        this.sink = sink;
        this.conversation = conversation;
        this.onDrain = onDrain;
        this.onUi = onUi;
        this.background = background;
        this.recordLastSubmitted = recordLastSubmitted;
        this.awakeGuard = awakeGuard;
        this.clearTurnScopedHooks = clearTurnScopedHooks;
        this.inputEmptyForRestore = inputEmptyForRestore;
        this.viewingAgentTask = viewingAgentTask;
        // The REPL.tsx queue effect: any queue mutation re-publishes the prompt-area
        // projection, and a 'now'-priority entry aborts the in-flight turn — whenever
        // it is observed, not only at its own enqueue instant.
        queue().addListener(() -> {
            publishInputQueue();
            if (!turnInFlight.get()) return;
            for (QueuedCommand queued : queue().snapshot()) {
                if (queued.priority() == QueuePriority.NOW) {
                    interruptForQueuedSubmit();
                    return;
                }
            }
        });
    }

    /** The session's single unified command queue (user input, notifications, SDK). */
    private MessageQueueManager queue() {
        return queryEngine.conversation().getMessageQueue();
    }

    // ── Input port ────────────────────────────────────────────────────────────

    /** Whether a turn or an exclusive deferred idle operation is currently running. */
    public boolean isInFlight() { return turnInFlight.get(); }

    /**
     * Whether a submitted turn's actual query/tool execution is running right now — narrower
     * than {@link #isInFlight()}, which stays true through the post-turn idle tail. Interrupt
     * gestures (Ctrl+C) should test this instead of {@link #isInFlight()}, so they fall through
     * to their next fallback rather than firing a no-op abort during the idle tail.
     */
    public boolean hasActiveTurn() { return realTurnActive.get(); }

    /**
     * Whether the active turn currently executes only mid-turn-steerable (CANCEL)
     * tools — the condition under which a queued submission may abort the turn
     * immediately instead of waiting for its completion (the queue-steer path).
     * False when idle.
     */
    public boolean hasInterruptibleToolInProgress() {
        try {
            return queryEngine.execution().hasInterruptibleToolInProgress();
        } catch (RuntimeException e) {
            log.warn("Failed to read the mid-turn steer flag; treating as not steerable", e);
            return false;
        }
    }

    /**
     * Aborts the active turn so a just-enqueued submission can steer it — the
     * runtime twin of the submit path's {@code hasInterruptibleToolInProgress →
     * abortController.abort('interrupt')} branch. The reason is {@code "interrupt"}
     * (silent, no visible interruption row) and only CANCEL-declared tools are
     * cancelled; BLOCK tools run to completion first. No-op when no turn is in
     * flight.
     */
    public void interruptForQueuedSubmit() {
        if (!turnInFlight.get()) return;
        log.debug("[STEER] Aborting the active turn for a queued submission (reason=interrupt)");
        queryEngine.submission().softInterrupt();
    }

    /**
     * Runs an operation immediately when idle, or after the active turn's cleanup and before queue
     * drain. The second idle check closes the race where completion occurs between the first check
     * and enqueue.
     */
    public void runWhenIdle(Runnable operation) {
        if (operation == null) return;
        runWhenIdleAsync(() -> {
            operation.run();
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Runs an asynchronous operation exclusively after the active turn has fully unwound. The
     * session remains in-flight until the returned stage completes, so queued prompts cannot start
     * while a rewind/compact operation is still replacing conversation state.
     */
    public void runWhenIdleAsync(Supplier<? extends CompletionStage<?>> operation) {
        if (operation == null) return;
        idleOperations.offer(operation);
        if (idleReleaseDepth.get() == 0) startIdleOperationsIfIdle();
    }

    /**
     * Add a command to the session's unified command queue — the same queue the
     * mid-turn drain ({@code QueryHelpers.drainQueuedCommands}) reads, so a prompt
     * submitted while a turn runs becomes visible to the model as a queued-command
     * attachment in the current turn, exactly like the TS {@code enqueue}. The
     * queue subscription registered in the constructor republishes the preview and
     * applies the {@code priority:'now'} abort effect.
     */
    public void enqueue(QueuedCommand cmd) {
        if (cmd == null) return;
        queue().enqueue(cmd);
    }

    /** Install the adapter's live queue projection and immediately publish its current state. */
    public void setInputQueueListener(Consumer<List<QueuedCommand>> listener) {
        inputQueueListener = listener != null ? listener : _ -> {};
        publishInputQueue();
    }

    /**
     * Pull every human-editable command back into one prompt draft. Meta commands
     * and task notifications remain queued for automatic processing.
     */
    public QueuedInputDraft popAllEditable(String currentInput, int currentCursorOffset) {
        String draft = currentInput == null ? "" : currentInput;
        List<QueuedCommand> editable = queue().dequeueAllMatching(TurnEngine::isQueuedCommandEditable);
        if (editable.isEmpty()) return null;

        List<String> queuedTexts = editable.stream().map(QueuedCommand::text).toList();
        List<String> nonEmptyParts = new ArrayList<>(queuedTexts.size() + 1);
        queuedTexts.stream().filter(text -> !text.isEmpty()).forEach(nonEmptyParts::add);
        if (!draft.isEmpty()) nonEmptyParts.add(draft);
        String text = String.join("\n", nonEmptyParts);
        int cursorOffset = String.join("\n", queuedTexts).length()
            + 1 + Math.max(0, currentCursorOffset);

        Map<Integer, PastedContent> images = new LinkedHashMap<>();
        for (QueuedCommand command : editable) {
            if (command.pastedContents() == null) continue;
            command.pastedContents().forEach((id, content) -> {
                if (content != null && content.isImage()) images.put(id, content);
            });
        }

        return new QueuedInputDraft(text, cursorOffset, images);
    }

    /** Count queued commands matching {@code p} — for the adapter's task-notification overflow logic. */
    public long countQueued(Predicate<QueuedCommand> p) {
        return queue().snapshot().stream().filter(p).count();
    }

    /**
     * Drain the next batch of queued commands if no turn is in flight — the same
     * queue tail {@link #completeTurn} runs at turn end, exposed for non-turn busy
     * periods (a background long-running slash command like {@code /compact}) whose
     * completion must also kick the queue. Each drained batch re-submits and drains
     * the next at its own completion, so one poll here is enough. See
     * {@link #takeNextBatch} for the batching rule. Must be called on the UI thread
     * (the drain callback re-enters {@code executeQueuedCommand}).
     */
    public void drainIfIdle() {
        if (turnInFlight.get()) return;
        if (!draining.compareAndSet(false, true)) return;
        try {
            List<QueuedCommand> batch = takeNextBatch();
            if (!batch.isEmpty()) onDrain.accept(batch);
        } finally {
            draining.set(false);
        }
    }

    /**
     * Subscribe this engine to its session command queue so that anything enqueued while the REPL sits
     * idle immediately wakes a turn.
     */
    public void bindIdleQueueWakeup(BooleanSupplier externallyBusy) {
        BooleanSupplier busy = externallyBusy != null ? externallyBusy : () -> false;
        queue().addListener(() -> {
            if (turnInFlight.get()) return;
            try {
                onUi.accept(() -> {
                    if (busy.getAsBoolean()) return;
                    drainIfIdle();
                });
            } catch (RuntimeException e) {
                log.warn("Failed to schedule the idle queue drain", e);
            }
        });
    }

    /**
     * Execute a turn now. Echoes {@code input} synchronously (via
     * {@link SessionSink#onTurnStart}), then streams the query on {@link #background}.
     * Overlapping submissions are rejected atomically; adapters should enqueue instead.
     */
    public void submit(UserInput input) {
        if (!turnInFlight.compareAndSet(false, true)) {
            throw new IllegalStateException("turn already in flight; enqueue the command instead");
        }
        realTurnActive.set(true);

        long startMs = System.currentTimeMillis();
        try {
            awakeGuard.preventSleep();
            if (isHumanInput(input)) {
                recordLastSubmitted.accept(input.displayText()); // saved for interrupt auto-restore / undo
            }
            sink.onTurnStart(input);                          // synchronous echo on the caller thread

            PermissionGate gate = permissionGate.get();
            if (gate != null && !input.allowedTools().isEmpty()) {
                gate.addRules(commandRules(input.allowedTools()));
            }

            Map<Integer, PastedContent> pasted = input.pasted();
            SubmitOptions opts = (pasted.isEmpty()
                ? SubmitOptions.of(input.querySource())
                : SubmitOptions.withPastedContents(input.querySource(), pasted))
                .withPermissionMode(input.permissionMode())
                .withPromptOverrides(input.modelOverride(), input.effortOverride())
                .withPrecedingUserMessages(input.precedingUserMessages())
                .withAdditionalUserMessages(input.additionalUserMessages())
                .withPlanContent(input.planContent());

            // getMessagesForPromptSlashCommand (images first, isMeta:true).
            if (input.isSlashCommand()) {
                opts = opts.asSlashCommand();
            }
            if (input.isMeta()) {
                opts = opts.asMeta();
            }
            if (input.isSlashCommand() && !input.suppressCommandPermissions()) {
                opts = opts.withCommandPermissions(input.allowedTools(), input.modelOverride());
            }
            if (input.suppressInitialAttachments()) {
                opts = opts.withoutInitialAttachments();
            }
            final SubmitOptions finalOpts = opts;

            if (input.interactiveStartupPrompt()) {
                FileHistoryManager fileHistory = queryEngine.conversation().getFileHistoryManager();
                if (fileHistory != null) {
                    fileHistory.scheduleSnapshot(UUID.randomUUID().toString());
                }
            }

            background.execute(() -> runTurn(input, finalOpts, startMs));
        } catch (RuntimeException e) {
            log.warn("Turn setup or background dispatch failed", e);
            notifyError(e, false);
            completeTurn(startMs);
        }
    }

    // ── Turn body ───────────────────────────────────────────────────────────────

    private void runTurn(UserInput input, SubmitOptions opts, long startMs) {
        int renderFailures = 0;
        try {
            Iterator<SDKMessage> messages = queryEngine.submission()
                .submitMessage(input.queryContent(), opts);
            while (messages.hasNext()) {
                SDKMessage msg = messages.next();
                if (log.isDebugEnabled()) {
                    if (msg instanceof SDKMessage.StreamEvent(String eventType, Object data)) {
                        log.debug("[TURN] StreamEvent: {} | {}", eventType,
                            data instanceof String s
                                ? s.substring(0, Math.min(80, s.length())) : data);
                    } else {
                        log.debug("[TURN] msg: {}", msg.getClass().getSimpleName());
                    }
                }
                renderFailures = deliver(msg, renderFailures);
            }
        } catch (Exception e) {
            boolean isUserCancel = isUserCancel();
            if (!isUserCancel) log.warn("Query error", e);
            notifyError(e, isUserCancel);
        } catch (StackOverflowError | LinkageError | AssertionError fatal) {
            // Not reachable through `catch (Exception)`. A native-image build surfaces
            // a missing reflection/resource registration as a LinkageError, so on the
            // native binary this arm is the difference between a reported failure and
            // a turn that silently stops updating the screen.
            log.error("Turn aborted by a non-Exception failure", fatal);
            notifyError(fatal, false);
        } finally {
            if (renderFailures > 0) {
                log.warn("Turn finished with {} message(s) the sink failed to render",
                    renderFailures);
            }
            completeTurn(startMs);
        }
    }

    /**
     * Hands one message to the sink, keeping a rendering failure out of the query
     * path's {@code catch}.
     *
     * <p>The pump drains an iterator fed by the query loop on another thread. If a
     * sink failure escaped here it would leave the {@code while} loop and run
     * {@code completeTurn}: the UI would stop the spinner and freeze the transcript
     * while the query loop kept producing messages into a queue nobody reads — a
     * turn that is still running but invisible. A sink that cannot render one
     * message is never a reason to stop reading the rest, so failures are logged
     * and the message is dropped.
     *
     * <p>Interruption still propagates: cancelling a turn must not be mistaken for
     * a rendering bug. {@link LinkageError} and {@link AssertionError} are treated
     * as rendering failures rather than fatal ones because on a native-image build
     * a missing reflection or resource registration in the render path arrives as a
     * {@code LinkageError}; letting it end the pump is exactly the invisible-turn
     * failure this method exists to prevent. {@link OutOfMemoryError} and the other
     * {@link VirtualMachineError}s are not caught — those the JVM cannot continue past.
     *
     * @return the running failure count, used to log a per-turn summary and to keep
     *         a sink that fails on every message from flooding the log with stacks
     */
    private int deliver(SDKMessage msg, int priorFailures) {
        try {
            sink.onMessage(msg);
            return priorFailures;
        } catch (RuntimeException | StackOverflowError | LinkageError
                 | AssertionError renderFailure) {
            if (Thread.currentThread().isInterrupted()) throw renderFailure;
            int failures = priorFailures + 1;
            String type = msg.getClass().getSimpleName();
            if (failures == 1) {
                log.error("Session sink failed to render {}; dropping it and continuing the turn",
                    type, renderFailure);
            } else {
                log.warn("Session sink failed to render {} ({} failures this turn): {}",
                    type, failures, renderFailure.toString());
            }
            return failures;
        }
    }

    /**
     * Turn completion: interrupt auto-restore + rewind + permission-mode restore (domain,
     * here), then {@link SessionSink#onTurnComplete} (UI teardown, in the adapter), then the
     * post-turn continuation on {@link #onUi} — release in-flight, skill/hook cleanup, drain.
     * matches the ordering of the former {@code TurnExecutor.completeTurn}.
     */
    private void completeTurn(long startMs) {
        // The turn's actual query/tool execution has already unwound by the time we reach here
        // (this runs in runTurn's finally), so an abort request from this point on has nothing
        // left to interrupt — only the idle tail (below) remains.
        realTurnActive.set(false);
        long elapsed = System.currentTimeMillis() - startMs;
        boolean isUserCancel = isUserCancelSafely();
        boolean permissionRejected = isPermissionRejectedSafely();
        boolean refusalFallbackEdit = isRefusalFallbackEditSafely();
        String toRestore = null;
        boolean finalTailAllowsRestore = finalMessageTailAllowsAutoRestore();
        boolean shouldRestore = isUserCancel
            && finalTailAllowsRestore
            && !queue().hasCommands() // no queued commands (getCommandQueueLength === 0)
            && inputEmptyForRestore.getAsBoolean()
            && !viewingAgentTask.getAsBoolean();

        // 2.1.197 simply leaves the conversation untouched when a live UI-state guard blocks
        // auto-restore. It does not append a second prompt-history entry as a salvage path.
        boolean restoreEligible = false;

        Map<Integer, PastedContent> imageChips = Map.of();
        String restoredPermMode = null;
        if (shouldRestore) {
            try {
                // Undo the history entry added on submit.
                conversation.dropLastPromptHistoryEntry();
                // Capture image chips + permission mode from the removed message before it's gone.
                UserMessage rewoundMsg = conversation.rewindBeforeLastRealUser();
                toRestore = conversation.restoredInput(rewoundMsg);
                imageChips = PastedContent.imagesFromMessage(rewoundMsg);
                if (rewoundMsg != null && rewoundMsg.permissionMode() != null) {
                    restoredPermMode = rewoundMsg.permissionMode();
                    PermissionGate gate = permissionGate.get();
                    if (gate != null) gate.setMode(restoredPermMode); // domain; sink reflects it in widgets
                }
            } catch (RuntimeException e) {
                log.warn("Interrupt auto-restore failed; continuing turn cleanup", e);
            }
        }

        String effectivePermMode = restoredPermMode;
        try {
            PermissionGate gate = permissionGate.get();
            if (gate != null && gate.currentMode() != null) {
                effectivePermMode = gate.currentMode().kind().wireValue();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to snapshot post-turn permission mode", e);
        }

        // Deferred idle work (rewind/compact) queued via runWhenIdle/runWhenIdleAsync keeps
        // turnInFlight true past this point (see releaseAfterIdleOperations), so the sink must
        // know the turn isn't really over yet even though onTurnComplete fires now.
        boolean hasPendingIdleWork = !idleOperations.isEmpty();
        TurnOutcome outcome = new TurnOutcome(isUserCancel, shouldRestore, restoreEligible,
            permissionRejected, refusalFallbackEdit, elapsed,
            (shouldRestore || restoreEligible) ? toRestore : null,
            imageChips, restoredPermMode, effectivePermMode, hasPendingIdleWork);
        try {
            sink.onTurnComplete(outcome);
        } catch (RuntimeException e) {
            log.warn("Session sink failed while completing a turn; continuing cleanup", e);
        }

        AtomicBoolean continuationRan = new AtomicBoolean(false);
        Runnable continuation = () -> {
            if (!continuationRan.compareAndSet(false, true)) return;

            // Finish all old-turn cleanup before releasing the guard, so another submitting
            // thread cannot install new turn-scoped state that this continuation then removes.
            runCleanupStep("allow sleep", awakeGuard::allowSleep);
            runCleanupStep("remove turn-scoped permission rules", () -> {
                PermissionGate gate = permissionGate.get();
                if (gate != null) {
                    gate.removeRules(r -> r.source() == RuleSource.SKILL
                        || r.source() == RuleSource.COMMAND);
                }
            });
            runCleanupStep("clear turn-scoped hooks", clearTurnScopedHooks);

            // Keep the in-flight guard while deferred rewind/compact work owns the session. The
            // final idle operation releases it immediately before queue drain.
            runNextIdleOperation();
        };

        try {
            onUi.accept(continuation);
        } catch (RuntimeException e) {
            log.warn("Turn continuation publisher failed; running cleanup inline", e);
            continuation.run();
        }
    }

    private void startIdleOperationsIfIdle() {
        if (!turnInFlight.compareAndSet(false, true)) return;
        runNextIdleOperation();
    }

    private void runNextIdleOperation() {
        Supplier<? extends CompletionStage<?>> operation = idleOperations.poll();
        if (operation == null) {
            releaseAfterIdleOperations();
            return;
        }

        runIdleOperation(operation);
    }

    private void runIdleOperation(Supplier<? extends CompletionStage<?>> operation) {
        CompletionStage<?> completion;
        try {
            completion = operation.get();
        } catch (RuntimeException e) {
            log.warn("Deferred idle operation failed", e);
            runNextIdleOperation();
            return;
        }
        if (completion == null) {
            runNextIdleOperation();
            return;
        }

        completion.whenComplete((_, failure) -> publishIdleOperationContinuation(failure));
    }

    private void publishIdleOperationContinuation(Throwable failure) {
        AtomicBoolean continuationRan = new AtomicBoolean(false);
        Runnable continuation = () -> {
            if (!continuationRan.compareAndSet(false, true)) return;
            if (failure != null) log.warn("Deferred idle operation failed", failure);
            runNextIdleOperation();
        };
        try {
            onUi.accept(continuation);
        } catch (RuntimeException e) {
            log.warn("Idle-operation continuation publisher failed; continuing inline", e);
            continuation.run();
        }
    }

    private void releaseAfterIdleOperations() {
        idleReleaseDepth.incrementAndGet();
        try {
            // Close the race where an operation was queued after runNextIdleOperation's empty poll
            // but before ownership was released. Keep the current ownership instead of briefly
            // exposing an idle session.
            Supplier<? extends CompletionStage<?>> operation = idleOperations.poll();
            if (operation != null) {
                runIdleOperation(operation);
                return;
            }

            turnInFlight.set(false);
            drainNextOrPublishIdle();
        } finally {
            // An operation queued while onDrain re-enters the adapter must not preempt the prompt
            // that onDrain is dispatching. If no prompt claimed the session, start it now.
            if (idleReleaseDepth.decrementAndGet() == 0 && !idleOperations.isEmpty()) {
                startIdleOperationsIfIdle();
            }
        }
    }

    private void drainNextOrPublishIdle() {
        List<QueuedCommand> batch = List.of();
        try {
            batch = takeNextBatch();
        } catch (RuntimeException e) {
            log.warn("Failed to drain the engine message queue", e);
        }

        if (!batch.isEmpty()) {
            try {
                onDrain.accept(batch);
            } catch (RuntimeException e) {
                log.warn("Queued command dispatch failed; publishing idle", e);
                publishIdleSafely();
            }
        } else {
            publishIdleSafely();
        }
    }

    /**
     * Take the next batch of main-thread commands to run as one turn. The single
     * unified queue is peeked by priority ({@code NOW > NEXT > LATER}), so a queued
     * user prompt always runs before a waiting task notification — the
     * {@code utils/queueProcessor.ts} rule.
     */
    private List<QueuedCommand> takeNextBatch() {
        MessageQueueManager queue = queue();
        QueuedCommand next = queue.peek(TurnEngine::isMainThread);
        if (next == null) return List.of();
        if (isIndividuallyProcessed(next)) {
            QueuedCommand one = queue.dequeue(TurnEngine::isMainThread);
            return one != null ? List.of(one) : List.of();
        }
        String targetMode = next.mode();
        return queue.dequeueAllMatching(cmd -> isMainThread(cmd)
            && !isSlashText(cmd) && Strings.CS.equals(targetMode, cmd.mode()));
    }

    private static boolean isMainThread(QueuedCommand cmd) { return cmd.agentId() == null; }


    private static boolean isSlashText(QueuedCommand cmd) {
        return cmd.text() != null && Strings.CS.startsWith(cmd.text().strip(), "/");
    }

    private static boolean isIndividuallyProcessed(QueuedCommand cmd) {
        return isSlashText(cmd) || Strings.CS.equals("bash", cmd.mode());
    }

    private void notifyError(Throwable error, boolean userCancel) {
        try {
            sink.onError(error, userCancel);
        } catch (RuntimeException sinkFailure) {
            log.warn("Session sink failed while reporting a turn error", sinkFailure);
        }
    }

    private static boolean isQueuedCommandEditable(QueuedCommand command) {
        return command != null
            && !command.isMeta()
            && !Strings.CS.equals("task-notification", command.mode());
    }

    private void publishInputQueue() {
        try {
            inputQueueListener.accept(queue().snapshot());
        } catch (RuntimeException e) {
            log.warn("Input queue listener failed", e);
        }
    }

    private void publishIdleSafely() {
        try {
            sink.onIdle();
        } catch (RuntimeException e) {
            log.warn("Session sink failed while publishing idle", e);
        }
    }

    private void runCleanupStep(String description, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            log.warn("Failed to {}; continuing turn cleanup", description, e);
        }
    }

    private boolean isUserCancelSafely() {
        try {
            return isUserCancel();
        } catch (RuntimeException e) {
            log.warn("Failed to inspect the query abort reason; treating it as a normal failure", e);
            return false;
        }
    }

    private boolean isPermissionRejectedSafely() {
        try {
            return Strings.CS.equals("user_reject_permission",
                queryEngine.execution().getAbortController().getReason());
        } catch (RuntimeException e) {
            log.warn("Failed to inspect the permission-rejection abort reason", e);
            return false;
        }
    }

    private boolean isRefusalFallbackEditSafely() {
        try {
            return Strings.CS.equals(AbortController.REFUSAL_FALLBACK_EDIT,
                queryEngine.execution().getAbortController().getReason());
        } catch (RuntimeException e) {
            log.warn("Failed to inspect the refusal-fallback abort reason", e);
            return false;
        }
    }

    private boolean finalMessageTailAllowsAutoRestore() {
        try {
            List<Message> messages = queryEngine.conversation().getMessages();
            int selectedIndex = HumanTurns.lastTypedTurnIndex(messages);
            return selectedIndex >= 0
                && HumanTurns.messagesAfterAreOnlySynthetic(messages, selectedIndex);
        } catch (RuntimeException e) {
            log.warn("Failed to inspect final messages for interrupt auto-restore", e);
            return false;
        }
    }

    /**
     * Whether the turn ended by the user's hand rather than by failure — the gate on the whole
     * auto-restore path.
     */
    private boolean isUserCancel() {
        String reason = queryEngine.execution().getAbortController().getReason();
        return Strings.CS.equals("user-cancel", reason)
            || Strings.CS.equals(AbortController.REFUSAL_FALLBACK_EDIT, reason);
    }

    private static boolean isHumanInput(UserInput input) {
        return input != null && !input.isMeta()
            && !Strings.CS.equals("task-notification", input.querySource());
    }

    private static List<PermissionRule> commandRules(List<String> toolSpecs) {
        List<PermissionRule> rules = new ArrayList<>(toolSpecs.size());
        for (String rawSpec : toolSpecs) {
            if (StringUtils.isBlank(rawSpec)) continue;
            String toolSpec = rawSpec.trim();
            int paren = toolSpec.indexOf('(');
            if (paren > 0 && Strings.CS.endsWith(toolSpec, ")")) {
                String toolName = toolSpec.substring(0, paren).trim();
                String pattern = toolSpec.substring(paren + 1, toolSpec.length() - 1).trim();
                rules.add(PermissionRule.withPattern(
                    toolName, PermissionBehavior.ALLOW, RuleSource.COMMAND, pattern));
            } else {
                rules.add(PermissionRule.of(
                    toolSpec, PermissionBehavior.ALLOW, RuleSource.COMMAND));
            }
        }
        return rules;
    }

    /**
     * Whether a message counts as "meaningful content" for interrupt auto-restore.
     */
    public static boolean isMeaningful(SDKMessage msg) {
        if (msg instanceof SDKMessage.StreamEvent ev) {
            String t = ev.eventType();
            return Strings.CS.equals("content_block_delta", t) || Strings.CS.equals("tool_streaming_start", t);
        }
        return false;
    }
}
