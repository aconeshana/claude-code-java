package com.claudecode.runtime.session;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.message.Message;
import com.claudecode.core.metrics.SessionMetricsEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Headless orchestration for an in-process session switch.
 */
public final class SessionLifecycle {




    public record TranscriptSnapshot(
            List<Message> messages,
            List<ToolResultBudget.Replacement> contentReplacements,
            List<SessionMetricsEvent> sessionMetrics,
            List<String> metricTurnIds) {
        public TranscriptSnapshot {
            messages = List.copyOf(messages);
            contentReplacements = List.copyOf(contentReplacements);
            sessionMetrics = List.copyOf(sessionMetrics);
            metricTurnIds = List.copyOf(metricTurnIds);
        }

        public TranscriptSnapshot(List<Message> messages,
                                  List<ToolResultBudget.Replacement> contentReplacements) {
            this(messages, contentReplacements, List.of(), List.of());
        }
    }

    /** Blocking transcript reader; production also performs unresolved-tool cleanup. */
    @FunctionalInterface
    public interface TranscriptReader {
        TranscriptSnapshot read(Path sessionFile) throws Exception;
    }

    /**
     * Side-effect ports around the pure session identity/message switch. Implementations
     * are normally assembled by the CLI from services, session persistence, and tools.
     */
    public interface Ports {



        void captureCost(String sessionId);

        void saveCost(String sessionId);

        void beforeSwitch();

        void restoreCost(String sessionId);

        void restoreToolResultBudget(String sessionId, List<Message> messages,
                                     List<ToolResultBudget.Replacement> replacements);

        void loadEngineMessages(List<Message> messages);

        void afterSwitch(Path sessionFile, List<Message> messages, String cwd);

        /**
         * Blocking half of a cross-project switch, invoked from {@link #prepare} before
         * anything is committed. Implementations validate the target directory and warm
         * whatever the first post-switch turn would otherwise compute synchronously;
         * throwing here aborts the resume with the target project untouched.
         */
        default void prepareProjectSwitch(String targetCwd) throws Exception { }

        /**
         * Event-loop half of a cross-project switch, invoked from {@link #activate} before
         * any session state is touched so the transcript sink is already pointed at the
         * target project when the new messages arrive. Must stay cheap — no blocking I/O.
         */
        default void applyProjectSwitch(String targetCwd) { }
    }

    private final QuerySession queryEngine;
    private final TranscriptReader transcriptReader;
    private final Ports ports;

    public SessionLifecycle(QuerySession queryEngine, TranscriptReader transcriptReader, Ports ports) {
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine");
        this.transcriptReader = Objects.requireNonNull(transcriptReader, "transcriptReader");
        this.ports = Objects.requireNonNull(ports, "ports");
    }

    /**
     * Runs the blocking half of resume and returns an immutable switch snapshot.
     */
    public PreparedSessionResume prepare(SessionResumeRequest request, String currentCwd)
        throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.sessionId(), "request.sessionId");
        Objects.requireNonNull(request.sessionFile(), "request.sessionFile");

        TranscriptSnapshot transcript = transcriptReader.read(request.sessionFile());

        String projectPath = request.projectPath();
        boolean hasProjectPath = StringUtils.isNotBlank(projectPath);
        String restoredCwd = hasProjectPath ? projectPath : currentCwd;
        boolean crossProject = hasProjectPath && !projectPath.equals(currentCwd);
        // Validated and warmed before any cost state is captured, so a rejected target
        // directory leaves the outgoing session exactly as it was.
        if (crossProject) ports.prepareProjectSwitch(restoredCwd);

        String outgoingSessionId = queryEngine.conversation().getSessionId();
        ports.captureCost(request.sessionId());
        ports.saveCost(outgoingSessionId);

        return new PreparedSessionResume(
            request, outgoingSessionId, transcript.messages(), transcript.contentReplacements(),
            transcript.sessionMetrics(), transcript.metricTurnIds(),
            restoredCwd, crossProject);
    }

    /**
     * Runs the ordered event-loop half of resume. {@code afterMessagesLoaded} is the
     * adapter seam for clearing/replaying its view after the engine has adopted the
     * new message list but before post-switch state restoration completes.
     */
    public void activate(PreparedSessionResume prepared,
                         Consumer<List<Message>> afterMessagesLoaded) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(afterMessagesLoaded, "afterMessagesLoaded");

        SessionResumeRequest request = prepared.request();
        // First, so every downstream write (transcript sink, cost store, post-switch
        // restoration) already resolves against the target project.
        if (prepared.crossProject()) ports.applyProjectSwitch(prepared.restoredCwd());
        ports.beforeSwitch();
        queryEngine.conversation().switchToSession(request.sessionId());
        queryEngine.execution().restoreSessionMetrics(
            request.sessionId(), prepared.sessionMetrics(), prepared.metricTurnIds());
        ports.restoreCost(request.sessionId());
        ports.restoreToolResultBudget(request.sessionId(), prepared.messages(),
            prepared.contentReplacements());
        ports.loadEngineMessages(prepared.messages());
        afterMessagesLoaded.accept(prepared.messages());
        ports.afterSwitch(request.sessionFile(), prepared.messages(), prepared.restoredCwd());

    }

    /** Persists cost state before a caller creates a fresh session identity. */
    public void saveCost(String sessionId) {
        ports.saveCost(sessionId);
    }

    /**
     * Performs the cost-aware identity-only switch used by command paths that
     * already loaded or branched their own message state.
     */
    public void switchIdentity(String newSessionId) {
        ports.captureCost(newSessionId);
        ports.saveCost(queryEngine.conversation().getSessionId());
        queryEngine.conversation().switchToSession(newSessionId);
        ports.restoreCost(newSessionId);
    }
}
