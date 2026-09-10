package com.claudecode.cli;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.state.CwdState;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.sessionhost.RemoteAttachmentStore;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
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
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolRegistry;
import org.apache.commons.lang3.StringUtils;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
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

    CliHeadlessSessionFactory(
            StreamingClient client,
            ToolRegistry toolRegistry,
            QuerySessionFactory querySessionFactory,
            PermissionGate sharedGate,
            String resolvedModel,
            String mainCwd) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.querySessionFactory = querySessionFactory;
        this.sharedGate = sharedGate;
        this.resolvedModel = resolvedModel;
        this.mainCwd = mainCwd;
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
        engine.execution().setTranscriptSink(recorder);

        SessionHostInfo info = new SessionHostInfo(
            sessionId, projectPath, "", 0, Instant.now(), "");
        HeadlessTurnDriver driver = new HeadlessTurnDriver(engine, events,
            file -> RemoteAttachmentStore.persist(projectPath, sessionId,
                submissionMessageId(file), file).toString());
        SessionHostSession host = new SessionHostSession(
            info, events, driver::submit);
        return new Assembled(host, engine, abort, projectPath);
    }

    /** One stable directory segment per distinct file name within a turn. */
    private static String submissionMessageId(
            SessionHostSubmission.Attachment file) {
        String name = StringUtils.isBlank(file.fileName())
            ? "remote" : file.fileName();
        return name;
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
