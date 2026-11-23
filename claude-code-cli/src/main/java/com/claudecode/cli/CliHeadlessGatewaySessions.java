package com.claudecode.cli;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.message.Message;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.gateway.GatewaySessionMessagesPort;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TranscriptLoader;
import com.claudecode.session.TranscriptMessageCache;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

/**
 * The CLI implementation of the gateway's headless-session port: owns the
 * open sessions, assembles each one through {@link CliHeadlessSessionFactory},
 * and registers every live session in the global {@code TaskRegistry} so the
 * TUI footer pill counts it and the tasks dialog can view and stop it.
 */
final class CliHeadlessGatewaySessions implements GatewayHeadlessSessions {

    private final CliHeadlessSessionFactory factory;
    private final String mainCwd;
    private final ConcurrentMap<String, Entry> sessions = new ConcurrentHashMap<>();

    /**
     * Total transcript size kept parsed for the messages port. A webui page
     * load reads one session's transcript dozens of times (messages, context
     * timeline, one changed-files card per turn); a few long sessions fit.
     */
    private static final long TRANSCRIPT_CACHE_BYTES = 256L * 1024 * 1024;

    private final TranscriptMessageCache transcripts =
        new TranscriptMessageCache(new SessionStorage(), TRANSCRIPT_CACHE_BYTES);

    private record Entry(
            SessionHostSession host,
            CliHeadlessSessionFactory.Assembled assembled,
            String taskId,
            Instant openedAt,
            boolean locallyOwned) {}

    CliHeadlessGatewaySessions(CliHeadlessSessionFactory factory, String mainCwd) {
        this.factory = factory;
        this.mainCwd = mainCwd;
    }

    @Override
    public Optional<SessionHostSession> find(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || !ownsHistory(entry)) return Optional.empty();
        return Optional.of(livingHost(entry));
    }

    /**
     * Whether this process, rather than some other TUI, is the one that runs
     * this entry's turns.
     *
     * <p>{@code open} also mints an entry when a client hands it a session id
     * belonging to a TUI elsewhere: the handle exists so the client can address
     * that session, but its conversation stays in the owning process. The
     * gateway's submission path asks {@link #find} first and only forwards to
     * the owning TUI over Session Link when it answers empty, so reporting such
     * an entry here routed every later submission into this process's own
     * engine — a second, forked branch of the same conversation.
     *
     * <p>The two cases cannot be told apart by "does the engine hold messages":
     * a resumed session also holds them. Ownership is therefore decided once, in
     * {@code open}, from which directory's transcript the id resolves to.
     */
    private boolean ownsHistory(Entry entry) {
        return entry.locallyOwned();
    }

    /**
     * The entry's host record with its message count re-read from the live engine.
     *
     * <p>{@link SessionHostInfo} is an immutable snapshot taken when the session was
     * assembled — at that instant a resumed session has no messages yet and a new one
     * never will. Returning the frozen record makes every catalog row for an open
     * headless session report {@code message_count: 0} forever, even though the
     * snapshot endpoint serves the real conversation. The engine, unlike the record,
     * stays current, so the count is projected from it per read. The other fields are
     * still whatever assembly saw; only the count is a live fact.
     */
    private static SessionHostSession livingHost(Entry entry) {
        SessionHostSession host = entry.host();
        SessionHostInfo frozen = host.info();
        int messages = entry.assembled().engine().conversation().getMessages().size();
        if (messages == frozen.messageCount()) return host;
        return new SessionHostSession(
            new SessionHostInfo(frozen.id(), frozen.workDir(), frozen.summary(), messages,
                frozen.modifiedAt(), frozen.gitBranch()),
            host.events(), host.submitter(),
            host.models(), host.efforts(), host.compacts(),
            host.permissions(), host.cancels());
    }

    /** The project directory an open headless session works in, or empty when none is open under that id. */
    Optional<String> projectPathOf(String sessionId) {
        Entry entry = sessionId == null ? null : sessions.get(sessionId);
        return entry == null ? Optional.empty() : Optional.of(entry.assembled().projectPath());
    }

    @Override
    public List<GatewayHeadlessSessions.SessionListing> list() {
        return sessions.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new GatewayHeadlessSessions.SessionListing(
                entry.getKey(), entry.getValue().assembled().projectPath(),
                entry.getValue().openedAt()))
            .toList();
    }

    @Override
    public Opened open(OpenRequest request) {
        String sessionId = StringUtils.defaultString(request.sessionId()).strip();
        // An existing session id names a session some TUI may own elsewhere on
        // this machine; its transcript then lives under that worker's project,
        // not under the directory this gateway was launched from. Resolve it
        // there first so the handle reports (and resumes) the real project.
        String owner = owningWorkDir(sessionId);
        String projectPath = resolveProjectPath(
            StringUtils.defaultIfBlank(request.projectPath(), owner));
        // A transcript another process reported owning is a handle on that
        // process's session, not a session this one may run turns in. A
        // session id nobody holds a transcript for is this process's to mint:
        // answering "remote" for it would leave every brand-new id unrunnable.
        boolean remote = owner != null && !Paths.get(owner).normalize()
            .equals(Paths.get(mainCwd).toAbsolutePath().normalize());

        Entry existing = sessions.get(sessionId);
        if (existing != null) {
            return new Opened(existing.host(), existing.assembled().projectPath(), false);
        }
        String id = sessionId.isEmpty() ? CliHeadlessSessionFactory.newSessionId() : sessionId;
        if (sessions.containsKey(id)) {
            throw new IllegalArgumentException("session is already open: " + id);
        }
        // A known disk session resumes message-level: the prior conversation
        // seeds the engine so the client can continue where it left off.
        // resumeMessages() itself falls back to an empty list when no transcript
        // exists for `id`, so a genuinely new session is unaffected.
        List<Message> priorMessages = resumeMessages(id, projectPath);
        boolean resumed = !priorMessages.isEmpty();
        CliHeadlessSessionFactory.Assembled assembled =
            factory.assemble(id, projectPath, priorMessages);
        TaskState task = TaskRegistry.global().store()
            .create(TaskType.WEB_SESSION, webSessionLabel(projectPath));
        // PENDING → RUNNING so the pill lists it and close can reach a
        // terminal state through the state machine.
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.RUNNING);
        sessions.put(id, new Entry(assembled.host(), assembled, task.id(), Instant.now(), !remote));
        // The tasks dialog's kill stops the session through this action; the
        // entry is removed by then, so close() re-runs harmlessly as a no-op.
        TaskRegistry.global().registerWebSession(task.id(), () -> close(id));
        return new Opened(assembled.host(), projectPath, resumed);
    }

    /**
     * The working directory of a process that holds this session's transcript,
     * or null when the session is new here.
     *
     * <p>This process's own project is checked first; a miss falls back to the
     * directories running workers report, which is where a session another TUI
     * owns keeps its transcript.
     */
    private String owningWorkDir(String sessionId) {
        if (sessionId.isEmpty()) return null;
        Optional<String> local = transcriptUnder(sessionId, mainCwd) ? Optional.of(mainCwd)
            : Optional.empty();
        if (local.isPresent()) return local.get();
        return new WebWorkerDirectory(ClaudePaths.WEB_WORKERS_DIR).scan().stream()
            .map(WebWorkerRegistration::workDir)
            .filter(workDir -> workDir != null && transcriptUnder(sessionId, workDir))
            .findFirst()
            .orElse(null);
    }

    /** Whether {@code workDir} holds a readable transcript for {@code sessionId}. */
    private static boolean transcriptUnder(String sessionId, String workDir) {
        if (StringUtils.isBlank(workDir)) return false;
        try {
            return Files.isRegularFile(
                new SessionManager(workDir).getSessionFile(sessionId));
        } catch (RuntimeException _) {
            return false;
        }
    }

    /** The persisted conversation for {@code sessionId}, or empty when none. */
    private List<Message> resumeMessages(String sessionId, String projectPath) {
        try {
            return new SessionStorage().readMessages(
                new SessionManager(projectPath).getSessionFile(sessionId));
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    @Override
    public boolean close(String sessionId) {
        Entry removed = sessions.remove(sessionId);
        if (removed == null) return false;
        TaskRegistry.global().unregisterWebSession(removed.taskId());
        removed.assembled().abort().abort("gateway_session_closed");
        TaskRegistry.global().store().updateStatus(removed.taskId(), TaskStatus.COMPLETED);
        return true;
    }

    @Override
    public void closeAll() {
        for (String sessionId : List.copyOf(sessions.keySet())) {
            close(sessionId);
        }
    }

    /**
     * The live transcript path for a {@code WEB_SESSION} task id, or null for
     * any other task. The TUI's agent-transcript resolver routes here first so
     * viewing a web session reads its own project's main transcript.
     */
    Path transcriptPathForTask(String taskId) {
        for (Entry entry : sessions.values()) {
            if (Strings.CS.equals(entry.taskId(), taskId)) {
                return new SessionManager(entry.assembled().projectPath())
                    .getSessionFile(entry.host().info().id());
            }
        }
        return null;
    }

    /** Whether {@code taskId} belongs to one of the open web sessions. */
    boolean isWebSessionTask(String taskId) {
        return sessions.values().stream()
            .anyMatch(entry -> Strings.CS.equals(entry.taskId(), taskId));
    }

    /** The open headless session's live engine rows, or empty when none is open under that id. */
    Optional<List<Message>> liveMessages(String sessionId) {
        Entry entry = sessionId == null ? null : sessions.get(sessionId);
        return entry == null
            ? Optional.empty()
            : Optional.of(entry.assembled().engine().conversation().getMessages());
    }

    /** The open headless session's live engine, or empty when none is open under that id. */
    Optional<QuerySession> liveEngine(String sessionId) {
        Entry entry = sessionId == null ? null : sessions.get(sessionId);
        return entry == null ? Optional.empty() : Optional.of(entry.assembled().engine());
    }

    /** Shared tool registry backing sessions opened by this gateway. */
    com.claudecode.tools.ToolRegistry toolRegistry() {
        return factory.toolRegistry();
    }

    /**
     * The snapshot adapter for the gateway's messages endpoint: an open
     * headless session serves its live in-memory conversation (engine
     * messages win over the transcript, which may lag mid-turn); any other
     * known session id falls back to its on-disk transcript.
     */
    GatewaySessionMessagesPort messagesPort(String mainCwd) {
        return new GatewaySessionMessagesPort() {
            @Override public Optional<List<Message>> messages(
                    String sessionId, String headlessSessionId) {
                if (headlessSessionId != null) {
                    Entry entry = sessions.get(headlessSessionId);
                    if (entry != null) {
                        List<Message> live =
                            entry.assembled().engine().conversation().getMessages();
                        // An entry can exist for a session this process merely
                        // opened a handle on — a TUI elsewhere hosts it, so the
                        // local engine never received its history. Answering
                        // with that empty conversation hid the real transcript
                        // behind a 200; fall through to disk instead.
                        if (!live.isEmpty()) return Optional.of(live);
                    }
                }
                if (StringUtils.isBlank(sessionId)) return Optional.empty();
                // Same-project transcripts resolve through the main cwd; a
                // cross-project headless id was already served live above.
                Path file = new SessionManager(mainCwd).getSessionFile(sessionId);
                if (!Files.isRegularFile(file)) return Optional.empty();
                try {
                    return Optional.of(transcripts.read(file));
                } catch (RuntimeException _) {
                    return Optional.empty();
                }
            }

            @Override public Optional<List<Message>> messagesInWorkDir(
                    String sessionId, String workDir) {
                if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(workDir)) {
                    return Optional.empty();
                }
                Path file = new SessionManager(workDir).getSessionFile(sessionId);
                if (!Files.isRegularFile(file)) return Optional.empty();
                try {
                    return Optional.of(transcripts.read(file));
                } catch (RuntimeException _) {
                    return Optional.empty();
                }
            }

            /**
             * A sub-agent's transcript lives in the session's sidecar tree
             * ({@code <project>/<sessionId>/subagents/agent-<agentId>.jsonl}).
             * The resolution and the parse are deliberately the ones the TUI's
             * local-agent viewer already uses
             * ({@code CliInteractiveSessionAdapter.readAgentMessages}), so the
             * two surfaces cannot drift on which rows belong to an agent.
             */
            @Override public Optional<List<Message>> agentMessages(
                    String sessionId, String agentId) {
                if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(agentId)) {
                    return Optional.empty();
                }
                // Live in-memory display transcript wins while an agent is
                // actively running (or just resumed), matching the TUI viewer.
                var liveHandle = TaskRegistry.global().getAgentHandle(agentId).orElse(null);
                if (liveHandle != null) {
                    List<Message> live = liveHandle.displayTranscript();
                    if (!live.isEmpty()) return Optional.of(live);
                }
                Path transcript = new SessionManager(mainCwd)
                    .getAgentTranscriptPath(sessionId, agentId);
                if (!Files.isRegularFile(transcript)) return Optional.empty();
                try {
                    return new SessionStorage().getAgentTranscript(transcript, agentId)
                        .map(TranscriptLoader.AgentTranscript::messages);
                } catch (RuntimeException _) {
                    return Optional.empty();
                }
            }
        };
    }

    private String resolveProjectPath(String requested) {
        String path = StringUtils.defaultIfBlank(requested, mainCwd).strip();
        Path resolved = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("project_path is not a directory: " + resolved);
        }
        return resolved.toString();
    }

    private static String webSessionLabel(String projectPath) {
        return "web session " + projectPath;
    }
}
