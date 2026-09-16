package com.claudecode.ui.lanterna.repl;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.imagestore.ImageStore;
import com.claudecode.core.lsp.LspPluginRecommendation;
import com.claudecode.core.lsp.LspRecommendationResponse;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.state.CwdState;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.sessionhost.CollaborationSetupPort;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.QueuedInputDraft;
import com.claudecode.runtime.turn.TurnEngine;
import com.claudecode.tools.cron.CronScheduler;
import com.claudecode.tools.hints.ClaudeCodeHint;
import com.claudecode.tools.hints.ClaudeCodeHintStore;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.tasks.PendingBackgroundWork;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.ui.lanterna.components.ModelDisplayName;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.dialog.CollaborationPickerDialog;
import com.claudecode.ui.lanterna.dialog.FeishuSetupDialog;
import com.claudecode.ui.lanterna.dialog.HistorySearchDialog;
import com.claudecode.ui.lanterna.dialog.LspRecommendationDialog;
import com.claudecode.ui.lanterna.dialog.PermissionDialog;
import com.claudecode.ui.lanterna.dialog.PluginHintMenu;
import com.claudecode.ui.lanterna.dialog.SudoPasswordDialog;
import com.claudecode.ui.lanterna.dialog.ThinkingToggleDialog;
import com.claudecode.ui.lanterna.features.projects.ProjectPanel;
import com.claudecode.ui.lanterna.features.projects.ProjectPanelController;
import com.claudecode.ui.lanterna.features.settings.AutoModeEntryWarningController;
import com.claudecode.ui.lanterna.features.settings.BypassPermissionsStartupGate;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.features.web.WebGatewayFeature;
import com.claudecode.ui.lanterna.input.InputActions;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.input.PromptExternalEditor;
import com.claudecode.ui.lanterna.input.PromptHistory;
import com.claudecode.ui.lanterna.mouse.SelectionController;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.slash.SlashHost;
import com.claudecode.ui.lanterna.statusline.StatusLineController;
import com.claudecode.ui.lanterna.suggest.DirectorySuggestionService;
import com.claudecode.ui.lanterna.suggest.SuggestionController;
import com.claudecode.ui.lanterna.theme.ClaudeTheme;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.BackgroundTaskPill;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessageActionsController;
import com.claudecode.ui.lanterna.transcript.MessageCollapser;
import com.claudecode.ui.lanterna.transcript.MessageHistory;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.Selection;
import com.claudecode.ui.lanterna.transcript.SelectionAwareTextGUI;
import com.claudecode.ui.lanterna.transcript.ToolPresentationSnapshotStore;
import com.claudecode.ui.lanterna.transcript.TranscriptController;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.Screen.RefreshType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.ExtendedTerminal;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interactive REPL screen: owns the Lanterna terminal/GUI lifecycle, hands scene construction to
 * {@link ReplComposer} in {@link #composeScene()}, and implements the {@link SlashHost} command
 * port that the slash dispatcher and {@link InputActions} call back into.
 *
 * <p>Ownership boundaries (each enforced by {@code ReplFeatureArchitectureTest}):
 * <ul>
 *   <li>Scene construction and wiring: {@link ReplComposer} builds the {@link ReplGraph} from a
 *       {@link ReplContext}; the graph reaches back only through the narrow
 *       {@link ReplComposer.Host} port implemented by {@link ComposerHost}.</li>
 *   <li>Overlay registration and z-order mounting: {@link ReplSceneLayout} over
 *       {@link ReplScene}.</li>
 *   <li>Text → turn pipeline, history, queue hand-off: {@link ReplSubmissionCoordinator}.</li>
 *   <li>Session Host publication / remote control: {@link SessionHostPublisher}.</li>
 *   <li>Status-line ingredients: {@link ReplStatusLineIngredients} + {@link StatusLineController}.</li>
 *   <li>Slash-command dialogs: feature facades installed into {@link ReplCommandUiBridge}
 *       (conversation tools, diagnostics, tasks, plugins, goal, hooks, MCP, pokemon, btw,
 *       compact progress, session lifecycle). The CLI binds launchers to the bridge only.</li>
 * </ul>
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code screens/REPL.tsx} — top-level interactive screen: message stream + prompt
 *       input + inline overlays, turn-state gating, and the slash-command host surface.</li>
 *   <li>{@code components/PromptInput/} — the parts of prompt submission not yet moved into
 *       {@link ReplSubmissionCoordinator} (bash-mode routing, interrupt gestures).</li>
 *   <li>{@code utils/terminal.ts} / {@code utils/Cursor.ts} — terminal setup, cursor style,
 *       job-control suspend/resume, resize handling.</li>
 * </ul>
 */
public class LanternaReplScreen implements SlashHost {

    private static final Logger log = LoggerFactory.getLogger(LanternaReplScreen.class);

    // ── Lanterna core ──────────────────────────────────────────────────────
    private Terminal            terminal;
    private EscapeSequenceInputStream terminalInput;
    private TerminalScreen      screen;
    private SelectionAwareTextGUI gui;
    private PrestartTextGUIThreadFactory prestartGuiThreadFactory;
    private BasicWindow         mainWindow;
    private volatile boolean terminalReleasedForExit;

    // ── UI Components ──────────────────────────────────────────────────────
    private MessagePanel    messagePanel;
    private SpinnerComponent spinnerComponent;
    private InputPanel      inputPanel;

    private StatusLineController statusLineController;
    /** Owns terminal escape-sequence I/O (title / OSC 9;4 progress / OSC 21337
     *  tab status / extended-key detection / screen dump). Built in {@link #initTerminal}. */
    private TerminalController terminalController;
    /** Inline LSP-plugin recommendation prompt — sibling of
     *  the approval question dialog; collapses to (0,0) when idle and is shown
     *  (via {@link #showLspRecommendation}) when the user opens a file whose
     *  language has a recommendable, already-installed LSP plugin. */
    private LspRecommendationDialog lspRecommendationDialog;
    /** Inline plugin-hint menu — activated by {@link #showPluginHintMenu} when a
     *  tool emits a {@code <claude-code-hint type="plugin" />} tag (the harness
     *  strips the tag and surfaces this install prompt instead). */
    private PluginHintMenu pluginHintMenu;
    /** Inline todo list panel — sits above InputPanel; Ctrl+T cycles compact,
     *  terminal-capacity expanded, and hidden views (app:toggleTodos). */
    private TaskBoardFeature taskBoardFeature;

    /** Left-docked project drawer (Java-side extension, no 197 counterpart);
     *  covers the transcript's left strip when active, toggled by
     *  {@code toggleProjectPanel} — the footer button is its only entry point. */
    private ProjectPanel projectPanel;
    private ProjectPanelController projectPanelController;
    /** Late-bound command-to-feature capability bridge installed when the scene is built. */
    private final ReplCommandUiBridge commandUi;
    /**
     * Inline dialog stack (one active at a time — see {@link InlineOverlay}) and the root
     * layout. Populated and sealed by {@link ReplComposer} through {@link ReplSceneLayout}.
     */
    private final ReplScene scene = new ReplScene();

    /** Meta+T thinking picker and mid-conversation confirmation. */
    private ThinkingToggleDialog thinkingToggleDialog;
    private CollaborationPickerDialog collaborationPickerDialog;
    private FeishuSetupDialog feishuSetupDialog;
    /** Virtual text selection + mouse-driven UX. Owns the {@link Selection}
     *  state, multi-click detection, drag-to-autoscroll, and OSC 52 copy.
     *  Keyboard handlers still drive selection state directly via
     *  {@link SelectionController#getSelection}. */
    private SelectionController selectionController;

    /** Directory/path completions for path-like @-tokens (~/, /, ./, ../). */
    private final DirectorySuggestionService directorySuggestionService =
        new DirectorySuggestionService();
    /** Typeahead orchestrator — slash/@ suggestion decision + command/skill building. */
    private SuggestionController suggestionController;
    /** Conversation / session lifecycle — resume, replay, rewind, summarize. */
    private SessionController sessionController;
    /** Transcript mode + viewed-teammate transcript presentation. */
    private TranscriptController transcriptController;
    private LocalAgentInputRouter localAgentInputRouter;
    /** Message selection/navigation/copy/edit interaction. */
    private MessageActionsController messageActionsController;
    /** Headless turn orchestrator (owns turnInFlight + the in-flight queue). Built by {@link ReplComposer}. */
    private TurnEngine turnEngine;
    /** Owns submitted-text routing and busy-turn queue hand-off. */
    private ReplSubmissionCoordinator submissionCoordinator;

    // ── App state ──────────────────────────────────────────────────────────
    private final QuerySession       queryEngine;
    /** One-time startup wiring handed to {@link ReplComposer} when the scene is composed. */
    private final ReplWiring wiring;
    private final InteractiveSessionPort interactiveSessions;
    private final ReplFeatureRuntime featureRuntime;
    private final CommandRegistry   commandRegistry;
    private final CommandContext    commandContext;
    private final ToolPresentationSnapshotStore presentationSnapshots;
    private final LanternaMessageDispatcher dispatcher;
    /**
     * Tool names passed to getHookEventMetadata for placeholder hints.
     */
    private List<String> toolNames = List.of();
    /** Collapse wrapper — applies applyGrouping / collapseReadSearch passes. */
    private final MessageCollapser  collapser;
    /**
     * Session-scoped message store for replay on Ctrl+O.
     */
    private final MessageHistory    messageHistory = new MessageHistory();
    private final PermissionGate          permissionGate;
    /** Opt-in user keybinding resolver (gate on); null when customization disabled. */
    private final UserKeybindingsStore keybindingsStore;
    /** Session Host publication + remote control surface; bound to scene collaborators by {@link ReplComposer}. */
    private final SessionHostPublisher sessionHostPublisher;
    private final SessionCollaborationController collaborationController;
    private final CollaborationSetupPort collaborationSetup;

    private volatile String model = "";
    private boolean verbose = false;

    /** Welcome banner render/model-line/web-row updates. */
    private WelcomePresenter welcome;
    /** {@code /web} + startup warmup of the third session endpoint. */
    private WebGatewayFeature webGateway;
    /** Ctrl+G external editor over the terminal handoff hooks below. */
    private PromptExternalEditor externalEditor;

    // Transcript search and its query/match state are owned by TranscriptController.

    // ── Queued commands ─────────────────────────────────────────────────────
    // turnInFlight + the in-flight queue now live in TurnEngine (owned per-session).
    // The screen reads/mutates them via turnEngine.isInFlight/enqueue/countQueued.

    private CronScheduler cronScheduler;
    private volatile IdlePromptNotifier idlePromptNotifier;
    private volatile AwaySummaryTrigger awaySummaryTrigger;

    /**
     * The last text submitted by the user.
     */
    private final AtomicReference<String> lastSubmittedInput = new AtomicReference<>();

    /** Persistent prompt history — shared with InputPanel for Up/Down navigation. */
    private final PromptHistory promptHistory = new PromptHistory();

    private final String historyProjectRoot;

    /** Coordinates the argv prompt with asynchronous startup setup gates and initial rendering. */
    private StartupPromptCoordinator startupPromptCoordinator;
    /** Bare {@code -r}: open the session picker once the startup gates resolve. */
    private boolean startupResumePickerRequested;
    /** {@code -r <value>} that matched no single session: the picker opens searching for it. */
    private String startupResumeSearchQuery;
    /** One-time interactive argv prompt supplied by the CLI; null for ordinary REPL startup. */
    private final String initialPrompt;
    /** Explicit CLI display name for the initial logical session only. */
    private final String initialSessionName;

    private final Function<String, CompletableFuture<String>> sessionTitleGenerator;
    /** Explicit CLI restoration state; fresh sessions must title even if JSONL was materialized early. */
    private final boolean restoredSession;
    /** One-shot title lifecycle, separate from /rename's persisted session name. */
    private SessionTopicTitleCoordinator sessionTopicTitleCoordinator;
    /** Interactive turn sink; also owns first-turn transcript metadata ordering. */
    private LanternaSessionSink turnView;
    private final ReplStartupReadiness startupReadiness;
    private CompletionStage<Void> hotUiReadiness = CompletableFuture.completedFuture(null);
    /** Ordered trust/external-include/managed-settings startup state machine. */
    private StartupGateController startupGateController;

    private BypassPermissionsStartupGate bypassPermissionsStartupGate;
    /** Ctrl+C/D, signal, worktree-exit, and shutdown state machine. */
    private ReplExitController exitController;
    /** Shared with {@link #exitController}; ESC reuses its turn-abort rule directly. */
    private ReplInterruptActions interruptActions;

    private AutoModeEntryWarningController autoModeEntryWarning;

    // ──────────────────────────────────────────────────────────────────────

    public LanternaReplScreen(
            QuerySession queryEngine,
            CommandRegistry commandRegistry,
            CommandContext commandContext,
            ReplWiring wiring) {
        this.queryEngine     = queryEngine;
        this.wiring          = wiring;
        this.commandRegistry = commandRegistry;
        this.commandContext  = commandContext;
        this.presentationSnapshots = new ToolPresentationSnapshotStore();
        this.dispatcher      = new LanternaMessageDispatcher(presentationSnapshots);
        this.dispatcher.setPersistedPlanSupplier(() -> PlanFiles.getPlan(
            queryEngine.conversation().getSessionId(), null));
        this.collapser       = new MessageCollapser(dispatcher, false);
        // One-time startup wiring installed atomically (see ReplWiring). Genuinely dynamic
        // settings (model / verbosity / tool names / session color) remain runtime setters.
        ReplApplicationPorts application = wiring.application();
        this.startupReadiness = wiring.startupReadiness();
        this.featureRuntime      = wiring.features();
        this.dispatcher.setTurnSummaryContext(
            () -> PendingBackgroundWork.count(featureRuntime.taskRegistry(),
                queryEngine.conversation().getMessageQueue().snapshot()),
            () -> {
                List<TaskState> running = featureRuntime.taskRegistry().listBackground();
                return running.isEmpty() ? null : BackgroundTaskPill.labelFor(
                    running, featureRuntime.taskRegistry()::isMonitorTask);
            });
        ReplLaunchState launch   = wiring.launch();
        this.permissionGate      = featureRuntime.permissionGate();
        // Optional ports arrive normalized to their inert implementation (see ReplApplicationPorts).
        this.commandUi           = application.commandUi();
        this.interactiveSessions = application.sessions();
        this.keybindingsStore    = launch.keybindings();
        this.dispatcher.setKeybindingsStore(this.keybindingsStore);
        this.collapser.setKeybindingsStore(this.keybindingsStore);
        this.collaborationController = launch.collaborationController();
        this.collaborationSetup = launch.collaborationSetup();
        this.initialPrompt       = launch.initialPrompt();
        this.initialSessionName  = StringUtils.trimToNull(launch.initialSessionName());
        this.restoredSession     = launch.restoredSession();
        this.sessionTitleGenerator = launch.sessionTitleGenerator();
        this.sessionHostPublisher = new SessionHostPublisher(
            launch.sessionHostRegistry(), queryEngine, commandContext, interactiveSessions,
            launch.customModels(), launch.showBuiltInModelFamilies(), collaborationController,
            initialSessionName,
            task -> gui.getGUIThread().invokeLater(task),
            new SessionHostPublisher.Feedback() {
                @Override public void modelChanged(String model) { setModel(model); }
                @Override public void system(String text) { postSystemMessage(text); }
                @Override public void transientHint(String text, int millis) {
                    gui.getGUIThread().invokeLater(() -> inputPanel.showTransientHint(text, millis));
                }
                @Override public void statusLineChanged() {
                    if (statusLineController != null) statusLineController.scheduleUpdate();
                }
            });
        Path stableProjectRoot = CwdState.getOriginalCwd();
        this.historyProjectRoot = PromptHistory.resolveProject(stableProjectRoot != null
            ? stableProjectRoot.toString() : System.getProperty("user.dir"));
    }

    /** Sets tool names used as placeholder hints in the hooks dialog matcher field. */
    public void setToolNames(List<String> names) {
        this.toolNames = names != null ? List.copyOf(names) : List.of();
    }

    /** Blocks the calling Bash tool thread while Lanterna owns a masked local prompt. */
    public SudoPasswordInteraction.Result promptSudoPassword(
            SudoPasswordInteraction.Request request) {
        return SudoPasswordDialog.prompt(gui, request);
    }

    /** Configure verbose mode (wired from CLI --verbose flag). */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
        dispatcher.setVerbose(verbose);
        collapser.setVerbose(verbose);
        // spinnerComponent is created by ReplComposer during run; CLI may
        // call setVerbose before then. Apply eagerly if already built, otherwise
        // ReplComposer will pick it up from the persisted `verbose` field.
        if (spinnerComponent != null) spinnerComponent.setVerbose(verbose);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Shows the inline LSP-plugin recommendation prompt for {@code rec} and
     * blocks the calling (virtual) thread until the user answers or the prompt
     * auto-dismisses. The actual mount runs on the GUI thread; {@code handler}
     * is invoked on the calling thread (off the GUI thread) once the answer is
     * in, so a {@code YES} install can do backend I/O without freezing the TUI.
     *
     * <p>matches {@link PermissionDialog#showAndWait}'s queue pattern but
     * returns to the caller instead of folding into a permission result, and
     * carries the {@code timedOut} flag (true only when the 30s timer fired) so
     * the CLI can count a timeout-dismiss as an "ignore".
     *
     * @param handler receives the user's response and whether it was an
     *                auto-dismiss-on-timeout
     * @return the response (null only if the calling thread was interrupted)
     */
    public LspRecommendationResponse showLspRecommendation(
            LspPluginRecommendation rec,
            BiConsumer<LspRecommendationResponse, Boolean> handler) {
        BlockingQueue<LspRecResult> queue = new ArrayBlockingQueue<>(1);
        gui.getGUIThread().invokeLater(() -> {
            // Inline overlays are mutually exclusive (see WindowInputRouter's
            // assert). Never stack the recommendation on top of an already-active
            // overlay (e.g. a permission prompt) — just skip this session's
            // prompt rather than tripping the single-active invariant.
            boolean conflict = scene.overlays().snapshot().stream()
                .anyMatch(o -> o != lspRecommendationDialog && o.isActive());
            if (conflict) {
                // Fresh capacity-1 queue, empty here — add() always succeeds and
                // surfaces a logic error (IllegalStateException) instead of silently
                // dropping the result if it were ever somehow full.
                queue.add(new LspRecResult(null, false));
                return;
            }
            lspRecommendationDialog.show(
                rec,
                (response, timedOut) -> {
                    try {
                        queue.put(new LspRecResult(response, timedOut));
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                },
                () -> inputPanel.takeFocus(),
                gui);
        });
        try {
            LspRecResult r = queue.take();
            if (r.response() != null && handler != null) {
                handler.accept(r.response(), r.timedOut());
            }
            return r.response();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Queue payload for {@link #showLspRecommendation}: answer + timeout flag. */
    private record LspRecResult(LspRecommendationResponse response, boolean timedOut) {}

    /**
     * Shows the plugin-hint menu when a tool emits a {@code <claude-code-hint
     * type="plugin" />} tag. matches the wiring of {@link #showLspRecommendation}:
     * the {@link ClaudeCodeHintStore} listener (set in {@link #installPluginHintListener}) invokes
     * this on the GUI thread; the menu is shown inline (non-blocking) and the
     * once-per-session flag is flipped as soon as it appears so no further prompt
     * surfaces this session.
     *
     * @param hint    the parsed plugin hint (slug + source command)
     * @param handler called with the user's response and a timeout flag
     */
    public void showPluginHintMenu(ClaudeCodeHint hint,
            BiConsumer<PluginHintMenu.Response, Boolean> handler) {
        BlockingQueue<PluginHintResult> queue = new ArrayBlockingQueue<>(1);
        gui.getGUIThread().invokeLater(() -> {
            // Inline overlays are mutually exclusive — never stack on an active one.
            boolean conflict = scene.overlays().snapshot().stream()
                .anyMatch(o -> o != pluginHintMenu && o.isActive());
            if (conflict) {
                queue.add(new PluginHintResult(null, false));
                return;
            }
            // Flip the once-per-session flag now that the dialog is shown.
            ClaudeCodeHintStore.getInstance().markShownThisSession();
            pluginHintMenu.show(
                hint,
                (response, timedOut) -> {
                    try {
                        queue.put(new PluginHintResult(response, timedOut));
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                },
                () -> inputPanel.takeFocus(),
                gui);
        });
        try {
            PluginHintResult r = queue.take();
            if (r.response() != null && handler != null) {
                handler.accept(r.response(), r.timedOut());
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** Queue payload for {@link #showPluginHintMenu}: answer + timeout flag. */
    private record PluginHintResult(PluginHintMenu.Response response, boolean timedOut) {}

    /** Handles the user's plugin-hint response. */
    private void handlePluginHintResponse(ClaudeCodeHint hint, PluginHintMenu.Response response) {
        switch (response) {
            case INSTALL -> postSystemMessage("Installing suggested plugin: " + hint.value()
                + " — if it does not start automatically, run `/plugin install " + hint.value() + "`.");
            case NOT_NOW, DONT_ASK_AGAIN -> {
                // NOT_NOW: dismissed, may reappear next session.
                // DONT_ASK_AGAIN: already covered by markShownThisSession (no further prompts).
            }
        }
    }

    public void run() throws IOException {
        log.info("[LANTERNA] run() START");
        log.info("[LANTERNA] chalkLevel={} (3=truecolor, 2=256-color, 1=16-color); TMUX={}, TERM_PROGRAM={}, COLORTERM={}, TERM={}",
                LanternaTheme.chalkLevel(),
                System.getenv("TMUX") != null,
                System.getenv("TERM_PROGRAM"),
                System.getenv("COLORTERM"),
                System.getenv("TERM"));
        String startupSessionId = queryEngine.conversation().getSessionId();
        CompletableFuture<ReplStartupPreparation.Prepared> preparedStartup =
            ReplStartupPreparation.start(startupSessionId, interactiveSessions);
        try (TuiOutputGuard _ = initTerminal()) {
        log.info("[LANTERNA] initTerminal() OK, terminal class={}", terminal.getClass().getName());
        // initTerminal starts the GUI thread. Install the title coordinator
        // before composeScene publishes a focused input panel; otherwise a PTY
        // can submit the first prompt in the narrow interval after the footer
        // becomes visible but before title state exists, silently skipping the
        // helper request and left-shifting every wire request number.
        sessionTopicTitleCoordinator = new SessionTopicTitleCoordinator(
            restoredSession || StringUtils.isNotBlank(initialSessionName),
            sessionTitleGenerator,
            title -> {
                var transcript = queryEngine.execution().getTranscriptSink();
                if (transcript != null) {
                    transcript.recordAiTitle(queryEngine.conversation().getSessionId(), title);
                }
                sessionHostPublisher.applyTitle(title);
                gui.getGUIThread().invokeLater(() -> terminalController.setTitle(title));
            });
        composeScene();
        startupReadiness.mark("scene");
        // This is the prestart/caller thread, never Lanterna's live GUI event
        // thread. Scene construction overlaps semantic startup, then the first
        // visible frame and editable prompt are published only after the
        // immutable command/hook/watcher generation is complete.
        CompletableFuture.allOf(
            startupReadiness.inputSemanticReady().toCompletableFuture(),
            hotUiReadiness.toCompletableFuture(),
            preparedStartup).join();
        applyPreparedStartup(preparedStartup.getNow(null), startupSessionId);
        startupReadiness.mark("hot-dialogs");
        startupReadiness.mark("input-ready");
        // Rebuild the project index off the critical path. The first drawer open
        // is the expensive one (stat every transcript directory, lite-read the
        // stale ones); paying it here means the user never waits for it. Delayed
        // so it does not contend with startup's own disk traffic.
        CompletableFuture.runAsync(projectPanelController::warmUp,
            CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS,
                task -> Thread.ofVirtual().name("project-catalog-warmup").start(task)));
        startupPromptCoordinator = new StartupPromptCoordinator(
            initialPrompt,
            startupResumePickerRequested
                ? settled -> sessionController.showSessionPicker(startupResumeSearchQuery, settled)
                : null,
            runnable -> gui.getGUIThread().invokeLater(runnable), this::handleStartupInput);
        Path startupCwd = Path.of(System.getProperty("user.dir"));

        messagePanel.setVisible(false);
        inputPanel.setVisible(false);
        startupGateController.start(startupCwd,
            () -> bypassPermissionsStartupGate.start(
                () -> {
                    messagePanel.setVisible(true);
                    inputPanel.setVisible(true);
                    inputPanel.takeFocus();
                    autoModeEntryWarning.onPermissionModeChanged(inputPanel.getPermissionMode());
                    startupPromptCoordinator.markSetupReady();
                },
                this::requestShutdown),
            this::requestShutdown);
        log.info("[LANTERNA] composeScene() OK, screen size={}", screen.getTerminalSize());

        exitController.registerSignalHandlers();

        // JVM shutdown hook — last-line cleanup. Covers crashes / Cmd+Q paths
        // where the normal finally block does not finish. Reuses the idempotent
        // terminal handoff so alternate screen and input modes unwind once.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (statusLineController != null) statusLineController.close();
            if (taskBoardFeature != null) taskBoardFeature.close();
            try {
                releaseTerminalForExit();
            } catch (Exception _) { /* best-effort */ }

            try {
                String sid = queryEngine.conversation().getSessionId();
                if (StringUtils.isNotBlank(sid)) {
                    interactiveSessions.reAppendSessionMetadata(
                        System.getProperty("user.dir"), sid);
                }
            } catch (Exception _) { /* best-effort */ }
        }, "claude-tui-shutdown"));

        // Show welcome BEFORE the GUI loop's first layout pass, so
        // messagePanel.calculatePreferredSize() returns the right height
        // immediately (otherwise SmartLayout gives it height=1 forever).
        welcome.renderFresh();

        // Eagerly start the web gateway off the first-frame path; the
        // quick-entry row appears in the welcome block once it is live.
        webGateway.warmUp();

        // Cold-start replay for --resume / --continue: if the engine was
        // preloaded with prior messages (ClaudeCodeCli before run()), render
        // them now so the REPL doesn't open with a blank transcript. matches

        List<Message> preloaded = queryEngine.conversation().getMessages();
        if (preloaded != null && !preloaded.isEmpty()) {
            messagePanel.appendLine(
                "  [Resumed session " + queryEngine.conversation().getSessionId() + " — "
                    + preloaded.size() + " message"
                    + (preloaded.size() == 1 ? "" : "s") + " loaded]",
                LanternaTheme.welcomeDim());
            sessionController.replayLoadedMessages(preloaded);
        }
        startupPromptCoordinator.markTranscriptReady();

        // Commit the fully assembled scene as the first GUI frame.
        if (prestartGuiThreadFactory != null) prestartGuiThreadFactory.start();
        startupReadiness.mark("first-frame");

        // Once per startup: scrub image-cache dirs left by previous sessions so
        // ~/.claude/image-cache/ doesn't accumulate one directory per session forever.
        try {
            ImageStore.cleanupOldImageCaches(queryEngine.conversation().getSessionId());
        } catch (Exception _) { /* best-effort */ }

        // If running with VirtualTerminal (non-TTY), process one frame and dump
        if (terminal instanceof DefaultVirtualTerminal) {
            log.info("[VIRTUAL] VirtualTerminal detected — processing one frame");
            try {
                gui.updateScreen();
                screen.refresh();
            } catch (Exception e) {
                log.warn("[VIRTUAL] GUI frame failed", e);
            }
            log.info("[VIRTUAL] Dumping screen");
            terminalController.dumpScreenToStdout();
            log.info("[VIRTUAL] Done. Screen size: {}", screen.getTerminalSize());
            if (inputPanel != null) inputPanel.closeCollaborationBinding();
            releaseTerminalForExit();
            return;
        }

        // Block until window closes (Lanterna's SeparateTextGUIThread handles
        // input/rendering on its own thread)
        try {
            log.info("[LANTERNA] entering waitForWindowToClose()");
            gui.waitForWindowToClose(mainWindow);
            log.info("[LANTERNA] window closed normally");
        } catch (Exception e) {
            log.error("[LANTERNA] GUI loop error", e);
        } finally {
            if (autoModeEntryWarning != null) autoModeEntryWarning.close();
            if (suggestionController != null) suggestionController.close();
            if (inputPanel != null) inputPanel.closeCollaborationBinding();

            // during interaction, then shutdown waits for the active writer and
            // drains any remaining buffer off Lanterna's GUI thread.
            promptHistory.close();
            if (cronScheduler != null) {
                cronScheduler.stop();
            }
            if (idlePromptNotifier != null) idlePromptNotifier.close();
            if (awaySummaryTrigger != null) awaySummaryTrigger.close();
            releaseTerminalForExit();
        }
        } finally {

            // VirtualTerminal early return, and startup/runtime exceptions.
            if (statusLineController != null) statusLineController.close();
            if (idlePromptNotifier != null) idlePromptNotifier.close();
            if (awaySummaryTrigger != null) awaySummaryTrigger.close();
            if (taskBoardFeature != null) taskBoardFeature.close();
            releaseTerminalForExit();
        }
    }

    private void handleCtrlD() {
        exitController.handleCtrlD();
    }

    @Override
    public void stop() {
        releaseTerminalForExit();
        if (gui != null) {
            try { gui.getGUIThread().invokeLater(() -> {
                if (gui.getActiveWindow() != null) gui.getActiveWindow().close();
            }); } catch (Exception _) {}
        }
    }

    private synchronized void releaseTerminalForExit() {
        if (terminalReleasedForExit || terminal == null || screen == null) {
            closeTerminalInput();
            return;
        }
        try {
            terminal.disableBracketedPaste();
        } catch (Exception e) {
            log.debug("[LANTERNA] disableBracketedPaste failed (non-fatal)", e);
        }
        try {
            terminal.disableFocusReporting();
        } catch (Exception _) {
            // Non-fatal.
        }
        try {
            if (TerminalController.supportsExtendedKeys()) terminal.disableKittyKeyboard();
        } catch (Exception _) {
            // Non-fatal.
        }
        try {
            if (terminalController != null) {
                terminalController.progressClear();
                terminalController.clearTabStatus();
            }
            terminal.setCursorStyle(CursorStyle.DEFAULT);
        } catch (Exception _) {
            // Non-fatal.
        }
        try {
            // Give the shell its main buffer back before graceful shutdown
            // prints the resume hint or a non-zero signal path halts the JVM.
            disableMouseBeforeHandoff();
            screen.stopScreen();
            terminalReleasedForExit = true;
            log.info("[LANTERNA] screen.stopScreen() OK");
        } catch (Exception e) {
            log.warn("[LANTERNA] stopScreen failed", e);
        }
        closeTerminalInput();
    }

    private void closeTerminalInput() {
        if (terminalInput == null) return;
        terminalInput.close();
        terminalInput = null;
    }

    /** Delegates exit-flow and graceful-shutdown orchestration. */
    @Override
    public void requestShutdown(String reason, int exitCode) {
        exitController.requestShutdown(reason, exitCode);
    }

    public void setModel(String model) {
        this.model = model;
        if (permissionGate != null) permissionGate.setAutoModeCurrentModel(model);

        // observe the new model in the same render cycle. Java's status line is
        // event-driven rather than reactive; explicitly refresh it here instead
        // of waiting for the next assistant message or turn completion.
        executeStatusLineCommandImmediately();
        // Model commands can finish on a virtual thread; the presenter hops to the GUI thread.
        // Startup calls (before the scene exists) only seed the volatile model field.
        if (welcome != null) welcome.repaintModelLine();
    }

    public void applyModelSelection(String model) {
        setModel(model != null ? model : ModelNames.defaultMainLoopModel());
        saveModelSetting(model);
    }

    /** Persists {@code false}, or removes the default-on setting when enabled. */
    private void saveThinkingEnabled(boolean enabled) {
        UiSettings.writeUserSettingAsync("alwaysThinkingEnabled", enabled ? null : false)
            .whenComplete((_, failure) -> {
                if (failure != null) {
                    log.warn("Failed to persist alwaysThinkingEnabled: {}",
                        rootMessage(failure));
                }
            });
    }

/** Persists {@code model} to  as {@code "model"}. */
    private void saveModelSetting(String model) {
        UiSettings.writeUserSettingAsync("model", model)
            .whenComplete((_, failure) -> {
                if (failure != null) {
                    log.warn("Failed to persist model setting: {}", rootMessage(failure));
                }
            });
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }


    /**
     * Toggles the left-docked project drawer (≡ footer button).
     * Java-side extension with no 197 counterpart.
     */
    public void toggleProjectPanel() {
        if (gui == null || projectPanel == null || projectPanelController == null) return;
        gui.getGUIThread().invokeLater(() -> {
            // Suppress mirrors the onClose callback's unsuppress; the
            // controller fires onClose for every close path (Esc, ←, toggle).
            boolean opening = !projectPanel.isActive();
            if (opening && inputPanel != null) inputPanel.setSuppressed(true);
            if (inputPanel != null) inputPanel.setProjectsButtonActive(opening);
            projectPanelController.toggle();
        });
    }

    /**
     * Applies a user-selected prompt-bar color from {@code /color}.
     */
    public void setSessionColor(String colorName) {
        if (inputPanel == null) return;
        if (gui != null) {
            gui.getGUIThread().invokeLater(() -> {
                inputPanel.setSessionColor(colorName);
                try { gui.updateScreen(); } catch (Exception _) {}
            });
        } else {
            inputPanel.setSessionColor(colorName);
        }
    }

    /**
     * Applies a user-selected theme from {@code /config set theme <name>}.
     */
    public void setThemeScheme(String schemeName) {
        LanternaTheme.Scheme scheme = LanternaTheme.schemeFromName(schemeName);
        if (scheme == null) return;
        LanternaTheme.setScheme(scheme);
        if (gui != null) {
            gui.getGUIThread().invokeLater(() -> {
                try { gui.updateScreen(); } catch (Exception _) {}
            });
        }
    }

    /**
     * Sets the always-thinking flag to a specific value from {@code /config set
     * thinkingEnabled <bool>} — unlike {@link ReplInputActions#toggleThinking}
     * (the Meta+T picker), this sets it directly.
     * Persists to  and applies live to
     * {@link com.claudecode.runtime.query.QuerySessionSpec}.
     */
    public void setThinkingEnabled(boolean enabled) {
        queryEngine.configuration().getConfig().setThinkingEnabled(enabled);
        saveThinkingEnabled(enabled);
    }

    /** Refreshes the status line after the {@code claudeHudEnabled} setting changes. */
    public void refreshClaudeHud() {
        if (statusLineController != null) statusLineController.scheduleUpdate();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Initialization
    // ──────────────────────────────────────────────────────────────────────

    private TuiOutputGuard initTerminal() throws IOException {
        log.info("[LANTERNA] initTerminal step 1: creating factory");
        terminalInput = new EscapeSequenceInputStream(TuiOutputGuard.terminalInput());
        DefaultTerminalFactory factory = new DefaultTerminalFactory(
            TuiOutputGuard.terminalOutput(), terminalInput, Charset.defaultCharset())
            .setForceTextTerminal(true)
            .setInitialTerminalSize(null)
            // Trap Ctrl+C as a keystroke instead of Lanterna's default
            // CTRL_C_KILLS_APPLICATION (which System.exit(1)s directly and
            // bypasses our clear-input / interrupt-turn / double-press logic).
            // Combined with the SIGINT signal handler, this delivers Ctrl+C
            // through the keyboard path where we can decide what to do.
            .setUnixTerminalCtrlCBehaviour(UnixLikeTerminal.CtrlCBehaviour.TRAP);

        log.info("[LANTERNA] initTerminal step 2: factory.createTerminal()");
        try {
            terminal = factory.createTerminal();
        } catch (IOException | RuntimeException exception) {
            closeTerminalInput();
            throw exception;
        }
        if (terminal instanceof DefaultVirtualTerminal) closeTerminalInput();
        if (terminal instanceof ExtendedTerminal extended
                && !(terminal instanceof DefaultVirtualTerminal)) {
            terminal = new CompactAnsiTerminal(extended,
                new FastTerminalInputDecoder(terminalInput, Charset.defaultCharset()));
        }
        log.info("[LANTERNA] initTerminal step 3: terminal created = {}", terminal.getClass().getName());

        // The terminal has captured the real process streams. Guard Java/JUL
        // output before entering private mode so startup warnings cannot paint
        // into the alternate-screen buffer.
        TuiOutputGuard outputGuard = terminal instanceof DefaultVirtualTerminal
            ? null : TuiOutputGuard.install();

        try {

        // Enable mouse capture so the trackpad / scroll wheel deliver
        // SCROLL_UP / SCROLL_DOWN events to handleKeyStroke. We use
        // CLICK_RELEASE_DRAG (not the lighter CLICK_RELEASE) so DRAG events
        // arrive too — required for virtual text selection: click-down
        // anchors, drag updates focus, release commits. In this fork,
        // CLICK_RELEASE_DRAG also enables ?1003 (any-motion tracking), so
        // button-less MOVE events arrive as a side effect — CompactAnsiTerminal
        // no longer filters them out, since the coordinator panel now uses
        // MOVE for row hover highlighting.
        if (terminal instanceof ExtendedTerminal et) {
            try {
                TerminalMouseModeLifecycle.enableForTui(et);
            } catch (Exception e) {
                log.warn("[LANTERNA] setMouseCaptureMode failed: {}", e.getMessage());
            }
        }

        log.info("[LANTERNA] initTerminal step 4: new TerminalScreen()");
        screen = new TerminalScreen(terminal);

        log.info("[LANTERNA] initTerminal step 5: screen.startScreenWithoutTerminalSizeQuery()");
        // TerminalScreen's constructor has just captured the size. Re-querying
        // here duplicates terminal I/O on the critical startup path; later
        // restarts still use startScreen so editor-time resizes are observed.
        screen.startScreenWithoutTerminalSizeQuery();

        // Owns all terminal escape-sequence I/O from here on (title, OSC 9;4
        // progress, OSC 21337 tab status, extended-key detection, screen dump).
        terminalController = new TerminalController(terminal, screen);

        // Wire AUTO-scheme theme changes to a full repaint.
        LanternaTheme.setOnAutoResolve(() -> {
            try { screen.refresh(Screen.RefreshType.COMPLETE); }
            catch (Exception _) {}
        });

        // Enable bracketed paste (DEC 2004) so the terminal wraps paste
        // operations with \e[200~ ... \e[201~ markers. InputPanel's
        // PasteKeyStroke handler uses these to distinguish paste from
        // keyboard input — replaces the old Ctrl+V clipboard probe.
        try {
            terminal.enableBracketedPaste();
            terminal.flush(); // ESC[?2004h must reach terminal before user can paste
        } catch (Exception e) {
            log.debug("[LANTERNA] enableBracketedPaste failed (non-fatal)", e);
        }
        // DEC 1004 focus reporting — DISABLED.


        // only consumer (MessagePanel.setFocused → pause blinking dot when blurred)
        // is a cosmetic optimisation, and iTerm2 raises a yellow warning banner
        // ("Looks like focus reporting was left on…") whenever it sees `?1004h`
        // outstanding for a hung-looking app — which can fire spuriously during
        // a large bracketed paste while the EDT is busy. The trade-off (false
        // alarm vs. blink-while-blurred) isn't worth it.

        // If we ever wire useTerminalFocus into something user-visible (e.g.
        if (TerminalController.supportsExtendedKeys()) {
            try {
                terminal.enableKittyKeyboard();
            } catch (Exception e) {
                log.debug("[LANTERNA] enableKittyKeyboard failed (non-fatal)", e);
            }
        }
        // OSC 133 (shell-integration prompt mark) was previously emitted on startup for terminals
        // that support it (iTerm2 / Kitty / WezTerm / Ghostty).
        if (StringUtils.isNotBlank(initialSessionName)) {
            terminalController.setTitle(initialSessionName);
        } else {
            terminalController.setStaticTitle();
        }

        log.info("[LANTERNA] initTerminal step 5b: clear + refresh");
        screen.clear();
        screen.refresh();

        log.info("[LANTERNA] initTerminal step 6: setCursorPosition(null)");
        screen.setCursorPosition(null);

        log.info("[LANTERNA] initTerminal step 7: new SelectionAwareTextGUI");
        // Use SameTextGUIThread for VirtualTerminal (non-TTY) to avoid blocking
        boolean isVirtual = terminal instanceof DefaultVirtualTerminal;
        prestartGuiThreadFactory = isVirtual ? null : new PrestartTextGUIThreadFactory();
        gui = new SelectionAwareTextGUI(
            isVirtual ? new SameTextGUIThread.Factory() : prestartGuiThreadFactory,
            screen);

        log.info("[LANTERNA] initTerminal step 8: setTheme");
        gui.setTheme(ClaudeTheme.build());

        log.info("[LANTERNA] initTerminal step 9: GUI thread created; start deferred until scene ready");
        // Eagerly load Lanterna's javac-generated switch-table companion class
        // (AbstractBasePane$1). It is triggered by doHandleInput's switch on
        // KeyType, but only when focusedInteractable==null — a code path that
        // can be hit for the first time while the JVM is already tearing down
        // after mainWindow.close(). If loading is deferred to that moment, the
        // classloader may refuse and throw NoClassDefFoundError inside the
        // LanternaGUI thread on exit (harmless but noisy).
        try {
            Class.forName("com.googlecode.lanterna.gui2.AbstractBasePane$1");
        } catch (ClassNotFoundException _) {}
        log.info("[LANTERNA] initTerminal DONE");
        return outputGuard;
        } catch (IOException | RuntimeException | Error failure) {
            releaseTerminalForExit();
            if (outputGuard != null) outputGuard.close();
            throw failure;
        }
    }

    private void disableMouseBeforeHandoff() {
        if (!(terminal instanceof ExtendedTerminal extended)) return;
        try {
            TerminalMouseModeLifecycle.disableBeforeHandoff(extended);
        } catch (Exception e) {
            log.debug("[LANTERNA] mouse-reporting disable failed (non-fatal)", e);
        }
    }

    private void restoreMouseAfterHandoff() {
        if (!(terminal instanceof ExtendedTerminal extended)) return;
        try {
            TerminalMouseModeLifecycle.enableForTui(extended);
        } catch (Exception e) {
            log.debug("[LANTERNA] mouse-reporting restore failed (non-fatal)", e);
        }
    }

    private void suspendForJobControl() {
        disableMouseBeforeHandoff();
        try {
            // Do not poll input while handing the terminal back for an explicit
            // user-requested suspend. Private mode and mouse reporting must be
            // disabled before the process is stopped.
            screen.stopScreen(false);
        } catch (Exception e) {
            log.debug("[LANTERNA] job-control screen suspend failed (non-fatal)", e);
        }
    }

    /** Applies worker-prepared startup data while the GUI loop is still sealed. */
    private void applyPreparedStartup(
            ReplStartupPreparation.Prepared prepared, String startupSessionId) {
        if (prepared == null) return;
        spinnerComponent.setTipsEnabled(prepared.spinnerTipsEnabled());
        spinnerComponent.setBtwUseCount(prepared.btwUseCount());
        selectionController.setCopyOnSelect(prepared.copyOnSelect());
        inputPanel.setVimEnabled(prepared.vimModeEnabled());
        sessionController.applyPreparedSessionColor(
            startupSessionId, prepared.sessionBadge(), System.getProperty("user.dir"));
        sessionHostPublisher.publishActiveSession(prepared.sessionCustomTitle());
        // The explicit launch name wins restored transcript metadata, matching
        // the previous construction order.
        if (StringUtils.isNotBlank(initialSessionName)) {
            inputPanel.setAgentName(initialSessionName);
        }
    }

    private void resumeAfterJobControl() {
        try {
            screen.startScreen();
            restoreMouseAfterHandoff();
            screen.refresh(RefreshType.COMPLETE);
        } catch (Exception e) {
            log.warn("[LANTERNA] Failed to restore screen after SIGCONT", e);
        }
    }

    /**
     * Builds the scene through {@link ReplComposer} and adopts the collaborators this screen
     * still drives from its lifecycle, input port, and public setters.
     */
    private void composeScene() {
        ReplContext ctx = new ReplContext(
            gui, screen, terminal,
            task -> gui.getGUIThread().invokeLater(task),
            () -> screen != null ? screen.getTerminalSize().getRows() : 40,
            () -> screen != null ? screen.getTerminalSize().getColumns() : 80,
            queryEngine, commandRegistry, commandContext, wiring, this,
            dispatcher, collapser, presentationSnapshots, messageHistory, promptHistory,
            historyProjectRoot, directorySuggestionService,
            sessionHostPublisher, sessionTopicTitleCoordinator, terminalController,
            lastSubmittedInput, verbose);
        adopt(new ReplComposer(ctx, new ComposerHost(), scene).compose());
        // Terminal handoff is a screen-lifecycle concern, so the editor launcher is built here.
        externalEditor = new PromptExternalEditor(screen, ctx.guiInvoker(), inputPanel,
            new PromptExternalEditor.TerminalHandoff() {
                @Override public void beforeHandoff() { disableMouseBeforeHandoff(); }
                @Override public void afterHandoff() { restoreMouseAfterHandoff(); }
            });
        // Post-composition observers that need the finished graph: the prompt's outward action
        // port (reads suggestionController / sessionController) and the plugin-hint listener.
        inputPanel.setActions(new ReplInputActions());
        installPluginHintListener();
    }

    private void adopt(ReplGraph graph) {
        var widgets = graph.widgets();
        var features = graph.features();
        var controllers = graph.controllers();
        var engine = graph.engine();
        mainWindow = graph.mainWindow();
        messagePanel = widgets.messagePanel();
        spinnerComponent = widgets.spinnerComponent();
        inputPanel = widgets.inputPanel();
        projectPanel = widgets.projectPanel();
        lspRecommendationDialog = widgets.lspRecommendationDialog();
        pluginHintMenu = widgets.pluginHintMenu();
        thinkingToggleDialog = widgets.thinkingToggleDialog();
        collaborationPickerDialog = widgets.collaborationPickerDialog();
        feishuSetupDialog = widgets.feishuSetupDialog();
        taskBoardFeature = features.taskBoard();
        bypassPermissionsStartupGate = features.bypassPermissionsGate();
        sessionController = features.session();
        exitController = features.exit();
        projectPanelController = controllers.projectPanel();
        startupGateController = controllers.startupGate();
        interruptActions = controllers.interrupt();
        selectionController = controllers.selection();
        transcriptController = controllers.transcript();
        localAgentInputRouter = controllers.localAgentInput();
        messageActionsController = controllers.messageActions();
        autoModeEntryWarning = controllers.autoModeEntryWarning();
        suggestionController = controllers.suggestion();
        statusLineController = controllers.statusLine();
        cronScheduler = controllers.cronScheduler();
        welcome = controllers.welcome();
        webGateway = features.webGateway();
        turnView = engine.turnView();
        turnEngine = engine.turnEngine();
        submissionCoordinator = engine.submission();
        hotUiReadiness = engine.hotUiReadiness();
    }

    /**
     * Surface tool-emitted plugin hints (Claude Code hints protocol) as an inline install
     * prompt. The listener fires on whatever thread recorded the hint (a BashTool turn thread);
     * it marshals to the GUI thread here.
     */
    private void installPluginHintListener() {
        ClaudeCodeHintStore.getInstance().setListener(hint -> {
            if (gui == null) return;
            gui.getGUIThread().invokeLater(
                () -> showPluginHintMenu(hint, (response, _) -> handlePluginHintResponse(hint, response)));
        });
    }

    /** The runtime behaviour the composed graph reaches back into this screen for. */
    private final class ComposerHost implements ReplComposer.Host {
        @Override public void stop() { LanternaReplScreen.this.stop(); }
        @Override public void suspendForJobControl() { LanternaReplScreen.this.suspendForJobControl(); }
        @Override public void resumeAfterJobControl() { LanternaReplScreen.this.resumeAfterJobControl(); }
        @Override public void modelChanged(String model) { setModel(model); }
        @Override public String currentModel() { return model; }
        @Override public List<String> toolNames() { return toolNames; }
        @Override public void setThemeScheme(String schemeName) {
            LanternaReplScreen.this.setThemeScheme(schemeName);
        }
        @Override public void turnCompleted() {
            if (idlePromptNotifier != null) idlePromptNotifier.turnCompleted();
            if (awaySummaryTrigger != null) awaySummaryTrigger.turnCompleted();
        }
        @Override public void userInteracted(String input) { noteUserInteraction(input); }
    }


    /**
     * The single outward action/notification port {@link InputPanel} fires into —
     * extracted from an inline anonymous class in the former layout builder to a named
     * (non-static) inner class so the wiring block stays readable. Every method is a
     * thin delegate to a screen behavior; a new REPL key feature adds a method on
     * {@link InputActions} + a delegate here, never a new {@code onXxx} field.
     *
     * <p>Non-static on purpose: it reads the enclosing screen's live collaborators
     * ({@code sessionController}, {@code suggestionController}, {@code statusLineController}, …).
     * The {@code messageActions*}/{@code toggle*} delegates qualify with
     * {@code LanternaReplScreen.this} because the names collide with this class's own
     * override — an unqualified call would recurse.
     */
    private final class ReplInputActions implements InputActions {
        @Override public void submit(String text) {
            ViewedTeammateHolder viewed = ViewedTeammateHolder.instance();
            if (viewed.isViewingLocalAgent() && localAgentInputRouter != null
                    && localAgentInputRouter.submit(viewed.viewingTaskId(), text)) {
                return;
            }
            handleInput(text);
        }
        @Override public void cancel() {
            // Before anything aborts, rescue the in-flight thinking body (197 runs this at
            // the top of its cancel handler, ahead of the abort and the prompt salvage).
            if (turnView != null) turnView.salvageInterruptedThinking();
            featureRuntime.loopWakeups().cancelAll();
            // Shares its abort rule with Ctrl+C (ReplExitController.handleCtrlC) via the
            // same ReplInterruptActions instance instead of re-deriving it here.
            interruptActions.interruptTurnIfRunning();
        }

        @Override public void killBackgroundAgents() {
            BackgroundAgentCancellationAction.execute(
                featureRuntime.taskRegistry(), queryEngine.conversation().getMessageQueue());
        }

        @Override public boolean backgroundForegroundTasks() {
            return featureRuntime.taskRegistry().backgroundAllForegroundTasks() > 0;
        }

        @Override public void exitOnEmptyEof() { handleCtrlD(); }
        @Override public QueuedInputDraft popEditableQueuedCommands(
                String currentInput, int currentCursorOffset) {
            return turnEngine.popAllEditable(currentInput, currentCursorOffset);
        }
        @Override public void showMessageSelector() { sessionController.showMessageSelector(); }
        @Override public void toggleTranscript() { transcriptController.toggle(); }
        @Override public void transcriptShowAll() { transcriptController.toggleShowAll(); }
        @Override public void redrawScreen() {
            try { screen.refresh(RefreshType.COMPLETE); }
            catch (Exception _) { /* non-fatal */ }
        }
        @Override public void externalEditor() { externalEditor.open(); }
        @Override public void openAgents() { commandUi.openAgents(); }
        @Override public void stash() {
            UiSettings.ensureGlobalBooleanAsync("hasUsedStash", true);
        }
        @Override public void undo() {
            if (sessionController.undoLastSubmission(lastSubmittedInput.get(), promptHistory)) {
                lastSubmittedInput.set(null);
            }
        }
        @Override public boolean openHistorySearch() {
            if (gui == null || inputPanel == null) return false;
            String initialQuery = inputPanel.getText();
            gui.getGUIThread().invokeLater(() -> HistorySearchDialog.open(
                gui, scope -> promptHistory.getTimestampedEntriesAsync(scope, historyProjectRoot,
                    queryEngine.conversation().getSessionId()), initialQuery, keybindingsStore,
                inputPanel::applyHistoryPickerEntry));
            return true;
        }
        @Override public void toggleThinking() {
            if (gui == null || thinkingToggleDialog == null || inputPanel == null) return;
            boolean current = queryEngine.configuration().getConfig().isThinkingEnabled();
            boolean midConversation = queryEngine.conversation().getMessages().stream()
                .anyMatch(AssistantMessage.class::isInstance);
            gui.getGUIThread().invokeLater(() -> {
                inputPanel.setSuppressed(true);
                thinkingToggleDialog.show(current, midConversation, selected -> {
                    inputPanel.setSuppressed(false);
                    if (selected == null) return;
                    queryEngine.configuration().getConfig().setThinkingEnabled(selected);
                    saveThinkingEnabled(selected);
                    inputPanel.showTransientHint(
                        "∴ Thinking: " + (selected ? "ON" : "OFF"), 1500);
                });
            });
        }
        @Override public void toggleTodos() {
            if (taskBoardFeature == null || spinnerComponent == null) return;
            gui.getGUIThread().invokeLater(taskBoardFeature::toggle);
        }
        @Override public void setTeammateTreeExpanded(boolean expanded) {
            if (spinnerComponent == null) return;
            if (expanded && taskBoardFeature != null) {
                taskBoardFeature.collapseForTeammateTreeExpansion();
            }
            spinnerComponent.setTeammateTreeExpanded(expanded);
            inputPanel.setTeammateTreeExpanded(expanded);
        }
        @Override public boolean isTeammateTreeExpanded() {
            return spinnerComponent != null && spinnerComponent.isTeammateTreeExpanded();
        }
        @Override public void openModelPicker() {
            commandUi.openModelPicker();
        }
        @Override public void toggleFastMode() {
            LanternaReplScreen.this.toggleFastMode();
        }
        @Override public void permissionModeChanged(String uiMode) {
            if (permissionGate != null) permissionGate.setMode(uiMode);
            if (autoModeEntryWarning != null) {
                autoModeEntryWarning.onPermissionModeChanged(uiMode);
            }

            if (statusLineController != null) statusLineController.scheduleUpdate();
        }
        @Override public void openTasksDialog() { commandUi.openTasks(); }
        @Override public void toggleProjectPanel() { LanternaReplScreen.this.toggleProjectPanel(); }
        @Override public void openWorkflowDialog(String taskId) {
            commandUi.openWorkflows(taskId, false);
        }
        @Override public void openCollaborationPicker() {
            if (collaborationPickerDialog == null
                    || inputPanel == null) return;
            List<String> channels = collaborationController == null
                ? List.of() : collaborationController.availableChannels();
            SessionCollaborationController.Selection current = collaborationController == null
                ? new SessionCollaborationController.Selection("", "")
                : collaborationController.current();
            inputPanel.setSuppressed(true);
            collaborationPickerDialog.show(channels, current.channel(), collaborationSetup != null
                    && !collaborationSetup.configured() && !collaborationSetup.setupPending(),
                collaborationSetup != null && collaborationSetup.setupPending(),
                selected -> {
                if (CollaborationPickerDialog.SETUP_FEISHU.equals(selected)
                        || CollaborationPickerDialog.CONTINUE_FEISHU.equals(selected)) {
                    if (feishuSetupDialog == null || collaborationSetup == null) {
                        inputPanel.setSuppressed(false);
                        return;
                    }
                    feishuSetupDialog.show(collaborationSetup, () -> {
                        inputPanel.setSuppressed(false);
                        if (collaborationController != null
                                && collaborationController.availableChannels().contains("feishu")) {
                            try { collaborationController.selectCurrent("feishu"); }
                            catch (RuntimeException failure) {
                                inputPanel.showTransientHint(failure.getMessage(), 3000);
                            }
                        }
                    });
                    return;
                }
                inputPanel.setSuppressed(false);
                if (selected == null) return;
                try {
                    if (collaborationController == null) return;
                    if (StringUtils.isBlank(selected)) collaborationController.disableCurrent();
                    else collaborationController.selectCurrent(selected);
                } catch (RuntimeException failure) {
                    inputPanel.showTransientHint(failure.getMessage(), 3000);
                }
            });
        }
        @Override public void toggleMessageActions() { messageActionsController.toggle(); }
        @Override public void messageActionsEscape() { messageActionsController.escape(); }
        @Override public void messageActionsForceExit() { messageActionsController.forceExit(); }
        @Override public void messageActionsPrev() { messageActionsController.previous(); }
        @Override public void messageActionsNext() { messageActionsController.next(); }
        @Override public void messageActionsPrevUser() { messageActionsController.previousUser(); }
        @Override public void messageActionsNextUser() { messageActionsController.nextUser(); }
        @Override public void messageActionsTop() { messageActionsController.top(); }
        @Override public void messageActionsBottom() { messageActionsController.bottom(); }
        @Override public void messageActionsCopy() { messageActionsController.copy(); }
        @Override public void messageActionsEdit() { messageActionsController.edit(); }
        @Override public void messageActionsCopyPrimaryInput() {
            messageActionsController.copyPrimaryInput();
        }
        @Override public void queryChanged(String text, int cursor) {
            if (idlePromptNotifier != null) idlePromptNotifier.cancel();
            suggestionController.onQueryChange(text, cursor);
        }
        @Override public void pastedContentsChanged(Map<Integer, PastedContent> contents) {
            gui.getGUIThread().invokeLater(() -> inputPanel.refreshHint());
        }
        @Override public void cursorStyleChanged(CursorStyle style) {
            try { terminal.setCursorStyle(style); }
            catch (Exception _) { /* non-fatal */ }

            // <StatusLine> uses to re-run on vimMode change (debounced, so the
            // extra focus-change fires coalesce harmlessly).
            if (statusLineController != null) statusLineController.scheduleUpdate();
        }
        @Override public void focusChanged(boolean focused) {
            messagePanel.setFocused(focused);
            AwaySummaryTrigger trigger = awaySummaryTrigger;
            if (trigger != null) trigger.focusChanged(focused);
        }
        @Override public void teammateViewChanged() {
            transcriptController.teammateViewChanged();
        }
    }

    private void handleInput(String input) {
        noteUserInteraction(input);
        submissionCoordinator.handleInput(input);
    }

    private void noteUserInteraction(String input) {
        if (idlePromptNotifier != null && StringUtils.isNotBlank(input)) {
            idlePromptNotifier.userInteracted();
        }
    }


    public synchronized void configureIdlePromptNotification(
            long thresholdMs, Runnable notification) {
        if (terminalReleasedForExit) return;
        if (idlePromptNotifier != null) idlePromptNotifier.close();
        idlePromptNotifier = new IdlePromptNotifier(thresholdMs, notification,
            () -> inputPanel != null && inputPanel.isVisible()
                && turnEngine != null && !turnEngine.isInFlight());
    }

    /**
     * Installs the focus-driven away-summary trigger (236 {@code bQg}). The
     * generation boundary is supplied by the composition root; the trigger
     * itself only owns focus/turn scheduling and the disable-hint counter.
     */
    public synchronized void configureAwaySummary(
            Supplier<List<Message>> messagesSupplier,
            RecapGeneration generation) {
        if (terminalReleasedForExit) return;
        if (awaySummaryTrigger != null) awaySummaryTrigger.close();
        awaySummaryTrigger = new AwaySummaryTrigger(
            messagesSupplier, generation, this::postAwaySummary,
            () -> turnEngine != null && turnEngine.isInFlight());
    }

    /** Completes once the semantic event hub and native submission path are ready. */
    public CompletableFuture<Void> sessionHostReady() { return sessionHostPublisher.ready(); }

    private void handleStartupInput(String input) {
        noteUserInteraction(input);
        submissionCoordinator.handleStartupInput(input);
    }

    /**
     * True while a background long-running slash command ({@code /compact}) is executing off the GUI
     * thread.
     */
    @Override
    public void longRunningCommandStarted() { submissionCoordinator.longRunningStarted(); }

    @Override
    public void prepareLongRunningCommandTranscript() {
        turnView.recordLocalCommandTranscriptMetadata(inputPanel.getPermissionMode());
    }

    @Override
    public void longRunningCommandFinished() { submissionCoordinator.longRunningFinished(); }

    @Override
    public void handleQuery(String userInput) {
        submissionCoordinator.handleQuery(userInput);
    }

    @Override
    public void permissionModeSynchronized(String mode) {
        if (autoModeEntryWarning != null) {
            autoModeEntryWarning.onPermissionModeChanged(mode);
        }
        if (statusLineController != null) statusLineController.scheduleUpdate();
    }

    // Turn construction (typed / remote / slash-expanded / prompt command / queue drain /
    // plan continuation) is owned by ReplSubmissionCoordinator; SlashHost only forwards.

    @Override
    public void renderAndQueue(QueuedCommand cmd, String displayText) {
        submissionCoordinator.renderAndQueue(cmd, displayText);
    }

    @Override
    public void executeQuery(String displayText, String queryContent,
                             Map<Integer, PastedContent> pasted) {
        submissionCoordinator.executeQuery(displayText, queryContent, pasted);
    }

    @Override
    public void executeQuery(String displayText, String queryContent,
                             Map<Integer, PastedContent> pasted, boolean isSlash) {
        submissionCoordinator.executeQuery(displayText, queryContent, pasted, isSlash);
    }

    @Override
    public void executePrompt(String displayText, PromptInvocation invocation,
                              Map<Integer, PastedContent> pasted) {
        submissionCoordinator.executePrompt(displayText, invocation, pasted);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers — must be called from UI thread (or use invokeLater)
    // ──────────────────────────────────────────────────────────────────────

    private void appendLine(String text, TextColor color) {
        messagePanel.appendLine(text, color);
    }

    /**
    /** Posts an inline system message to the transcript from any thread. */
    public void postSystemMessage(String text) {
        if (StringUtils.isBlank(text)) return;
        Runnable append = () -> appendLine(text, LanternaTheme.welcomeDim());
        if (gui == null) {
            append.run();
        } else {
            gui.getGUIThread().invokeLater(append);
        }
    }

    /** Applies a SessionStart hook title through the live terminal/session-host path. */
    public void applyHookSessionTitle(String title) {
        if (StringUtils.isBlank(title)) return;
        Runnable apply = () -> {
            if (inputPanel != null) inputPanel.setAgentName(title);
            if (terminalController != null) terminalController.setTitle(title);
            sessionHostPublisher.applyTitle(title);
        };
        if (gui == null) apply.run();
        else gui.getGUIThread().invokeLater(apply);
    }

    /**
     * Renders the "while you were away" recap.
     */
    public void postAwaySummary(String text) {
        if (StringUtils.isBlank(text)) return;
        SystemMessage sys = MessageFactory.createAwaySummaryMessage(text);
        SDKMessage.System sdkSys = new SDKMessage.System(sys);
        Runnable render = () -> {
            messageHistory.record(sdkSys);
            dispatcher.dispatch(sdkSys, messagePanel);
        };
        if (gui == null) {
            render.run();
        } else {
            gui.getGUIThread().invokeLater(render);
        }
    }

    /**
     * Live sub-agent progress (incl.
     */
    public void showAgentProgress(String status) {
        if (StringUtils.isBlank(status)) return;
        String text = "⠿ " + status;
        if (gui == null) {
            inputPanel.setTransientStatusLine(text, 0);
        } else {
            gui.getGUIThread().invokeLater(() -> inputPanel.setTransientStatusLine(text, 0));
        }
    }

    /** Clears completed live progress without disturbing the persistent HUD. */
    public void clearAgentProgress() {
        if (gui == null) {
            inputPanel.clearTransientStatusLine();
        } else {
            gui.getGUIThread().invokeLater(inputPanel::clearTransientStatusLine);
        }
    }

    /** Clears one completed Agent card without disturbing parallel Agent cards. */
    public void clearAgentProgress(String toolUseId) {
        if (toolUseId == null || messagePanel == null) {
            clearAgentProgress();
            return;
        }
        Runnable render = () -> {
            dispatcher.clearAgentProgress(toolUseId, messagePanel);
            inputPanel.clearTransientStatusLine();
        };
        if (gui == null) render.run(); else gui.getGUIThread().invokeLater(render);
    }

    /**
     * Routes the background affordance to its owning tool card, or to the status line for tool
     * uses that have no card of their own (a plain Bash call, or an Agent folded into a group).
     * Upstream shows the same component either way; dropping it for card-less calls would make
     * the affordance disappear exactly where the user is most likely to want it.
     */
    public void showAgentBackgroundHint(String toolUseId) {
        if (toolUseId == null) return;
        Runnable render = () -> {
            if (messagePanel == null
                    || !dispatcher.showAgentBackgroundHint(toolUseId, messagePanel)) {
                inputPanel.setTransientStatusLine(
                    LanternaMessageDispatcher.BACKGROUND_HINT_TEXT,
                    LanternaMessageDispatcher.BACKGROUND_HINT_PADDING);
            }
        };
        if (gui == null) render.run(); else gui.getGUIThread().invokeLater(render);
    }

    /**
     * Toggle Fast Mode through the query session's shared controller.
     */
    @Override
    public void toggleFastMode() {
        FastModeToggleAction.Result result = FastModeToggleAction.toggle(
            queryEngine.configuration().getFastModeController(), model,
            selected -> {
                queryEngine.configuration().setModel(selected);
                model = selected;
            });
        if (!result.accepted()) {
            messagePanel.appendLine("  Fast mode unavailable", LanternaTheme.welcomeDim());
            refreshAfterFastModeChange();
            return;
        }
        messagePanel.appendLine(
            result.enabled()
                ? "  ⚡ Fast mode enabled — using " + ModelDisplayName.render(result.model())
                : "  Fast mode disabled — using " + ModelDisplayName.render(result.model()),
            LanternaTheme.welcomeDim());
        refreshAfterFastModeChange();
    }

    private void refreshAfterFastModeChange() {
        try {
            screen.refresh(RefreshType.COMPLETE);
        } catch (Exception _) { /* non-fatal */ }
    }

    /** Returns true if fast mode is active. */
    public boolean isFastMode() {
        return queryEngine.configuration().getFastModeController().enabled();
    }


    /** Refreshes model-sensitive HUD state without the ordinary interaction debounce. */
    private void executeStatusLineCommandImmediately() {
        if (statusLineController != null) statusLineController.scheduleInitialUpdate();
    }

    // ── Session lifecycle delegates → SessionController ───────────────────

    /**
     * {@code SlashHost} entry for {@code /resume}|{@code /continue}|{@code /restore} with no args.
     */
    @Override
    public void showSessionPicker() {
        sessionController.showSessionPicker();
    }

    /**
     * Arms the picker that a target-less {@code -r} asks for. Must be called before
     * {@link #run()} builds the startup coordinator; the picker itself opens only after the
     * setup gates and the first transcript render have both resolved.
     *
     * @param searchQuery pre-fills the picker's search box with a {@code -r <value>} that resolved
     *                    to no single session, or {@code null} for a bare {@code -r}
     */
    public void requestStartupResumePicker(String searchQuery) {
        startupResumePickerRequested = true;
        startupResumeSearchQuery = searchQuery;
    }

    /** Native Session Host create/resume command; safe to call from a virtual thread. */
    public CompletableFuture<SessionHostSession> activateHostSession(SessionOpenRequest request) {
        return sessionHostPublisher.activateHostSession(request);
    }

    /** Snapshot for the CLI-owned registry/list adapter. */
    public SessionHostSession currentHostSession() {
        return sessionHostPublisher.currentHostSession();
    }

    // ── SlashHost command port ───────────────────────────────────────────
    @Override public boolean isTurnInFlight() { return turnEngine.isInFlight(); }
    @Override public boolean isLongRunningCommandInFlight() {
        return submissionCoordinator.longRunningInFlight();
    }
}
