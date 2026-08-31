package com.claudecode.cli;

import com.claudecode.runtime.query.QuerySessionEnvironment;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.util.UuidUtils;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.claudemd.MemoryType;
import com.claudecode.services.config.HookSettings;
import com.claudecode.services.config.ManagedEnvironmentApplier;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.config.SettingsParseException;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.services.config.WorkspaceSettings;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.services.model.ModelAllowlist;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.tools.tasks.TaskLifecycleHooks;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TodoStore;
import com.claudecode.tools.worktree.WorktreeException;
import com.claudecode.tools.worktree.WorktreeHooks;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.worktree.WorktreeSession;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.io.TempFilePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Establishes the process-local workspace before clients, tools, or engines are built.
 *
 * <ul>
 *   <li>canonicalizes the
 *       startup cwd and initializes project identity before path-backed state.</li>
 *   <li>
 *
 *       applies source selection, flag settings, and safe/full environment
 *       overlays in bootstrap order.</li>
 *   <li> main-thread-agent bootstrap and
 *        resolves agent defaults and
 *       text input into immutable launch values.</li>
 *   <li> {@code --worktree} setup plus
 *        startup wiring — creates hooks before worktree
 *       creation and preserves its process-wide hook seams.</li>
 * </ul>
 */
final class CliWorkspaceBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CliWorkspaceBootstrap.class);

    private CliWorkspaceBootstrap() {}

    record SettingSourceSelection(boolean user, boolean project, boolean local,
                                  List<RuleSource> orderedSources,
                                  boolean flagBeforePolicy) {
        SettingSourceSelection(boolean user, boolean project, boolean local) {
            this(user, project, local, defaultOrderedSources(user, project, local), true);
        }

        private static List<RuleSource> defaultOrderedSources(
                boolean user, boolean project, boolean local) {
            List<RuleSource> result = new ArrayList<>();
            if (user) result.add(RuleSource.USER_SETTINGS);
            if (project) result.add(RuleSource.PROJECT_SETTINGS);
            if (local) result.add(RuleSource.LOCAL_SETTINGS);
            return List.copyOf(result);
        }

        Set<MemoryType> memoryScopes() {
            Set<MemoryType> out = new HashSet<>();
            if (user) out.add(MemoryType.USER);
            if (project) out.add(MemoryType.PROJECT);
            if (local) out.add(MemoryType.LOCAL);
            return Set.copyOf(out);
        }
    }


    static SettingSourceSelection parseSettingSources(String raw) {
        if (raw == null) return new SettingSourceSelection(true, true, true);
        if (raw.isEmpty()) return new SettingSourceSelection(false, false, false, List.of(), false);
        boolean user = false;
        boolean project = false;
        boolean local = false;
        List<RuleSource> ordered = new ArrayList<>();
        for (String token : raw.split(",", -1)) {
            String name = token.trim();
            switch (name) {
                case "user" -> {
                    user = true;
                    if (!ordered.contains(RuleSource.USER_SETTINGS)) ordered.add(RuleSource.USER_SETTINGS);
                }
                case "project" -> {
                    project = true;
                    if (!ordered.contains(RuleSource.PROJECT_SETTINGS)) ordered.add(RuleSource.PROJECT_SETTINGS);
                }
                case "local" -> {
                    local = true;
                    if (!ordered.contains(RuleSource.LOCAL_SETTINGS)) ordered.add(RuleSource.LOCAL_SETTINGS);
                }
                default -> throw new IllegalArgumentException(
                    "Invalid setting source: " + name + ". Valid options are: user, project, local");
            }
        }
        return new SettingSourceSelection(user, project, local, List.copyOf(ordered), false);
    }

    record Workspace(
            CliLaunchRequest request,
            CliResolvedLaunch launch,
            SettingSourceSelection settingSources,
            SessionIdentity sessionIdentity,
            Path launchCwd,
            String cwd,
            List<Path> cliAdditionalDirectories,
            HookEngine hookEngine,
            ConfigLoader configLoader,
            boolean promptNonInteractive,
            boolean sdkCliSession,
            double effectiveMaxBudgetUsd) {
        Workspace {
            cliAdditionalDirectories = List.copyOf(cliAdditionalDirectories);
        }
    }

    static Workspace bootstrap(
            CliLaunchRequest request, SettingSourceSelection settingSources,
            CliOutput errorOutput) {
        CliLaunchRequest.WorkspaceOptions workspace = request.workspace();
        CliLaunchRequest.ModelOptions modelOptions = request.model();
        CliLaunchRequest.OutputOptions output = request.output();

        Path requestedCwd = Path.of(workspace.cwdOverride() != null
                ? workspace.cwdOverride() : System.getProperty("user.dir"))
            .toAbsolutePath().normalize();
        if (!Files.isDirectory(requestedCwd)) {
            errorOutput.println("claude: --cwd path does not exist or is not a directory: " + requestedCwd);
            throw new CliLaunchAbort(1);
        }
        Path cwdPath = CwdState.canonicalizeStartupCwd(requestedCwd);
        System.setProperty("user.dir", cwdPath.toString());
        SessionIdentity sessionIdentity = resolveInitialSessionIdentity(
            request.session(), new SessionManager(cwdPath.toString()), errorOutput);
        SettingsSources.configureAllowedSettingSources(
            settingSources.orderedSources(), cwdPath.toString(), settingSources.flagBeforePolicy());

        PromptOverrides promptOverrides;
        try {
            resolveSettingsOption(workspace.settingsFileOrJson());
            promptOverrides = resolveSystemPromptOptions(modelOptions);
        } catch (IllegalArgumentException e) {
            errorOutput.println(e.getMessage());
            throw new CliLaunchAbort(1);
        }

        // Trusted settings.env is intentionally applied before agent and
        // worktree setup; full config env is delayed until non-interactive
        // mode has been determined below.
        ManagedEnvironmentApplier.applySafeConfigEnvironmentVariables(cwdPath.toString());
        List<Path> cliAdditionalDirectories = resolveAdditionalDirectories(
            workspace.additionalDirectories());
        SettingsSources.setSessionAdditionalDirectories(
            cliAdditionalDirectories.stream().map(Path::toString).toList());

        AgentDefinitionLoader.setCliAgentsProvider(
            () -> AgentDefinitionLoader.parseCliAgents(workspace.agentsJson()));
        var mainThreadAgentDefinition = StringUtils.isBlank(workspace.agent())
            ? null
            : AgentDefinitionLoader.getActive(cwdPath.toString()).stream()
                .filter(agent -> workspace.agent().equals(agent.agentType()))
                .findFirst()
                .orElse(null);
        String selectedSystemPrompt = promptOverrides.systemPrompt();
        String selectedModel = modelOptions.model();
        if (mainThreadAgentDefinition != null) {
            if (selectedSystemPrompt == null && mainThreadAgentDefinition.systemPrompt() != null
                    && !StringUtils.isBlank(mainThreadAgentDefinition.systemPrompt())) {
                selectedSystemPrompt = mainThreadAgentDefinition.systemPrompt();
            }
            if (selectedModel == null && mainThreadAgentDefinition.model() != null
                    && !Strings.CI.equals("inherit", mainThreadAgentDefinition.model())) {
                selectedModel = mainThreadAgentDefinition.model();
            }
        }
        String mainThreadAgentInitialPrompt = mainThreadAgentDefinition != null
            ? mainThreadAgentDefinition.initialPrompt() : null;

        CwdState.setOriginalCwd(cwdPath);
        TaskOutputPaths.configure(sessionIdentity);
        HookEngine hookEngine = createHookEngine(sessionIdentity);

        applyWorktree(workspace.worktreeName(), cwdPath, sessionIdentity, errorOutput);
        if (workspace.worktreeName() != null) {
            // --worktree changes the project root after the initial
            // source-selection setup. Recompute the path deny-list against the
            // active worktree so an explicit --setting-sources selection

            SettingsSources.configureAllowedSettingSources(
                settingSources.orderedSources(), System.getProperty("user.dir"),
                settingSources.flagBeforePolicy());
            // The hook snapshot is intentionally captured before worktree
            // creation so WorktreeCreate can itself be a configured hook. Once

// updateHooksConfigSnapshot and atomically switch the engine to
            // the worktree's effective hooks/policy.
            hookEngine.replaceSettings(HookSettings.loadHooksSettings());
            hookEngine.replaceHttpHookPolicy(HookSettings.loadHttpHookPolicy());
        }

        if (request.permissions().dangerouslySkipPermissions()) {
            log.warn("--dangerously-skip-permissions is active: all permission prompts will be skipped");
        }
        validateOutputOptions(request, errorOutput);

        boolean rewindFilesOperation = request.session().rewindFiles() != null;
        String initialPrompt = resolveInputPrompt(
            output, request.session().initialPrompt(), errorOutput, rewindFilesOperation);
        if (rewindFilesOperation && StringUtils.isNotEmpty(initialPrompt)) {
            errorOutput.println(
                "Error: --rewind-files is a standalone operation and cannot be used with a prompt");
            throw new CliLaunchAbort(1);
        }
        if (!Strings.CS.equals("stream-json", output.inputFormat())) {
            initialPrompt = prependAgentInitialPrompt(mainThreadAgentInitialPrompt, initialPrompt);
        }
        if (output.printMode() && Strings.CS.equals("text", output.inputFormat())
                && (StringUtils.isEmpty(initialPrompt))) {
            errorOutput.println("Error: Input must be provided either through stdin or as a prompt argument when using --print");
            throw new CliLaunchAbort(1);
        }

        boolean promptNonInteractive = output.printMode() || output.noInteractive()
            || Strings.CS.equals("stream-json", output.inputFormat())
            || rewindFilesOperation;

        // that mode visible to the tools module without coupling it to CLI
        // classes.
        System.setProperty("claude.code.nonInteractive", Boolean.toString(promptNonInteractive));
        if (promptNonInteractive) {
            // Worktree creation may have changed user.dir. Full settings.env is
            // intentionally delayed until after that transition, so a headless
            // launch observes the same project tier as its client and tools.
            ManagedEnvironmentApplier.applyConfigEnvironmentVariables();
        }
        ConfigLoader configLoader = new ConfigLoader();
        ModelCatalog.installModelOverrideLookup(modelId -> {
            var overrides = RuntimeSettings.loadEffectiveSetting("modelOverrides");
            if (overrides == null || !overrides.isObject()) return null;
            var value = overrides.get(modelId);
            return value != null && value.isTextual() ? value.asText() : null;
        });
        String environmentModel = configLoader.resolveModel();
        var settingsModelNode = RuntimeSettings.loadEffectiveSetting("model");
        String settingsModel = settingsModelNode != null && settingsModelNode.isTextual()
            ? settingsModelNode.asText() : null;
        String resolvedModel = resolveLaunchModel(selectedModel, environmentModel, settingsModel);
        String modelPreference = resolveLaunchModelPreference(
            selectedModel, environmentModel, settingsModel);
        if ((selectedModel != null
                || (StringUtils.isNotBlank(environmentModel))
                || (StringUtils.isNotBlank(settingsModel)))
                && !ModelAllowlist.isAllowed(resolvedModel)) {
            resolvedModel = ModelNames.defaultMainLoopModel();
            modelPreference = null;
        }
        boolean sdkCliSession = isSdkCliSession(promptNonInteractive, output.inputFormat());
        System.setProperty("claude.code.entrypoint", sdkCliSession ? "sdk-cli" : "cli");

        return new Workspace(
            request,
            new CliResolvedLaunch(request, resolvedModel, modelPreference,
                selectedModel != null, selectedSystemPrompt,
                promptOverrides.appendSystemPrompt(), initialPrompt),
            settingSources, sessionIdentity, cwdPath, System.getProperty("user.dir"),
            cliAdditionalDirectories, hookEngine, configLoader, promptNonInteractive,
            sdkCliSession, effectiveMaxBudgetUsd(modelOptions.maxBudgetUsd()));
    }

    static SessionIdentity resolveInitialSessionIdentity(
            CliLaunchRequest.SessionOptions session,
            SessionManager sessionManager,
            CliOutput errorOutput) {
        String requested = session.sessionId();
        if (StringUtils.isEmpty(requested)) return SessionIdentity.newRandom();
        if ((session.continueLastSession() || session.resumeSession() != null)
                && !session.forkSession()) {
            errorOutput.println("Error: --session-id can only be used with --continue or --resume "
                + "if --fork-session is also specified.");
            throw new CliLaunchAbort(1);
        }
        if (!UuidUtils.isValid(requested)) {
            errorOutput.println("Error: Invalid session ID. Must be a valid UUID.");
            throw new CliLaunchAbort(1);
        }
        if (sessionManager.sessionIdExists(requested)) {
            errorOutput.println("Error: Session ID " + requested + " is already in use.");
            throw new CliLaunchAbort(1);
        }
        return SessionIdentity.of(requested);
    }

    static double effectiveMaxBudgetUsd(double configured) {
        return configured;
    }

    static String resolveLaunchModel(String cliModel, String environmentModel, String settingsModel) {
        if (cliModel != null) {
            return Strings.CS.equals( "default", cliModel) ? ModelNames.defaultMainLoopModel() : cliModel;
        }
        if (StringUtils.isNotBlank(environmentModel)) return environmentModel;
        if (StringUtils.isNotBlank(settingsModel)) return settingsModel;
        return ModelNames.defaultMainLoopModel();
    }

    static String resolveLaunchModelPreference(
            String cliModel, String environmentModel, String settingsModel) {
        if (cliModel != null) {
            return Strings.CS.equals("default", cliModel)
                ? ModelNames.defaultMainLoopModel() : cliModel;
        }
        if (StringUtils.isNotBlank(environmentModel)) return environmentModel;
        if (StringUtils.isNotBlank(settingsModel)) return settingsModel;
        return null;
    }

    static boolean isSdkCliSession(boolean promptNonInteractive, String inputFormat) {
        return promptNonInteractive || Strings.CS.equals("stream-json", inputFormat);
    }

    static String prependAgentInitialPrompt(String agentInitialPrompt, String userPrompt) {
        if (StringUtils.isEmpty(agentInitialPrompt)) return userPrompt;
        if (StringUtils.isEmpty(userPrompt)) return agentInitialPrompt;
        return agentInitialPrompt + "\n\n" + userPrompt;
    }

    private static HookEngine createHookEngine(SessionIdentity sessionIdentity) {
        HooksSettings hooksSettings = loadHooksSettings();
        HookEngine hookEngine = new HookEngine(
            hooksSettings, /* fixedWorkingDirectory */ null, sessionIdentity);
        hookEngine.replaceHttpHookPolicy(HookSettings.loadHttpHookPolicy());
        hookEngine.setBackgroundTasksRunningSupplier(
            () -> !TaskRegistry.global().listBackground().isEmpty());
        WorktreeService.setWorktreeHooks(new WorktreeHooks() {
            @Override public boolean hasCreateHook() { return hookEngine.hasWorktreeCreateHook(); }
            @Override public Optional<String> create(String slug) { return hookEngine.dispatchWorktreeCreate(slug); }
            @Override public boolean remove(String worktreePath) { return hookEngine.dispatchWorktreeRemove(worktreePath); }
        });
        TodoStore.setTaskLifecycleHooks(new TaskLifecycleHooks() {
            @Override public boolean hasTaskCreatedHook() { return hookEngine.hasTaskCreatedHook(); }
            @Override public List<String> dispatchTaskCreated(String taskId, String subject, String description) {
                return hookEngine.dispatchTaskCreated(taskId, subject, description);
            }
            @Override public List<String> dispatchTaskCreated(
                    String taskId, String subject, String description,
                    String teammateName, String teamName) {
                return hookEngine.dispatchTaskCreated(
                    taskId, subject, description, teammateName, teamName);
            }
            @Override public boolean hasTaskCompletedHook() { return hookEngine.hasTaskCompletedHook(); }
            @Override public List<String> dispatchTaskCompleted(String taskId, String subject, String description) {
                return hookEngine.dispatchTaskCompleted(taskId, subject, description);
            }
            @Override public List<String> dispatchTaskCompleted(
                    String taskId, String subject, String description,
                    String teammateName, String teamName) {
                return hookEngine.dispatchTaskCompleted(
                    taskId, subject, description, teammateName, teamName);
            }
        });
        WorktreeService.setSymlinkDirectoriesSupplier(WorkspaceSettings::loadWorktreeSymlinkDirectories);
        WorktreeService.setBaseRefSupplier(WorkspaceSettings::loadWorktreeBaseRef);
        WorktreeService.setSparsePathsSupplier(WorkspaceSettings::loadWorktreeSparsePaths);
        WorktreeService.setPlansDirectoryCacheClearer(() ->
            PlanFiles.configurePlansDirectory(
                WorkspaceSettings.loadPlansDirectory(System.getProperty("user.dir"))));
        return hookEngine;
    }

    private static HooksSettings loadHooksSettings() {
        try {
            return HookSettings.loadHooksSettings();
        } catch (SettingsParseException e) {
            log.warn("Malformed hooks in {} at startup — starting with no hooks: {}",
                e.path(), e.getMessage());
            return HooksSettings.EMPTY;
        }
    }

    private static void applyWorktree(String worktreeName, Path cwdPath,
            SessionIdentity sessionIdentity, CliOutput errorOutput) {
        if (worktreeName == null) return;
        QuerySessionEnvironment.primeGitStatusSnapshot(cwdPath.toString());
        String cwdNow = System.getProperty("user.dir");
        String slug = StringUtils.isBlank(worktreeName) ? generateWorktreeSlug() : worktreeName;
        try {
            WorktreeService.validateWorktreeSlug(slug);
        } catch (IllegalArgumentException e) {
            errorOutput.println("claude: " + e.getMessage());
            throw new CliLaunchAbort(1);
        }
        WorktreeService.WorktreeCreateResult created;
        try {
            created = WorktreeService.createSessionWorktree(slug, cwdNow);
        } catch (WorktreeException e) {
            errorOutput.println("claude: " + e.getMessage());
            throw new CliLaunchAbort(1);
        }
        String anchorCwd = created.hookBased() ? cwdNow : WorktreeService.findCanonicalGitRoot(cwdNow);
        WorktreeSession worktreeSession = new WorktreeSession(
            anchorCwd, created.worktreePath(), slug, created.worktreeBranch(),
            created.originalBranch(), created.originalHeadCommit(), sessionIdentity.get(), null,
            created.hookBased(), 0L, false, /* projectRootMoved */ true);
        WorktreeService.tryClaim(worktreeSession);
        System.setProperty("user.dir", worktreeSession.worktreePath());
        CwdState.setOriginalCwd(Path.of(worktreeSession.worktreePath()));
        WorktreeService.persistWorktreeState(
            new SessionStorage(JsonUtils.getMapper()),
            new SessionManager(worktreeSession.worktreePath()).getSessionFile(sessionIdentity.get()),
            sessionIdentity.get(), worktreeSession);
    }

    private static void validateOutputOptions(
            CliLaunchRequest request, CliOutput errorOutput) {
        CliLaunchRequest.OutputOptions output = request.output();
        if (StringUtils.isNotBlank(request.model().planModeInstructions())
                && !output.printMode()) {
            errorOutput.println("Error: --plan-mode-instructions can only be used with --print mode.");
            throw new CliLaunchAbort(1);
        }
        if (output.includePartialMessages()
                && !(output.printMode() && Strings.CS.equals("stream-json", output.outputFormat()))) {
            errorOutput.println("Error: --include-partial-messages requires --print and --output-format=stream-json.");
            throw new CliLaunchAbort(1);
        }
        if (output.printMode() && Strings.CS.equals("stream-json", output.outputFormat())
                && !output.verbose()) {
            errorOutput.println("Error: When using --print, --output-format=stream-json requires --verbose");
            throw new CliLaunchAbort(1);
        }
        if (Strings.CS.equals("stream-json", output.inputFormat())) {
            if (!Strings.CS.equals("stream-json", output.outputFormat())) {
                errorOutput.println("Error: --input-format=stream-json requires --output-format=stream-json.");
                throw new CliLaunchAbort(1);
            }
            if (!output.verbose()) {
                errorOutput.println("Error: --input-format=stream-json requires --verbose.");
                throw new CliLaunchAbort(1);
            }
        }
        if (output.replayUserMessages() && !Strings.CS.equals("stream-json", output.inputFormat())) {
            errorOutput.println("Error: --replay-user-messages requires both --input-format=stream-json and --output-format=stream-json.");
            throw new CliLaunchAbort(1);
        }
    }

    private static String resolveInputPrompt(CliLaunchRequest.OutputOptions output,
            String initialPrompt, CliOutput errorOutput, boolean standaloneReadsStdin) {
        if ((!output.printMode() && !standaloneReadsStdin)
                || !Strings.CS.equals("text", output.inputFormat())) return initialPrompt;
        try {
            TextInputPromptReader.Result textInput = TextInputPromptReader.resolve(
                initialPrompt, System.in, StdinTtyDetector.isStdinTty(), Duration.ofSeconds(3));
            if (textInput.timedOut()) {
                errorOutput.print("Warning: no stdin data received in 3s, proceeding without it. "
                    + "If piping from a slow command, redirect stdin explicitly: "
                    + "< /dev/null to skip, or wait longer.\n");
            }
            return textInput.prompt();
        } catch (IOException e) {
            errorOutput.println("Error reading stdin: " + e.getMessage());
            throw new CliLaunchAbort(1);
        }
    }

    private static PromptOverrides resolveSystemPromptOptions(CliLaunchRequest.ModelOptions options) {
        String systemPrompt = options.systemPrompt();
        String appendSystemPrompt = options.appendSystemPrompt();
        if (options.systemPromptFile() != null) {
            if (systemPrompt != null) {
                throw new IllegalArgumentException("Error: Cannot use both --system-prompt and --system-prompt-file. "
                    + "Please use only one.");
            }
            systemPrompt = readPromptFile(options.systemPromptFile(), "System prompt");
        }
        if (options.appendSystemPromptFile() != null) {
            if (appendSystemPrompt != null) {
                throw new IllegalArgumentException("Error: Cannot use both --append-system-prompt and "
                    + "--append-system-prompt-file. Please use only one.");
            }
            appendSystemPrompt = readPromptFile(options.appendSystemPromptFile(), "Append system prompt");
        }
        return new PromptOverrides(systemPrompt, appendSystemPrompt);
    }

    static void resolveSettingsOption(String rawOption) {
        if (StringUtils.isEmpty(rawOption)) {
            SettingsSources.clearFlagSettings();
            SandboxManager.setFlagSettingsPath(null);
            return;
        }
        String raw = rawOption.trim();
        Path settingsPath;
        if (Strings.CS.startsWith(raw, "{") && Strings.CS.endsWith(raw, "}")) {
            if (JsonUtils.safeParseJson(raw) == null) {
                throw new IllegalArgumentException("Error: Invalid JSON provided to --settings");
            }
            settingsPath = TempFilePaths.generate("claude-settings", ".json", raw);
            try {
                FileUtils.writeString(settingsPath, raw, StandardCharsets.UTF_8);
                FileUtils.trySetOwnerOnlyPermissions(settingsPath);
            } catch (IOException e) {
                throw new IllegalArgumentException("Error processing settings: " + e.getMessage(), e);
            }
        } else {
            settingsPath = resolveSettingsPath(rawOption);
            if (!Files.exists(settingsPath)) {
                throw new IllegalArgumentException("Error: Settings file not found: " + settingsPath);
            }
            if (!Files.isRegularFile(settingsPath)) {
                throw new IllegalArgumentException("Error processing settings: path is not a regular file: " + settingsPath);
            }
            try {
                Files.readString(settingsPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalArgumentException("Error processing settings: " + e.getMessage(), e);
            }
        }
        try {
            SettingsSources.setFlagSettingsPath(settingsPath);
            SandboxManager.setFlagSettingsPath(settingsPath);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Error processing settings: " + e.getMessage(), e);
        }
    }

    private static Path resolveSettingsPath(String raw) {
        if (Strings.CS.startsWith(raw, "//") || Strings.CS.startsWith(raw, "\\\\")) {
            throw new IllegalArgumentException("Error: Settings file not found: " + raw);
        }
        Path path = Path.of(raw);
        if (!path.isAbsolute()) path = Path.of(System.getProperty("user.dir")).resolve(path);
        path = path.toAbsolutePath().normalize();
        try {
            if (Files.exists(path)) path = path.toRealPath();
        } catch (IOException _) {
            // Preserve normalized fallback for CLI-compatible errors.
        }
        return path;
    }

    private static String readPromptFile(String rawPath, String label) {
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException _) {
            throw new IllegalArgumentException("Error: " + label + " file not found: " + path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Error reading " + label.toLowerCase(Locale.ROOT)
                + " file: " + e.getMessage());
        }
    }

    private static List<Path> resolveAdditionalDirectories(List<String> directories) {
        if (directories.isEmpty()) return List.of();
        List<Path> resolved = new ArrayList<>();
        for (String raw : directories) {
            if (StringUtils.isBlank(raw)) continue;
            Path path = Path.of(raw).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) resolved.add(path);
        }
        return List.copyOf(resolved);
    }

    private static String generateWorktreeSlug() {
        byte[] bytes = new byte[4];
        new SecureRandom().nextBytes(bytes);
        return "session-" + HexFormat.of().formatHex(bytes);
    }

    private record PromptOverrides(String systemPrompt, String appendSystemPrompt) {}
}
