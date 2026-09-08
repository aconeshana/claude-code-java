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
}
