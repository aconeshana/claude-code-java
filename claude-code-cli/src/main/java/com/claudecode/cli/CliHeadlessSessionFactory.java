package com.claudecode.cli;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.state.CwdState;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.sessionhost.RemoteAttachmentStore;
import com.claudecode.runtime.sessionhost.SessionHostEffortController;
import com.claudecode.runtime.sessionhost.SessionHostEffortState;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostModelController;
import com.claudecode.runtime.sessionhost.SessionHostModelOptions;
import com.claudecode.runtime.sessionhost.SessionHostModelState;
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
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolRegistry;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    private final StreamingClient client;
    private final ToolRegistry toolRegistry;
    private final QuerySessionFactory querySessionFactory;
    private final PermissionGate sharedGate;
    private final String resolvedModel;
    private final String mainCwd;
    private final CustomModelCatalog customModels;
    private final boolean showBuiltInModelFamilies;

    CliHeadlessSessionFactory(
            StreamingClient client,
            ToolRegistry toolRegistry,
            QuerySessionFactory querySessionFactory,
            PermissionGate sharedGate,
            String resolvedModel,
            String mainCwd,
            CustomModelCatalog customModels,
            boolean showBuiltInModelFamilies) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.querySessionFactory = querySessionFactory;
        this.sharedGate = sharedGate;
        this.resolvedModel = resolvedModel;
        this.mainCwd = mainCwd;
        this.customModels = customModels;
        this.showBuiltInModelFamilies = showBuiltInModelFamilies;
    }

    /** One assembled headless session: the host record plus its engine. */
    record Assembled(
            SessionHostSession host,
            QuerySession engine,
            AbortController abort,
            String projectPath) {}

    /**
     * Assembles one headless session bound to {@code projectPath}, optionally
     * resuming {@code priorMessages}.
     */
    Assembled assemble(String sessionId, String projectPath, List<Message> priorMessages) {
        SessionIdentity identity = SessionIdentity.of(sessionId);
        AbortController abort = new AbortController();
        SessionEventHub events = new SessionEventHub(new NoopSink(), failure ->
            log.log(Level.WARNING, "headless session observer failed", failure));

        QuerySessionSpec.Builder builder = QuerySessionSpec.builder()
            .llmClient(client)
            .model(resolvedModel)
            .tools(toolRegistry.getAll().stream().map(Tool::name).toList())
            .toolExecutor(toolRegistry)
            .workingDirectory(projectPath)
            .abortController(abort)
            .sessionIdentity(identity)
            .claudeMdContentSupplier(() -> loadClaudeMd(Path.of(projectPath)));
        if (priorMessages != null && !priorMessages.isEmpty()) {
            builder.initialMessages(priorMessages);
        }
        QuerySession engine = querySessionFactory.create(builder.build());
        TranscriptRecorder recorder = new TranscriptRecorder(new SessionManager(projectPath));
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

        SessionHostInfo info = new SessionHostInfo(
            sessionId, projectPath, "", 0, Instant.now(), "");
        HeadlessTurnDriver driver = new HeadlessTurnDriver(engine, events,
            file -> RemoteAttachmentStore.persist(projectPath, sessionId,
                submissionMessageId(file), file).toString());
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
            });
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

    /** The permission gate for a headless session in {@code projectPath}. */
    PermissionGate gateFor(String projectPath) {
        // Cross-project sessions need their own gate: the shared gate's
        // working-directory checks would fall every other-project path to ASK,
        // and a headless session has no UI to answer with. The gate matches
        // the main gate's construction (CliToolchainAssembler.createPermissionGate)
        // with only the cwd/settings roots swapped to this session's project.
        if (Path.of(projectPath).toAbsolutePath().normalize()
                .equals(Path.of(mainCwd).toAbsolutePath().normalize())) {
            return sharedGate;
        }
        Path cwdPath = Path.of(projectPath).toAbsolutePath().normalize();
        return new PermissionGate(
            ToolPermissionContext.builder()
                .workingDirectory(cwdPath)
                .pathContext(CliToolchainAssembler.newPermissionPathContext(
                    cwdPath, settingsRootFor(cwdPath)))
                .build());
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
