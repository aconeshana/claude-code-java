package com.claudecode.runtime.query;

import com.claudecode.core.engine.*;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.attachment.AttachmentService;
import com.claudecode.core.attachment.FeatureFlagRegistry;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.PlanModeExitInfo;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.message.TodoItem;
import com.claudecode.core.message.UsageSnapshot;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.prompt.SystemPromptRuntime;
import com.claudecode.core.queue.MessageQueueManager;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Configuration for the DefaultQuerySession.
 */
public final class QuerySessionSpec {

    private final StreamingClient llmClient;
    private volatile MessageCompactor messageCompactor;
    private volatile String model;
    /** Temporary writer selected by refusal fallback; does not replace the user's model setting. */
    private volatile String mainLoopModelOverride;
    /** Restores the pre-fallback writer when the conversation changes, unless rewind replaced it. */
    private volatile RefusalFallbackLatch refusalFallbackLatch;
    /**
     * Optional live resolver for the non-pinned main-loop model.
     */
    private final Supplier<String> dynamicModelSupplier;
    /** Nullable user-facing model setting; null is the explicit/default row. */
    private volatile String modelPreference;

    private final Supplier<String> dynamicModelPreferenceSupplier;
    /** Optional composition-root supplied organization model allowlist. */
    private final Predicate<String> modelAllowed;
    /** User-model catalogue membership used for safe capability defaults. */
    @Explanation("Distinguishes user-defined endpoints from first-party model defaults")
    private final Predicate<String> customModel;
    private volatile boolean userSpecifiedModelOverride;
    private final String systemPrompt;
    /** Optional text appended after the default or custom system prompt. */
    private final String appendSystemPrompt;
    private volatile int maxTokens;
    private final ToIntFunction<String> maxTokensResolver;
    private final boolean maxTokensExplicit;
    private final FeatureFlagRegistry featureFlags;
    private final int maxTurns;
    private final double maxBudgetUsd;
    private final List<Message> initialMessages;
    private final AbortController abortController;
    private final List<String> tools;
    private final Map<String, String> readFileCache;
    /**
     * Optional pre-populated read-before-write cache to seed the new {@link DefaultQuerySession} with,
     * instead of starting from an empty {@link FileStateCache}.
     */
    private final FileStateCache initialFileStateCache;
    /**
     * Whether the {@code /rewind} "Restore code" checkpoint subsystem is active for this engine.
     */
    private final boolean fileHistoryEnabled;
    /**
     * Optional pre-built {@link FileHistoryManager} to seed the new
     * {@link DefaultQuerySession} with — used by {@code /resume} once the snapshot
     * chain has been reconstructed from the transcript, so the resumed
     * engine doesn't start with empty file-history state. Same inversion as
     * {@link #initialFileStateCache}. {@code null} = construct a fresh one
     * (or none, if {@link #fileHistoryEnabled} is false).
     */
    private final FileHistoryManager initialFileHistoryManager;
    private final ToolExecutor toolExecutor;
    /** Session-live cwd; foreground Bash may update it after a successful {@code cd}. */
    private volatile String workingDirectory;
    private final String initialWorkingDirectory;
    private final List<String> mcpServers;
    /**
     * Optional supplier of the pre-loaded CLAUDE.md tail block, evaluated on
     * every call to {@code fetchSystemPromptParts}. Populated by CLI wiring
     * that runs {@code MemoryFileScanner} + glob-filter each turn so
     * frontmatter-gated files activate/deactivate as cwd changes. {@code null}
     * = fall back to legacy per-path load in {@code SystemPromptService}.
     */
    private final Supplier<String> claudeMdContentSupplier;

    /**
     * Sink for progress updates emitted during tool execution (e.g. sub-agent
     * progress lines, including the 30s AgentSummaryService summary). Defaults
     * to {@link ToolExecutionContext.ProgressSink#NOOP} so headless/test runs
     * stay silent; the CLI wires a UI sink (statusline) for interactive use.
     */
    private final ToolExecutionContext.ProgressSink progressSink;
    /** Per-turn latency profiler; no-op outside explicitly enabled headless sessions. */
    private final HeadlessTurnProfiler headlessTurnProfiler;
    /** Shared Fast Mode preference and cooldown state for this session. */
    private final FastModeController fastModeController;
    /**
     * Shared session-id holder. Defaults to a freshly minted
     * {@link SessionIdentity} when the caller doesn't supply one, so each
     * {@link DefaultQuerySession} keeps its own independent identity unless the
     * composition root explicitly hands it a shared instance (CLI/UI wiring
     * threads the same instance into {@code HookEngine}/{@code InputPanel}/
     * {@code ShellVariableInjector} so a single {@code switchToSession} call
     * is visible to all of them).
     */
    private final SessionIdentity sessionIdentity;

    private final ToolBatchSummarizer toolBatchSummarizer;

    private final String agentId;

    private final int agentDepth;
    /** Fixed maximum-depth snapshot for this sub-agent tree; null for roots. */
    private final Integer subagentMaxDepthSnapshot;
    /**
     * Injected background memory extractor, or {@code null} when the
     * composition root doesn't wire one (feature off — see
     * {@code services.config.RuntimeSettings#loadExtractMemoriesEnabled}).
     * See {@link MemoryExtractor}.
     */
    private final MemoryExtractor memoryExtractor;
    /**
     * Injected background memory-consolidation ("auto-dream") trigger, or {@code null} when the
     * composition root doesn't wire one (feature off — see {@code
     * services.config.RuntimeSettings#isAutoDreamEnabled}).
     */
    private final AutoDreamEngine autoDreamEngine;
    /**
     * Optional shared command queue.
     */
    private final MessageQueueManager messageQueue;


    private final AttachmentService attachmentService;


    private final String criticalSystemReminder;
    /**
     * Per-turn supplier of the active agent pool.
     */
    private final Supplier<List<BuiltInAgentDefinitions.AgentDefinition>> activeAgentsSupplier;
    /**
     * Per-turn supplier of connected MCP server → server-declared {@code instructions}.
     */
    private final Supplier<Map<String, String>> mcpServerInstructionsSupplier;

    /**
     * Resolved output style (e.g. "default"/"concise"/"formal"); feeds {@code
     * output_style}. Null → suppress.
     */
    private final Supplier<String> outputStyleSupplier;
    /** Current agent's todos; feeds {@code todo_reminder}. Null → none. */
    private final Supplier<List<TodoItem>> todosSupplier;
    /** One-time plan-mode-exit signal; feeds {@code plan_mode_exit}. Null → none. */
    private final Supplier<PlanModeExitInfo> planModeExitSupplier;
    /** Dynamic-skill trigger dirs; feeds {@code dynamic_skill}. Null → empty. */
    private final Supplier<Set<String>> dynamicSkillDirTriggersSupplier;
    /** Available skills for the Skill tool; feeds {@code skill_listing}. Null → none. */
    private final Supplier<List<SkillListingEntry>> skillListingSupplier;
    /** {@code (server, uri) -> content} MCP resource reader; feeds {@code
     *  mcp_resource}. Null → no reader. */
    private final BiFunction<String, String, String> mcpResourceReader;
    /** Per-session usage snapshot; feeds {@code token_usage}/{@code budget_usd}/
     *  {@code output_token_usage}. Null → none. */
    private final Supplier<UsageSnapshot> usageSupplier;

    private final Function<String, List<Message>> transcriptLoader;

    /**
     * Whether the local team-memory secret-write guard is active for this engine.
     */
    private final Supplier<Boolean> teamMemoryEnabledSupplier;
    /** Session-live gate for Bash git guidance and initial git status context. */
    private final Supplier<Boolean> includeGitInstructionsSupplier;
    /**
     * Working directory used for the memoized startup git-status snapshot. Anchored to the
     * session's project rather than its shell cwd, and therefore mutable only through
     * {@link #setGitStatusWorkingDirectory} on a project switch.
     */
    private volatile String gitStatusWorkingDirectory;

    /**
     * The resolved {@code settings.sandbox} snapshot supplier. Supplied (not a
     * plain value) so the composition root can re-read layered sandbox settings
     * per engine without rebuilding the config. Null → fall back to
     * {@link SandboxConfig#disabled} (no sandboxing, matching the default-off
     * setting).
     */
    private final Supplier<SandboxConfig> sandboxConfigSupplier;

    /**
     * Supplies the resolved file-read deny-rule glob patterns to exclude from GlobTool results.
     */
    private final Supplier<List<FileReadIgnorePattern>> readDenyIgnorePatternsSupplier;
/**
     * Live current-rule check for changed-file attachment rereads.
     */
    private final Predicate<String> fileReadDeniedPredicate;
    /**
     * Creates/restores the session's plan slug before the first plan-mode user
     * row is sent to the transcript sink. The core loop cannot resolve the
     * configured plans directory itself, so the CLI injects the tools-layer
     * resolver. Null means no eager plan-slug materialization.
     */
    private final Consumer<String> planSlugInitializer;

    private QuerySessionSpec(Builder builder) {
        this.llmClient = builder.llmClient;
        this.model = builder.model;
        this.dynamicModelSupplier = builder.dynamicModelSupplier;
        this.modelPreference = builder.modelPreference;
        this.dynamicModelPreferenceSupplier = builder.dynamicModelPreferenceSupplier;
        this.modelAllowed = builder.modelAllowed;
        this.customModel = builder.customModel;
        this.userSpecifiedModelOverride = false;
        this.systemPrompt = builder.systemPrompt;
        this.appendSystemPrompt = builder.appendSystemPrompt;
        this.maxTokens = builder.maxTokens;
        this.maxTokensResolver = builder.maxTokensResolver;
        this.maxTokensExplicit = builder.maxTokensExplicit;
        this.featureFlags = builder.featureFlags != null
            ? builder.featureFlags : FeatureFlagRegistry.allOff();
        this.maxTurns = builder.maxTurns;
        this.maxBudgetUsd = builder.maxBudgetUsd;
        this.taskBudgetTokens = builder.taskBudgetTokens;
        this.initialMessages = builder.initialMessages != null
            ? List.copyOf(builder.initialMessages) : List.of();
        this.abortController = builder.abortController;
        this.tools = builder.tools != null
            ? List.copyOf(builder.tools) : List.of();
        this.readFileCache = builder.readFileCache != null
            ? Map.copyOf(builder.readFileCache) : Map.of();
        this.initialFileStateCache = builder.initialFileStateCache;
        this.fileHistoryEnabled = builder.fileHistoryEnabled;
        this.initialFileHistoryManager = builder.initialFileHistoryManager;
        this.toolExecutor = builder.toolExecutor != null
            ? builder.toolExecutor : new NoOpToolExecutor();
        this.workingDirectory = builder.workingDirectory != null
            ? builder.workingDirectory : System.getProperty("user.dir");
        this.initialWorkingDirectory = this.workingDirectory;
        this.mcpServers = builder.mcpServers != null
            ? List.copyOf(builder.mcpServers) : List.of();
        this.claudeMdContentSupplier = builder.claudeMdContentSupplier;
        this.promptRuntimeSupplier = builder.promptRuntimeSupplier;
        this.progressSink = builder.progressSink != null
            ? builder.progressSink : ToolExecutionContext.ProgressSink.NOOP;
        this.headlessTurnProfiler = builder.headlessTurnProfiler != null
            ? builder.headlessTurnProfiler : HeadlessTurnProfiler.NOOP;
        this.fastModeController = builder.fastModeController != null
            ? builder.fastModeController
            : new FastModeController(false, false, System::currentTimeMillis);
        this.sessionIdentity = builder.sessionIdentity != null
            ? builder.sessionIdentity : SessionIdentity.newRandom();
        this.toolBatchSummarizer = builder.toolBatchSummarizer;
        this.agentId = builder.agentId;
        this.agentDepth = builder.agentDepth;
        this.subagentMaxDepthSnapshot = builder.subagentMaxDepthSnapshot;
        this.memoryExtractor = builder.memoryExtractor;
        this.autoDreamEngine = builder.autoDreamEngine;
        this.messageQueue = builder.messageQueue;
        this.attachmentService = builder.attachmentService != null
            ? builder.attachmentService : AttachmentService.empty();
        this.criticalSystemReminder = builder.criticalSystemReminder;
        this.activeAgentsSupplier = builder.activeAgentsSupplier != null
            ? builder.activeAgentsSupplier : List::of;
        this.mcpServerInstructionsSupplier = builder.mcpServerInstructionsSupplier != null
            ? builder.mcpServerInstructionsSupplier : Map::of;
        this.outputStyleSupplier = builder.outputStyleSupplier;
        this.todosSupplier = builder.todosSupplier;
        this.planModeExitSupplier = builder.planModeExitSupplier;
        this.dynamicSkillDirTriggersSupplier = builder.dynamicSkillDirTriggersSupplier;
        this.skillListingSupplier = builder.skillListingSupplier;
        this.mcpResourceReader = builder.mcpResourceReader;
        this.usageSupplier = builder.usageSupplier;
        this.transcriptLoader = builder.transcriptLoader;
        this.teamMemoryEnabledSupplier = builder.teamMemoryEnabledSupplier != null
            ? builder.teamMemoryEnabledSupplier : () -> false;
        this.includeGitInstructionsSupplier = builder.includeGitInstructionsSupplier != null
            ? builder.includeGitInstructionsSupplier : () -> true;
        this.gitStatusWorkingDirectory = builder.gitStatusWorkingDirectory != null
            ? builder.gitStatusWorkingDirectory : this.initialWorkingDirectory;
        this.sandboxConfigSupplier = builder.sandboxConfigSupplier != null
            ? builder.sandboxConfigSupplier : SandboxConfig::disabled;
        this.readDenyIgnorePatternsSupplier = builder.readDenyIgnorePatternsSupplier != null
            ? builder.readDenyIgnorePatternsSupplier : List::of;
        this.fileReadDeniedPredicate = builder.fileReadDeniedPredicate != null
            ? builder.fileReadDeniedPredicate : _ -> false;
        this.planSlugInitializer = builder.planSlugInitializer;
        this.dynamicEffortSettingSupplier = builder.dynamicEffortSettingSupplier;
        this.lastObservedEffortSetting = null;
    }

    /**
     * Dynamic system-prompt inputs (language / skills / output style / MCP
     * instructions / additional dirs), gathered fresh per query by the app
     * layer. Null → all defaults ({@link com.claudecode.core.prompt.SystemPromptRuntime#empty}).
     * Same inversion pattern as {@link #claudeMdContentSupplier}.
     */
    private final Supplier<SystemPromptRuntime> promptRuntimeSupplier;

    public Supplier<SystemPromptRuntime> promptRuntimeSupplier() {
        return promptRuntimeSupplier;
    }

    public StreamingClient llmClient() { return llmClient; }
    public MessageCompactor messageCompactor() { return messageCompactor; }

    /** Completes late-bound compactor wiring before the spec is handed to the factory. */
    public QuerySessionSpec attachMessageCompactor(MessageCompactor compactor) {
        this.messageCompactor = compactor;
        return this;
    }

    /**
     * Returns the current main-loop model.
     */
    public String model() {
        refreshDynamicModel();
        return effectiveModel();
    }

    /** Current temporary main-loop writer, separate from the model-picker preference. */
    public String mainLoopModelOverride() {
        return mainLoopModelOverride;
    }

    /**
     * Returns the nullable setting shown by the model picker. A null value is
     * intentionally distinct from {@link #model}: it means the user chose or
     * inherited {@code Default}, even though requests still use a concrete model.
     */
    public String modelPreference() {
        refreshDynamicModel();
        return modelPreference;
    }

    /** Returns whether a user-selected model is allowed; absent predicate means unrestricted. */
    public boolean isModelAllowed(String model) {
        if (modelAllowed == null) return true;
        try {
            return modelAllowed.test(model);
        } catch (RuntimeException _) {
            return false;
        }
    }

    public boolean isCustomModel(String model) {
        if (customModel == null || model == null) return false;
        try {
            return customModel.test(model);
        } catch (RuntimeException _) {
            return false;
        }
    }

    public String systemPrompt() { return systemPrompt; }

    public String appendSystemPrompt() { return appendSystemPrompt; }

    public int maxTokens() { return maxTokens; }

    /** Whether the startup max-token value came from an explicit CLI/config value. */
    public boolean maxTokensExplicit() { return maxTokensExplicit; }

    /** Shared real-feature registry used by query behavior and attachments. */
    public FeatureFlagRegistry featureFlags() { return featureFlags; }

    


    public int maxTurns() { return maxTurns; }

    public double maxBudgetUsd() { return maxBudgetUsd; }

    public List<Message> initialMessages() { return initialMessages; }

    public AbortController abortController() { return abortController; }

    public List<String> tools() { return tools; }

    public Map<String, String> readFileCache() { return readFileCache; }

    public FileStateCache initialFileStateCache() { return initialFileStateCache; }

    public boolean fileHistoryEnabled() { return fileHistoryEnabled; }

    public FileHistoryManager initialFileHistoryManager() { return initialFileHistoryManager; }

    public ToolExecutor toolExecutor() { return toolExecutor; }

    public String workingDirectory() { return workingDirectory; }

    public String initialWorkingDirectory() { return initialWorkingDirectory; }

    public void setWorkingDirectory(String workingDirectory) {
        if (StringUtils.isNotBlank(workingDirectory)) {
            this.workingDirectory = workingDirectory;
        }
    }

    public List<String> mcpServers() { return mcpServers; }

    public Supplier<String> claudeMdContentSupplier() { return claudeMdContentSupplier; }

    public ToolExecutionContext.ProgressSink progressSink() { return progressSink; }

    public HeadlessTurnProfiler headlessTurnProfiler() { return headlessTurnProfiler; }

    public FastModeController fastModeController() { return fastModeController; }

    public SessionIdentity sessionIdentity() { return sessionIdentity; }

    public ToolBatchSummarizer toolBatchSummarizer() { return toolBatchSummarizer; }

    public String agentId() { return agentId; }

    public int agentDepth() { return agentDepth; }

    public Integer subagentMaxDepthSnapshot() { return subagentMaxDepthSnapshot; }

    public MemoryExtractor memoryExtractor() { return memoryExtractor; }

    public AutoDreamEngine autoDreamEngine() { return autoDreamEngine; }

    public MessageQueueManager messageQueue() { return messageQueue; }

    public AttachmentService attachmentService() { return attachmentService; }

    public String criticalSystemReminder() { return criticalSystemReminder; }

    public Supplier<List<BuiltInAgentDefinitions.AgentDefinition>> activeAgentsSupplier() {
        return activeAgentsSupplier;
    }

    public Supplier<Map<String, String>> mcpServerInstructionsSupplier() {
        return mcpServerInstructionsSupplier;
    }

    public Supplier<String> outputStyleSupplier() { return outputStyleSupplier; }

    public Supplier<List<TodoItem>> todosSupplier() { return todosSupplier; }

    public Supplier<PlanModeExitInfo> planModeExitSupplier() { return planModeExitSupplier; }

    public Supplier<Set<String>> dynamicSkillDirTriggersSupplier() { return dynamicSkillDirTriggersSupplier; }

    public Supplier<List<SkillListingEntry>> skillListingSupplier() { return skillListingSupplier; }

    public BiFunction<String, String, String> mcpResourceReader() { return mcpResourceReader; }

    public Supplier<UsageSnapshot> usageSupplier() { return usageSupplier; }

    public Function<String, List<Message>> transcriptLoader() { return transcriptLoader; }

    public Supplier<Boolean> teamMemoryEnabledSupplier() { return teamMemoryEnabledSupplier; }

    public Supplier<Boolean> includeGitInstructionsSupplier() { return includeGitInstructionsSupplier; }

    public String gitStatusWorkingDirectory() { return gitStatusWorkingDirectory; }

    /**
     * Repoints the status anchor after a cross-project resume. Unlike
     * {@link #setWorkingDirectory}, which tracks the shell cwd and therefore moves with a Bash
     * {@code cd}, this follows the session's project identity only.
     */
    public void setGitStatusWorkingDirectory(String directory) {
        if (StringUtils.isNotBlank(directory)) {
            this.gitStatusWorkingDirectory = directory;
        }
    }

    public Supplier<SandboxConfig> sandboxConfigSupplier() { return sandboxConfigSupplier; }

    public Supplier<List<FileReadIgnorePattern>> readDenyIgnorePatternsSupplier() {
        return readDenyIgnorePatternsSupplier;
    }

    public Predicate<String> fileReadDeniedPredicate() { return fileReadDeniedPredicate; }

    public Consumer<String> planSlugInitializer() { return planSlugInitializer; }

    /**
     * Returns a {@link Builder} pre-seeded with this config's <em>cache-safe</em> fork parameters, for
     * engines that fork off the current one (auto-dream memory consolidation, background extraction).
     */
    public Builder cacheSafeForkBuilder() {
        return builder()
            .model(model())
            .systemPrompt(systemPrompt)
            .maxTokens(maxTokens)
            .tools(tools)
            .workingDirectory(workingDirectory)
            .mcpServers(mcpServers)
            .gitStatusWorkingDirectory(gitStatusWorkingDirectory)
            .promptRuntimeSupplier(promptRuntimeSupplier)
            .claudeMdContentSupplier(claudeMdContentSupplier)
            .activeAgentsSupplier(activeAgentsSupplier)
            .mcpServerInstructionsSupplier(mcpServerInstructionsSupplier)
            .outputStyleSupplier(outputStyleSupplier)
            .todosSupplier(todosSupplier)
            .dynamicSkillDirTriggersSupplier(dynamicSkillDirTriggersSupplier)
            .skillListingSupplier(skillListingSupplier)
            .mcpResourceReader(mcpResourceReader)
            .includeGitInstructionsSupplier(includeGitInstructionsSupplier);
    }

    /**
     * Allows the model to be changed at runtime (e.g., via /model command).
     */
    public void setUserSpecifiedModel(String model) {
        userSpecifiedModelOverride = true;
        modelPreference = model;
        mainLoopModelOverride = null;
        refusalFallbackLatch = null;
        applyModel(model != null && isModelAllowed(model)
            ? model : ModelNames.defaultMainLoopModel());
    }

    /**
     * Changes only the effective main-loop writer. Refusal fallback and rewind use this state so
     * the user's raw {@code /model} selection remains available for compatibility decisions.
     */
    public void setMainLoopModelOverride(String modelOverride) {
        mainLoopModelOverride = StringUtils.trimToNull(modelOverride);
        applyMaxTokens(effectiveModel());
    }

    /**
     * Installs a refusal fallback while remembering the writer it temporarily replaced. Repeated
     * fallbacks update the active fallback without losing the original writer.
     */
    synchronized void activateRefusalFallback(String fallbackModel) {
        String normalizedFallback = StringUtils.trimToNull(fallbackModel);
        if (normalizedFallback == null) {
            throw new IllegalArgumentException("fallbackModel must not be blank");
        }
        RefusalFallbackLatch current = refusalFallbackLatch;
        if (current != null && Objects.equals(mainLoopModelOverride, current.fallbackModel())) {
            refusalFallbackLatch = new RefusalFallbackLatch(
                normalizedFallback, current.previousOverride());
        } else {
            refusalFallbackLatch = new RefusalFallbackLatch(
                normalizedFallback, mainLoopModelOverride);
        }
        mainLoopModelOverride = normalizedFallback;
        applyMaxTokens(effectiveModel());
    }

    /**
     * Clears the session-scoped refusal latch and restores its prior writer only while the fallback
     * is still active. A rewind-selected writer intentionally survives the session transition.
     */
    synchronized void restoreRefusalFallbackForSessionTransition() {
        RefusalFallbackLatch current = refusalFallbackLatch;
        refusalFallbackLatch = null;
        if (current == null
                || !Objects.equals(mainLoopModelOverride, current.fallbackModel())) {
            return;
        }
        mainLoopModelOverride = current.previousOverride();
        applyMaxTokens(effectiveModel());
    }

    /**
     * Clears a runtime model override and resumes the live settings/env resolver.
     */
    public void clearUserSpecifiedModelOverride() {
        userSpecifiedModelOverride = false;
        mainLoopModelOverride = null;
        refusalFallbackLatch = null;
        refreshDynamicModel();
        applyMaxTokens(effectiveModel());
    }

    private void refreshDynamicModel() {
        if (dynamicModelSupplier == null || userSpecifiedModelOverride) return;
        if (dynamicModelPreferenceSupplier != null) {
            try {
                modelPreference = dynamicModelPreferenceSupplier.get();
            } catch (RuntimeException _) {
                // Preserve the last good preference snapshot.
            }
        }
        String resolved;
        try {
            resolved = dynamicModelSupplier.get();
        } catch (RuntimeException _) {
            return;
        }
        if (StringUtils.isBlank(resolved) || resolved.equals(model)) return;
        applyModel(resolved);
    }

    private void applyModel(String model) {
        this.model = model;
        applyMaxTokens(effectiveModel());
    }

    private String effectiveModel() {
        return mainLoopModelOverride != null ? mainLoopModelOverride : model;
    }

    private void applyMaxTokens(String model) {
        if (maxTokensResolver != null) {
            int resolvedMaxTokens = maxTokensResolver.applyAsInt(model);
            if (resolvedMaxTokens <= 0) {
                throw new IllegalStateException(
                    "maxTokensResolver returned a non-positive value for model " + model);
            }
            this.maxTokens = resolvedMaxTokens;
        }
    }

    private record RefusalFallbackLatch(String fallbackModel, String previousOverride) {}

    // ── Effort level (mutable, set via /effort) ───────────────────────────
    /**
     * Current effort level — one of {@code "low" | "medium" | "high" | "max"} or {@code null} for unset
     * (model default).
     */
    private volatile String effortValue;
    /**
     * Optional effective settings-level effort reader.
     */
    private final Supplier<String> dynamicEffortSettingSupplier;
    private volatile String lastObservedEffortSetting;


    private volatile Supplier<PermissionModeKind> permissionModeSupplier;
    private volatile Consumer<String> permissionModeRestorer;

    public Supplier<PermissionModeKind> permissionModeSupplier() { return permissionModeSupplier; }

    public void setPermissionModeSupplier(Supplier<PermissionModeKind> supplier) {
        this.permissionModeSupplier = supplier;
    }

    public Consumer<String> permissionModeRestorer() { return permissionModeRestorer; }

    public void setPermissionModeRestorer(Consumer<String> restorer) {
        this.permissionModeRestorer = restorer;
    }

    /** Returns the live session effort, incorporating changed settings. */
    public String effortValue() {
        refreshDynamicEffortSetting();
        return effortValue;
    }

    /**
     * Marks the current persisted settings value as observed without changing the session/CLI effort
     * already seeded by the composition root.
     */
    public void initializeDynamicEffortObservation() {
        if (dynamicEffortSettingSupplier == null) return;
        try {
            lastObservedEffortSetting = dynamicEffortSettingSupplier.get();
        } catch (RuntimeException _) {
            // A transient settings read failure must not prevent session startup.
        }
    }

    private void refreshDynamicEffortSetting() {
        if (dynamicEffortSettingSupplier == null) return;
        String current;
        try {
            current = dynamicEffortSettingSupplier.get();
        } catch (RuntimeException _) {
            return;
        }
        if (Objects.equals(current, lastObservedEffortSetting)) return;
        lastObservedEffortSetting = current;
        // Clearing the settings key preserves a CLI/session effort; a newly

        if (StringUtils.isNotBlank(current)) {
            setEffortValue(current);
        }
    }

    public void setEffortValue(String value) {

        // those the same as "no effort set, use model default".
        if (value == null) { this.effortValue = null; return; }
        String s = value.trim();
        if (s.isEmpty() || Strings.CI.equals("auto", s) || Strings.CI.equals("unset", s)) {
            this.effortValue = null;
        } else {
            this.effortValue = s.toLowerCase(Locale.ROOT);
        }
    }

    // ── Fallback model (mutable, set from --fallback-model / config) ─────
    /**
     * Model to switch to when the API layer signals {@link FallbackTriggeredError} (primary model
     * overloaded).
     */
    private volatile String fallbackModel;
    private final Integer taskBudgetTokens;

    public String fallbackModel() { return fallbackModel; }

    public Integer taskBudgetTokens() { return taskBudgetTokens; }

    public void setFallbackModel(String model) {
        this.fallbackModel = (StringUtils.isBlank(model)) ? null : model;
    }

    // ── Refusal fallback (mutable, sourced from switchModelsOnFlag) ──────
    /**
     * Whether a turn the model's safeguards refused may switch to another model on its own instead of
     * asking first.
     */
    private final AtomicBoolean switchModelsOnFlag = new AtomicBoolean(true);

    public boolean isSwitchModelsOnFlag() { return switchModelsOnFlag.get(); }

    public void setSwitchModelsOnFlag(boolean enabled) {
        switchModelsOnFlag.set(enabled);
    }

    // ── Thinking configuration (mutable, toggled via meta+t / SDK control) ─
    /**
     * Whether extended thinking is enabled.
     */
    private final AtomicBoolean thinkingEnabled = new AtomicBoolean(true);
    private volatile Integer thinkingBudgetTokens;

    public boolean isThinkingEnabled() { return thinkingEnabled.get(); }

    public Integer thinkingBudgetTokens() { return thinkingBudgetTokens; }

    /**
     * Sets the adaptive/default thinking state and clears any legacy explicit budget.
     */
    public void setThinkingEnabled(boolean enabled) {
        thinkingEnabled.set(enabled);
        thinkingBudgetTokens = null;
    }

    /**
     * matches SDK {@code set_max_thinking_tokens}: {@code null} resets to the
     * default enabled mode, zero disables, and a positive value selects legacy
     * budgeted thinking for models that do not use adaptive thinking.
     */
    public void setThinkingBudgetTokens(Integer tokens) {
        if (tokens == null) {
            thinkingEnabled.set(true);
            thinkingBudgetTokens = null;
        } else if (tokens == 0) {
            thinkingEnabled.set(false);
            thinkingBudgetTokens = null;
        } else {
            thinkingEnabled.set(true);
            thinkingBudgetTokens = tokens;
        }
    }

    /** Toggles and returns the new state atomically. */
    public boolean toggleThinking() {
        boolean current;
        do {
            current = thinkingEnabled.get();
        } while (!thinkingEnabled.compareAndSet(current, !current));
        thinkingBudgetTokens = null;
        return !current;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private StreamingClient llmClient;
        private String model = ModelNames.DEFAULT_MAIN_LOOP_MODEL;
        private Supplier<String> dynamicModelSupplier;
        private String modelPreference;
        private Supplier<String> dynamicModelPreferenceSupplier;
        private Predicate<String> modelAllowed;
        private Predicate<String> customModel;
        private Supplier<String> dynamicEffortSettingSupplier;
        private String systemPrompt = "";
        private String appendSystemPrompt;
        private int maxTokens = 16384;
        private ToIntFunction<String> maxTokensResolver;
        private boolean maxTokensExplicit;
        private FeatureFlagRegistry featureFlags = FeatureFlagRegistry.allOff();
        private int maxTurns = 0;
        private double maxBudgetUsd = -1.0;
        private Integer taskBudgetTokens;
        private List<Message> initialMessages;
        private AbortController abortController;
        private List<String> tools;
        private Map<String, String> readFileCache;
        private FileStateCache initialFileStateCache;
        private boolean fileHistoryEnabled;
        private FileHistoryManager initialFileHistoryManager;
        private ToolExecutor toolExecutor;
        private String workingDirectory;
        private List<String> mcpServers;
        private Supplier<String> claudeMdContentSupplier;
        private Supplier<SystemPromptRuntime> promptRuntimeSupplier;
        private ToolExecutionContext.ProgressSink progressSink;
        private HeadlessTurnProfiler headlessTurnProfiler;
        private FastModeController fastModeController;
        private SessionIdentity sessionIdentity;
        private ToolBatchSummarizer toolBatchSummarizer;
        private String agentId;
        private int agentDepth;
        private Integer subagentMaxDepthSnapshot;
        private MemoryExtractor memoryExtractor;
        private AutoDreamEngine autoDreamEngine;
        private MessageQueueManager messageQueue;
        private AttachmentService attachmentService;
        private String criticalSystemReminder;
        private Supplier<List<BuiltInAgentDefinitions.AgentDefinition>> activeAgentsSupplier;
        private Supplier<Map<String, String>> mcpServerInstructionsSupplier;
        private Supplier<String> outputStyleSupplier;
        private Supplier<List<TodoItem>> todosSupplier;
        private Supplier<PlanModeExitInfo> planModeExitSupplier;
        private Supplier<Set<String>> dynamicSkillDirTriggersSupplier;
        private Supplier<List<SkillListingEntry>> skillListingSupplier;
        private BiFunction<String, String, String> mcpResourceReader;
        private Supplier<UsageSnapshot> usageSupplier;
        private Supplier<Boolean> teamMemoryEnabledSupplier;
        private Supplier<Boolean> includeGitInstructionsSupplier;
        private String gitStatusWorkingDirectory;
        private Supplier<SandboxConfig> sandboxConfigSupplier;
        private Supplier<List<FileReadIgnorePattern>> readDenyIgnorePatternsSupplier;
        private Predicate<String> fileReadDeniedPredicate;
        private Consumer<String> planSlugInitializer;

        private Function<String, List<Message>> transcriptLoader;

        private Builder() {}

        public Builder llmClient(StreamingClient llmClient) {
            this.llmClient = llmClient;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Supplies the nullable setting shown by the model picker. */
        public Builder modelPreference(String preference) {
            this.modelPreference = preference;
            return this;
        }

        /**
         * Supplies the live model for sessions without an explicit startup
         * override.  The supplier is evaluated by {@link QuerySessionSpec#model()}
         * and must include the complete env/settings/default precedence chain.
         */
        public Builder dynamicModelSupplier(Supplier<String> supplier) {
            this.dynamicModelSupplier = supplier;
            return this;
        }

        /** Supplies the live nullable env/settings model preference. */
        public Builder dynamicModelPreferenceSupplier(Supplier<String> supplier) {
            this.dynamicModelPreferenceSupplier = supplier;
            return this;
        }

        /** Supplies the settings-backed organization allowlist without a services dependency. */
        public Builder modelAllowed(Predicate<String> predicate) {
            this.modelAllowed = predicate;
            return this;
        }

        /** Supplies membership in Java's user-defined model catalogue. */
        @Explanation("Wires custom endpoint membership into effort resolution")
        public Builder customModel(Predicate<String> predicate) {
            this.customModel = predicate;
            return this;
        }


        public Builder dynamicEffortSettingSupplier(Supplier<String> supplier) {
            this.dynamicEffortSettingSupplier = supplier;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** Appends caller policy text after the default/custom prompt. */
        public Builder appendSystemPrompt(String appendSystemPrompt) {
            this.appendSystemPrompt = appendSystemPrompt;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Supplies the live per-model request limit used after a runtime model
         * switch. Null keeps the historical fixed {@link #maxTokens(int)} value.
         */
        public Builder maxTokensResolver(ToIntFunction<String> resolver) {
            this.maxTokensResolver = resolver;
            return this;
        }

        /** Marks {@code maxTokens} as an explicit CLI/config value. */
        public Builder maxTokensExplicit(boolean explicit) {
            this.maxTokensExplicit = explicit;
            return this;
        }

        /** Injects the same real feature registry used by attachment providers. */
        public Builder featureFlags(FeatureFlagRegistry flags) {
            this.featureFlags = flags;
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder maxBudgetUsd(double maxBudgetUsd) {
            this.maxBudgetUsd = maxBudgetUsd;
            return this;
        }

        public Builder taskBudgetTokens(Integer tokens) {
            this.taskBudgetTokens = tokens;
            return this;
        }

        public Builder initialMessages(List<Message> initialMessages) {
            this.initialMessages = initialMessages;
            return this;
        }

        public Builder abortController(AbortController abortController) {
            this.abortController = abortController;
            return this;
        }

        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        public Builder readFileCache(Map<String, String> readFileCache) {
            this.readFileCache = readFileCache;
            return this;
        }

        public Builder initialFileStateCache(FileStateCache cache) {
            this.initialFileStateCache = cache;
            return this;
        }

        public Builder fileHistoryEnabled(boolean enabled) {
            this.fileHistoryEnabled = enabled;
            return this;
        }

        public Builder initialFileHistoryManager(FileHistoryManager manager) {
            this.initialFileHistoryManager = manager;
            return this;
        }

        public Builder toolExecutor(ToolExecutor toolExecutor) {
            this.toolExecutor = toolExecutor;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder promptRuntimeSupplier(
                Supplier<SystemPromptRuntime> supplier) {
            this.promptRuntimeSupplier = supplier;
            return this;
        }

        public Builder mcpServers(List<String> mcpServers) {
            this.mcpServers = mcpServers;
            return this;
        }

        public Builder claudeMdContentSupplier(Supplier<String> supplier) {
            this.claudeMdContentSupplier = supplier;
            return this;
        }

        public Builder progressSink(ToolExecutionContext.ProgressSink sink) {
            this.progressSink = sink;
            return this;
        }

        public Builder headlessTurnProfiler(HeadlessTurnProfiler profiler) {
            this.headlessTurnProfiler = profiler;
            return this;
        }

        public Builder fastModeController(FastModeController controller) {
            this.fastModeController = controller;
            return this;
        }

        public Builder sessionIdentity(SessionIdentity sessionIdentity) {
            this.sessionIdentity = sessionIdentity;
            return this;
        }

        public Builder toolBatchSummarizer(ToolBatchSummarizer toolBatchSummarizer) {
            this.toolBatchSummarizer = toolBatchSummarizer;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder agentDepth(int agentDepth) {
            this.agentDepth = agentDepth;
            return this;
        }

        public Builder subagentMaxDepthSnapshot(Integer maxDepth) {
            this.subagentMaxDepthSnapshot = maxDepth;
            return this;
        }

        public Builder memoryExtractor(MemoryExtractor memoryExtractor) {
            this.memoryExtractor = memoryExtractor;
            return this;
        }

        public Builder autoDreamEngine(AutoDreamEngine autoDreamEngine) {
            this.autoDreamEngine = autoDreamEngine;
            return this;
        }

        public Builder messageQueue(MessageQueueManager messageQueue) {
            this.messageQueue = messageQueue;
            return this;
        }

        public Builder attachmentService(AttachmentService attachmentService) {
            this.attachmentService = attachmentService;
            return this;
        }

        public Builder criticalSystemReminder(String criticalSystemReminder) {
            this.criticalSystemReminder = criticalSystemReminder;
            return this;
        }

        public Builder activeAgentsSupplier(
                Supplier<List<BuiltInAgentDefinitions.AgentDefinition>> supplier) {
            this.activeAgentsSupplier = supplier;
            return this;
        }

        public Builder mcpServerInstructionsSupplier(
                Supplier<Map<String, String>> supplier) {
            this.mcpServerInstructionsSupplier = supplier;
            return this;
        }

        public Builder outputStyleSupplier(Supplier<String> supplier) {
            this.outputStyleSupplier = supplier;
            return this;
        }

        public Builder todosSupplier(Supplier<List<TodoItem>> supplier) {
            this.todosSupplier = supplier;
            return this;
        }

        public Builder planModeExitSupplier(Supplier<PlanModeExitInfo> supplier) {
            this.planModeExitSupplier = supplier;
            return this;
        }

        public Builder dynamicSkillDirTriggersSupplier(Supplier<Set<String>> supplier) {
            this.dynamicSkillDirTriggersSupplier = supplier;
            return this;
        }

        public Builder skillListingSupplier(Supplier<List<SkillListingEntry>> supplier) {
            this.skillListingSupplier = supplier;
            return this;
        }

        public Builder mcpResourceReader(BiFunction<String, String, String> reader) {
            this.mcpResourceReader = reader;
            return this;
        }

        public Builder usageSupplier(Supplier<UsageSnapshot> supplier) {
            this.usageSupplier = supplier;
            return this;
        }

        public Builder teamMemoryEnabledSupplier(Supplier<Boolean> supplier) {
            this.teamMemoryEnabledSupplier = supplier;
            return this;
        }

        public Builder includeGitInstructionsSupplier(Supplier<Boolean> supplier) {
            this.includeGitInstructionsSupplier = supplier;
            return this;
        }


        public Builder gitStatusWorkingDirectory(String directory) {
            this.gitStatusWorkingDirectory = directory;
            return this;
        }

        public Builder sandboxConfigSupplier(Supplier<SandboxConfig> supplier) {
            this.sandboxConfigSupplier = supplier;
            return this;
        }

        public Builder readDenyIgnorePatternsSupplier(
                Supplier<List<FileReadIgnorePattern>> supplier) {
            this.readDenyIgnorePatternsSupplier = supplier;
            return this;
        }

        public Builder fileReadDeniedPredicate(Predicate<String> predicate) {
            this.fileReadDeniedPredicate = predicate;
            return this;
        }

        public Builder planSlugInitializer(Consumer<String> initializer) {
            this.planSlugInitializer = initializer;
            return this;
        }

        public Builder transcriptLoader(Function<String, List<Message>> loader) {
            this.transcriptLoader = loader;
            return this;
        }

        public QuerySessionSpec build() {
            if (llmClient == null) {
                throw new IllegalStateException("llmClient is required");
            }
            return new QuerySessionSpec(this);
        }
    }
}
