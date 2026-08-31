package com.claudecode.cli;

import com.claudecode.commands.tooling.ToolingCommandPorts;
import com.claudecode.core.engine.PermissionExplainerCallback;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.runtime.compact.CompactWarningProvider;
import com.claudecode.runtime.doctor.DoctorPort;
import com.claudecode.runtime.hooks.HookConfigurationPort;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.runtime.session.ConversationResetPort;
import com.claudecode.runtime.session.SessionLifecycle;
import com.claudecode.runtime.sessionhost.CollaborationSetupPort;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.shutdown.ShutdownPort;
import com.claudecode.runtime.startup.StartupTrustPort;
import com.claudecode.runtime.statusline.StatusLinePort;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.runtime.turn.TurnAwakeGuard;
import com.claudecode.session.stats.StatsAggregator;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.loop.LoopWakeupManager;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import com.claudecode.ui.lanterna.repl.ReplApplicationPorts;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplFeatureRuntime;
import com.claudecode.ui.lanterna.repl.ReplLaunchState;
import com.claudecode.ui.lanterna.repl.ReplStartupReadiness;
import com.claudecode.ui.lanterna.repl.ReplWiring;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Predicate;

/**
 * Sole CLI assembler for the collaborators shared by one interactive session.
 */
final class CliInteractiveRuntimeAssembler {
    private final TaskRegistry tasks = TaskRegistry.global();
    private final WorkflowRunStore workflows = WorkflowRunStore.global();
    private final InvokedSkillRegistry invokedSkills = InvokedSkillRegistry.global();
    private final LoopWakeupManager loopWakeups = LoopWakeupManager.global();
    private final InteractiveSessionPort sessions;
    private final ToolingCommandPorts toolingCommands =
        CliToolingCommandAdapter.create(tasks, invokedSkills);

    CliInteractiveRuntimeAssembler() {
        this(_ -> false);
    }

    CliInteractiveRuntimeAssembler(Predicate<String> builtInCommand) {
        this.sessions = new CliInteractiveSessionAdapter(
            new StatsAggregator(), builtInCommand);
    }

    ToolingCommandPorts toolingCommands() { return toolingCommands; }
    InteractiveSessionPort sessions() { return sessions; }
    TaskRegistry tasks() { return tasks; }
    WorkflowRunStore workflows() { return workflows; }
    InvokedSkillRegistry invokedSkills() { return invokedSkills; }
    LoopWakeupManager loopWakeups() { return loopWakeups; }

    ReplApplicationPorts application(
            ReplCommandUiBridge commandUi, HookConfigurationPort hooks,
            McpManagementPort mcp, CompactWarningProvider compactWarnings,
            SessionLifecycle sessionLifecycle, ConversationResetPort conversationReset,
            MemoryCatalog memory, OutputStyleCatalog outputStyles, DoctorPort doctor,
            PluginMarketplacePort plugins, StatusLinePort statusLine,
            StartupTrustPort startupTrust, ShutdownPort shutdown, TurnAwakeGuard awakeGuard,
            TaskBoardPort taskBoard) {
        return new ReplApplicationPorts(commandUi, sessions, hooks, mcp, compactWarnings,
            sessionLifecycle, conversationReset, memory, outputStyles, doctor, plugins,
            statusLine, startupTrust, shutdown, awakeGuard, taskBoard);
    }

    ReplFeatureRuntime features(
            PermissionGate permissionGate, ToolRegistry toolRegistry,
            Supplier<List<Skill>> skills, Consumer<String> skillHookRegistrar,
            PermissionExplainerCallback permissionExplainer) {
        return new ReplFeatureRuntime(permissionGate, toolRegistry, tasks, workflows,
            invokedSkills, loopWakeups, skills, skillHookRegistrar, permissionExplainer,
            WorktreeService::getCurrentWorktreeSession);
    }

    ReplLaunchState launch(
            UserKeybindingsStore keybindings, boolean allowDangerouslySkipPermissions,
            String initialPrompt, String initialSessionName, boolean restoredSession,
            Function<String, CompletableFuture<String>> sessionTitleGenerator,
            boolean showBuiltInModelFamilies, CustomModelCatalog customModels,
            Supplier<String> tipSupplier, SessionHostRegistry sessionHostRegistry,
            InteractionCoordinator interactionCoordinator,
            SessionCollaborationController collaborationController,
            CollaborationSetupPort collaborationSetup) {
        return new ReplLaunchState(keybindings, allowDangerouslySkipPermissions, initialPrompt,
            initialSessionName, restoredSession, sessionTitleGenerator,
            showBuiltInModelFamilies, customModels,
            tipSupplier, sessionHostRegistry, interactionCoordinator, collaborationController,
            collaborationSetup);
    }

    ReplWiring assemble(ReplApplicationPorts application, ReplFeatureRuntime features,
                        ReplLaunchState launch) {
        return new ReplWiring(application, features, launch);
    }

    ReplWiring assemble(ReplApplicationPorts application, ReplFeatureRuntime features,
                        ReplLaunchState launch, ReplStartupReadiness readiness) {
        return new ReplWiring(application, features, launch, readiness);
    }
}
