package com.claudecode.gateway;

import com.claudecode.core.message.Message;
import java.util.List;
import java.util.Optional;

/**
 * Consumer-owned boundary for one session's authoritative message snapshot.
 *
 * <p>The snapshot endpoint ({@code GET /api/sessions/{id}/messages}) serves
 * the current message list of any known session — an open headless session's
 * live engine messages, or the on-disk transcript of the active TUI session
 * or any listed session id. The gateway must not depend on the session
 * module, so the CLI composition root injects the resolution; the gateway
 * only projects {@code Message} rows into the alignment spec's snapshot
 * shape.
 */
public interface GatewaySessionMessagesPort {

    /**
     * The authoritative current message list for {@code sessionId}.
     *
     * @param sessionId the session id from the URL path
     * @param headlessSessionId the id of an open headless session when
     *        {@code sessionId} names one; live engine messages win over disk
     * @return the messages, or empty when the session is unknown
     */
    default Optional<List<Message>> messages(String sessionId, String headlessSessionId) {
        return Optional.empty();
    }

    /**
     * One sub-agent's own conversation, restored from its sidechain transcript.
     *
     * <p>A sub-agent's work is not part of its parent's message list: it lives
     * in the session's sidecar tree
     * ({@code <project>/<sessionId>/subagents/agent-<agentId>.jsonl}), which the
     * gateway cannot read for itself (it has no session-module dependency). The
     * CLI composition root resolves the file and parses it with the same
     * {@code SessionStorage.getAgentTranscript} the TUI's local-agent viewer
     * uses, so both surfaces read one implementation.
     *
     * <p>The rows come back in the same {@link Message} shape the parent
     * snapshot serves, so the gateway projects them through the identical
     * wire projection and a client renders a sub-agent transcript with its
     * ordinary row renderer.
     *
     * @param sessionId the owning session's id
     * @param agentId the sub-agent id (the {@code agentId} a completed
     *        {@code Task}/{@code Agent} result carried)
     * @return the sub-agent's messages, or empty when the agent is unknown or
     *         its transcript is absent/unreadable
     */
    default Optional<List<Message>> agentMessages(String sessionId, String agentId) {
        return Optional.empty();
    }
}
