package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.ToolUseRenderContext;
import com.claudecode.tools.agent.AgentContinuationService;
import com.claudecode.tools.agent.AgentTool;
import com.claudecode.tools.cron.CronFeatureGate;
import com.claudecode.tools.cron.CronScheduler;
import com.claudecode.tools.cron.CronStore;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.tasks.TaskNotificationBridge;
import com.claudecode.ui.lanterna.bashmode.BashModeExecutor;
import com.claudecode.ui.lanterna.components.LogoPanel;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.components.WelcomeBlockHolder;
import com.claudecode.ui.lanterna.dialog.CollaborationPickerDialog;
import com.claudecode.ui.lanterna.dialog.FeishuSetupDialog;
import com.claudecode.ui.lanterna.dialog.ItermImagePreviewWindow;
import com.claudecode.ui.lanterna.dialog.LspRecommendationDialog;
import com.claudecode.ui.lanterna.dialog.PluginHintMenu;
import com.claudecode.ui.lanterna.dialog.ThinkingToggleDialog;
import com.claudecode.ui.lanterna.features.agents.AgentsFeature;
import com.claudecode.ui.lanterna.features.btw.BtwFeature;
import com.claudecode.ui.lanterna.features.conversation.ConversationToolsFeature;
import com.claudecode.ui.lanterna.features.diagnostics.DiagnosticsFeature;
import com.claudecode.ui.lanterna.features.goal.GoalFeature;
import com.claudecode.ui.lanterna.features.memory.MemoryFeature;
import com.claudecode.ui.lanterna.features.plugins.PluginsFeature;
import com.claudecode.ui.lanterna.features.pokemon.PokemonFeature;
import com.claudecode.ui.lanterna.features.projects.ProjectPanel;
import com.claudecode.ui.lanterna.features.projects.ProjectPanelController;
import com.claudecode.ui.lanterna.features.sandbox.SandboxFeature;
import com.claudecode.ui.lanterna.features.settings.AutoModeEntryWarningController;
import com.claudecode.ui.lanterna.features.settings.BypassPermissionsStartupGate;
import com.claudecode.ui.lanterna.features.settings.HooksController;
import com.claudecode.ui.lanterna.features.settings.MCPController;
import com.claudecode.ui.lanterna.features.settings.PermissionsFeature;
import com.claudecode.ui.lanterna.features.settings.PreferencesFeature;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.features.tasks.BackgroundTasksFeature;
import com.claudecode.ui.lanterna.features.web.WebGatewayFeature;
import com.claudecode.ui.lanterna.input.CoordinatorNavigationController;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.input.WindowInputRouter;
import com.claudecode.ui.lanterna.mouse.SelectionController;
import com.claudecode.ui.lanterna.slash.ReplRefs;
import com.claudecode.ui.lanterna.slash.SlashCommandDispatcher;
import com.claudecode.ui.lanterna.statusline.StatusLineController;
import com.claudecode.ui.lanterna.suggest.FileSuggestionService;
import com.claudecode.ui.lanterna.suggest.SuggestionController;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessageActionsController;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.Selection;
import com.claudecode.ui.lanterna.transcript.ToolApprovalInteraction;
import com.claudecode.ui.lanterna.transcript.ToolInlineHeaderResolver;
import com.claudecode.ui.lanterna.transcript.TranscriptController;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.TurnEngine;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the REPL scene: constructs every widget, feature facade, controller and
 * the turn engine from a {@link ReplContext}, declares the scene through {@link ReplSceneLayout},
 * attaches the fullscreen window, and returns the immutable {@link ReplGraph}.
 *
 * <p>This is <em>only</em> construction and wiring. Runtime behaviour that the graph needs from
 * the screen (lifecycle, terminal job control, the mutable model / tool-name state, and
 * post-construction observers the CLI installs later) is reached through the narrow
 * {@link Host} port so that a feature cannot grow a dependency on the screen by accident.
 *
 * <p>Single-use: {@link #compose} may be called once. Late-bound collaborators (the turn engine,
 * submission coordinator, status line, cron scheduler) are captured through instance fields so
 * that lambdas created early in the graph resolve them lazily, mirroring the construction order
 * the original inline layout used.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code screens/REPL.tsx} — the component tree assembled for the interactive session
 *       (message list, permission prompts, startup dialogs, prompt input) and the hooks that
 *       bind them together.</li>
 *   <li>{@code components/PromptInput/PromptInput.tsx} — prompt-side wiring of history,
 *       suggestions, queued commands and the status line.</li>
 * </ul>
 */
final class ReplComposer {

    private static final Logger log = LoggerFactory.getLogger(ReplComposer.class);

    /** What the composed graph needs from the screen at runtime. */
    interface Host {
        void stop();
        void suspendForJobControl();
        void resumeAfterJobControl();
        /** {@code /model} or Session Host selected a new model. */
        void modelChanged(String model);
        String currentModel();
        List<String> toolNames();
        void setThemeScheme(String schemeName);
        /** A turn finished; the idle-prompt and away-summary observers (if installed) observe it. */
        void turnCompleted();
        /** The user submitted {@code input} from the prompt. */
        void userInteracted(String input);
    }

    private final ReplContext ctx;
    private final Host host;
    private final ReplScene scene;

    // Late-bound: created partway through compose(), read lazily by earlier lambdas.
    private TurnEngine turnEngine;
    private ReplSubmissionCoordinator submission;
    private BashModeExecutor bashMode;
    private TranscriptController transcriptController;
    private StatusLineController statusLine;
    private CronScheduler cronScheduler;
    private boolean composed;

    ReplComposer(ReplContext ctx, Host host, ReplScene scene) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.host = Objects.requireNonNull(host, "host");
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    ReplGraph compose() {
        if (composed) throw new IllegalStateException("ReplComposer is single-use");
        composed = true;

        var gui = ctx.gui();
        var screen = ctx.screen();
        var queryEngine = ctx.queryEngine();
        var application = ctx.application();
        var runtime = ctx.features();
        var launch = ctx.launch();
        var keybindings = launch.keybindings();
        var guiInvoker = ctx.guiInvoker();
        PermissionGate permissionGate = runtime.permissionGate();
        ToolRegistry toolRegistry = runtime.toolRegistry();
        Supplier<List<Skill>> skills = runtime.skills() != null ? runtime.skills() : List::of;
        var interactiveSessions = application.sessions();
        var interactionCoordinator = launch.interactionCoordinator();
        int terminalRows = ctx.terminalRows().getAsInt();

        // ── Components ─────────────────────────────────────────────────────
        MessagePanel messagePanel = new MessagePanel();
        messagePanel.setKeybindingsStore(keybindings);
        SpinnerComponent spinnerComponent = new SpinnerComponent();
        spinnerComponent.setVerbose(ctx.verbose());
        InputPanel inputPanel = new InputPanel(permissionGate != null
            ? permissionGate.currentMode().kind().wireValue()
            : "default");
        // The GUI thread is already running. Keep the base REPL surface hidden before
        // scene.attach publishes it; startup gates reveal it atomically once input is genuinely
        // ready. Hiding only after composition returns exposes a transient footer that PTY
        // drivers (and fast users) can type into before callbacks are fully installed.
        messagePanel.setVisible(false);
        inputPanel.setVisible(false);
        inputPanel.setBypassPermissionsModeAvailable(() ->
            permissionGate != null && permissionGate.isBypassPermissionsModeAvailable());
        inputPanel.setKeybindingsStore(keybindings);
        // Session Link may change collaboration state from a virtual thread. Install the GUI
        // hop before subscribing the footer to that state.
        inputPanel.setGuiInvoker(guiInvoker);
        inputPanel.setCollaborationController(launch.collaborationController());
        // Share the engine's SessionIdentity so a switchToSession call (resume/branch/clear)
        // is visible here too without a separate setSessionId sync step.
        inputPanel.wireSessionIdentity(queryEngine.conversation().sessionIdentity());
        // Shared transcript write port for features: system lines, dim lines, and the
        // " ❯ /cmd" breadcrumb chip. Created before any feature so none reaches back to the screen.
        ReplTranscriptSink transcript = new MessagePanelTranscriptSink(messagePanel, guiInvoker);
        LogoPanel welcomePanel = new LogoPanel();
        WelcomeBlockHolder welcomeBlock = new WelcomeBlockHolder();
        WelcomePresenter welcome = new WelcomePresenter(
            welcomePanel, welcomeBlock, messagePanel, ctx.terminalColumns(), host::currentModel,
            guiInvoker);
        WebGatewayFeature webGateway = new WebGatewayFeature(
            launch.gatewaySupervisor(), application.plugins(), transcript, welcome::updateWebLine);

        ToolApprovalInteraction toolApproval = new ToolApprovalInteraction(
            gui, inputPanel, spinnerComponent, queryEngine, permissionGate,
            runtime.permissionExplainer(),
            this::turnInFlight, text -> submission.handleQuery(text),
            event -> ctx.dispatcher().dispatch(event, messagePanel), interactionCoordinator,
            messagePanel, runtime.taskRegistry());
        toolApproval.setPresentationSnapshotStore(ctx.presentationSnapshots());
        toolApproval.setPlanClearApprovalConsumer(
            approval -> submission.acceptPlanWithClearedContext(approval));
        toolApproval.setKeybindingsStore(keybindings);
        LspRecommendationDialog lspRecommendationDialog = new LspRecommendationDialog();
        lspRecommendationDialog.setKeybindingsStore(keybindings);
        PluginHintMenu pluginHintMenu = new PluginHintMenu();
        pluginHintMenu.setKeybindingsStore(keybindings);
        LanternaSessionSink[] turnViewRef = new LanternaSessionSink[1];
        TaskBoardFeature taskBoard = new TaskBoardFeature(
            gui, screen, spinnerComponent, inputPanel, application.taskBoard(), runtime,
            () -> turnViewRef[0] == null ? List.of() : turnViewRef[0].runningTeammateMetricsSnapshot());
        taskBoard.start();
        ThinkingToggleDialog thinkingToggleDialog = new ThinkingToggleDialog();
        thinkingToggleDialog.setKeybindingsStore(keybindings);
        CollaborationPickerDialog collaborationPickerDialog = new CollaborationPickerDialog();
        collaborationPickerDialog.setKeybindingsStore(keybindings);
        collaborationPickerDialog.setInteractionBlocked(toolApproval::isPromptActive);
        FeishuSetupDialog feishuSetupDialog = new FeishuSetupDialog();
        feishuSetupDialog.setGuiInvoker(guiInvoker);
        GoalFeature goal = new GoalFeature(gui, inputPanel, queryEngine);
        ConversationToolsFeature conversationTools = new ConversationToolsFeature(
            gui, inputPanel, messagePanel, transcript, ctx.commandRegistry(), ctx.commandContext(),
            queryEngine, keybindings, terminalRows, ctx.terminalColumns());
        // Left-docked project drawer — covering overlay over the transcript's left strip; zero
        // size until toggled. Loads run on virtual threads.
        ProjectPanel projectPanel = new ProjectPanel(ctx.terminalColumns(), ctx.terminalRows());
        PluginsFeature plugins = new PluginsFeature(
            gui, inputPanel, transcript, ctx.commandContext(), application.plugins(),
            application.mcp(), keybindings);
        StartupGateDialogs startupGates = new StartupGateDialogs(keybindings);
        StartupGateController startupGateController = new StartupGateController(
            application.startupTrust(), application.memory(), startupGates, null,
            message -> log.warn(
                "[LANTERNA] Failed to compute external CLAUDE.md includes: {}", message));
        BypassPermissionsStartupGate bypassPermissionsGate = BypassPermissionsStartupGate.standard(
            () -> launch.allowDangerouslySkipPermissions()
                || (permissionGate != null
                    && permissionGate.currentMode() == PermissionMode.BYPASS_PERMISSIONS),
            UiSettings::readSkipDangerousModePermissionPrompt,
            UiSettings::persistDangerousModePermissionPrompt,
            terminalRows,
            keybindings);
        ReplInterruptActions interrupt = new ReplInterruptActions(
            () -> bashMode,
            this::hasActiveTurn,
            new QuerySessionAbortTarget(queryEngine),
            interactionCoordinator,
            () -> inputPanel,
            guiInvoker,
            ctx.lastSubmittedInput()::get,
            () -> submission != null && submission.lastSubmittedInputWasInteractiveStartupPrompt());
        ReplExitController exit = ReplExitController.standard(
            application.shutdown(),
            interrupt,
            message -> transcript.line("  " + message, LanternaTheme.welcomeDim()),
            host::stop,
            new ReplExitController.JobControlActions() {
                @Override public void beforeSuspend() { host.suspendForJobControl(); }
                @Override public void afterResume() { host.resumeAfterJobControl(); }
            }, interactiveSessions, runtime.currentWorktree(), keybindings);
        collaborationPickerDialog.setExitGestureHandler(key -> {
            if (key == 'c') exit.handleCtrlC();
            else if (key == 'd') exit.handleCtrlD();
        });
        feishuSetupDialog.setExitGestureHandler(key -> {
            if (key == 'c') exit.handleCtrlC();
            else if (key == 'd') exit.handleCtrlD();
        });
        DiagnosticsFeature diagnostics = new DiagnosticsFeature(
            gui, transcript, application.doctor(), skills, interactiveSessions,
            ctx.terminalColumns(), ZoneId.systemDefault(), keybindings);
        BackgroundTasksFeature tasks = new BackgroundTasksFeature(
            gui, inputPanel, transcript, runtime.taskRegistry(), runtime.workflowRuns(),
            () -> interactiveSessions.workflowRunPath(System.getProperty("user.dir"),
                queryEngine.conversation().getSessionId(), "wf_history").getParent(),
            () -> { if (transcriptController != null) transcriptController.teammateViewChanged(); },
            this::handleInput,
            keybindings);
        CompactProgressPresenter compactProgress = new CompactProgressPresenter(spinnerComponent);
        ImmediateCommandUiAdapter immediateAdapter =
            new ImmediateCommandUiAdapter(inputPanel, messagePanel, guiInvoker);
        bashMode = new BashModeExecutor(gui, messagePanel, queryEngine, interactiveSessions,
            interactionCoordinator,
            (image, onClose) -> ItermImagePreviewWindow.show(gui, image, onClose));
        // See FileSuggestionService for cache, throttling, git-index mtime, and stale-VT gen
        // semantics.
        FileSuggestionService fileSuggestionService = new FileSuggestionService(gui, inputPanel);

        SelectionController selectionController = new SelectionController(gui, messagePanel, true);
        Selection selection = selectionController.getSelection();
        selectionController.setBareClickHandler(inputPanel::handlePromptBareClick);
        // Screen-level selection: the GUI intercepts selection mouse events above window
        // dispatch and paints the highlight over the full back buffer after every draw.
        gui.wireSelection(selection, selectionController::handleMouse,
            mouse -> inputPanel.handleProjectsButtonMouse(mouse)
                || inputPanel.handleTasksPillMouse(mouse)
                || inputPanel.handleCoordinatorPanelMouse(mouse));
        // SlashHost is a pure command port; the components a slash command reads/renders into
        // are injected as plain references (see ReplRefs / SlashHost).
        ReplRefs replRefs = new ReplRefs(gui, messagePanel, inputPanel, ctx.messageHistory(),
            ctx.dispatcher(), queryEngine, permissionGate);
        SlashCommandDispatcher slashDispatcher = new SlashCommandDispatcher(
            ctx.slashHost(), replRefs, ctx.commandRegistry(), ctx.commandContext(),
            skills, runtime.skillHookRegistrar());
        // Conversation / session lifecycle (resume / replay / rewind / summarize).
        var topicTitles = ctx.sessionTopicTitleCoordinator();
        var terminalController = ctx.terminalController();
        SessionController session = new SessionController(
            gui, screen, queryEngine, ctx.commandContext(), messagePanel,
            ctx.messageHistory(), ctx.collapser(), inputPanel,
            () -> permissionGate, application.sessionLifecycle(),
            application.conversationReset(),
            () -> { if (topicTitles != null) topicTitles.resetForNewSession(); },
            () -> { if (topicTitles != null) topicTitles.markExistingSession(); },
            title -> { if (terminalController != null) terminalController.setTitle(title); },
            sessionId -> {
                if (interactionCoordinator != null) interactionCoordinator.cancelSession(sessionId);
            },
            welcome::renderFresh,
            ctx.sessionHostPublisher()::publishActiveSession,
            interactiveSessions, runtime.invokedSkills(),
            () -> exit.requestShutdown("prompt_input_exit", 0));
        session.setKeybindingsStore(keybindings);
        session.setModelChanged(host::modelChanged);
        ProjectDrawerActions drawerActions = new ProjectDrawerActions(
            guiInvoker, session, ctx.commandContext(), interactiveSessions, projectPanel, transcript);
        ProjectPanelController projectPanelController = new ProjectPanelController(
            application.projects(), projectPanel,
            task -> Thread.ofVirtual().name("project-catalog-io").start(task),
            guiInvoker,
            new ProjectPanel.Actions(
                drawerActions::resume,
                drawerActions::delete,
                drawerActions::preview,
                () -> {
                    inputPanel.setSuppressed(false);
                    inputPanel.setProjectsButtonActive(false);
                },
                null));
        transcriptController = new TranscriptController(
            gui, screen, messagePanel, spinnerComponent, inputPanel,
            ctx.messageHistory(), ctx.collapser(), runtime.taskRegistry(), interactiveSessions);
        transcriptController.setKeybindingsStore(keybindings);
        transcriptController.setAgentTranscriptResolver(agentId -> {
            // Web-gateway headless sessions record their own project's main transcript;
            // resolve those first so viewing one reads it live.
            Path headless = interactiveSessions.headlessTranscriptPath(agentId);
            if (headless != null) return headless;
            return interactiveSessions.agentTranscriptPath(System.getProperty("user.dir"),
                queryEngine.conversation().getSessionId(), agentId);
        });
        LocalAgentInputRouter localAgentInput = new LocalAgentInputRouter(
            runtime.taskRegistry(),
            (agentId, prompt, context, userInitiated) -> {
                Tool<?, ?> registered = toolRegistry.get("Agent").orElse(null);
                if (!(registered instanceof AgentTool agentTool)) {
                    throw new IllegalStateException("Agent tool is unavailable");
                }
                new AgentContinuationService(agentTool.subAgentFactory())
                    .resume(agentId, prompt, context, userInitiated);
            },
            () -> AgentToolExecutionContexts.current(queryEngine),
            transcriptController::appendLocalAgentUserMessage,
            failure -> messagePanel.appendLine("  " + failure, LanternaTheme.toolError()));

        // The persistent vertical main+local-agent list inside the prompt footer, before the
        // permanent Collaboration row. InputPanel merges it with the optional background-task
        // pill as one tasks selection state.
        CoordinatorTaskPanel coordinatorTaskPanel = new CoordinatorTaskPanel();
        CoordinatorNavigationController coordinatorNavigation =
            new CoordinatorNavigationController(runtime.taskRegistry());
        inputPanel.setTaskRegistry(runtime.taskRegistry());
        inputPanel.setWorkflowRunStore(runtime.workflowRuns());
        inputPanel.setCoordinatorNavigation(
            coordinatorNavigation, coordinatorTaskPanel, runtime.taskRegistry()::resolveAgentName);
        MessageActionsController messageActions = new MessageActionsController(
            ctx.terminal(), screen, messagePanel, inputPanel, session::editMessageFromActions);

        // Turn lifecycle — headless TurnEngine (stream loop + queue + interrupt/rewind/cleanup)
        // driving a LanternaSessionSink (all Lanterna rendering). The engine owns turnInFlight +
        // the queue; callers read them via turnEngine.isInFlight()/enqueue()/countQueued().
        PokemonFeature[] pokemonRef = new PokemonFeature[1];
        BtwFeature[] btwRef = new BtwFeature[1];
        LanternaSessionSink turnView = new LanternaSessionSink(
            guiInvoker,
            messagePanel, inputPanel, spinnerComponent, terminalController,
            ctx.dispatcher(), ctx.collapser(), ctx.messageHistory(), queryEngine,
            this::scheduleStatusLineUpdate, host::currentModel, () -> btwRef[0].readUseCount(),
            application.compactWarnings(), launch.tipSupplier(), () -> {
                runtime.loopWakeups().onTurnIdle();
                if (cronScheduler != null) cronScheduler.checkNow();
                host.turnCompleted();
            },
            tokens -> pokemonRef[0].addExperience(tokens));
        turnViewRef[0] = turnView;
        // Mid-turn HUD progress. Resolved lazily against the controller built
        // further down, so the sink never sees a half-built statusLine.
        turnView.setProgressStatusLineRefresh(this::scheduleStatusLineProgressUpdate);
        session.setRewindStateReset(turnView::resetBackgroundWaitForRewind);
        SessionEventHub sessionEvents = new SessionEventHub(turnView,
            failure -> log.warn("Session Link observer failed", failure));
        // The end-of-turn row reports what is still running in the background.
        turnView.setTaskRegistry(runtime.taskRegistry());
        turnView.setTaskBoardLoadingListener(taskBoard::setLoading);
        turnView.setTaskBoardOwnersChangedListener(taskBoard::refreshProjection);
        // Interrupt salvage re-records what the user typed into prompt history.
        turnView.setInterruptSalvage(restoredInput -> {
            if (StringUtils.isNotBlank(restoredInput)) {
                ctx.promptHistory().addEntry(restoredInput,
                    queryEngine.conversation().getSessionId(),
                    System.getProperty("user.dir"), ctx.historyProjectRoot(), Map.of());
            }
        });
        AutoModeEntryWarningController autoModeEntryWarning = AutoModeEntryWarningController.standard(
            message -> appendPersistentSystemMessage(
                message, turnView, inputPanel, messagePanel));
        turnEngine = new TurnEngine(
            queryEngine, () -> permissionGate, sessionEvents, session.conversationOps(ctx.promptHistory()),
            batch -> submission.executeQueuedCommands(batch),
            guiInvoker,
            r -> Thread.ofVirtual().name("api-query").start(r),
            ctx.lastSubmittedInput()::set,
            application.awakeGuard(),
            application.hooks()::clearSessionHooks,
            // Auto-rewind only when the prompt is empty (don't clobber in-flight typing) and the
            // user is not viewing a teammate's transcript (don't rewind the main conversation
            // behind their back).
            () -> inputPanel.getText().isEmpty(),
            () -> ViewedTeammateHolder.instance().isViewing());
        session.setRewindInterruptRequired(this::turnInFlight);
        session.setRewindDeferrer(turnEngine::runWhenIdle);
        session.setAsyncRewindDeferrer(turnEngine::runWhenIdleAsync);
        turnEngine.setInputQueueListener(commands ->
            guiInvoker.accept(() -> inputPanel.setQueuedCommands(commands)));
        submission = new ReplSubmissionCoordinator(
            inputPanel, ctx.promptHistory(), ctx.commandRegistry(), ctx.commandContext(),
            immediateAdapter, bashMode, slashDispatcher, ctx.historyProjectRoot(),
            new ReplSubmissionCoordinator.TurnSubmission(
                queryEngine, turnEngine, turnView, topicTitles,
                runtime.invokedSkills(), transcript::system, guiInvoker),
            new ReplSubmissionCoordinator.PlanContinuation(
                session, permissionGate, interactiveSessions));
        // Session Host publication needs the event hub + native submission path; both exist now.
        ctx.sessionHostPublisher().bind(new SessionHostPublisher.Bindings(
            sessionEvents, submission, slashDispatcher, session));
        BtwFeature btw = new BtwFeature(
            gui, screen, inputPanel, transcript, toolRegistry, queryEngine, ctx.commandContext(),
            () -> AgentToolExecutionContexts.current(queryEngine));
        btwRef[0] = btw;
        PreferencesFeature preferences = new PreferencesFeature(
            gui, inputPanel, ctx.terminalRows(),
            queryEngine, ctx.commandRegistry(), ctx.commandContext(),
            application.doctor(), application.outputStyles(), host::setThemeScheme, transcript,
            launch.customModels());
        preferences.setBuiltInModelFamiliesVisible(launch.showBuiltInModelFamilies());
        preferences.setKeybindingsStore(keybindings);
        preferences.setEffortChanged(this::refreshStatusLineNow);
        var hotUiReadiness = preferences.startHotUiPreparation();
        PermissionsFeature permissions = new PermissionsFeature(
            gui, inputPanel, ctx.commandContext(), permissionGate, transcript);
        permissions.setKeybindingsStore(keybindings);
        AgentsFeature agents = new AgentsFeature(
            gui, inputPanel, application.memory(), ctx.commandContext(),
            () -> toolRegistry != null
                ? toolRegistry.getAll().stream().map(Tool::name).toList()
                : host.toolNames(),
            transcript,
            submission::handleQuery,
            runtime.taskRegistry(),
            tasks::viewAgentTask);
        agents.setKeybindingsStore(keybindings);
        SandboxFeature sandbox = new SandboxFeature(gui, inputPanel, transcript);
        MemoryFeature memory = new MemoryFeature(gui, screen, application.memory(), transcript);
        memory.setKeybindingsStore(keybindings);
        PokemonFeature pokemon = new PokemonFeature(
            gui, screen, messagePanel, inputPanel, welcomePanel, welcomeBlock,
            host::currentModel, transcript);
        pokemonRef[0] = pokemon;
        MCPController mcp = new MCPController(gui, inputPanel, transcript, application.mcp(), keybindings);
        // Hooks browser: snapshot loading + settings hot-reload subscription. Tool names are
        // mutable; the application hook port is stable for the session.
        HooksController hooks = new HooksController(gui, inputPanel, transcript,
            host::toolNames, application.hooks(), keybindings);
        // Every slash command reaches its UI through this single bridge; nothing above this
        // line should hand a feature reference to the command layer directly.
        application.commandUi().install(new ReplCommandUiBridge.Capabilities(
            preferences, permissions, agents, sandbox, memory, session, conversationTools,
            diagnostics, hooks, mcp, tasks, plugins, goal, compactProgress, pokemon, btw,
            webGateway));
        // Bridge background-task (bash / subagent) terminal transitions into the session message
        // queue as <task-notification> messages (see TaskNotificationBridge).
        new TaskNotificationBridge(queryEngine.conversation().getMessageQueue()).register();
        // …and let any such arrival wake an idle REPL.
        turnEngine.bindIdleQueueWakeup(() -> submission.longRunningInFlight());
        // Expose the session queue to background bash tasks so the stall watchdog can enqueue an
        // interactive-prompt notification; matches the HookEngine.setMessageQueue wiring at the
        // CLI root.
        runtime.taskRegistry().setMessageQueue(queryEngine.conversation().getMessageQueue());

        var widgets = new ReplGraph.Widgets(
            messagePanel, spinnerComponent, inputPanel, projectPanel, coordinatorTaskPanel,
            lspRecommendationDialog, pluginHintMenu, thinkingToggleDialog,
            collaborationPickerDialog, feishuSetupDialog);
        var features = new ReplGraph.Features(
            toolApproval, taskBoard, startupGates, bypassPermissionsGate, preferences, permissions,
            agents, sandbox, memory, session, conversationTools, diagnostics, tasks, plugins, goal,
            hooks, mcp, pokemon, btw, exit, compactProgress, webGateway);
        ReplSceneLayout.install(scene, widgets, features);

        var dispatcher = ctx.dispatcher();
        dispatcher.setToolTagLookup(request -> toolRegistry.resolveToolUseTag(
            request.toolName(), request.inputJson(), new ToolUseRenderContext(
                request.toolUseId(), request.toolUseResult(), request.progressMessages(),
                queryEngine.configuration().getConfig().model())));
        dispatcher.setInlineHeaderLookup(ToolInlineHeaderResolver::resolve);
        // Resolved lazily via ToolRegistry so dynamically-registered (MCP) tools are honored;
        // drives LanternaMessageDispatcher's transparent header suppression.
        dispatcher.setTransparentWrapperLookup(
            name -> toolRegistry.get(name).map(Tool::isTransparentWrapper).orElse(false));

        // ── Main window ──────────────────────────────────────────────────────
        // Scroll keys, mouse selection, Ctrl+Shift+C copy and Ctrl+C interrupt are handled at
        // the window level — before the focused InputPanel sees them — so they work in every
        // mode. The routing lives in WindowInputRouter.
        BasicWindow mainWindow = scene.attach(gui, new WindowInputRouter(
            scene.overlays(), messagePanel, selection, selectionController,
            exit::handleCtrlC, keybindings, taskBoard::refreshProjection));
        // InputPanel is mounted before the root is attached, so Lanterna never invokes its
        // Component#onAdded callback. Start the background-task footer refresh explicitly once
        // the live scene exists.
        inputPanel.startTaskPillRefresh();

        // ── Input callbacks ─────────────────────────────────────────────────
        inputPanel.setHasMessages(() -> !queryEngine.conversation().getMessages().isEmpty());
        inputPanel.setPromptHistory(ctx.promptHistory());
        inputPanel.setLiveHistorySupplier(transcriptController::viewedPromptHistory);
        inputPanel.setHistoryContext(
            queryEngine.conversation().getSessionId(), ctx.historyProjectRoot());
        boolean[] promptBatchActive = new boolean[1];
        boolean[] settingsBatchActive = new boolean[1];
        gui.wireInputBatch(() -> {
            settingsBatchActive[0] = preferences.isSettingsActive();
            promptBatchActive[0] = !settingsBatchActive[0]
                && !scene.overlays().hasActiveOverlay();
            if (settingsBatchActive[0]) preferences.beginInputBatch();
            if (promptBatchActive[0]) inputPanel.beginGuiInputBatch();
        }, () -> {
            try {
                if (settingsBatchActive[0]) preferences.endInputBatch();
            } finally {
                if (promptBatchActive[0]) inputPanel.endGuiInputBatch();
                settingsBatchActive[0] = false;
                promptBatchActive[0] = false;
            }
        });
        gui.wirePlainTextBatch((focused, text) -> {
            if (scene.overlays().routePlainText(text)) return true;
            return gui.getActiveWindow() == mainWindow
                && inputPanel.handleGuiTextBatch(focused, text);
        });
        gui.wireBackspaceBatch((focused, count) -> {
            if (scene.overlays().routeRepeatedKey(new KeyStroke(KeyType.BACKSPACE), count)) {
                return true;
            }
            return gui.getActiveWindow() == mainWindow
                && inputPanel.handleGuiBackspaceBatch(focused, count);
        });
        gui.wireInlineOverlayInput(scene.overlays()::routeDirect);
        gui.wireTerminalGestures(key -> {
            if (!ReplExitController.isSuspendGesture(key)) return false;
            exit.handleJobControlSuspend("ctrl-z");
            return true;
        });

        String initialSessionName = StringUtils.trimToNull(launch.initialSessionName());
        if (initialSessionName != null) inputPanel.setAgentName(initialSessionName);
        toolApproval.install();

        // Live query → slash-command + @-file/dir typeahead.
        SuggestionController suggestion = new SuggestionController(
            gui, inputPanel, ctx.commandRegistry(), slashDispatcher,
            fileSuggestionService, ctx.directorySuggestions(),
            ctx.terminalColumns().getAsInt(), skills);

        // Fill horizontal divider lines in input panel.
        inputPanel.setWidth(ctx.terminalColumns().getAsInt());

        // Drives the user's statusLine command; renders its (ANSI-colored, possibly multi-line)
        // output into the InputPanel footer. Refreshed on each assistant message (including
        // tool-loop API rounds), turn-complete, permission-mode, and vim-mode changes, plus once now.
        // The built-in HUD additionally follows turn progress through
        // scheduleProgressUpdate (rate-limited); a user statusLine command does not.
        ReplStatusLineIngredients statusLineIngredients = new ReplStatusLineIngredients(
            queryEngine, interactiveSessions, () -> permissionGate, launch.customModels(),
            inputPanel::getVimMode);
        statusLine = new StatusLineController(
            application.statusLine(),
            statusLineIngredients::ingredients,
            () -> queryEngine.conversation().getMessages(),
            guiInvoker,
            inputPanel::setStatusLine,
            inputPanel::clearStatusLine,
            UiSettings::isClaudeHudEnabled,
            () -> Math.max(1, ctx.terminalColumns().getAsInt() - 4),
            statusLineIngredients::effort);
        statusLine.scheduleInitialUpdate();

        if (CronFeatureGate.system().cronEnabled()) {
            LeadScheduledTaskPresenter leadTasks = new LeadScheduledTaskPresenter(
                guiInvoker, queryEngine, dispatcher, messagePanel, turnEngine);
            ScheduledTaskInteractionRouter scheduledTaskRouter =
                new ScheduledTaskInteractionRouter(
                    runtime.taskRegistry()::injectUserMessageToActiveTeammate,
                    CronStore::removeById,
                    leadTasks::present);
            cronScheduler = new CronScheduler(
                this::turnInFlight,
                scheduledTaskRouter::route,
                () -> queryEngine.conversation().getSessionId());
            Thread.ofVirtual().name("cron-startup").start(cronScheduler::start);
        }
        // The first frame publishes one complete, immutable scene graph. Dialogs may change
        // visibility/data later, but no component or input route may be attached after this.
        scene.seal();

        return new ReplGraph(
            widgets,
            features,
            new ReplGraph.Controllers(
                projectPanelController, startupGateController, interrupt, selectionController,
                transcriptController, localAgentInput, messageActions, autoModeEntryWarning,
                suggestion, statusLine, cronScheduler, welcome),
            new ReplGraph.Engine(
                turnView, sessionEvents, turnEngine, submission, slashDispatcher, bashMode,
                hotUiReadiness),
            mainWindow);
    }

    private boolean turnInFlight() {
        return turnEngine != null && turnEngine.isInFlight();
    }

    private boolean hasActiveTurn() {
        return turnEngine != null && turnEngine.hasActiveTurn();
    }

    private void handleInput(String input) {
        host.userInteracted(input);
        submission.handleInput(input);
    }

    private void scheduleStatusLineUpdate() {
        if (statusLine != null) statusLine.scheduleUpdate();
    }

    /** Turn-progress HUD refresh; rate-limited by the controller, not here. */
    private void scheduleStatusLineProgressUpdate() {
        if (statusLine != null) statusLine.scheduleProgressUpdate();
    }

    /** Refreshes model-sensitive HUD state without the ordinary interaction debounce. */
    private void refreshStatusLineNow() {
        if (statusLine != null) statusLine.scheduleInitialUpdate();
    }

    /** A system message that is both rendered and persisted to the session transcript. */
    private void appendPersistentSystemMessage(SystemMessage message, LanternaSessionSink turnView,
                                               InputPanel inputPanel, MessagePanel messagePanel) {
        if (message == null) return;
        ctx.guiInvoker().accept(() -> {
            turnView.prepareStartupSystemTranscriptMetadata(inputPanel.getPermissionMode());
            ctx.queryEngine().conversation().appendTranscriptMessage(message);
            SDKMessage.System sdk = new SDKMessage.System(message);
            ctx.messageHistory().record(sdk);
            ctx.dispatcher().dispatch(sdk, messagePanel);
        });
    }
}
