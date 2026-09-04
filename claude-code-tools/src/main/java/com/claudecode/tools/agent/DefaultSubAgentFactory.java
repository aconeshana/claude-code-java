package com.claudecode.tools.agent;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.CostCalculator;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.NoOpToolExecutor;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.query.PreparedQueryRequest;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.runtime.query.QuerySessionEnvironment;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.PostToolUseOutputResult;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.message.*;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.engine.SubAgentLifecycleListener;
import com.claudecode.core.engine.SubAgentProgressSummarizer;
import com.claudecode.core.engine.SubAgentCompactServiceFactory;
import com.claudecode.core.attachment.AttachmentService;
import com.claudecode.core.attachment.FeatureFlag;
import com.claudecode.core.attachment.FeatureFlagRegistry;
import com.claudecode.core.attachment.PlanModeReminderAttachmentProvider;
import com.claudecode.core.attachment.SkillListingAttachmentProvider;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.session.TeamInfo;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.core.prompt.SystemPromptRuntime;
import com.claudecode.core.prompt.EnvInfoSection;
import com.claudecode.core.util.AgentId;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.model.ModelSkillVisibility;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.AgentMetadata;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.tools.worktree.WorktreeSession;
import com.claudecode.tools.skills.Skill;
import com.claudecode.core.prompt.ArgumentSubstitutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.tools.output.SyntheticOutputTool;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.worktree.WorktreeInfo;
import com.claudecode.tools.ToolErrors;

/**
 * Default implementation of SubAgentFactory.
 */
public class DefaultSubAgentFactory implements SubAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubAgentFactory.class);

    private final StreamingClient llmClient;
    private final ToolExecutor toolExecutor;
    private final String workingDirectory;
    private final SubAgentProgressSummarizer agentSummarizer;
    private final SessionIdentity sessionIdentity;
    private final SubAgentLifecycleListener lifecycleListener;
    private final Path claudeHomeOverride;
    private final SubAgentCompactServiceFactory compactFactory;
    private volatile QuerySessionFactory querySessionFactory;
    private volatile Supplier<List<SkillListingEntry>> skillListingSupplier;
    private volatile Supplier<List<Skill>> skillSupplier;
    private volatile Supplier<Boolean> includeGitInstructionsSupplier;
    private volatile Function<Path, String> claudeMdContentLoader;
    private volatile boolean usingThirdPartyServices;
    private volatile Supplier<JsonNode> effectiveSettingsSupplier;
    private volatile SubAgentModelPolicy modelPolicy = SubAgentModelPolicy.permissive();

    @Override
    public boolean supportsFirstModelRequestSignal() {
        return true;
    }

    public DefaultSubAgentFactory(StreamingClient llmClient, ToolExecutor toolExecutor, String workingDirectory) {
        this(llmClient, toolExecutor, workingDirectory, null, null, null, (Path) null, null, null);
    }

    /**
     * Full constructor that also threads the parent session's {@link SessionIdentity} into every
     * sub-agent's {@link QuerySession}.
     */
    public DefaultSubAgentFactory(StreamingClient llmClient, ToolExecutor toolExecutor,
                                  String workingDirectory, SubAgentProgressSummarizer summarizer,
                                  SessionIdentity sessionIdentity) {
        this(llmClient, toolExecutor, workingDirectory, summarizer, sessionIdentity, null, (Path) null, null, null);
    }

    /**
     * Production constructor that also wires a {@link SubAgentLifecycleListener} (core interface) fired
     * once each sub-agent invocation ends — used to release per-agent resources in higher layers (e.g.
     */
    public DefaultSubAgentFactory(StreamingClient llmClient, ToolExecutor toolExecutor,
                                  String workingDirectory, SubAgentProgressSummarizer summarizer,
                                  SessionIdentity sessionIdentity, SubAgentLifecycleListener lifecycleListener) {
        this(llmClient, toolExecutor, workingDirectory, summarizer, sessionIdentity, lifecycleListener, (Path) null, null, null);
    }


    public DefaultSubAgentFactory(StreamingClient llmClient, ToolExecutor toolExecutor,
                                  String workingDirectory, SubAgentProgressSummarizer summarizer,
                                  SessionIdentity sessionIdentity, SubAgentLifecycleListener lifecycleListener,
                                  SubAgentCompactServiceFactory compactFactory) {
        this(llmClient, toolExecutor, workingDirectory, summarizer, sessionIdentity, lifecycleListener, null, compactFactory, null);
    }

    /**
     * Test-only constructor: also overrides the {@code ~/.claude} home
     * directory used to root the sidechain transcript's {@link SessionManager}
     * ({@link com.claudecode.core.config.ClaudePaths#CLAUDE_HOME} is a
     * {@code static final} field computed once per JVM fork from
     * {@code user.home} — {@code System.setProperty} redirection is
     * unreliable if it runs after that class's first load in the same fork,
     * see {@link com.claudecode.session.SessionManager#SessionManager(Path, String)}'s own
     * test-injection point). Pass {@code null} for the real default (every non-test constructor does).
     */
    DefaultSubAgentFactory(StreamingClient llmClient, ToolExecutor toolExecutor,
                           String workingDirectory, SubAgentProgressSummarizer summarizer,
                           SessionIdentity sessionIdentity, SubAgentLifecycleListener lifecycleListener,
                           Path claudeHomeOverride, SubAgentCompactServiceFactory compactFactory) {
        this(llmClient, toolExecutor, workingDirectory, summarizer, sessionIdentity,
            lifecycleListener, claudeHomeOverride, compactFactory, null);
    }

    /** Test-only home override constructor retained for existing package tests. */
    DefaultSubAgentFactory(StreamingClient llmClient, ToolExecutor toolExecutor,
                           String workingDirectory, SubAgentProgressSummarizer summarizer,
                           SessionIdentity sessionIdentity, SubAgentLifecycleListener lifecycleListener,
                           Path claudeHomeOverride, SubAgentCompactServiceFactory compactFactory,
                           Supplier<List<Skill>> skillSupplier) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient is required");
        this.toolExecutor = toolExecutor != null ? toolExecutor : new NoOpToolExecutor();
        this.workingDirectory = workingDirectory != null ? workingDirectory : System.getProperty("user.dir");
        this.agentSummarizer = summarizer;
        this.sessionIdentity = sessionIdentity;
        this.lifecycleListener = lifecycleListener;
        this.claudeHomeOverride = claudeHomeOverride;
        this.compactFactory = compactFactory;
        this.skillListingSupplier = null;
        this.skillSupplier = skillSupplier;
        this.includeGitInstructionsSupplier = () -> true;
        this.claudeMdContentLoader = null;
        this.usingThirdPartyServices = false;
        this.effectiveSettingsSupplier = () -> null;
    }

    /** Injects the process-wide runtime query-session factory. */
    public void setQuerySessionFactory(QuerySessionFactory factory) {
        this.querySessionFactory = Objects.requireNonNull(factory, "querySessionFactory");
    }

    /** Production constructor with the process' live Skill inventory. */
    public DefaultSubAgentFactory(StreamingClient llmClient, ToolExecutor toolExecutor,
                                  String workingDirectory, SubAgentProgressSummarizer summarizer,
                                  SessionIdentity sessionIdentity, SubAgentLifecycleListener lifecycleListener,
                                  SubAgentCompactServiceFactory compactFactory,
                                  Supplier<List<SkillListingEntry>> skillListingSupplier) {
        this(llmClient, toolExecutor, workingDirectory, summarizer, sessionIdentity,
            lifecycleListener, null, compactFactory, null);
        this.skillListingSupplier = skillListingSupplier;
    }

    /** Production constructor with both live skill inventory and full skill bodies. */
    public DefaultSubAgentFactory(StreamingClient llmClient, ToolExecutor toolExecutor,
                                  String workingDirectory, SubAgentProgressSummarizer summarizer,
                                  SessionIdentity sessionIdentity, SubAgentLifecycleListener lifecycleListener,
                                  SubAgentCompactServiceFactory compactFactory,
                                  Supplier<List<SkillListingEntry>> skillListingSupplier,
                                  Supplier<List<Skill>> skillSupplier) {
        this(llmClient, toolExecutor, workingDirectory, summarizer, sessionIdentity,
            lifecycleListener, null, compactFactory, skillSupplier);
        this.skillListingSupplier = skillListingSupplier;
    }

    /** Production constructor with the live Git gate and all-scope memory loader. */
    public DefaultSubAgentFactory(StreamingClient llmClient, ToolExecutor toolExecutor,
                                  String workingDirectory, SubAgentProgressSummarizer summarizer,
                                  SessionIdentity sessionIdentity, SubAgentLifecycleListener lifecycleListener,
                                  SubAgentCompactServiceFactory compactFactory,
                                  Supplier<List<SkillListingEntry>> skillListingSupplier,
                                  Supplier<List<Skill>> skillSupplier,
                                  Supplier<Boolean> includeGitInstructionsSupplier,
                                  Function<Path, String> claudeMdContentLoader) {
        this(llmClient, toolExecutor, workingDirectory, summarizer, sessionIdentity,
            lifecycleListener, compactFactory, skillListingSupplier, skillSupplier);
        this.includeGitInstructionsSupplier = includeGitInstructionsSupplier != null
            ? includeGitInstructionsSupplier : () -> true;
        this.claudeMdContentLoader = claudeMdContentLoader;
    }

    /** Production constructor with the complete guide-agent runtime context. */
    public DefaultSubAgentFactory(StreamingClient llmClient, ToolExecutor toolExecutor,
                                  String workingDirectory, SubAgentProgressSummarizer summarizer,
                                  SessionIdentity sessionIdentity, SubAgentLifecycleListener lifecycleListener,
                                  SubAgentCompactServiceFactory compactFactory,
                                  Supplier<List<SkillListingEntry>> skillListingSupplier,
                                  Supplier<List<Skill>> skillSupplier,
                                  Supplier<Boolean> includeGitInstructionsSupplier,
                                  Function<Path, String> claudeMdContentLoader,
                                  boolean usingThirdPartyServices,
                                  Supplier<JsonNode> effectiveSettingsSupplier) {
        this(llmClient, toolExecutor, workingDirectory, summarizer, sessionIdentity,
            lifecycleListener, compactFactory, skillListingSupplier, skillSupplier,
            includeGitInstructionsSupplier, claudeMdContentLoader);
        this.usingThirdPartyServices = usingThirdPartyServices;
        this.effectiveSettingsSupplier = effectiveSettingsSupplier != null
            ? effectiveSettingsSupplier : () -> null;
    }

    @Override
    public SubAgentResult runSubAgent(SubAgentRequest request) {
        String parentModel = request.parentContext() != null
            ? request.parentContext().currentModel() : null;
        SubAgentModelPolicy.Decision decision;
        try {
            decision = modelPolicy.resolve(request.model(), parentModel);
        } catch (RuntimeException failure) {
            return SubAgentResult.error("Sub-agent model policy failed: " + failure.getMessage());
        }
        if (decision.outcome() == SubAgentModelPolicy.Outcome.REJECT) {
            return SubAgentResult.error(decision.message());
        }
        if (decision.outcome() == SubAgentModelPolicy.Outcome.INHERIT_PARENT) {
            log.warn(decision.message());
        }
        SubAgentRequest effectiveRequest = request.toBuilder().model(decision.model()).build();
        try {
            return executeSubAgent(effectiveRequest);
        } catch (Exception e) {
            log.error("Sub-agent execution failed", e);
            return SubAgentResult.error("Sub-agent failed: " + e.getMessage());
        }
    }

    /** Defense-in-depth guard for every sub-agent entry point, including workflows. */
    public void setModelAvailabilityPredicate(Predicate<String> predicate) {
        Predicate<String> effective = predicate != null ? predicate : _ -> true;
        setSubAgentModelPolicy(new SubAgentModelPolicy() {
            @Override
            public Decision resolve(String requestedModel, String parentModel) {
                String selected = StringUtils.isBlank(requestedModel)
                    || Strings.CI.equals("inherit", requestedModel) ? parentModel : requestedModel;
                String resolved = resolvedSubAgentModel(selected);
                return effective.test(resolved) ? Decision.use(resolved)
                    : Decision.reject(resolved, "Sub-agent model is not available with the current "
                        + "model provider and authentication: " + resolved);
            }

            @Override
            public List<String> advertisedModels() {
                return List.of("sonnet", "opus", "haiku", "fable");
            }
        });
    }

    /** Installs the shared session policy used by Agent and Workflow entry points. */
    public void setSubAgentModelPolicy(SubAgentModelPolicy policy) {
        modelPolicy = policy != null ? policy : SubAgentModelPolicy.permissive();
    }

    /**
     * Flattens the sub-agent's structured progress into the single status string the parent's
     * callback accepts.
     *
     * <p>UI-affordance events are dropped rather than flattened. They carry no displayable text
     * by design, and the parent's callback writes whatever it receives into the task's progress
     * summary — which the coordinator panel, the agents panel, and the teammate tree all render
     * as the task's description, and which nothing ever resets. Letting the "press Ctrl+B"
     * affordance through would replace a backgrounded task's description with it permanently.
     */
    static ToolExecutionContext.ProgressSink flatteningProgressSink(
            SubAgentRequest.ProgressCallback callback) {
        if (callback == null) return ToolExecutionContext.ProgressSink.NOOP;
        return update -> {
            if (update.uiAffordanceOnly()) return;
            callback.onProgress(
                update.message() == null ? "" : update.message(), update.progress());
        };
    }

    /**
     * Builds the sub-engine's config — split out from {@link #executeSubAgent}
     * so tests can assert the {@code sessionIdentity} sharing contract (see
     * this class's Javadoc) without running a full sub-agent turn.
     */
    QuerySessionSpec buildSubEngineConfig(SubAgentRequest request) {
        return buildSubEngineConfig(request, null);
    }

    /**
     * @param cwdOverride when non-null (an {@code isolation: "worktree"} agent
     *   worktree path), the sub-engine runs there instead of the inherited cwd.
     */
    QuerySessionSpec buildSubEngineConfig(SubAgentRequest request, String cwdOverride) {
        return buildSubEngineConfig(request, cwdOverride, null);
    }

    /**
     * @param cwdOverride when non-null (an {@code isolation: "worktree"} agent worktree path), the
     * sub-engine runs there instead of the inherited cwd.
     */
    QuerySessionSpec buildSubEngineConfig(SubAgentRequest request, String cwdOverride, String agentId) {
        return buildSubEngineConfig(request, cwdOverride, agentId, null);
    }

    /**
     * @param progressSink when non-null, threaded onto the sub-engine config so nested tool progress
     * (e.g.
     */
    QuerySessionSpec buildSubEngineConfig(SubAgentRequest request, String cwdOverride,
                                           String agentId, ToolExecutionContext.ProgressSink progressSink) {
        // The public Agent schema carries model aliases (haiku/sonnet/opus),
        // while the Anthropic request must contain the resolved concrete model

        // the sub-query; using the raw alias here made custom agents silently
        // fall back to the parent/main-loop model in the wire adapter.
        String model = resolvedSubAgentModel(request.model());
        double budgetUsd = request.budgetUsd();
        int maxTurns = request.maxTurns() != null ? request.maxTurns() : 0;
        // An empty allow-list means "all tools" for the Agent tool. The query
        // config still needs the effective names for attachment gates (notably
        // Skill listing); derive them from the registry rather than leaving the
        // list empty and accidentally suppressing those attachments.
        String resolvedCwd = cwdOverride != null ? cwdOverride : resolveWorkingDirectory(request);
        SessionIdentity effectiveSessionIdentity = sessionIdentity != null
            ? sessionIdentity : SessionIdentity.newRandom();
        boolean sdkSubAgent = request.async()
            || Boolean.parseBoolean(System.getProperty("claude.code.nonInteractive", "false"));
        List<String> effectiveToolNames = effectiveToolNames(request);
        ToolExecutionContext toolDefinitionContext = toolDefinitionSnapshotContext(
            request, effectiveSessionIdentity.get(), resolvedCwd, model,
            effectiveToolNames, agentId);
        ToolExecutor restrictedExecutor = createRestrictedToolExecutor(
            request, effectiveToolNames, toolDefinitionContext);
        restrictedExecutor.restoreToolResultBudget(
            request.priorMessages() == null ? List.of() : request.priorMessages(),
            request.contentReplacements(),
            effectiveSessionIdentity.get(),
            resolvedCwd, agentId);

        QuerySessionSpec.Builder builder = QuerySessionSpec.builder()
            .llmClient(withFirstModelRequestSignal(
                llmClient, request.beforeFirstModelRequest(),
                request.awaitParentToolResultEmission()))
            .model(model)
            .systemPrompt(buildSystemPrompt(request))

            // criticalSystemReminder_EXPERIMENTAL) — re-injected as a
            // system-reminder by CriticalSystemReminderProvider via the engine's
            // AttachmentContext. Null = no reminder.
            .criticalSystemReminder(request.criticalSystemReminder())
            // Claude 4 sub-agents use the model-family default (32k for
            // Haiku/Sonnet 4, 64k for Opus 4.6), not the old 4096 fallback.
            .maxTokens(defaultSubAgentMaxTokens(model))
            .maxTurns(maxTurns)
            .maxBudgetUsd(budgetUsd)
            .tools(effectiveToolNames)
            .toolExecutor(restrictedExecutor)
            .workingDirectory(resolvedCwd)


            // TaskRegistry.killAgent abort the sub-engine's query loop.
            .abortController(request.abortController() != null ? request.abortController() : new AbortController())

            // read-before-write cache (cloned so the two sessions don't share
            // mutable state); a plain sub-agent always starts with an empty

            // createFileStateCacheWithSizeLimit() for the non-fork path.
            // Fork routing in Java also forces async (AgentTool.buildRequest folds

            // now carry the parent's file cache, rendered system prompt, full
            // conversation prefix and exact tool catalogue through AgentTool;
            // ordinary sub-agents still start with a fresh conversation/cache.
            .initialFileStateCache(request.fork() && request.parentContext() != null
                ? request.parentContext().fileStateCache().copy() : null)
            .sessionIdentity(effectiveSessionIdentity)
            .agentId(agentId)
            .agentDepth(Math.max(0, request.agentDepth()))
            .subagentMaxDepthSnapshot(request.teammate()
                ? null : normalizedMaxDepth(request.subagentMaxDepthSnapshot()))
            .messageQueue(request.parentQueue())

// from an interactive CLI on the CLI prompt profile.
            .promptRuntimeSupplier(() -> new SystemPromptRuntime(
                null,
                skillListingSupplier != null && hasSkills(),
                null,
                sdkSubAgent,
                List.of(), List.of(), false, null, null))

            .attachmentService(new AttachmentService(
                List.of(
                    new SkillListingAttachmentProvider(),
                    new SubAgentListingAttachmentProvider(),
                    new PlanModeReminderAttachmentProvider(
                        () -> request.permissionMode() == null
                            ? PermissionModeKind.DEFAULT
                            : request.permissionMode().kind(),
                        () -> PlanFiles.activatePlan(
                            effectiveSessionIdentity.get(), agentId)),
                    new AgentPendingMessageAttachmentProvider(TaskRegistry.global())),
                FeatureFlagRegistry.builder()
                    .enable(FeatureFlag.AGENT_LISTING_DELTA)
                    .build()))
            .activeAgentsSupplier(() -> {
                try {
                    return AgentDefinitionLoader.getActive(resolvedCwd).stream()
                        .filter(agent -> modelPolicy.resolveAgent(
                            agent, null, request.model()).outcome()
                            != SubAgentModelPolicy.Outcome.REJECT)
                        .toList();
                } catch (Throwable _) {
                    return List.of();
                }
            })
            .skillListingSupplier(() -> skillListingSupplier == null
                ? List.of() : ModelSkillVisibility.filter(safeSkillListing(), request.model()))
// Inherit the parent's team-memory guard setting so a sub-agent is subject to the same
// secret-write block when it writes into the team-memory directory.
            .teamMemoryEnabledSupplier(() ->
                request.parentContext() != null && request.parentContext().teamMemoryEnabled())
// Multi-turn history: seed the sub-engine with the prior conversation.
            .initialMessages(request.priorMessages() != null ? request.priorMessages() : List.of());
        if (claudeMdContentLoader != null) {
            builder.claudeMdContentSupplier(() -> loadClaudeMdContent(Path.of(resolvedCwd)));
        }
        if (progressSink != null) {
            builder.progressSink(progressSink);
        }
        QuerySessionSpec config = builder.build();
        if (request.permissionMode() != null) {
            config.setPermissionModeSupplier(() -> request.permissionMode().kind());
        }

        // this must be reflected as thinking:{type:"disabled"} on the wire.
        config.setThinkingEnabled(false);
        return config;
    }

    private String loadClaudeMdContent(Path cwd) {
        try {
            String content = claudeMdContentLoader.apply(cwd);
            return content != null ? content : "";
        } catch (RuntimeException _) {
            return "";
        }
    }

    private boolean hasSkills() {
        return !safeSkillListing().isEmpty();
    }

    private List<SkillListingEntry> safeSkillListing() {
        try {
            List<SkillListingEntry> skills = skillListingSupplier != null
                ? skillListingSupplier.get() : List.of();
            return skills == null ? List.of() : skills;
        } catch (RuntimeException _) {
            return List.of();
        }
    }




    private static int defaultSubAgentMaxTokens(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(m, "opus-5") || Strings.CS.contains(m, "opus-4-6")) return 64_000;
        if (Strings.CS.contains(m, "claude-3-opus") || Strings.CS.contains(m, "claude-3-haiku")) return 4_096;
        if (Strings.CS.contains(m, "claude-3-sonnet") || Strings.CS.contains(m, "3-5-sonnet") || Strings.CS.contains(m, "3-5-haiku")) {
            return 8_192;
        }
        return 32_000;
    }

    /**
     * Builds the sub-agent's sidechain transcript sink — split out from {@link #executeSubAgent} so
     * tests can assert the wiring contract without letting a real turn run (constructing one is cheap;
     * nothing is written to disk until {@link TranscriptRecorder#record} is actually called on a
     * completed turn).
     */
    TranscriptRecorder buildSidechainTranscriptSink() {
        return buildSidechainTranscriptSink(AgentId.create());
    }

    /**
     * @param agentId the SAME id passed to {@link #buildSubEngineConfig(SubAgentRequest, String, String)}
     *   for this sub-agent invocation — one call = one agentId, used consistently
     *   for both the config's {@code agentId} gate and the sidechain file name.
     */
    TranscriptRecorder buildSidechainTranscriptSink(String agentId) {
        return buildSidechainTranscriptSink(agentId, null);
    }

    TranscriptRecorder buildSidechainTranscriptSink(String agentId,
                                                     String transcriptSubdir) {
        return buildSidechainTranscriptSink(agentId, transcriptSubdir, null);
    }

    TranscriptRecorder buildSidechainTranscriptSink(String agentId,
                                                     String transcriptSubdir,
                                                     String attributionAgent) {
        if (sessionIdentity == null) {
            return null;
        }
        SessionManager sidechainSessionManager = sidechainSessionManager();
        Path explicit = sidechainTranscriptPath(agentId, transcriptSubdir);
        return new TranscriptRecorder(sidechainSessionManager, new SessionStorage(),
            workingDirectory, /* isSidechain */ true, agentId, explicit,
            attributionAgent);
    }

    private SessionManager sidechainSessionManager() {
        return claudeHomeOverride != null
            ? new SessionManager(claudeHomeOverride, workingDirectory)
            : new SessionManager(workingDirectory);
    }

    private Path sidechainTranscriptPath(String agentId, String transcriptSubdir) {
        if (sessionIdentity == null) return null;
        SessionManager manager = sidechainSessionManager();
        if (StringUtils.isBlank(transcriptSubdir)) {
            return manager.getAgentTranscriptPath(sessionIdentity.get(), agentId).normalize();
        }
        Path base = manager.getProjectDir()
            .resolve(sessionIdentity.get()).resolve("subagents").normalize();
        Path explicit = base.resolve(transcriptSubdir)
            .resolve("agent-" + agentId + ".jsonl").normalize();
        if (!explicit.startsWith(base)) {
            throw new IllegalArgumentException("Invalid sub-agent transcript subdirectory");
        }
        return explicit;
    }

    private SubAgentResult executeSubAgent(SubAgentRequest request) {
// isolation: "worktree" — create a dedicated agent worktree up front so it becomes the
// sub-engine's cwd, then clean it up after the run (remove if unchanged, keep + surface the
// path if the agent made changes).

        WorktreeService.AgentWorktree agentWorktree = null;
        if (request.worktreeIsolation()) {
            try {
                String slug = "agent-" + AgentId.create().substring(0, 8);
                agentWorktree = WorktreeService.createAgentWorktree(slug, resolveWorkingDirectory(request));
            } catch (RuntimeException e) {
                return SubAgentResult.error("Failed to create agent worktree: " + e.getMessage());
            }
        }
        // One agentId per sub-agent invocation, shared by the engine config,
        // sidechain transcript, and agent-exit cleanup. Mint it outside the
        // turn body so the lifecycle hook also runs after exceptional exits.
        String agentId = (request.agentId() != null) ? request.agentId() : AgentId.create();
        AtomicBoolean cleaned = new AtomicBoolean(false);
        try {
            return runSubAgentTurn(request, agentWorktree, cleaned, agentId);
        } finally {
            // Exception path (runSubAgentTurn threw before its own cleanup): keep the
            // worktree on disk — we can't safely verify whether the agent made changes.
            if (agentWorktree != null && !cleaned.get()) {
                log.debug("Agent worktree left in place after abnormal sub-agent exit: {}",
                    agentWorktree.worktreePath());
            }
            if (lifecycleListener != null) {
                lifecycleListener.onSubAgentComplete(agentId);
            }
        }
    }

    private SubAgentResult runSubAgentTurn(SubAgentRequest request, WorktreeService.AgentWorktree agentWorktree,
                                           AtomicBoolean cleaned, String agentId) {
        SubAgentRequest effectiveRequest = withSubAgentStartContext(request, agentId);
        SubAgentRequest.ProgressCallback progressCallback = effectiveRequest.progressCallback();

        String cwdOverride = agentWorktree != null ? agentWorktree.worktreePath() : null;
        // Thread the sub-agent's live tool progress (e.g. nested Bash output)

        // onProgress re-emission. Falls back to NOOP when the parent supplied
        // no progressCallback (e.g. headless).
        // The sub-engine's own ToolExecution will stamp toolUseId onto each

        // sets the parent's toolUseID on re-emitted bash_progress ticks).
        final SubAgentRequest.ProgressCallback progressCb = progressCallback;
        final String parentTuid = agentId;
        ToolExecutionContext.ProgressSink baseSink = flatteningProgressSink(progressCb);
        ToolExecutionContext.ProgressSink nestedSink = (baseSink == ToolExecutionContext.ProgressSink.NOOP)
            ? baseSink
            : update -> baseSink.accept(update.withIdentity(update.toolUseId(), parentTuid));

        QuerySessionSpec config = buildSubEngineConfig(effectiveRequest, cwdOverride, agentId, nestedSink);

// Give the sub-agent its own scoped compact service when one is wired in (composition root
// injects SubAgentCompactServiceImpl).

        // guard: sub-agents run the same query() loop, so they get auto-compact
        // + micro-compact too. The supplier is late-bound (engineBox[0]) because
        // the FileStateCache only exists once the QuerySession is built, exactly
        // like the main-thread engine::getFileStateCache wiring in ClaudeCodeCli.
        // When no factory is supplied (legacy/test callers) we fall back to the
        // single-arg ctor, matching the prior "no compaction in sub-agents" path.
        String model = resolvedSubAgentModel(effectiveRequest.model());
        final QuerySession[] engineBox = new QuerySession[1];
        MessageCompactor subCompact = compactFactory != null
            ? compactFactory.createForSubAgent(agentId, sessionIdentity, model,
                () -> engineBox[0].forks().getFileStateCache())
            : null;
        QuerySessionFactory factory = Objects.requireNonNull(
            querySessionFactory, "QuerySessionFactory is not wired");
        engineBox[0] = factory.create(config.attachMessageCompactor(subCompact));
        QuerySession subEngine = engineBox[0];
        ToolExecutionContext parentContext = effectiveRequest.parentContext();
        if (parentContext != null && parentContext.permissionAskCallback() != null) {
            subEngine.execution().setPermissionAskCallback(
                parentContext.permissionAskCallback());
        }

        TranscriptRecorder sidechainRecorder = buildSidechainTranscriptSink(
            agentId, effectiveRequest.transcriptSubdir(), effectiveRequest.subagentType());
        if (sidechainRecorder != null) {
            TeamInfo teamInfo = currentTeamInfo();
            if (StringUtils.isNotBlank(teamInfo.teamName())
                    || StringUtils.isNotBlank(teamInfo.agentName())) {
                sidechainRecorder.setTeamInfoResolver(_ -> teamInfo);
            }
            subEngine.execution().setTranscriptSink(sidechainRecorder);
            if (sessionIdentity != null) {

                // query() starts.  The child dispatcher is created before the
                // first transcript write, so establish the sidechain turn id
                // here as well; SubagentStop and the child user JSONL row then
                // share the exact same UUID.
                sidechainRecorder.recordPromptStart(sessionIdentity.get(), null);
            }
            if (effectiveRequest.fork() && sessionIdentity != null
                    && effectiveRequest.parentContext() != null
                    && effectiveRequest.priorMessages() != null) {
                List<Message> parentContextMessages =
                    effectiveRequest.parentContext().conversationMessages();
                if (!parentContextMessages.isEmpty()
                        && effectiveRequest.priorMessages().size()
                            >= parentContextMessages.size()) {
                    Message parentLast = parentContextMessages.getLast();
                    sidechainRecorder.recordForkContextRef(
                        sessionIdentity.get(), agentId, sessionIdentity.get(),
                        parentLast.uuid(), parentContextMessages.size());
                    // submitPrepared receives parent-prefix + fork directive as
                    // one prepared list and therefore does not run the ordinary
                    // prompt writer. Persist only the sidechain-owned suffix.
                    for (int i = parentContextMessages.size();
                            i < effectiveRequest.priorMessages().size(); i++) {
                        sidechainRecorder.record(
                            sessionIdentity.get(), effectiveRequest.priorMessages().get(i));
                    }
                }
            }
            if (effectiveRequest.transcriptSubdir() != null) {
                writeWorkflowAgentMetadata(agentId, effectiveRequest);
            } else {
                writeAgentMetadata(agentId, effectiveRequest, agentWorktree);
            }
        }

        if (lifecycleListener != null) {
            BuiltInAgentDefinitions.AgentDefinition definition =
                resolveAgentDefinition(effectiveRequest);
            var configuredMode = config.permissionModeSupplier() == null
                ? null : config.permissionModeSupplier().get();
            String permissionMode = configuredMode == null
                ? null : configuredMode.wireValue();
            String effort = EffortHelpers.resolveAppliedEffort(
                config.model(), effectiveRequest.effort());
            Path transcriptPath = sidechainTranscriptPath(
                agentId, effectiveRequest.transcriptSubdir());
            HookDispatcher childHooks = lifecycleListener.createSubAgentHookDispatcher(
                new SubAgentLifecycleListener.SubAgentHookContext(
                    agentId,
                    effectiveRequest.subagentType() == null ? "" : effectiveRequest.subagentType(),
                    config.workingDirectory(),
                    transcriptPath == null ? null : transcriptPath.toString(),
                    permissionMode,
                    effort,
                    definition == null ? null : definition.hooks(),
                    () -> subEngine.conversation().getMessages(),
                    sidechainRecorder == null || sessionIdentity == null
                        ? () -> null
                        : () -> sidechainRecorder.currentPromptId(sessionIdentity.get())));
            if (childHooks != null) {
                subEngine.execution().setHookDispatcher(childHooks);
            }
        }

        String errorMessage = null;
        SubAgentTermination termination = SubAgentTermination.FAILED;
        String stopReason = null;
        JsonNode structuredOutput = null;
        double progressPercent = 0;
        long startTime = System.currentTimeMillis();
        String taskId = progressCallback != null
            ? "subagent-" + UUID.randomUUID().toString().substring(0, 8)
            : null;


        // when enabled. The summarizer is injected (core interface); it's a no-op
        // (returns a no-op stopper) when disabled or null. Snapshot progress
        // (effectively final) for the lambda — the live value is racy to read.
        Runnable stopSummarization = null;
        final double summaryProgress = progressPercent;
        if (agentSummarizer != null) {
            stopSummarization = agentSummarizer.startSummarization(
                taskId,
                () -> subEngine.conversation().getMessages(),
                (_, text) -> emitProgress(progressCallback, "subagent: " + text, summaryProgress));
        }

        emitProgress(progressCallback, "Starting sub-agent...", 0);

        Iterator<SDKMessage> iterator;
        if (effectiveRequest.fork()
                && effectiveRequest.priorMessages() != null
                && !effectiveRequest.priorMessages().isEmpty()) {
            // The fork messages already contain the parent assistant turn,
            // placeholder tool_results and the fork directive. Use the direct
            // QueryParams path so submitMessage does not append a second user

            PreparedQueryRequest forkParams = new PreparedQueryRequest(
                effectiveRequest.priorMessages(),
                buildSystemPrompt(effectiveRequest),
                model,
                config.fallbackModel(),
                "agent",
                null,
                config.maxTurns(),
                null,
                null,
                false,
                buildSubmitOptions(effectiveRequest));
            iterator = subEngine.submission().submitPrepared(forkParams);
        } else {
            iterator = subEngine.submission().submitMessage(
                buildAgentPrompt(effectiveRequest), buildSubmitOptions(effectiveRequest));
        }

        int asyncToolUseCount = 0;
        PendingToolAssistant pendingToolAssistant = null;
        String pendingPlainAssistantUuid = null;

        MessageQueueManager asyncParentQueue = effectiveRequest.parentQueue();
        String parentToolUseId = effectiveRequest.parentContext() == null
            ? null : effectiveRequest.parentContext().toolUseId();
        String sdkTaskId = effectiveRequest.agentId() != null
            ? effectiveRequest.agentId() : agentId;

        while (iterator.hasNext()) {
            SDKMessage msg = iterator.next();

            if (msg instanceof SDKMessage.Error(Exception exception)) {
                errorMessage = exception.getMessage();
                termination = effectiveRequest.abortController() != null
                        && effectiveRequest.abortController().isAborted()
                    ? SubAgentTermination.INTERRUPTED
                    : SubAgentTermination.FAILED;
                emitProgress(progressCallback, "Error: " + errorMessage, progressPercent);
                break;
            }

            if (msg instanceof SDKMessage.Progress(
                ProgressMessage message
            )) {
                emitProgress(progressCallback, message.content(), progressPercent);
            } else if (msg instanceof SDKMessage.StreamEvent(String eventType, Object data)) {
                if (Strings.CS.equals(
                        SDKMessage.ASSISTANT_USAGE_FINALIZED_EVENT, eventType)
                        && pendingToolAssistant != null
                        && Strings.CS.equals(
                            pendingToolAssistant.messageUuid(), String.valueOf(data))) {
                    AssistantMessage finalized = findAssistantMessage(
                        subEngine, pendingToolAssistant.messageUuid());
                    if (finalized != null) {
                        emitAgentUsage(progressCallback, finalized);
                        enqueueAsyncTaskProgress(asyncParentQueue, sdkTaskId,
                            parentToolUseId, effectiveRequest,
                            pendingToolAssistant.toolUse(),
                            pendingToolAssistant.toolUseCount(), startTime,
                            List.of(finalized));
                        asyncParentQueue.enqueueSdkEvent(new SDKMessage.Assistant(
                            finalized, finalized.message().usage(),
                            finalized.message().model(), parentToolUseId,
                            effectiveRequest.subagentType(),
                            effectiveRequest.description()));
                    }
                    pendingToolAssistant = null;
                } else if (Strings.CS.equals(
                        SDKMessage.ASSISTANT_USAGE_FINALIZED_EVENT, eventType)
                        && pendingPlainAssistantUuid != null
                        && Strings.CS.equals(
                            pendingPlainAssistantUuid, String.valueOf(data))) {
                    AssistantMessage finalized = findAssistantMessage(
                        subEngine, pendingPlainAssistantUuid);
                    emitAgentUsage(progressCallback, finalized);
                    if (finalized != null && asyncParentQueue != null) {
                        asyncParentQueue.enqueuePendingTerminalAssistant(
                            sdkTaskId, new SDKMessage.Assistant(
                                finalized, finalized.message().usage(),
                                finalized.message().model(), parentToolUseId,
                                effectiveRequest.subagentType(),
                                effectiveRequest.description()));
                    }
                    pendingPlainAssistantUuid = null;
                } else if (Strings.CS.equals("thinking", eventType)) {
                    emitProgress(progressCallback, "Thinking...", progressPercent);
                } else if (Strings.CS.equals("tool_use", eventType)) {
                    emitProgress(progressCallback, "Using tool...", progressPercent);
                }
            } else if (msg instanceof SDKMessage.Assistant assistant) {
                emitAgentUsage(progressCallback, assistant.message());
                if (progressCallback != null && assistant.message() != null
                        && containsAgentProgressContent(assistant.message().message().content())) {
                    progressCallback.onAgentMessage(assistant.message(), sdkTaskId);
                }

                if (asyncParentQueue != null) {
                    ToolUseBlock lastToolUse = null;
                    if (assistant.message() != null
                            && assistant.message().message() != null
                            && assistant.message().message().content() != null) {
                        for (ContentBlock block : assistant.message().message().content()) {
                            if (block instanceof ToolUseBlock toolUse) {
                                asyncToolUseCount++;
                                lastToolUse = toolUse;
                            }
                        }
                    }
                    if (lastToolUse != null) {
// QueryLoop yields the completed tool block before its message_delta usage
// update.
                        pendingToolAssistant = new PendingToolAssistant(
                            assistant.message().uuid(), lastToolUse, asyncToolUseCount);
                    } else if (effectiveRequest.async() && assistant.message() != null) {
                        pendingPlainAssistantUuid = assistant.message().uuid();
                    }
                }
            } else if (msg instanceof SDKMessage.User user) {
                if (progressCallback != null && user.message() != null
                        && containsAgentProgressContent(user.message().message().blocks())) {
                    progressCallback.onAgentMessage(user.message(), sdkTaskId);
                }
                // AgentTool emits the initial child prompt synchronously next to
                // task_started so it deterministically precedes the parent tool
                // result. QuerySession exposes that same prompt as a replay user;
                // skip the duplicate here and forward only live child users such
                // as tool results and interruption feedback.
                if (asyncParentQueue != null && user.message() != null && !user.isReplay()
                        && !user.message().isMeta()) {
                    asyncParentQueue.enqueueSdkEvent(new SDKMessage.User(
                        user.message(), false, parentToolUseId,
                        effectiveRequest.subagentType(), effectiveRequest.description(),
                        user.isSynthetic()));
                }
            } else if (msg instanceof SDKMessage.Result result) {
                stopReason = result.stopReason();
                termination = terminationFrom(result, effectiveRequest);
                if (termination != SubAgentTermination.COMPLETED) {
                    errorMessage = result.errors() != null && !result.errors().isEmpty()
                        ? String.join("; ", result.errors())
                        : result.resultText();
                }
                if (effectiveRequest.parentContext() != null
                        && effectiveRequest.parentContext().permissionDenialSink() != null) {
                    result.permissionDenials().forEach(
                        effectiveRequest.parentContext().permissionDenialSink());
                }
                if (result.structuredOutput() != null) {
                    structuredOutput = result.structuredOutput();
                }
                emitProgress(progressCallback, "Finalizing...", 95);
                // NOTE: the sub-agent's text output is accumulated from the
                // streaming SDKMessage.Assistant messages above — NOT from
// result.messages here. result.messages is the full
                // conversation and contains the SAME assistant turns already
                // streamed, so re-appending it would duplicate the entire output
                // (a pre-existing bug). Usage is still taken from the final
                // result when present.
            }
        }

        // Custom/test streaming clients may finish without exposing the
        // internal usage-finalized marker to this consumer. At the iterator
        // boundary the child conversation is final, so publish the retained
        // background terminal assistant from its updated envelope.
        if (pendingPlainAssistantUuid != null && asyncParentQueue != null) {
            AssistantMessage finalized = findAssistantMessage(
                subEngine, pendingPlainAssistantUuid);
            if (finalized != null) {
                asyncParentQueue.enqueuePendingTerminalAssistant(
                    sdkTaskId, new SDKMessage.Assistant(
                        finalized, finalized.message().usage(),
                        finalized.message().model(), parentToolUseId,
                        effectiveRequest.subagentType(),
                        effectiveRequest.description()));
            }
        }

        if (stopSummarization != null) stopSummarization.run();


// recordable message yielded by query. TranscriptRecorder deliberately
        // queues writes, so establish the equivalent normal-return durability
        // boundary here. Without it, the child can report completion while its
        // final JSONL row (and lock acquisition) is still racing caller teardown.
        if (sidechainRecorder != null && sessionIdentity != null
                && !sidechainRecorder.awaitPendingWrites(sessionIdentity.get(), 5_000)) {
            log.warn("Timed out waiting for sub-agent {} transcript writes", agentId);
        }
        if (sidechainRecorder != null && sessionIdentity != null
                && !sidechainRecorder.releaseSessionState(sessionIdentity.get(), 5_000)) {
            log.warn("Timed out releasing sub-agent {} transcript state", agentId);
        }

// Clean up the isolated agent worktree: remove if unchanged, keep + surface the path if the
// agent made changes.
        Optional<String> keptWorktreePath = WorktreeService.cleanupAgentWorktree(agentWorktree);
        cleaned.set(true);
        if (agentWorktree != null && keptWorktreePath.isEmpty()
                && effectiveRequest.transcriptSubdir() == null) {
            writeAgentMetadata(agentId, effectiveRequest, null);
        }

        // Hand the full post-turn conversation back so a multi-turn caller (the
        // in-process teammate) can thread it into the next runSubAgent call.
        List<Message> conversation = new ArrayList<>(subEngine.conversation().getMessages());
        FinalAssistantResult finalAssistant = finalizeAssistantResult(conversation);
        String output = structuredOutput != null
            ? JsonUtils.toJson(structuredOutput)
            : finalAssistant.output();
        if (termination == SubAgentTermination.COMPLETED
                && structuredOutput == null
                && StringUtils.isBlank(output)
                && endsWithUnresolvedToolUse(conversation)) {
            termination = SubAgentTermination.FAILED;
            errorMessage = "Sub-agent stopped before completing its final tool call.";
        }
        Usage totalUsage = finalAssistant.usage();

        long durationMs = System.currentTimeMillis() - startTime;
        double cost = CostCalculator.forModel(config.model()).calculateCost(totalUsage);

        emitProgress(progressCallback,
            termination == SubAgentTermination.COMPLETED
                ? "Complete. Tokens: " + totalUsage.totalTokens()
                    + ", Cost: " + FormatUtils.formatCost(cost)
                : "Stopped: " + termination.name().toLowerCase(Locale.ROOT),
            100);


        // blocks across all assistant messages in the conversation (the old code
        // left this at 0 because the local turnCount was never incremented).
        int toolUseCount = countToolUses(conversation);

        WorktreeInfo worktree = keptWorktreePath
            .map(path -> new WorktreeInfo(path,
                agentWorktree != null ? agentWorktree.worktreeBranch() : null))
            .orElse(null);

        return AgentExecutionResult.builder(output.trim())
            .tokensUsed(totalUsage.totalTokens())
            .outputTokens(totalUsage.outputTokens())
            .costUsd(cost)
            .toolUseCount(toolUseCount)
            .durationMs(durationMs)
            .agentId(agentId)
            .worktree(worktree)
            .conversationMessages(conversation)
            .termination(termination)
            .terminalError(errorMessage)
            .stopReason(stopReason)
            .structuredOutputPresent(structuredOutput != null)
            .usage(totalUsage)
            .resolvedModel(config.model())
            .progressTokens(progressTokenCount(conversation))
            .build();
    }

    private static ToolExecutionContext toolDefinitionSnapshotContext(
            SubAgentRequest request, String sessionId, String workingDirectory,
            String model, List<String> effectiveToolNames, String agentId) {
        ToolExecutionContext parent = request.parentContext();

        if (request.fork() && parent != null) return parent;
        ToolExecutionContext.Builder builder = parent != null
            ? parent.toBuilder()
            : ToolExecutionContext.builder(
                request.abortController() != null
                    ? request.abortController() : new AbortController(),
                sessionId);
        return builder
            .workingDirectory(workingDirectory)
            .currentModel(model)
            .enabledTools(effectiveToolNames)
            .agentId(agentId)
            .agentDepth(Math.max(0, request.agentDepth()))
            .subagentMaxDepthSnapshot(request.teammate()
                ? null : normalizedMaxDepth(request.subagentMaxDepthSnapshot()))
            .build();
    }

    static TeamInfo currentTeamInfo() {
        TeammateContext teammate = TeammateContextHolder.get();
        return teammate != null && StringUtils.isNotBlank(teammate.teamId())
            ? new TeamInfo(teammate.teamId(), teammate.name())
            : TeamInfo.EMPTY;
    }

    private static SubAgentTermination terminationFrom(
            SDKMessage.Result result, SubAgentRequest request) {
        if (request.abortController() != null && request.abortController().isAborted()) {
            return SubAgentTermination.INTERRUPTED;
        }
        return switch (result.resultType()) {
            case SDKMessage.Result.SUCCESS -> SubAgentTermination.COMPLETED;
            case SDKMessage.Result.ERROR_MAX_BUDGET -> SubAgentTermination.MAX_BUDGET;
            case SDKMessage.Result.ERROR_MAX_TURNS -> SubAgentTermination.MAX_TURNS;
            default -> SubAgentTermination.FAILED;
        };
    }

    private static boolean endsWithUnresolvedToolUse(List<Message> conversation) {
        if (conversation == null || conversation.isEmpty()) return false;
        Message last = conversation.getLast();
        if (!(last instanceof AssistantMessage assistant)) return false;
        return assistant.message().content().stream().anyMatch(ToolUseBlock.class::isInstance);
    }

    private static boolean containsAgentProgressContent(List<ContentBlock> content) {
        if (content == null) return false;
        return content.stream().anyMatch(block ->
            block instanceof ToolUseBlock || block instanceof ToolResultBlock);
    }


    private static String activityDescription(ToolUseBlock toolUse, String fallback) {
        if (toolUse == null) return fallback;
        JsonNode input = toolUse.input();
        if (Strings.CS.equals("Bash", toolUse.name())) {
            if (input == null || StringUtils.isBlank(input.path("command").asText(""))) {
                return "Running command";
            }
            String description = input.path("description").asText("");
            if (StringUtils.isBlank(description)) description = input.path("command").asText("");
            return "Running " + description;
        }
        return StringUtils.isBlank(fallback) ? "Using " + toolUse.name() : fallback;
    }

    private static void enqueueAsyncTaskProgress(
            MessageQueueManager queue, String taskId, String parentToolUseId,
            SubAgentRequest request, ToolUseBlock toolUse, int toolUseCount,
            long startTime, List<Message> conversation) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("total_tokens", progressTokenCount(conversation));
        usage.put("tool_uses", toolUseCount);
        usage.put("duration_ms", Math.max(0L, System.currentTimeMillis() - startTime));
        queue.enqueueSdkEvent(new SDKMessage.TaskProgress(
            taskId, parentToolUseId,
            activityDescription(toolUse, request.description()),
            request.subagentType(), usage, toolUse.name()));
    }

    private static AssistantMessage findAssistantMessage(QuerySession engine, String uuid) {
        if (engine == null || uuid == null) return null;
        for (Message message : engine.conversation().getMessages()) {
            if (message instanceof AssistantMessage assistant
                    && Strings.CS.equals(uuid, assistant.uuid())) {
                return assistant;
            }
        }
        return null;
    }

    private record PendingToolAssistant(
        String messageUuid, ToolUseBlock toolUse, int toolUseCount
    ) {}

    /**
     * Signals async startup only after the transport has established the first child stream.
     */
    private static StreamingClient withFirstModelRequestSignal(
            StreamingClient delegate, Runnable signal, Runnable awaitParentResult) {
        if (signal == null) return delegate;
        AtomicBoolean firstRequest = new AtomicBoolean(false);
        return new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                Iterator<StreamingEvent> stream = delegate.createStream(request);
                if (firstRequest.compareAndSet(false, true)) signal.run();
                if (awaitParentResult != null) awaitParentResult.run();
                return stream;
            }

            @Override
            public String getModel() {
                return delegate.getModel();
            }
        };
    }

    private record FinalAssistantResult(String output, Usage usage) {}

/** matches LocalAgentTask.getTokenCountFromTracker: latest input/cache + summed outputs. */
    private static long progressTokenCount(List<Message> messages) {
        long latestInput = 0L;
        long cumulativeOutput = 0L;
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant)
                    || assistant.message() == null || assistant.message().usage() == null) {
                continue;
            }
            Usage usage = assistant.message().usage();
            latestInput = usage.inputTokens()
                + usage.cacheCreationInputTokens()
                + usage.cacheReadInputTokens();
            cumulativeOutput += usage.outputTokens();
        }
        return latestInput + cumulativeOutput;
    }


    private static FinalAssistantResult finalizeAssistantResult(List<Message> messages) {
        AssistantMessage lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant) {
                lastAssistant = assistant;
                break;
            }
        }
        if (lastAssistant == null) {
            throw new IllegalStateException("No assistant messages found");
        }

        String output = assistantText(lastAssistant);
        if (output.isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof AssistantMessage assistant) {
                    String fallback = assistantText(assistant);
                    if (!fallback.isEmpty()) {
                        output = fallback;
                        break;
                    }
                }
            }
        }
        Usage usage = lastAssistant.message() != null && lastAssistant.message().usage() != null
            ? lastAssistant.message().usage()
            : Usage.EMPTY;
        return new FinalAssistantResult(output, usage);
    }

    private static String assistantText(AssistantMessage assistant) {
        if (assistant.message() == null || assistant.message().content() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (var block : assistant.message().content()) {
            if (block instanceof TextBlock(String text1)) {
                text.append(text1);
            }
        }
        return text.toString();
    }

    private SubAgentRequest withSubAgentStartContext(SubAgentRequest request, String agentId) {
        if (lifecycleListener == null) {
            return request;
        }
        try {
            HookDispatcher.HookOutcome outcome = lifecycleListener.onSubAgentStart(
                agentId, request.subagentType() == null ? "" : request.subagentType());
            if (outcome == null) {
                return request;
            }
            List<String> contexts = outcome.additionalContexts().stream()
                .filter(Objects::nonNull)
                .filter(context -> !StringUtils.isBlank(context))
                .toList();
            if (contexts.isEmpty()) {
                return request;
            }

            List<Message> initialMessages = new ArrayList<>(
                request.priorMessages() == null ? List.of() : request.priorMessages());
            initialMessages.add(new AttachmentMessage(
                UUID.randomUUID().toString(),
                new HookAdditionalContextAttachment(
                    contexts,
                    "SubagentStart",
                    UUID.randomUUID().toString(),
                    "SubagentStart")));
            return request.withPriorMessages(List.copyOf(initialMessages));
        } catch (RuntimeException error) {
// Hooks are advisory at this boundary.
            log.warn("SubagentStart hook failed for agent {}", agentId, error);
            return request;
        }
    }

    private void emitProgress(SubAgentRequest.ProgressCallback callback, String status, double progress) {
        if (callback != null) {
            try {
                callback.onProgress(status, progress);
            } catch (Exception e) {
                log.debug("Progress callback failed: {}", e.getMessage());
            }
        } else {
            log.info("[SubAgent] {} ({}%)", status, (int) progress);
        }
    }

    private void emitAgentUsage(SubAgentRequest.ProgressCallback callback,
                                AssistantMessage assistant) {
        if (callback == null || assistant == null || assistant.message() == null
                || assistant.message().usage() == null) {
            return;
        }
        try {
            callback.onAgentUsage(assistant.uuid(), assistant.message().usage());
        } catch (Exception e) {
            log.debug("Agent usage callback failed: {}", e.getMessage());
        }
    }

    private void writeWorkflowAgentMetadata(String agentId, SubAgentRequest request) {
        String transcriptSubdir = request.transcriptSubdir();
        if (sessionIdentity == null || transcriptSubdir == null) return;
        SessionManager manager = claudeHomeOverride != null
            ? new SessionManager(claudeHomeOverride, workingDirectory)
            : new SessionManager(workingDirectory);
        Path base = manager.getProjectDir().resolve(sessionIdentity.get())
            .resolve("subagents").normalize();
        Path metadata = base.resolve(transcriptSubdir)
            .resolve("agent-" + agentId + ".meta.json").normalize();
        if (!metadata.startsWith(base)) {
            throw new IllegalArgumentException("Invalid sub-agent transcript subdirectory");
        }
        try {
            Files.createDirectories(metadata.getParent());
            ObjectNode json = JsonUtils.getMapper().createObjectNode();
            json.put("agentType", "workflow-subagent");
            json.put("spawnDepth", Math.max(0, request.agentDepth()));
            json.put("subagentMaxDepth", normalizedMaxDepth(
                request.subagentMaxDepthSnapshot()));
            Files.writeString(metadata, JsonUtils.getMapper().writeValueAsString(json),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to persist workflow-agent metadata {}: {}",
                metadata, e.getMessage());
        }
    }

    private void writeAgentMetadata(String agentId, SubAgentRequest request,
                                    WorktreeService.AgentWorktree agentWorktree) {
        Path transcript = sidechainTranscriptPath(agentId, null);
        if (transcript == null) return;
        SessionStorage storage = new SessionStorage();
        String worktreePath = agentWorktree == null ? null : agentWorktree.worktreePath();
        if (worktreePath == null && StringUtils.isNotBlank(request.cwd())) {
            try {
                AgentMetadata previous = storage.readAgentMetadata(transcript).orElse(null);
                if (previous != null && Strings.CS.equals(previous.worktreePath(), request.cwd())
                        && Files.isDirectory(Path.of(previous.worktreePath()))) {
                    worktreePath = previous.worktreePath();
                }
            } catch (RuntimeException error) {
                log.debug("Unable to preserve prior agent worktree metadata for {}", agentId, error);
            }
        }
        String agentType = request.fork() ? "fork"
            : StringUtils.defaultIfBlank(request.subagentType(), "general-purpose");
        String description = StringUtils.isBlank(request.description())
            ? null : request.description();
        try {
            storage.writeAgentMetadata(transcript,
                new AgentMetadata(agentType, worktreePath, description, false,
                    Math.max(0, request.agentDepth()), request.teammate() ? null
                        : normalizedMaxDepth(request.subagentMaxDepthSnapshot())));
        } catch (RuntimeException error) {
            log.warn("Failed to persist agent metadata {}: {}", agentId, error.getMessage());
        }
    }

    /**
     * Counts the number of {@code tool_use} blocks across all assistant messages in the sub-agent's
     * conversation.
     */
    private static int countToolUses(List<Message> messages) {
        int count = 0;
        for (Message m : messages) {
            if (m instanceof AssistantMessage am) {
                for (var block : am.message().content()) {
                    if (block instanceof ToolUseBlock) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Builds the first user turn for an agent invocation.  Claude Code's
     * {@code runAgent} appends model-invocable skills declared by custom-agent
     * frontmatter after the task prompt, in the same user message.  Keeping
     * this as structured content (rather than concatenating strings) preserves
     * the block boundaries and the final prompt-cache breakpoint used by the
     * Anthropic request formatter.
     */
    Object buildAgentPrompt(SubAgentRequest request) {
        BuiltInAgentDefinitions.AgentDefinition resolved = resolveAgentDefinition(request);
        if (resolved == null || resolved.skills() == null || resolved.skills().isEmpty()
                || skillSupplier == null) {
            return request.prompt();
        }

        List<Skill> available;
        try {
            available = skillSupplier.get();
        } catch (Throwable t) {
            log.debug("Unable to load skills for agent preloading", t);
            return request.prompt();
        }
        if (available == null || available.isEmpty()) return request.prompt();

        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock(request.prompt() == null ? "" : request.prompt()));
        boolean preloaded = false;
        for (String requestedName : resolved.skills()) {
            if (StringUtils.isBlank(requestedName)) continue;
            Skill skill = findSkill(available, requestedName.trim());
            if (skill == null || skill.content() == null) continue;
            String content = skillPromptContent(skill);
            if (StringUtils.isEmpty(content)) continue;
            blocks.add(new TextBlock(skillLoadingMetadata(skill.name())));
            blocks.add(new TextBlock(content));
            preloaded = true;
        }
        return preloaded ? MessageContent.ofBlocks(List.copyOf(blocks)) : request.prompt();
    }

    private BuiltInAgentDefinitions.AgentDefinition resolveAgentDefinition(SubAgentRequest request) {
        if (request == null) return null;
        String effectiveType = StringUtils.defaultIfBlank(
            request.subagentType(), "general-purpose");
        String cwd = resolveWorkingDirectory(request);
        return AgentDefinitionLoader.getAll(cwd).stream()
            .filter(a -> a.agentType().equals(effectiveType))
            .findFirst()
            .orElse(null);
    }

    private static Skill findSkill(List<Skill> available, String requested) {
        String bare = Strings.CS.contains(requested, ":")
            ? requested.substring(requested.lastIndexOf(':') + 1) : null;
        for (Skill skill : available) {
            if (requested.equals(skill.name())) return skill;
            if (bare != null && bare.equals(skill.name())
                    && skill.source() == Skill.SkillSource.PLUGIN) return skill;
        }
        return null;
    }

    private static String skillLoadingMetadata(String name) {
        return "<command-message>" + name + "</command-message>\n"
            + "<command-name>" + name + "</command-name>\n"
            + "<skill-format>true</skill-format>";
    }

    private static String skillPromptContent(Skill skill) {
        String content = skill.content();
        if (content == null) return null;
        if (skill.source() != Skill.SkillSource.MCP && skill.sourceFile() != null
                && skill.sourceFile().getFileName() != null
                && Strings.CI.equals("SKILL.md", skill.sourceFile().getFileName().toString())) {
            Path root = skill.sourceFile().toAbsolutePath().normalize().getParent();
            if (root != null) {
                String base = root.toString();
                if (File.separatorChar == '\\') base = base.replace('\\', '/');
                content = "Base directory for this skill: " + base + "\n\n" + content;
                content = content.replace("${CLAUDE_SKILL_DIR}", base);
            }
        }

        String dormantRequest = "\n\n## User Request\n\n$ARGUMENTS";
        if (Strings.CS.endsWith(content, dormantRequest)) {
            content = content.substring(0, content.length() - dormantRequest.length());
        }
        // A preloaded agent skill is already the complete instruction body.
        return ArgumentSubstitutor.substitute(content, "", skill.argumentNames(), false);
    }

    // Package-private (not private) so SubAgentTypeRoutingTest can exercise
    // it directly without threading a fake StreamingClient through the full
    // runSubAgent → QuerySession pipeline just to inspect the prompt string.
    String buildSystemPrompt(SubAgentRequest request) {
        if (request.systemPromptOverride() != null) {
            return request.systemPromptOverride();
        }
        // The claude-code-guide agent has its own dedicated system prompt


        // Bypass the generic DEFAULT_AGENT_PROMPT path when routing to it.
        if (request.subagentType() != null
                && request.subagentType().equals(ClaudeCodeGuideAgentPrompt.AGENT_TYPE)) {
            String cwd = resolveWorkingDirectory(request);
            StringBuilder guide = new StringBuilder(ClaudeCodeGuideAgentPrompt.build(
                usingThirdPartyServices, buildClaudeCodeGuideContext(request)));
            // getSystemPrompt supplies the guide-specific body; runAgent then
            // applies the same notes/environment enhancement as every other
            // sub-agent. Returning early here used to drop that shared tail.
            guide.append("\n\n").append(EnvInfoSection.agentNotes());
            guide.append("\n\n").append(EnvInfoSection.computeEnvInfo(
                resolvedSubAgentModel(request.model()), cwd, isGitRepository(cwd), List.of()));
            if (includeGitInstructions()) {
                String gitStatus = QuerySessionEnvironment.initialGitStatusSnapshot(cwd);
                if (StringUtils.isNotBlank(gitStatus)) {
                    guide.append("\n\ngitStatus: ").append(gitStatus);
                }
            }
            return guide.toString();
        }

        String cwd = resolveWorkingDirectory(request);

        // Looks across built-in AND custom (~/.claude/agents, <cwd>/.claude/agents)
        // definitions — previously only checked built-ins, so a custom agent's
        // hand-authored system prompt was silently discarded and every custom
        // agent ran with the generic DEFAULT_AGENT_PROMPT instead of what the
        // user actually wrote in their .md file.
        String effectiveType = StringUtils.defaultIfBlank(
            request.subagentType(), "general-purpose");
        BuiltInAgentDefinitions.AgentDefinition resolved =
            AgentDefinitionLoader.getAll(cwd).stream()
                .filter(a -> a.agentType().equals(effectiveType))
                .findFirst()
                .orElse(null);

        StringBuilder sb = new StringBuilder();
        boolean authoredPrompt = resolved != null
            && resolved.systemPrompt() != null && !StringUtils.isBlank(resolved.systemPrompt());
        if (authoredPrompt) {
// Custom/user/project agent: the authored body IS the whole prompt.
            sb.append(resolved.systemPrompt());
        } else {
// Built-in with no authored prompt (general-purpose/Explore/Plan/ statusline-setup), or
// an unresolvable subagent_type — unchanged synthesis.
            if (resolved != null) {
                sb.append("You are the \"").append(resolved.agentType()).append("\" agent.\n\n");
                sb.append(resolved.whenToUse()).append("\n\n");
            }
            sb.append(SystemPromptConstants.DEFAULT_AGENT_PROMPT);
        }

        if (resolved != null && resolved.memory() != null && !StringUtils.isBlank(resolved.memory())) {
            Path memoryDir = AgentMemoryPrompt.resolveDirectory(
                resolved.agentType(), resolved.memory(), Path.of(cwd), claudeHomeOverride);
            String memoryPrompt = AgentMemoryPrompt.build(memoryDir, resolved.memory());
            if (!StringUtils.isBlank(memoryPrompt)) sb.append("\n\n").append(memoryPrompt);
        }

        if (!authoredPrompt && !request.tools().isEmpty()) {
            sb.append("\n\nAvailable tools: ");
            sb.append(String.join(", ", request.tools()));
        }


        if (request.teammate()) {
            sb.append("\n\n").append(TEAMMATE_SYSTEM_ADDENDUM);
        }


        // full environment context (cwd, repository status, platform/shell,
        // model and cutoff). This is distinct from the main loop's compact
        // env_info_simple section.
        sb.append("\n\n").append(EnvInfoSection.agentNotes());
        sb.append("\n\n").append(EnvInfoSection.computeEnvInfo(
            resolvedSubAgentModel(request.model()),
            cwd,
            isGitRepository(cwd),
            List.of()));

        if (!isGitOmittingAgent(request.subagentType()) && includeGitInstructions()) {
            String gitStatus = QuerySessionEnvironment.initialGitStatusSnapshot(cwd);
            if (StringUtils.isNotBlank(gitStatus)) {
                sb.append("\n\ngitStatus: ").append(gitStatus);
            }
        }

        return sb.toString();
    }

    private ClaudeCodeGuideAgentPrompt.Context buildClaudeCodeGuideContext(
            SubAgentRequest request) {
        List<Skill> skills = safeSkills();
        List<ClaudeCodeGuideAgentPrompt.Command> customSkills = guideCommands(skills);
        List<ClaudeCodeGuideAgentPrompt.Command> pluginSkills = skills.stream()
            .filter(skill -> skill.source() == Skill.SkillSource.PLUGIN)
            .filter(skill -> !StringUtils.isBlank(skill.name()))
            .map(skill -> new ClaudeCodeGuideAgentPrompt.Command(
                skill.name(), Objects.toString(skill.description(), "")))
            .toList();
        String cwd = resolveWorkingDirectory(request);
        List<ClaudeCodeGuideAgentPrompt.Agent> customAgents = AgentDefinitionLoader.getAll(cwd)
            .stream()
            .filter(agent -> agent.source() != AgentSource.BUILT_IN)
            .map(agent -> new ClaudeCodeGuideAgentPrompt.Agent(
                agent.agentType(), Objects.toString(agent.whenToUse(), "")))
            .toList();
        return new ClaudeCodeGuideAgentPrompt.Context(
            customSkills, customAgents, pluginSkills, safeEffectiveSettings());
    }

    private static List<ClaudeCodeGuideAgentPrompt.Command> guideCommands(List<Skill> skills) {
        List<ClaudeCodeGuideAgentPrompt.Command> commands = new ArrayList<>();
        for (Skill skill : skills) {
            if (StringUtils.isBlank(skill.name())) continue;
            String description = Objects.toString(skill.description(), "");
            if (Strings.CS.equals("deep-research", skill.name())) {
                description = "Deep research harness — fan-out web searches, fetch sources, "
                    + "adversarially verify claims, synthesize a cited report.";
            } else if (Strings.CS.equals("loop", skill.name())) {
                description = "Run a prompt or slash command on a recurring interval "
                    + "(e.g. /loop 5m /foo, defaults to 10m)";
            }
            commands.add(new ClaudeCodeGuideAgentPrompt.Command(skill.name(), description));
            switch (skill.name()) {
                case "verify" -> commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                    "debug", "Enable debug logging for this session and help diagnose issues"));
                case "simplify" -> commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                    "batch", "Research and plan a large-scale change, then execute it in parallel "
                        + "across 5–30 isolated worktree agents that each open a PR."));
                case "run" -> commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                    "run-skill-generator", "Author or improve the run-<unit> skill — a per-project "
                        + "skill that tells agents how to build, launch, and drive this project's app. "
                        + "Use when the user asks to set up the project, get it running, write run "
                        + "instructions, or verify build/run steps work from a clean environment."));
                case "init" -> commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                    "statusline", "Set up Claude Code's status line UI"));
                case "security-review" -> {
                    commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                        "insights", "Generate a report analyzing your Claude Code sessions"));
                    commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                        "team-onboarding", "Help teammates ramp on Claude Code with a guide from your usage"));
                }
                default -> { }
            }
        }
        return List.copyOf(commands);
    }

    private List<Skill> safeSkills() {
        try {
            List<Skill> skills = skillSupplier != null ? skillSupplier.get() : List.of();
            return skills == null ? List.of() : skills;
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    private JsonNode safeEffectiveSettings() {
        try {
            JsonNode settings = effectiveSettingsSupplier != null
                ? effectiveSettingsSupplier.get() : null;
            return settings == null ? null : settings.deepCopy();
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static boolean isGitOmittingAgent(String subagentType) {
        return Strings.CS.equals("Explore", subagentType) || Strings.CS.equals("Plan", subagentType);
    }

    private boolean includeGitInstructions() {
        try {
            return !Boolean.FALSE.equals(includeGitInstructionsSupplier.get());
        } catch (RuntimeException _) {
            return true;
        }
    }

    private static boolean isGitRepository(String cwd) {
        if (StringUtils.isBlank(cwd)) return false;
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                .directory(Path.of(cwd).toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception _) {
            return false;
        }
    }

    private static String resolvedSubAgentModel(String requested) {
        if (StringUtils.isBlank(requested)) return "claude-sonnet-4-20250514";
        return switch (requested.toLowerCase(Locale.ROOT)) {
            case "haiku", "sonnet", "opus", "fable" -> ModelCatalog.resolve(requested);
            default -> requested;
        };
    }


    private static final String TEAMMATE_SYSTEM_ADDENDUM =
        """
            You are running as an in-process teammate within a larger agent team.
            - The team leader (the main coordinator) will send you tasks. Work on one task at a time.
            - Use the SendMessage tool to communicate with the leader or other teammates by name.
            - When you finish a task and have no further tool calls, report your result briefly and stop; \
            the leader will send the next task.
            - If the leader asks you to shut down, call the shutdown approval tool (or explain why you should continue).
            - Never prompt the user directly; route all questions through the leader.""";

    static SubmitOptions buildSubmitOptions(SubAgentRequest request) {
        SubmitOptions options = request.jsonSchema() != null
            ? SubmitOptions.withSchema("user", request.jsonSchema())
            : SubmitOptions.DEFAULT;
        return StringUtils.isNotBlank(request.effort())
            ? options.withPromptOverrides(null, request.effort())
            : options;
    }

    ToolExecutor createRestrictedToolExecutor(SubAgentRequest request) {
        List<String> effectiveToolNames = effectiveToolNames(request);
        ToolExecutionContext context = toolDefinitionSnapshotContext(
            request, request.parentContext() != null
                ? request.parentContext().sessionId() : "subagent-tool-snapshot",
            resolveWorkingDirectory(request), resolvedSubAgentModel(request.model()),
            effectiveToolNames, request.agentId());
        return createRestrictedToolExecutor(request, effectiveToolNames, context);
    }

    private ToolExecutor createRestrictedToolExecutor(
            SubAgentRequest request, List<String> effectiveToolNames,
            ToolExecutionContext toolDefinitionContext) {
        // Defense-in-depth: even if a caller manages to put an interactive / coordination tool
        // (AskUserQuestion, ExitPlanMode,...) into the sub-agent's tool list, the restricted
        // executor refuses it and hides it from the model.
        Set<String> requested = new LinkedHashSet<>(effectiveToolNames);

        // including Agent; recursive fork attempts are rejected by the injected
        // fork directive/history guard rather than by changing this definition.
        Set<String> allowed = new HashSet<>(requested);
        Set<String> denied = request.fork()
            ? new HashSet<>()
            : new HashSet<>(AgentTool.AGENT_DISALLOWED_TOOLS);
        if (!request.fork()
                && request.agentDepth() >= normalizedMaxDepth(
                    request.subagentMaxDepthSnapshot())) {
            denied.add("Agent");
        }
        boolean synchronousAgent = !request.async();
        if (synchronousAgent) {
            denied.remove("TaskStop");
        }
        if (!request.fork()) {
            allowed.removeAll(AgentTool.AGENT_DISALLOWED_TOOLS);
            if (synchronousAgent && requested.contains("TaskStop")) {
                allowed.add("TaskStop");
            }
        }
        if (request.disallowedTools() != null) {
            denied.addAll(request.disallowedTools());
        }
        allowed.removeAll(denied);
        SyntheticOutputTool structuredOutput = null;
        if (request.jsonSchema() != null) {
            SyntheticOutputTool.CreateResult created = SyntheticOutputTool.create(request.jsonSchema());
            if (created instanceof SyntheticOutputTool.CreateResult.Ok(SyntheticOutputTool tool)) {
                structuredOutput = tool;
            } else if (created instanceof SyntheticOutputTool.CreateResult.Err(String error)) {
                throw new IllegalArgumentException(error);
            }
        }
        List<String> ordered = effectiveToolNames.stream()
            .filter(StringUtils::isNotBlank)
            .filter(name -> !denied.contains(name))
            .distinct()
            .toList();
        return RestrictedToolExecutor.create(toolExecutor, allowed, denied,
            structuredOutput, ordered, toolDefinitionContext);
    }

    private List<String> effectiveToolNames(SubAgentRequest request) {
        List<String> source = request.tools().isEmpty()
            ? toolExecutor.getToolDefinitions().stream()
                .map(StreamingClient.StreamRequest.ToolDef::name).toList()
            : request.tools();
        Set<String> denied = new HashSet<>(request.fork()
            ? Set.of() : AgentTool.AGENT_DISALLOWED_TOOLS);
        if (!request.fork()
                && request.agentDepth() >= normalizedMaxDepth(
                    request.subagentMaxDepthSnapshot())) {
            denied.add("Agent");
        }
        if (request.disallowedTools() != null) denied.addAll(request.disallowedTools());
        return source.stream()
            .filter(StringUtils::isNotBlank)
            .filter(name -> !denied.contains(name))
            .distinct()
            .toList();
    }

    private static int normalizedMaxDepth(Integer configured) {
        return configured != null && configured >= 1 && configured <= 5
            ? configured : 2;
    }


    private String resolveWorkingDirectory(SubAgentRequest request) {
        // Explicit cwd input overrides the inherited cwd / session worktree

        if (StringUtils.isNotBlank(request.cwd())) {
            return request.cwd();
        }
        if (request.parentContext() != null
                && StringUtils.isNotBlank(request.parentContext().workingDirectory())) {
            return request.parentContext().workingDirectory();
        }
        WorktreeSession active = WorktreeService.getCurrentWorktreeSession();
        return active != null ? active.worktreePath() : workingDirectory;
    }

    /**
     * Restricted tool executor that only allows specified tools.
     */
    private record RestrictedToolExecutor(ToolExecutor delegate,
                                          Set<String> allowedTools,
                                          Set<String> deniedTools,
                                          SyntheticOutputTool structuredOutput,
                                          List<String> orderedToolNames,
                                          List<StreamingClient.StreamRequest.ToolDef> eagerDefinitions,
                                          List<StreamingClient.StreamRequest.ToolDef> deferredDefinitions)
            implements ToolExecutor {

        static RestrictedToolExecutor create(
                ToolExecutor delegate, Set<String> allowedTools,
                Set<String> deniedTools, SyntheticOutputTool structuredOutput,
                List<String> orderedToolNames,
                ToolExecutionContext definitionContext) {
            Set<String> allowedSnapshot = Set.copyOf(allowedTools);
            Set<String> deniedSnapshot = Set.copyOf(deniedTools);
            List<String> orderSnapshot = List.copyOf(orderedToolNames);
            List<StreamingClient.StreamRequest.ToolDef> eager = freezeDefinitions(
                delegate.getToolDefinitions(definitionContext), allowedSnapshot,
                deniedSnapshot, structuredOutput, orderSnapshot);
            List<StreamingClient.StreamRequest.ToolDef> deferred = freezeDefinitions(
                delegate.getToolDefinitions(new LinkedHashSet<>(orderSnapshot),
                    definitionContext),
                allowedSnapshot, deniedSnapshot, structuredOutput, orderSnapshot);
            return new RestrictedToolExecutor(delegate, allowedSnapshot,
                deniedSnapshot, structuredOutput, orderSnapshot, eager, deferred);
        }

        @Override
        public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context) {
            if (structuredOutput != null && SyntheticOutputTool.NAME.equals(toolName)) {
                try {
                    Object value = structuredOutput.call(input, context);
                    if (value instanceof StructuredToolOutput(String text, Object toolUseResult)) {
                        return ToolResult.success(text).withToolUseResult(toolUseResult);
                    }
                    return ToolResult.success(value == null ? "" : value.toString());
                } catch (Exception e) {
                    return ToolResult.error(ToolErrors.formatError(e));
                }
            }
            if (deniedTools.contains(toolName)
                    || !allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
                return ToolResult.error(
                    "Tool '" + toolName + "' is not allowed for this sub-agent");
            }
            return delegate.execute(toolName, input, context);
        }

        @Override
        public PostToolUseOutputResult processPostToolUseOutput(
                String toolName, JsonNode originalInput, JsonNode updatedOutput,
                ToolResult originalResult, ToolExecutionContext context) {
            if (deniedTools.contains(toolName)
                    || !allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
                return new PostToolUseOutputResult.Rejected(
                    "Tool '" + toolName + "' is not allowed for this sub-agent");
            }
            return delegate.processPostToolUseOutput(
                toolName, originalInput, updatedOutput, originalResult, context);
        }

        @Override
        public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
            return eagerDefinitions;
        }

        @Override
        public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                ToolExecutionContext context) {
            return eagerDefinitions;
        }

        @Override
        public List<Message> applyToolResultBudget(List<Message> messages,
                String sessionId, String workingDirectory, String agentId) {
            return delegate.applyToolResultBudget(
                messages, sessionId, workingDirectory, agentId);
        }

        @Override
        public void restoreToolResultBudget(List<Message> messages,
                List<ToolResultBudget.Replacement> replacements,
                String sessionId, String workingDirectory, String agentId) {
            delegate.restoreToolResultBudget(
                messages, replacements, sessionId, workingDirectory, agentId);
        }

        @Override
        public List<ToolResultBudget.Replacement>
                drainToolResultBudgetReplacements(
                    String sessionId, String workingDirectory, String agentId) {
            return delegate.drainToolResultBudgetReplacements(
                sessionId, workingDirectory, agentId);
        }

        @Override
        public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                Set<String> discoveredToolNames, ToolExecutionContext context) {
            Set<String> discovered = discoveredToolNames != null
                ? discoveredToolNames : Set.of();
            return deferredDefinitions.stream()
                .filter(def -> !def.deferLoading() || discovered.contains(def.name()))
                .toList();
        }

        private static List<StreamingClient.StreamRequest.ToolDef> freezeDefinitions(
                List<StreamingClient.StreamRequest.ToolDef> source,
                Set<String> allowedTools, Set<String> deniedTools,
                SyntheticOutputTool structuredOutput,
                List<String> orderedToolNames) {
            List<StreamingClient.StreamRequest.ToolDef> definitions = new ArrayList<>(
                source.stream()
                .filter(def -> !deniedTools.contains(def.name()))
                .filter(def -> allowedTools.isEmpty() || allowedTools.contains(def.name()))
                .map(RestrictedToolExecutor::copyDefinition)
                .toList());
            if (structuredOutput != null) {
                definitions.add(new StreamingClient.StreamRequest.ToolDef(
                    structuredOutput.name(), structuredOutput.description(),
                    structuredOutput.inputSchema()));
            }
            if (!orderedToolNames.isEmpty()) {
                Map<String, Integer> order = new HashMap<>();
                for (int i = 0; i < orderedToolNames.size(); i++) {
                    order.putIfAbsent(orderedToolNames.get(i), i);
                }
                definitions.sort(Comparator
                    .comparingInt((StreamingClient.StreamRequest.ToolDef def) ->
                        order.getOrDefault(def.name(), Integer.MAX_VALUE))
                    .thenComparing(StreamingClient.StreamRequest.ToolDef::name));
            }
            return List.copyOf(definitions);
        }

        private static StreamingClient.StreamRequest.ToolDef copyDefinition(
                StreamingClient.StreamRequest.ToolDef definition) {
            Object schema = definition.inputSchema();
            if (schema instanceof JsonNode node) {
                schema = node.deepCopy();
            } else if (schema != null) {
                schema = JsonUtils.getMapper().valueToTree(schema);
            }
            return new StreamingClient.StreamRequest.ToolDef(
                definition.name(), definition.description(), schema,
                definition.type(), definition.maxUses(),
                definition.allowedDomains() == null ? null
                    : List.copyOf(definition.allowedDomains()),
                definition.blockedDomains() == null ? null
                    : List.copyOf(definition.blockedDomains()),
                definition.deferLoading(), definition.strict(),
                definition.eagerInputStreaming());
        }
    }
}
