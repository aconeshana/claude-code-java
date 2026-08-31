package com.claudecode.runtime.sessionlink;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeature;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.InteractionKind;
import com.claudecode.runtime.interaction.InteractionNotImplementedException;
import com.claudecode.runtime.interaction.InteractionPresenter;
import com.claudecode.runtime.interaction.InteractionRequest;
import com.claudecode.runtime.interaction.InteractionResolution;
import com.claudecode.runtime.interaction.InteractionSupport;
import com.claudecode.runtime.interaction.InteractionUnsupported;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostCompactResult;
import com.claudecode.runtime.sessionhost.SessionHostEffortState;
import com.claudecode.runtime.sessionhost.SessionHostModelOption;
import com.claudecode.runtime.sessionhost.SessionHostModelState;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionHostSessionView;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.sessionhost.SessionHostSubmissionLedger;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.core.message.SDKMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticated NDJSON Session Link v1 server over a local Unix domain socket.
 */
@Explanation("Authenticated local transport for Session Host collaboration")
public final class SessionLinkServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionLinkServer.class);
    private static final int OUTBOUND_QUEUE_CAPACITY = 4_096;
    private static final Duration RESPONSE_WRITE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SESSION_END_ACK_TIMEOUT = Duration.ofSeconds(8);

    /** Immutable server configuration; inline token never leaves the Java process. */
    public record Config(Path socketPath, String authToken, int maxFrameBytes) {
        public Config(Path socketPath, String authToken) {
            this(socketPath, authToken, SessionLinkCodec.DEFAULT_MAX_FRAME_BYTES);
        }

        public Config {
            Objects.requireNonNull(socketPath, "socketPath");
            Objects.requireNonNull(authToken, "authToken");
            if (!socketPath.isAbsolute()) {
                throw new IllegalArgumentException("Session Link socket path must be absolute");
            }
            if (authToken.length() < 32 || authToken.length() > 4096) {
                throw new IllegalArgumentException(
                    "Session Link auth token must contain 32-4096 characters");
            }
        }
    }

    private final Config config;
    private final SessionHostRegistry sessions;
    private final InteractionCoordinator interactions;
    private final SessionCollaborationController collaboration;
    private final SessionHostSubmissionLedger submissionLedger;
    private final SessionLinkCodec codec;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList<>();
    private final Map<String, PendingSessionEndAck> sessionEndAcks = new ConcurrentHashMap<>();
    private final List<AutoCloseable> interactionRegistrations = new ArrayList<>();
    private volatile ServerSocketChannel server;

    private record PendingSessionEndAck(String sessionId, CompletableFuture<Void> acknowledged) {}

    private final class RemotePermissionPresenter implements InteractionPresenter<
            PermissionAskContext, PermissionAskCallback.Result> {
        @Override public InteractionEndpoint endpoint() {
            return InteractionEndpoint.REMOTE;
        }

        @Override public InteractionSupport support() {
            return InteractionSupport.SUPPORTED;
        }

        @Override public boolean available(String sessionId) {
            return hasAttachedConnection(sessionId);
        }

        @Override public void present(InteractionRequest<PermissionAskContext,
                PermissionAskCallback.Result> request) {
            for (Connection connection : connections) {
                if (connection.attachedSessions.containsKey(request.descriptor().sessionId())) {
                    connection.sendEvent(interactionRequested(request));
                }
            }
        }

        @Override public void resolved(
                InteractionResolution<PermissionAskCallback.Result> resolution) {
            for (Connection connection : connections) {
                if (connection.attachedSessions.containsKey(
                        resolution.descriptor().sessionId())) {
                    connection.sendEvent(interactionResolved(resolution));
                }
            }
        }
    }

    private final class RemoteSudoPasswordPresenter implements InteractionPresenter<
            SudoPasswordInteraction.Request, SudoPasswordInteraction.Result> {
        @Override public InteractionEndpoint endpoint() {
            return InteractionEndpoint.REMOTE;
        }

        @Override public InteractionSupport support() {
            return InteractionSupport.UNIMPLEMENTED;
        }

        @Override public boolean available(String sessionId) {
            return hasAttachedConnection(sessionId);
        }

        @Override public void present(InteractionRequest<SudoPasswordInteraction.Request,
                SudoPasswordInteraction.Result> request) {
            throw new InteractionNotImplementedException(
                request.descriptor(), endpoint(), "complete_in_tui");
        }

        @Override public void unsupported(InteractionUnsupported event) {
            for (Connection connection : connections) {
                if (connection.attachedSessions.containsKey(event.descriptor().sessionId())) {
                    connection.sendEvent(interactionUnsupported(event));
                }
            }
        }
    }

    private boolean hasAttachedConnection(String sessionId) {
        for (Connection connection : connections) {
            if (connection.authenticated
                    && connection.attachedSessions.containsKey(sessionId)) return true;
        }
        return false;
    }

    public SessionLinkServer(
            Config config,
            SessionHostRegistry sessions,
            InteractionCoordinator interactions) {
        this(config, sessions, interactions, new SessionCollaborationController(sessions));
    }

    public SessionLinkServer(
            Config config,
            SessionHostRegistry sessions,
            InteractionCoordinator interactions,
            SessionCollaborationController collaboration) {
        this.config = Objects.requireNonNull(config, "config");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.collaboration = Objects.requireNonNull(collaboration, "collaboration");
        this.submissionLedger = new SessionHostSubmissionLedger();
        this.codec = new SessionLinkCodec(config.maxFrameBytes());
        interactionRegistrations.add(interactions.register(
            InteractionFeatures.PERMISSION,
            new RemotePermissionPresenter()));
        interactionRegistrations.add(interactions.register(
            InteractionFeatures.USER_QUESTION,
            new RemotePermissionPresenter()));
        interactionRegistrations.add(interactions.register(
            InteractionFeatures.SUDO_PASSWORD, new RemoteSudoPasswordPresenter()));
    }

    /** Binds the socket and starts a virtual-thread accept loop. */
    public void start() throws IOException {
        if (!started.compareAndSet(false, true)) return;
        if (closed.get()) throw new IllegalStateException("Session Link server is closed");
        Path parent = config.socketPath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.deleteIfExists(config.socketPath());
        ServerSocketChannel created = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            created.bind(UnixDomainSocketAddress.of(config.socketPath()));
            restrictSocketPermissions(config.socketPath());
        } catch (IOException failure) {
            created.close();
            throw failure;
        }
        server = created;
        Thread.ofVirtual().name("session-link-accept").start(this::acceptLoop);
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                SocketChannel socket = server.accept();
                Connection connection = new Connection(socket);
                connections.add(connection);
                Thread.ofVirtual().name("session-link-client").start(connection::run);
            } catch (IOException failure) {
                if (!closed.get()) log.warn("Session Link accept failed", failure);
                return;
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ServerSocketChannel snapshot = server;
        if (snapshot != null) {
            try { snapshot.close(); } catch (IOException _) {}
        }
        for (Connection connection : connections) connection.close();
        connections.clear();
        for (AutoCloseable registration : interactionRegistrations) closeQuietly(registration);
        interactionRegistrations.clear();
        try { Files.deleteIfExists(config.socketPath()); } catch (IOException _) {}
    }

    private final class Connection implements AutoCloseable {
        private final SocketChannel socket;
        private final BufferedInputStream input;
        private final SessionLinkOutboundWriter outbound;
        private final Map<String, AttachedSession> attachedSessions = new ConcurrentHashMap<>();
        private final AtomicBoolean connectionClosed = new AtomicBoolean();
        private volatile AutoCloseable lifecycleSubscription;
        private volatile AutoCloseable collaborationSubscription;
        private boolean authenticated;

        private Connection(SocketChannel socket) throws IOException {
            this.socket = socket;
            this.input = new BufferedInputStream(Channels.newInputStream(socket));
            this.outbound = new SessionLinkOutboundWriter(
                socket, codec, OUTBOUND_QUEUE_CAPACITY, _ -> close());
            outbound.start();
        }

        private void run() {
            try {
                while (!connectionClosed.get()) {
                    SessionLinkFrame request = readFrame();
                    if (request.kind() != SessionLinkFrame.Kind.REQUEST) {
                        throw new SessionLinkProtocolException(
                            "session-link: client frames must be requests");
                    }
                    if (!authenticated && !Strings.CS.equals("link.hello", request.name())) {
                        sendError(request, "authentication_required", "authentication failed");
                        return;
                    }
                    handle(request);
                }
            } catch (EOFException _) {
                // Normal peer detach.
            } catch (SessionLinkProtocolException | IOException failure) {
                if (!connectionClosed.get()) log.debug("Session Link client disconnected", failure);
            } finally {
                close();
            }
        }

        private void handle(SessionLinkFrame request) throws IOException {
            try {
                switch (request.name()) {
                    case "link.hello" -> hello(request);
                    case "session.open" -> openSession(request);
                    case "session.list" -> listSessions(request);
                    case "session.detach" -> detachSession(request);
                    case "turn.submit" -> submitTurn(request);
                    case "interaction.respond" -> respondInteraction(request);
                    case "model.get" -> getModel(request);
                    case "model.set" -> setModel(request);
                    case "effort.get" -> getEffort(request);
                    case "effort.set" -> setEffort(request);
                    case "compact.run" -> runCompact(request);
                    case "session.ended.ack" -> acknowledgeSessionEnded(request);
                    default -> sendError(request, "unsupported_request", "unsupported request");
                }
            } catch (IllegalArgumentException | SessionLinkProtocolException failure) {
                sendError(request, "invalid_request", failure.getMessage());
            } catch (RuntimeException failure) {
                sendError(request, "host_error", safeMessage(failure));
            }
        }

        private void hello(SessionLinkFrame request) throws IOException {
            if (authenticated) {
                sendError(request, "already_authenticated", "link already authenticated");
                return;
            }
            ObjectNode payload = payload(request,
                Set.of("client", "auth_token", "collaboration_channels"));
            String token = requiredText(payload, "auth_token");
            if (!MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8),
                    config.authToken().getBytes(StandardCharsets.UTF_8))) {
                sendError(request, "authentication_failed", "authentication failed");
                close();
                return;
            }
            authenticated = true;
            JsonNode rawChannels = payload.get("collaboration_channels");
            List<String> channels = new ArrayList<>();
            if (rawChannels != null && !rawChannels.isNull()) {
                if (!rawChannels.isArray()) {
                    throw new IllegalArgumentException("collaboration_channels must be an array");
                }
                for (JsonNode channel : rawChannels) {
                    if (!channel.isTextual()) {
                        throw new IllegalArgumentException(
                            "collaboration channel names must be strings");
                    }
                    channels.add(channel.asText());
                }
            }
            collaboration.replaceAvailableChannels(channels);
            lifecycleSubscription = sessions.subscribe(event -> {
                String origin = event.origin().name().toLowerCase(Locale.ROOT);
                switch (event.type()) {
                    case ACTIVATED -> {
                        sendSessionActivated(
                            event.info(), origin, event.activationId(), event.activationGeneration());
                        sendCollaborationSnapshot(event.info(), origin);
                    }
                    case UPDATED -> sendSessionUpdated(event.info(), origin);
                    case ENDED -> sendSessionEndedAndWait(event.info(), origin, event.reason());
                }
            });
            collaborationSubscription = collaboration.subscribe(change -> {
                ObjectNode eventPayload = infoPayload(change.info());
                eventPayload.put("channel", change.channel());
                eventPayload.put("enabled", change.enabled());
                eventPayload.put("origin", change.origin().name().toLowerCase(Locale.ROOT));
                sendEvent(SessionLinkFrame.event(
                    "collaboration.changed", change.sessionId(), eventPayload));
            });
            ObjectNode response = object();
            response.put("host", "claude-code-java");
            sendResponse(request, response);
            sessions.currentActivation().ifPresent(current -> {
                sendSessionActivated(
                    current.session().info(),
                    current.origin().name().toLowerCase(Locale.ROOT),
                    current.activationId(),
                    current.activationGeneration());
                sendCollaborationSnapshot(
                    current.session().info(), current.origin().name().toLowerCase(Locale.ROOT));
            });
        }

        /**
         * Publishes the active host identity before any session-scoped state so
         * the following collaboration snapshot can reattach a locally resumed
         * session to its durable IM thread.
         */
        private void sendSessionActivated(
                SessionHostInfo info,
                String origin,
                String activationId,
                long activationGeneration) {
            ObjectNode eventPayload = infoPayload(info);
            eventPayload.put("origin", origin);
            putOptional(eventPayload, "activation_id", activationId);
            eventPayload.put("activation_generation", activationGeneration);
            sendEvent(SessionLinkFrame.event(
                "session.activated", info.id(), eventPayload));
        }

        private void sendSessionUpdated(SessionHostInfo info, String origin) {
            if (!attachedSessions.containsKey(info.id())) return;
            ObjectNode eventPayload = infoPayload(info);
            eventPayload.put("origin", origin);
            sendEvent(SessionLinkFrame.event("session.updated", info.id(), eventPayload));
        }

        /**
         * Delivers the terminal-end marker before the collaboration process is
         * stopped. The peer acknowledges only after its IM send returns.
         */
        private void sendSessionEndedAndWait(SessionHostInfo info, String origin, String reason) {
            CompletableFuture<Void> acknowledged = new CompletableFuture<>();
            String notificationId = UUID.randomUUID().toString();
            PendingSessionEndAck pending = new PendingSessionEndAck(info.id(), acknowledged);
            sessionEndAcks.put(notificationId, pending);
            ObjectNode eventPayload = infoPayload(info);
            eventPayload.put("origin", origin);
            eventPayload.put("reason", reason == null ? "" : reason);
            eventPayload.put("notification_id", notificationId);
            try {
                outbound.sendAndWait(SessionLinkFrame.event(
                    "session.ended", info.id(), eventPayload), RESPONSE_WRITE_TIMEOUT);
                acknowledged.get(SESSION_END_ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception failure) {
                log.debug("Session Link terminal-end notification was not acknowledged", failure);
            } finally {
                sessionEndAcks.remove(notificationId, pending);
            }
        }

        private void acknowledgeSessionEnded(SessionLinkFrame request) throws IOException {
            ObjectNode body = payload(request, Set.of("notification_id"));
            String sessionId = request.sessionId();
            if (StringUtils.isBlank(sessionId)) {
                throw new IllegalArgumentException("session_id is required");
            }
            String notificationId = requiredText(body, "notification_id");
            PendingSessionEndAck pending = sessionEndAcks.get(notificationId);
            if (pending != null && pending.sessionId().equals(sessionId)) {
                pending.acknowledged().complete(null);
            }
            sendResponse(request, object());
        }

        /** Replays enabled collaboration state after reconnect, /resume, and /new activation. */
        private void sendCollaborationSnapshot(SessionHostInfo info, String origin) {
            SessionCollaborationController.Selection selection = collaboration.selection(info.id());
            if (!selection.enabled()) return;
            ObjectNode collaborationPayload = infoPayload(info);
            collaborationPayload.put("channel", selection.channel());
            collaborationPayload.put("enabled", true);
            collaborationPayload.put("origin", origin);
            sendEvent(SessionLinkFrame.event(
                "collaboration.changed", info.id(), collaborationPayload));
        }

        private void openSession(SessionLinkFrame request) throws IOException {
            ObjectNode payload = payload(request,
                Set.of("requested_session_id", "work_dir", "collaboration_channel", "activation_id"));
            String requestedId = optionalText(payload, "requested_session_id");
            String workDir = requiredText(payload, "work_dir");
            String activationId = optionalText(payload, "activation_id");
            SessionHostRegistry.ActivationResult activation = sessions.openActivation(
                new SessionOpenRequest(requestedId, workDir, activationId))
                .toCompletableFuture().join();
            SessionHostSession session = activation.session();
            attach(session);
            String collaborationChannel = optionalText(payload, "collaboration_channel");
            if (StringUtils.isNotBlank(collaborationChannel)) {
                collaboration.selectRemote(session.info(), collaborationChannel);
            }
            ObjectNode response = object();
            response.put("session_id", session.info().id());
            putOptional(response, "activation_id", activation.activationId());
            response.put("activation_generation", activation.activationGeneration());
            sendResponse(request, response);
            for (InteractionRequest<?, ?> pending :
                    interactions.pendingRequests(session.info().id())) {
                if (pending.descriptor().kind() == InteractionKind.SUDO_PASSWORD) {
                    sendEvent(interactionUnsupported(new InteractionUnsupported(
                        pending.descriptor(), InteractionEndpoint.REMOTE, "complete_in_tui")));
                } else {
                    sendEvent(interactionRequested(permissionRequest(pending)));
                }
            }
        }

        private void listSessions(SessionLinkFrame request) throws IOException {
            payload(request, Set.of());
            ArrayNode array = object().putArray("sessions");
            for (SessionHostInfo info : sessions.list()) array.add(infoPayload(info));
            ObjectNode response = object();
            response.set("sessions", array);
            sendResponse(request, response);
        }

        private void detachSession(SessionLinkFrame request) throws IOException {
            payload(request, Set.of());
            AttachedSessionView attachment = requireAttachment(request);
            closeQuietly(attachedSessions.remove(attachment.sessionId()));
            sendResponse(request, object());
        }

        private void submitTurn(SessionLinkFrame request) throws IOException {
            AttachedSessionView attachment = requireAttachment(request);
            ObjectNode payload = payload(request,
                Set.of("prompt", "message_id", "images", "attachments"));
            SessionHostSessionView session = attachment.activeView();
            SessionHostSubmission submission = new SessionHostSubmission(
                requiredText(payload, "prompt"), optionalText(payload, "message_id"),
                attachments(payload.get("images")), attachments(payload.get("attachments")));
            submissionLedger.submit(attachment.sessionId(), submission.messageId(),
                () -> session.submit(submission)).toCompletableFuture().join();
            sendResponse(request, object());
        }

        private void getModel(SessionLinkFrame request) throws IOException {
            payload(request, Set.of());
            sendResponse(request, modelPayload(requireAttachment(request).activeView().models().get()));
        }

        private void setModel(SessionLinkFrame request) throws IOException {
            AttachedSessionView attachment = requireAttachment(request);
            ObjectNode body = payload(request, Set.of("model"));
            String selected = validateModelName(requiredText(body, "model"));
            sendResponse(request,
                modelPayload(attachment.activeView().models().set(selected)));
        }

        private void getEffort(SessionLinkFrame request) throws IOException {
            payload(request, Set.of());
            sendResponse(request, effortPayload(requireAttachment(request).activeView().efforts().get()));
        }

        private void setEffort(SessionLinkFrame request) throws IOException {
            AttachedSessionView attachment = requireAttachment(request);
            ObjectNode body = payload(request, Set.of("effort"));
            String selected = requiredText(body, "effort").trim().toLowerCase(Locale.ROOT);
            if (!Set.of("auto", "none", "minimal", "low", "medium", "high", "xhigh", "max")
                    .contains(selected)) {
                throw new IllegalArgumentException("unsupported effort level");
            }
            sendResponse(request,
                effortPayload(attachment.activeView().efforts().set(selected)));
        }

        private void runCompact(SessionLinkFrame request) throws IOException {
            AttachedSessionView attachment = requireAttachment(request);
            ObjectNode body = payload(request, Set.of("instructions"));
            String instructions = validateCompactInstructions(optionalText(body, "instructions"));
            SessionHostCompactResult result = attachment.activeView().compacts()
                .compact(instructions).toCompletableFuture().join();
            ObjectNode response = object();
            response.put("message", result.message());
            sendResponse(request, response);
        }

        private void respondInteraction(SessionLinkFrame request) throws IOException {
            String sessionId = requireAttachment(request).sessionId();
            ObjectNode payload = payload(request,
                Set.of("request_id", "behavior", "updated_input", "message"));
            String behavior = requiredText(payload, "behavior");
            JsonNode updatedInput = payload.get("updated_input");
            String message = optionalText(payload, "message");
            PermissionAskCallback.Result result = switch (behavior) {
                case "allow" -> updatedInput != null && !updatedInput.isNull()
                    ? PermissionAskCallback.Result.allowWithInputAndFeedback(updatedInput, message)
                    : (message == null ? PermissionAskCallback.Result.allow()
                        : PermissionAskCallback.Result.allowWithFeedback(message));
                case "deny" -> message == null
                    ? PermissionAskCallback.Result.deny()
                    : PermissionAskCallback.Result.denyWithDirectMessage(message);
                default -> throw new IllegalArgumentException("unsupported interaction behavior");
            };
            String requestId = requiredText(payload, "request_id");
            boolean accepted = interactions.respond(InteractionFeatures.USER_QUESTION,
                requestId, sessionId, result, InteractionEndpoint.REMOTE)
                || interactions.respond(InteractionFeatures.PERMISSION,
                    requestId, sessionId, result, InteractionEndpoint.REMOTE);
            if (!accepted) {
                sendError(request, "stale_interaction", "interaction is no longer pending");
                return;
            }
            sendResponse(request, object());
        }

        private void attach(SessionHostSession session) {
            String sessionId = session.info().id();
            AttachedSession existing = attachedSessions.get(sessionId);
            if (existing != null && existing.observes(session)) return;
            AttachedSession candidate = new AttachedSession(session);
            AttachedSession retained = attachedSessions.compute(sessionId, (_, current) -> {
                if (current != null && current.observes(session)) return current;
                closeQuietly(current);
                return candidate;
            });
            if (retained != candidate) candidate.close();
        }

        private AttachedSessionView requireAttachment(SessionLinkFrame request) {
            AttachedSession attachment = request.sessionId() == null
                ? null : attachedSessions.get(request.sessionId());
            if (attachment == null) {
                throw new IllegalArgumentException("session is not attached");
            }
            return attachment;
        }

        /**
         * Non-owning command view of a connection-owned session attachment.
         *
         * <p>Request handlers borrow this view and must not close it. The enclosing
         * connection retains lifecycle ownership through {@code attachedSessions},
         * matching the {@code McpConnectionView} ownership boundary.
         */
        private interface AttachedSessionView {
            String sessionId();
            SessionHostSessionView activeView();
        }

        /**
         * Connection-owned attachment to one registry-owned session.
         *
         * <p>The full session is used only while establishing the event subscription.
         * Commands subsequently borrow a non-owning {@link SessionHostSessionView}
         * from the registry, so an attachment cannot keep a stale session operational
         * after the application switches conversations.
         */
        private final class AttachedSession implements AttachedSessionView, AutoCloseable {
            private final String sessionId;
            private final SessionEventHub eventSource;
            private final AutoCloseable eventSubscription;
            private final AtomicBoolean attachmentClosed = new AtomicBoolean();

            private AttachedSession(SessionHostSession session) {
                sessionId = session.info().id();
                eventSource = session.events();
                SessionSink delegate = new SessionLinkEventSink(sessionId, Connection.this::sendEvent);
                eventSubscription = eventSource.subscribe(activeOnly(delegate));
            }

            @Override
            public String sessionId() { return sessionId; }

            private boolean observes(SessionHostSession session) {
                return eventSource == session.events();
            }

            @Override
            public SessionHostSessionView activeView() {
                return sessions.borrowCurrent(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("session is not active"));
            }

            private SessionSink activeOnly(SessionSink delegate) {
                return new SessionSink() {
                    private boolean active() { return sessions.isCurrent(sessionId); }
                    @Override public void onTurnStart(UserInput input) {
                        if (active()) delegate.onTurnStart(input);
                    }
                    @Override public void onMessage(SDKMessage msg) {
                        if (active()) delegate.onMessage(msg);
                    }
                    @Override public void onError(Throwable error, boolean userCancel) {
                        if (active()) delegate.onError(error, userCancel);
                    }
                    @Override public void onTurnComplete(TurnOutcome outcome) {
                        if (active()) delegate.onTurnComplete(outcome);
                    }
                    @Override public void onIdle() {
                        if (active()) delegate.onIdle();
                    }
                };
            }

            @Override
            public void close() {
                if (!attachmentClosed.compareAndSet(false, true)) return;
                closeQuietly(eventSubscription);
            }
        }

        private SessionLinkFrame readFrame() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (true) {
                int next = input.read();
                if (next < 0) {
                    if (line.size() == 0) throw new EOFException();
                    break;
                }
                if (next == '\n') break;
                if (next == '\r') continue;
                if (line.size() >= config.maxFrameBytes()) {
                    throw new SessionLinkProtocolException(
                        "session-link: frame exceeds configured limit");
                }
                line.write(next);
            }
            if (line.size() == 0) {
                throw new SessionLinkProtocolException("session-link: empty frame");
            }
            return codec.decode(line.toByteArray());
        }

        private void sendEvent(SessionLinkFrame event) {
            if (!outbound.offer(event)) close();
        }

        private void sendResponse(SessionLinkFrame request, JsonNode payload) throws IOException {
            outbound.sendAndWait(SessionLinkFrame.response(
                request.id(), request.name(), request.sessionId(), payload),
                RESPONSE_WRITE_TIMEOUT);
        }

        private void sendError(
                SessionLinkFrame request, String code, String message) throws IOException {
            outbound.sendAndWait(
                SessionLinkFrame.error(request.id(), request.name(), code, message),
                RESPONSE_WRITE_TIMEOUT);
        }

        @Override
        public void close() {
            if (!connectionClosed.compareAndSet(false, true)) return;
            closeQuietly(lifecycleSubscription);
            closeQuietly(collaborationSubscription);
            for (AttachedSession attachment : attachedSessions.values()) {
                closeQuietly(attachment);
            }
            attachedSessions.clear();
            outbound.close();
            try { socket.close(); } catch (IOException _) {}
            connections.remove(this);
        }
    }

    private static SessionLinkFrame interactionRequested(
            InteractionRequest<PermissionAskContext, PermissionAskCallback.Result> request) {
        PermissionAskContext context = request.payload();
        ObjectNode payload = object();
        payload.put("request_id", request.descriptor().id());
        payload.put("tool_name", context.toolName());
        payload.put("tool_input", toolInputSummary(context.input()));
        if (context.input() != null && context.input().isObject()) {
            payload.set("input_raw", context.input());
        }
        ArrayNode questions = questions(context.input());
        if (!questions.isEmpty()) payload.set("questions", questions);
        putOptional(payload, "decision_reason_type", context.decisionReasonType());
        putOptional(payload, "decision_reason_detail", context.decisionReasonDetail());
        putOptional(payload, "suggestion_rule_content", context.suggestionRuleContent());
        putOptional(payload, "suggestion_label", context.suggestionLabel());
        putOptional(payload, "worker_id", context.workerId());
        putOptional(payload, "worker_color", context.workerColor());
        putOptional(payload, "destructive_warning", context.destructiveWarning());
        putOptional(payload, "blocked_path", context.blockedPath());
        putOptional(payload, "custom_message", context.customMessage());
        putOptional(payload, "tool_description", context.toolDescription());
        return SessionLinkFrame.event(
            "interaction.requested", request.descriptor().sessionId(), payload);
    }

    private static SessionLinkFrame interactionResolved(
            InteractionResolution<PermissionAskCallback.Result> resolution) {
        ObjectNode payload = object();
        payload.put("request_id", resolution.descriptor().id());
        payload.put("behavior", resolution.result().allowed() ? "allow" : "deny");
        payload.put("origin", resolution.origin().name().toLowerCase(Locale.ROOT));
        if (resolution.result().updatedInput() != null
                && !resolution.result().updatedInput().isNull()) {
            payload.set("updated_input", resolution.result().updatedInput());
        }
        if (resolution.result().feedback() != null) {
            payload.put("message", resolution.result().feedback());
        }
        return SessionLinkFrame.event("interaction.resolved",
            resolution.descriptor().sessionId(), payload);
    }

    private static SessionLinkFrame interactionUnsupported(InteractionUnsupported event) {
        ObjectNode payload = object();
        payload.put("request_id", event.descriptor().id());
        payload.put("interaction_kind",
            event.descriptor().kind().name().toLowerCase(Locale.ROOT));
        payload.put("action", event.action());
        return SessionLinkFrame.event("interaction.unsupported",
            event.descriptor().sessionId(), payload);
    }

    private static InteractionRequest<PermissionAskContext, PermissionAskCallback.Result>
            permissionRequest(InteractionRequest<?, ?> request) {
        if (!(request.payload() instanceof PermissionAskContext context)) {
            throw new IllegalArgumentException("interaction request is not permission-like");
        }
        InteractionFeature<PermissionAskContext, PermissionAskCallback.Result> feature =
            request.descriptor().kind() == InteractionKind.USER_QUESTION
                ? InteractionFeatures.USER_QUESTION : InteractionFeatures.PERMISSION;
        return new InteractionRequest<>(request.descriptor(), feature, context);
    }

    private static ArrayNode questions(JsonNode input) {
        ArrayNode result = object().arrayNode();
        JsonNode raw = input == null ? null : input.get("questions");
        if (raw == null || !raw.isArray()) return result;
        for (JsonNode question : raw) {
            if (!question.isObject() || !question.path("question").isTextual()) continue;
            ObjectNode mapped = result.addObject();
            mapped.put("question", question.path("question").asText());
            if (question.path("header").isTextual()) {
                mapped.put("header", question.path("header").asText());
            }
            mapped.put("multi_select",
                question.path("multiSelect").asBoolean(
                    question.path("multi_select").asBoolean(false)));
            ArrayNode options = mapped.putArray("options");
            JsonNode rawOptions = question.get("options");
            if (rawOptions == null || !rawOptions.isArray()) continue;
            for (JsonNode option : rawOptions) {
                if (!option.isObject() || !option.path("label").isTextual()) continue;
                ObjectNode mappedOption = options.addObject();
                mappedOption.put("label", option.path("label").asText());
                if (option.path("description").isTextual()) {
                    mappedOption.put("description", option.path("description").asText());
                }
            }
        }
        return result;
    }

    private static String toolInputSummary(JsonNode input) {
        if (input == null || input.isNull()) return "";
        for (String field : List.of("command", "file_path", "path", "query", "description")) {
            JsonNode value = input.get(field);
            if (value != null && value.isTextual()) return value.asText();
        }
        return input.toString();
    }

    private static List<SessionHostSubmission.Attachment> attachments(JsonNode raw) {
        if (raw == null || raw.isNull()) return List.of();
        if (!raw.isArray()) throw new IllegalArgumentException("attachments must be an array");
        List<SessionHostSubmission.Attachment> result = new ArrayList<>();
        for (JsonNode item : raw) {
            if (!item.isObject()) throw new IllegalArgumentException("attachment must be an object");
            Set<String> allowed = Set.of("mime_type", "file_name", "data");
            item.fieldNames().forEachRemaining(field -> {
                if (!allowed.contains(field)) {
                    throw new IllegalArgumentException("unknown attachment field " + field);
                }
            });
            String encoded = requiredText((ObjectNode) item, "data");
            byte[] data;
            try {
                data = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("attachment data is not valid base64", failure);
            }
            result.add(new SessionHostSubmission.Attachment(
                optionalText((ObjectNode) item, "mime_type"),
                optionalText((ObjectNode) item, "file_name"), data));
        }
        return List.copyOf(result);
    }

    private static ObjectNode payload(SessionLinkFrame request, Set<String> allowed) {
        JsonNode raw = request.payload();
        if (raw == null || raw.isNull()) raw = object();
        if (!(raw instanceof ObjectNode object)) {
            throw new SessionLinkProtocolException("session-link: payload must be an object");
        }
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new SessionLinkProtocolException(
                    "session-link: unrecognized payload field " + field);
            }
        });
        return object;
    }

    private static ObjectNode infoPayload(SessionHostInfo info) {
        ObjectNode payload = object();
        payload.put("id", info.id());
        if (!StringUtils.isBlank(info.summary())) payload.put("summary", info.summary());
        if (info.messageCount() > 0) payload.put("message_count", info.messageCount());
        if (info.modifiedAt() != null) {
            payload.put("modified_at", DateTimeFormatter.ISO_INSTANT.format(info.modifiedAt()));
        }
        if (!StringUtils.isBlank(info.gitBranch())) payload.put("git_branch", info.gitBranch());
        if (!StringUtils.isBlank(info.workDir())) payload.put("work_dir", info.workDir());
        return payload;
    }

    private static ObjectNode modelPayload(SessionHostModelState state) {
        ObjectNode payload = object();
        if (!StringUtils.isBlank(state.current())) payload.put("current", state.current());
        ArrayNode models = payload.putArray("models");
        for (SessionHostModelOption option : state.models()) {
            ObjectNode mapped = models.addObject();
            mapped.put("name", option.name());
            putOptional(mapped, "label", option.label());
            putOptional(mapped, "description", option.description());
            putOptional(mapped, "alias", option.alias());
            if (option.defaultOption()) mapped.put("default", true);
        }
        return payload;
    }

    private static ObjectNode effortPayload(SessionHostEffortState state) {
        ObjectNode payload = object();
        payload.put("current", state.current());
        if (!StringUtils.isBlank(state.effective())) payload.put("effective", state.effective());
        ArrayNode efforts = payload.putArray("efforts");
        state.efforts().forEach(efforts::add);
        return payload;
    }

    private static String validateModelName(String model) {
        String selected = model.trim();
        if (selected.isEmpty() || selected.length() > 1024) {
            throw new IllegalArgumentException("model must contain 1-1024 characters");
        }
        if (selected.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("model contains control characters");
        }
        return selected;
    }

    private static String requiredText(ObjectNode object, String field) {
        String value = optionalText(object, field);
        if (value == null) throw new IllegalArgumentException("missing field " + field);
        return value;
    }

    private static String optionalText(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("field " + field + " must be text");
        return value.asText();
    }

    private static String validateCompactInstructions(String value) {
        if (value == null) return "";
        if (value.length() > 32_768) {
            throw new IllegalArgumentException("compact instructions are too long");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                throw new IllegalArgumentException("compact instructions contain control characters");
            }
        }
        return value.trim();
    }

    private static void putOptional(ObjectNode target, String field, String value) {
        if (StringUtils.isNotBlank(value)) target.put(field, value);
    }

    private static ObjectNode object() {
        return JsonUtils.getMapper().createObjectNode();
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return StringUtils.isBlank(message) ? "host operation failed" : message;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception _) {}
    }

    private static void restrictSocketPermissions(Path socket) {
        try {
            Files.setPosixFilePermissions(socket, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException _) {
            // Unix-domain sockets are only used on local Unix-like platforms;
            // POSIX mode support can still be absent on an unusual filesystem.
        }
    }
}
