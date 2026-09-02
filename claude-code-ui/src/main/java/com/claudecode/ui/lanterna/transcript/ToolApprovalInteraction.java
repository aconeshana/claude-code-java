package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.PermissionExplainerCallback;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.commands.context.ContextUsageAnalyzer;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.tools.questions.AskUserQuestionTool;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.tasks.InProcessTeammateTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.teammate.TeammateLeaderCoordinator;
import com.claudecode.tools.tasks.teammate.TeammateLeaderPermissionResolver;
import com.claudecode.ui.lanterna.dialog.AskUserQuestionDialog;
import com.claudecode.ui.lanterna.dialog.PermissionDialog;
import com.claudecode.ui.lanterna.dialog.PermissionPreviewPreparer;
import com.claudecode.ui.lanterna.dialog.PreparedPermissionPrompt;
import com.claudecode.ui.lanterna.dialog.RefusalFallbackDialog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.core.serialization.JsonUtils;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeature;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.InteractionPresenter;
import com.claudecode.runtime.interaction.InteractionRequest;
import com.claudecode.runtime.interaction.InteractionResolution;
import com.claudecode.runtime.interaction.InteractionSupport;
import com.claudecode.ui.MarkdownRenderer;

/**
 * Owns interactive tool approval and the leader-side teammate interaction boundary.
 */
public final class ToolApprovalInteraction {
    private static final MarkdownRenderer MARKDOWN_RENDERER = MarkdownRenderer.shared();
    private final MultiWindowTextGUI gui;
    private final InputPanel input;
    private final SpinnerComponent spinner;
    private final QuerySession queryEngine;
    private final PermissionGate permissionGate;
    private final PermissionExplainerCallback explainer;
    private final Supplier<Boolean> turnInFlight;
    private final Consumer<String> teammateTurnSubmitter;
    private final Consumer<SDKMessage.StreamEvent> permissionEventSink;
    private final PermissionDialog leaderDialog = new PermissionDialog();
    private final PermissionDialog teammateDialog = new PermissionDialog();
    private final AskUserQuestionDialog questionDialog = new AskUserQuestionDialog();
    private final RefusalFallbackDialog refusalDialog = new RefusalFallbackDialog();
    private final PermissionPromptQueue permissionPromptQueue = new PermissionPromptQueue();
    private final InteractionCoordinator interactionCoordinator;
    private final MessagePanel messagePanel;
    private final TaskRegistry taskRegistry;
    private final PermissionPreviewPreparer previewPreparer = PermissionPreviewPreparer.standard();
    private ToolPresentationSnapshotStore presentationSnapshots =
        new ToolPresentationSnapshotStore();

    public ToolApprovalInteraction(MultiWindowTextGUI gui, InputPanel input, SpinnerComponent spinner,
                            QuerySession queryEngine, PermissionGate permissionGate,
                            PermissionExplainerCallback explainer, Supplier<Boolean> turnInFlight,
                            Consumer<String> teammateTurnSubmitter) {
        this(gui, input, spinner, queryEngine, permissionGate, explainer, turnInFlight,
            teammateTurnSubmitter, _ -> {});
    }

    public ToolApprovalInteraction(MultiWindowTextGUI gui, InputPanel input, SpinnerComponent spinner,
                            QuerySession queryEngine, PermissionGate permissionGate,
                            PermissionExplainerCallback explainer, Supplier<Boolean> turnInFlight,
                            Consumer<String> teammateTurnSubmitter,
                            Consumer<SDKMessage.StreamEvent> permissionEventSink) {
        this(gui, input, spinner, queryEngine, permissionGate, explainer, turnInFlight,
            teammateTurnSubmitter, permissionEventSink, null, null);
    }

    public ToolApprovalInteraction(MultiWindowTextGUI gui, InputPanel input, SpinnerComponent spinner,
                            QuerySession queryEngine, PermissionGate permissionGate,
                            PermissionExplainerCallback explainer, Supplier<Boolean> turnInFlight,
                            Consumer<String> teammateTurnSubmitter,
                            Consumer<SDKMessage.StreamEvent> permissionEventSink,
                            InteractionCoordinator interactionCoordinator,
                            MessagePanel messagePanel) {
        this(gui, input, spinner, queryEngine, permissionGate, explainer, turnInFlight,
            teammateTurnSubmitter, permissionEventSink, interactionCoordinator, messagePanel, null);
    }

    public ToolApprovalInteraction(MultiWindowTextGUI gui, InputPanel input, SpinnerComponent spinner,
                            QuerySession queryEngine, PermissionGate permissionGate,
                            PermissionExplainerCallback explainer, Supplier<Boolean> turnInFlight,
                            Consumer<String> teammateTurnSubmitter,
                            Consumer<SDKMessage.StreamEvent> permissionEventSink,
                            InteractionCoordinator interactionCoordinator,
                            MessagePanel messagePanel,
                            TaskRegistry taskRegistry) {
        this.gui = gui;
        this.input = input;
        this.spinner = spinner;
        this.queryEngine = queryEngine;
        this.permissionGate = permissionGate;
        this.explainer = explainer;
        this.turnInFlight = turnInFlight;
        this.teammateTurnSubmitter = teammateTurnSubmitter;
        this.permissionEventSink = permissionEventSink == null ? _ -> {} : permissionEventSink;
        this.interactionCoordinator = interactionCoordinator;
        this.messagePanel = messagePanel;
        this.taskRegistry = taskRegistry;
        questionDialog.setTerminalColumnsSupplier(
            () -> gui.getScreen() != null ? gui.getScreen().getTerminalSize().getColumns() : 80);
    }

    public PermissionDialog leaderView() { return leaderDialog; }
    public AskUserQuestionDialog questionView() { return questionDialog; }
    public RefusalFallbackDialog refusalView() { return refusalDialog; }

    /** Whether a turn-blocking interaction currently owns terminal input. */
    public boolean isPromptActive() {
        return leaderDialog.isActive() || teammateDialog.isActive()
            || questionDialog.isActive() || refusalDialog.isActive();
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        leaderDialog.setKeybindingsStore(store);
        teammateDialog.setKeybindingsStore(store);
        refusalDialog.setKeybindingsStore(store);
    }

    public void setPresentationSnapshotStore(ToolPresentationSnapshotStore store) {
        presentationSnapshots = store == null ? new ToolPresentationSnapshotStore() : store;
    }

    public void setPlanClearApprovalConsumer(
            Consumer<PermissionDialog.PlanClearApproval> consumer) {
        leaderDialog.setPlanClearApprovalConsumer(consumer);
        teammateDialog.setPlanClearApprovalConsumer(consumer);
    }

    public void install() {
        if (interactionCoordinator == null) {
            queryEngine.execution().setPermissionAskCallback(queryEngine.execution().withDenialRecording(this::resolveLeader));
        } else {
            interactionCoordinator.register(InteractionFeatures.PERMISSION,
                localPresenter(InteractionFeatures.PERMISSION));
            interactionCoordinator.register(InteractionFeatures.USER_QUESTION,
                localPresenter(InteractionFeatures.USER_QUESTION));
            queryEngine.execution().setPermissionAskCallback(
                queryEngine.execution().withDenialRecording(interactionCoordinator));
        }
        TeammateLeaderCoordinator.instance().setPermissionResolver(new TeammateLeaderPermissionResolver() {
            @Override public PermissionAskCallback.Result resolvePermission(PermissionAskContext context) {
                return resolveTeammate(context);
            }

            @Override public InProcessTeammateTask.PlanApproval resolvePlanApproval(
                    @SuppressWarnings("unused") String summary) {
                return ToolApprovalInteraction.this.resolvePlanApproval();
            }
        });
        TeammateLeaderCoordinator.instance().setTurnSubmitter(this::submitTeammateTurn);
        // Not a tool approval, but the same turn-thread-blocks-on-GUI-thread
        // boundary: the refused turn waits here for the model it should retry on.
        queryEngine.execution().setRefusalFallbackPrompt(request -> withSpinnerPaused(() ->
            refusalDialog.showAndWait(gui, request, input::takeFocus)));
    }

    private InteractionPresenter<PermissionAskContext, PermissionAskCallback.Result>
            localPresenter(InteractionFeature<PermissionAskContext,
                PermissionAskCallback.Result> feature) {
        return new InteractionPresenter<>() {
            @Override public InteractionEndpoint endpoint() {
                return InteractionEndpoint.LOCAL;
            }

            @Override public InteractionSupport support() {
                return InteractionSupport.SUPPORTED;
            }

            @Override public boolean available(String sessionId) {
                return true;
            }

            @Override public void present(
                    InteractionRequest<PermissionAskContext,
                        PermissionAskCallback.Result> request) {
                Thread.ofVirtual().name("local-interaction-"
                    + request.descriptor().id()).start(() -> {
                        PermissionAskCallback.Result result = resolveLeader(
                            request.descriptor().id(), request.payload());
                        if (result != null) {
                            interactionCoordinator.respond(feature,
                                request.descriptor().id(), request.descriptor().sessionId(),
                                result, InteractionEndpoint.LOCAL);
                        }
                    });
            }

            @Override public void resolved(
                    InteractionResolution<PermissionAskCallback.Result> resolution) {
                if (resolution.origin() == InteractionEndpoint.LOCAL) return;
                permissionPromptQueue.cancel(resolution.descriptor().id());
            }
        };
    }

    private PermissionAskCallback.Result resolveLeader(PermissionAskContext context) {
        return resolve(null, context, leaderDialog);
    }

    private PermissionAskCallback.Result resolveLeader(
            String requestId, PermissionAskContext context) {
        return resolve(requestId, context, leaderDialog);
    }

    private PermissionAskCallback.Result resolveTeammate(PermissionAskContext context) {
        return resolve(null, enrichWorkerBadge(context), teammateDialog);
    }

    private PermissionAskCallback.Result resolve(
            String requestId, PermissionAskContext context, PermissionDialog dialog) {
        PermissionAskContext snapshot = previewPreparer.snapshotContext(context);
        return permissionPromptQueue.execute(requestId,
            () -> resolveActivePrompt(snapshot, dialog,
                () -> permissionPromptQueue.isCancelled(requestId)),
            () -> gui.getGUIThread().invokeLater(() -> {
                leaderDialog.cancelPending();
                questionDialog.cancelPending();
            }));
    }

    private PermissionAskCallback.Result resolveActivePrompt(PermissionAskContext context,
                                                               PermissionDialog dialog,
                                                               BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return null;
        PermissionAskContext routedContext = enrichSpecializedContext(context);
        ToolPresentationSnapshotStore.Ticket presentationTicket =
            presentationSnapshots.ticket(routedContext.toolUseId());
        boolean tracksPresentation = !StringUtils.isBlank(routedContext.toolUseId());
        PermissionPromptUiLifecycle lifecycle = new PermissionPromptUiLifecycle(
            input, permissionEventSink, routedContext.toolName(), input::takeFocus);
        boolean exitPlanMode = Strings.CS.equals("ExitPlanMode", routedContext.toolName());
        String plan = exitPlanMode ? textField(routedContext.input(), "plan") : "";
        boolean externalPlanPreview = messagePanel != null && exitPlanMode;
        Runnable closeUi = () -> {
            if (externalPlanPreview) messagePanel.clearTransientTail();
            lifecycle.close();
        };
        gui.getGUIThread().invokeLater(() -> {
            lifecycle.begin();
            if (externalPlanPreview) {
                messagePanel.showPlanApprovalPreview(plan, MARKDOWN_RENDERER);
            }
        });
        try {
            return withSpinnerPaused(() -> {
                if (Strings.CS.equals("AskUserQuestion", routedContext.toolName())) {
                    return resolveQuestion(routedContext, dialog, closeUi, cancelled);
                }
                if (cancelled.getAsBoolean()) return null;
                PreparedPermissionPrompt prepared = previewPreparer.prepare(routedContext);
                if (cancelled.getAsBoolean()
                        || !presentationSnapshots.isCurrent(presentationTicket)) return null;
                if (tracksPresentation && prepared.rejectedFileChangePreview() != null
                        && !presentationSnapshots.publishFilePreview(
                            presentationTicket, prepared.rejectedFileChangePreview())) return null;
                if (tracksPresentation && exitPlanMode && !StringUtils.isBlank(plan)) {
                    if (!presentationSnapshots.publishPlan(presentationTicket, plan)) return null;
                }
                BooleanSupplier invalidated = () -> cancelled.getAsBoolean()
                    || !presentationSnapshots.isCurrent(presentationTicket);
                AtomicReference<List<PermissionUpdate>> acceptedUpdates =
                    new AtomicReference<>(List.of());
                PermissionAskCallback.Result result = dialog.showAndWait(gui, prepared, explainer,
                    updates -> acceptedUpdates.set(List.copyOf(updates)), closeUi,
                    externalPlanPreview
                        ? edited -> {
                            presentationSnapshots.publishPlan(presentationTicket, edited);
                            messagePanel.showPlanApprovalPreview(edited, MARKDOWN_RENDERER);
                        }
                        : _ -> {},
                    invalidated);
                if (invalidated.getAsBoolean()) {
                    presentationSnapshots.discard(routedContext.toolUseId());
                    return null;
                }
                applySuggestedUpdates(acceptedUpdates.get());
                return result;
            });
        } finally {
// Normal completion closes on the GUI thread from PermissionDialog.hide.
            // This queued fallback covers exceptional exits and is idempotent.
            gui.getGUIThread().invokeLater(closeUi);
        }
    }










    static final class PermissionPromptQueue {
        private record Entry(
                String requestId, Supplier<?> request, CompletableFuture<Object> result,
                Runnable cancelActive) {}
        private final Object stateLock = new Object();
        private final ArrayDeque<Entry> waiting = new ArrayDeque<>();
        private final Set<String> cancelled = new HashSet<>();
        private Entry active;
        private boolean draining;

        @SuppressWarnings("unchecked")
        <T> T execute(Supplier<T> request) {
            return execute(null, request, null);
        }

        @SuppressWarnings("unchecked")
        <T> T execute(String requestId, Supplier<T> request, Runnable cancelActive) {
            CompletableFuture<Object> result = new CompletableFuture<>();
            boolean startDrain = false;
            synchronized (stateLock) {
                waiting.addLast(new Entry(requestId, request, result, cancelActive));
                if (!draining) {
                    draining = true;
                    startDrain = true;
                }
            }
            if (startDrain) {
                Thread.ofVirtual().name("permission-prompt-queue").start(this::drain);
            }
            return (T) result.join();
        }

        void cancel(String requestId) {
            if (StringUtils.isBlank(requestId)) return;
            Runnable cancelActive = null;
            synchronized (stateLock) {
                cancelled.add(requestId);
                if (active != null && requestId.equals(active.requestId())) {
                    cancelActive = active.cancelActive();
                }
            }
            if (cancelActive != null) cancelActive.run();
        }

        boolean isCancelled(String requestId) {
            if (StringUtils.isBlank(requestId)) return false;
            synchronized (stateLock) {
                return cancelled.contains(requestId);
            }
        }

        private void drain() {
            while (true) {
                Entry entry;
                synchronized (stateLock) {
                    entry = waiting.pollFirst();
                    if (entry == null) {
                        active = null;
                        draining = false;
                        return;
                    }
                    if (entry.requestId() != null && cancelled.remove(entry.requestId())) {
                        entry.result().complete(null);
                        continue;
                    }
                    active = entry;
                }
                try {
                    entry.result().complete(entry.request().get());
                } catch (Throwable failure) {
                    entry.result().completeExceptionally(failure);
                } finally {
                    synchronized (stateLock) {
                        if (active == entry) active = null;
                        if (entry.requestId() != null) cancelled.remove(entry.requestId());
                    }
                }
            }
        }
    }

    private PermissionAskContext enrichSpecializedContext(PermissionAskContext context) {
        if (!Strings.CS.equals("ExitPlanMode", context.toolName())) return context;
        ObjectNode inputNode = context.input() != null && context.input().isObject()
            ? ((ObjectNode) context.input()).deepCopy()
            : JsonUtils.getMapper().createObjectNode();
        String sessionId = queryEngine.conversation().getSessionId();
        String plan = PlanFiles.getPlan(sessionId, null);
        if (plan != null) inputNode.put("plan", plan);
        inputNode.put("planFilePath", PlanFiles.getPlanFilePath(sessionId, null).toString());
        inputNode.put("_uiBypassPermissionsAvailable",
            permissionGate != null && permissionGate.isBypassPermissionsModeAvailable());
        inputNode.put("_uiShowClearContext",
            UiSettings.readEffectiveBoolean("showClearContextOnPlanAccept", false));
        inputNode.put("_uiAutoModeAvailable",
            permissionGate != null && permissionGate.isPlanAutoModeAvailable());
        Usage usage = ContextUsageAnalyzer.lastApiUsage(
            queryEngine.conversation().getMessages());
        Integer usedPercent = contextUsedPercent(
            usage, queryEngine.configuration().getConfig().model());
        if (usedPercent != null) inputNode.put("_uiContextUsedPercent", usedPercent);
        if (messagePanel != null) inputNode.put("_uiPlanPreviewInTranscript", true);
        return context.toBuilder().input(inputNode).build();
    }

    static Integer contextUsedPercent(Usage usage, String model) {
        if (usage == null) return null;
        long contextWindow = ContextUsageAnalyzer.contextWindowFor(model);
        if (contextWindow <= 0) return null;
        long used = usage.inputTokens()
            + usage.cacheCreationInputTokens()
            + usage.cacheReadInputTokens();
        return Math.clamp((int) Math.round(used * 100.0 / contextWindow), 0, 100);
    }

    private PermissionAskCallback.Result resolveQuestion(PermissionAskContext context,
                                                          PermissionDialog fallbackDialog,
                                                          Runnable onClose,
                                                          BooleanSupplier cancelled) {
        var questions = AskUserQuestionTool.parseQuestions(context.input());
        if (questions == null) {
            PreparedPermissionPrompt prepared = previewPreparer.prepare(context);
            return fallbackDialog.showAndWait(gui, prepared, explainer, _ -> {}, onClose,
                _ -> {}, cancelled);
        }
        var answers = questionDialog.showAndWait(gui, questions, onClose, cancelled);
        if (answers == null) return PermissionAskCallback.Result.deny();
        JsonNode updated = AskUserQuestionTool.buildAnswerInput(context.input(), answers);
        return PermissionAskCallback.Result.allowWithInput(updated);
    }

    void applySuggestedUpdates(List<PermissionUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;
        RuntimeException persistenceFailure = null;
        try {
        UiSettings.persistPermissionUpdates(System.getProperty("user.dir", "."), updates);
        } catch (RuntimeException failure) {
            persistenceFailure = failure;
        }
        if (permissionGate != null) {
            permissionGate.applyUpdates(updates);
            syncInputPermissionModeFromGate();
        }
        if (persistenceFailure != null && messagePanel != null) {
            String detail = persistenceFailure.getMessage() == null
                ? persistenceFailure.getClass().getSimpleName() : persistenceFailure.getMessage();
            gui.getGUIThread().invokeLater(() -> messagePanel.appendLine(
                "⚠ Permission update applies to this session but could not be saved: " + detail,
                LanternaTheme.toolWarning()));
        }
    }

    /**
     * Released PermissionContext updates the reactive permission context as part
     * of accepting the prompt. Lanterna keeps the gate and input footer as two
     * projections, so update the footer at the same boundary instead of waiting
     * for the enclosing turn to complete.
     */
    private void syncInputPermissionModeFromGate() {
        if (input == null || permissionGate == null) return;
        Runnable update = () -> {
            var currentMode = permissionGate.currentMode();
            if (currentMode != null) input.setPermissionMode(currentMode.kind().wireValue());
        };
        if (gui != null) gui.getGUIThread().invokeLater(update);
        else update.run();
    }

    private PermissionAskContext enrichWorkerBadge(PermissionAskContext context) {
        String workerId = context.workerId();
        if (StringUtils.isBlank(workerId)) return context;
        if (taskRegistry == null) return context;
        return taskRegistry.getTeammateHandle(workerId)
            .map(teammate -> context.toBuilder()
                .worker(teammate.name(), context.workerColor())
                .build())
            .orElse(context);
    }

    private static String textField(JsonNode input, String key) {
        JsonNode value = input == null ? null : input.get(key);
        return value != null && value.isTextual() ? value.asText() : "";
    }


    private InProcessTeammateTask.PlanApproval resolvePlanApproval() {
        if (permissionGate == null) return new InProcessTeammateTask.PlanApproval(true, "", "default");
        PermissionModeKind kind = permissionGate.currentMode().kind();
        String inherited = kind == PermissionModeKind.PLAN ? "default" : permissionGate.currentMode().external();
        return new InProcessTeammateTask.PlanApproval(true, "", inherited);
    }

    private void submitTeammateTurn(String message) {
        gui.getGUIThread().invokeLater(() -> teammateTurnSubmitter.accept(message));
    }

    private <T> T withSpinnerPaused(Supplier<T> action) {
        boolean visible = spinner.isVisible();
        boolean activeTurn = Boolean.TRUE.equals(turnInFlight.get());
        if (visible) {
            gui.getGUIThread().invokeLater(() -> spinner.setVisible(false));
        }
        try {
            return withTurnClockPaused(spinner, activeTurn, action);
        } finally {
            if (visible) {
                if (Boolean.TRUE.equals(turnInFlight.get())) gui.getGUIThread().invokeLater(() -> spinner.setVisible(true));
            }
        }
    }

    /** Permission focus pauses the submitted-turn clock even if streamed text hid the spinner. */
    static <T> T withTurnClockPaused(SpinnerComponent spinner, boolean activeTurn,
                                     Supplier<T> action) {
        if (activeTurn) spinner.pauseTimer();
        try {
            return action.get();
        } finally {
            if (activeTurn) spinner.resumeTimer();
        }
    }

    /**
     * GUI-thread lifecycle shared by every permission prompt.
     */
    static final class PermissionPromptUiLifecycle {
        private final InputPanel input;
        private final Consumer<SDKMessage.StreamEvent> eventSink;
        private final String toolName;
        private final Runnable restoreFocus;
        private final AtomicBoolean begun = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        PermissionPromptUiLifecycle(InputPanel input,
                                    Consumer<SDKMessage.StreamEvent> eventSink,
                                    String toolName,
                                    Runnable restoreFocus) {
            this.input = input;
            this.eventSink = eventSink == null ? _ -> {} : eventSink;
            this.toolName = toolName == null ? "" : toolName;
            this.restoreFocus = restoreFocus == null ? () -> {} : restoreFocus;
        }

        void begin() {
            if (!begun.compareAndSet(false, true)) return;
            if (input != null) input.setSuppressed(true);
            eventSink.accept(new SDKMessage.StreamEvent("permission_waiting", toolName));
        }

        void close() {
            if (!begun.get() || !closed.compareAndSet(false, true)) return;
            eventSink.accept(new SDKMessage.StreamEvent("permission_resolved", toolName));
            if (input != null) input.setSuppressed(false);
            restoreFocus.run();
        }
    }
}
