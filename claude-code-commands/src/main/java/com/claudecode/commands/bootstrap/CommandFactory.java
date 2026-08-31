package com.claudecode.commands.bootstrap;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandRegistry;

import com.claudecode.commands.impl.config.AddDirCommand;
import com.claudecode.commands.impl.agents.AdvisorCommand;
import com.claudecode.commands.impl.agents.AgentsCommand;
import com.claudecode.commands.impl.git.BranchCommand;
import com.claudecode.commands.impl.terminal.BtwCommand;
import com.claudecode.commands.impl.session.ClearCommand;
import com.claudecode.commands.impl.config.ColorCommand;
import com.claudecode.commands.impl.context.CompactCommand;
import com.claudecode.commands.impl.config.ConfigCommand;
import com.claudecode.commands.impl.context.ContextCommand;
import com.claudecode.commands.impl.terminal.CopyCommand;
import com.claudecode.commands.impl.info.CostCommand;
import com.claudecode.commands.impl.git.DiffCommand;
import com.claudecode.commands.impl.info.DoctorCommand;
import com.claudecode.commands.impl.agents.DreamCommand;
import com.claudecode.commands.impl.config.EffortCommand;
import com.claudecode.commands.impl.session.ExitCommand;
import com.claudecode.commands.impl.session.ExportCommand;
import com.claudecode.commands.impl.context.GoalCommand;
import com.claudecode.commands.impl.info.HelpCommand;
import com.claudecode.commands.impl.integration.HooksCommand;
import com.claudecode.commands.impl.git.InitCommand;
import com.claudecode.commands.impl.context.InsightsCommand;
import com.claudecode.commands.impl.terminal.KeybindingsCommand;
import com.claudecode.commands.impl.context.MemoryCommand;
import com.claudecode.commands.impl.config.ModelCommand;
import com.claudecode.commands.impl.config.OutputStyleCommand;
import com.claudecode.commands.impl.config.PermissionsCommand;
import com.claudecode.commands.impl.config.PokemonCommand;
import com.claudecode.commands.impl.git.PlanCommand;
import com.claudecode.commands.impl.integration.PluginCommand;
import com.claudecode.commands.impl.integration.ReloadPluginsCommand;
import com.claudecode.commands.impl.session.RenameCommand;
import com.claudecode.commands.impl.session.ResumeCommand;
import com.claudecode.commands.impl.session.RewindCommand;
import com.claudecode.commands.impl.config.SandboxToggleCommand;
import com.claudecode.commands.impl.integration.SkillsCommand;
import com.claudecode.commands.impl.info.StatsCommand;
import com.claudecode.commands.impl.info.StatusCommand;
import com.claudecode.commands.impl.terminal.StatuslineCommand;
import com.claudecode.commands.impl.info.StubCommand;
import com.claudecode.commands.impl.info.TagCommand;
import com.claudecode.commands.impl.terminal.TasksCommand;
import com.claudecode.commands.impl.config.ThemeCommand;
import com.claudecode.commands.impl.info.UsageCommand;
import com.claudecode.commands.impl.info.VersionCommand;
import com.claudecode.commands.impl.integration.WorkflowsCommand;

import java.util.ArrayList;
import java.util.List;
import com.claudecode.runtime.settings.SettingsManagementPort;
import com.claudecode.commands.tooling.ToolingCommandPorts;

/**
 * Factory that creates a CommandRegistry pre-populated with all default commands.
 */
public final class CommandFactory {

    private CommandFactory() {}

    /**
     * Create a registry with all built-in commands registered.
     */
    public static CommandRegistry createDefault() {
        return createDefault(SettingsManagementPort.none(), ToolingCommandPorts.none());
    }

    public static CommandRegistry createDefault(SettingsManagementPort settings,
                                                 ToolingCommandPorts tooling) {
        CommandRegistry registry = new CommandRegistry();
        List<Command> builtIns = new ArrayList<>();

        // P0 commands
        builtIns.add(new HelpCommand(registry));
        builtIns.add(new ExitCommand());
        builtIns.add(new ClearCommand());
        builtIns.add(new CompactCommand());
        builtIns.add(new ConfigCommand());
        builtIns.add(new OutputStyleCommand());
        builtIns.add(new SandboxToggleCommand(settings.sandbox(), tooling.sandbox()));
        builtIns.add(new ModelCommand());
        builtIns.add(new CostCommand());
        builtIns.add(new ReloadPluginsCommand());
        builtIns.add(new RenameCommand());
        builtIns.add(new AdvisorCommand());

        // P1 commands
        builtIns.add(new BtwCommand());
        builtIns.add(new InsightsCommand());
        builtIns.add(new DiffCommand());
        builtIns.add(new ResumeCommand());
        builtIns.add(new ExportCommand());
        builtIns.add(new MemoryCommand());
        builtIns.add(new DoctorCommand());
        builtIns.add(new PermissionsCommand());
        builtIns.add(new StatusCommand());

        // P2 commands
        builtIns.add(new BranchCommand());
        builtIns.add(new SkillsCommand());
        builtIns.add(new StatsCommand());
        builtIns.add(new InitCommand());
        // /dream: prompt-type command — injects the memory-consolidation prompt
        // into the main loop (main agent performs the dream under normal perms).
        builtIns.add(new DreamCommand());
        builtIns.add(new GoalCommand());
        builtIns.add(new HooksCommand());
        builtIns.add(new ThemeCommand());

        // into the session-history picker (LogSelector), not as a standalone
        // slash command.
        builtIns.add(new CopyCommand());
        // /mcp is registered by the CLI startup path — it requires a live
        // McpClientManager (built after CommandFactory runs) and must not
        // exist in the registry until that dependency is available.
        builtIns.add(new PluginCommand());
        builtIns.add(new EffortCommand());
        builtIns.add(new AddDirCommand());
        builtIns.add(new AgentsCommand());
        builtIns.add(new UsageCommand());
        builtIns.add(new ColorCommand());
        builtIns.add(new PokemonCommand());
        builtIns.add(new RewindCommand());
        builtIns.add(new TasksCommand());
        builtIns.add(new WorkflowsCommand());
        builtIns.add(new KeybindingsCommand());
        builtIns.add(new StatuslineCommand());
        builtIns.add(new PlanCommand());
        builtIns.add(new ContextCommand());
        builtIns.add(new VersionCommand());
        builtIns.add(new TagCommand());

        // Compatibility stubs remain discoverable while their implementations are unavailable.
        builtIns.add(new StubCommand("security-review",
            "Complete a security review of the pending changes on the current branch"));
        builtIns.add(new StubCommand("init-verifiers",
            "Create verifier skill(s) for automated verification of code changes"));
        builtIns.add(new StubCommand("terminal-setup",
            "Install Shift+Enter key binding for newlines"));
        builtIns.add(new StubCommand("heapdump",
            "Dump the JS heap to ~/Desktop"));
        builtIns.add(new StubCommand("ide",
            "Manage IDE integrations and show status"));
        builtIns.add(new StubCommand("remote-control",
            "Connect this terminal for remote-control sessions", List.of("rc")));
        builtIns.add(new StubCommand("brief",
            "Toggle brief-only mode"));
        builtIns.add(new StubCommand("install",
            "Install Claude Code native build"));
        builtIns.add(new StubCommand("review",
            "Review a pull request"));
        builtIns.add(new StubCommand("ultrareview",
            "~10–20 min · Finds and verifies bugs in your branch. "
            + "Runs in Claude Code on the web. "
            + "See https://code.claude.com/docs/en/claude-code-on-the-web"));

        builtIns.add(new StubCommand("ultraplan",
            "~10–30 min · Claude Code on the web drafts an advanced plan "
            + "you can edit and approve. "
            + "See https://code.claude.com/docs/en/claude-code-on-the-web"));
        builtIns.add(new StubCommand("pr-comments",
            "Get comments from a GitHub pull request"));
        builtIns.add(new StubCommand("stickers",
            "Order Claude Code stickers"));
        builtIns.add(new StubCommand("release-notes",
            "View release notes"));
        builtIns.add(new StubCommand("commit",
            "Create a git commit"));
        builtIns.add(new StubCommand("commit-push-pr",
            "Commit, push, and open a PR"));
        builtIns.add(new StubCommand("files",
            "List all files currently in context"));









        builtIns.add(new StubCommand("feedback",
            "Submit feedback about Claude Code", List.of("bug")));
        // /vim — NOT_IMPL：官方发布版 CLI 里已下掉（用户 2026-07-03 验证
        // /vim → unknown command）。Java 侧不做 InputPanel vim-mode

        // MULTI_LINE 分支，用户已明确 "不用你管 InputPanel"）。
        builtIns.add(new StubCommand("vim",
            "Toggle between Vim and Normal editing modes"));

        // Subscription / experimental commands. They remain registered for
        // exact-name compatibility, but StubCommand.isHidden() keeps them out

        builtIns.add(new StubCommand("chrome",
            "Claude in Chrome (Beta) settings"));
        builtIns.add(new StubCommand("desktop",
            "Continue the current session in Claude Desktop", List.of("app")));
        builtIns.add(new StubCommand("extra-usage",
            "Configure extra usage to keep working when limits are hit"));
        builtIns.add(new StubCommand("install-github-app",
            "Set up Claude GitHub Actions for a repository"));
        builtIns.add(new StubCommand("install-slack-app",
            "Install the Claude Slack app"));
        builtIns.add(new StubCommand("passes",
            "Share a free week of Claude Code with friends"));
        builtIns.add(new StubCommand("rate-limit-options",
            "Show options when rate limit is reached"));
        builtIns.add(new StubCommand("remote-env",
            "Configure the default remote environment for teleport sessions"));
        builtIns.add(new StubCommand("web-setup",
            "Setup Claude Code on the web (requires connecting your GitHub account)"));
        builtIns.add(new StubCommand("session",
            "Show remote session URL and QR code", List.of("remote")));
        builtIns.add(new StubCommand("think-back",
            "Your 2025 Claude Code Year in Review"));
        builtIns.add(new StubCommand("thinkback-play",
            "Play the thinkback animation"));
        builtIns.add(new StubCommand("voice",
            "Toggle voice mode"));
        // Reserved compatibility entries remain hidden and dispatchable.
        builtIns.add(new StubCommand("env", "Reserved (TS-side disabled stub)"));
        builtIns.add(new StubCommand("share", "Reserved (TS-side disabled stub)"));

        // Misc stub commands — kept at the bottom alongside the other stubs.
        // (Previously interleaved in the P2 block above.)
        // Account-bound commands remain stubs because subscriber login state is unavailable.
        builtIns.add(new StubCommand("login", "Authenticate with API provider"));
        builtIns.add(new StubCommand("logout", "Sign out from your Anthropic account"));
        builtIns.add(new StubCommand("mobile", "Show QR code to download the Claude mobile app",
            List.of("ios", "android")));
        // /fast: Subscription-only feature (fast mode with the current Opus model)
        builtIns.add(new StubCommand("fast", "Toggle fast mode (Opus 5) for quicker responses"));
        // /upgrade: claude.ai subscription feature
        builtIns.add(new StubCommand("upgrade", "Upgrade to Max for higher rate limits and more Opus"));
        // /privacy-settings: claude.ai subscription feature
        builtIns.add(new StubCommand("privacy-settings", "Review and manage privacy settings"));

        registry.registerAll(builtIns);
        registry.markAllRegisteredAsBuiltIn();
        return registry;
    }
}
