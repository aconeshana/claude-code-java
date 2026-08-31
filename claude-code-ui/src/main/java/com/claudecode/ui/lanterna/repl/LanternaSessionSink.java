package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.text.FormatUtils;

import com.claudecode.core.constants.Figures;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.engine.TurnTokenBudget;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.HookSuccessAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.runtime.compact.CompactWarningProvider;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnEngine;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.tools.tasks.InProcessTeammateTask;
import com.claudecode.core.paste.PastedRefParser;
import com.claudecode.tools.tasks.PendingBackgroundWork;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TeamRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.TextColor;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.ui.lanterna.components.ChipSegments;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.components.SpinnerStateMachine;
import com.claudecode.ui.lanterna.components.SpinnerVerbs;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.BackgroundTaskPill;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessageCollapser;
import com.claudecode.ui.lanterna.transcript.MessageHistory;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.TranscriptEventReducer;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;

/**
 * Lanterna (TUI) implementation of {@link SessionSink} — the first adapter for the headless {@link
 * TurnEngine}.
 */
public final class LanternaSessionSink implements SessionSink {

    private static final Logger log = LoggerFactory.getLogger(LanternaSessionSink.class);

    private static final int MAX_API_ERROR_CHARS = 1000;

    private final Consumer<Runnable> onUi;
    private final MessagePanel messagePanel;
    private final InputPanel inputPanel;
    private final SpinnerComponent spinnerComponent;
    private final TerminalController terminalController;
    private final LanternaMessageDispatcher dispatcher;
    private final MessageCollapser collapser;
    private final TranscriptEventReducer transcriptEvents;
    private final QuerySession queryEngine; // read-only: getConfig for the turn-summary line
    private final Runnable runStatusLine;       // debounced status-line refresh signal
    private final Supplier<String> model;       // for the spinner effort suffix
    private final IntSupplier btwUseCount;      // global-config btwUseCount for the /btw auto-tip
    private final CompactWarningProvider compactWarnings;
    private final Supplier<String> tipSupplier;
    private final Runnable onIdleHook;
    private final LongConsumer pokemonExperienceConsumer;
    private final UiStreamDeltaBatcher streamDeltaBatcher;
    private volatile String messageDisplayTurnId;
    private volatile String messageDisplayMessageId;
    private final AtomicInteger messageDisplayIndex = new AtomicInteger();
    private final AtomicBoolean turnUiSettingsRefreshInFlight = new AtomicBoolean();
    private final AtomicBoolean tipPrefetchInFlight = new AtomicBoolean();
    private final AtomicReference<String> prefetchedTip = new AtomicReference<>("");
    private volatile boolean terminalProgressEnabled = true;
    private volatile int cachedBtwUseCount;
    private volatile String cachedEffortSuffix = "";
    /** Salvage callback for an interrupted prompt whose rewind was suppressed by a
     *  live UI-state guard; see {@link #setInterruptSalvage}. */
    private volatile Consumer<String> interruptSalvage = _ -> { };

    /** Per-turn spinner state machine — created in {@link #onTurnStart}, driven in {@link #onMessage}. */
    private volatile SpinnerStateMachine spinnerMachine;
    private volatile UserInput currentInput;
    private volatile String initializedMetadataSessionId;
    private volatile String deferredInitialMetadataSessionId;
    private volatile long turnStartingUsageTokens;
    /**
     * When the current background wait began, or {@code null} while nothing is
     * pending. Cross-turn state on purpose: a turn that ends with agents still
     * running reports only its own slice, and the turn that finally finds nothing
     * pending reports the whole span back to this instant.
     */
    private volatile Long backgroundWaitStartMillis;

    private volatile Long swarmDurationStartMillis;
    private volatile TurnBudgetSnapshot swarmBudgetSnapshot;
    /**
     * Injected task registry the background-wait census reads. Injected rather
     * than resolved from the global holder, which presentation code must not do;
     * {@code null} until the screen wires it, in which case nothing is pending.
     */
    private volatile TaskRegistry taskRegistry;
    private volatile Consumer<Boolean> taskBoardLoadingListener = _ -> { };
    private volatile Runnable taskBoardOwnersChangedListener = () -> { };
    private volatile List<TaskBoardOwnerState> lastTaskBoardOwners = List.of();

    private final Map<String, String> teammateSpinnerVerbs = new ConcurrentHashMap<>();
    private final Map<String, String> teammatePastVerbs = new ConcurrentHashMap<>();
    private final Map<String, String> teammateColors = new ConcurrentHashMap<>();
    private final AtomicInteger teammateColorIndex = new AtomicInteger();
    // Convergent "last seen" cache of the running teammate team identity
    // (Fields#teammateColorTeamIdentity). It gates one-time color-palette resets
    // rather than requiring atomic read-modify-write, so it is deliberately NOT
    // volatile: NonAtomicVolatileUpdate would otherwise flag the read-modify-write
    // below. Visibility across the render path is via ConcurrentHashMap/AtomicInteger.
    private TeammateColorTeamIdentity teammateColorTeamIdentity;
    private static final List<String> TEAMMATE_COLOR_PALETTE = List.of(
        "red", "blue", "green", "yellow", "purple", "orange", "pink", "cyan");
    private final TurnPokemonExperienceLedger pokemonExperienceLedger =
        new TurnPokemonExperienceLedger();

    LanternaSessionSink(Consumer<Runnable> onUi, MessagePanel messagePanel, InputPanel inputPanel,
                        SpinnerComponent spinnerComponent,
                        TerminalController terminalController, LanternaMessageDispatcher dispatcher,
                        MessageCollapser collapser, MessageHistory messageHistory, QuerySession queryEngine,
                        Runnable runStatusLine, Supplier<String> model, IntSupplier btwUseCount,
                        CompactWarningProvider compactWarnings, Supplier<String> tipSupplier,
                        Runnable onIdleHook, LongConsumer pokemonExperienceConsumer) {
        this.onUi = onUi;
        this.messagePanel = messagePanel;
        this.inputPanel = inputPanel;
        this.spinnerComponent = spinnerComponent;
        this.terminalController = terminalController;
        this.dispatcher = dispatcher;
        // Route the render-layer visible-streaming-text phase (197's
        // visibleStreamingText) to the current turn's spinner machine. The machine is
        // per-turn (recreated on every onTurnStart), so the lambda reads the live
        // volatile field rather than capturing a single instance.
        if (dispatcher != null) {
            dispatcher.onStreamTextVisibility(visible -> {
                SpinnerStateMachine machine = this.spinnerMachine;
                if (machine != null) machine.onStreamTextVisibility(visible);
            });
        }
        this.collapser = collapser;
        this.transcriptEvents = new TranscriptEventReducer(
            messageHistory, collapser, messagePanel,
            () -> ViewedTeammateHolder.instance().isViewing());
        this.queryEngine = queryEngine;
        this.runStatusLine = runStatusLine;
        this.model = model;
        this.btwUseCount = btwUseCount;
        this.compactWarnings = compactWarnings != null
            ? compactWarnings : CompactWarningProvider.none();
        this.tipSupplier = tipSupplier != null ? tipSupplier : () -> "";
        this.onIdleHook = onIdleHook != null ? onIdleHook : () -> { };
        this.pokemonExperienceConsumer = pokemonExperienceConsumer != null
            ? pokemonExperienceConsumer : _ -> { };
        this.streamDeltaBatcher = new UiStreamDeltaBatcher(onUi,
            text -> dispatchOnUi(new SDKMessage.StreamEvent("content_block_delta", text)),
            this::transformMessageDisplayDelta);
        if (spinnerComponent != null) {
            spinnerComponent.setViewedTeammateIdSupplier(() -> {
                ViewedTeammateHolder holder = ViewedTeammateHolder.instance();
                return holder.hasForegroundedTeammate()
                    ? holder.viewingTaskId() : null;
            });
            spinnerComponent.setTeammateSelectionSuppliers(
                () -> ViewedTeammateHolder.instance().isSelecting(),
                () -> ViewedTeammateHolder.instance().selectedIndex());
            spinnerComponent.setTeammateSwarmFinishedListener(this::onTeammateSwarmFinished);
        }
        refreshTurnUiSettings();
        prefetchNextTip();
    }

    // ── Turn start (synchronous, GUI thread) ────────────────────────────────────

    @Override
    public void onTurnStart(UserInput input) {
        messageDisplayTurnId = UUID.randomUUID().toString();
        resetMessageDisplayMessage();
        turnStartingUsageTokens = totalUsageTokens(queryEngine.execution().getTotalUsage());
        pokemonExperienceLedger.reset();
        recordPromptStart(queryEngine, input.isMeta() ? "system"
            : isHumanInput(input) ? "typed" : null);
        prepareFirstTurnTranscriptMetadata(input);
        currentInput = input;
        inputPanel.setIsLoading(true);  // Esc now aborts immediately while loading
        collapser.setLoading(true, messagePanel);
        inputPanel.setQueuedHint(false);

        if (input.precedingUserMessages().isEmpty() && isHumanInput(input)) {
            renderEcho(input.displayText(), input.pasted());
        } else if (!isHumanInput(input)) {
            // renderEcho normally opens a fresh dispatcher/collapser turn. An
            // internal notification deliberately skips that human-input echo,
            // so reset the presentation state explicitly before its streamed
            // compact summary arrives.
            dispatcher.resetTurn();
            collapser.resetTurn();
        }

        // Commit the submitted prompt before best-effort terminal chrome. Each
        // OSC method flushes and title animation allocates a scheduler; none of
        // that should hold the Enter echo hostage. In the TUI, onUi queues this
        // after the input frame; direct test/headless adapters still run it now.
        onUi.accept(this::startTerminalBusyIndicators);

        // Per-turn spinner: created here, driven from onMessage. Uses onUi for its own
        // (and its delayed thinking-clear) marshaling.
        SpinnerStateMachine machine = new SpinnerStateMachine(onUi, spinnerComponent);
        this.spinnerMachine = machine;
        int btwCount = cachedBtwUseCount;
        onUi.accept(() -> spinnerComponent.setBtwUseCount(btwCount));
        machine.startTurn(prefetchedTip.getAndSet(""), cachedEffortSuffix);
        onUi.accept(() -> taskBoardLoadingListener.accept(true));
        refreshTurnUiSettings();
        prefetchNextTip();
    }

    private void startTerminalBusyIndicators() {
        if (terminalController == null) return;
        // OSC 9;4 indeterminate progress + OSC 21337 busy tab status + animated title.


        if (terminalProgressEnabled) terminalController.progressIndeterminate();
        terminalController.setTabStatus("state", "busy");
        terminalController.setTabStatus("color", "rgb(255,149,0)");
        terminalController.startTitleAnimation();
    }

    private void refreshTurnUiSettings() {
        if (!turnUiSettingsRefreshInFlight.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("turn-ui-settings-prefetch").start(() -> {
            try {
                terminalProgressEnabled =
                    UiSettings.readGlobalBoolean("terminalProgressBarEnabled", true);
                cachedBtwUseCount = btwUseCount.getAsInt();
                cachedEffortSuffix = UiSettings.readEffortSuffix(model.get());
            } catch (RuntimeException e) {
                log.debug("Turn UI settings prefetch failed: {}", e.toString());
            } finally {
                turnUiSettingsRefreshInFlight.set(false);
            }
        });
    }

    private void prefetchNextTip() {
        if (!tipPrefetchInFlight.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("spinner-tip-prefetch").start(() -> {
            try {
                String tip = tipSupplier.get();
                prefetchedTip.set(tip != null ? tip : "");
            } catch (RuntimeException e) {
                log.debug("Spinner tip prefetch failed: {}", e.toString());
            } finally {
                tipPrefetchInFlight.set(false);
            }
        });
    }

    /**
     * Materializes the fresh interactive session's cached mode metadata. The caller
     * starts the title helper first: a fast completed helper therefore appends
     * {@code ai-title} before these rows, while a pending helper completes after them.
     * Resumed sessions deliberately keep the existing completion-tail deferral.
     */
    void prepareFirstTurnTranscriptMetadata(UserInput input) {
        String sessionId = queryEngine.conversation().getSessionId();
        if (Objects.equals(initializedMetadataSessionId, sessionId)) return;
        boolean restoredSession = queryEngine.conversation().getMessages() != null
            && !queryEngine.conversation().getMessages().isEmpty();
        boolean deferred = recordInitialTranscriptMetadata(
            queryEngine, input, restoredSession);
        deferredInitialMetadataSessionId = deferred ? sessionId : null;
        initializedMetadataSessionId = sessionId;
    }

    /**
     * Materializes fresh-session mode metadata immediately before a startup system message is appended.
     */
    void prepareStartupSystemTranscriptMetadata(String permissionMode) {
        String sessionId = queryEngine.conversation().getSessionId();
        if (Objects.equals(initializedMetadataSessionId, sessionId)) return;
        boolean restoredSession = queryEngine.conversation().getMessages() != null
            && !queryEngine.conversation().getMessages().isEmpty();
        if (restoredSession) {
            deferredInitialMetadataSessionId = sessionId;
            initializedMetadataSessionId = sessionId;
            return;
        }
        TranscriptSink transcript = queryEngine.execution().getTranscriptSink();
        if (transcript == null) return;
        transcript.prepareSessionMaterialization(sessionId);
        transcript.recordMode(sessionId, "normal");
        if (permissionMode != null) {
            transcript.recordPermissionMode(sessionId, permissionMode);
        }
        deferredInitialMetadataSessionId = null;
        initializedMetadataSessionId = sessionId;
    }

    /**
     * Writes the live REPL mode immediately before a local command such as
     * {@code /compact} starts producing transcript rows. Unlike first-turn
     * initialization this is intentionally unconditional: a restored session
     * may contain stale metadata, while the command-line permission selection
     * is the effective state for this invocation.
     */
    void recordLocalCommandTranscriptMetadata(String permissionMode) {
        TranscriptSink transcript = queryEngine.execution().getTranscriptSink();
        if (transcript == null) return;
        String sessionId = queryEngine.conversation().getSessionId();
        transcript.cachePermissionMode(sessionId, permissionMode);
    }

    /**
     * Synchronous UI echo: "❯ &lt;input&gt;" (chips in brand color) + blank separator + per-turn
     * dispatcher/collapser reset + inline "⎿ [Image #N]" lines painted together with the echo
     * (before the SDK User event streams back after ImageResizer). Stable compatibility data from the old executor.
     */
    private void renderEcho(String displayText, Map<Integer, PastedContent> pasted) {
        messagePanel.appendLine("", TextColor.ANSI.DEFAULT); // spacer
// Expand `[Pasted text #N...]` placeholders back to the original pasted text before
// echoing.

        // the already-expanded finalInput, not the raw chip-bearing input).
        // `[Image #N]` refs are deliberately left alone by expandPastedTextRefs
        // (images render as their own pill, not inlined text) and the SDK's
        // real echo of this same message is suppressed right below via
// suppressNextUserEcho, so this is the only chance to show the
        // pasted text's real content rather than a permanent placeholder.
        String expanded = PastedRefParser.expandPastedTextRefs(displayText, pasted);
        String truncated = truncateUserInput(expanded);
        int logicalStart = messagePanel.snapshotLineCount();
        String[] echoLines = truncated.split("\n", -1);
        for (int i = 0; i < echoLines.length; i++) {
            String prefix = (i == 0) ? "❯ " : "  ";
            messagePanel.appendMixed(
                ChipSegments.of(prefix + echoLines[i],
                    LanternaTheme.inputText(),
                    LanternaTheme.claude(),
                    LanternaTheme.userQueryBg()));
        }
        messagePanel.registerLogicalMessage(
            "live-user-" + System.nanoTime(),
            MessagePanel.LogicalMessageKind.USER,
            logicalStart,
            messagePanel.snapshotLineCount() - 1,
            expanded,
            displayText,
            null,
            null,
            false);
        // UserPromptMessage has margin before the next response, not a visible

        // interactive turn and was especially obvious above permission dialogs.
        messagePanel.appendLine("", TextColor.ANSI.DEFAULT);

        dispatcher.resetTurn();
        collapser.resetTurn();
        // Drop the SDK User event that will stream through — we've already painted the echo.
        dispatcher.suppressNextUserEcho();

        // Inline "⎿ [Image #N]" lines, synchronously (AFTER resetTurn cleared the rendered set).
        if (!pasted.isEmpty()) {
            List<Integer> renderedIds = new ArrayList<>();
            pasted.values().stream()
                .filter(pc -> Strings.CS.equals("image", pc.type()))
                .sorted(Comparator.comparingInt(PastedContent::id))
                .forEach(pc -> {
                    dispatcher.renderUserImageMessage(pc.id(), messagePanel);
                    renderedIds.add(pc.id());
                });
            dispatcher.markImagesRenderedInline(renderedIds);
        }
    }

    // ── Per message (background thread; marshals internally) ─────────────────────

    @Override
    public void onMessage(SDKMessage msg) {

        // the outer submitted prompt completes. Java's per-block Assistant event can
        // precede GPT response.completed / normalized message_delta by more than the
        // 300 ms debounce, so refreshing there can briefly snapshot Usage.EMPTY and
        // flash 0%. QueryLoop emits this provider-neutral signal only after it has
        // written final usage into the conversation envelope.
        if (shouldRefreshStatusLine(msg)) {
            runStatusLine.run();
            long experienceGain = pokemonExperienceLedger.creditFinalizedAssistant(
                msg, queryEngine.conversation().getMessages());
            if (experienceGain > 0) pokemonExperienceConsumer.accept(experienceGain);
        }

        // Drive the spinner state machine directly (it marshals its own mutations).
        SpinnerStateMachine machine = this.spinnerMachine;
        if (machine != null) {
            switch (msg) {
                case SDKMessage.StreamRequestStart _ ->
                    machine.onStreamEvent("stream_request_start", "");
                case SDKMessage.StreamEvent(String eventType, Object data1)
                    when data1 instanceof String evData -> machine.onStreamEvent(eventType, evData);
                default -> { }
            }
        }

// ── Ephemeral progress collapse ────────────────────────────────────.















        // updates in place, then a concise completion summary — without bloating
        // the transcript/message list.
        if (msg instanceof SDKMessage.Progress(ProgressMessage message) && message != null
                && message.data() != null) {
            ProgressMessage.ProgressData data = message.data();
            if (data.isEphemeral() && !Boolean.FALSE.equals(data.isIncomplete())) {
                // In-progress ephemeral tick: status line already handles it
                // (single transient line, overwritten next tick); skip panel
                // dispatch to avoid panel bloat.
                return;
            }
        }

        if (msg instanceof SDKMessage.StreamEvent(String eventType, Object data)
                && Strings.CS.equals("content_block_delta", eventType)
                && data instanceof String delta) {
            streamDeltaBatcher.append(delta);
            return;
        }

        // Record for Ctrl+O replay + dispatch through the collapser (Read/Search fold).
        final SDKMessage m = msg;
        boolean finalAssistantDelta = msg instanceof SDKMessage.Assistant;
        streamDeltaBatcher.runAfterPending(finalAssistantDelta, () -> {
            dispatchOnUi(m);
            if (finalAssistantDelta) resetMessageDisplayMessage();
        });
    }

    private String transformMessageDisplayDelta(String delta, Boolean finalDelta) {
        HookDispatcher hooks = queryEngine.execution().getHookDispatcher();
        if (hooks == null) return delta;
        HookDispatcher.HookOutcome outcome = hooks.dispatchMessageDisplayWithOutcome(
            messageDisplayTurnId, messageDisplayMessageId,
            messageDisplayIndex.getAndIncrement(), Boolean.TRUE.equals(finalDelta), delta);
        if (!outcome.proceed() || outcome.preventContinuation()) return delta;
        return outcome.specificOutput("MessageDisplay")
            .map(output -> output.get("displayContent"))
            .filter(JsonNode::isTextual)
            .map(JsonNode::asText)
            .orElse(delta);
    }

    private void resetMessageDisplayMessage() {
        messageDisplayMessageId = UUID.randomUUID().toString();
        messageDisplayIndex.set(0);
    }

    static boolean shouldRefreshStatusLine(SDKMessage message) {
        return message instanceof SDKMessage.StreamEvent event
            && Strings.CS.equals(
                SDKMessage.ASSISTANT_USAGE_FINALIZED_EVENT, event.eventType());
    }

    private void dispatchOnUi(SDKMessage message) {
        try {




            transcriptEvents.accept(message);
        } catch (Throwable t) {
            // Never let a single bad message strand the GUI thread (Lanterna's
            // SeparateTextGUIThread stops on any uncaught task exception).
            log.error("[DISPATCH] failed for {}", message.getClass().getSimpleName(), t);
        }
    }

    // ── Error (background thread; marshals internally) ───────────────────────────

    @Override
    public void onError(Throwable error, boolean userCancel) {
        if (!userCancel) {
            streamDeltaBatcher.runAfterPending(() -> {
                spinnerComponent.stop();
                String errMsg = error.getMessage() != null ? error.getMessage() : error.toString();
                if (errMsg.length() > MAX_API_ERROR_CHARS) {
                    errMsg = FormatUtils.truncate(errMsg, MAX_API_ERROR_CHARS);
                }
                // While a teammate transcript is being viewed, the leader's error
                // is recorded in history but not painted into the panel.
                if (!ViewedTeammateHolder.instance().isViewing()) {
                    messagePanel.appendLine("✗ Error: " + errMsg, LanternaTheme.toolError());
                }
            });
        } else {
            streamDeltaBatcher.runAfterPending(spinnerComponent::stop);
        }
    }

    // ── Turn complete (background thread; marshals teardown internally) ──────────

    @Override
    public void onTurnComplete(TurnOutcome outcome) {
        long activeElapsedMs = spinnerComponent.adjustedElapsedMsForTranscript();
        TurnBudgetSnapshot budgetSnapshot = turnBudgetSnapshot();
        UserInput completedInput = currentInput;
        String completedSessionId = queryEngine.conversation().getSessionId();
        boolean appendRestoredMetadata = Objects.equals(
            deferredInitialMetadataSessionId, completedSessionId);
        // Resolve before the UI marshal so the wait window is measured from the
        // engine's turn boundary, not from whenever the GUI thread gets around to
        // painting. Only turns that actually emit the row advance the wait state,
        // matching the established guard (`if (recordsDuration && !aborted)`).
        boolean recordsDuration = shouldRecordTurnDuration(
            activeElapsedMs, budgetSnapshot != null, outcome);
        boolean runningTeammates = taskRegistry != null
            && !taskRegistry.listRunningTeammates().isEmpty();
        PendingBackgroundWork.Resolved turnDuration = recordsDuration && !runningTeammates
            ? resolveBackgroundWait(activeElapsedMs) : null;
        if (recordsDuration && runningTeammates && swarmDurationStartMillis == null) {
            swarmDurationStartMillis = spinnerComponent.turnStartMillis();
        }
        if (recordsDuration && runningTeammates && budgetSnapshot != null) {
            swarmBudgetSnapshot = budgetSnapshot;
        }
        spinnerComponent.finishTurnClock();
        String backgroundSummary = backgroundTaskSummary();
        currentInput = null;
        streamDeltaBatcher.runAfterPending(() -> {
            recordTranscriptLifecycle(queryEngine, completedInput, outcome,
                turnDuration, appendRestoredMetadata, budgetSnapshot);
            if (appendRestoredMetadata && completedInput != null && !outcome.userCancel()
                    && Objects.equals(
                        deferredInitialMetadataSessionId, completedSessionId)) {
                deferredInitialMetadataSessionId = null;
            }
            // Match Claude Code 2.1.197: the streaming spinner's isLoading folds in
            // "main-thread command queue non-empty", so while queued commands await
            // their next turn the spinner stays mounted and animated — never an
            // invisible stop→start gap, and the verb is never re-randomized between
            // queued turns. Only end the leader's spinner when nothing is queued.
            if (!hasQueuedMainThreadCommands()) {
                spinnerComponent.finishLeaderTurn();
            }
            spinnerComponent.setToolUseMode(false);
            collapser.setLoading(false, messagePanel);
            inputPanel.setIsLoading(false); // Esc now opens MessageSelector (idle state)
            taskBoardLoadingListener.accept(false);

            switch (closingRow(outcome)) {
                case INTERRUPTED -> messagePanel.appendMixed(List.of(
                    new MessagePanel.Segment(Figures.RESULT_PREFIX, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("Interrupted", LanternaTheme.welcomeDim())));
                case TURN_SUMMARY -> {
                    if (turnDuration != null) {
                        dispatcher.renderTurnSummary(messagePanel, turnDuration.durationMs(),
                            turnDuration.pendingBackgroundAgentCount(),
                            turnDuration.pendingWorkflowCount(), backgroundSummary,
                            budgetSnapshot == null ? null : budgetSnapshot.tokens(),
                            budgetSnapshot == null ? null : budgetSnapshot.limit(),
                            budgetSnapshot == null ? null : budgetSnapshot.nudges(), null);
                    }
                }
                case NONE -> { }
            }
            // Reflect both rewind restoration and tool-driven permission updates
            // (notably ExitPlanMode's setMode(default)) into the prompt footer.
            // The gate is already authoritative; this only synchronizes the widget.
            syncPermissionMode(inputPanel, outcome);

            // Auto-restore the prompt if the input box is still empty.
            if (outcome.restored() && inputPanel.getText().isEmpty()) {
                inputPanel.setRestoredText(outcome.restoredInput());
                if (!outcome.restoredImageChips().isEmpty()) {
                    inputPanel.restoreImageChips(outcome.restoredImageChips());
                }
            }

            // The engine suppressed the destructive rewind on a live UI-state guard
            // (non-empty input / viewing a teammate) — the prompt is still lost from
            // the conversation, so let the adapter salvage it (e.g. append to the
            // prompt history) when its current state makes that safe.
            if (outcome.restoreEligible()) {
                interruptSalvage.accept(outcome.restoredInput());
            }

            terminalController.stopTitleAnimation();

            runStatusLine.run();
            // Clear OSC 9;4 progress + reset OSC 21337 tab status to idle.
            terminalController.progressClear();
            terminalController.setTabStatus("state", "idle");
            terminalController.setTabStatus("color", "rgb(0,215,95)");

            long completedUsageTokens = totalUsageTokens(queryEngine.execution().getTotalUsage());
            long completedTurnUsage = Math.max(0L,
                completedUsageTokens - turnStartingUsageTokens);
            long experienceGain = pokemonExperienceLedger.creditTurnRemainder(
                completedTurnUsage);
            if (experienceGain > 0) pokemonExperienceConsumer.accept(experienceGain);

            // Token / auto-compact warning banner: when the conversation is near
            // the auto-compact threshold, surface a persistent footer notice (matches

            // compaction both clear it through the compact-warning provider.
            showCompactWarning();
        });
    }

    /**
     * True when the main-thread command queue still holds commands that must run as
     * the next turn. Mirrors 197's {@code getMainThreadQueueLength() > 0} term: while
     * it holds, the spinner stays mounted between queued turns instead of stopping.
     */
    private boolean hasQueuedMainThreadCommands() {
        try {
            return queryEngine.conversation().getMessageQueue().snapshot().stream()
                .anyMatch(cmd -> cmd.agentId() == null);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Counts outstanding background work and folds it into this turn's duration,
     * advancing {@link #backgroundWaitStartMillis} for the next turn.
     */
    private PendingBackgroundWork.Resolved resolveBackgroundWait(long activeElapsedMs) {
        PendingBackgroundWork pending = taskRegistry == null ? PendingBackgroundWork.NONE
            : PendingBackgroundWork.count(taskRegistry,
                queryEngine.conversation().getMessageQueue().snapshot());
        PendingBackgroundWork.Resolved resolved = PendingBackgroundWork.resolve(pending,
            activeElapsedMs, spinnerComponent.turnStartMillis(), System.currentTimeMillis(),
            backgroundWaitStartMillis);
        backgroundWaitStartMillis = resolved.backgroundWaitStartTime();
        return resolved;
    }

    /** A conversation rewind starts a fresh background-wait interval. */
    void resetBackgroundWaitForRewind() {
        backgroundWaitStartMillis = null;
    }

    /** Emits the single deferred swarm duration after the final teammate exits. */
    private void onTeammateSwarmFinished() {
        Long start = swarmDurationStartMillis;
        if (start == null) return;
        swarmDurationStartMillis = null;
        TurnBudgetSnapshot budget = swarmBudgetSnapshot;
        swarmBudgetSnapshot = null;
        long durationMs = Math.max(0L, System.currentTimeMillis() - start);
        onUi.accept(() -> {
            int messageCount = loggableMessageCount(queryEngine.conversation().getMessages());
            queryEngine.conversation().appendTranscriptMessage(
                MessageFactory.createTurnDurationMessage(durationMs, messageCount,
                    null, null, budget == null ? null : budget.tokens(),
                    budget == null ? null : budget.limit(),
                    budget == null ? null : budget.nudges(), null));
            dispatcher.renderTurnSummary(messagePanel, durationMs,
                null, null, null, budget == null ? null : budget.tokens(),
                budget == null ? null : budget.limit(),
                budget == null ? null : budget.nudges(), null);
        });
    }

    private TurnBudgetSnapshot turnBudgetSnapshot() {
        TurnTokenBudget budget = queryEngine.execution().getTurnTokenBudget();
        if (budget == null || budget.total() == null) return null;
        return new TurnBudgetSnapshot(budget.spent(), budget.total(), 0);
    }

    private record TurnBudgetSnapshot(long tokens, long limit, int nudges) { }

    private String backgroundTaskSummary() {
        if (taskRegistry == null) return null;
        List<TaskState> running = taskRegistry.listBackground();
        if (running.isEmpty()) return null;
        return BackgroundTaskPill.labelFor(running, taskRegistry::isMonitorTask);
    }

    static long totalUsageTokens(Usage usage) {
        if (usage == null) return 0L;
        return saturatedAdd(saturatedAdd(usage.inputTokens(), usage.outputTokens()),
            saturatedAdd(usage.cacheCreationInputTokens(), usage.cacheReadInputTokens()));
    }

    static final class TurnPokemonExperienceLedger {
        private final Set<String> creditedAssistantIds = new HashSet<>();
        private long creditedTokens;

        synchronized void reset() {
            creditedAssistantIds.clear();
            creditedTokens = 0L;
        }

        synchronized long creditFinalizedAssistant(SDKMessage message,
                                                   List<Message> messages) {
            if (!(message instanceof SDKMessage.StreamEvent(String eventType, Object data))
                    || !Strings.CS.equals(
                        SDKMessage.ASSISTANT_USAGE_FINALIZED_EVENT, eventType)
                    || !(data instanceof String assistantId)
                    || StringUtils.isBlank(assistantId)
                    || creditedAssistantIds.contains(assistantId)) {
                return 0L;
            }
            Usage usage = findAssistantUsage(messages, assistantId);
            long tokens = totalUsageTokens(usage);
            if (tokens <= 0) return 0L;
            creditedAssistantIds.add(assistantId);
            creditedTokens = saturatedAdd(creditedTokens, tokens);
            return tokens;
        }

        synchronized long creditTurnRemainder(long completedTurnUsage) {
            long remainder = Math.max(0L, completedTurnUsage - creditedTokens);
            creditedTokens = saturatedAdd(creditedTokens, remainder);
            return remainder;
        }

        private static Usage findAssistantUsage(List<Message> messages, String assistantId) {
            if (messages == null || messages.isEmpty()) return null;
            for (int index = messages.size() - 1; index >= 0; index--) {
                Message message = messages.get(index);
                if (message instanceof AssistantMessage assistant
                        && Strings.CS.equals(assistantId, assistant.uuid())
                        && assistant.message() != null) {
                    return assistant.message().usage();
                }
            }
            return null;
        }
    }

    private static boolean isHumanInput(UserInput input) {
        return input != null && !input.isMeta()
            && !Strings.CS.equals("task-notification", input.querySource());
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    /**
     * Registers the callback invoked when the engine suppressed the interrupt
     * auto-restore on a live UI-state guard (non-empty input box or viewing an agent
     * task) — the destructive rewind was skipped, but the interrupted prompt is still
     * recoverable. The adapter typically appends it to the prompt history so Up-arrow
     * can still reach it. Runs on the UI thread inside {@link #onTurnComplete}.
     */
    void setInterruptSalvage(Consumer<String> interruptSalvage) {
        this.interruptSalvage = interruptSalvage != null ? interruptSalvage : _ -> { };
    }

    /**
     * Injects the task registry the end-of-turn background-work census reads.
     * Until it is set the turn row behaves as if nothing were ever pending.
     */
    void setTaskRegistry(TaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
        if (spinnerComponent == null) return;
        spinnerComponent.setRunningTeammateMetricsSupplier(taskRegistry == null
            ? List::of
            : () -> runningTeammateMetrics(taskRegistry, true, false));
    }

    void setTaskBoardLoadingListener(Consumer<Boolean> listener) {
        taskBoardLoadingListener = listener != null ? listener : _ -> { };
    }

    void setTaskBoardOwnersChangedListener(Runnable listener) {
        taskBoardOwnersChangedListener = listener != null ? listener : () -> { };
    }

    List<SpinnerComponent.TeammateMetric> runningTeammateMetricsSnapshot() {
        TaskRegistry registry = taskRegistry;
        return registry == null ? List.of() : runningTeammateMetrics(registry, false, true);
    }

    private List<SpinnerComponent.TeammateMetric> runningTeammateMetrics(
            TaskRegistry registry, boolean notifyTaskBoard, boolean taskBoardActivityOnly) {
        List<InProcessTeammateTask> teammates = registry.listRunningTeammates();
        String runningTeamId = teammates.stream()
            .map(InProcessTeammateTask::teamId)
            .filter(StringUtils::isNotBlank)
            .findFirst()
            .orElse(null);
        Object runningTeamLifecycle = runningTeamId == null ? null
            : TeamRegistry.instance().get(runningTeamId).orElse(null);
        teammateColorTeamIdentity = synchronizeTeammateColorTeam(
            teammateColorTeamIdentity, runningTeamId, runningTeamLifecycle,
            teammateColors, teammateColorIndex);
        // Teammate colors belong to identity at spawn time, not to
        // the alphabetic order used to render the tree. Allocate previously
        // unseen colors in task start order, then retain the registry's render order.
        teammates.stream()
            .sorted((left, right) -> compareTeammateStartTimes(
                registry.store()
                    .get(left.getTaskId())
                    .map(TaskState::startTime)
                    .orElse(Instant.MAX),
                registry.store()
                    .get(right.getTaskId())
                    .map(TaskState::startTime)
                    .orElse(Instant.MAX)))
            .forEach(teammate -> teammateColors.computeIfAbsent(teammate.getTaskId(),
                _ -> teammateColorForOrdinal(teammateColorIndex.getAndIncrement())));
        List<SpinnerComponent.TeammateMetric> metrics = teammates.stream()
            .map(teammate -> registry.store().get(teammate.getTaskId())
                .map(task -> new SpinnerComponent.TeammateMetric(
                    teammate.getTaskId(), teammate.name(), teammateColors.get(teammate.getTaskId()),
                    teammate.isIdle(), teammate.isShutdownRequested(),
                    teammate.isAwaitingPlanApproval(), taskBoardActivityOnly
                        ? teammate.taskBoardActivity()
                        : teammate.progressActivity(),
                    teammateSpinnerVerbs.computeIfAbsent(teammate.getTaskId(),
                        _ -> SpinnerVerbs.randomActive()),
                    teammatePastVerbs.computeIfAbsent(teammate.getTaskId(),
                        _ -> SpinnerVerbs.randomCompleted()),
                    task.startTime().toEpochMilli(), teammate.totalPausedMillis(),
                    teammate.progressTokens(), teammate.progressToolUses()))
                .orElse(null))
            .filter(Objects::nonNull)
            .toList();
        if (notifyTaskBoard) notifyTaskBoardOwnersChanged(teammates);
        return metrics;
    }

    private void notifyTaskBoardOwnersChanged(List<InProcessTeammateTask> teammates) {
        List<TaskBoardOwnerState> next = teammates.stream()
            .map(teammate -> new TaskBoardOwnerState(
                teammate.getTaskId(), teammate.name(),
                teammateColors.get(teammate.getTaskId()), teammate.taskBoardActivity()))
            .toList();
        if (next.equals(lastTaskBoardOwners)) return;
        lastTaskBoardOwners = next;
        onUi.accept(taskBoardOwnersChangedListener);
    }

    private record TaskBoardOwnerState(
        String taskId, String name, String colorName, String activity) { }

    record TeammateColorTeamIdentity(String teamId, Object lifecycle) { }

    static String teammateColorForOrdinal(int ordinal) {
        return TEAMMATE_COLOR_PALETTE.get(Math.floorMod(
            ordinal + 1, TEAMMATE_COLOR_PALETTE.size()));
    }

    static int compareTeammateStartTimes(Instant left, Instant right) {
        return left.compareTo(right);
    }

    static String synchronizeTeammateColorTeam(
            String currentTeamId,
            String runningTeamId,
            Map<String, String> colors,
            AtomicInteger colorIndex) {
        TeammateColorTeamIdentity current = currentTeamId == null ? null
            : new TeammateColorTeamIdentity(currentTeamId, currentTeamId);
        TeammateColorTeamIdentity next = synchronizeTeammateColorTeam(
            current, runningTeamId, runningTeamId, colors, colorIndex);
        return next == null ? null : next.teamId();
    }

    static TeammateColorTeamIdentity synchronizeTeammateColorTeam(
            TeammateColorTeamIdentity current,
            String runningTeamId,
            Object runningLifecycle,
            Map<String, String> colors,
            AtomicInteger colorIndex) {
        if (StringUtils.isBlank(runningTeamId)) return current;
        if (current != null && Objects.equals(current.teamId(), runningTeamId)
                && current.lifecycle() == runningLifecycle) {
            return current;
        }
        colors.clear();
        colorIndex.set(0);
        return new TeammateColorTeamIdentity(runningTeamId, runningLifecycle);
    }

    static void syncPermissionMode(InputPanel inputPanel, TurnOutcome outcome) {
        String mode = outcome.effectivePermissionMode() != null
            ? outcome.effectivePermissionMode()
            : outcome.restoredPermissionMode();
        if (mode != null) {
            inputPanel.setPermissionMode(mode);
        }
    }

    /** The single row a finished turn writes under its output, if any. */
    enum ClosingRow {
        /** "⏺ Interrupted" — the turn was cut short but the prompt stays consumed. */
        INTERRUPTED,
        /** "✻ Brewed for 6s …" — a turn that ran to its end. */
        TURN_SUMMARY,
        /** Nothing: something the turn already printed says what happened. */
        NONE
    }

    /**
     * Which row closes a finished turn.
     */
    static ClosingRow closingRow(TurnOutcome outcome) {
        if (outcome.permissionRejected() || outcome.refusalFallbackEdit() || outcome.restored()) {
            return ClosingRow.NONE;
        }
        if (outcome.userCancel()) return ClosingRow.INTERRUPTED;
        return ClosingRow.TURN_SUMMARY;
    }


    static boolean shouldRecordTurnDuration(long activeElapsedMs, boolean hasBudget,
                                            TurnOutcome outcome) {
        // Matches Claude Code 2.1.197 REPL.tsx:2978 turn-duration guard:
        //   (turnDurationMs > 30000 || budgetInfo !== undefined)
        //   && !abortController.signal.aborted
        // 'permissionRejected' is a Java-side refinement of the abort condition
        // (a rejected permission turn is short and not worth a duration row);
        // 197 has no proactive mode, so nothing maps to !proactiveActive.
        return (activeElapsedMs > 30_000L || hasBudget)
            && !outcome.userCancel()
            && !outcome.permissionRejected();
    }


    static int loggableMessageCount(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        return (int) messages.stream()
            .filter(LanternaSessionSink::isLoggableDurationMessage)
            .count();
    }

    private static boolean isLoggableDurationMessage(Message message) {
        if (message == null || message instanceof ProgressMessage) return false;
        if (message instanceof AttachmentMessage attachment
                && attachment.payload() instanceof HookSuccessAttachment success) {
            return StringUtils.isNotEmpty(success.content())
                || StringUtils.isNotBlank(success.stdout())
                || StringUtils.isNotBlank(success.stderr());
        }
        return true;
    }


    static void recordTranscriptLifecycle(QuerySession queryEngine, UserInput input,
                                          TurnOutcome outcome,
                                          PendingBackgroundWork.Resolved turnDuration,
                                          boolean appendRestoredMetadata) {
        recordTranscriptLifecycle(queryEngine, input, outcome, turnDuration,
            appendRestoredMetadata, null);
    }

    static void recordTranscriptLifecycle(QuerySession queryEngine, UserInput input,
                                          TurnOutcome outcome,
                                          PendingBackgroundWork.Resolved turnDuration,
                                          boolean appendRestoredMetadata,
                                          TurnBudgetSnapshot budget) {
        if (queryEngine == null || input == null) return;

        if (turnDuration != null && !outcome.userCancel() && !outcome.permissionRejected()) {
            int messageCount = loggableMessageCount(queryEngine.conversation().getMessages());
            queryEngine.conversation().appendTranscriptMessage(
                MessageFactory.createTurnDurationMessage(turnDuration.durationMs(), messageCount,
                    turnDuration.pendingBackgroundAgentCount(),
                    turnDuration.pendingWorkflowCount(),
                    budget == null ? null : budget.tokens(),
                    budget == null ? null : budget.limit(),
                    budget == null ? null : budget.nudges(), null));
        }

        if (outcome.userCancel()) return;

        TranscriptSink transcript = queryEngine.execution().getTranscriptSink();
        if (transcript == null) return;
        String sessionId = queryEngine.conversation().getSessionId();
        if (isHumanInput(input)
                && shouldCacheLastPrompt(queryEngine, input, appendRestoredMetadata)) {
            if (appendRestoredMetadata) {

                // before its mode metadata. Fresh sessions keep the prompt in
                // memory until shutdown/compact instead.
                transcript.recordLastPrompt(sessionId, input.displayText());
            } else {
                transcript.cacheLastPrompt(sessionId, input.displayText());
            }
        }

        String effectiveMode = outcome.effectivePermissionMode();
        boolean planExitRestoredMode = currentTurnUsedTool(queryEngine, "ExitPlanMode");
        boolean changedPermissionMode = !planExitRestoredMode && effectiveMode != null
            && !effectiveMode.equals(input.permissionMode());
        if (appendRestoredMetadata) {
            if (!transcript.hasPersistedMode(sessionId)) {
                transcript.recordMode(sessionId, "normal");
            }
            String permissionMode = effectiveMode != null
                ? effectiveMode : input.permissionMode();
            if (permissionMode != null && (changedPermissionMode
                    || !transcript.hasPersistedPermissionMode(sessionId))) {
                transcript.recordPermissionMode(sessionId, permissionMode);
            }
        } else if (changedPermissionMode) {
            transcript.recordPermissionMode(sessionId, effectiveMode);
        }
    }


    private static boolean currentTurnUsedTool(QuerySession queryEngine, String toolName) {
        List<Message> messages = queryEngine.conversation().getMessages();
        if (messages == null || messages.isEmpty()) return false;
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message instanceof UserMessage user && !user.isMeta()
                    && user.toolUseResult() == null
                    && (MessageOrigin.USER.equals(user.origin())
                        || MessageOrigin.AUTO_CONTINUATION.equals(user.origin()))) {
                break;
            }
            if (message instanceof AssistantMessage assistant
                    && assistant.message() != null
                    && assistant.message().content() != null
                    && assistant.message().content().stream().anyMatch(block ->
                        block instanceof ToolUseBlock tool
                            && Strings.CS.equals(toolName, tool.name()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A fresh one-shot argv TTY turn without tool use leaves no textual
     * last-prompt tail. A later {@code --continue} reconstructs that missing
     * fallback from the transcript's first meaningful user row.
     */
    private static boolean shouldCacheLastPrompt(QuerySession queryEngine, UserInput input,
                                                 boolean restoredSession) {
        if (!input.interactiveStartupPrompt() || restoredSession) return true;
        List<Message> messages = queryEngine.conversation().getMessages();
        if (messages == null) return false;
        return messages.stream().anyMatch(message -> message instanceof AssistantMessage assistant
            && assistant.message() != null
            && assistant.message().content() != null
            && assistant.message().content().stream().anyMatch(ToolUseBlock.class::isInstance));
    }

    /** Initial interactive session metadata, queued before the first transcript message. */
    static void recordInitialTranscriptMetadata(QuerySession queryEngine, UserInput input) {
        recordInitialTranscriptMetadata(queryEngine, input, false);
    }

    static boolean recordInitialTranscriptMetadata(QuerySession queryEngine, UserInput input,
                                                   boolean restoredSession) {
        if (queryEngine == null || input == null) return false;
        if (restoredSession) return true;
        TranscriptSink transcript = queryEngine.execution().getTranscriptSink();
        if (transcript == null) return false;
        String sessionId = queryEngine.conversation().getSessionId();
        transcript.recordMode(sessionId, "normal");
        if (input.permissionMode() != null) {
            transcript.recordPermissionMode(sessionId, input.permissionMode());
        }
        return false;
    }


    static void recordInteractivePromptStart(QuerySession queryEngine) {
        recordPromptStart(queryEngine, "typed");
    }

    static void recordPromptStart(QuerySession queryEngine, String source) {
        if (queryEngine == null) return;
        TranscriptSink transcript = queryEngine.execution().getTranscriptSink();
        if (transcript != null) {
            transcript.recordPromptStart(queryEngine.conversation().getSessionId(), source);
        }
    }

    /**
     * Renders the auto-compact token-warning banner if the conversation is above the warning threshold
     * and the warning isn't suppressed.
     */
    private void showCompactWarning() {
        var warning = compactWarnings.warning(
            queryEngine.conversation().getMessages(), queryEngine.configuration().getConfig().model());
        if (warning.isEmpty()) return;
        String text = "Context is " + warning.get().percentLeft()
            + "% from the auto-compact limit - run /compact to summarize";
        // Long timeout so the banner persists between turns while still above threshold;
        // re-evaluated each turn. Matches the transient-hint footer channel used by the
        // effort notification (ReplCommandUiBridge#showEffortNotification).
        inputPanel.showTransientHint(text, 30_000L);
    }

    @Override
    public void onIdle() {
        // Called on the publishing (GUI) thread by the engine's drain continuation.
        onIdleHook.run();
        inputPanel.setQueuedHint(false);
    }

    /**
     * Truncate an over-long user prompt for the echo line.
     */
    private static String truncateUserInput(String input) {
        if (input == null) return "";
        final int MAX = 10_000;
        final int HEAD = 2_500;
        final int TAIL = 2_500;
        if (input.length() <= MAX) return input;
        return input.substring(0, HEAD)
            + "\n[... truncated " + (input.length() - HEAD - TAIL) + " chars ...]\n"
            + input.substring(input.length() - TAIL);
    }
}
