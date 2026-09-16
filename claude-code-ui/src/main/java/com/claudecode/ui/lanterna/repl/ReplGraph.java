package com.claudecode.ui.lanterna.repl;

import com.claudecode.tools.cron.CronScheduler;
import com.claudecode.ui.lanterna.bashmode.BashModeExecutor;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.dialog.CollaborationPickerDialog;
import com.claudecode.ui.lanterna.dialog.FeishuSetupDialog;
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
import com.claudecode.ui.lanterna.features.tasks.BackgroundTasksFeature;
import com.claudecode.ui.lanterna.features.web.WebGatewayFeature;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.mouse.SelectionController;
import com.claudecode.ui.lanterna.slash.SlashCommandDispatcher;
import com.claudecode.ui.lanterna.statusline.StatusLineController;
import com.claudecode.ui.lanterna.suggest.SuggestionController;
import com.claudecode.ui.lanterna.transcript.MessageActionsController;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.ToolApprovalInteraction;
import com.claudecode.ui.lanterna.transcript.TranscriptController;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.TurnEngine;
import com.googlecode.lanterna.gui2.BasicWindow;
import java.util.concurrent.CompletionStage;

/**
 * The fully composed, immutable REPL object graph that {@link ReplComposer#compose} returns.
 *
 * <p>Grouped by concern so that the screen and {@link ReplSceneLayout} read
 * {@code graph.features().preferences()} rather than one of sixty screen fields. Everything in
 * here is constructed exactly once per REPL run and lives until the window closes.
 *
 * @param mainWindow the attached fullscreen window; null until {@link ReplScene#attach} ran
 */
record ReplGraph(
    Widgets widgets,
    Features features,
    Controllers controllers,
    Engine engine,
    BasicWindow mainWindow) {

    /** Bare Lanterna components the screen still addresses directly. */
    record Widgets(
        MessagePanel messagePanel,
        SpinnerComponent spinnerComponent,
        InputPanel inputPanel,
        ProjectPanel projectPanel,
        CoordinatorTaskPanel coordinatorTaskPanel,
        LspRecommendationDialog lspRecommendationDialog,
        PluginHintMenu pluginHintMenu,
        ThinkingToggleDialog thinkingToggleDialog,
        CollaborationPickerDialog collaborationPickerDialog,
        FeishuSetupDialog feishuSetupDialog) {}

    /** Feature facades: each owns its dialogs and (where relevant) a command-bridge capability. */
    record Features(
        ToolApprovalInteraction toolApproval,
        TaskBoardFeature taskBoard,
        StartupGateDialogs startupGates,
        BypassPermissionsStartupGate bypassPermissionsGate,
        PreferencesFeature preferences,
        PermissionsFeature permissions,
        AgentsFeature agents,
        SandboxFeature sandbox,
        MemoryFeature memory,
        SessionController session,
        ConversationToolsFeature conversationTools,
        DiagnosticsFeature diagnostics,
        BackgroundTasksFeature tasks,
        PluginsFeature plugins,
        GoalFeature goal,
        HooksController hooks,
        MCPController mcp,
        PokemonFeature pokemon,
        BtwFeature btw,
        ReplExitController exit,
        CompactProgressPresenter compactProgress,
        WebGatewayFeature webGateway) {}

    /** Non-visual coordinators the screen drives from its lifecycle and input port. */
    record Controllers(
        ProjectPanelController projectPanel,
        StartupGateController startupGate,
        ReplInterruptActions interrupt,
        SelectionController selection,
        TranscriptController transcript,
        LocalAgentInputRouter localAgentInput,
        MessageActionsController messageActions,
        AutoModeEntryWarningController autoModeEntryWarning,
        SuggestionController suggestion,
        StatusLineController statusLine,
        CronScheduler cronScheduler,
        WelcomePresenter welcome) {}

    /** Turn orchestration and the text-to-turn pipeline. */
    record Engine(
        LanternaSessionSink turnView,
        SessionEventHub sessionEvents,
        TurnEngine turnEngine,
        ReplSubmissionCoordinator submission,
        SlashCommandDispatcher slashDispatcher,
        BashModeExecutor bashMode,
        CompletionStage<Void> hotUiReadiness) {}
}
