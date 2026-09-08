package com.claudecode.cli;

import com.claudecode.core.message.Message;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.gateway.GatewaySessionMessagesPort;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private record Entry(
            SessionHostSession host,
            CliHeadlessSessionFactory.Assembled assembled,
            String taskId,
            Instant openedAt) {}

    CliHeadlessGatewaySessions(CliHeadlessSessionFactory factory, String mainCwd) {
        this.factory = factory;
        this.mainCwd = mainCwd;
    }

    @Override
    public Optional<SessionHostSession> find(String sessionId) {
        Entry entry = sessions.get(sessionId);
        return Optional.ofNullable(entry).map(Entry::host);
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
        String projectPath = resolveProjectPath(request.projectPath());

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
        List<Message> priorMessages = request.projectPath() == null
            ? resumeMessages(id, projectPath) : List.of();
        boolean resumed = !priorMessages.isEmpty();
        factory.gateFor(projectPath).setMode(PermissionMode.DEFAULT);
        CliHeadlessSessionFactory.Assembled assembled =
            factory.assemble(id, projectPath, priorMessages);
        TaskState task = TaskRegistry.global().store()
            .create(TaskType.WEB_SESSION, webSessionLabel(projectPath));
        // PENDING → RUNNING so the pill lists it and close can reach a
        // terminal state through the state machine.
        TaskRegistry.global().store().updateStatus(task.id(), TaskStatus.RUNNING);
        sessions.put(id, new Entry(assembled.host(), assembled, task.id(), Instant.now()));
        // The tasks dialog's kill stops the session through this action; the
        // entry is removed by then, so close() re-runs harmlessly as a no-op.
        TaskRegistry.global().registerWebSession(task.id(), () -> close(id));
        return new Opened(assembled.host(), projectPath, resumed);
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
                        return Optional.of(
                            entry.assembled().engine().conversation().getMessages());
                    }
                }
                if (StringUtils.isBlank(sessionId)) return Optional.empty();
                // Same-project transcripts resolve through the main cwd; a
                // cross-project headless id was already served live above.
                Path file = new SessionManager(mainCwd).getSessionFile(sessionId);
                if (!Files.isRegularFile(file)) return Optional.empty();
                try {
                    return Optional.of(new SessionStorage().readMessages(file));
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
