package com.claudecode.cli;

import com.claudecode.cli.daemon.DaemonWorkerDispatcher;
import com.claudecode.runtime.query.AutoDreamEngine;
import com.claudecode.core.config.VersionInfo;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.tools.mcp.ManagedMcpRuntime;
import com.claudecode.tools.mcp.McpToolProvider;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Stable Picocli root command and process-exit boundary for the CLI.
 *
 * <ul>
 *   <li>preserves the executable root,
 *       option parsing, root subcommands, and process exit-code boundary.</li>
 *   <li>freezes parsed launch options and delegates
 *       common session setup before SDK, headless, or interactive routing.</li>
 * </ul>
 */
@Command(
    name = "claude",
    versionProvider = ClaudeCodeCli.BuildVersionProvider.class,
    description = "Claude Code — AI-powered CLI tool for software development",
    mixinStandardHelpOptions = true,
    subcommands = {McpCliCommand.class}
)
public class ClaudeCodeCli implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeCli.class);

    /**
     * Reports the version stamped into the jar manifest at build time, so {@code --version}
     * cannot drift from the release tag the way a literal in {@code @Command} does. Shares
     * {@link VersionInfo} with {@code /version}, {@code /doctor}, telemetry, and the
     * {@code user-agent} header.
     */
    static final class BuildVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] { "claude-code-java " + VersionInfo.version() };
        }
    }

    static final class EffortLevelConverter implements CommandLine.ITypeConverter<String> {
        @Override
        public String convert(String value) {
            String normalized = value != null ? value.toLowerCase(Locale.ROOT) : "";
            if (!EffortHelpers.isEffortLevel(normalized)) {
                throw new CommandLine.TypeConversionException(
                    "It must be one of: none, minimal, low, medium, high, xhigh, max");
            }
            return normalized;
        }
    }

    static final class ThinkingModeConverter implements CommandLine.ITypeConverter<String> {
        @Override
        public String convert(String value) {
            String normalized = value != null ? value.toLowerCase(Locale.ROOT) : "";
            if (!List.of("enabled", "adaptive", "disabled").contains(normalized)) {
                throw new CommandLine.TypeConversionException(
                    "It must be one of: enabled, adaptive, disabled");
            }
            return normalized;
        }
    }

    @Option(names = {"--effort"}, converter = EffortLevelConverter.class,
            description = "Effort level for the current session (model-dependent)")
    String effort;

    @Option(names = {"--thinking"}, converter = ThinkingModeConverter.class, hidden = true,
            description = "Thinking mode: enabled (equivalent to adaptive), disabled")
    String thinkingMode;

    @Option(names = {"--max-thinking-tokens"}, hidden = true,
            description = "Maximum number of thinking tokens (only works with --print)")
    Integer maxThinkingTokens;

    @Option(names = {"--fallback-model"},
            description = "Model to fall back to when the primary model is overloaded")
    String fallbackModel;

    @Option(names = {"--model", "-m"}, description = "Model alias or model ID to use")
    private String model;

    @Option(names = {"--api-key"}, description = "Anthropic API key (overrides env/config)")
    private String apiKey;

    @Option(names = {"--system-prompt"}, description = "Custom system prompt")
    private String systemPrompt;

    @Option(names = {"--system-prompt-file"}, hidden = true,
        description = "Read the custom system prompt from a file")
    private String systemPromptFile;

    @Option(names = {"--append-system-prompt"},
        description = "Append text to the default system prompt")
    private String appendSystemPrompt;

    @Option(names = {"--append-system-prompt-file"}, hidden = true,
        description = "Read appended system prompt text from a file")
    private String appendSystemPromptFile;

    @Option(names = {"--plan-mode-instructions"}, hidden = true,
        description = "Custom workflow body for plan mode")
    private String planModeInstructions;

    // No defaultValue: null means "not explicitly set" so the effective
    // value below can fall back to the resolved model's own default
    // (ModelOutputTokens.getModelMaxOutputTokens) instead of one constant

    @Option(names = {"--max-tokens"}, description = "Maximum tokens per response (default: model-specific)")
    private Integer maxTokens;


    @Option(names = {"--max-turns"},
        description = "Maximum number of agentic turns in non-interactive mode. "
            + "This will early exit the conversation after the specified number "
            + "of turns. (only works with --print)",
        defaultValue = "0")
    private int maxTurns;

    @Option(names = {"--max-budget-usd"}, description = "Maximum USD budget for the session", defaultValue = "-1.0")
    private double maxBudgetUsd;

    @Option(names = {"--output-format"},
        description = "Output format (only works with --print): \"text\" (default), \"json\" (single result), or \"stream-json\" (realtime streaming)",
        defaultValue = "text")
    private String outputFormat;

    @Option(names = {"--input-format"},
        description = "Input format for SDK control mode: \"text\" (default) or \"stream-json\" "
            + "(reads NDJSON from stdin — user messages plus control_response for the SDK control protocol). "
            + "Requires --output-format=stream-json.",
        defaultValue = "text")
    private String inputFormat;

    @Option(names = {"--include-partial-messages"},
        description = "Include partial message chunks as they arrive (only works with --print and --output-format=stream-json)")
    private boolean includePartialMessages;

    @Option(names = {"--replay-user-messages"},
        description = "Re-emit stream-json user messages on stdout as acknowledgments")
    private boolean replayUserMessages;

    @Option(names = {"--permission-prompt-tool"}, hidden = true,
        description = "Permission prompt transport used by SDK stream-json mode")
    private String permissionPromptToolName;

    @Option(names = {"--json-schema"},
        description = "JSON Schema for structured output validation. "
            + "Example: {\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}")
    private String jsonSchemaRaw;

    @Option(names = {"--base-url"}, description = "Custom API base URL (e.g., https://api.minimaxi.com/anthropic)")
    private String baseUrl;

    @Option(names = {"--no-interactive"}, description = "Disable interactive REPL mode (process single prompt and exit)")
    private boolean noInteractive;



    @Option(names = {"--verbose", "-v"}, description = "Verbose output — include full API response metadata")
    private boolean verbose;

    @Option(names = {"--debug"}, description = "Enable debug logging")
    private boolean debug;

    @Option(names = "--disable-slash-commands",
        description = "Disable all skills")
    private boolean disableSlashCommands;

    @Option(names = {"--print", "-p"}, description = "Non-interactive print mode: print the response and exit")
    private boolean printMode;

    @Option(names = {"--continue", "-c"}, description = "Continue the most recent session")
    private boolean continueLastSession;


    @Option(names = {"--resume", "-r"}, arity = "0..1",
        description = "Resume a conversation by session ID, or open the interactive picker")
    private String resumeSession;

    @Option(names = {"--session-id"},
        description = "Use a specific UUID for the new conversation")
    private String sessionId;

    @Option(names = {"--name", "-n"},
        description = "Set a display name for this session (shown in the prompt box, /resume picker, and terminal title)")
    private String sessionName;

    @Option(names = "--init", hidden = true)
    private boolean init;

    @Option(names = "--init-only", hidden = true)
    private boolean initOnly;

    @Option(names = "--maintenance", hidden = true)
    private boolean maintenance;

    @Option(names = {"--theme"}, description = "UI theme: dark (default), light, dark-daltonized, light-daltonized, dark-ansi, light-ansi")
    private String theme;

    @Option(names = {"--settings"},
        description = "Path to a settings JSON file or a JSON string to load additional settings from")
    private String settingsFileOrJson;

    @Option(names = {"--setting-sources"},
        description = "Comma-separated list of setting sources to load (user, project, local).")
    private String settingSourcesRaw;

    @Option(names = {"--mcp-config"}, arity = "1..*",
        description = "Load MCP servers from JSON files or inline JSON objects (repeatable)")
    private final List<String> mcpConfigCli = new ArrayList<>();

    @Option(names = {"--strict-mcp-config"},
        description = "Use only servers supplied by --mcp-config")
    private boolean strictMcpConfig;


    @Option(names = {"--add-dir"}, arity = "1..*", split = ",",
        description = "Additional directories to allow tool access to (repeatable)")
    // Picocli populates option fields reflectively, so static analysis cannot
    // observe the collection update performed during argument parsing.
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private final List<String> additionalDirectoriesCli = new ArrayList<>();

    @Option(names = {"--plugin-dir"}, arity = "1",
        description = "Load plugins from a directory for this session only "
            + "(repeatable: --plugin-dir A --plugin-dir B)")
    List<String> pluginDirs = new ArrayList<>();

    @Option(names = {"--agent"},
        description = "Agent for the current session (overrides settings)")
    private String agentCli;

    @Option(names = {"--agents"},
        description = "JSON object defining custom agents")
    private String agentsJson;

    @Option(names = {"--dangerously-skip-permissions"}, description = "DANGEROUS: skip all permission prompts (use only in sandboxed environments)")
    private boolean dangerouslySkipPermissions;

    @Option(names = {"--allow-dangerously-skip-permissions"}, hidden = true,
        description = "Allow SDK control to enter bypass-permissions mode")
    private boolean allowDangerouslySkipPermissions;

    // Public Agent SDK transport flags.
    private Integer taskBudget;

    @Option(names = "--task-budget", hidden = true)
    void setTaskBudget(Integer value) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("--task-budget must be a positive integer");
        }
        taskBudget = value;
    }

    @Option(names = "--betas", hidden = true, arity = "1..*", split = ",")
    private final List<String> betas = new ArrayList<>();

    @Option(names = "--debug-file", hidden = true)
    private String debugFile;

    @Option(names = "--include-hook-events", hidden = true)
    private boolean includeHookEvents;

    @Option(names = "--session-mirror", hidden = true)
    private boolean sessionMirror;

    @Option(names = "--plugin-dir-no-mcp", hidden = true)
    private final List<String> pluginDirsNoMcp = new ArrayList<>();

    @Option(names = "--fork-session", hidden = true)
    private boolean forkSession;

    @Option(names = "--resume-session-at", hidden = true)
    private String resumeSessionAt;

    @Option(names = "--rewind-files", hidden = true,
        description = "Restore files to state at the specified user message and exit (requires --resume)")
    private String rewindFiles;

    @Option(names = "--no-session-persistence", hidden = true)
    private boolean noSessionPersistence;

    @Option(names = "--managed-settings", hidden = true)
    private String managedSettings;

    @Option(names = "--thinking-display", hidden = true)
    private String thinkingDisplay;


    @Option(names = {"--agent-teams"}, hidden = true,
        description = "Enable the experimental agent-teams subsystem")
    private boolean agentTeams;

    @Option(names = {"--permission-mode"}, description = "Initial permission mode: default, acceptEdits, plan, dontAsk, bypassPermissions, auto")
    private String permissionModeCli;

    @Option(names = {"--allowedTools", "--allowed-tools"}, arity = "1..*",
        description = "Tools or permission rules to allow, separated by commas or spaces")
    private final List<String> allowedToolsCli = new ArrayList<>();

    @Option(names = {"--disallowedTools", "--disallowed-tools"}, arity = "1..*",
        description = "Tools or permission rules to deny, separated by commas or spaces")
    private final List<String> disallowedToolsCli = new ArrayList<>();

    @Option(names = {"--tools"}, arity = "1..*",
        description = "Base tools exposed to the model (for example: default or Read,Bash)")
    private List<String> baseToolsCli;

    @Option(names = {"--cwd", "-C"}, description = "Working directory (defaults to current shell directory)")
    private String cwdOverride;

    @Option(names = {"--worktree", "-w"}, arity = "0..1",
        description = "Create (or resume) an isolated git worktree and start the session inside it. "
            + "An optional name may follow; a random one is generated otherwise.")
    private String worktreeName;

    @Parameters(index = "0", arity = "0..1", description = "Initial prompt (optional)")
    private String initialPrompt;

    // Visible for testing — allows injecting a custom StreamingClient
    private StreamingClient streamingClientOverride;

    // Visible for testing — verifies CLI composition reaches the main-loop stop hook.
    private AutoDreamEngine autoDreamEngineOverride;

    // Visible for testing — allows injecting non-owning custom I/O.
    private CliOutput outputOverride;
    private CliOutput errorOutputOverride;

    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        var daemonWorker = DaemonWorkerDispatcher.tryRun(
            args, CliOutput.systemInStream(), CliOutput.systemOutStream(),
            CliOutput.systemErrStream());
        if (daemonWorker.isPresent()) {
            System.exit(daemonWorker.getAsInt());
            return;
        }
        // Suppress the macOS Dock icon + app-switcher entry. ImageResizer uses
        // java.awt.Graphics2D / ImageIO to resize pasted images, which normally
        // triggers macOS to promote the Java process to a foreground app the
        // first time AWT initialises (the "Java coffee-cup pops up in the Dock"
        // symptom). apple.awt.UIElement=true tells LaunchServices to treat the
        // process as a background UI element (status-bar-app style) so no Dock
        // icon appears — AWT itself still functions normally. Must be set
// before ANY AWT class loads, so this stays at the top of main.
        // Also skip when explicitly overridden (e.g. dev debugging).
        if (System.getProperty("apple.awt.UIElement") == null) {
            System.setProperty("apple.awt.UIElement", "true");
        }
        int exitCode = commandLine(new ClaudeCodeCli()).execute(args);
        System.exit(exitCode);
    }

    /** Commander accepts repeated scalar/boolean options with the last value winning. */
    static CommandLine commandLine(ClaudeCodeCli cli) {
        return new CommandLine(cli).setOverwrittenOptionsAllowed(true);
    }

    @Override
    public Integer call() {
        Integer invalidStandaloneOperation = validateRewindFilesArguments();
        if (invalidStandaloneOperation != null) return invalidStandaloneOperation;
        // Picocli has parsed the flag by this point; expose it to the same
        // centralized gate used by SendMessage/Team/Agent tools.
        AgentTeamsEnabled.setCliFlag(agentTeams);
        CliLaunchRequest request = snapshotLaunchRequest();
        try (ManagedMcpRuntime mcpRuntime = new McpToolProvider()) {
            return CliSessionAssembler.assembleAndRun(request, mcpRuntime);
        } catch (Exception e) {
            log.error("Fatal error", e);
            errorOutput().println("Error: " + e.getMessage());
            return 1;
        }
    }


    /**
     * Captures every Picocli-populated option and explicit test seam exactly
     * once. Startup code must consume this immutable value rather than the
     * mutable fields Picocli owns during parsing.
     */
    CliLaunchRequest snapshotLaunchRequest() {
        CliLaunchRequest.OutputOptions output = new CliLaunchRequest.OutputOptions(
            outputFormat, inputFormat, includePartialMessages, includeHookEvents,
            replayUserMessages, sessionMirror, permissionPromptToolName, debugFile,
            noInteractive, verbose, debug, printMode);
        CliLaunchRequest.SessionOptions session = new CliLaunchRequest.SessionOptions(
            continueLastSession, resumeSession, sessionId, sessionName, theme, initialPrompt,
            forkSession, resumeSessionAt, rewindFiles, noSessionPersistence,
            init, initOnly, maintenance);
        return new CliLaunchRequest(
            new CliLaunchRequest.ModelOptions(
                effort, thinkingMode, thinkingDisplay, maxThinkingTokens, taskBudget, betas,
                fallbackModel, model, apiKey,
                systemPrompt, systemPromptFile, appendSystemPrompt, appendSystemPromptFile,
                planModeInstructions,
                maxTokens, maxTurns, maxBudgetUsd, jsonSchemaRaw, baseUrl),
            output,
            session,
            new CliLaunchRequest.WorkspaceOptions(
                settingsFileOrJson, settingSourcesRaw, mcpConfigCli, strictMcpConfig,
                additionalDirectoriesCli, pluginDirs, pluginDirsNoMcp, managedSettings,
                agentCli, agentsJson, cwdOverride,
                worktreeName, disableSlashCommands),
            new CliLaunchRequest.PermissionOptions(
                dangerouslySkipPermissions, allowDangerouslySkipPermissions,
                permissionModeCli, allowedToolsCli,
                disallowedToolsCli, baseToolsCli),
            new CliLaunchRequest.TestOverrides(
                streamingClientOverride, autoDreamEngineOverride, outputOverride,
                errorOutputOverride),
            CliLaunchRequest.Mode.from(output, session));
    }

    private Integer validateRewindFilesArguments() {
        if (rewindFiles == null) return null;
        if (resumeSession == null) {
            errorOutput().println("Error: --rewind-files requires --resume");
            return 1;
        }
        if (StringUtils.isNotEmpty(initialPrompt)) {
            errorOutput().println(
                "Error: --rewind-files is a standalone operation and cannot be used with a prompt");
            return 1;
        }
        return null;
    }

    // -- Test support methods --

    void setStreamingClientOverride(StreamingClient client) {
        this.streamingClientOverride = client;
    }

    void setAutoDreamEngineOverride(AutoDreamEngine engine) {
        this.autoDreamEngineOverride = engine;
    }

    void setOutputWriter(PrintWriter writer) {
        this.outputOverride = CliOutput.borrowed(writer);
    }

    void setErrorWriter(PrintWriter writer) {
        this.errorOutputOverride = CliOutput.borrowed(writer);
    }

    private CliOutput errorOutput() {
        return errorOutputOverride != null ? errorOutputOverride : CliOutput.systemErr();
    }

    String getModel() { return model; }
    String getApiKey() { return apiKey; }
    String getSystemPrompt() { return systemPrompt; }
    String getAppendSystemPrompt() { return appendSystemPrompt; }
    Integer getMaxTokens() { return maxTokens; }
    int getMaxTurns() { return maxTurns; }
    double getMaxBudgetUsd() { return maxBudgetUsd; }
    String getOutputFormat() { return outputFormat; }
    boolean isNoInteractive() { return noInteractive; }
    boolean isDisableSlashCommands() { return disableSlashCommands; }
    String getSettingSourcesRaw() { return settingSourcesRaw; }
    String getSettingsFileOrJson() { return settingsFileOrJson; }
    String getInitialPrompt() { return initialPrompt; }
}
