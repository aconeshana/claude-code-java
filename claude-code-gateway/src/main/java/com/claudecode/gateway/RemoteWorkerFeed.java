package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.sessionlink.SessionLinkClient;
import com.claudecode.runtime.sessionlink.SessionLinkEndpoint;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(RemoteWorkerFeed.class);
    private static final String ACTIVATED = "session.activated";
    private static final String INTERACTION_REQUESTED = "interaction.requested";
    private static final String INTERACTION_RESOLVED = "interaction.resolved";
    private static final String COLLABORATION_CHANGED = "collaboration.changed";
    private static final String SUDO_KIND = "sudo_password";

    private final MirrorHub hub;
    private final SessionLinkClient client;
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();

    RemoteWorkerFeed(MirrorHub hub, Path socket, String token) {
        this(hub, new SessionLinkEndpoint.UnixDomain(socket), token, null);
    }

    RemoteWorkerFeed(MirrorHub hub, SessionLinkEndpoint endpoint, String token) {
        this(hub, endpoint, token, null);
    }

    RemoteWorkerFeed(
            MirrorHub hub, SessionLinkEndpoint endpoint, String token, Runnable onDisconnect) {
        this.hub = hub;
        this.client = new SessionLinkClient(endpoint, token, this::onFrame, onDisconnect);
    }

    /** Creates and connects a feed, closing it automatically if the handshake fails. */
    static RemoteWorkerFeed connect(
            MirrorHub hub,
            SessionLinkEndpoint endpoint,
            String token,
            Runnable onDisconnect) throws IOException {
        AtomicBoolean started = new AtomicBoolean();
        RemoteWorkerFeed feed = new RemoteWorkerFeed(hub, endpoint, token, () -> {
            if (started.get() && onDisconnect != null) {
                onDisconnect.run();
            }
        });
        try {
            feed.start();
            started.set(true);
            return feed;
        } catch (IOException | RuntimeException | Error failure) {
            feed.close();
            throw failure;
        }
    }

    void start() throws IOException {
        client.connect();
    }

    /**
     * Whether this link can still carry a forwarded request.
     *
     * <p>A client is single-use: its reader loop closes the client on any read
     * failure, and a closed client answers every later request with
     * {@code session-link connection closed} forever. The pool caches one feed
     * per worker socket, so when the host drops the connection (a TUI restart,
     * a {@code /resume} that re-attaches the link) that cached feed would pin a
     * dead link and every forwarded read would fail until the process exits.
     * The pool drops such a feed and dials a fresh one on the next scan.
     */
    boolean isOpen() {
        return !client.isClosed();
    }

    /**
     * Forwards one turn with everything the composer attached. Images and files
     * travel in the shape the host's {@code turn.submit} decoder reads, so a
     * picture pasted into the webui reaches the TUI as the same {@code [Image #N]}
     * chip a local paste would make instead of being dropped beside its text.
     */
    CompletableFuture<SessionLinkFrame> submit(String sessionId, SessionHostSubmission submission) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("prompt", submission.prompt());
        payload.put("message_id", submission.messageId());
        writeAttachments(payload.putArray("images"), submission.images());
        writeAttachments(payload.putArray("attachments"), submission.attachments());
        return client.request("turn.submit", sessionId, payload);
    }

    private static void writeAttachments(ArrayNode target,
            List<SessionHostSubmission.Attachment> attachments) {
        for (SessionHostSubmission.Attachment attachment : attachments) {
            ObjectNode item = target.addObject();
            if (!attachment.mimeType().isEmpty()) item.put("mime_type", attachment.mimeType());
            if (!attachment.fileName().isEmpty()) item.put("file_name", attachment.fileName());
            item.put("data", Base64.getEncoder().encodeToString(attachment.data()));
        }
    }

    CompletableFuture<SessionLinkFrame> cancelTurn(String sessionId) {
        return client.request("turn.cancel", sessionId, JsonUtils.getMapper().createObjectNode());
    }

    CompletableFuture<SessionLinkFrame> previewRewind(String sessionId, String messageId) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("message_id", messageId);
        return client.request("rewind.preview", sessionId, payload);
    }

    CompletableFuture<SessionLinkFrame> executeRewind(
            String sessionId, String messageId, String mode) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("message_id", messageId);
        payload.put("mode", mode);
        return client.request("rewind.execute", sessionId, payload);
    }

    CompletableFuture<SessionLinkFrame> respondInteraction(String sessionId, ObjectNode payload) {
        return client.request("interaction.respond", sessionId, payload);
    }

    CompletableFuture<SessionLinkFrame> getPermissionMode(String sessionId) {
        return client.request("permission.get", sessionId, JsonUtils.getMapper().createObjectNode());
    }

    CompletableFuture<SessionLinkFrame> setPermissionMode(String sessionId, String mode) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("mode", mode);
        return client.request("permission.set", sessionId, payload);
    }

    CompletableFuture<SessionLinkFrame> getModel(String sessionId) {
        return client.request("model.get", sessionId, JsonUtils.getMapper().createObjectNode());
    }

    CompletableFuture<SessionLinkFrame> setModel(String sessionId, String model) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("model", model);
        return client.request("model.set", sessionId, payload);
    }

    CompletableFuture<SessionLinkFrame> getEffort(String sessionId) {
        return client.request("effort.get", sessionId, JsonUtils.getMapper().createObjectNode());
    }

    CompletableFuture<SessionLinkFrame> setEffort(String sessionId, String effort) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("effort", effort);
        return client.request("effort.set", sessionId, payload);
    }

    CompletableFuture<SessionLinkFrame> getCollaboration(String sessionId) {
        return client.request("collaboration.get", sessionId, JsonUtils.getMapper().createObjectNode());
    }

    CompletableFuture<SessionLinkFrame> setCollaboration(
            String sessionId, String channel, boolean enabled) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("channel", channel == null ? "" : channel);
        payload.put("enabled", enabled);
        return client.request("collaboration.set", sessionId, payload);
    }

    CompletableFuture<SessionLinkFrame> listSessions() {
        return client.request("session.list", null, JsonUtils.getMapper().createObjectNode());
    }

    /** Attaches to a session once; repeated calls for an attached session are no-ops. */
    CompletableFuture<SessionLinkFrame> subscribe(String sessionId) {
        if (!subscribed.add(sessionId)) return CompletableFuture.completedFuture(null);
        log.debug("[remote-feed] subscribe session={}", sessionId);
        return client.request("session.subscribe", sessionId,
            JsonUtils.getMapper().createObjectNode()).whenComplete((_, failure) -> {
                log.debug("[remote-feed] subscribe settled session={} failure={}",
                    sessionId, failure == null ? "none" : failure.toString());
                if (failure != null) subscribed.remove(sessionId);
            });
    }

    private void onFrame(SessionLinkFrame frame) {
        log.debug("[remote-feed] frame name={} session={}", frame.name(), frame.sessionId());
        if (frame.sessionId() == null) return;
        if (ACTIVATED.equals(frame.name())) {
            subscribe(frame.sessionId());
            hub.publishSessionsChanged();
            return;
        }
        // A sudo ask shares the interaction frame names but is a password
        // prompt, not an approval: it carries no tool input and must not be
        // rendered as an allow/deny card, so it splits off by its kind tag.
        boolean sudo = Strings.CS.equals(SUDO_KIND, payloadOf(frame).path("interaction_kind").asText());
        if (INTERACTION_REQUESTED.equals(frame.name())) {
            if (sudo) {
                hub.publishSudoAsked(frame.sessionId(), RemoteInteractionMirror.sudoAsked(payloadOf(frame)));
            } else {
                hub.publishPermissionAsked(frame.sessionId(),
                    RemoteInteractionMirror.asked(payloadOf(frame)));
            }
            return;
        }
        if (INTERACTION_RESOLVED.equals(frame.name())) {
            if (sudo) {
                hub.publishSudoResolved(frame.sessionId(),
                    RemoteInteractionMirror.sudoResolved(payloadOf(frame)));
            } else {
                hub.publishPermissionResolved(frame.sessionId(),
                    RemoteInteractionMirror.resolved(payloadOf(frame)));
            }
            return;
        }
        if (COLLABORATION_CHANGED.equals(frame.name())) {
            hub.publishCcConnectChanged();
        }
        hub.publishRemote(frame.sessionId(), frame.name(), frame.payload());
    }

    private static JsonNode payloadOf(SessionLinkFrame frame) {
        return frame.payload() == null ? JsonUtils.getMapper().createObjectNode() : frame.payload();
    }

    @Override
    public void close() {
        client.close();
    }
}
