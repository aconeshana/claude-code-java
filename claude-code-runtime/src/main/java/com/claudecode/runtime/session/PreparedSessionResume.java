package com.claudecode.runtime.session;

import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.message.Message;
import com.claudecode.core.metrics.SessionMetricsEvent;
import java.util.List;

/**
 * Immutable hand-off between the blocking transcript-read phase and the ordered active-session
 * switch phase.
 */
public record PreparedSessionResume(
    SessionResumeRequest request,
    String outgoingSessionId,
    List<Message> messages,
    List<ToolResultBudget.Replacement> contentReplacements,
    List<SessionMetricsEvent> sessionMetrics,
    List<String> metricTurnIds,
    String restoredCwd,
    boolean crossProject
) {
    public PreparedSessionResume {
        messages = List.copyOf(messages);
        contentReplacements = List.copyOf(contentReplacements);
        sessionMetrics = List.copyOf(sessionMetrics);
        metricTurnIds = List.copyOf(metricTurnIds);
    }

    public PreparedSessionResume(SessionResumeRequest request, String outgoingSessionId,
                                 List<Message> messages,
                                 List<ToolResultBudget.Replacement> contentReplacements,
                                 String restoredCwd, boolean crossProject) {
        this(request, outgoingSessionId, messages, contentReplacements, List.of(), List.of(),
            restoredCwd, crossProject);
    }
}
