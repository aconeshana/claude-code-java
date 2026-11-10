package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionlink.SessionLinkClient;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Follows one interactive process's Session Link: every live event of every
 * session that process hosts is republished into the local {@link MirrorHub}.
 *
 * <p>The peer announces its current session with {@code session.activated}; the
 * feed answers by subscribing to it, because a link delivers a session's turn
 * events only after that session is attached. A later activation (the TUI's
 * {@code /resume} or {@code /new}) is handled the same way.
 */
final class RemoteWorkerFeed implements AutoCloseable {

    private static final String ACTIVATED = "session.activated";

    private final MirrorHub hub;
    private final SessionLinkClient client;
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();

    RemoteWorkerFeed(MirrorHub hub, Path socket, String token) {
        this.hub = hub;
        this.client = new SessionLinkClient(socket, token, this::onFrame);
    }

    void start() throws IOException {
        client.connect();
    }

    CompletableFuture<SessionLinkFrame> submit(String sessionId, String prompt, String messageId) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("prompt", prompt);
        payload.put("message_id", messageId);
        return client.request("turn.submit", sessionId, payload);
    }

    CompletableFuture<SessionLinkFrame> cancelTurn(String sessionId) {
        return client.request("turn.cancel", sessionId, JsonUtils.getMapper().createObjectNode());
    }

    CompletableFuture<SessionLinkFrame> respondInteraction(String sessionId, ObjectNode payload) {
        return client.request("interaction.respond", sessionId, payload);
    }

    CompletableFuture<SessionLinkFrame> listSessions() {
        return client.request("session.list", null, JsonUtils.getMapper().createObjectNode());
    }

    /** Attaches to a session once; repeated calls for an attached session are no-ops. */
    CompletableFuture<SessionLinkFrame> subscribe(String sessionId) {
        if (!subscribed.add(sessionId)) return CompletableFuture.completedFuture(null);
        return client.request("session.subscribe", sessionId,
            JsonUtils.getMapper().createObjectNode()).whenComplete((_, failure) -> {
                if (failure != null) subscribed.remove(sessionId);
            });
    }

    private void onFrame(SessionLinkFrame frame) {
        if (frame.sessionId() == null) return;
        if (ACTIVATED.equals(frame.name())) {
            subscribe(frame.sessionId());
            return;
        }
        hub.publishRemote(frame.sessionId(), frame.name(), frame.payload());
    }

    @Override
    public void close() {
        client.close();
    }
}
