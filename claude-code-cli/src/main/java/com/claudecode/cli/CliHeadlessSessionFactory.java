package com.claudecode.cli;

import com.claudecode.core.attachment.AgentListingDeltaAttachmentProvider;
import com.claudecode.core.attachment.AtMentionedFilesProvider;
import com.claudecode.core.attachment.AttachmentService;
import com.claudecode.core.attachment.ChangedFilesProvider;
import com.claudecode.core.attachment.DateChangeAttachmentProvider;
import com.claudecode.core.attachment.DeferredToolsDeltaAttachmentProvider;
import com.claudecode.core.attachment.FeatureFlag;
import com.claudecode.core.attachment.FeatureFlagRegistry;
import com.claudecode.core.attachment.McpInstructionsDeltaAttachmentProvider;
import com.claudecode.core.attachment.McpResourceAttachmentProvider;
import com.claudecode.core.attachment.SkillListingAttachmentProvider;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.prompt.SystemPromptRuntime;
import com.claudecode.core.state.CwdState;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.sessionhost.RemoteAttachmentStore;
import com.claudecode.runtime.sessionhost.QuerySessionRewindService;
import com.claudecode.runtime.sessionhost.SessionHostCompactController;
import com.claudecode.runtime.sessionhost.SessionHostEffortController;
import com.claudecode.runtime.sessionhost.SessionHostEffortState;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostModelController;
import com.claudecode.runtime.sessionhost.SessionHostModelOptions;
import com.claudecode.runtime.sessionhost.SessionHostModelState;
import com.claudecode.runtime.sessionhost.SessionHostPermissionController;
import com.claudecode.runtime.sessionhost.SessionHostPermissionOptions;
import com.claudecode.runtime.sessionhost.SessionHostPermissionState;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.services.claudemd.MemoryFileScanner;
import com.claudecode.services.claudemd.MemoryPromptBuilder;
import com.claudecode.services.claudemd.MemoryType;
import com.claudecode.services.config.WorkspaceSettings;
import com.claudecode.services.session.SessionFileHistorySink;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.skills.SkillLoader;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Assembles one parallel headless {@code QuerySession} for the web gateway.
 *
 * <p>The spec follows the sub-agent assembly shape ({@code
 * DefaultSubAgentFactory.buildSubEngineConfig}): a full tool registry, an
 * arbitrary working directory, its own abort controller, and a claude.md
 * supplier bound to the session's own project rather than the process-wide
 * project switch. The transcript records into the session's own project
 * directory, so the TUI {@code /resume} and {@code /api/sessions} listings
 * see it like any other session.
 */
final class CliHeadlessSessionFactory {

    private static final Logger log =
        System.getLogger(CliHeadlessSessionFactory.class.getName());

    /** Binds the active headless session id while raising a permission/question ask. */
    static final ScopedValue<String> CURRENT_SESSION_ID = ScopedValue.newInstance();

    static String currentSessionIdOrEmpty() {
        return CURRENT_SESSION_ID.isBound() ? CURRENT_SESSION_ID.get() : "";
    }

    private final StreamingClient client;
    private final ToolRegistry toolRegistry;
    private final QuerySessionFactory querySessionFactory;
    private final PermissionGate sharedGate;
    private final String resolvedModel;
    private final CustomModelCatalog customModels;
    private final boolean showBuiltInModelFamilies;
    private final InteractionCoordinator interactions;
    private final CliSessionCapabilityService capabilityService;
    private final SkillLoader skillLoader;

    CliHeadlessSessionFactory(
            StreamingClient client,
            ToolRegistry toolRegistry,
            QuerySessionFactory querySessionFactory,
            PermissionGate sharedGate,
            String resolvedModel,
            CustomModelCatalog customModels,
            boolean showBuiltInModelFamilies) {
        this(client, toolRegistry, querySessionFactory, sharedGate, resolvedModel,
            customModels, showBuiltInModelFamilies, null, null, null);
    }

    CliHeadlessSessionFactory(
            StreamingClient client,
            ToolRegistry toolRegistry,
            QuerySessionFactory querySessionFactory,
            PermissionGate sharedGate,
            String resolvedModel,
            CustomModelCatalog customModels,
            boolean showBuiltInModelFamilies,
            InteractionCoordinator interactions) {
        this(client, toolRegistry, querySessionFactory, sharedGate, resolvedModel,
            customModels, showBuiltInModelFamilies, interactions, null, null);
    }

    CliHeadlessSessionFactory(
            StreamingClient client,
            ToolRegistry toolRegistry,
            QuerySessionFactory querySessionFactory,
            PermissionGate sharedGate,
            String resolvedModel,
            CustomModelCatalog customModels,
            boolean showBuiltInModelFamilies,
            InteractionCoordinator interactions,
            CliSessionCapabilityService capabilityService,
            SkillLoader skillLoader) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.querySessionFactory = querySessionFactory;
        this.sharedGate = sharedGate;
        this.resolvedModel = resolvedModel;
        this.customModels = customModels;
        this.showBuiltInModelFamilies = showBuiltInModelFamilies;
        this.interactions = interactions;
        this.capabilityService = capabilityService;
        this.skillLoader = skillLoader;
    }

    /** One assembled headless session: the host record plus its engine. */
    record Assembled(
            SessionHostSession host,
            QuerySession engine,
            AbortController abort,
            String projectPath) {}

    /** Tool registry shared by headless sessions assembled by this factory. */
    ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    CliSessionCapabilityService capabilityService() {
        return capabilityService;
    }

    /**
     * Assembles one headless session bound to {@code projectPath}, optionally
     * resuming {@code priorMessages}.
     */
    Assembled assemble(String sessionId, String projectPath, List<Message> priorMessages) {
        SessionIdentity identity = SessionIdentity.of(sessionId);
        AbortController abort = new AbortController();
        SessionEventHub events = new SessionEventHub(new NoopSink(), failure ->
            log.log(Level.WARNING, "headless session observer failed", failure));

        if (capabilityService != null) {
            capabilityService.bindSessionProject(sessionId, projectPath);
            capabilityService.ensureSessionMcpConnected(sessionId);
        }

        AtomicReference<QuerySession> engineRef = new AtomicReference<>();
        List<String> initialTools = capabilityService != null
            ? List.copyOf(toolRegistry.getFrozenInitialEagerToolNames(sessionId))
            : toolRegistry.getAll().stream().map(Tool::name).toList();

        QuerySessionSpec.Builder builder = QuerySessionSpec.builder()
            .llmClient(client)
            .model(resolvedModel)
            .customModel(name -> customModels != null && customModels.find(name).isPresent())
            .tools(initialTools)
            .toolExecutor(toolRegistry)
            .workingDirectory(projectPath)
            .fileHistoryEnabled(true)
            .abortController(abort)
            .sessionIdentity(identity)
            .claudeMdContentSupplier(() -> loadClaudeMd(Path.of(projectPath)));
        if (capabilityService != null) {
            FeatureFlagRegistry flags = FeatureFlagRegistry.builder()
                .enable(FeatureFlag.DEFERRED_TOOLS_DELTA)
                .enable(FeatureFlag.MCP_INSTRUCTIONS_DELTA)
                .enable(FeatureFlag.AGENT_LISTING_DELTA)
                .build();
            AttachmentService attachments = new AttachmentService(List.of(
                new AtMentionedFilesProvider(),
                new McpResourceAttachmentProvider(),
                new DateChangeAttachmentProvider(),
                new ChangedFilesProvider(),
                new DeferredToolsDeltaAttachmentProvider(),
                new AgentListingDeltaAttachmentProvider(),
                new McpInstructionsDeltaAttachmentProvider(),
                new SkillListingAttachmentProvider()), flags);
            builder
                .featureFlags(flags)
                .attachmentService(attachments)
                .initialToolBaselineSupplier(() -> List.copyOf(
                    toolRegistry.getFrozenInitialEagerToolNames(sessionId)))
                .liveToolNamesSupplier(() -> toolRegistry.getLiveSessionToolNames(sessionId))
                .mcpServerInstructionsSupplier(() -> {
                    try {
                        return capabilityService.sessionMcpInstructions(sessionId);
                    } catch (Throwable _) {
                        return Map.of();
                    }
                })
                .promptRuntimeSupplier(() -> new SystemPromptRuntime(
                    null, true, null, true, List.of(), List.of(), false));
            if (skillLoader != null) {
                builder.skillListingSupplier(() -> {
                    try {
                        QuerySession live = engineRef.get();
                        String currentModel = live != null
                            ? live.configuration().getConfig().model() : resolvedModel;
                        return CliPromptInventoryAssembler.skillListingEntries(
                            skillLoader.loadEnabled(sessionId), currentModel);
                    } catch (Throwable _) {
                        return List.of();
                    }
                });
            }
        }
        if (priorMessages != null && !priorMessages.isEmpty()) {
            builder.initialMessages(priorMessages);
        }
        QuerySession engine = querySessionFactory.create(builder.build());
        engineRef.set(engine);
        // The project path is the session's working directory, and it must be
        // stamped into every transcript row: the catalog groups sessions by the
        // cwd a row records, so the two-argument constructor would stamp this
        // process's own directory instead — a web-created session would then
        // be listed under the gateway's project rather than the one it opened.
        TranscriptRecorder recorder = new TranscriptRecorder(
            new SessionManager(projectPath), new SessionStorage(), projectPath, false);
        Path sessionFile = new SessionManager(projectPath).getSessionFile(sessionId);
        // Restore the durable HUD fold from the same persisted rows the TUI
        // /resume path consumes, so an opened history session reports
        // whole-session metrics instead of an empty fold. Contiguous seq +
        // full turnId coverage is required by the tracker; anything short
        // stays INCOMPLETE, which the gateway serves as null rather than a
        // partial total. Must land before any turn can start.
        if (Files.isRegularFile(sessionFile)) {
            // Restore BEFORE the sink install: the recorder flushes writes
            // asynchronously, so a restore that reads after setTranscriptSink
            // races the just-emitted session/start row — an unflushed queue
            // makes the read miss it and the empty-events branch then emits a
            // second session/start with a reset seq, permanently breaking the
            // strict-sequence restore for this transcript. Restoring first
            // reads a pristine file; a successful replay marks the session
            // started, which also suppresses the sink install's own
            // ensureStarted emit.
            SessionStorage storage = new SessionStorage();
            engine.execution().restoreSessionMetrics(sessionId,
                storage.readSessionMetrics(sessionFile), storage.readMetricTurnIds(sessionFile));
        }
        // A brand-new session skips the restore entirely: with no file the
        // tracker is already fresh-complete, and the sink install below emits
        // exactly one session/start row.
        engine.execution().setTranscriptSink(recorder);
        if (engine.conversation().getFileHistoryManager() != null) {
            engine.conversation().getFileHistoryManager().setSnapshotSink(
                new SessionFileHistorySink(new SessionStorage(), recorder::sessionManager));
        }

        PermissionGate gate = gateFor(projectPath);
        if (interactions != null) {
            engine.execution().setPermissionAskCallback(context -> {
                var feature = Strings.CS.equals("AskUserQuestion", context.toolName())
                    ? InteractionFeatures.USER_QUESTION
                    : InteractionFeatures.PERMISSION;
                return ScopedValue.where(CURRENT_SESSION_ID, sessionId)
                    .call(() -> interactions.request(feature, context));
            });
        }
        SessionHostInfo info = new SessionHostInfo(
            sessionId, projectPath, "", 0, Instant.now(), "");
        HeadlessTurnDriver driver = new HeadlessTurnDriver(engine, events,
            file -> RemoteAttachmentStore.persist(projectPath, sessionId,
                submissionMessageId(file), file).toString(),
            () -> gate.currentMode().external(),
            recorder,
            sessionId);
        SessionHostSession host = new SessionHostSession(
            info, events, driver::submit,
            new SessionHostModelController() {
                @Override public SessionHostModelState get() {
                    return modelState(engine);
                }

                @Override public SessionHostModelState set(String selected) {
                    SessionHostModelState available = modelState(engine);
                    if (!SessionHostModelOptions.isSelectable(
                            available.models(), selected)) {
                        throw new IllegalArgumentException(
                            "model is not available for this session");
                    }
                    // Clearing the preference lets requests resolve the concrete
                    // default — the same preference semantics as the TUI picker.
                    String preference = Strings.CS.equals(
                        SessionHostModelOptions.DEFAULT_SELECTION, selected) ? null : selected;
                    engine.configuration().setModel(preference);
                    return modelState(engine);
                }
            },
            new SessionHostEffortController() {
                @Override public SessionHostEffortState get() {
                    return effortState(engine);
                }

                @Override public SessionHostEffortState set(String selected) {
                    SessionHostEffortState available = effortState(engine);
                    if (!available.efforts().contains(selected)) {
                        throw new IllegalArgumentException(
                            "effort is not available for this session");
                    }
                    String configured = Strings.CS.equals("auto", selected)
                        ? null : selected;
                    engine.configuration().getConfig().setEffortValue(configured);
                    return effortState(engine);
                }
            },
            SessionHostCompactController.unsupported(),
            new SessionHostPermissionController() {
                @Override public SessionHostPermissionState get() {
                    return SessionHostPermissionOptions.build(gate);
                }

                @Override public SessionHostPermissionState set(String selected) {
                    if (!SessionHostPermissionOptions.isSelectable(selected)) {
                        throw new IllegalArgumentException(
                            "permission mode is not available for this session");
                    }
                    PermissionMode parsed = PermissionGate.parseMode(selected);
                    if (parsed == PermissionMode.BYPASS_PERMISSIONS) {
                        if (gate.isBypassPermissionsModeDisabledByPolicy()) {
                            throw new IllegalArgumentException(
                                "Cannot set permission mode to bypassPermissions "
                                    + "because it is disabled by settings or configuration");
                        }
                        if (!gate.isBypassPermissionsModeAvailable()) {
                            gate.setBypassPermissionsModeAvailable(true);
                        }
                    }
                    gate.setMode(parsed);
                    return SessionHostPermissionOptions.build(gate);
                }
            },
            // Same engine interrupt the TUI Ctrl+C and the SDK interrupt
            // control request drive (reason user-cancel); a no-op when the
            // headless turn already ended.
            () -> engine.submission().interrupt(),
            new QuerySessionRewindService(engine, recorder, null));
        return new Assembled(host, engine, abort, projectPath);
    }

    /**
     * The model catalogue over the engine's live preference — the same
     * projection, and the same {@code showBuiltInModelFamilies} gate, the TUI
     * /model picker and {@code SessionHostPublisher} serve. Omitting the gate
     * here is what let a custom-endpoint session advertise the official
     * families alongside its own catalogue.
     */
    private SessionHostModelState modelState(QuerySession engine) {
        QuerySessionSpec config = engine.configuration().getConfig();
        String preference = config.modelPreference();
        List<CustomModelConfig> custom =
            customModels != null ? customModels.list() : List.of();
        return new SessionHostModelState(
            SessionHostModelOptions.currentSelection(preference, config.model()),
            SessionHostModelOptions.build(preference, config::isModelAllowed, custom,
                showBuiltInModelFamilies));
    }

    /** The engine's effort levels — the same projection the TUI picker and webui serve. */
    private SessionHostEffortState effortState(QuerySession engine) {
        String model = engine.configuration().getConfig().model();
        if (!EffortHelpers.modelSupportsEffort(model)) {
            return new SessionHostEffortState("auto", "", List.of());
        }
        String configured = engine.configuration().getConfig().effortValue();
        String current = StringUtils.isBlank(configured) ? "auto" : configured;
        // One shared projection so webui offers and renders ultracode exactly as the TUI does;
        // effective stays folded to the real level that reaches the wire.
        EffortHelpers.EffortProjection projection = EffortHelpers.projectEffort(
            model, configured, CliEngineAssembler.workflowsEnabled(), null);
        List<String> choices = new ArrayList<>();
        choices.add("auto");
        choices.addAll(projection.choices());
        return new SessionHostEffortState(current, projection.effective(), choices);
    }

    /** One stable directory segment per distinct file name within a turn. */
    private static String submissionMessageId(
        SessionHostSubmission.Attachment file) {
        return StringUtils.isBlank(file.fileName())
            ? "remote" : file.fileName();
    }

    /**
     * Dedicated permission gate for one headless session in {@code projectPath}.
     *
     * <p>Every parallel headless session receives its own {@link PermissionGate}
     * instance so switching permission mode in Session A (e.g. {@code plan} or
     * {@code acceptEdits}) never mutates concurrent Session B in the same workspace.
     */
    PermissionGate gateFor(String projectPath) {
        Path cwdPath = Path.of(projectPath).toAbsolutePath().normalize();
        PermissionGate gate = new PermissionGate(
            ToolPermissionContext.builder()
                .workingDirectory(cwdPath)
                .pathContext(CliToolchainAssembler.newPermissionPathContext(
                    cwdPath, settingsRootFor(cwdPath)))
                .build());
        if (sharedGate != null) {
            gate.setMode(sharedGate.currentMode());
            gate.setBypassPermissionsModeAvailable(sharedGate.isBypassPermissionsModeAvailable());
        }
        return gate;
    }

    private static Path settingsRootFor(Path cwdPath) {
        Path original = CwdState.getOriginalCwd();
        return original != null ? original : cwdPath;
    }

    private static String loadClaudeMd(Path cwd) {
        try {
            var scanner = MemoryFileScanner.forConfigHome(
                ClaudePaths.CLAUDE_HOME,
                WorkspaceSettings.loadClaudeMdExcludes(
                    CliToolchainAssembler.settingsRootForSubAgent(cwd)), null);
            return new MemoryPromptBuilder(scanner).build(cwd, List.of(),
                Set.of(MemoryType.USER, MemoryType.PROJECT, MemoryType.LOCAL), null);
        } catch (RuntimeException failure) {
            log.log(Level.WARNING, "headless memory content load failed: {0}",
                failure.getMessage());
            return "";
        }
    }

    /** A fresh session id for open requests without one. */
    static String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /** No-op primary sink: the transcript flows through the recorder, not the hub. */
    private record NoopSink() implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
