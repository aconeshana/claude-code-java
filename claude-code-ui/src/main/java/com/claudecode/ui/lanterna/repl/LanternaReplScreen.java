package com.claudecode.ui.lanterna.repl;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.diff.DiffData;
import com.claudecode.commands.diff.GitDiffCollector;
import com.claudecode.commands.diff.TurnDiffExtractor;
import com.claudecode.commands.impl.git.BranchCommand;
import com.claudecode.commands.impl.info.VersionCommand;
import com.claudecode.commands.impl.terminal.CopyCommand;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.commands.session.ResumeRequest;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.PermissionExplainerCallback;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.imagestore.ImageStore;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.lsp.LspPluginRecommendation;
import com.claudecode.core.lsp.LspRecommendationResponse;
import com.claudecode.core.lsp.LspToolUseSummary;
import com.claudecode.core.message.*;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.paste.PastedRefParser;
import com.claudecode.core.pokemon.PokemonEvolution;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.process.ExternalEditorDefaults;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.runtime.compact.CompactWarningProvider;
import com.claudecode.runtime.doctor.DoctorPort;
import com.claudecode.runtime.hooks.HookConfigurationPort;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.runtime.session.ConversationResetPort;
import com.claudecode.runtime.session.SessionLifecycle;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.sessionhost.SessionHostCompactResult;
import com.claudecode.runtime.sessionhost.SessionHostEffortController;
import com.claudecode.runtime.sessionhost.SessionHostEffortState;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostModelController;
import com.claudecode.runtime.sessionhost.SessionHostModelState;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.shutdown.ShutdownPort;
import com.claudecode.runtime.startup.StartupTrustPort;
import com.claudecode.runtime.statusline.StatusLinePort;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.runtime.turn.ConversationOps;
import com.claudecode.runtime.turn.QueuedInputDraft;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.TurnAwakeGuard;
import com.claudecode.runtime.turn.TurnEngine;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.ToolUseRenderContext;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.agent.AgentTool;
import com.claudecode.tools.agent.AgentContinuationService;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.claudecode.tools.cron.CronFeatureGate;
import com.claudecode.tools.cron.CronScheduler;
import com.claudecode.tools.cron.CronStore;
import com.claudecode.tools.hints.ClaudeCodeHint;
import com.claudecode.tools.hints.ClaudeCodeHintStore;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.tasks.TaskNotificationBridge;
import com.claudecode.tools.tasks.PendingBackgroundWork;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.ui.lanterna.bashmode.BashModeExecutor;
import com.claudecode.ui.lanterna.components.ChipSegments;
import com.claudecode.ui.lanterna.components.LogoPanel;
import com.claudecode.ui.lanterna.components.ModelDisplayName;
import com.claudecode.ui.lanterna.components.OSC52Helper;
import com.claudecode.ui.lanterna.components.PokemonCardRenderer;
import com.claudecode.ui.lanterna.components.PokemonEvolutionOverlay;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.dialog.BackgroundTasksDialog;
import com.claudecode.ui.lanterna.dialog.BtwSideQuestionDialog;
import com.claudecode.ui.lanterna.dialog.BypassPermissionsModeDialog;
import com.claudecode.ui.lanterna.dialog.ClaudeMdExternalIncludesDialog;
import com.claudecode.ui.lanterna.dialog.CollaborationPickerDialog;
import com.claudecode.ui.lanterna.dialog.FeishuSetupDialog;
import com.claudecode.runtime.sessionhost.CollaborationSetupPort;
import com.claudecode.ui.lanterna.dialog.CopyPickerDialog;
import com.claudecode.ui.lanterna.dialog.DiffDialog;
import com.claudecode.ui.lanterna.dialog.DoctorDialog;
import com.claudecode.ui.lanterna.dialog.ExportDialog;
import com.claudecode.ui.lanterna.dialog.GoalDialog;
import com.claudecode.ui.lanterna.dialog.HistorySearchDialog;
import com.claudecode.ui.lanterna.dialog.ItermImagePreviewWindow;
import com.claudecode.ui.lanterna.dialog.HooksConfigMenuDialog;
import com.claudecode.ui.lanterna.dialog.LspRecommendationDialog;
import com.claudecode.ui.lanterna.dialog.MCPSettingsDialog;
import com.claudecode.ui.lanterna.dialog.ManagedSettingsSecurityDialog;
import com.claudecode.ui.lanterna.dialog.MessageSelectorDialog;
import com.claudecode.ui.lanterna.dialog.PermissionDialog;
import com.claudecode.ui.lanterna.dialog.PluginHintMenu;
import com.claudecode.ui.lanterna.dialog.PokemonHatchDialog;
import com.claudecode.ui.lanterna.dialog.SkillsDialog;
import com.claudecode.ui.lanterna.dialog.StatsDialog;
import com.claudecode.ui.lanterna.dialog.SudoPasswordDialog;
import com.claudecode.ui.lanterna.dialog.TagRemovalDialog;
import com.claudecode.ui.lanterna.dialog.ThinkingToggleDialog;
import com.claudecode.ui.lanterna.dialog.TrustFolderDialog;
import com.claudecode.ui.lanterna.dialog.WorkflowsDialog;
import com.claudecode.ui.lanterna.dialog.WorktreeExitDialog;
import com.claudecode.ui.lanterna.features.agents.AgentsFeature;
import com.claudecode.ui.lanterna.features.help.HelpCommandCatalog;
import com.claudecode.ui.lanterna.features.help.HelpPanel;
import com.claudecode.ui.lanterna.features.memory.MemoryFeature;
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
import com.claudecode.ui.lanterna.features.tasks.TaskBoardPresentationState;
import com.claudecode.ui.lanterna.features.tasks.TaskBoardProjection;
import com.claudecode.ui.lanterna.features.tasks.TaskListPanel;
import com.claudecode.ui.lanterna.input.ExternalEditorCommand;
import com.claudecode.ui.lanterna.input.CoordinatorNavigationController;
import com.claudecode.ui.lanterna.input.InputActions;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.input.PromptHistory;
import com.claudecode.ui.lanterna.input.WindowInputRouter;
import com.claudecode.ui.lanterna.mouse.SelectionController;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.plugin.PluginPanelServices;
import com.claudecode.ui.lanterna.plugin.PluginRoute;
import com.claudecode.ui.lanterna.plugin.PluginSettingsPanel;
import com.claudecode.ui.lanterna.slash.PromptInvocationAdapter;
import com.claudecode.ui.lanterna.slash.ReplRefs;
import com.claudecode.ui.lanterna.slash.SlashCommandDispatcher;
import com.claudecode.ui.lanterna.slash.SlashHost;
import com.claudecode.ui.lanterna.status.GoalStatusHistory;
import com.claudecode.ui.lanterna.statusline.StatusLineController;
import com.claudecode.ui.lanterna.statusline.StatusLineInputBuilder;
import com.claudecode.ui.lanterna.suggest.DirectorySuggestionService;
import com.claudecode.ui.lanterna.suggest.FileSuggestionService;
import com.claudecode.ui.lanterna.suggest.SuggestionController;
import com.claudecode.ui.lanterna.theme.ClaudeTheme;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.ContextVisualizationRenderer;
import com.claudecode.ui.lanterna.transcript.BackgroundTaskPill;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessageActionsController;
import com.claudecode.ui.lanterna.transcript.MessageCollapser;
import com.claudecode.ui.lanterna.transcript.MessageHistory;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.Selection;
import com.claudecode.ui.lanterna.transcript.SelectionAwareTextGUI;
import com.claudecode.ui.lanterna.transcript.ToolApprovalInteraction;
import com.claudecode.ui.lanterna.transcript.ToolPresentationSnapshotStore;
import com.claudecode.ui.lanterna.transcript.TranscriptController;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.Screen.RefreshType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.ExtendedTerminal;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class LanternaReplScreen implements SlashHost {

    private static final Logger log = LoggerFactory.getLogger(LanternaReplScreen.class);
    private static final ScheduledExecutorService TASK_BOARD_PRESENTATION_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofVirtual().name("task-board-presentation").unstarted(runnable));

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
    /** Interactive approval + teammate bridge; owns its three inline dialog views. */
    private ToolApprovalInteraction toolApprovalInteraction;
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
    private TaskListPanel taskListPanel;
    private final TaskBoardPort taskBoard;
    private final ProjectCatalogPort projectCatalog;
    private volatile TaskBoardPort.Snapshot taskBoardSnapshot = TaskBoardPort.Snapshot.EMPTY;
    private final TaskBoardPresentationState taskBoardPresentationState =
        new TaskBoardPresentationState();
    private final TaskBoardToggleState taskBoardToggleState = new TaskBoardToggleState();
    private boolean taskBoardExpandable;
    private AutoCloseable taskBoardSubscription;
    private AutoCloseable taskBoardIntentSubscription;
    private volatile ScheduledFuture<?> taskBoardCompletionRefresh;
    private volatile boolean taskBoardLoading;
    /** Inline /export picker — sibling of effortSlider; collapses to (0,0) when
     *  idle and is activated by {@code openExportDialog}. */
    private ExportDialog exportDialog;
    /** Inline /hooks browser — sibling of exportDialog; collapses to (0,0) when
     *  idle and is activated by {@code openHooksDialog}. */
    private HooksConfigMenuDialog hooksDialog;

    private GoalDialog goalDialog;

    private BtwSideQuestionDialog btwSideQuestionDialog;
    /** Inline /copy content picker — sibling of themePicker; collapses to (0,0)
     *  when idle and is activated by {@code openCopyPicker(...)}. */
    private CopyPickerDialog copyPicker;
    /** Inline /diff dialog — sibling of copyPicker; collapses to (0,0) when
     *  idle and is activated by {@code openDiffDialog}. */
    private DiffDialog diffDialog;
    /** Inline /help panel — sibling of diffDialog; collapses to (0,0) when
     *  idle and is activated by {@code openHelpPanel}. */
    private HelpPanel helpPanel;
    /** Left-docked project drawer (Java-side extension, no 197 counterpart);
     *  covers the transcript's left strip when active, toggled by
     *  {@code toggleProjectPanel} — the footer button is its only entry point. */
    private ProjectPanel projectPanel;
    private ProjectPanelController projectPanelController;
    /** Inline /plugin settings panel — sibling of helpPanel; collapses to (0,0)
     *  when idle and is activated by {@code openPluginPanel(String)}. */
    private PluginSettingsPanel pluginSettingsPanel;
    /** Startup "trust this folder?" dialog — collapses to (0,0) when idle and is
     *  activated once at REPL startup (see {@code run}) when the cwd is untrusted. */
    private TrustFolderDialog trustDialog;
    private ManagedSettingsSecurityDialog managedSettingsDialog;
    /** Final startup safety gate for dangerous permission bypass. */
    private BypassPermissionsModeDialog bypassPermissionsDialog;
    /** Second startup gate — warns when CLAUDE.md @-imports files outside the cwd.
     *  Collapses to (0,0) when idle; activated at REPL startup (see {@code run})
     *  after the trust dialog resolves, only when external imports are detected
     *  and the user hasn't already decided for this project. */
    private ClaudeMdExternalIncludesDialog externalIncludesDialog;
    /** Late-bound command-to-feature capability bridge installed when the scene is built. */
    private final ReplCommandUiBridge commandUi;
    /** Drives the /hooks browser: snapshot loading + settings hot-reload subscription.
     *  Reads {@link #toolNames} live and uses the injected hook configuration port. */
    private HooksController hooksController;
    /** Collects the /context usage snapshot ({@code ContextUsageAnalyzer} wired by the CLI);
     *  consumed by {@link #showContextVisualization()}. Null in headless / bridge contexts. */
    private Supplier<ContextData> contextDataCollector;
    /** Inline /mcp browser — sibling of hooksDialog; collapses to (0,0) when
     *  idle and is activated by {@code openMcpDialog()}. */
    private MCPSettingsDialog mcpDialog;
    /** Drives the /mcp browser's backend actions (reconnect / enable / disable /
     *  view tools) through the application-owned management port. */
    private MCPController mcpController;
    private final McpManagementPort mcpManagement;
    /** Shared tool registry — used to push newly-discovered MCP tools into the model's catalog
     *  after the user completes {@code Authenticate} or {@code Reconnect} against a remote server. */
    private final ToolRegistry toolRegistry;
    /**
     * Inline overlays (effort / export / hooks / mcp) polled by {@code onInput} ahead of the
     * global key switch, in registration = priority order. They are mutually exclusive; see
    /** Inline dialog stack — one active at a time. Consult {@link InlineOverlay}. Registered in {@link #buildLayout}.
     * <p>Registration order dictates polling order and rendering z-index for the
     * inline dialog stack. See {@link InlineOverlay} for the single-active
     * invariant.
     */
    private final ReplScene scene = new ReplScene();

    /** Worktree exit confirmation dialog — inline, zero height until shown. */
    private WorktreeExitDialog worktreeExitDialog;
    private TagRemovalDialog tagRemovalDialog;
    private PokemonHatchDialog pokemonHatchDialog;
    private MemoryFeature memoryFeature;
    private MessageSelectorDialog messageSelectorDialog;
    private DoctorDialog doctorDialog;
    private SkillsDialog skillsDialog;
    /** Meta+T thinking picker and mid-conversation confirmation. */
    private ThinkingToggleDialog thinkingToggleDialog;
    private CollaborationPickerDialog collaborationPickerDialog;
    private FeishuSetupDialog feishuSetupDialog;
    /** /stats interactive panel — heatmap + Overview/Models tabs. Built in buildLayout. */
    private StatsDialog statsDialog;
    /** Inline /tasks (alias /bashes) background-tasks panel — sibling of
     *  skillsDialog; collapses to (0,0) when idle and is activated by
     *  {@code openTasksDialog}. */
    private BackgroundTasksDialog tasksDialog;
    /** Inline {@code /workflows} browser; independent from {@code /tasks}. */
    private WorkflowsDialog workflowsDialog;
    /**
     * Hook snapshot/hot-reload port installed by the CLI via {@link ReplWiring}.
     */
    private final HookConfigurationPort hookConfiguration;
    /** Virtual text selection + mouse-driven UX. Owns the {@link Selection}
     *  state, multi-click detection, drag-to-autoscroll, and OSC 52 copy.
     *  Keyboard handlers still drive selection state directly via
     *  {@link SelectionController#getSelection}. */
    private SelectionController selectionController;
    /** Alias to {@link SelectionController#getSelection} — set once at
     *  buildLayout so the keyboard branches can keep the concise
     *  {@code selection.xxx} form. */
    private Selection selection;

    private ImmediateCommandUiAdapter immediateAdapter;

    private BashModeExecutor bashModeExecutor;
    /**
     * Slash-command + skill dispatcher.
     */
    private SlashCommandDispatcher slashDispatcher;
    /** Directory/path completions for path-like @-tokens (~/, /, ./, ../). */
    private final DirectorySuggestionService directorySuggestionService =
        new DirectorySuggestionService();
    /** Typeahead orchestrator — slash/@ suggestion decision + command/skill building. */
    private SuggestionController suggestionController;
    /** Conversation / session lifecycle — resume, replay, rewind, summarize. */
    private SessionController sessionController;
    /** Transcript mode + viewed-teammate transcript presentation. */
    private TranscriptController transcriptController;
    /** The subagent coordinator panel — persistent {@code main} + local-agent list. */
    private CoordinatorTaskPanel coordinatorTaskPanel;
    /** Selection/view/eviction state for {@link #coordinatorTaskPanel}. */
    private CoordinatorNavigationController coordinatorNavigation;
    private LocalAgentInputRouter localAgentInputRouter;
    /** Message selection/navigation/copy/edit interaction. */
    private MessageActionsController messageActionsController;
    /** Headless turn orchestrator (owns turnInFlight + the in-flight queue). Built in {@link #buildLayout}. */
    private TurnEngine turnEngine;
    /** Owns submitted-text routing and busy-turn queue hand-off. */
    private ReplSubmissionCoordinator submissionCoordinator;

    // ── App state ──────────────────────────────────────────────────────────
    private final QuerySession       queryEngine;
    private final CompactWarningProvider compactWarnings;
    private final SessionLifecycle  sessionLifecycle;
    private final InteractiveSessionPort interactiveSessions;
    private final ReplFeatureRuntime featureRuntime;
    private final ConversationResetPort conversationReset;
    private final MemoryCatalog memoryCatalog;
    private final OutputStyleCatalog outputStyles;
    private final DoctorPort doctor;
    private final CustomModelCatalog customModels;
    private final boolean showBuiltInModelFamilies;
    private final PluginMarketplacePort plugins;
    private final StatusLinePort statusLine;
    private final StartupTrustPort startupTrust;
    private final ShutdownPort shutdown;
    private final TurnAwakeGuard awakeGuard;
    private final Supplier<String> tipSupplier;
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
    private final boolean allowDangerouslySkipPermissions;
    private final PermissionExplainerCallback permissionExplainer;
    private final Supplier<List<Skill>> skillsSupplier;
    private final Consumer<String> skillHookRegistrar;
    /** Opt-in user keybinding resolver (gate on); null when customization disabled. */
    private final UserKeybindingsStore keybindingsStore;
    private final SessionHostRegistry sessionHostRegistry;
    private final InteractionCoordinator interactionCoordinator;
    private final SessionCollaborationController collaborationController;
    private final CollaborationSetupPort collaborationSetup;

    private volatile String model = "";
    private boolean verbose = false;

    private final LogoPanel welcomePanel = new LogoPanel();
    private final PokemonCardRenderer pokemonCardRenderer =
        new PokemonCardRenderer();
    private final Object pokemonExperienceLock = new Object();
    private PokemonProfile pokemonExperienceState = welcomePanel.pokemon();
    /** Replaceable source-line range occupied by the welcome block. */
    private LogoPanel.WelcomeBlock welcomeBlock;

    private record PokemonProgressUpdate(PokemonProfile before, PokemonProfile after) {}

    // Transcript search and its query/match state are owned by TranscriptController.

    // ── Queued commands ─────────────────────────────────────────────────────
    // turnInFlight + the in-flight queue now live in TurnEngine (owned per-session).
// The screen reads/mutates them via turnEngine.isInFlight/enqueue/countQueued.


    private CronScheduler cronScheduler;
    private volatile IdlePromptNotifier idlePromptNotifier;

    /**
     * The last text submitted by the user.
     */
    private volatile String lastSubmittedInput = null;
    private volatile boolean lastSubmittedInputWasInteractiveStartupPrompt;

    /** Persistent prompt history — shared with InputPanel for Up/Down navigation. */
    private final PromptHistory promptHistory = new PromptHistory();

    private final String historyProjectRoot;

    // ── @ file suggestion service ───────────────────────────────────────────
    // See com.claudecode.ui.lanterna.suggest.FileSuggestionService for cache,
    // throttling, git-index mtime, and stale-VT gen semantics.
    private FileSuggestionService fileSuggestionService;
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
    private volatile String publishedHostSessionTitle;
    /** Latest-session-wins fence for background transcript-title reads. */
    private final AtomicLong sessionHostTitleGeneration = new AtomicLong();
    /** Interactive turn sink; also owns first-turn transcript metadata ordering. */
    private LanternaSessionSink turnView;
    private SessionEventHub sessionEvents;
    private String publishedHostSessionId;
    private final CompletableFuture<Void> sessionHostReady = new CompletableFuture<>();
    private final ReplStartupReadiness startupReadiness;
    private CompletionStage<Void> hotUiReadiness = CompletableFuture.completedFuture(null);
    /** True only while the one-shot argv prompt is synchronously routed through handleInput. */
    private boolean routingInteractiveStartupPrompt;
    /** Ordered trust/external-include/managed-settings startup state machine. */
    private StartupGateController startupGateController;

    private BypassPermissionsStartupGate bypassPermissionsStartupGate;
    /** Ctrl+C/D, signal, worktree-exit, and shutdown state machine. */
    private ReplExitController exitController;

    private AutoModeEntryWarningController autoModeEntryWarning;

    // ──────────────────────────────────────────────────────────────────────

    public LanternaReplScreen(
            QuerySession queryEngine,
            CommandRegistry commandRegistry,
            CommandContext commandContext,
            ReplWiring wiring) {
        this.queryEngine     = queryEngine;
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
        this.allowDangerouslySkipPermissions = launch.allowDangerouslySkipPermissions();
        this.commandUi           = application.commandUi() != null
            ? application.commandUi() : new ReplCommandUiBridge();
        this.interactiveSessions = application.sessions();
        this.hookConfiguration   = application.hooks();
        this.mcpManagement       = application.mcp() != null
            ? application.mcp() : McpManagementPort.none();
        this.toolRegistry        = featureRuntime.toolRegistry();
        this.permissionExplainer = featureRuntime.permissionExplainer();
        this.skillsSupplier      = featureRuntime.skills();
        this.skillHookRegistrar  = featureRuntime.skillHookRegistrar();
        this.compactWarnings     = application.compactWarnings() != null
            ? application.compactWarnings() : CompactWarningProvider.none();
        this.sessionLifecycle    = application.sessionLifecycle();
        this.conversationReset   = application.conversationReset() != null
            ? application.conversationReset() : ConversationResetPort.noop();
        this.memoryCatalog       = application.memory() != null
            ? application.memory() : MemoryCatalog.empty();
        this.outputStyles        = application.outputStyles() != null
            ? application.outputStyles() : OutputStyleCatalog.builtIns();
        this.doctor              = application.doctor();
        this.customModels        = launch.customModels();
        this.showBuiltInModelFamilies = launch.showBuiltInModelFamilies();
        this.plugins             = application.plugins();
        this.statusLine          = application.statusLine() != null
            ? application.statusLine() : StatusLinePort.disabled();
        this.startupTrust        = application.startupTrust() != null
            ? application.startupTrust() : StartupTrustPort.trustAll();
        this.shutdown            = application.shutdown() != null
            ? application.shutdown() : ShutdownPort.noop();
        this.awakeGuard          = application.awakeGuard() != null
            ? application.awakeGuard() : TurnAwakeGuard.noop();
        this.taskBoard           = application.taskBoard() != null
            ? application.taskBoard() : TaskBoardPort.none();
        this.projectCatalog      = application.projects() != null
            ? application.projects() : ProjectCatalogPort.none();
        this.tipSupplier         = launch.tipSupplier() != null ? launch.tipSupplier() : () -> "";
        this.keybindingsStore    = launch.keybindings();
        this.dispatcher.setKeybindingsStore(this.keybindingsStore);
        this.collapser.setKeybindingsStore(this.keybindingsStore);
        this.sessionHostRegistry = launch.sessionHostRegistry();
        this.interactionCoordinator = launch.interactionCoordinator();
        this.collaborationController = launch.collaborationController();
        this.collaborationSetup = launch.collaborationSetup();
        this.initialPrompt       = launch.initialPrompt();
        this.initialSessionName  = StringUtils.trimToNull(launch.initialSessionName());
        this.restoredSession     = launch.restoredSession();
        this.sessionTitleGenerator = launch.sessionTitleGenerator();
        this.publishedHostSessionTitle = StringUtils.defaultString(initialSessionName);
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
// spinnerComponent is created in buildLayout during run; CLI may
        // call setVerbose before then. Apply eagerly if already built, otherwise
// buildLayout will pick it up from the persisted `verbose` field.
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
     * the {@link ClaudeCodeHintStore} listener (set in {@link #buildLayout}) invokes
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
        // before buildLayout publishes a focused input panel; otherwise a PTY
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
                sessionHostTitleGeneration.incrementAndGet();
                publishedHostSessionTitle = title;
                if (sessionHostRegistry != null) {
                    sessionHostRegistry.refreshLocal(buildHostSession(queryEngine.conversation().getSessionId()));
                }
                gui.getGUIThread().invokeLater(() -> terminalController.setTitle(title));
            });
        buildLayout();
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
        log.info("[LANTERNA] buildLayout() OK, screen size={}", screen.getTerminalSize());

        exitController.registerSignalHandlers();

        // JVM shutdown hook — last-line cleanup. Covers crashes / Cmd+Q paths
        // where the normal finally block does not finish. Reuses the idempotent
        // terminal handoff so alternate screen and input modes unwind once.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (statusLineController != null) statusLineController.close();
            closeTaskBoardSubscriptions();
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
        renderFreshConversationWelcome();

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
            releaseTerminalForExit();
        }
        } finally {

            // VirtualTerminal early return, and startup/runtime exceptions.
            if (statusLineController != null) statusLineController.close();
            if (idlePromptNotifier != null) idlePromptNotifier.close();
            closeTaskBoardSubscriptions();
            releaseTerminalForExit();
        }
    }


    private void handleCtrlC() {
        exitController.handleCtrlC();
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
        Runnable repaintWelcomeModel = () -> {
            if (messagePanel == null || welcomeBlock == null) return;
            int terminalWidth = screen != null
                ? screen.getTerminalSize().getColumns() : 100;
            welcomePanel.updateModelLine(
                messagePanel, welcomeBlock, terminalWidth, model);
        };
        // Model commands can finish on a virtual thread. Component mutation
        // stays on Lanterna's GUI thread; startup calls (before gui exists)
        // only seed the volatile model field used by buildLayout().
        if (gui != null) gui.getGUIThread().invokeLater(repaintWelcomeModel);
        else repaintWelcomeModel.run();
    }

    public void applyModelSelection(String model) {
        setModel(model != null ? model : ModelNames.defaultMainLoopModel());
        saveModelSetting(model);
    }


    public void openBtwDialog(String question, Function<String, String> sideQuestionRunner) {
        if (gui == null || btwSideQuestionDialog == null || sideQuestionRunner == null) return;
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
            int rows = 24;
            try { rows = screen.getTerminalSize().getRows(); } catch (Exception _) { }
            btwSideQuestionDialog.show(question, rows, sideQuestionRunner,
                this::forkBtwExchange, () -> {
                if (inputPanel != null) {
                    inputPanel.setSuppressed(false);
                    inputPanel.takeFocus();
                }
            });
        });
    }


    private void forkBtwExchange(String question, String response) {
        if (!EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_COORDINATOR_MODE"))) {
            spawnBtwForkAgent(question, response);
            return;
        }
        branchBtwExchange(question, response);
    }

    private void spawnBtwForkAgent(String question, String response) {
        try {
            Tool<?, ?> registered = toolRegistry.get("Agent").orElse(null);
            if (!(registered instanceof AgentTool agentTool)) {
                throw new IllegalStateException("Agent tool is unavailable");
            }
            List<Message> current = queryEngine.conversation().getMessages();
            String parent = current == null || current.isEmpty() ? null : current.getLast().uuid();
            String userUuid = UUID.randomUUID().toString();
            String sessionId = queryEngine.conversation().getSessionId();
            UserMessage user = new UserMessage(
                userUuid, MessageContent.ofText(question), false, false, null,
                MessageOrigin.USER, parent, Instant.now(),
                null, null, sessionId);
            AssistantMessage assistant = new AssistantMessage(
                UUID.randomUUID().toString(),
                AssistantContent.of(List.of(new TextBlock(response))),
                false, userUuid, Instant.now());
            ToolExecutionContext context = currentAgentToolExecutionContext();
            AgentTool.SpawnedFork spawned = agentTool.spawnForkFromDirective(
                question, List.of(user, assistant), context);
            gui.getGUIThread().invokeLater(() -> {
                btwSideQuestionDialog.hide();
                if (spawned == null) {
                    messagePanel.appendLine("  Cannot fork before the first conversation turn",
                        LanternaTheme.welcomeDim());
                } else {
                    String suffix = spawned.agentId().length() <= 4 ? spawned.agentId()
                        : spawned.agentId().substring(spawned.agentId().length() - 4);
                    messagePanel.appendLine("  ✻ forked " + spawned.name() + " (" + suffix + ")",
                        LanternaTheme.welcomeDim());
                }
            });
        } catch (Exception exception) {
            gui.getGUIThread().invokeLater(() -> {
                btwSideQuestionDialog.hide();
                messagePanel.appendLine("  Failed to fork: " + rootMessage(exception),
                    LanternaTheme.toolError());
            });
        }
    }

    private ToolExecutionContext currentAgentToolExecutionContext() {
        var config = queryEngine.configuration().getConfig();
        var permissionMode = config.permissionModeSupplier() == null
            ? null : config.permissionModeSupplier().get();
        return ToolExecutionContext
            .builder(new AbortController(), queryEngine.conversation().getSessionId())
            .workingDirectory(config.workingDirectory())
            .permissionAskCallback(queryEngine.execution().getPermissionAskCallback())
            .fileStateCache(queryEngine.forks().getFileStateCache())
            .fileHistoryManager(queryEngine.conversation().getFileHistoryManager())
            .messageQueueManager(queryEngine.conversation().getMessageQueue())
            .agentId(config.agentId())
            .nestedMemoryAttachmentTriggers(queryEngine.forks().getNestedMemoryAttachmentTriggers())
            .loadedNestedMemoryPaths(queryEngine.forks().getLoadedNestedMemoryPaths())
            .teamMemoryEnabled(config.teamMemoryEnabledSupplier().get())
            .currentModel(config.model())
            .sandboxConfig(config.sandboxConfigSupplier().get())
            .readDenyIgnorePatterns(config.readDenyIgnorePatternsSupplier().get())
            .turnTokenBudget(queryEngine.execution().getTurnTokenBudget())
            .workingDirectoryController(queryEngine.configuration().workingDirectoryController())
            .enabledTools(config.tools())
            .currentPermissionMode(permissionMode)
            .conversationMessages(queryEngine.conversation().getMessages())
            .renderedSystemPrompt(queryEngine.configuration().fetchSystemPromptParts())
            .build();
    }

    private void branchBtwExchange(String question, String response) {
        try {
            List<Message> current = commandContext.session().messagesSupplier().get();
            String parent = current == null || current.isEmpty() ? null : current.getLast().uuid();
            String userUuid = UUID.randomUUID().toString();
            String sessionId = commandContext.session().currentSessionId() == null
                ? null : commandContext.session().currentSessionId().get();
            UserMessage user = new UserMessage(
                userUuid, MessageContent.ofText(question), false, false, null,
                MessageOrigin.USER, parent, Instant.now(),
                null, null, sessionId);
            AssistantMessage assistant = new AssistantMessage(
                UUID.randomUUID().toString(),
                AssistantContent.of(List.of(new TextBlock(response))),
                false, userUuid, Instant.now());
            String normalized = question.replaceAll("\\s+", " ").trim();
            String title = FormatUtils.truncate("btw: " + normalized, 80);
            var result = new BranchCommand().executeWithAdditionalMessages(
                commandContext, title, List.of(user, assistant));
            gui.getGUIThread().invokeLater(() -> {
                if (StringUtils.isNotBlank(result.output())) {
                    for (String line : result.output().split("\\R", -1)) {
                        messagePanel.appendLine("  " + line, LanternaTheme.welcomeDim());
                    }
                }
                if (Strings.CS.startsWith(result.output(), "Branched conversation")) {
                    btwSideQuestionDialog.hide();
                    if (result.newSessionName() != null && inputPanel != null) {
                        inputPanel.setAgentName(result.newSessionName());
                    }
                } else {
                    btwSideQuestionDialog.finishFork();
                }
            });
        } catch (Exception exception) {
            gui.getGUIThread().invokeLater(() -> {
                btwSideQuestionDialog.hide();
                messagePanel.appendLine("  Failed to branch /btw response: "
                    + rootMessage(exception), LanternaTheme.toolError());
            });
        }
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
     * Opens the {@code /copy} interactive flow.
     */
    public void openCopyPicker(String fullText,
                               List<CopyCommand.CodeBlock> codeBlocks,
                               boolean skipPicker) {
        if (gui == null || copyPicker == null) return;
        gui.getGUIThread().invokeLater(() -> {
            if (skipPicker) {
                handleCopyDialogResult(fullText, codeBlocks,
                    new CopyPickerDialog.CopySelection(-1, false, false));
                return;
            }
            if (inputPanel != null) inputPanel.setSuppressed(true);
            copyPicker.show(fullText, codeBlocks, selection -> {
                if (inputPanel != null) inputPanel.setSuppressed(false);
                handleCopyDialogResult(fullText, codeBlocks, selection);
            });
        });
    }

    /**
     * Executes a {@code /copy} selection and echoes the result.
     */
    private void handleCopyDialogResult(
            String fullText,
            List<CopyCommand.CodeBlock> codeBlocks,
            CopyPickerDialog.CopySelection selection) {
        appendLine("", TextColor.ANSI.DEFAULT);  // spacer
        messagePanel.appendMixed(
            ChipSegments.of(" ❯ /copy",
                LanternaTheme.inputText(),
                LanternaTheme.claude(),
                LanternaTheme.userQueryBg()));
        if (selection == null) {

            appendLine("  ⎿  Copy cancelled", LanternaTheme.welcomeDim());
            return;
        }
        String text;
        String filename;
        if (selection.blockIndex() >= 0 && selection.blockIndex() < codeBlocks.size()) {
            var block = codeBlocks.get(selection.blockIndex());
            text = block.code();
            filename = "copy" + CopyCommand.fileExtension(block.lang());
        } else {
            text = fullText;
            filename = CopyCommand.RESPONSE_FILENAME;
        }
        if (!selection.writeOnly()) {
            OSC52Helper.copyToClipboard(text);
        }
        Thread.startVirtualThread(() -> {
            String result = commandContext.presentation().copyApplyFromDialog() != null
                ? commandContext.presentation().copyApplyFromDialog().apply(
                    text, filename, selection.always(), selection.writeOnly())
                : null;
            gui.getGUIThread().invokeLater(() -> {
                if (StringUtils.isNotBlank(result)) {
                    String[] lines = result.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        appendLine((i == 0 ? "  ⎿  " : "     ") + lines[i], LanternaTheme.welcomeDim());
                    }
                }
            });
        });
    }


    public void openHelpPanel() {
        if (gui == null || helpPanel == null) return;
        HelpCommandCatalog.Catalog catalog = HelpCommandCatalog.build(
            commandRegistry, commandContext);
        var resolver = keybindingsStore.currentResolver();
        var shortcutLabels = HelpPanel.ShortcutLabels.from(
            resolver, keybindingsStore.isEnabled());
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
            helpPanel.show(LogoPanel.appVersion(), catalog.builtin(), catalog.custom(),
                shortcutLabels, resolver, () -> {
                if (inputPanel != null) inputPanel.setSuppressed(false);
                appendLine("  Help dialog dismissed", LanternaTheme.welcomeDim());
            });
        });
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
     * Drawer resume. The drawer lists every project, so picking a session from another
     * directory carries the session's own project through as the resume target and the
     * runtime moves the whole app there before restoring — the released picker instead
     * prints a {@code cd} command, which makes a drawer that can browse but not open.
     */
    private void resumeSessionFromProjectPanel(ProjectCatalogPort.ProjectSessionEntry entry) {
        gui.getGUIThread().invokeLater(() -> {
            if (sessionController == null) return;
            String targetCwd = StringUtils.isBlank(entry.cwd())
                ? commandContext.session().workingDirectory() : entry.cwd();
            sessionController.resume(new ResumeRequest(
                entry.id(), entry.transcriptPath(), targetCwd,
                ResumeRequest.Entrypoint.SLASH_COMMAND_PICKER));
        });
    }

    /** Drawer delete — disk I/O off the GUI thread; the panel already removed the row. */
    private void deleteSessionFromProjectPanel(ProjectCatalogPort.ProjectSessionEntry entry) {
        Thread.ofVirtual().name("project-panel-delete").start(() -> {
            try {
                InteractiveSessionPort.SessionEntry sessionEntry = new InteractiveSessionPort.SessionEntry(
                    entry.id(), entry.lastModified(), entry.createdAt(), entry.messageCount(),
                    entry.summary(), entry.gitBranch(), entry.cwd(), entry.tag(),
                    entry.transcriptPath(), null, entry.customTitle(), entry.fileSize(), false);
                boolean deleted = interactiveSessions.deleteSession(sessionEntry,
                    commandContext.session().workingDirectory());
                if (!deleted) {
                    gui.getGUIThread().invokeLater(() -> appendLine(
                        "  Could not delete session " + entry.id(), LanternaTheme.welcomeDim()));
                }
            } catch (RuntimeException failure) {
                gui.getGUIThread().invokeLater(() -> appendLine(
                    "  Failed to delete session " + entry.id() + ": " + failure.getMessage(),
                    LanternaTheme.welcomeDim()));
            }
        });
    }

    /** Drawer preview — transcript read off the GUI thread, replayed when it lands. */
    private void previewSessionFromProjectPanel(ProjectCatalogPort.ProjectSessionEntry entry) {
        Thread.ofVirtual().name("project-panel-preview").start(() -> {
            List<Message> messages;
            try {
                // Same filter the resume picker applies: retracted turns are not
                // part of the conversation the user would resume into.
                messages = RetractedMessages.filter(
                    interactiveSessions.readMessages(entry.transcriptPath()));
            } catch (RuntimeException failure) {
                log.warn("Failed to read the transcript for the drawer preview", failure);
                messages = null;   // null reports the failure to the panel
            }
            List<Message> result = messages;
            gui.getGUIThread().invokeLater(() -> projectPanel.showPreviewMessages(entry, result));
        });
    }

    /**
     * Opens the {@code /plugin} settings panel.
     */
    public void openPluginPanel(String args) {
        if (gui == null || pluginSettingsPanel == null) return;
        PluginRoute route = PluginRoute.parse(args);
        List<Map.Entry<String, TextColor>> changeLog = new ArrayList<>();
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
            pluginSettingsPanel.show(route,
                (line, color) -> changeLog.add(Map.entry(line, color)),
                () -> {
                    if (inputPanel != null) inputPanel.setSuppressed(false);
                    if (!changeLog.isEmpty()) {
                        appendLine("", TextColor.ANSI.DEFAULT);
                        for (var entry : changeLog) {
                            appendLine("  " + entry.getKey(), entry.getValue());
                        }
                        // Plugins changed — re-inject runtime state.
                        Thread.startVirtualThread(() -> {
                            var rt = commandContext.application().plugins();
                            if (rt != null) {
                                try { rt.refresh(); } catch (Exception _) { }
                            }
                        });
                    }
                });
        });
    }

    /**
     * Opens the {@code /diff} dialog.
     */
    public void openDiffDialog() {
        if (gui == null || diffDialog == null) return;
        Thread.startVirtualThread(() -> {
            DiffData gitDiff = null;
            List<TurnDiffExtractor.TurnDiff> turnDiffs = List.of();
            try {
                gitDiff = new GitDiffCollector(
                    System.getProperty("user.dir")).collect();
            } catch (Exception _) {
                // Not a git repo / git unavailable — dialog shows the empty state.
            }
            try {
                turnDiffs = TurnDiffExtractor.extract(
                    commandContext.session().messagesSupplier().get());
            } catch (Exception _) {
                // Per-turn extraction is best-effort.
            }
            final var fGit = gitDiff;
            final var fTurns = turnDiffs;
            gui.getGUIThread().invokeLater(() -> {
                if (inputPanel != null) inputPanel.setSuppressed(true);
                diffDialog.show(fGit, fTurns, () -> {
                    if (inputPanel != null) inputPanel.setSuppressed(false);
                    appendLine("  Diff dialog dismissed", LanternaTheme.welcomeDim());
                });
            });
        });
    }

    /**
     * Opens the inline {@code /export} picker (Clipboard / File).
     */
    public void openExportDialog(String content) {
        if (gui == null || exportDialog == null || content == null) return;
        final String firstPrompt = extractFirstPromptFromHistory();
        final String defaultName = ExportDialog.buildDefaultFilename(firstPrompt);
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
            exportDialog.show(content, defaultName, System.getProperty("user.dir"),
                (result, _) -> {
                    if (inputPanel != null) inputPanel.setSuppressed(false);
                    handleExportDialogResult(result.message());
                });
        });
    }

    private void handleExportDialogResult(String message) {
// Same transcript shape as /effort: a grey-bg user-query row matching
        // what the user "typed", followed by the result line. Keeps the
        // history symmetric with a textual /export <filename>.
        appendLine("", TextColor.ANSI.DEFAULT);  // spacer
        messagePanel.appendMixed(
            ChipSegments.of(" ❯ /export",
                LanternaTheme.inputText(),
                LanternaTheme.claude(),
                LanternaTheme.userQueryBg()));
        if (StringUtils.isNotBlank(message)) {
            appendLine("  ⎿  " + message, LanternaTheme.welcomeDim());
        }
    }

    /**
     * Opens the inline hooks configuration browser — the {@code /hooks} entry point wired from
     * the CLI. Backend orchestration (settings hot-reload subscription, snapshot loading) lives
     * in {@link HooksController}; this thin delegate preserves the launcher contract.
     */
    public void openHooksDialog() {
        hooksController.open();
    }


    public void openGoalDialog() {
        if (gui == null || goalDialog == null) return;
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
            Runnable onClose = () -> {
                if (inputPanel != null) {
                    inputPanel.setSuppressed(false);
                    inputPanel.takeFocus();
                }
            };

            HookDispatcher hooks = queryEngine.execution().getHookDispatcher();
            if (hooks != null && hooks.activeGoal().isPresent()) {
                goalDialog.showActive(
                    () -> hooks.activeGoal().orElse(null),
                    System::currentTimeMillis,
                    this::currentTokenCount,
                    onClose);
                return;
            }
            GoalStatusAttachment latest = GoalStatusHistory.latestSuccessful(queryEngine.conversation().getMessages());
            if (latest != null) goalDialog.showLatest(latest, onClose);
            else goalDialog.showNone(onClose);
        });
    }

    private long currentTokenCount() {
        Usage usage = queryEngine.execution().getTotalUsage();
        return usage == null ? 0L
            : usage.inputTokens() + usage.outputTokens()
                + usage.cacheCreationInputTokens() + usage.cacheReadInputTokens();
    }

    /**
     * Opens the inline diagnostic report dialog for {@code /doctor} (LOADING →
     * scrollable REPORT). Backend collection lives in
     * the CLI doctor diagnostics adapter; this
     * thin delegate preserves the launcher contract.
     *
     * <p>Called from {@link com.claudecode.commands.CommandPresentationPorts#doctorDialogLauncher}.
     * No-op when the GUI isn't up yet (headless / bridge modes).
     */
    public void openDoctorDialog() {
        if (gui == null || doctorDialog == null) return;
        gui.getGUIThread().invokeLater(() ->
            doctorDialog.show(() ->
                appendLine("  Claude Code diagnostics dismissed", LanternaTheme.welcomeDim())));
    }

    /**
     * Opens the read-only {@code /skills} list overlay — the {@code skillsDialogLauncher} entry point.
     */
    public void openSkillsDialog() {
        if (gui == null || skillsDialog == null) return;
        gui.getGUIThread().invokeLater(() ->
            skillsDialog.show(() ->
                appendLine("  Skills dialog dismissed", LanternaTheme.welcomeDim())));
    }

    /**
     * Opens the {@code /stats} panel — the {@code statsDialogLauncher} entry point.
     */
    public void openStatsDialog() {
        if (gui == null || statsDialog == null) return;
        gui.getGUIThread().invokeLater(() ->
            statsDialog.show(() ->
                appendLine("  Stats dialog dismissed", LanternaTheme.welcomeDim())));
    }

    /**
     * Opens the {@code /tasks} (alias {@code /bashes}) background-tasks panel — the {@code
     * tasksDialogLauncher} entry point.
     */
    public void openTasksDialog() {
        if (gui == null || tasksDialog == null) return;
        gui.getGUIThread().invokeLater(() -> {

            // PromptInput — the prompt bar must vanish while the dialog is
            // open (same command-to-feature wiring pattern as other inline dialogs). The
// dialog's close is the single exit for every dismiss path
            // (Esc/←/Space/Enter, kill-last-task auto-close, goBackToList's
            // close branch) and always fires this callback, so the suppression
            // flag cannot leak.
            if (inputPanel != null) inputPanel.setSuppressed(true);
            tasksDialog.show(() -> {
                if (inputPanel != null) inputPanel.setSuppressed(false);
                appendLine("  Background tasks dialog dismissed", LanternaTheme.welcomeDim());
            });
        });
    }

    private void viewAgentTask(TaskState task) {
        if (task == null) return;
        ViewedTeammateHolder.instance().enterLocalAgentViewing(task.id());
        if (transcriptController != null) transcriptController.teammateViewChanged();
        if (inputPanel != null) {
            String name = featureRuntime != null
                ? featureRuntime.taskRegistry().resolveAgentName(task.id()) : task.description();
            inputPanel.setTransientStatusLine(
                "Viewing @" + name + " — Esc to return to main", 0);
        }
    }


    public void openWorkflowsDialog() {
        openWorkflowsDialog(null, false);
    }

    private void openWorkflowsDialog(String taskId, boolean returnToTasks) {
        if (gui == null || workflowsDialog == null) return;
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
        });
        Thread.ofVirtual().name("workflows-dialog-load").start(() -> {
            featureRuntime.workflowRuns().loadDirectory(
                interactiveSessions.workflowRunPath(System.getProperty("user.dir"),
                    queryEngine.conversation().getSessionId(), "wf_history")
                    .getParent());
            gui.getGUIThread().invokeLater(() -> {
                Runnable onClose = () -> {
                    if (returnToTasks) {
                        openTasksDialog();
                    } else {
                        if (inputPanel != null) inputPanel.setSuppressed(false);
                        appendLine("  Dynamic workflows dialog dismissed", LanternaTheme.welcomeDim());
                    }
                };
                if (taskId == null) {
                    workflowsDialog.show(onClose);
                } else if (!workflowsDialog.showTask(taskId, onClose)) {
                    if (returnToTasks) openTasksDialog();
                    else if (inputPanel != null) inputPanel.setSuppressed(false);
                    appendLine("  Dynamic workflow is no longer available", LanternaTheme.welcomeDim());
                }
            });
        });
    }


    public void openTagRemovalDialog(CommandContext.TagRemovalRequest request) {
        if (gui == null || tagRemovalDialog == null || request == null) return;
        Runnable show = () -> tagRemovalDialog.show(request, result -> {
            if (result != null && result.output() != null && !StringUtils.isBlank(result.output())) {
                postSystemMessage(result.output());
            }
            inputPanel.takeFocus();
        });
        gui.getGUIThread().invokeLater(show);
    }

    /** Opens the safe-by-default confirmation used by {@code /pokemon hatch}. */
    public void openPokemonHatchDialog(CommandContext.PokemonHatchRequest request) {
        if (gui == null || pokemonHatchDialog == null || request == null) return;
        gui.getGUIThread().invokeLater(() -> pokemonHatchDialog.show(request, result -> {
            if (result != null && result.output() != null && !StringUtils.isBlank(result.output())) {
                postSystemMessage(result.output());
            }
            inputPanel.takeFocus();
        }));
    }

    /** Installs the /context data collector (CLI wiring). */
    public void setContextDataCollector(Supplier<ContextData> collector) {
        this.contextDataCollector = collector;
    }


    public void showContextVisualization() {
        if (gui == null || contextDataCollector == null) return;
        Thread.startVirtualThread(() -> {
            try {
                var data = contextDataCollector.get();
                int width = screen != null ? screen.getTerminalSize().getColumns() : 80;
                var lines = ContextVisualizationRenderer.render(data, width);
                gui.getGUIThread().invokeLater(() -> {
                    appendLine("", TextColor.ANSI.DEFAULT);
                    for (var segments : lines) {
                        messagePanel.appendMixed(segments);
                    }
                });
            } catch (Exception e) {
                gui.getGUIThread().invokeLater(() ->
                    appendLine("  /context failed: " + e.getMessage(), LanternaTheme.toolError()));
            }
        });
    }

    /**
     * True when {@link #handleCompactProgress} started the spinner itself for a
     * standalone {@code /compact} (no query in flight — the spinner is otherwise
     * only running during turns). Cleared at {@code compact_end}, which then also
     * stops the spinner; when compaction happens mid-turn (auto-compact), the
     * spinner was already running and must be left running.
     */
    private volatile boolean compactSpinnerAutostarted = false;

    /**
     * Handles a {@link CompactProgressEvent} from the manual {@code /compact} or auto-compact flow.
     */
    public void handleCompactProgress(CompactProgressEvent event) {
        if (spinnerComponent == null) return;

        // is always mounted in the REPL tree and shows whenever isCompacting.
        if (!(event instanceof CompactProgressEvent.CompactEnd) && !spinnerComponent.isSpinning()) {
            spinnerComponent.start("Compacting");
            compactSpinnerAutostarted = true;
        }
        switch (event) {
            case CompactProgressEvent.HooksStart hs -> {
                String msg = switch (hs.hookType()) {
                    case "pre_compact"   -> "Running PreCompact hooks…";
                    case "post_compact"  -> "Running PostCompact hooks…";
                    case "session_start" -> "Running SessionStart hooks…";
                    default              -> "Running hooks…";
                };
                spinnerComponent.setOverrideColor(LanternaTheme.systemSpinner());
                spinnerComponent.setOverrideShimmerColor(LanternaTheme.systemSpinnerShimmer());
                spinnerComponent.setOverrideMessage(msg);
            }
            case CompactProgressEvent.CompactStart _ -> {
                spinnerComponent.setOverrideColor(LanternaTheme.systemSpinner());
                spinnerComponent.setOverrideShimmerColor(LanternaTheme.systemSpinnerShimmer());
                spinnerComponent.setOverrideMessage("Compacting conversation");
                spinnerComponent.setCompacting(true);
            }
            case CompactProgressEvent.CompactEnd _ -> {
                spinnerComponent.setCompacting(false);
                spinnerComponent.setOverrideColor(null);
                spinnerComponent.setOverrideShimmerColor(null);
                spinnerComponent.setOverrideMessage(null);
                if (compactSpinnerAutostarted) {
                    compactSpinnerAutostarted = false;
                    spinnerComponent.stop();
                }
            }
        }
    }

    // ── MCP dialog wiring ────────────────────────────────────────────────────

    /**
     * Opens the inline MCP browser — the {@code /mcp} entry point wired from
     * {@code McpCommand.setDialogLauncher}. Backend orchestration lives in
     * {@link MCPController}; this thin delegate preserves the launcher contract.
     */
    public void openMcpDialog() {
        mcpController.open();
    }

    /**
     * matches {@code ExportCommand.extractFirstPrompt} — first user message
     * text, first line, max 50 chars. Used to seed the default filename in the
     * picker. Returns empty string if no usable prompt found.
     */
    private String extractFirstPromptFromHistory() {
        var messages = queryEngine.conversation().getMessages();
        for (var msg : messages) {
            if (!(msg instanceof UserMessage um)) continue;
            if (um.message() == null) continue;
            String text = null;
            if (um.message().text() != null) {
                text = um.message().text().trim();
            } else if (um.message().blocks() != null) {
                for (var block : um.message().blocks()) {
                    if (block instanceof TextBlock(String text1)) {
                        text = text1.trim();
                        break;
                    }
                }
            }
            if (StringUtils.isEmpty(text)) continue;
            String firstLine = text.split("\n")[0];
            if (firstLine.length() > 50) firstLine = FormatUtils.truncate(firstLine, 50);
            return firstLine;
        }
        return "";
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

    /** Live-applies a newly hatched Pokémon and renders its Buddy-style detail card. */
    public void setWelcomePokemon(PokemonProfile pokemon) {
        synchronized (pokemonExperienceLock) {
            pokemonExperienceState = pokemon;
        }
        Runnable repaint = () -> {
            if (messagePanel == null) return;
            int terminalWidth = screen != null ? screen.getTerminalSize().getColumns() : 100;
            if (welcomeBlock != null) {
                welcomeBlock = welcomePanel.replacePokemon(
                    messagePanel, welcomeBlock, terminalWidth, model, pokemon);
            }
            pokemonCardRenderer.show(messagePanel, terminalWidth, pokemon);
            try { if (gui != null) gui.updateScreen(); } catch (Exception _) {}
        };
        if (gui != null) gui.getGUIThread().invokeLater(repaint);
        else repaint.run();
    }

    /** Renders the current Pokémon card without replacing welcome state. */
    public void showWelcomePokemon(PokemonProfile pokemon) {
        Runnable show = () -> {
            if (messagePanel == null || pokemon == null) return;
            int terminalWidth = screen != null ? screen.getTerminalSize().getColumns() : 100;
            pokemonCardRenderer.show(messagePanel, terminalWidth, pokemon);
            try { if (gui != null) gui.updateScreen(); } catch (Exception _) {}
        };
        if (gui != null) gui.getGUIThread().invokeLater(show);
        else show.run();
    }

    private void addPokemonExperience(long tokens) {
        if (tokens <= 0) return;
        PokemonProgressUpdate update;
        synchronized (pokemonExperienceLock) {
            PokemonProfile current = pokemonExperienceState;
            PokemonProfile progressed = PokemonEvolution.addExperience(current, tokens);
            if (progressed == null || progressed.equals(current)) return;
            pokemonExperienceState = progressed;
            update = new PokemonProgressUpdate(current, progressed);
        }
        UiSettings.writeGlobalAsync("welcomePokemon", update.after().toJson())
            .whenComplete((_, failure) -> {
                if (failure != null) {
                    log.warn("Failed to persist welcomePokemon: {}", rootMessage(failure));
                }
            });
        Runnable applyExperience = () -> {
            if (messagePanel == null || welcomeBlock == null) return;
            int terminalWidth = screen != null ? screen.getTerminalSize().getColumns() : 100;
            welcomeBlock = welcomePanel.replacePokemon(
                messagePanel, welcomeBlock, terminalWidth, model, update.after());
            if (!update.before().name().equals(update.after().name()) && gui != null) {
                PokemonEvolutionOverlay.play(
                    gui, update.before(), update.after(),
                    () -> { if (inputPanel != null) inputPanel.takeFocus(); });
            }
            try { if (gui != null) gui.updateScreen(); } catch (Exception _) {}
        };
        if (gui != null) gui.getGUIThread().invokeLater(applyExperience);
        else applyExperience.run();
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
        // anchors, drag updates focus, release commits. CLICK_RELEASE_DRAG_MOVE
        // would also deliver hover/move which floods the input queue (one
        // event per cell traversed) with no functional benefit — we have
        // no hover semantics anywhere in the TUI.
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
        publishActiveSession(prepared.sessionCustomTitle());
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

    private void buildLayout() {
        // ── Components ─────────────────────────────────────────────────────
        messagePanel     = new MessagePanel();
        messagePanel.setKeybindingsStore(keybindingsStore);
        spinnerComponent = new SpinnerComponent();
        spinnerComponent.setVerbose(verbose);
        inputPanel       = new InputPanel(permissionGate != null
            ? permissionGate.currentMode().kind().wireValue()
            : "default");
        // The GUI thread is already running. Keep the base REPL surface
        // hidden before scene.attach publishes it; startup gates reveal it
        // atomically once input is genuinely ready. Hiding only after
        // buildLayout returns exposes a transient footer that PTY drivers (and
        // fast users) can type into before callbacks are fully installed.
        messagePanel.setVisible(false);
        inputPanel.setVisible(false);
        inputPanel.setBypassPermissionsModeAvailable(() ->
            permissionGate != null && permissionGate.isBypassPermissionsModeAvailable());
        inputPanel.setKeybindingsStore(keybindingsStore);
        // Session Link may change collaboration state from a virtual thread.
        // Install the GUI hop before subscribing the footer to that state.
        inputPanel.setGuiInvoker(r -> gui.getGUIThread().invokeLater(r));
        inputPanel.setCollaborationController(collaborationController);
        int terminalRows = screen != null ? screen.getTerminalSize().getRows() : 40;
// Share the engine's SessionIdentity so a switchToSession call
        // (resume/branch/clear) is visible here too without a separate
// setSessionId sync step.
        inputPanel.wireSessionIdentity(queryEngine.conversation().sessionIdentity());
        toolApprovalInteraction = new ToolApprovalInteraction(
            gui, inputPanel, spinnerComponent, queryEngine, permissionGate, permissionExplainer,
            () -> turnEngine != null && turnEngine.isInFlight(), this::handleQuery,
            event -> dispatcher.dispatch(event, messagePanel), interactionCoordinator, messagePanel,
            featureRuntime.taskRegistry());
        toolApprovalInteraction.setPresentationSnapshotStore(presentationSnapshots);
        toolApprovalInteraction.setPlanClearApprovalConsumer(this::acceptPlanWithClearedContext);
        toolApprovalInteraction.setKeybindingsStore(keybindingsStore);
        lspRecommendationDialog = new LspRecommendationDialog(); // inline, zero height until shown
        lspRecommendationDialog.setKeybindingsStore(keybindingsStore);
        pluginHintMenu   = new PluginHintMenu();   // inline, zero height until shown
        pluginHintMenu.setKeybindingsStore(keybindingsStore);
        taskListPanel    = new TaskListPanel();       // inline, zero height until shown
        applyTaskBoardSnapshot(taskBoard.snapshot());
        taskListPanel.setVisible(UiSettings.readGlobalBoolean("showExpandedTodos", false)
            && !taskBoardSnapshot.hidden());
        taskBoardSubscription = taskBoard.subscribe(snapshot ->
            gui.getGUIThread().invokeLater(() -> applyTaskBoardSnapshot(snapshot)));
        taskBoardIntentSubscription = taskBoard.subscribeIntents(_ ->
            gui.getGUIThread().invokeLater(this::expandTaskBoard));
        exportDialog     = new ExportDialog();       // inline, zero height until shown
        exportDialog.setKeybindingsStore(keybindingsStore);
        exportDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        thinkingToggleDialog = new ThinkingToggleDialog();
        thinkingToggleDialog.setKeybindingsStore(keybindingsStore);
        collaborationPickerDialog = new CollaborationPickerDialog();
        collaborationPickerDialog.setKeybindingsStore(keybindingsStore);
        collaborationPickerDialog.setInteractionBlocked(toolApprovalInteraction::isPromptActive);
        feishuSetupDialog = new FeishuSetupDialog();
        feishuSetupDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        hooksDialog      = new HooksConfigMenuDialog(); // inline, zero height until shown
        hooksDialog.setKeybindingsStore(keybindingsStore);
        goalDialog       = new GoalDialog();         // inline, zero height until shown
        btwSideQuestionDialog = new BtwSideQuestionDialog(); // inline, zero height until shown
        btwSideQuestionDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        copyPicker       = new CopyPickerDialog();   // inline, zero height until shown
        copyPicker.setKeybindingsStore(keybindingsStore);
        diffDialog       = new DiffDialog(           // inline, zero height until shown
            terminalRows);
        helpPanel        = new HelpPanel(            // inline, zero height until shown
            terminalRows);
        helpPanel.setTerminalColumnsSupplier(
            () -> screen != null ? screen.getTerminalSize().getColumns() : 80);
        // Left-docked project drawer — covering overlay over the transcript's
        // left strip; zero size until toggled. Loads run on virtual threads.
        projectPanel = new ProjectPanel(
            () -> screen != null ? screen.getTerminalSize().getColumns() : 80,
            () -> screen != null ? screen.getTerminalSize().getRows() : terminalRows);
        projectPanelController = new ProjectPanelController(
            projectCatalog, projectPanel,
            task -> Thread.ofVirtual().name("project-catalog-io").start(task),
            task -> gui.getGUIThread().invokeLater(task),
            new ProjectPanel.Actions(
                this::resumeSessionFromProjectPanel,
                this::deleteSessionFromProjectPanel,
                this::previewSessionFromProjectPanel,
                () -> {
                    if (inputPanel != null) {
                        inputPanel.setSuppressed(false);
                        inputPanel.setProjectsButtonActive(false);
                    }
                },
                null));
        pluginSettingsPanel = new PluginSettingsPanel( // inline, zero height until shown
            new PluginPanelServices(plugins,
                task -> Thread.ofVirtual().name("plugin-panel-io").start(task),
                mcpManagement));
        pluginSettingsPanel.setKeybindingsStore(keybindingsStore);
        trustDialog      = new TrustFolderDialog();   // inline, zero height until shown
        trustDialog.setKeybindingsStore(keybindingsStore);
        managedSettingsDialog = new ManagedSettingsSecurityDialog();  // inline, zero height until shown
        managedSettingsDialog.setKeybindingsStore(keybindingsStore);
        bypassPermissionsDialog = new BypassPermissionsModeDialog(terminalRows);
        bypassPermissionsDialog.setKeybindingsStore(keybindingsStore);
        externalIncludesDialog = new ClaudeMdExternalIncludesDialog(); // inline, zero height until shown
        externalIncludesDialog.setKeybindingsStore(keybindingsStore);
        startupGateController = new StartupGateController(
            startupTrust,
            memoryCatalog,
            new StartupGateController.View() {
                @Override
                public void promptTrust(Path cwd, Runnable onAccept, Runnable onExit) {
                    trustDialog.prompt(cwd, onAccept, onExit);
                }

                @Override
                public void promptExternalIncludes(Path cwd, List<String> paths,
                                                   Runnable onAllow, Runnable onDisable,
                                                   Runnable onExit) {
                    externalIncludesDialog.prompt(
                        cwd, paths, onAllow, onDisable, onExit);
                }

                @Override
                public void promptManagedSettings(Path cwd, List<String> items,
                                                  Runnable onAccept, Runnable onExit) {
                    managedSettingsDialog.prompt(cwd, items, onAccept, onExit);
                }
            },
            null,
            message -> log.warn(
                "[LANTERNA] Failed to compute external CLAUDE.md includes: {}", message));
        bypassPermissionsStartupGate = new BypassPermissionsStartupGate(
            () -> allowDangerouslySkipPermissions
                || (permissionGate != null
                    && permissionGate.currentMode() == PermissionMode.BYPASS_PERMISSIONS),
            UiSettings::readSkipDangerousModePermissionPrompt,
            UiSettings::persistDangerousModePermissionPrompt,
            bypassPermissionsDialog::prompt);
        mcpDialog        = new MCPSettingsDialog();  // inline, zero height until shown
        mcpDialog.setKeybindingsStore(keybindingsStore);
        worktreeExitDialog = new WorktreeExitDialog(); // inline, zero height until shown
        worktreeExitDialog.setKeybindingsStore(keybindingsStore);
        exitController = ReplExitController.standard(
            shutdown,
            worktreeExitDialog,
            new ReplExitController.InterruptActions() {
                @Override
                public boolean interruptBashIfRunning() {
                    if (bashModeExecutor == null || !bashModeExecutor.isRunning()) return false;
                    bashModeExecutor.interrupt();
                    return true;
                }

                @Override
                public boolean interruptTurnIfRunning() {
                    if (turnEngine == null || !turnEngine.isInFlight()
                            || spinnerComponent == null || !spinnerComponent.isSpinning()) {
                        return false;
                    }
                    queryEngine.submission().interrupt();
                    if (interactionCoordinator != null) {
                        interactionCoordinator.cancelSession(queryEngine.conversation().getSessionId());
                    }
                    return true;
                }

                @Override
                public void softInterruptTurnIfRunning() {
                    if (InterruptedPromptPolicy.shouldCacheSoftInterruptedPrompt(lastSubmittedInput,
                            lastSubmittedInputWasInteractiveStartupPrompt)
                            && queryEngine.execution().getTranscriptSink() != null) {
                        queryEngine.execution().getTranscriptSink().cacheLastPrompt(
                            queryEngine.conversation().getSessionId(), lastSubmittedInput);
                    }
                    // Do not depend on the UI in-flight flag here. During the
                    // terminal teardown race the query iterator can still be
                    // blocked in HTTP while the UI has already cleared its
                    // visible busy state.
                    queryEngine.submission().softInterrupt();
                }

                @Override
                public boolean clearInputIfPresent() {
                    if (inputPanel == null || inputPanel.getText() == null
                            || inputPanel.getText().isEmpty()) {
                        return false;
                    }
                    gui.getGUIThread().invokeLater(() -> {
                        inputPanel.setText("");
                        inputPanel.resetHistory();
                    });
                    return true;
                }

                @Override
                public void showExitHint(String text, int durationMs) {
                    if (inputPanel != null) {
                        gui.getGUIThread().invokeLater(() ->
                            inputPanel.showTransientHint(text, durationMs));
                    }
                }
            },
            message -> appendLine("  " + message, LanternaTheme.welcomeDim()),
            this::stop,
            new ReplExitController.JobControlActions() {
                @Override public void beforeSuspend() { suspendForJobControl(); }
                @Override public void afterResume() { resumeAfterJobControl(); }
            }, interactiveSessions, featureRuntime.currentWorktree());
        collaborationPickerDialog.setExitGestureHandler(key -> {
            if (key == 'c') handleCtrlC();
            else if (key == 'd') handleCtrlD();
        });
        feishuSetupDialog.setExitGestureHandler(key -> {
            if (key == 'c') handleCtrlC();
            else if (key == 'd') handleCtrlD();
        });
        tagRemovalDialog = new TagRemovalDialog(); // inline, zero height until shown
        tagRemovalDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        pokemonHatchDialog = new PokemonHatchDialog(); // inline, zero height until shown
        pokemonHatchDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        messageSelectorDialog = new MessageSelectorDialog(); // inline, zero height until shown
        messageSelectorDialog.setKeybindingsStore(keybindingsStore);
        messageSelectorDialog.setTerminalRowsSupplier(
            () -> screen != null ? screen.getTerminalSize().getRows() : 40);
        messageSelectorDialog.setTerminalColumnsSupplier(
            () -> screen != null ? screen.getTerminalSize().getColumns() : 80);
        messageSelectorDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        messageSelectorDialog.setExitAction(
            () -> exitController.requestShutdown("prompt_input_exit", 0));
        diffDialog.setKeybindingsStore(keybindingsStore);
        doctorDialog = new DoctorDialog(doctor);
        doctorDialog.setKeybindingsStore(keybindingsStore);
        skillsDialog = new SkillsDialog(
            skillsSupplier != null ? skillsSupplier : List::of,
            Path.of(System.getProperty("user.home")));
        skillsDialog.setKeybindingsStore(keybindingsStore);
        skillsDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        workflowsDialog = new WorkflowsDialog(featureRuntime.workflowRuns(),
            featureRuntime.taskRegistry(), this::handleInput, this::postSystemMessage);
        tasksDialog = new BackgroundTasksDialog(featureRuntime.taskRegistry());
        tasksDialog.setKeybindingsStore(keybindingsStore);
        // inline, zero height until shown
        statsDialog = new StatsDialog(
            interactiveSessions,
            r -> gui.getGUIThread().invokeLater(r),
            () -> {
                try { return screen.getTerminalSize().getColumns(); }
                catch (Exception _) { return 80; }
            },
            ZoneId.systemDefault());
        statsDialog.setKeybindingsStore(keybindingsStore);
        immediateAdapter = new ImmediateCommandUiAdapter(
            inputPanel, messagePanel, r -> gui.getGUIThread().invokeLater(r));
        bashModeExecutor = new BashModeExecutor(gui, messagePanel, queryEngine, interactiveSessions,
            interactionCoordinator,
            (image, onClose) -> ItermImagePreviewWindow.show(gui, image, onClose));
        fileSuggestionService = new FileSuggestionService(gui, inputPanel);

        selectionController = new SelectionController(gui, messagePanel, true);
        selection = selectionController.getSelection();
        selectionController.setBareClickHandler(inputPanel::handlePromptBareClick);
        // Screen-level selection: the GUI intercepts selection mouse events
        // above window dispatch and paints the highlight over the full back
        // buffer after every draw (see SelectionAwareTextGUI).
        gui.wireSelection(selection, selectionController::handleMouse,
            mouse -> inputPanel.handleProjectsButtonMouse(mouse)
                || inputPanel.handleTasksPillMouse(mouse));
        // SlashHost is a pure command port; the components a slash command reads/renders into
        // are injected as plain references (see ReplRefs / SlashHost).
        ReplRefs replRefs = new ReplRefs(gui, messagePanel, inputPanel, messageHistory,
            dispatcher, queryEngine, permissionGate);
        slashDispatcher = new SlashCommandDispatcher(this, replRefs, commandRegistry, commandContext,
            skillsSupplier != null ? skillsSupplier : List::of, skillHookRegistrar);
        // Conversation / session lifecycle (resume / replay / rewind / summarize).
        // permissionGate is passed as a Supplier for uniformity with the other controllers.
        sessionController = new SessionController(
            gui, screen, queryEngine, commandContext, messagePanel,
            messageHistory, collapser, inputPanel,
            messageSelectorDialog, () -> permissionGate, sessionLifecycle,
            conversationReset,
            () -> {
                if (sessionTopicTitleCoordinator != null) {
                    sessionTopicTitleCoordinator.resetForNewSession();
                }
            },
            () -> {
                if (sessionTopicTitleCoordinator != null) {
                    sessionTopicTitleCoordinator.markExistingSession();
                }
            },
            title -> {
                if (terminalController != null) terminalController.setTitle(title);
            }, sessionId -> {
                if (interactionCoordinator != null) interactionCoordinator.cancelSession(sessionId);
            }, this::renderFreshConversationWelcome, this::publishActiveSession,
            interactiveSessions, featureRuntime.invokedSkills());
        sessionController.setKeybindingsStore(keybindingsStore);
        sessionController.setModelChanged(this::setModel);
        transcriptController = new TranscriptController(
            gui, screen, messagePanel, spinnerComponent, inputPanel,
            messageHistory, collapser, featureRuntime.taskRegistry(), interactiveSessions);
        transcriptController.setKeybindingsStore(keybindingsStore);
        transcriptController.setAgentTranscriptResolver(agentId ->
            interactiveSessions.agentTranscriptPath(System.getProperty("user.dir"),
                queryEngine.conversation().getSessionId(), agentId));
        localAgentInputRouter = new LocalAgentInputRouter(
            featureRuntime.taskRegistry(),
            (agentId, prompt, context, userInitiated) -> {
                Tool<?, ?> registered = toolRegistry.get("Agent").orElse(null);
                if (!(registered instanceof AgentTool agentTool)) {
                    throw new IllegalStateException("Agent tool is unavailable");
                }
                new AgentContinuationService(agentTool.subAgentFactory())
                    .resume(agentId, prompt, context, userInitiated);
            },
            this::currentAgentToolExecutionContext,
            transcriptController::appendLocalAgentUserMessage,
            failure -> messagePanel.appendLine("  " + failure, LanternaTheme.toolError()));
        tasksDialog.setOnViewAgent(this::viewAgentTask);
        tasksDialog.setOnViewWorkflowRoute((task, returnToTasks) ->
            openWorkflowsDialog(task.id(), returnToTasks));

        // the persistent vertical main+local-agent list inside the prompt footer,
        // before the permanent Collaboration row. InputPanel merges it with the
        // optional background-task pill as one tasks selection state.
        coordinatorTaskPanel = new CoordinatorTaskPanel();
        coordinatorNavigation = new CoordinatorNavigationController(featureRuntime.taskRegistry());
        inputPanel.setTaskRegistry(featureRuntime.taskRegistry());
        inputPanel.setWorkflowRunStore(featureRuntime.workflowRuns());
        inputPanel.setCoordinatorNavigation(
            coordinatorNavigation, coordinatorTaskPanel,
            featureRuntime.taskRegistry()::resolveAgentName);
        messageActionsController = new MessageActionsController(
            terminal, screen, messagePanel, inputPanel,
            sessionController::editMessageFromActions);
        // Turn lifecycle — headless TurnEngine (stream loop + queue + interrupt/rewind/cleanup)
        // driving a LanternaSessionSink (all Lanterna rendering). The engine owns turnInFlight +
        // the queue; the screen reads them via turnEngine.isInFlight()/enqueue()/countQueued().
        turnView = new LanternaSessionSink(
            r -> gui.getGUIThread().invokeLater(r),
            messagePanel, inputPanel, spinnerComponent, terminalController,
            dispatcher, collapser, messageHistory, queryEngine,
            this::executeStatusLineCommand, () -> model, this::readBtwUseCount,
            compactWarnings, tipSupplier, () -> {
                featureRuntime.loopWakeups().onTurnIdle();
                if (cronScheduler != null) cronScheduler.checkNow();
                if (idlePromptNotifier != null) idlePromptNotifier.turnCompleted();
            },
            this::addPokemonExperience);
        sessionController.setRewindStateReset(turnView::resetBackgroundWaitForRewind);
        sessionEvents = new SessionEventHub(turnView,
            failure -> log.warn("Session Link observer failed", failure));
        // The end-of-turn row reports what is still running in the background.
        turnView.setTaskRegistry(featureRuntime.taskRegistry());
        turnView.setTaskBoardLoadingListener(loading -> {
            taskBoardLoading = loading;
            refreshTaskBoardProjection();
        });
        turnView.setTaskBoardOwnersChangedListener(this::refreshTaskBoardProjection);


        // what the user typed.
        turnView.setInterruptSalvage(restoredInput -> {
            if (StringUtils.isNotBlank(restoredInput)) {
                promptHistory.addEntry(restoredInput, queryEngine.conversation().getSessionId(),
                    System.getProperty("user.dir"), historyProjectRoot, Map.of());
            }
        });
        autoModeEntryWarning = AutoModeEntryWarningController.standard(
            this::appendPersistentSystemMessage);
        ConversationOps conversationOps =
            new ConversationOps() {
                @Override public void dropLastPromptHistoryEntry() { promptHistory.removeLastEntry(); }
                @Override public UserMessage rewindBeforeLastRealUser() {
                    return sessionController.rewindToBeforeLastRealUserMessage();
                }
                @Override public String restoredInput(UserMessage message) {
                    return SessionController.restoredInput(message).text();
                }
            };
        turnEngine = new TurnEngine(
            queryEngine, () -> permissionGate, sessionEvents, conversationOps,
            this::executeQueuedCommands,
            r -> gui.getGUIThread().invokeLater(r),
            r -> Thread.ofVirtual().name("api-query").start(r),
            s -> lastSubmittedInput = s,
            awakeGuard,
            hookConfiguration::clearSessionHooks,

            // empty (don't clobber in-flight typing) and the user is not viewing a
            // teammate's transcript (don't rewind the main conversation behind their back).
            () -> inputPanel.getText().isEmpty(),
            () -> ViewedTeammateHolder.instance().isViewing());
        sessionController.setRewindInterruptRequired(
            () -> turnEngine != null && turnEngine.isInFlight());
        sessionController.setRewindDeferrer(turnEngine::runWhenIdle);
        sessionController.setAsyncRewindDeferrer(turnEngine::runWhenIdleAsync);
        turnEngine.setInputQueueListener(commands ->
            gui.getGUIThread().invokeLater(() -> inputPanel.setQueuedCommands(commands)));
        submissionCoordinator = new ReplSubmissionCoordinator(
            inputPanel, promptHistory, commandRegistry, commandContext, immediateAdapter,
            bashModeExecutor, slashDispatcher, turnEngine, this::executeQuery,
            this::executeRemoteQuery,
            this::renderAndQueue, () -> queryEngine.conversation().getSessionId(), historyProjectRoot);
        // MCP browser backend orchestration (reconnect / enable / disable / view tools).
        // The sink lets the controller write notifications + breadcrumbs into the message
        // area without reaching back into this screen.
        ReplTranscriptSink transcriptSink = new ReplTranscriptSink() {
            @Override public void system(String text) { postSystemMessage(text); }
            @Override public void line(String text, TextColor color) { appendLine(text, color); }
            @Override public void breadcrumb(String commandLabel) {
                appendLine("", TextColor.ANSI.DEFAULT);
                messagePanel.appendMixed(
                    ChipSegments.of(" ❯ " + commandLabel,
                        LanternaTheme.inputText(),
                        LanternaTheme.claude(),
                        LanternaTheme.userQueryBg()));
            }
        };
        PreferencesFeature preferencesFeature = new PreferencesFeature(
            gui, inputPanel,
            () -> screen != null ? screen.getTerminalSize().getRows() : 40,
            queryEngine, commandRegistry, commandContext,
            doctor, outputStyles, this::setThemeScheme, transcriptSink, customModels);
        preferencesFeature.setBuiltInModelFamiliesVisible(showBuiltInModelFamilies);
        preferencesFeature.setKeybindingsStore(keybindingsStore);
        preferencesFeature.setEffortChanged(this::executeStatusLineCommandImmediately);
        hotUiReadiness = preferencesFeature.startHotUiPreparation();
        PermissionsFeature permissionsFeature = new PermissionsFeature(
            gui, inputPanel, commandContext, permissionGate, transcriptSink);
        permissionsFeature.setKeybindingsStore(keybindingsStore);
        AgentsFeature agentsFeature = new AgentsFeature(
            gui, inputPanel, memoryCatalog, commandContext,
            () -> toolRegistry != null
                ? toolRegistry.getAll().stream().map(Tool::name).toList()
                : toolNames,
            transcriptSink,
            submissionCoordinator::handleQuery,
            featureRuntime.taskRegistry(),
            this::viewAgentTask);
        agentsFeature.setKeybindingsStore(keybindingsStore);
        SandboxFeature sandboxFeature = new SandboxFeature(gui, inputPanel, transcriptSink);
        memoryFeature = new MemoryFeature(gui, screen, memoryCatalog, transcriptSink);
        memoryFeature.setKeybindingsStore(keybindingsStore);
        commandUi.install(
            preferencesFeature, permissionsFeature, agentsFeature, sandboxFeature,
            memoryFeature, sessionController);
        mcpController = new MCPController(gui, mcpDialog, inputPanel, transcriptSink,
            mcpManagement);
// Bridge background-task (bash / subagent) terminal transitions into the session message
// queue as <task_notification> messages.

        // enqueue*Notification — see TaskNotificationBridge / TaskNotificationBuilder.
        new TaskNotificationBridge(queryEngine.conversation().getMessageQueue()).register();
// …and let any such arrival wake an idle REPL.
        turnEngine.bindIdleQueueWakeup(this::isLongRunningCommandInFlight);
        // Expose the session queue to background bash tasks so the stall
// watchdog can enqueue an interactive-prompt notification. matches
        // the HookEngine.setMessageQueue wiring at the CLI root.
        featureRuntime.taskRegistry().setMessageQueue(queryEngine.conversation().getMessageQueue());
        // Hooks browser: snapshot loading + settings hot-reload subscription. toolNames /
        // Tool names are mutable; the application hook port is stable for the session.
        hooksController = new HooksController(gui, hooksDialog, inputPanel, transcriptSink,
            () -> toolNames, hookConfiguration);
        // Inline overlays polled by onInput, in priority order. Mutually exclusive —
        // opening one suppresses the others (see InlineOverlay).
        scene.registerAll(preferencesFeature.overlays());
        scene.registerAll(permissionsFeature.overlays());
        scene.registerAll(agentsFeature.overlays());
        scene.registerAll(sandboxFeature.overlays());
        scene.register(toolApprovalInteraction.questionView());
        scene.register(toolApprovalInteraction.refusalView());
        scene.register(lspRecommendationDialog);
        scene.register(pluginHintMenu);

        // Surface tool-emitted plugin hints (Claude Code hints protocol) as an
        // inline install prompt. The listener fires on whatever thread recorded
        // the hint (a BashTool turn thread); it marshals to the GUI thread here.
        ClaudeCodeHintStore.getInstance().setListener(hint -> {
            if (gui == null) {
                return;
            }
            gui.getGUIThread().invokeLater(
                () -> showPluginHintMenu(hint, (response, _) -> handlePluginHintResponse(hint, response)));
        });
        scene.register(exportDialog);
        scene.register(thinkingToggleDialog);
        scene.register(collaborationPickerDialog);
        scene.register(feishuSetupDialog);
        scene.register(hooksDialog);
        scene.register(goalDialog);
        scene.register(btwSideQuestionDialog);
        scene.register(copyPicker);
        scene.register(diffDialog);
        scene.register(helpPanel);
        scene.register(projectPanel);
        scene.register(pluginSettingsPanel);
        scene.register(mcpDialog);
        scene.register(worktreeExitDialog);
        scene.register(tagRemovalDialog);
        scene.register(pokemonHatchDialog);
        scene.register(memoryFeature.overlay());
        scene.register(messageSelectorDialog);
        scene.register(doctorDialog);
        scene.register(skillsDialog);
        scene.register(tasksDialog);
        scene.register(workflowsDialog);
        scene.register(statsDialog);
        scene.register(trustDialog);
        scene.register(managedSettingsDialog);
        scene.register(bypassPermissionsDialog);
        scene.register(externalIncludesDialog);

        // ── Root: SmartLayout — messagePanel sized by content, input pinned right below ──
        // Order matters: spinner / permissionPanel / effortSlider / taskListPanel
        // / exportDialog / hooksDialog / input flow together beneath the message stream. Each
        // collapses to (0,0) when idle so the layout hands those rows back to MessagePanel.
        scene.mount(
            messagePanel,
            spinnerComponent,
            toolApprovalInteraction.leaderView(),
            preferencesFeature.effortView(),
            toolApprovalInteraction.questionView(),
            toolApprovalInteraction.refusalView(),
            trustDialog,
            managedSettingsDialog,
            bypassPermissionsDialog,
            sandboxFeature.view(),
            externalIncludesDialog,
            lspRecommendationDialog,
            preferencesFeature.modelView(),
            preferencesFeature.customModelView(),
            taskListPanel,
            thinkingToggleDialog,
            collaborationPickerDialog,
            feishuSetupDialog,
            exportDialog,
            hooksDialog,
            goalDialog,
            btwSideQuestionDialog,
            preferencesFeature.themeView(),
            copyPicker,
            diffDialog,
            helpPanel,
            projectPanel,
            pluginSettingsPanel,
            permissionsFeature.addDirectoryView(),
            preferencesFeature.settingsView(),
            permissionsFeature.rulesView(),
            agentsFeature.view(),
            mcpDialog,
            worktreeExitDialog,
            tagRemovalDialog,
            pokemonHatchDialog,
            memoryFeature.view(),
            messageSelectorDialog,
            doctorDialog,
            skillsDialog,
            tasksDialog,
            workflowsDialog,
            statsDialog,

            // the live spinner/tool zone and the prompt divider.
            new EmptySpace(new TerminalSize(0, 1)),
            inputPanel);

        dispatcher.setToolTagLookup(request -> toolRegistry.resolveToolUseTag(
            request.toolName(), request.inputJson(), new ToolUseRenderContext(
                request.toolUseId(), request.toolUseResult(), request.progressMessages(),
                queryEngine.configuration().getConfig().model())));

// ── Per-tool inline header (e.g.

        // CollapsedReadSearchContent as a normal message-panel line — independent

        // while toolUseConfirmQueue is non-empty (see permission callback below).
        dispatcher.setInlineHeaderLookup((toolName, argsJson) -> {
            if (Strings.CS.equals("Bash", toolName)) {
                if (StringUtils.isBlank(argsJson)) return Optional.empty();
                try {
                    JsonNode root = JsonUtils.getMapper().readTree(argsJson);
                    JsonNode cmd  = root.get("command");
                    if (cmd == null || !cmd.isTextual()) return Optional.empty();
                    String c = cmd.asText().strip();
                    if (c.isEmpty()) return Optional.empty();
                    return Optional.of("$ " + c);
                } catch (Exception _) {
                    return Optional.empty();
                }
            }
            if (Strings.CS.equals("LSP", toolName)) {
                if (StringUtils.isBlank(argsJson)) return Optional.empty();
                try {
                    JsonNode root = JsonUtils.getMapper().readTree(argsJson);
                    return LspToolUseSummary.format(root);
                } catch (Exception _) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        });

        // ── Transparent-wrapper resolution (per message) ──────────────────

        // Resolved lazily via ToolRegistry so dynamically-registered (MCP) tools are
        // honored; drives LanternaMessageDispatcher's transparent header suppression.
        dispatcher.setTransparentWrapperLookup(
            name -> toolRegistry.get(name).map(Tool::isTransparentWrapper).orElse(false));

        // ── Main window: full screen, no decorations, no shadow ───────────
        // NO_POST_RENDERING is critical — without it Lanterna's default
        // WindowShadowRenderer paints a 1-2 col right/bottom shadow (black
        // bg + bold SGR) that user terminals render as a stray yellow /
        // gray border every time the GUI thread re-paints (e.g. on mouse
        // focus shifts). NO_DECORATIONS alone only skips the title border.
        // Scroll keys (PageUp / PageDown / Ctrl+Home / Ctrl+End / mouse wheel),
        // mouse selection, Ctrl+Shift+C copy and Ctrl+C interrupt are handled at
        // the window level — before the focused InputPanel sees them — so they
        // work in every mode (normal input, vim, message-actions, etc.). The
        // routing lives in WindowInputRouter; handleCtrlC stays here because it
        // reads live turn/input state and is shared with the SIGINT handler.
        mainWindow = scene.attach(gui, new WindowInputRouter(
            scene.overlays(), messagePanel, selection, selectionController,
            this::handleCtrlC, keybindingsStore, this::refreshTaskBoardProjection));
        // InputPanel is mounted before the root is attached, so Lanterna never
        // invokes its Component#onAdded callback. Start the background-task
        // footer refresh explicitly once the live scene exists; otherwise
        // Agent/Bash tasks are present in TaskRegistry but the status pill is
        // never repainted until an unrelated key changes the footer.
        inputPanel.startTaskPillRefresh();

// ── Input callbacks ──────────────────────────────────────────────── Stable config/data
// injected directly; behaviors go through the single InputActions port wired via
// setActions(...) below.
        inputPanel.setHasMessages(() -> !queryEngine.conversation().getMessages().isEmpty());
        inputPanel.setPromptHistory(promptHistory);
        inputPanel.setLiveHistorySupplier(transcriptController::viewedPromptHistory);
        // Wire session + project context so history is filtered correctly
        inputPanel.setHistoryContext(
            queryEngine.conversation().getSessionId(),
            historyProjectRoot);
        // Image paste and Session Link collaboration changes share the GUI
        // invoker installed before the collaboration controller was bound.
        boolean[] promptBatchActive = new boolean[1];
        boolean[] settingsBatchActive = new boolean[1];
        gui.wireInputBatch(() -> {
            settingsBatchActive[0] = preferencesFeature.isSettingsActive();
            promptBatchActive[0] = !settingsBatchActive[0]
                && !scene.overlays().hasActiveOverlay();
            if (settingsBatchActive[0]) preferencesFeature.beginInputBatch();
            if (promptBatchActive[0]) inputPanel.beginGuiInputBatch();
        }, () -> {
            try {
                if (settingsBatchActive[0]) preferencesFeature.endInputBatch();
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

        if (StringUtils.isNotBlank(initialSessionName)) {
            inputPanel.setAgentName(initialSessionName);
        }
        toolApprovalInteraction.install();

        // Transcript search navigation is owned by TranscriptController; the main
        // InputPanel never sees those keys while the overlay is open.

// Live query → slash-command + @-file/dir typeahead.
        suggestionController = new SuggestionController(
            gui, inputPanel, commandRegistry, slashDispatcher,
            fileSuggestionService, directorySuggestionService,
            screen.getTerminalSize().getColumns(),
            skillsSupplier != null ? skillsSupplier : List::of);

        // ── Outward action / notification port ──────────────────────────────
        // Every REPL action/notification InputPanel fires goes through this single
        // InputActions instance (replaces ~19 individual setOnXxx callbacks). A new
        // key feature adds a method on InputActions, NOT a new onXxx field + setter.
        // Wired here (after suggestionController) because queryChanged reaches it.
        inputPanel.setActions(new ReplInputActions());

        // Fill horizontal divider lines in input panel
        int termW = screen.getTerminalSize().getColumns();
        inputPanel.setWidth(termW);


        // Drives the user's statusLine command; renders its (ANSI-colored,
        // possibly multi-line) output into the InputPanel footer. Refreshed on
        // each assistant message (including tool-loop API rounds), turn-complete,
        // permission-mode, and vim-mode changes, plus once now.
        statusLineController = new StatusLineController(
            statusLine,
            this::statusLineIngredients,
            () -> queryEngine.conversation().getMessages(),
            r -> gui.getGUIThread().invokeLater(r),
            (text, padding) -> inputPanel.setStatusLine(text, padding),
            () -> inputPanel.clearStatusLine(),
            UiSettings::isClaudeHudEnabled,
            () -> Math.max(1, screen.getTerminalSize().getColumns() - 4),
            this::statusLineEffort);
        statusLineController.scheduleInitialUpdate();


        if (CronFeatureGate.system().cronEnabled()) {
            ScheduledTaskInteractionRouter scheduledTaskRouter =
                new ScheduledTaskInteractionRouter(
                    featureRuntime.taskRegistry()::injectUserMessageToActiveTeammate,
                    CronStore::removeById,
                    this::handleLeadScheduledTask);
            cronScheduler = new CronScheduler(
                () -> turnEngine.isInFlight(),
                scheduledTaskRouter::route,
                () -> queryEngine.conversation().getSessionId()
            );
            Thread.ofVirtual().name("cron-startup").start(cronScheduler::start);
        }
        // The first frame publishes one complete, immutable scene graph.
        // Dialogs may change visibility/data later, but no component or input
        // route may be attached after this point.
        scene.seal();
    }

    private void handleLeadScheduledTask(CronScheduler.FiredTask task) {
        gui.getGUIThread().invokeLater(() -> {
            ZonedDateTime now = ZonedDateTime.now();
            String displayTime = now.format(
                DateTimeFormatter.ofPattern("MMM d h:mm", Locale.US))
                + now.format(DateTimeFormatter.ofPattern("a", Locale.US))
                    .toLowerCase(Locale.US);
            String label = Strings.CS.equals("loop", task.kind())
                ? "Claude resuming /loop wakeup (" + displayTime + ")"
                : "Running scheduled task (" + displayTime + ")";
            SystemMessage fireMsg = MessageFactory.createScheduledTaskFireMessage(label);
            queryEngine.conversation().appendTranscriptMessage(fireMsg);
            dispatcher.dispatch(new SDKMessage.System(fireMsg), messagePanel);
            queryEngine.conversation().getMessageQueue().enqueuePendingNotification(
                QueuedCommand.modelScheduled(
                    task.resolvedPrompt(), task.prompt(), "cron", null));
            turnEngine.drainIfIdle();
        });
    }

    private void applyTaskBoardSnapshot(TaskBoardPort.Snapshot snapshot) {
        taskBoardSnapshot = snapshot == null ? TaskBoardPort.Snapshot.EMPTY : snapshot;
        taskBoardToggleState.updateSnapshot(taskBoardSnapshot);
        long nowMillis = System.currentTimeMillis();
        taskBoardPresentationState.update(taskBoardSnapshot, nowMillis);
        if (spinnerComponent != null) spinnerComponent.setTaskSnapshot(taskBoardSnapshot);
        refreshTaskBoardProjection(nowMillis);
        scheduleTaskBoardCompletionRefresh(nowMillis);
        if (taskBoardSnapshot.hidden() && taskListPanel.isVisible()) {
            taskListPanel.setVisible(false);
            UiSettings.ensureGlobalBooleanAsync("showExpandedTodos", false);
        }
    }

    private void refreshTaskBoardProjection() {
        refreshTaskBoardProjection(System.currentTimeMillis());
    }

    private void refreshTaskBoardProjection(long nowMillis) {
        if (taskListPanel == null || screen == null) return;
        TerminalSize size = screen.getTerminalSize();
        TaskBoardProjection.View view = TaskBoardProjection.project(
            taskBoardSnapshot, size.getRows(), size.getColumns(), !taskBoardLoading,
            taskBoardToggleState.expanded(),
            nowMillis, taskBoardPresentationState.completionTimes(nowMillis),
            activeTaskOwners());
        taskBoardExpandable = view.expandable();
        taskListPanel.refresh(view);
    }

    private Map<String, TaskBoardProjection.ActiveOwner> activeTaskOwners() {
        if (turnView == null) return Map.of();
        return activeTaskOwners(turnView.runningTeammateMetricsSnapshot());
    }

    static Map<String, TaskBoardProjection.ActiveOwner> activeTaskOwners(
            List<SpinnerComponent.TeammateMetric> teammates) {
        Map<String, TaskBoardProjection.ActiveOwner> owners = new LinkedHashMap<>();
        for (SpinnerComponent.TeammateMetric teammate : teammates) {
            if (StringUtils.isNotBlank(teammate.taskId())) {
                owners.put(teammate.taskId(), new TaskBoardProjection.ActiveOwner(
                    null, teammate.activity()));
            }
            if (StringUtils.isNotBlank(teammate.name())) {
                owners.put(teammate.name(), new TaskBoardProjection.ActiveOwner(
                    teammate.colorName(), teammate.activity()));
            }
        }
        return Map.copyOf(owners);
    }

    private synchronized void scheduleTaskBoardCompletionRefresh(long nowMillis) {
        cancelTaskBoardCompletionRefresh();
        long delayMillis = taskBoardPresentationState.nextExpiryDelayMillis(nowMillis);
        if (delayMillis < 0L) return;
        taskBoardCompletionRefresh = TASK_BOARD_PRESENTATION_SCHEDULER.schedule(() -> {
            if (gui == null) return;
            gui.getGUIThread().invokeLater(() -> {
                long refreshAt = System.currentTimeMillis();
                refreshTaskBoardProjection(refreshAt);
                scheduleTaskBoardCompletionRefresh(refreshAt);
            });
        }, Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelTaskBoardCompletionRefresh() {
        ScheduledFuture<?> current = taskBoardCompletionRefresh;
        taskBoardCompletionRefresh = null;
        if (current != null) current.cancel(false);
    }

    private void expandTaskBoard() {
        boolean alreadyVisible = taskListPanel.isVisible();
        applyTaskBoardSnapshot(taskBoard.snapshot());
        if (taskBoardSnapshot.hidden()) return;
        if (!alreadyVisible) taskBoardToggleState.showCompact();
        spinnerComponent.setTeammateTreeExpanded(false);
        inputPanel.setTeammateTreeExpanded(false);
        taskListPanel.setVisible(true);
        refreshTaskBoardProjection();
        UiSettings.ensureGlobalBooleanAsync("showExpandedTodos", true);
    }

    private void toggleTaskBoard() {
        boolean hasTeammates = !featureRuntime.taskRegistry().listRunningTeammates().isEmpty();
        applyTaskBoardSnapshot(taskBoard.snapshot());
        if (taskListPanel.isVisible()) {
            TaskBoardToggleState.Toggle toggle = taskBoardToggleState.toggle(
                true, taskBoardExpandable);
            if (toggle == TaskBoardToggleState.Toggle.SHOW_EXPANDED) {
                spinnerComponent.setTeammateTreeExpanded(false);
                inputPanel.setTeammateTreeExpanded(false);
                refreshTaskBoardProjection();
            } else {
                taskListPanel.setVisible(false);
                spinnerComponent.setTeammateTreeExpanded(hasTeammates);
                inputPanel.setTeammateTreeExpanded(hasTeammates);
            }
        } else if (hasTeammates && spinnerComponent.isTeammateTreeExpanded()) {
            taskBoardToggleState.showCompact();
            spinnerComponent.setTeammateTreeExpanded(false);
            inputPanel.setTeammateTreeExpanded(false);
        } else if (!taskBoardSnapshot.hidden()) {
            taskBoardToggleState.toggle(false, taskBoardExpandable);
            spinnerComponent.setTeammateTreeExpanded(false);
            inputPanel.setTeammateTreeExpanded(false);
            taskListPanel.setVisible(true);
            refreshTaskBoardProjection();
        } else {
            taskBoardToggleState.showCompact();
            spinnerComponent.setTeammateTreeExpanded(false);
            inputPanel.setTeammateTreeExpanded(false);
        }
        UiSettings.ensureGlobalBooleanAsync(
            "showExpandedTodos", taskListPanel.isVisible());
    }

    private void closeTaskBoardSubscriptions() {
        cancelTaskBoardCompletionRefresh();
        closeQuietly(taskBoardSubscription);
        closeQuietly(taskBoardIntentSubscription);
        taskBoardSubscription = null;
        taskBoardIntentSubscription = null;
    }

    private static void closeQuietly(AutoCloseable subscription) {
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (Exception _) {
            // UI teardown is best effort.
        }
    }

    /**
     * The single outward action/notification port {@link InputPanel} fires into —
     * extracted from an inline anonymous class in {@link #buildLayout} to a named
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
            if (interactionCoordinator != null) {
                interactionCoordinator.cancelSession(queryEngine.conversation().getSessionId());
            }
            if (spinnerComponent.isSpinning()) {
                queryEngine.submission().interrupt();
            }
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
        @Override public void externalEditor() { openExternalEditor(); }
        @Override public void openAgents() { commandUi.openAgents(); }
        @Override public void stash() {
            UiSettings.ensureGlobalBooleanAsync("hasUsedStash", true);
        }
        @Override public void undo() { undoLastMessage(); }
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
            if (taskListPanel == null || spinnerComponent == null) return;
            gui.getGUIThread().invokeLater(LanternaReplScreen.this::toggleTaskBoard);
        }
        @Override public void setTeammateTreeExpanded(boolean expanded) {
            if (spinnerComponent == null) return;
            if (expanded && taskListPanel != null) {
                taskBoardToggleState.showCompact();
                taskListPanel.setVisible(false);
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
        @Override public void openTasksDialog() { LanternaReplScreen.this.openTasksDialog(); }
        @Override public void toggleProjectPanel() { LanternaReplScreen.this.toggleProjectPanel(); }
        @Override public void openWorkflowDialog(String taskId) {
            LanternaReplScreen.this.openWorkflowsDialog(taskId, false);
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
        }
        @Override public void teammateViewChanged() {
            transcriptController.teammateViewChanged();
        }
    }

    private void handleInput(String input) {
        if (idlePromptNotifier != null && StringUtils.isNotBlank(input)) {
            idlePromptNotifier.userInteracted();
        }
        submissionCoordinator.handleInput(input);
    }


    public synchronized void configureIdlePromptNotification(
            long thresholdMs, Runnable notification) {
        if (terminalReleasedForExit) return;
        if (idlePromptNotifier != null) idlePromptNotifier.close();
        idlePromptNotifier = new IdlePromptNotifier(thresholdMs, notification,
            () -> inputPanel != null && inputPanel.isVisible()
                && turnEngine != null && !turnEngine.isInFlight());
    }

    private void renderFreshConversationWelcome() {
        int terminalWidth = screen != null
            ? screen.getTerminalSize().getColumns() : 100;
        welcomeBlock = welcomePanel.show(messagePanel, terminalWidth, model);
    }

    private void publishActiveSession() {
        publishActiveSession(null, true);
    }

    /** Startup overload consuming the already-scanned immutable transcript metadata. */
    private void publishActiveSession(String preparedTitle) {
        publishActiveSession(preparedTitle, false);
    }

    private void publishActiveSession(String preparedTitle, boolean refreshTitleInBackground) {
        if (sessionHostRegistry == null || sessionEvents == null || submissionCoordinator == null) return;
        String sessionId = queryEngine.conversation().getSessionId();
        long titleGeneration = sessionHostTitleGeneration.incrementAndGet();
        boolean newBinding = !Objects.equals(publishedHostSessionId, sessionId);
        if (newBinding) {
            boolean firstPublication = publishedHostSessionId == null;
            // The hub intentionally survives /new and /resume so all endpoint
            // subscriptions remain attached. Its replay prefix does not: those
            // events belong to the previously active logical session and must
            // never seed the newly bound IM thread.
            sessionEvents.resetReplay();
            publishedHostSessionId = sessionId;
            String effectiveTitle = StringUtils.trimToNull(preparedTitle);
            if (effectiveTitle == null && firstPublication) {
                effectiveTitle = initialSessionName;
            }
            publishedHostSessionTitle = StringUtils.defaultString(effectiveTitle);
        }
        sessionHostRegistry.activateLocal(buildHostSession(sessionId));
        sessionHostReady.complete(null);
        if (newBinding && refreshTitleInBackground) {
            refreshSessionHostTitle(sessionId, titleGeneration);
        }
    }

    private void refreshSessionHostTitle(String sessionId, long generation) {
        Thread.ofVirtual().name("session-host-title-" + sessionId).start(() -> {
            String title;
            try {
                title = interactiveSessions.readCustomTitle(
                    commandContext.session().workingDirectory(), sessionId);
            } catch (RuntimeException failure) {
                log.debug("Session Host title refresh failed: {}", failure.toString());
                return;
            }
            if (generation != sessionHostTitleGeneration.get()
                    || !Objects.equals(publishedHostSessionId, sessionId)) return;
            publishedHostSessionTitle = StringUtils.defaultString(StringUtils.trimToNull(title));
            sessionHostRegistry.refreshLocal(buildHostSession(sessionId));
        });
    }

    /** Completes once the semantic event hub and native submission path are ready. */
    public CompletableFuture<Void> sessionHostReady() { return sessionHostReady; }

    private SessionHostSession buildHostSession(String sessionId) {
        String workDir = commandContext.session().workingDirectory();
        SessionHostInfo info = new SessionHostInfo(sessionId, workDir, publishedHostSessionTitle,
            queryEngine.conversation().getMessages().size(), Instant.now(), "");
        return new SessionHostSession(
            info, sessionEvents, submission -> submitRemote(sessionId, submission),
            new SessionHostModelController() {
                @Override public SessionHostModelState get() {
                    return currentSessionModelState(sessionId);
                }

                @Override public SessionHostModelState set(String selected) {
                    return setSessionModel(sessionId, selected);
                }
            },
            new SessionHostEffortController() {
                @Override public SessionHostEffortState get() {
                    return currentSessionEffortState(sessionId);
                }

                @Override public SessionHostEffortState set(String selected) {
                    return setSessionEffort(sessionId, selected);
                }
            },
            instructions -> {
                requireActiveHostSession(sessionId);
                return slashDispatcher.dispatchSessionHostCompact(instructions)
                    .thenApply(result -> new SessionHostCompactResult(result.output()));
            });
    }

    private SessionHostModelState currentSessionModelState(String expectedSessionId) {
        requireActiveHostSession(expectedSessionId);
        String current = queryEngine.configuration().getConfig().modelPreference();
        List<CustomModelConfig> custom = customModels != null ? customModels.list() : List.of();
        return new SessionHostModelState(current == null ? "default" : current,
            SessionHostModelOptions.build(current, queryEngine.configuration().getConfig()::isModelAllowed,
                custom, showBuiltInModelFamilies));
    }

    private SessionHostModelState setSessionModel(String expectedSessionId, String selected) {
        requireActiveHostSession(expectedSessionId);
        SessionHostModelState available = currentSessionModelState(expectedSessionId);
        if (available.models().stream().noneMatch(option -> selected.equals(option.name()))) {
            throw new IllegalArgumentException("model is not available for this session");
        }
        String preference = Strings.CS.equals("default", selected) ? null : selected;
        queryEngine.configuration().setModel(preference);
// Session Host model changes match SDK set_model: update only this
        // QuerySession/session. Reusing applyModelSelection() here wrote
// ~/on and made sibling PTY/Feishu sessions drift.
        setModel(queryEngine.configuration().getConfig().model());
        return currentSessionModelState(expectedSessionId);
    }

    private SessionHostEffortState currentSessionEffortState(String expectedSessionId) {
        requireActiveHostSession(expectedSessionId);
        String model = queryEngine.configuration().getConfig().model();
        if (!EffortHelpers.modelSupportsEffort(model)) {
            return new SessionHostEffortState("auto", "", List.of());
        }
        String configured = queryEngine.configuration().getConfig().effortValue();
        String current = StringUtils.isBlank(configured) ? "auto" : configured;
        String effective = EffortHelpers.getDisplayedEffortLevel(model, configured);
        List<String> choices = new ArrayList<>();
        choices.add("auto");
        choices.addAll(EffortHelpers.supportedEffortLevels(model));
        return new SessionHostEffortState(current, effective, choices);
    }

    private SessionHostEffortState setSessionEffort(String expectedSessionId, String selected) {
        requireActiveHostSession(expectedSessionId);
        SessionHostEffortState available = currentSessionEffortState(expectedSessionId);
        if (!available.efforts().contains(selected)) {
            throw new IllegalArgumentException("effort is not available for this session");
        }
        String configured = Strings.CS.equals("auto", selected) ? null : selected;
        queryEngine.configuration().getConfig().setEffortValue(configured);
        SessionHostEffortState updated = currentSessionEffortState(expectedSessionId);
        showRemoteEffortNotification(expectedSessionId, configured, updated);
        return updated;
    }

    private void showRemoteEffortNotification(
            String sessionId, String configured, SessionHostEffortState state) {
        String text = EffortHelpers.getEffortNotificationText(
            configured, queryEngine.configuration().getConfig().model());
        if (text != null) {
            gui.getGUIThread().invokeLater(() -> inputPanel.showTransientHint(text, 12_000));
        }
        String channel = collaborationController == null
            ? "" : collaborationController.selection(sessionId).channel();
        postSystemMessage(RemoteSessionControlFeedback.effortChanged(state, channel));
        if (statusLineController != null) statusLineController.scheduleUpdate();
    }

    private void requireActiveHostSession(String expectedSessionId) {
        if (!queryEngine.conversation().getSessionId().equals(expectedSessionId)) {
            throw new IllegalStateException("session is no longer active");
        }
    }

    private CompletableFuture<Void> submitRemote(
            String expectedSessionId, SessionHostSubmission submission) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        gui.getGUIThread().invokeLater(() -> {
            try {
                if (!queryEngine.conversation().getSessionId().equals(expectedSessionId)) {
                    throw new IllegalStateException("session is no longer active");
                }
                Map<Integer, PastedContent> pasted = new LinkedHashMap<>();
                StringBuilder prompt = new StringBuilder(submission.prompt());
                int imageId = 1;
                for (SessionHostSubmission.Attachment image : submission.images()) {
                    String mediaType = StringUtils.isBlank(image.mimeType())
                        ? "image/png" : image.mimeType();
                    pasted.put(imageId, PastedContent.image(imageId,
                        Base64.getEncoder().encodeToString(image.data()),
                        mediaType, null, null));
                    if (!prompt.isEmpty()) prompt.append(' ');
                    prompt.append(PastedRefParser.formatImageRef(imageId));
                    imageId++;
                }
                for (SessionHostSubmission.Attachment file : submission.attachments()) {
                    Path filePath = persistRemoteAttachment(expectedSessionId,
                        submission.messageId(), file);
                    if (!prompt.isEmpty()) prompt.append('\n');
                    prompt.append("Attached file: ").append(filePath);
                }
                submissionCoordinator.handleRemoteQuery(prompt.toString(), pasted);
                result.complete(null);
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private Path persistRemoteAttachment(
            String sessionId, String messageId, SessionHostSubmission.Attachment attachment) {
        return RemoteAttachmentStore.persist(commandContext.session().workingDirectory(),
            sessionId, messageId, attachment);
    }

    private void handleStartupInput(String input) {
        routingInteractiveStartupPrompt = true;
        try {
            handleInput(input);
        } finally {
            routingInteractiveStartupPrompt = false;
        }
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

    /**
     * Drain a batch of {@link QueuedCommand}s from the in-flight queue.
     */
    private void executeQueuedCommands(List<QueuedCommand> batch) {
        if (batch.isEmpty()) return;
        var transcript = queryEngine.execution().getTranscriptSink();
        if (transcript != null) {
            transcript.recordQueueOperation(queryEngine.conversation().getSessionId(), "dequeue", null);
        }
        QueuedCommand cmd = batch.getFirst();
        String text = QueuedCommandMapper.envelope(cmd);
        // skipSlashCommands: treat as plain text even if starts with '/'.
        // Covers inputs that must bypass local slash-command routing.
        if (!cmd.skipSlashCommands() && text != null && Strings.CS.startsWith(text, "/")) {
// Re-route through slash dispatch.

            // alone), so there is nothing left in `batch` to lose here.
            slashDispatcher.dispatch(text);
            return;
        }
// matches ReplSubmissionCoordinator.handleInput/handleRemoteQuery's blank-input
        // guard for human-typed submissions: a queued command must never reach
        // turnEngine.submit() with neither text nor a pasted image. Without this, a
        // malformed task-notification or an orphaned-permission command that (contrary
        // to the assumption below) reached this UI-edge drain with its payload already
        // consumed elsewhere would submit an empty user turn — which serializes to a
        // wire message with an empty text content block. Real incident: that empty block
        // survived into a tool_result-heavy turn and downstream strict backends rejected
        // it with "message content cannot be empty".
        if (QueuedCommandMapper.isBlankQueuedCommand(cmd, text)) {
            log.warn("executeQueuedCommands: dropping queued command with blank text and no "
                + "pasted image (mode={}, originKind={})", cmd.mode(), cmd.originKind());
            return;
        }
        // mode == "orphaned-permission" / "task-notification": route as plain query.
        // isMeta: passed through — the message will be sent to the model but
        // the UI does not currently filter meta messages differently.
        // bridgeOrigin is retained only as legacy queue provenance; no bridge
        // command filter exists after the bridge subsystem removal.
        //
        // NOTE: a *payload-bearing* orphaned-permission command (from the SDK control
        // broker, cli module) is consumed by the engine's in-loop drain
        // (QueryHelpers.drainQueuedCommands → OrphanedPermissionExecutor), never here.
        // The UI edge drain only ever sees a payload-less orphaned-permission, which the
        // UI mode never enqueues in the first place — so routing it as a plain query is a
        // harmless fallback, not a behavior change.
        UserInput input = QueuedCommandMapper.applyQueuedCommandProvenance(
            UserInput.builder(text, text)
            .pasted(cmd.pastedContents())
            .permissionMode(inputPanel.getPermissionMode())
            .build(), cmd);
        input = withStartupPromptProvenance(input);
        if (batch.size() > 1) {
            input = input.withAdditionalUserMessages(batch.stream().skip(1)
                .map(QueuedCommandMapper::envelope)
                .filter(StringUtils::isNotEmpty)
                .map(MessageContent::ofText)
                .toList());
        }
        if (sessionTopicTitleCoordinator != null
                && !Strings.CS.equals("task-notification", cmd.mode())) {
            sessionTopicTitleCoordinator.onUserQuery(text, false);
        }
        turnView.prepareFirstTurnTranscriptMetadata(input);
        turnEngine.submit(input);
    }

    /**
     * Add a command to the live runtime queue. The prompt's reactive queue
     * projection renders from {@link TurnEngine#setInputQueueListener}; nothing
     * is appended to transcript history here.
     *
     * @param cmd         the command to queue for later execution
     * @param displayText text to show in the dim preview line (may differ from
     *                    cmd.text() for skill invocations)
     */
    @Override
    public void renderAndQueue(QueuedCommand cmd, String displayText) {
        QueuedCommand queued = cmd;
        if (cmd.preExpansionValue() == null && displayText != null
                && !displayText.equals(cmd.text())) {
            queued = new QueuedCommand(
                cmd.text(), cmd.pastedContents(), cmd.mode(), cmd.priority(), cmd.isMeta(),
                cmd.originKind(), cmd.skipSlashCommands(), cmd.bridgeOrigin(), displayText,
                cmd.workload(), cmd.agentId(), cmd.orphanedPermission(), cmd.taskId(),
                cmd.modelScheduledOrigin());
        }
        var transcript = queryEngine.execution().getTranscriptSink();
        if (transcript != null) {
            transcript.recordQueueOperation(
                queryEngine.conversation().getSessionId(), "enqueue", queued.text());
        }
        turnEngine.enqueue(queued);
    }

    private void executeQuery(String userInput, Map<Integer, PastedContent> pasted) {
        executeQuery(userInput, userInput, pasted);
    }

    private void executeRemoteQuery(String userInput, Map<Integer, PastedContent> pasted) {
        UserInput input = withStartupPromptProvenance(UserInput.of(
            userInput, userInput, pasted, inputPanel.getPermissionMode(), false))
            .withInputOrigin("remote");
        if (sessionTopicTitleCoordinator != null) {
            sessionTopicTitleCoordinator.onUserQuery(userInput, false);
        }
        turnView.prepareFirstTurnTranscriptMetadata(input);
        turnEngine.submit(input);
    }

    /**
     * Execute a query with separate display text and actual query content.
     */
    @Override
    public void executeQuery(String displayText, String queryContent,
                              Map<Integer, PastedContent> pasted) {
        UserInput input = withStartupPromptProvenance(UserInput.of(
            displayText, queryContent, pasted, inputPanel.getPermissionMode(), false));
        if (sessionTopicTitleCoordinator != null) {
            sessionTopicTitleCoordinator.onUserQuery(queryContent, false);
        }
        turnView.prepareFirstTurnTranscriptMetadata(input);
        turnEngine.submit(input);
    }


    @Override
    public void executeQuery(String displayText, String queryContent,
                              Map<Integer, PastedContent> pasted, boolean isSlash) {
        UserInput input = withStartupPromptProvenance(UserInput.of(
            displayText, queryContent, pasted, inputPanel.getPermissionMode(), isSlash));
        if (sessionTopicTitleCoordinator != null) {
            sessionTopicTitleCoordinator.onUserQuery(queryContent, isSlash);
        }
        turnView.prepareFirstTurnTranscriptMetadata(input);
        turnEngine.submit(input);
    }

    /**
     * Structured prompt-command path. Unlike the legacy string overload this
     * retains MCP image/document blocks and installs command-scoped hooks,
     * permissions and model overrides before the turn starts.
     */
    @Override
    public void executePrompt(String displayText, PromptInvocation invocation,
                              Map<Integer, PastedContent> pasted) {
        HookDispatcher.HookOutcome expansionOutcome =
            PromptInvocationAdapter.installTurnScopedState(
            invocation,
            displayText,
            PromptInvocationAdapter.commandNameFromDisplay(displayText),
            queryEngine.execution().getHookDispatcher(),
            (commandName, logicalPath, content) -> featureRuntime.invokedSkills()
                .record(null, commandName, logicalPath, content));
        if (!expansionOutcome.proceed() || expansionOutcome.preventContinuation()) {
            String reason = expansionOutcome.hasBlockingErrors()
                ? expansionOutcome.blockingErrors().getFirst()
                : expansionOutcome.stopReason();
            postSystemMessage(StringUtils.isNotBlank(reason)
                ? "Prompt expansion blocked by hook: " + reason
                : "Prompt expansion blocked by hook");
            queryEngine.execution().getHookDispatcher().clearInvocationHooks();
            return;
        }
        UserInput input = PromptInvocationAdapter.applyExpansionOutcome(
            PromptInvocationAdapter.toUserInput(
                displayText, invocation, pasted, inputPanel.getPermissionMode()),
            expansionOutcome);
        input = withStartupPromptProvenance(input);
        if (sessionTopicTitleCoordinator != null) {
            sessionTopicTitleCoordinator.onUserQuery(invocation.textContent(), true);
        }
        turnView.prepareFirstTurnTranscriptMetadata(input);
        turnEngine.submit(input);
    }

    private UserInput withStartupPromptProvenance(UserInput input) {
        UserInput routed = routingInteractiveStartupPrompt
            ? input.asInteractiveStartupPrompt() : input;
        lastSubmittedInputWasInteractiveStartupPrompt = routed.interactiveStartupPrompt();
        return routed;
    }


    private void acceptPlanWithClearedContext(PermissionDialog.PlanClearApproval approval) {
        if (approval == null || StringUtils.isBlank(approval.plan())) return;
        String previousSessionId = queryEngine.conversation().getSessionId();
        Path previousTranscript = StringUtils.isNotBlank(previousSessionId)
            && interactiveSessions != null
            ? interactiveSessions.sessionFile(System.getProperty("user.dir"), previousSessionId)
            : null;
        gui.getGUIThread().invokeLater(() -> {
            sessionController.clearConversation();
            permissionGate.applyUpdates(approval.permissionUpdates());
            String mode = permissionGate.currentMode().kind().wireValue();
            inputPanel.setPermissionMode(mode);
            permissionGate.markPlanModeExited();
            boolean hasAgentTool = AgentTeamsEnabled.isEnabled()
                && queryEngine.configuration().getConfig().tools().contains("Agent");
            String prompt = buildClearedContextPlanPrompt(
                approval.plan(), previousTranscript, hasAgentTool, approval.feedback());
            UserInput continuation = UserInput.of(
                    prompt, prompt, Map.of(), mode)
                .withQuerySource("auto-continuation")
                .withPlanContent(approval.plan());
            turnView.prepareFirstTurnTranscriptMetadata(continuation);
            turnEngine.submit(continuation);
        });
    }

    static String buildClearedContextPlanPrompt(String plan, Path previousTranscript) {
        return buildClearedContextPlanPrompt(plan, previousTranscript, false, null);
    }

    static String buildClearedContextPlanPrompt(
            String plan, Path previousTranscript, boolean hasAgentTool, String feedback) {
        String transcriptHint = previousTranscript == null ? ""
            : "\nIf you need specific details from before exiting plan mode (like exact code snippets, "
                + "error messages, or content you generated), read the full transcript at: "
                + previousTranscript;
        String teamHint = hasAgentTool
            ? """
                
                If this plan can be broken down into multiple independent tasks, consider spawning \
                named teammates with the Agent tool (pass a `name`) to parallelize the work."""
            : "";
        String normalizedFeedback = StringUtils.trimToNull(feedback);
        String feedbackSuffix = normalizedFeedback == null ? ""
            : "\nUser feedback on this plan: " + normalizedFeedback;
        return "Implement the following plan:\n" + plan
            + transcriptHint + teamHint + feedbackSuffix;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers — must be called from UI thread (or use invokeLater)
    // ──────────────────────────────────────────────────────────────────────

    private void appendLine(String text, TextColor color) {
        messagePanel.appendLine(text, color);
    }

    /**
     * Posts an inline system message to the transcript from any thread.
     */
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
            sessionHostTitleGeneration.incrementAndGet();
            publishedHostSessionTitle = title;
            if (inputPanel != null) inputPanel.setAgentName(title);
            if (terminalController != null) terminalController.setTitle(title);
            if (sessionHostRegistry != null) {
                sessionHostRegistry.refreshLocal(
                    buildHostSession(queryEngine.conversation().getSessionId()));
            }
        };
        if (gui == null) apply.run();
        else gui.getGUIThread().invokeLater(apply);
    }

    /** Adds one real system message to state, JSONL, history, and the visible transcript. */
    private void appendPersistentSystemMessage(SystemMessage message) {
        if (message == null) return;
        Runnable append = () -> {
            turnView.prepareStartupSystemTranscriptMetadata(inputPanel.getPermissionMode());
            queryEngine.conversation().appendTranscriptMessage(message);
            SDKMessage.System sdk = new SDKMessage.System(message);
            messageHistory.record(sdk);
            dispatcher.dispatch(sdk, messagePanel);
        };
        if (gui == null) append.run();
        else gui.getGUIThread().invokeLater(append);
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


    private int readBtwUseCount() {
        return UiSettings.readGlobalInt("btwUseCount", 0);
    }


    private void undoLastMessage() {
        // Get the last submitted input
        String lastInput = lastSubmittedInput;
        if (StringUtils.isEmpty(lastInput)) {
            messagePanel.appendLine("  [Nothing to undo]", LanternaTheme.welcomeDim());
            try { screen.refresh(); } catch (Exception _) {}
            return;
        }

        // Rewind the conversation by removing the last message pair
        var mutableMessages = queryEngine.conversation().getMessages();
        if (mutableMessages != null && !mutableMessages.isEmpty()) {
            int lastIdx = mutableMessages.size() - 1;
            var lastMsg = mutableMessages.get(lastIdx);
            if (lastMsg instanceof AssistantMessage) {
                mutableMessages.remove(lastIdx);
            } else if (lastIdx > 0 && mutableMessages.get(lastIdx - 1) instanceof AssistantMessage) {
                mutableMessages.remove(lastIdx);     // remove user message
                mutableMessages.remove(lastIdx - 1); // remove assistant message
            } else {
                mutableMessages.remove(lastIdx); // just remove the last message
            }
        }

        // Remove from prompt history
        promptHistory.removeLastEntry();

        // Restore the input text
        inputPanel.setText(lastInput);
        lastSubmittedInput = null;

        // Clear the message panel and re-render
        messagePanel.clear();
        messagePanel.appendLine("  [Undone — edit and resubmit]", LanternaTheme.welcomeDim());

        try { screen.refresh(RefreshType.COMPLETE); } catch (Exception _) {}
    }

    /**
     * Triggers a (debounced) refresh of the custom status line via {@link
     * com.claudecode.ui.lanterna.statusline.StatusLineController}.
     */
    private void executeStatusLineCommand() {
        if (statusLineController != null) statusLineController.scheduleUpdate();
    }

    /** Refreshes model-sensitive HUD state without the ordinary interaction debounce. */
    private void executeStatusLineCommandImmediately() {
        if (statusLineController != null) statusLineController.scheduleInitialUpdate();
    }

    /**
     * Assembles the live {@code StatusLineCommandInput} ingredients from the current REPL state.
     */
    private StatusLineInputBuilder.Ingredients statusLineIngredients() {
        String sid = queryEngine.conversation().getSessionId();
        String cwd = System.getProperty("user.dir");
        String sessionName = (StringUtils.isNotBlank(sid))
            ? interactiveSessions.readCustomTitle(cwd, sid) : null;
        String transcript = (StringUtils.isNotBlank(sid))
            ? interactiveSessions.sessionFile(cwd, sid).toString() : "";
        List<String> addedDirs = permissionGate != null
            ? permissionGate.currentContext().additionalDirs().keySet().stream().map(Path::toString).toList()
            : List.of();
        String outputStyle = UiSettings.readStringFromSettings("outputStyle");

        // an opusplan setting shows Opus while plan mode is active.
        String runtimeModel = statusLineRuntimeModel();
        Long contextWindow = customModels != null ? customModels.contextWindow(runtimeModel) : null;
        return new StatusLineInputBuilder.Ingredients(
            sid, sessionName, transcript, cwd, cwd, addedDirs,
            runtimeModel, outputStyle,
            inputPanel.getVimMode(),
            VersionCommand.readVersion(), contextWindow,
            queryEngine.execution().getSessionMetrics());
    }

    private String statusLineRuntimeModel() {
        PermissionModeKind permMode = permissionGate != null
            ? permissionGate.currentMode().kind() : null;
        return ModelNames.runtimeMainLoopModel(
            queryEngine.configuration().getConfig().model(), permMode, false);
    }

    /** Effective effort sent by the session, or {@code auto} for an unknown custom endpoint. */
    private String statusLineEffort() {
        String runtimeModel = statusLineRuntimeModel();
        if (!EffortHelpers.modelSupportsEffort(runtimeModel)) return null;
        String configured = queryEngine.configuration().getEffortOverride() != null
            ? queryEngine.configuration().getEffortOverride() : queryEngine.configuration().getConfig().effortValue();
        String applied = EffortHelpers.resolveAppliedEffort(
            runtimeModel, configured, queryEngine.configuration().getConfig().isCustomModel(runtimeModel));
        return applied != null ? applied : "auto";
    }

    /**
     * Open the user's external editor ($VISUAL or $EDITOR) to compose a longer message.
     */
    private void openExternalEditor() {
        Thread.ofVirtual().name("external-editor").start(() -> {
            Path tmpFile = null;
            boolean screenStopped = false;
            String editedContent = null;
            try {
                // Detect editor: $VISUAL > $EDITOR > platform fallback.
                String editor = SubprocessEnvironment.get("VISUAL");
                if (StringUtils.isEmpty(editor)) {
                    editor = SubprocessEnvironment.get("EDITOR");
                }
                if (StringUtils.isEmpty(editor)) {
                    editor = ExternalEditorDefaults.defaultCommand();
                }
                ExternalEditorCommand command = ExternalEditorCommand.resolve(editor);

                // Write current input to a temp file
                String currentInput = inputPanel.getText();
                tmpFile = FileUtils.createTempFile("claude-code-input", ".md");
                Files.writeString(tmpFile, currentInput);

                // Stop the screen to give the editor full terminal control

                // child inherits stdio. A queued stop plus a fixed sleep can
                // launch the editor while Lanterna still owns the terminal.
                disableMouseBeforeHandoff();
                screen.stopScreen();
                screenStopped = true;


                // from the shared resolver before waitFor() returns.
                ProcessBuilder pb = new ProcessBuilder(command.argvFor(tmpFile)).inheritIO();
                Process p = pb.start();
                int exitCode = p.waitFor();
                if (exitCode != 0) {
                    log.info("[LANTERNA] External editor '{}' exited with code {}", editor, exitCode);
                }

                // Read the edited content
                editedContent = Files.readString(tmpFile);

                // Strip trailing newline (editors often add one)
                if (Strings.CS.endsWith(editedContent, "\n")) {
                    editedContent = editedContent.substring(0, editedContent.length() - 1);
                }
            } catch (Exception e) {
                log.warn("[LANTERNA] External editor failed", e);
            } finally {
                try {
                    if (tmpFile != null) Files.deleteIfExists(tmpFile);
                } catch (IOException cleanupFailure) {
                    log.debug("[LANTERNA] External editor temp-file cleanup failed: {}",
                        cleanupFailure.getMessage());
                }
                if (screenStopped) {
                    final String finalContent = editedContent;
                    try {
                        gui.getGUIThread().invokeLater(() -> {
                            try {
                                screen.startScreen();
                                restoreMouseAfterHandoff();
                                if (finalContent != null) inputPanel.setText(finalContent);
                                screen.refresh(RefreshType.COMPLETE);
                            } catch (Exception restoreFailure) {
                                log.warn("[LANTERNA] Failed to restore screen after external editor",
                                    restoreFailure);
                            }
                        });
                    } catch (RuntimeException schedulingFailure) {
                        log.warn("[LANTERNA] Could not schedule screen restore", schedulingFailure);
                        try {
                            screen.startScreen();
                            restoreMouseAfterHandoff();
                        } catch (Exception _) {}
                    }
                }
            }
        });
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

    /** Runs the same full restore pipeline as the interactive session picker. */
    public void resumeSession(ResumeRequest request) {
        sessionController.resume(request);
    }

    /** Native Session Host create/resume command; safe to call from a virtual thread. */
    public CompletableFuture<SessionHostSession> activateHostSession(SessionOpenRequest request) {
        if (request == null) return CompletableFuture.failedFuture(
            new IllegalArgumentException("session request is required"));
        String requested = request.requestedSessionId();
        if (StringUtils.isBlank(requested)) {
            CompletableFuture<SessionHostSession> result = new CompletableFuture<>();
            gui.getGUIThread().invokeLater(() -> {
                try {
                    sessionController.clearConversation();
                    result.complete(currentHostSession());
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }
        if (requested.equals(queryEngine.conversation().getSessionId())) {
            return CompletableFuture.completedFuture(currentHostSession());
        }
        String searchCwd = StringUtils.isBlank(request.workDir())
            ? commandContext.session().workingDirectory() : request.workDir();
        return CompletableFuture.supplyAsync(() -> interactiveSessions
                .findExactSession(searchCwd, requested)
                .orElseThrow(() -> new IllegalArgumentException(
                    "session not found: " + requested)))
            .thenCompose(located -> sessionController.resumeAsync(new ResumeRequest(
                located.id(), located.transcriptPath(), located.projectPath(),
                ResumeRequest.Entrypoint.SLASH_COMMAND_SESSION_ID)).toCompletableFuture())
            .thenApply(_ -> currentHostSession());
    }

    /** Snapshot for the CLI-owned registry/list adapter. */
    public SessionHostSession currentHostSession() {
        if (sessionEvents == null || submissionCoordinator == null) {
            throw new IllegalStateException("Session Host is not ready");
        }
        return buildHostSession(queryEngine.conversation().getSessionId());
    }

    /**
     * {@code /rewind} entry — wired into
     * {@link com.claudecode.commands.CommandPresentationPorts#openMessageSelector}.
     * Delegates to {@link SessionController#openMessageSelector()}.
     */
    public void openMessageSelector() {
        sessionController.openMessageSelector();
    }

    // ── SlashHost command port ───────────────────────────────────────────
    @Override public boolean isTurnInFlight() { return turnEngine.isInFlight(); }
    @Override public boolean isLongRunningCommandInFlight() {
        return submissionCoordinator.longRunningInFlight();
    }
}
