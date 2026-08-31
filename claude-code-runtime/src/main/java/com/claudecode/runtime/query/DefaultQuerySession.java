package com.claudecode.runtime.query;

import com.claudecode.core.engine.*;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.claudecode.core.attachment.AttachmentService;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.runtime.metrics.SessionMetricsTracker;
import com.claudecode.core.prompt.SystemPromptConfig;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.core.prompt.SystemPromptRuntime;
import com.claudecode.core.prompt.SystemPromptService;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.error.ErrorUtils;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Core AI interaction engine.
 */
public class DefaultQuerySession implements QuerySession, QuerySession.Submission,
    QuerySession.Conversation, QuerySession.Configuration, QuerySession.Execution,
    QuerySession.Forks {

    private static final Logger log = LoggerFactory.getLogger(DefaultQuerySession.class);

    private static final String CONTEXT_ACTION_GUIDANCE =
        "When you have enough information to act, act. Do not re-derive facts already "
        + "established in the conversation, re-litigate a decision the user has already "
        + "made, or narrate options you will not pursue. If you are weighing a choice, "
        + "give a recommendation, not an exhaustive survey";

    private final QuerySessionSpec config;
    /** Stateless prompt assembler reused for this engine's session lifetime. */
    private final SystemPromptService systemPromptService;
    private volatile StreamingClient.StreamRequest lastCacheSafeForkRequest;
    private final List<Message> mutableMessages;
    /** Transcript loader (on-disk) for {@link #findUnresolvedToolUse}; null → in-memory only. */
    private final Function<String, List<Message>> transcriptLoader;
    private final AbortController abortController;
    /**
     * Process shutdown and submit-interrupt suppress the synthetic human
     * interruption row even when another cancellation reason won the
     * AbortController's first-write-wins race.
     */
    private final AtomicBoolean softInterruptRequested = new AtomicBoolean();
    // Task 48.20 / 75.1: Structured permission denials (replaces List<String>)
    private volatile List<SDKMessage.PermissionDenial> permissionDenials;
    private final QueryTiming queryTiming;
    private volatile Usage totalUsage;
    private volatile boolean hasHandledOrphanedPermission;
    private final Map<String, String> readFileState;
    /**
     * Session-scoped read-before-write cache — one shared instance handed to every tool call via {@link
     * ToolExecutionContext#fileStateCache}, not per-tool static state.
     */
    private final FileStateCache fileStateCache;
    /**
     * Distinct CLI names invoked via the {@code Bash} tool during this session.
     */
    private final Set<String> bashTools;
    private final Set<String> discoveredSkillNames;
    private final Set<String> loadedNestedMemoryPaths;

    private final Set<String> nestedMemoryAttachmentTriggers;

    private volatile boolean compactionOccurred;
    /** One prior compact interval retained for fullscreen rewind selection, matching 2.1.197. */
    private volatile List<Message> rewindScrollbackMessages = List.of();
    /**
     * Snapshot of the tool names announced to the model on the PREVIOUS turn.
     */
    private volatile List<String> previousTurnTools;
    private final SessionIdentity sessionIdentity;

    private static volatile String gitStatusCache;

    /**
     * Prime the process-level startup snapshot before a caller mutates the repository (for example by
     * creating {@code.claude/worktrees}).
     */
    public static void primeGitStatusSnapshot(String cwd) {
        initialGitStatusSnapshot(cwd);
    }

    /**
     * Returns the process-wide startup git-status snapshot used by both the
     * main loop and sub-agents. A null result means the launch directory is not
     * inside a Git work tree or Git could not be queried.
     */
    public static String initialGitStatusSnapshot(String cwd) {
        if (StringUtils.isBlank(cwd)) return null;
        String cached = gitStatusCache;
        if (cached != null) return cached.isEmpty() ? null : cached;
        synchronized (DefaultQuerySession.class) {
            if (gitStatusCache == null) {
                String computed = buildGitStatus(cwd);
                gitStatusCache = computed != null ? computed : "";
            }
            return gitStatusCache.isEmpty() ? null : gitStatusCache;
        }
    }
    private final MessageCompactor compactService;
    private volatile HookDispatcher hookDispatcher;
    private final Object startupReadinessLock = new Object();
    private final Queue<CompletableFuture<?>> startupBarriers = new ConcurrentLinkedQueue<>();
    private volatile CompletableFuture<HookDispatcher.HookOutcome> pendingSessionStart;
    private volatile CompletableFuture<Void> sealedStartupReadiness;
    private boolean startupReadinessSealed;
    private volatile PermissionAskCallback permissionAskCallback;
    private volatile RefusalFallbackPrompt refusalFallbackPrompt;
    private volatile FileChangeListener fileChangeListener;
    private volatile TranscriptSink transcriptSink;
    /** Optional adapter notification immediately before each main model request starts. */
    private volatile Runnable beforeModelRequestCallback;
/** {@code /rewind} "Restore code" backend — {@code null} when disabled (see {@link QuerySessionSpec#fileHistoryEnabled}). */
    private final FileHistoryManager fileHistoryManager;
    /**
     * The id of the user message that started the turn currently executing tools — set by {@link
     * com.claudecode.runtime.query.QueryLoop} right after the new {@code UserMessage} joins {@link
     * #mutableMessages}, read by {@link ConcurrentToolRunner} when building each {@link
     * ToolExecutionContext} so {@link FileHistoryManager#trackEdit} knows which snapshot a pre-write
     * backup belongs to.
     */
    private volatile String currentTurnMessageId;

    private volatile String modelOverride;
    private volatile String effortOverride;
    private volatile String attributionSkill;
    private volatile String attributionPlugin;

    private volatile String attributionMcpServer;
    private volatile String attributionMcpTool;
    /** Current top-level turn's shared workflow token target/counter. */
    private volatile TurnTokenBudget turnTokenBudget;
    private final SessionMetricsTracker sessionMetrics;

    public DefaultQuerySession(QuerySessionSpec config) {
        this(config, null);
    }

    public DefaultQuerySession(QuerySessionSpec config, MessageCompactor compactService) {
        this.config = config;
        this.systemPromptService = new SystemPromptService();
        this.mutableMessages = new ArrayList<>(
            config.initialMessages() != null ? config.initialMessages() : List.of()
        );
        this.abortController = config.abortController() != null
            ? config.abortController() : new AbortController();
        this.permissionDenials = new CopyOnWriteArrayList<>();
        this.queryTiming = new QueryTiming();
        this.readFileState = new ConcurrentHashMap<>(config.readFileCache());
        this.fileStateCache = config.initialFileStateCache() != null
            ? config.initialFileStateCache() : new FileStateCache();
        this.bashTools = ConcurrentHashMap.newKeySet();
        this.totalUsage = Usage.EMPTY;
        this.hasHandledOrphanedPermission = false;
        this.discoveredSkillNames = ConcurrentHashMap.newKeySet();
        this.loadedNestedMemoryPaths = ConcurrentHashMap.newKeySet();
        this.nestedMemoryAttachmentTriggers = ConcurrentHashMap.newKeySet();
        this.sessionIdentity = config.sessionIdentity();
        this.transcriptLoader = config.transcriptLoader();
        this.messageQueue = config.messageQueue() != null
            ? config.messageQueue() : new MessageQueueManager();
        this.compactService = compactService;
        this.fileHistoryManager = config.initialFileHistoryManager() != null
            ? config.initialFileHistoryManager()
            : config.fileHistoryEnabled()
                ? new FileHistoryManager(sessionIdentity,
                    Path.of(config.workingDirectory()),
                    ClaudePaths.CLAUDE_HOME.resolve("file-history"))
                : null;
        this.turnTokenBudget = TurnTokenBudget.unlimited();
        this.sessionMetrics = new SessionMetricsTracker(
            this.sessionIdentity.get(), () -> this.transcriptSink);
    }

    @Override
    public Submission submission() { return this; }

    @Override
    public Conversation conversation() { return this; }

    @Override
    public Configuration configuration() { return this; }

    @Override
    public Execution execution() { return this; }

    @Override
    public Forks forks() { return this; }

    /**
     * Submits a message and returns a streaming iterator of SDK messages.
     */
    @Override public Iterator<SDKMessage> submitMessage(Object prompt, SubmitOptions options) {
        config.headlessTurnProfiler().startTurn();
        awaitStartupReadiness();
// Reset abort signal before each new query.
        softInterruptRequested.set(false);
        abortController.reset();
        beginPermissionDenialTurn();
        turnTokenBudget = TurnTokenBudget.fromPrompt(prompt);
        QuerySessionSpec config = getConfig();
        SubmitOptions effectiveOptions = options != null ? options : SubmitOptions.DEFAULT;
        if (effectiveOptions.permissionMode() == null) {
            var supplier = config.permissionModeSupplier();
            if (supplier != null) {
                try {
                    var mode = supplier.get();
                    if (mode != null) {
                        effectiveOptions = effectiveOptions.withPermissionMode(mode.wireValue());
                    }
                } catch (Exception _) {
                    // Permission subsystem may be tearing down; preserve the
                    // explicit-null behavior when no live mode can be read.
                }
            }
        }
        String turnModel = StringUtils.isNotBlank(effectiveOptions.modelOverride())
            ? effectiveOptions.modelOverride() : config.model();
        QueryParams params = QueryParams.builder()
            .systemPrompt(fetchSystemPromptParts())
            .model(turnModel)
            .querySource(effectiveOptions.querySource())
            .maxTurns(config.maxTurns())
            .fallbackModel(config.fallbackModel())
            .taskBudget(config.taskBudgetTokens() == null
                ? null : new QueryParams.TaskBudget(config.taskBudgetTokens()))
            .deps(new CallModelAdapter(config.llmClient(), getCompactService()))
            .build();
        return new QueryLoop(this, params, prompt, effectiveOptions);
    }

    /**
     * Submits a structured query via {@link QueryParams} and returns a streaming iterator.
     */
    Iterator<SDKMessage> submitMessage(QueryParams params) {
        config.headlessTurnProfiler().startTurn();
        awaitStartupReadiness();
        softInterruptRequested.set(false);
        abortController.reset();
        beginPermissionDenialTurn();
        turnTokenBudget = TurnTokenBudget.unlimited();
        return new QueryLoop(this, params);
    }

    @Override
    public Iterator<SDKMessage> submitPrepared(PreparedQueryRequest request) {
        QueryParams.Builder builder = QueryParams.builder()
            .messages(request.messages())
            .systemPrompt(request.systemPrompt())
            .model(request.model())
            .fallbackModel(request.fallbackModel())
            .querySource(request.querySource())
            .maxOutputTokensOverride(request.maxOutputTokensOverride())
            .skipCacheWrite(request.skipCacheWrite())
            .canUseTool(request.canUseTool())
            .toolUseContext(request.toolUseContext())
            .deps(new CallModelAdapter(config.llmClient(), null));
        if (request.maxTurns() != null) builder.maxTurns(request.maxTurns());
        return submitMessage(builder.build());
    }

    /**
     * Interrupts the current query with reason "user-cancel".
     */
    @Override public void interrupt() {
        log.warn("[ABORT] engine.interrupt() (reason=user-cancel) on thread '{}'",
            Thread.currentThread().getName(), new Throwable("interrupt() call site"));
        abortController.abort("user-cancel");
    }

    /**
     * Stops the active query without emitting a visible interruption sentinel.
     */
    @Override public void softInterrupt() {
        log.warn("[ABORT] engine.softInterrupt() (reason=interrupt, silent) on thread '{}'",
            Thread.currentThread().getName(), new Throwable("softInterrupt() call site"));
        softInterruptRequested.set(true);
        abortController.abort("interrupt");
    }

    /** Whether the active query must unwind without a visible user sentinel. */
    @Override public boolean isSoftInterruptRequested() {
        return softInterruptRequested.get();
    }

    /**
     * Returns an unmodifiable view of the current message history.
     */
    @Override public List<Message> getMessages() {
        return Collections.unmodifiableList(mutableMessages);
    }

    @Override public List<Message> getMessagesForRewind() {
        synchronized (mutableMessages) {
            if (rewindScrollbackMessages.isEmpty()) return List.copyOf(mutableMessages);
            List<Message> combined = new ArrayList<>(
                rewindScrollbackMessages.size() + mutableMessages.size());
            combined.addAll(rewindScrollbackMessages);
            combined.addAll(mutableMessages);
            return List.copyOf(combined);
        }
    }

    /**
     * Returns the session ID for this engine instance.
     */
    @Override public String getSessionId() {
        return sessionIdentity.get();
    }

    /**
     * Exposes the shared {@link SessionIdentity} so composition roots (CLI/UI
     * wiring) can hand the SAME instance to other components (hook dispatch,
     * skill variable injection, input state) — a single {@code set}/
     * {@link #switchToSession} call then becomes visible to all of them,
     * instead of each needing its own {@code setSessionId} pass-through.
     */
    @Override public SessionIdentity sessionIdentity() {
        return sessionIdentity;
    }

    /**
     * Changes the model at runtime (e.g., via /model command). Null selects
     * the user-facing Default row while requests use the concrete default.
     */
    @Override public void setModel(String model) {
        config.setUserSpecifiedModel(model);
    }

    // ---- Task 6.1: processUserInput ----

    /**
     * Processes user input, detecting slash commands and attachment references.
     *
     * @param rawInput the raw user input string
     * @return processed input with command detection results
     */
    @Override public ProcessedInput processUserInput(String rawInput) {
        if (StringUtils.isEmpty(rawInput)) {
            return ProcessedInput.forQuery("");
        }

        String trimmed = rawInput.trim();

        // Detect slash command prefix
        if (Strings.CS.startsWith(rawInput, "/")) {
            String commandName = extractCommandName(trimmed);
            switch (commandName) {
                case "help" -> {
                    return ProcessedInput.forLocalCommand("Available commands: /help, /exit, /clear, /compact, /model, /cost, /config");
                }
                case "exit", "quit" -> {
                    return ProcessedInput.forLocalCommand("Goodbye!");
                }
                case "clear" -> {
                    mutableMessages.clear();
                    return ProcessedInput.forLocalCommand("Conversation cleared.");
                }
                case "cost" -> {
                    double cost = getCostCalculator().calculateCost(totalUsage);
                    return ProcessedInput.forLocalCommand(
                        String.format("Total usage: %d input, %d output tokens. Estimated cost: $%.4f",
                            totalUsage.inputTokens(), totalUsage.outputTokens(), cost));
                }
                case "model" -> {
                    String arg = trimmed.substring("/model".length()).trim();
                    if (arg.isEmpty()) {
                        return ProcessedInput.forLocalCommand("Current model: " + config.model());
                    }
                    if (!config.isModelAllowed(arg)) {
                        return ProcessedInput.forLocalCommand(
                            "Model '" + arg
                                + "' is not available. Your organization restricts model selection.");
                    }
                    config.setUserSpecifiedModel(arg);
                    return ProcessedInput.forLocalCommand("Model changed to: " + arg);
                }
                default -> {
                    // Unknown slash command — treat as query
                    return ProcessedInput.forQuery(rawInput);
                }
            }
        }

        // Handle attachment references (e.g., @file.txt)
        String processed = processAttachmentReferences(rawInput);
        return ProcessedInput.forQuery(processed);
    }

    private String extractCommandName(String input) {
        // Extract command name after /
        String withoutSlash = input.substring(1);
        int spaceIdx = withoutSlash.indexOf(' ');
        return spaceIdx >= 0 ? withoutSlash.substring(0, spaceIdx).toLowerCase(Locale.ROOT)
                             : withoutSlash.toLowerCase(Locale.ROOT);
    }

    private String processAttachmentReferences(String input) {
        // For now, pass through. Full attachment handling will be in later tasks.
        return input;
    }

    // ---- Task 6.2: fetchSystemPromptParts ----

    /**
     * Constructs the full system prompt from parts.
     */
    @Override public String fetchSystemPromptParts() {
        // CLAUDE.md / memory content deliberately does NOT go into the system

        // message's <system-reminder> (getUserContext → prependUserContext),
// which QueryLoop.buildClaudeMdUserContext matches (it reads
        // the same claudeMdContentSupplier). Keeping it out of `system` also
        // keeps the cached system prefix stable when memory files change
        // mid-session.
        return assembleSystemPrompt(null);
    }

    /**
     * Assembles the system prompt with the given CLAUDE.md payload. Passing
     * {@code null} yields the base prompt without memory injection — used by
     * {@code /context} to attribute memory-file tokens separately from the
     * "System prompt" category (Preserves the compatibility rule where {@code getSystemPrompt} and
     * {@code getMemoryFiles} are independent inputs to
     * {@code analyzeContextUsage}).
     */
    @Override public String assembleSystemPrompt(String claudeMd) {
        // If the caller supplied a pre-assembled prompt (customOverride path),
        // return it verbatim. Otherwise assemble from sections using default
        // config derived from the QuerySessionSpec fields we have.
        String override = config.systemPrompt();
        // Dynamic prompt inputs from the app layer (language / skills / output
        // style / MCP instructions / additional dirs) — fetched fresh per
        // assembly so settings changes take effect on the next turn. Engines
        // wired without a supplier (tests, workers) fall back to defaults.
        SystemPromptRuntime runtime = SystemPromptRuntime.empty();
        if (config.promptRuntimeSupplier() != null) {
            try {
                var supplied = config.promptRuntimeSupplier().get();
                if (supplied != null) runtime = supplied;
            } catch (Exception _) {
                // Prompt enrichment is best-effort — never block the query.
            }
        }
// Live cwd (not config.workingDirectory, which is frozen at QuerySessionSpec
        // construction time) — a mid-session worktree switch (EnterWorktreeTool /
        // ExitWorktreeTool / /resume restore) mutates System.setProperty("user.dir", ...)
        // and env_info_simple needs to reflect it on the very next turn, same reasoning
        // as isWorktree below.
        String liveCwd = System.getProperty("user.dir");
        String gitStatus = includeGitInstructions() ? gitStatusMemoized() : null;
        SystemPromptConfig promptConfig =
            SystemPromptConfig.builder()
                .modelId(config.model())
                .apiProvider(config.llmClient() != null
                    ? config.llmClient().provider() : "firstParty")
                .workingDirectory(liveCwd)
                .isGitRepo(gitStatus != null)
                .enabledTools(config.tools() != null ? new HashSet<>(config.tools()) : Set.of())
                .customOverride(override)
                .claudeMdContent(claudeMd)
                .languagePreference(runtime.languagePreference())
                .hasSkills(runtime.hasSkills())
                .outputStyle(runtime.outputStyle())
                .isNonInteractiveSession(runtime.isNonInteractiveSession())
                .mcpInstructions(runtime.mcpInstructions())
                .additionalWorkingDirectories(runtime.additionalWorkingDirectories())
                .isWorktree(runtime.isWorktree())
                .memoryDir(runtime.memoryDir())
                .simpleSystemPromptModelPatterns(runtime.simpleSystemPromptModelPatterns())
                .build();

        String append = config.appendSystemPrompt();
        boolean hasAppendSystemPrompt = StringUtils.isNotBlank(append);
        StringBuilder sb = new StringBuilder();


        // is supplied, even though the custom body itself replaces defaults.
        // The wire layer recognizes this exact string and splits it into its
        // own cacheable system block.
        sb.append(SystemPromptConstants.cliSyspromptPrefix(
                runtime.isNonInteractiveSession(), hasAppendSystemPrompt))
          .append("\n\n");
        sb.append(systemPromptService.buildSystemPrompt(promptConfig));

        if (hasAppendSystemPrompt) {
            sb.append("\n\n").append(append);
        }



        // each system-context entry as "<key>: <value>" appended after the
        // prompt parts — so the block goes on the wire with a "gitStatus: "
        // key prefix. Memoized process-wide (see gitStatusMemoized).

        // when customSystemPrompt is supplied.  In that mode the caller's
        // prompt replaces the default prompt, so gitStatus must not be appended
        // to the custom block (it would change the caller-provided text and the
        // cacheable wire payload).  Default prompt assembly still receives the
        // cached git context below.
        if (override == null && gitStatus != null && !StringUtils.isBlank(gitStatus)) {
            sb.append("\n\n").append("gitStatus: ").append(gitStatus);
        }

        return sb.toString();
    }

    /**
     * Returns the effective prompt in the same independently countable sections consumed by.
     */
    @Override public List<String> assembleSystemPromptParts(String claudeMd) {
        String override = config.systemPrompt();
        if (StringUtils.isNotBlank(override)) {
            String append = config.appendSystemPrompt();
            List<String> custom = new ArrayList<>();
            custom.add(override);
            if (StringUtils.isNotBlank(append)) custom.add(append);
            return List.copyOf(custom);
        }

        // The service owns the canonical section boundaries. Rebuild only its
        // section list, then recover the query-layer prefix/context entries from
        // the already assembled string so both paths share identical live inputs.
        SystemPromptRuntime runtime = config.promptRuntimeSupplier() != null
            ? valueOrDefault(config.promptRuntimeSupplier(), SystemPromptRuntime.empty())
            : SystemPromptRuntime.empty();
        String liveCwd = System.getProperty("user.dir");
        String gitStatus = includeGitInstructions() ? gitStatusMemoized() : null;
        SystemPromptConfig promptConfig = SystemPromptConfig.builder()
            .modelId(config.model())
            .apiProvider(config.llmClient() != null ? config.llmClient().provider() : "firstParty")
            .workingDirectory(liveCwd)
            .isGitRepo(gitStatus != null)
            .enabledTools(config.tools() != null ? new HashSet<>(config.tools()) : Set.of())
            .claudeMdContent(claudeMd)
            .languagePreference(runtime.languagePreference())
            .hasSkills(runtime.hasSkills())
            .outputStyle(runtime.outputStyle())
            .isNonInteractiveSession(runtime.isNonInteractiveSession())
            .mcpInstructions(runtime.mcpInstructions())
            .additionalWorkingDirectories(runtime.additionalWorkingDirectories())
            .isWorktree(runtime.isWorktree())
            .memoryDir(runtime.memoryDir())
            .simpleSystemPromptModelPatterns(runtime.simpleSystemPromptModelPatterns())
            .build();
        String append = config.appendSystemPrompt();
        List<String> parts = new ArrayList<>();
        String embeddedGuidance = "\n\n" + CONTEXT_ACTION_GUIDANCE;
        systemPromptService.buildSystemPromptParts(promptConfig).stream()
            .filter(part -> !SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY.equals(part))
            .filter(StringUtils::isNotEmpty)
            .forEach(part -> {

                if (Strings.CS.endsWith(part, embeddedGuidance)) {
                    parts.add(part.substring(0, part.length() - embeddedGuidance.length()));
                } else {
                    parts.add(part);
                }
            });

        parts.add(CONTEXT_ACTION_GUIDANCE);
        if (StringUtils.isNotBlank(append)) parts.add(append);
        if (StringUtils.isNotBlank(gitStatus)) parts.add("gitStatus: " + gitStatus);
        return List.copyOf(parts);
    }

    private static <T> T valueOrDefault(Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value != null ? value : fallback;
        } catch (RuntimeException _) {
            return fallback;
        }
    }

    private boolean includeGitInstructions() {
        try {
            return !Boolean.FALSE.equals(config.includeGitInstructionsSupplier().get());
        } catch (RuntimeException _) {
            return true;
        }
    }

    /**
     * Returns the process-level memoized git status, computing it on first use — Preserves
     * compatibility with {@code getGitStatus = memoize(...)}: git subprocesses run once per process,
     * and the block stays identical across turns so the system prompt remains a stable API-cache prefix
     * even while the working tree changes mid-session.
     */
    private String gitStatusMemoized() {
        return initialGitStatusSnapshot(config.gitStatusWorkingDirectory());
    }


    private static String buildGitStatus(String cwd) {
        if (cwd == null) return null;
        File dir = new File(cwd);
        try {
            String insideWorkTree = runGit(dir, "rev-parse", "--is-inside-work-tree");
            if (!Strings.CS.equals("true", insideWorkTree)) return null;

            String branch = runGit(dir, "symbolic-ref", "--short", "HEAD");
            if (branch == null) {
                branch = runGit(dir, "rev-parse", "--abbrev-ref", "HEAD");
            }
            if (branch == null) branch = "HEAD";

            String mainBranch = runGit(dir, "rev-parse", "--abbrev-ref", "origin/HEAD");
            if (mainBranch != null && Strings.CS.startsWith(mainBranch, "origin/")) {
                mainBranch = mainBranch.substring("origin/".length());
            }
            if (Strings.CS.equals("HEAD", mainBranch)) mainBranch = null;
            if (mainBranch == null) mainBranch = "main"; // safe default

            String status = runGit(dir, "--no-optional-locks", "status", "--short");
            if (status == null) status = "";
            final int MAX_STATUS_CHARS = 2000;
            if (status.length() > MAX_STATUS_CHARS) {
                status = status.substring(0, MAX_STATUS_CHARS)
                    + "\n... (truncated because it exceeds 2k characters. If you need more information, run \"git status\" using BashTool)";
            }

            String log      = runGit(dir, "--no-optional-locks", "log", "--oneline", "-n", "5");
            if (log == null) log = "";

            String userName = runGit(dir, "config", "user.name");

            StringBuilder sb = new StringBuilder();
            sb.append("This is the git status at the start of the conversation. Note that this status is a snapshot in time, and will not update during the conversation.");
            sb.append("\n\nCurrent branch: ").append(branch);
            sb.append("\n\nMain branch (you will usually use this for PRs): ").append(mainBranch);
            if (StringUtils.isNotBlank(userName)) {
                sb.append("\n\nGit user: ").append(userName);
            }
            sb.append("\n\nStatus:\n").append(StringUtils.isBlank(status) ? "(clean)" : status);
            sb.append("\n\nRecent commits:\n").append(log);
            return sb.toString();
        } catch (Exception _) {
            return null;
        }
    }

    /** Runs a git command and returns trimmed stdout, or {@code null} on failure / non-zero exit. */
    private static String runGit(File dir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        Collections.addAll(command, args);
        ProcessResult result = ProcessRunner.run(command, dir.toPath(), Duration.ofSeconds(30));
        return result.succeeded() ? result.stdout().trim() : null;
    }

    // ---- Task 6.7: orphanedPermission handling ----

    /**
     * Handles orphaned permission denials from previous sessions.
     * Only runs on the first submission.
     *
     * @return optional context string to inject
     */
    @Override public Optional<String> handleOrphanedPermissions() {
        if (hasHandledOrphanedPermission) {
            return Optional.empty();
        }
        hasHandledOrphanedPermission = true;

        if (permissionDenials.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder("Previous permission denials:\n");
        for (SDKMessage.PermissionDenial denial : permissionDenials) {
            sb.append("- ").append(denial.toolName())
              .append(" (").append(denial.toolUseId()).append(")\n");
        }
        return Optional.of(sb.toString());
    }

    // ---- Package-private accessors for QueryLoop ----

    @Override public QuerySessionSpec getConfig() {
        return config;
    }

    /**
     * Last completed main-thread request prefix that a fire-and-forget fork may
     * reuse without changing the prompt-cache key. The query loop refreshes this
     * only after a natural assistant turn has completed.
     */
    @Override public StreamingClient.StreamRequest getLastCacheSafeForkRequest() {
        return lastCacheSafeForkRequest;
    }

    @Override
    public StreamingClient.StreamRequest buildCacheSharingRequest(
            List<Message> messages, String compactPrompt) {
        List<Message> forkMessages = new ArrayList<>(messages.size() + 1);
        forkMessages.addAll(messages);
        forkMessages.add(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText(compactPrompt)));
        String claudeMdContext = QueryHelpers.buildClaudeMdUserContext(this);
        List<StreamingClient.StreamRequest.RequestMessage> requestMessages =
            QueryHelpers.buildRequestMessages(
                this, forkMessages, claudeMdContext, config.model(), List.of());
        ToolExecutionContext toolPromptContext = QueryHelpers.toolPromptContext(
            this, config.model());
        List<StreamingClient.StreamRequest.ToolDef> toolDefs = ToolSearchGate.isEnabled(config.model())
            ? config.toolExecutor().getToolDefinitions(
                ToolSearchGate.extractDiscoveredToolNames(forkMessages), toolPromptContext)
            : config.toolExecutor().getToolDefinitions(toolPromptContext);
        String effortValue = getEffortOverride() != null
            ? getEffortOverride() : config.effortValue();
        String resolvedEffort = EffortHelpers.resolveAppliedEffort(config.model(), effortValue);
        return new StreamingClient.StreamRequest(
            config.model(), config.maxTokens(), fetchSystemPromptParts(), requestMessages,
            true, toolDefs, null, resolvedEffort, config.fallbackModel(), null, null,
            null, null, config.isThinkingEnabled(), getSessionId(), config.agentId(),
            true, "compact", getAbortController());
    }

    @Override public void setLastCacheSafeForkRequest(StreamingClient.StreamRequest request) {
        this.lastCacheSafeForkRequest = request;
    }

    /**
     * Session-scoped cwd port supplied to foreground Bash executions. Main-thread
     * engines may mutate their live cwd; sub-agent engines retain their isolated
     * configured directory and therefore expose an immutable controller.
     */
    @Override public WorkingDirectoryController workingDirectoryController() {
        return new WorkingDirectoryController() {
            @Override
            public boolean mutable() {
                return config.agentId() == null;
            }

            @Override
            public Path originalDirectory() {
                Path original = CwdState.getOriginalCwd();
                return original != null ? original
                    : Path.of(config.initialWorkingDirectory());
            }

            @Override
            public List<Path> allowedDirectories() {
                List<Path> allowed = new ArrayList<>();
                allowed.add(originalDirectory());
                if (config.promptRuntimeSupplier() != null) {
                    try {
                        SystemPromptRuntime runtime = config.promptRuntimeSupplier().get();
                        if (runtime != null) {
                            for (String directory : runtime.additionalWorkingDirectories()) {
                                if (StringUtils.isNotBlank(directory)) {
                                    allowed.add(Path.of(directory));
                                }
                            }
                        }
                    } catch (RuntimeException _) {
                        // A settings refresh failure must not make Bash unusable.
                    }
                }
                return List.copyOf(allowed);
            }

            @Override
            public void update(Path previous, Path current) {
                if (!mutable() || current == null) return;
                String next = current.toString();
                config.setWorkingDirectory(next);
                System.setProperty("user.dir", next);
                HookDispatcher hooks = hookDispatcher;
                if (hooks != null && previous != null && !previous.equals(current)) {
                    hooks.dispatchCwdChanged(previous.toString(), next);
                }
            }
        };
    }

    /** Starts a fresh SDK timing window for one submitted query. */
    @Override public void resetQueryTiming() {
        queryTiming.reset();
    }

    /** Records the first dispatch to the model for the current query. */
    @Override public void markQueryRequestStarted() {
        Runnable callback = beforeModelRequestCallback;
        if (callback != null) callback.run();
        queryTiming.markRequestStarted();
    }

    /** Records the first decoded streaming event for the current query. */
    @Override public void markQueryStreamEvent() {
        queryTiming.markStreamEvent();
    }

    /** Records the first output content/tool block for the current query. */
    @Override public void markQueryOutput() {
        queryTiming.markOutput();
    }

    @Override public long getQueryTtftMs() {
        return queryTiming.snapshot().ttftMs();
    }

    @Override public long getQueryTtftStreamMs() {
        return queryTiming.snapshot().ttftStreamMs();
    }

    @Override public long getQueryTimeToRequestMs() {
        return queryTiming.snapshot().timeToRequestMs();
    }

    /**
     * Applies a tool's {@link ToolContextModifier} (model/effort/active-skill portion) to the engine so
     * subsequent requests use the override.
     */
    @Override public void applyContextModifier(ToolContextModifier modifier) {
        if (modifier == null) return;
        if (StringUtils.isNotBlank(modifier.model())
                && config.isModelAllowed(modifier.model())) {
            this.modelOverride = modifier.model();
        }
        if (StringUtils.isNotBlank(modifier.effort())) {
            this.effortOverride = modifier.effort();
        }
        if (StringUtils.isNotBlank(modifier.attributionSkill())) {
            this.attributionSkill = modifier.attributionSkill();
            this.attributionPlugin = StringUtils.isBlank(modifier.attributionPlugin())
                ? null : modifier.attributionPlugin();
        }
    }

    /** Effective model override from a skill's contextModifier, or {@code null} if none. */
    @Override public String getModelOverride() {
        return modelOverride;
    }

    /** Effective effort override from a skill's contextModifier, or {@code null} if none. */
    @Override public String getEffortOverride() {
        return effortOverride;
    }


    @Override public String getAttributionSkill() {
        return attributionSkill;
    }

/** Plugin attribution paired with {@link #getAttributionSkill}. */
    @Override public String getAttributionPlugin() {
        return attributionPlugin;
    }

    /** Record the latest MCP invocation for assistant transcript attribution. */
    @Override public void activateMcpAttribution(String serverName, String toolName) {
        if (StringUtils.isBlank(serverName)) return;
        attributionMcpServer = serverName;
        attributionMcpTool = StringUtils.isBlank(toolName) ? null : toolName;
    }

    @Override public String getAttributionMcpServer() {
        return attributionMcpServer;
    }

    @Override public String getAttributionMcpTool() {
        return attributionMcpTool;
    }

    /** Clears MCP attribution after the submitted query that invoked the tool. */
    @Override public void clearMcpAttribution() {
        attributionMcpServer = null;
        attributionMcpTool = null;
    }

    public List<Message> getMutableMessages() {
        return mutableMessages;
    }

    /**
     * Finds the assistant message carrying the given {@code toolUseId}'s tool_use request, provided
     * that request has NOT yet been answered by a {@code tool_result} (i.e.
     */
    @Override public Optional<AssistantMessage> findUnresolvedToolUse(String toolUseId) {

        // reads the on-disk transcript file. Falls back to in-memory so headless/test
        // runs (no session file) stay correct — our in-memory view is kept in sync with
        // the transcript via recordTranscript, so it is at least as fresh as the disk.
        if (transcriptLoader != null) {
            try {
                List<Message> disk = transcriptLoader.apply(getSessionId());
                if (disk != null) {
                    Optional<AssistantMessage> fromDisk = findUnresolvedIn(disk, toolUseId);
                    if (fromDisk.isPresent()) return fromDisk;
                }
            } catch (RuntimeException _) {
                // Fall through to in-memory on any transcript read failure.
            }
        }
        return findUnresolvedIn(mutableMessages, toolUseId);
    }

    /**
     * Scans a message list for the assistant message carrying the given {@code toolUseId} that has not
     * yet been answered by a {@code tool_result}.
     */
    static Optional<AssistantMessage> findUnresolvedIn(List<Message> messages, String toolUseId) {
        AssistantMessage found = null;
        for (Message msg : messages) {
            if (!(msg instanceof AssistantMessage am)) continue;
            AssistantContent ac = am.message();
            if (ac == null || ac.content() == null) continue;
            boolean hasUse = ac.content().stream()
                    .anyMatch(b -> b instanceof ToolUseBlock tu && toolUseId.equals(tu.id()));
            if (hasUse) {
                found = am;
                break;
            }
        }
        if (found == null) return Optional.empty();
        // Already resolved? A tool_result for this id means replaying would be wrong.
        for (Message msg : messages) {
            if (!(msg instanceof UserMessage um)) continue;
            MessageContent mc = um.message();
            if (mc == null || mc.blocks() == null) continue;
            for (ContentBlock b : mc.blocks()) {
                if (b instanceof ToolResultBlock tr && toolUseId.equals(tr.toolUseId())) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(found);
    }

    /**
     * Cross-turn mailbox for async background work (currently only
     * {@link MemoryExtractor}) whose completion arrives after the turn that
     * triggered it has already finished streaming — background extraction
     * runs an actual multi-turn sub-conversation and routinely outlives the
     * triggering turn's {@link com.claudecode.runtime.query.QueryLoop}, whose queue nothing is
     * still polling by the time it completes. Queued here, drained and
     * emitted at the very start of whichever turn happens to run next.
     */
    private final Queue<SystemMessage> pendingNotifications = new ConcurrentLinkedQueue<>();

    /** Fire-and-forget: stash a system message to surface at the start of the next turn. */
    @Override public void queueNotification(SystemMessage message) {
        pendingNotifications.add(message);
    }

    /** Drains and returns all queued notifications (empties the queue). */
    @Override public List<SystemMessage> drainNotifications() {
        List<SystemMessage> drained = new ArrayList<>();
        SystemMessage msg;
        while ((msg = pendingNotifications.poll()) != null) {
            drained.add(msg);
        }
        return drained;
    }

    @Override public AbortController getAbortController() {
        return abortController;
    }

    @Override public Usage getTotalUsage() {
        return totalUsage;
    }

    /**
     * Overwrite the running usage total — used by {@code /resume} to seed the
     * engine with the historical usage summed from the transcript so the
     * status bar and cost display do not reset to zero after a session swap.
     */
    @Override public void setTotalUsage(Usage usage) {
        this.totalUsage = usage != null ? usage : Usage.EMPTY;
    }

    @Override public SessionMetricsSnapshot getSessionMetrics() {
        return sessionMetrics.snapshot();
    }

    @Override public void restoreSessionMetrics(String sessionId,
                                                List<SessionMetricsEvent> events,
                                                List<String> transcriptTurnIds) {
        sessionMetrics.restore(sessionId, events, transcriptTurnIds);
    }

    SessionMetricsTracker sessionMetricsTracker() {
        return sessionMetrics;
    }

    @Override public TurnTokenBudget getTurnTokenBudget() {
        return turnTokenBudget;
    }

    @Override public Set<String> getDiscoveredSkillNames() {
        return discoveredSkillNames;
    }


    @Override public CostCalculator getCostCalculator() {
        return CostCalculator.forModel(config.model());
    }

    @Override public MessageCompactor getCompactService() {
        return compactService;
    }

    /** Returns the currently installed hook dispatcher, or null if hooks are disabled. */
    @Override public HookDispatcher getHookDispatcher() {
        return hookDispatcher;
    }

    /**
     * Installs a hook dispatcher. When set, the iterator will fire
     * USER_PROMPT_SUBMIT, PRE_TOOL_USE, POST_TOOL_USE, and STOP at the
     * appropriate moments. Pass null to disable.
     */
    @Override public void setHookDispatcher(HookDispatcher dispatcher) {
        boolean dispatchSessionStart;
        synchronized (startupReadinessLock) {
            HookDispatcher previous = this.hookDispatcher;
            this.hookDispatcher = dispatcher;
            dispatchSessionStart = dispatcher != null && previous == null;
        }
        if (dispatchSessionStart) {
            // First installation — fire SESSION_START exactly once.
            try {
                applySessionStartOutcome(dispatcher.dispatchSessionStartWithOutcome("startup"));
            } catch (Throwable failure) {
                log.warn("[HOOK] SessionStart hook failed [sessionId={}, dispatch=immediate, "
                        + "failureType={}]",
                    getSessionId(), failure.getClass().getName(),
                    ErrorUtils.redactedForLogging(failure));
            }
        }
    }

    /**
     * Installs hooks while running the one-shot SessionStart dispatch on a
     * virtual thread. The first submitted query joins that work and injects
     * any additional context before request assembly, so only the empty-shell
     * startup path is made non-blocking.
     */
    @Override public void setHookDispatcherDeferred(HookDispatcher dispatcher) {
        if (dispatcher == null) {
            this.hookDispatcher = null;
            return;
        }
        CompletableFuture<HookDispatcher.HookOutcome> sessionStart;
        synchronized (startupReadinessLock) {
            HookDispatcher previous = this.hookDispatcher;
            this.hookDispatcher = dispatcher;
            if (previous != null) return;
            if (startupReadinessSealed) {
                this.hookDispatcher = null;
                throw new IllegalStateException(
                    "startup readiness is sealed; deferred hooks must be installed first");
            }
            sessionStart = new CompletableFuture<>();
            pendingSessionStart = sessionStart;
        }
        Thread.ofVirtual().name("session-start-hook").start(() -> {
            try {
                sessionStart.complete(dispatcher.dispatchSessionStartWithOutcome("startup"));
            } catch (Throwable failure) {
                log.warn("[HOOK] SessionStart hook failed [sessionId={}, dispatch=deferred, "
                        + "failureType={}]",
                    getSessionId(), failure.getClass().getName(),
                    ErrorUtils.redactedForLogging(failure));
                sessionStart.complete(HookDispatcher.HookOutcome.PROCEED);
            }
        });
    }

    /**
     * Registers background startup work whose results must be visible before
     * the first query (for example plugin tool/skill injection).
     */
    @Override public void addStartupBarrier(CompletionStage<?> barrier) {
        if (barrier == null) return;
        synchronized (startupReadinessLock) {
            if (startupReadinessSealed) {
                throw new IllegalStateException("startup readiness is already sealed");
            }
            startupBarriers.add(barrier.toCompletableFuture());
        }
    }

    /**
     * Freezes the startup dependency set exactly once. No wait or client
     * callback runs while the readiness lock is held.
     */
    @Override public CompletionStage<Void> sealStartupReadiness() {
        CompletableFuture<Void> existing = sealedStartupReadiness;
        if (existing != null) return existing;

        List<CompletableFuture<?>> barriers = new ArrayList<>();
        CompletableFuture<HookDispatcher.HookOutcome> sessionStart;
        CompletableFuture<Void> result;
        synchronized (startupReadinessLock) {
            if (sealedStartupReadiness != null) return sealedStartupReadiness;
            startupReadinessSealed = true;
            CompletableFuture<?> barrier;
            while ((barrier = startupBarriers.poll()) != null) {
                barriers.add(barrier);
            }
            sessionStart = pendingSessionStart;
            pendingSessionStart = null;
            result = new CompletableFuture<>();
            sealedStartupReadiness = result;
        }

        List<CompletableFuture<?>> normalized = new ArrayList<>(barriers.size() + 1);
        for (CompletableFuture<?> barrier : barriers) {
            normalized.add(barrier.handle((_, failure) -> {
                if (failure != null) {
                    log.warn("[STARTUP] Startup barrier failed [sessionId={}, failureType={}]",
                        getSessionId(), failure.getClass().getName(),
                        ErrorUtils.redactedForLogging(failure));
                }
                return null;
            }));
        }
        if (sessionStart != null) {
            normalized.add(sessionStart.handle((outcome, failure) -> {
                if (failure == null) {
                    try {
                        applySessionStartOutcome(outcome);
                    } catch (RuntimeException applyFailure) {
                        log.warn("[HOOK] SessionStart outcome application failed "
                                + "[sessionId={}, failureType={}]",
                            getSessionId(), applyFailure.getClass().getName(),
                            ErrorUtils.redactedForLogging(applyFailure));
                        // Hook output must never poison startup readiness.
                    }
                }
                return null;
            }));
        }
        CompletableFuture.allOf(normalized.toArray(CompletableFuture[]::new))
            .whenComplete((_, _) -> result.complete(null));
        return result;
    }

    private void awaitStartupReadiness() {
        try {
            sealStartupReadiness().toCompletableFuture().join();
        } catch (RuntimeException _) {
            // Startup services are best-effort and log their own errors.
        }
    }

    private void applySessionStartOutcome(HookDispatcher.HookOutcome outcome) {
        if (outcome == null) return;
        injectSystemReminder(outcome.additionalContext());
        outcome.specificOutput("SessionStart").ifPresent(output -> {
            JsonNode initial = output.get("initialUserMessage");
            if (initial != null && initial.isTextual()
                    && StringUtils.isNotBlank(initial.asText())) {
                mutableMessages.add(MessageFactory.createUserMessage(initial.asText(), true));
            }
        });
    }

    /** Returns the installed permission ask callback, or null if none. */
    @Override public PermissionAskCallback getPermissionAskCallback() {
        return permissionAskCallback;
    }

    /**
     * Installs a callback for interactive permission prompts (ASK-level decisions).
     * The callback blocks the calling Virtual Thread until the user responds.
     * Pass null to disable (ASK decisions will default to deny).
     */
    @Override public void setPermissionAskCallback(PermissionAskCallback callback) {
        this.permissionAskCallback = callback;
    }

    /** Returns the installed refusal fallback dialog port, or null if none. */
    @Override public RefusalFallbackPrompt getRefusalFallbackPrompt() {
        return refusalFallbackPrompt;
    }

    /**
     * Installs the port a refused turn uses to ask whether it may move to another
     * model. The callback blocks the calling Virtual Thread until the dialog is
     * answered, exactly like {@link #setPermissionAskCallback}.
     *
     * <p>Installing one is what makes the question reachable at all: its presence
     * is the {@code dialogHostAvailable} fact
     * {@code RefusalFallbackDecision.suppression} consults, and a host that
     * installs none switches models silently. Even with one installed the dialog
     * only appears once the user turns {@code switchModelsOnFlag} off, since that
     * setting is consulted first.
     */
    @Override public void setRefusalFallbackPrompt(RefusalFallbackPrompt prompt) {
        this.refusalFallbackPrompt = prompt;
    }

    /**
     * Returns a {@link PermissionAskCallback} that records a {@link SDKMessage.PermissionDenial} into
     * this engine whenever the user ultimately denies, then delegates to {@code delegate}.
     */
    @Override public PermissionAskCallback withDenialRecording(PermissionAskCallback delegate) {
        return delegate == null ? null
            : new DenialRecordingPermissionAskCallback(delegate, this::addPermissionDenial);
    }

    /**
     * Wraps a {@link PermissionAskCallback} to record a denial on any non-allow result.
     */
    private record DenialRecordingPermissionAskCallback(PermissionAskCallback delegate,
                                                        Consumer<SDKMessage.PermissionDenial> recorder) implements
        PermissionAskCallback {

        @Override
        public Result ask(PermissionAskContext context) {
            Result result = delegate.ask(context);
            if (!result.allowed()) {
                recorder.accept(new SDKMessage.PermissionDenial(
                    sdkCompatToolName(context.toolName()), context.toolUseId(),
                    toMap(context.input())));
            }
            return result;
        }
    }


    private static String sdkCompatToolName(String name) {
        return Strings.CS.equals("Agent", name) ? "Task" : name;
    }

    /** Safely converts a tool-input JSON object into the {@code Map} the denial record expects. */
    private static Map<String, Object> toMap(JsonNode input) {
        if (input == null || !input.isObject()) return Map.of();
        try {
            return JsonUtils.getMapper().convertValue(input, MAP_TYPE_REF);
        } catch (Exception _) {
            return Map.of();
        }
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    /** Returns the installed file-change listener, or null if none. */
    @Override public FileChangeListener getFileChangeListener() {
        return fileChangeListener;
    }

    /**
     * Installs a callback fired after a successful Write/Edit tool execution
     * (see {@link ConcurrentToolRunner}). Pass null to disable.
     */
    @Override public void setFileChangeListener(FileChangeListener listener) {
        this.fileChangeListener = listener;
    }

    /** Returns the active transcript sink, or null if persistence is disabled. */
    @Override public TranscriptSink getTranscriptSink() {
        return transcriptSink;
    }

    /** Installs a sink for session transcript persistence. Null disables. */
    @Override public void setTranscriptSink(TranscriptSink sink) {
        this.transcriptSink = sink;
        if (sink != null && mutableMessages.isEmpty()) sessionMetrics.ensureStarted();
    }

    /**
     * Installs an adapter callback fired after request assembly (including typed
     * user/attachment transcript persistence) and immediately before the main
     * model request is dispatched. Null disables the notification.
     */
    @Override public void setBeforeModelRequestCallback(Runnable callback) {
        this.beforeModelRequestCallback = callback;
    }

    /**
     * Callback fired after a successful compact (manual {@code /compact} or auto-compact) so the app
     * layer can re-append session metadata to the JSONL EOF.
     */
    private volatile Runnable postCompactCallback;

    @Override public Runnable getPostCompactCallback() {
        return postCompactCallback;
    }

    @Override public void setPostCompactCallback(Runnable callback) {
        this.postCompactCallback = callback;
    }

    /**
     * UI callback for auto-compact progress events (spinner colour/message overrides).
     */
    private volatile Consumer<CompactProgressEvent> onCompactProgress;

    @Override public Consumer<CompactProgressEvent> getOnCompactProgress() {
        return onCompactProgress;
    }

    @Override public void setOnCompactProgress(Consumer<CompactProgressEvent> callback) {
        this.onCompactProgress = callback;
    }

    // ---- MessageQueueManager (MCP channel + notification injection) ----

    /**
     * Priority queue for injecting messages between query turns.
     */
    private final MessageQueueManager messageQueue;

    /** Returns the session-scoped message queue (never null). */
    @Override public MessageQueueManager getMessageQueue() {
        return messageQueue;
    }

    /**
     * Replaces the message history. Used by /resume to reload a prior session.
     * Existing messages are cleared first.
     */
    @Override public void loadMessages(List<Message> messages) {
        synchronized (mutableMessages) {
            rewindScrollbackMessages = List.of();
            mutableMessages.clear();
            if (messages != null) mutableMessages.addAll(messages);
        }
        lastCacheSafeForkRequest = null;
    }

    /** Replace active messages after a successful full compact while retaining one UI interval. */
    @Override public void loadCompactedMessages(List<Message> messages) {
        synchronized (mutableMessages) {
            replaceCompactedMessages(
                messages,
                MessageConstants.getMessagesAfterCompactBoundary(mutableMessages));
        }
        compactionOccurred = true;
        lastCacheSafeForkRequest = null;
    }

    /** Replace active messages while retaining the exact fullscreen prefix chosen by partial compact. */
    @Override public void loadCompactedMessages(
            List<Message> messages, List<Message> retainedRewindMessages) {
        synchronized (mutableMessages) {
            replaceCompactedMessages(messages, retainedRewindMessages);
        }
        compactionOccurred = true;
        lastCacheSafeForkRequest = null;
    }

    private void replaceCompactedMessages(
            List<Message> messages, List<Message> retainedRewindMessages) {
        rewindScrollbackMessages = retainedRewindMessages == null
            ? List.of() : List.copyOf(retainedRewindMessages);
        mutableMessages.clear();
        if (messages != null) mutableMessages.addAll(messages);
    }


    @Override public String startNewSession() {
        if (hookDispatcher != null) {
            hookDispatcher.clearGoal();
        }
        synchronized (mutableMessages) {
            rewindScrollbackMessages = List.of();
            mutableMessages.clear();
        }
        lastCacheSafeForkRequest = null;
        discoveredSkillNames.clear();
        loadedNestedMemoryPaths.clear();
        nestedMemoryAttachmentTriggers.clear();
        readFileState.clear();
        fileStateCache.clear();
        if (fileHistoryManager != null) {
            fileHistoryManager.clear();
        }
        bashTools.clear();
        permissionDenials.clear();
        hasHandledOrphanedPermission = false;
        compactionOccurred = false;
        previousTurnTools = List.of();
        currentTurnMessageId = null;
        modelOverride = null;
        effortOverride = null;
        attributionSkill = null;
        attributionPlugin = null;
        attributionMcpServer = null;
        attributionMcpTool = null;
        config.restoreRefusalFallbackForSessionTransition();
        String newSessionId = sessionIdentity.regenerate();
        sessionMetrics.startFresh(newSessionId);
        return newSessionId;
    }

    /**
     * Repoints this engine's write-target identity at an already-existing
     * session without touching conversation state — the counterpart to
     * {@link #startNewSession} for {@code /resume} and {@code /branch}.
     * <p>
     * Both commands load their own message list via a separate
     * {@code loadMessages} call; the one thing they were missing is this —
     * without it, {@code sink.record(engine.getSessionId, message)} in
     * {@code QueryLoop} keeps reading the pre-switch id, so every
     * message sent after an in-place resume/branch silently lands in the
     * OLD session's JSONL file instead of the resumed/forked one. Found while
     * reviewing {@code /branch} (2026-07-08) — the same gap existed in both
     * the {@code /resume} picker and {@code /resume <id>} text path.
     *
     * @param existingSessionId id of the session to switch to; must be non-blank
     * @return {@code existingSessionId}, for fluent call sites
     */
    @Override public String switchToSession(String existingSessionId) {
        if (StringUtils.isBlank(existingSessionId)) {
            throw new IllegalArgumentException("existingSessionId must not be blank");
        }
        if (!Strings.CS.equals(sessionIdentity.get(), existingSessionId)) {
            config.restoreRefusalFallbackForSessionTransition();
        }
        sessionIdentity.set(existingSessionId);
        return sessionIdentity.get();
    }

    /**
     * Appends a hook-emitted context string as a {@code <system-reminder>} user message with {@code
     * isMeta=true} — hidden from the visible transcript but seen by the model on its next turn.
     */
    @Override public void injectSystemReminder(String context) {
        if (StringUtils.isBlank(context)) return;
        String wrapped = MessageConstants.wrapInSystemReminder(context);
        UserMessage hookMsg = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofText(wrapped),
            /* isMeta */ true,
            /* isCompactSummary */ false,
            /* toolUseResult */ null,
            MessageOrigin.USER,
            /* parentUuid */ null,
            Instant.now(),
            /* imagePasteIds */ null,
            /* permissionMode */ null,
            sessionIdentity.get(),
            /* sourceToolAssistantUUID */ null
        );
        mutableMessages.add(hookMsg);
    }

    boolean getHasHandledOrphanedPermission() {
        return hasHandledOrphanedPermission;
    }

    @Override public List<SDKMessage.PermissionDenial> getPermissionDenials() {
        return Collections.unmodifiableList(permissionDenials);
    }

    /** Starts a fresh result-scoped denial bucket for one submitted query. */
    private void beginPermissionDenialTurn() {
        permissionDenials = new CopyOnWriteArrayList<>();
    }

    /**
     * Captures the current submitted query's denial bucket. Background Agent
     * children may finish after the parent result was emitted; retaining this
     * captured consumer lets that held result observe the late denial even if a
     * later submit has already installed a new bucket.
     */
    Consumer<SDKMessage.PermissionDenial> permissionDenialRecorder() {
        List<SDKMessage.PermissionDenial> turnBucket = permissionDenials;
        return turnBucket::add;
    }

    // ---- Task 48.1: Permission denial tracking ----

    /**
     * Records a permission denial (surfaced later by {@link #handleOrphanedPermissions}'s "Previous
     * permission denials" report, and emitted in the SDK {@code result}'s {@code permission_denials}).
     */
    @Override public void addPermissionDenial(SDKMessage.PermissionDenial denial) {
        permissionDenials.add(denial);
    }

    // ---- File history (/rewind "Restore code") ----

    /** The session's {@code /rewind} checkpoint backend, or {@code null} when disabled. */
    @Override public FileHistoryManager getFileHistoryManager() {
        return fileHistoryManager;
    }

    // ---- Fast mode state ----

    @Override public String getFastModeState() {
        return config.fastModeController().state(config.model()).wireValue();
    }

    @Override public FastModeController getFastModeController() {
        return config.fastModeController();
    }

    /** @see #currentTurnMessageId */
    String getCurrentTurnMessageId() {
        return currentTurnMessageId;
    }

    /** @see #currentTurnMessageId */
    @Override public void setCurrentTurnMessageId(String messageId) {
        this.currentTurnMessageId = messageId;
    }

    // ---- Task 75.2: FileStateCache accessor ----

    @Override public Map<String, String> getReadFileState() {
        return Collections.unmodifiableMap(readFileState);
    }

    /** The session's shared read-before-write cache — see {@link #fileStateCache}. */
    @Override public FileStateCache getFileStateCache() {
        return fileStateCache;
    }

    /** Session-scoped de-dup set of already-injected nested-memory paths. */
    @Override public Set<String> getLoadedNestedMemoryPaths() {
        return loadedNestedMemoryPaths;
    }

    /** This turn's pending nested-memory trigger paths. */
    @Override public Set<String> getNestedMemoryAttachmentTriggers() {
        return nestedMemoryAttachmentTriggers;
    }

    /** Reset the per-turn trigger set after a request build consumes it. */
    @Override public void clearNestedMemoryAttachmentTriggers() {
        nestedMemoryAttachmentTriggers.clear();
    }

    /** Whether a compaction has occurred this session (drives {@code compaction_reminder}). */
    @Override public boolean hasCompactionOccurred() { return compactionOccurred; }

    /** Reset by the engine when a compaction fires — see {@code runAutoCompactPhase}. */
    @Override public void setCompactionOccurred(boolean occurred) { this.compactionOccurred = occurred; }

    /** Tool names announced on the previous turn (drives {@code deferred_tools_delta}). */
    @Override public List<String> getPreviousTurnTools() { return previousTurnTools; }

    /** Snapshot the current turn's tools so the next turn can diff against them. */
    @Override public void setPreviousTurnTools(List<String> tools) { this.previousTurnTools = tools; }

/**
     * Per-turn attachment orchestrator.
     */
    @Override public AttachmentService getAttachmentService() {
        return config.attachmentService();
    }

    /**
     * Appends a transcript-level message produced outside the normal model
     * stream (for example {@code /goal} sentinels, goal evaluator status, and
     * scheduled-task fire markers).
     * The message joins the in-memory list so resume/status UIs see the same
     * state immediately, and is persisted through the active transcript sink.
     */
    @Override public void appendTranscriptMessage(Message message) {
        if (message == null) return;
        appendInMemoryMessage(message);
        TranscriptSink sink = transcriptSink;
        if (sink != null) {
            try {
                sink.record(getSessionId(), message);
            } catch (Throwable failure) {
                log.warn("[TRANSCRIPT] Transcript sink failed [sessionId={}, messageType={}, "
                        + "messageUuid={}, failureType={}]",
                    getSessionId(), message.type(), message.uuid(), failure.getClass().getName(),
                    ErrorUtils.redactedForLogging(failure));
                // Transcript persistence is best-effort for the live query.
            }
        }
    }

    /**
     * Adds a synthetic message to the live conversation without persisting it yet.
     * SDK control model-switch breadcrumbs use this while stdin is still being
     * decoded, then persist the same rows after the next prompt identity and
     * queue-dequeue entries have been established.
     */
    @Override public void appendInMemoryMessage(Message message) {
        if (message != null) mutableMessages.add(message);
    }

    /** Complete conversation transcript, including typed attachment events. */
    @Override public List<Message> getAttachmentContextMessages() {
        return List.copyOf(mutableMessages);
    }

    /**
     * Bulk-populate the read-file marker map — called on {@code /resume} by
     * {@link com.claudecode.runtime.query.DefaultQuerySession} clients that have
     * extracted the historical Read/Write/Edit paths from the transcript.
     * Existing markers are preserved (last-write-wins is not desired: a
     * fresh in-session Read must not be clobbered by a resumed marker).
     */
    @Override public void putReadFilePaths(Collection<String> absolutePaths) {
        if (absolutePaths == null) return;
        for (String p : absolutePaths) {
            if (p != null) readFileState.putIfAbsent(p, "resumed");
        }
    }

    /** Bash-tool CLI names invoked so far in this session. */
    @Override public Set<String> getBashTools() {
        return Collections.unmodifiableSet(bashTools);
    }

    /** Bulk-populate {@link #bashTools} from a resumed transcript. */
    @Override public void putBashTools(Collection<String> tools) {
        if (tools == null) return;
        for (String t : tools) {
            if (StringUtils.isNotBlank(t)) bashTools.add(t);
        }
    }

    @Override
    public void drainQueuedCommands(Consumer<SDKMessage> emit) {
        QueryHelpers.drainQueuedCommands(this, emit);
    }
}
