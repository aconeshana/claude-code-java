package com.claudecode.runtime.sessionlink;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.InteractionPresenter;
import com.claudecode.runtime.interaction.InteractionRequest;
import com.claudecode.runtime.interaction.InteractionSupport;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.claudecode.runtime.sessionhost.*;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionLinkServerTest {

    @TempDir Path tempDir;

    @Test
    void remoteSudoIsReportedAsUnsupportedWithoutLeakingRequestPayload() throws Exception {
        SessionHostSession session = session("session-sudo", new ArrayList<>());
        SessionHostRegistry registry = registry(session);
        registry.activateLocal(session);
        InteractionCoordinator interactions =
            new InteractionCoordinator(() -> "session-sudo");
        interactions.register(InteractionFeatures.SUDO_PASSWORD,
            new InteractionPresenter<>() {
                @Override public InteractionEndpoint endpoint() {
                    return InteractionEndpoint.LOCAL;
                }
                @Override public InteractionSupport support() {
                    return InteractionSupport.SUPPORTED;
                }
                @Override public boolean available(String sessionId) { return true; }
                @Override public void present(
                        InteractionRequest<SudoPasswordInteraction.Request,
                            SudoPasswordInteraction.Result> request) {
                    interactions.respond(InteractionFeatures.SUDO_PASSWORD,
                        request.descriptor().id(), request.descriptor().sessionId(),
                        SudoPasswordInteraction.Result.cancelled(), endpoint());
                }
            });
        Path socket = tempDir.resolve("sudo-unsupported.sock");

        try (SessionLinkServer server = new SessionLinkServer(
                new SessionLinkServer.Config(socket,
                    "0123456789abcdef0123456789abcdef"), registry, interactions)) {
            server.start();
            try (TestClient client = TestClient.connect(socket)) {
                client.send(request("hello-sudo", "link.hello", null,
                    object().put("client", "cc-connect")
                        .put("auth_token", "0123456789abcdef0123456789abcdef")));
                assertEquals("link.hello", client.read().name());
                assertEquals("session.activated", client.read().name());
                client.send(request("open-sudo", "session.open", null,
                    object().put("requested_session_id", "session-sudo")
                        .put("work_dir", "/project")));
                assertEquals("session.open", client.read().name());

                CompletableFuture<SudoPasswordInteraction.Result> result =
                    CompletableFuture.supplyAsync(() -> interactions.request(
                        new SudoPasswordInteraction.Request(
                            "/usr/bin/sudo", "sudo launchctl limit maxfiles 65536")));

                SessionLinkFrame unsupported = client.read();
                assertEquals("interaction.unsupported", unsupported.name());
                assertEquals("sudo_password",
                    unsupported.payload().path("interaction_kind").asText());
                assertEquals("complete_in_tui",
                    unsupported.payload().path("action").asText());
                assertFalse(unsupported.payload().toString().contains("launchctl"));
                assertInstanceOf(SudoPasswordInteraction.Result.Cancelled.class,
                    result.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void attachedSessionReceivesMetadataUpdateWithoutReactivation() throws Exception {
        SessionHostSession session = session("session-title", new ArrayList<>());
        SessionHostRegistry registry = registry(session);
        registry.activateLocal(session);
        Path socket = tempDir.resolve("metadata-update.sock");

        try (SessionLinkServer server = new SessionLinkServer(
                new SessionLinkServer.Config(socket, "0123456789abcdef0123456789abcdef"),
                registry, new InteractionCoordinator(() -> "session-title"))) {
            server.start();
            try (TestClient client = TestClient.connect(socket)) {
                client.send(request("hello-title", "link.hello", null,
                    object().put("client", "cc-connect")
                        .put("auth_token", "0123456789abcdef0123456789abcdef")));
                assertEquals("link.hello", client.read().name());
                assertEquals("session.activated", client.read().name());

                client.send(request("open-title", "session.open", null,
                    object().put("requested_session_id", "session-title")
                        .put("work_dir", "/project")));
                assertEquals("session.open", client.read().name());

                SessionHostSession titled = new SessionHostSession(
                    new SessionHostInfo("session-title", "/project", "Readable title", 1,
                        null, ""),
                    session.events(), session.submitter());
                registry.refreshLocal(titled);

                SessionLinkFrame updated = client.read();
                assertEquals("session.updated", updated.name());
                assertEquals("session-title", updated.sessionId());
                assertEquals("Readable title", updated.payload().path("summary").asText());
            }
        }
    }

    @Test
    void sessionOpenCarriesActivationIdentityAndGenerationThroughNativeResume() throws Exception {
        SessionHostSession resumed = session("session-resumed", new ArrayList<>());
        AtomicReference<SessionHostRegistry> registryRef = new AtomicReference<>();
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                registryRef.get().activateLocal(resumed);
                return CompletableFuture.completedFuture(resumed);
            }
            @Override public List<SessionHostInfo> list() { return List.of(resumed.info()); }
        });
        registryRef.set(registry);
        Path socket = tempDir.resolve("causal-open.sock");
        long generation;

        try (SessionLinkServer server = new SessionLinkServer(
                new SessionLinkServer.Config(socket, "0123456789abcdef0123456789abcdef"),
                registry, new InteractionCoordinator(() -> "session-resumed"))) {
            server.start();
            try (TestClient client = TestClient.connect(socket)) {
                client.send(request("hello-causal", "link.hello", null,
                    object().put("client", "cc-connect")
                        .put("auth_token", "0123456789abcdef0123456789abcdef")));
                assertEquals("link.hello", client.read().name());

                client.send(request("open-causal", "session.open", null,
                    object().put("requested_session_id", "session-resumed")
                        .put("work_dir", "/project")
                        .put("activation_id", "activation-causal-1")));

                SessionLinkFrame activated = client.read();
                assertEquals("session.activated", activated.name());
                assertEquals("remote", activated.payload().path("origin").asText());
                assertEquals("activation-causal-1",
                    activated.payload().path("activation_id").asText());
                generation = activated.payload().path("activation_generation").asLong();
                assertTrue(generation > 0);

                SessionLinkFrame opened = client.read();
                assertEquals("session.open", opened.name());
                assertEquals("session-resumed", opened.payload().path("session_id").asText());
                assertEquals("activation-causal-1", opened.payload().path("activation_id").asText());
                assertEquals(generation,
                    opened.payload().path("activation_generation").asLong());
            }
            try (TestClient reconnected = TestClient.connect(socket)) {
                reconnected.send(request("hello-reconnected", "link.hello", null,
                    object().put("client", "cc-connect")
                        .put("auth_token", "0123456789abcdef0123456789abcdef")));
                assertEquals("link.hello", reconnected.read().name());
                SessionLinkFrame snapshot = reconnected.read();
                assertEquals("session.activated", snapshot.name());
                assertEquals("remote", snapshot.payload().path("origin").asText());
                assertEquals("activation-causal-1",
                    snapshot.payload().path("activation_id").asText());
                assertEquals(generation,
                    snapshot.payload().path("activation_generation").asLong());
            }
        }
    }

    @Test
    void realUnixSocketMultiplexesTurnOutputAndNativeInteraction() throws Exception {
        List<SessionHostSubmission> submissions = new ArrayList<>();
        SessionHostSession session = session("session-1", submissions);
        SessionHostRegistry registry = registry(session);
        registry.activateLocal(session);
        SessionCollaborationController collaboration =
            new SessionCollaborationController(registry);
        InteractionCoordinator interactions = new InteractionCoordinator(() -> "session-1");
        Path socket = tempDir.resolve("session-link.sock");

        try (SessionLinkServer server = new SessionLinkServer(
                new SessionLinkServer.Config(socket, "0123456789abcdef0123456789abcdef"),
                registry, interactions, collaboration)) {
            server.start();
            try (TestClient client = TestClient.connect(socket)) {
            ObjectNode hello = object().put("client", "cc-connect")
                .put("auth_token", "0123456789abcdef0123456789abcdef");
            hello.putArray("collaboration_channels").add("feishu");
            client.send(request("hello-1", "link.hello", null, hello));
            assertEquals("link.hello", client.read().name());
            assertEquals("session.activated", client.read().name());
            assertEquals(List.of("feishu"), collaboration.availableChannels());

            collaboration.selectCurrent("feishu");
            SessionLinkFrame selected = client.read();
            assertEquals("collaboration.changed", selected.name());
            assertEquals("feishu", selected.payload().path("channel").asText());
            assertTrue(selected.payload().path("enabled").asBoolean());

            // /resume republishes the active Session Host identity. The selected
            // collaboration channel must immediately follow it so the persisted
            // Feishu thread is restored before subsequent PTY output.
            registry.activateLocal(session);
            SessionLinkFrame reactivated = client.read();
            assertEquals("session.activated", reactivated.name());
            assertEquals("session-1", reactivated.sessionId());
            SessionLinkFrame resumedCollaboration = client.read();
            assertEquals("collaboration.changed", resumedCollaboration.name());
            assertEquals("session-1", resumedCollaboration.sessionId());
            assertEquals("feishu", resumedCollaboration.payload().path("channel").asText());
            assertTrue(resumedCollaboration.payload().path("enabled").asBoolean());
            assertEquals("local", resumedCollaboration.payload().path("origin").asText());

            client.send(request("open-1", "session.open", null,
                object().put("requested_session_id", "session-1").put("work_dir", "/project")
                    .put("collaboration_channel", "feishu")));
            SessionLinkFrame opened = client.read();
            assertEquals("session-1", opened.payload().path("session_id").asText());

            client.send(request("turn-1", "turn.submit", "session-1",
                object().put("prompt", "from feishu").put("message_id", "om_1")));
            assertEquals("turn.submit", client.read().name());
            assertEquals("from feishu", submissions.getFirst().prompt());

            client.send(request("turn-1-retry", "turn.submit", "session-1",
                object().put("prompt", "from feishu").put("message_id", "om_1")));
            assertEquals("turn.submit", client.read().name());
            assertEquals(1, submissions.size(), "retried IM message must not execute twice");

            session.events().onMessage(new SDKMessage.Assistant(
                new AssistantMessage("a1", AssistantContent.of("m1", List.of(new TextBlock("hi")))),
                Usage.EMPTY));
            SessionLinkFrame output = client.read();
            assertEquals("output.text", output.name());
            assertEquals("hi", output.payload().path("content").asText());

            CompletableFuture<PermissionAskCallback.Result> permission =
                CompletableFuture.supplyAsync(() -> interactions.ask(
                    PermissionAskContext.builder("Bash", object().put("command", "pwd"))
                        .toolUseId("tool-1")
                        .decisionReason("rule", "Bash(pwd)")
                        .suggestion("pwd", "pwd in this project")
                        .destructiveWarning("May inspect private paths")
                        .blockedPath("/private")
                        .customMessage("Review cwd")
                        .toolDescription("Runs a shell command")
                        .build()));
            SessionLinkFrame requested = client.read();
            assertEquals("interaction.requested", requested.name());
            assertEquals("Bash", requested.payload().path("tool_name").asText());
            assertEquals("rule", requested.payload().path("decision_reason_type").asText());
            assertEquals("Bash(pwd)", requested.payload().path("decision_reason_detail").asText());
            assertEquals("May inspect private paths",
                requested.payload().path("destructive_warning").asText());
            assertEquals("/private", requested.payload().path("blocked_path").asText());
            assertEquals("Review cwd", requested.payload().path("custom_message").asText());
            assertEquals("Runs a shell command",
                requested.payload().path("tool_description").asText());

            client.send(request("respond-1", "interaction.respond", "session-1",
                object().put("request_id", requested.payload().path("request_id").asText())
                    .put("behavior", "allow")));
            assertEquals("interaction.resolved", client.read().name());
            assertEquals("interaction.respond", client.read().name());
            assertTrue(permission.get(2, TimeUnit.SECONDS).allowed());

            ObjectNode askInput = object();
            askInput.putArray("questions").addObject()
                .put("question", "Which database?")
                .put("header", "Database")
                .put("multiSelect", false)
                .putArray("options").addObject().put("label", "PostgreSQL");
            CompletableFuture<PermissionAskCallback.Result> askQuestion =
                CompletableFuture.supplyAsync(() -> interactions.ask(
                    PermissionAskContext.simple("AskUserQuestion", askInput, "tool-ask-1")));
            SessionLinkFrame askRequested = client.read();
            assertEquals("interaction.requested", askRequested.name());
            ObjectNode askUpdatedInput = askInput.deepCopy();
            askUpdatedInput.putObject("answers").put("Which database?", "PostgreSQL");
            assertTrue(interactions.respond(InteractionFeatures.USER_QUESTION,
                askRequested.payload().path("request_id").asText(), "session-1",
                PermissionAskCallback.Result.allowWithInput(askUpdatedInput),
                InteractionEndpoint.LOCAL));
            SessionLinkFrame askResolved = client.read();
            assertEquals("interaction.resolved", askResolved.name());
            assertEquals("local", askResolved.payload().path("origin").asText());
            assertEquals("PostgreSQL", askResolved.payload().path("updated_input")
                .path("answers").path("Which database?").asText());
            assertEquals(askUpdatedInput, askQuestion.get(2, TimeUnit.SECONDS).updatedInput());

            CompletableFuture<Void> ended = CompletableFuture.runAsync(
                () -> registry.endLocal("terminal_exit"));
            SessionLinkFrame sessionEnded = client.read();
            assertEquals("session.ended", sessionEnded.name());
            assertEquals("session-1", sessionEnded.sessionId());
            assertEquals("terminal_exit", sessionEnded.payload().path("reason").asText());
            String notificationId = sessionEnded.payload().path("notification_id").asText();
            assertFalse(StringUtils.isBlank(notificationId));
            client.send(request("wrong-ended-ack", "session.ended.ack", "session-1",
                object().put("notification_id", "stale-notification")));
            assertEquals("session.ended.ack", client.read().name());
            assertFalse(ended.isDone(), "a stale connection notification must not release the waiter");
            client.send(request("ended-ack", "session.ended.ack", "session-1",
                object().put("notification_id", notificationId)));
            assertEquals("session.ended.ack", client.read().name());
            ended.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void modelGetAndSetAreAttachedSessionScoped() throws Exception {
        AtomicReference<String> model = new AtomicReference<>("sonnet");
        SessionHostSession session = session("session-model", new ArrayList<>(),
            new SessionHostModelController() {
                @Override public SessionHostModelState get() {
                    return new SessionHostModelState(
                        model.get(), List.of(
                            new SessionHostModelOption(
                                "sonnet", "Sonnet 4.6", "Best for everyday tasks", "sol", false),
                            new SessionHostModelOption(
                                "opus", "Opus 4.6", "Most capable", false)));
                }

                @Override public SessionHostModelState set(
                        String selected) {
                    model.set(selected);
                    return get();
                }
            });
        SessionHostRegistry registry = registry(session);
        registry.activateLocal(session);
        Path socket = tempDir.resolve("model.sock");

        try (SessionLinkServer server = new SessionLinkServer(
                new SessionLinkServer.Config(socket, "0123456789abcdef0123456789abcdef"),
                registry, new InteractionCoordinator(() -> "session-model"))) {
            server.start();
            try (TestClient client = TestClient.connect(socket)) {
                client.send(request("hello-model", "link.hello", null,
                    object().put("client", "cc-connect")
                        .put("auth_token", "0123456789abcdef0123456789abcdef")));
                assertEquals("link.hello", client.read().name());
                assertEquals("session.activated", client.read().name());

                client.send(request("open-model", "session.open", null,
                    object().put("requested_session_id", "session-model")
                        .put("work_dir", "/project")));
                assertEquals("session.open", client.read().name());

                client.send(request("get-model", "model.get", "session-model", object()));
                SessionLinkFrame current = client.read();
                assertEquals("sonnet", current.payload().path("current").asText());
                assertEquals("opus", current.payload().path("models").path(1).path("name").asText());
                assertEquals("sol", current.payload().path("models").path(0).path("alias").asText());

                client.send(request("set-model", "model.set", "session-model",
                    object().put("model", "opus")));
                SessionLinkFrame changed = client.read();
                assertEquals("opus", changed.payload().path("current").asText());
                assertEquals("opus", model.get());

                client.send(request("set-empty", "model.set", "session-model",
                    object().put("model", "")));
                SessionLinkFrame invalid = client.read();
                assertEquals(SessionLinkFrame.Kind.ERROR, invalid.kind());
                assertEquals("invalid_request", invalid.error().code());

                client.send(request("detached-model", "model.get", "other-session", object()));
                SessionLinkFrame detached = client.read();
                assertEquals(SessionLinkFrame.Kind.ERROR, detached.kind());
                assertEquals("invalid_request", detached.error().code());
            }
        }
    }

    @Test
    void effortGetAndSetAreAttachedSessionScopedAndSupportAuto() throws Exception {
        AtomicReference<String> effort = new AtomicReference<>(null);
        SessionHostSession base = session("session-effort", new ArrayList<>());
        SessionHostSession session = new SessionHostSession(
            base.info(), base.events(), base.submitter(), base.models(),
            new SessionHostEffortController() {
                @Override public SessionHostEffortState get() {
                    String current = effort.get();
                    return new SessionHostEffortState(
                        current == null ? "auto" : current,
                        current == null ? "high" : current,
                        List.of("auto", "none", "minimal", "low", "medium", "high", "xhigh", "max"));
                }

                @Override public SessionHostEffortState set(
                        String selected) {
                    effort.set(Strings.CS.equals("auto", selected) ? null : selected);
                    return get();
                }
            });
        SessionHostRegistry registry = registry(session);
        registry.activateLocal(session);
        Path socket = tempDir.resolve("effort.sock");

        try (SessionLinkServer server = new SessionLinkServer(
                new SessionLinkServer.Config(socket, "0123456789abcdef0123456789abcdef"),
                registry, new InteractionCoordinator(() -> "session-effort"))) {
            server.start();
            try (TestClient client = TestClient.connect(socket)) {
                client.send(request("hello-effort", "link.hello", null,
                    object().put("client", "cc-connect")
                        .put("auth_token", "0123456789abcdef0123456789abcdef")));
                assertEquals("link.hello", client.read().name());
                assertEquals("session.activated", client.read().name());

                client.send(request("open-effort", "session.open", null,
                    object().put("requested_session_id", "session-effort")
                        .put("work_dir", "/project")));
                assertEquals("session.open", client.read().name());

                client.send(request("get-effort", "effort.get", "session-effort", object()));
                SessionLinkFrame current = client.read();
                assertEquals("auto", current.payload().path("current").asText());
                assertEquals("high", current.payload().path("effective").asText());
                assertEquals("xhigh", current.payload().path("efforts").path(6).asText());

                client.send(request("set-effort", "effort.set", "session-effort",
                    object().put("effort", "medium")));
                SessionLinkFrame changed = client.read();
                assertEquals("medium", changed.payload().path("current").asText());
                assertEquals("medium", effort.get());

                client.send(request("auto-effort", "effort.set", "session-effort",
                    object().put("effort", "auto")));
                assertEquals("auto", client.read().payload().path("current").asText());
                assertNull(effort.get());

                client.send(request("none-effort", "effort.set", "session-effort",
                    object().put("effort", "none")));
                assertEquals("none", client.read().payload().path("current").asText());
                assertEquals("none", effort.get());

                client.send(request("minimal-effort", "effort.set", "session-effort",
                    object().put("effort", "minimal")));
                assertEquals("minimal", client.read().payload().path("current").asText());
                assertEquals("minimal", effort.get());

                client.send(request("bad-effort", "effort.set", "session-effort",
                    object().put("effort", "ultra")));
                assertEquals(SessionLinkFrame.Kind.ERROR, client.read().kind());

                client.send(request("detached-effort", "effort.get", "other-session", object()));
                SessionLinkFrame detached = client.read();
                assertEquals(SessionLinkFrame.Kind.ERROR, detached.kind());
                assertEquals("invalid_request", detached.error().code());
            }
        }
    }

    @Test
    void compactRunIsAttachedSessionScopedAndPreservesCustomInstructions() throws Exception {
        AtomicReference<String> instructions = new AtomicReference<>();
        SessionHostSession base = session("session-compact", new ArrayList<>());
        SessionHostSession session = new SessionHostSession(
            base.info(), base.events(), base.submitter(), base.models(), base.efforts(),
            value -> {
                instructions.set(value);
                return CompletableFuture.completedFuture(
                    new SessionHostCompactResult("Compacted"));
            });
        SessionHostRegistry registry = registry(session);
        registry.activateLocal(session);
        Path socket = tempDir.resolve("compact.sock");

        try (SessionLinkServer server = new SessionLinkServer(
                new SessionLinkServer.Config(socket, "0123456789abcdef0123456789abcdef"),
                registry, new InteractionCoordinator(() -> "session-compact"))) {
            server.start();
            try (TestClient client = TestClient.connect(socket)) {
                client.send(request("hello-compact", "link.hello", null,
                    object().put("client", "cc-connect")
                        .put("auth_token", "0123456789abcdef0123456789abcdef")));
                assertEquals("link.hello", client.read().name());
                assertEquals("session.activated", client.read().name());

                client.send(request("open-compact", "session.open", null,
                    object().put("requested_session_id", "session-compact")
                        .put("work_dir", "/project")));
                assertEquals("session.open", client.read().name());

                client.send(request("run-compact", "compact.run", "session-compact",
                    object().put("instructions", "keep the migration decisions")));
                SessionLinkFrame compacted = client.read();
                assertEquals("compact.run", compacted.name());
                assertEquals("Compacted", compacted.payload().path("message").asText());
                assertEquals("keep the migration decisions", instructions.get());

                client.send(request("bad-compact", "compact.run", "session-compact",
                    object().put("instructions", "bad\u0000instruction")));
                SessionLinkFrame invalid = client.read();
                assertEquals(SessionLinkFrame.Kind.ERROR, invalid.kind());
                assertEquals("invalid_request", invalid.error().code());

                client.send(request("detached-compact", "compact.run", "other-session",
                    object().put("instructions", "")));
                SessionLinkFrame detached = client.read();
                assertEquals(SessionLinkFrame.Kind.ERROR, detached.kind());
                assertEquals("invalid_request", detached.error().code());
            }
        }
    }

    @Test
    void reattachingSameIdReplacesTheOwnedEventSubscription() throws Exception {
        SessionHostSession first = session("session-1", new ArrayList<>());
        SessionHostRegistry registry = registry(first);
        registry.activateLocal(first);
        Path socket = tempDir.resolve("reattach.sock");

        try (SessionLinkServer server = new SessionLinkServer(
                new SessionLinkServer.Config(socket, "0123456789abcdef0123456789abcdef"),
                registry, new InteractionCoordinator(() -> "session-1"))) {
            server.start();
            try (TestClient client = TestClient.connect(socket)) {
                client.send(request("hello", "link.hello", null,
                    object().put("client", "cc-connect")
                        .put("auth_token", "0123456789abcdef0123456789abcdef")));
                assertEquals("link.hello", client.read().name());
                assertEquals("session.activated", client.read().name());

                client.send(request("open-first", "session.open", null,
                    object().put("requested_session_id", "session-1")
                        .put("work_dir", "/project")));
                assertEquals("session.open", client.read().name());

                SessionHostSession replacement = session("session-1", new ArrayList<>());
                registry.activateLocal(replacement);
                assertEquals("session.activated", client.read().name());
                client.send(request("open-replacement", "session.open", null,
                    object().put("requested_session_id", "session-1")
                        .put("work_dir", "/project")));
                assertEquals("session.open", client.read().name());

                first.events().onMessage(assistant("stale"));
                replacement.events().onMessage(assistant("current"));
                SessionLinkFrame output = client.read();
                assertEquals("output.text", output.name());
                assertEquals("current", output.payload().path("content").asText());
            }
        }
    }

    @Test
    void badAuthenticationGetsGenericErrorAndConnectionCloses() throws Exception {
        SessionHostSession session = session("session-1", new ArrayList<>());
        SessionHostRegistry registry = registry(session);
        InteractionCoordinator interactions = new InteractionCoordinator(() -> "session-1");
        Path socket = tempDir.resolve("auth.sock");

        try (SessionLinkServer server = new SessionLinkServer(
                new SessionLinkServer.Config(socket, "0123456789abcdef0123456789abcdef"),
                registry, interactions)) {
            server.start();
            try (TestClient client = TestClient.connect(socket)) {
                client.send(request("hello-bad", "link.hello", null,
                    object().put("client", "cc-connect").put("auth_token", "wrong")));
                SessionLinkFrame error = client.read();
                assertEquals(SessionLinkFrame.Kind.ERROR, error.kind());
                assertEquals("authentication_failed", error.error().code());
                assertFalse(client.hasAnotherFrame());
            }
        }
    }

    private static SessionHostRegistry registry(SessionHostSession session) {
        return new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                return CompletableFuture.completedFuture(session);
            }
            @Override public List<SessionHostInfo> list() { return List.of(session.info()); }
        });
    }

    private static SessionHostSession session(String id, List<SessionHostSubmission> submissions) {
        return session(id, submissions,
            SessionHostModelController.unsupported());
    }

    private static SessionHostSession session(
            String id,
            List<SessionHostSubmission> submissions,
            SessionHostModelController models) {
        SessionSink primary = new SessionSink() {
            @Override public void onTurnStart(UserInput input) {}
            @Override public void onMessage(SDKMessage msg) {}
            @Override public void onError(Throwable error, boolean userCancel) {}
            @Override public void onTurnComplete(TurnOutcome outcome) {}
            @Override public void onIdle() {}
        };
        return new SessionHostSession(new SessionHostInfo(id, "/project", "", 0, null, ""),
            new SessionEventHub(primary, _ -> {}), submission -> {
                submissions.add(submission);
                return CompletableFuture.completedFuture(null);
            }, models);
    }

    private static SessionLinkFrame request(
            String id, String name, String sessionId, ObjectNode payload) {
        return SessionLinkFrame.request(id, name, sessionId, payload);
    }

    private static ObjectNode object() {
        return JsonUtils.getMapper().createObjectNode();
    }

    private static SDKMessage.Assistant assistant(String text) {
        return new SDKMessage.Assistant(
            new AssistantMessage("assistant", AssistantContent.of(
                "message", List.of(new TextBlock(text)))), Usage.EMPTY);
    }

    private static final class TestClient implements AutoCloseable {
        private final SocketChannel channel;
        private final BufferedInputStream input;
        private final SessionLinkCodec codec = new SessionLinkCodec();

        private TestClient(SocketChannel channel) {
            this.channel = channel;
            this.input = new BufferedInputStream(Channels.newInputStream(channel));
        }

        static TestClient connect(Path socket) throws Exception {
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
            return new TestClient(channel);
        }

        void send(SessionLinkFrame frame) throws Exception {
            byte[] encoded = codec.encode(frame);
            channel.write(ByteBuffer.wrap(encoded));
            channel.write(ByteBuffer.wrap(new byte[] {'\n'}));
        }

        SessionLinkFrame read() throws Exception {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) >= 0 && value != '\n') line.write(value);
            if (value < 0 && line.size() == 0) throw new IllegalStateException("connection closed");
            return codec.decode(line.toByteArray());
        }

        boolean hasAnotherFrame() throws Exception {
            channel.configureBlocking(false);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                ByteBuffer byteBuffer = ByteBuffer.allocate(1);
                while (System.nanoTime() < deadline) {
                    int read = channel.read(byteBuffer);
                    if (read > 0) return true;
                    if (read < 0) return false;
                    Thread.sleep(10);
                }
                return false;
            } finally {
                channel.configureBlocking(true);
            }
        }

        @Override public void close() throws Exception { channel.close(); }
    }
}
