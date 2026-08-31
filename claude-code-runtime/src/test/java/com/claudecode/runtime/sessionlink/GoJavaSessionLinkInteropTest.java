package com.claudecode.runtime.sessionlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.PermissionAskContext;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostModelController;
import com.claudecode.runtime.sessionhost.SessionHostModelOption;
import com.claudecode.runtime.sessionhost.SessionHostModelState;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoJavaSessionLinkInteropTest {

    @TempDir Path tempDir;

    @Test
    void productionGoClientTalksToProductionJavaServer() throws Exception {
        Path ccConnect = locateCcConnect();
        Assumptions.assumeTrue(Files.isRegularFile(ccConnect.resolve("go.mod")),
            "sibling cc-connect checkout is required for interop test");

        String sessionId = "java-interop-session";
        String token = "0123456789abcdef0123456789abcdef";
        InteractionCoordinator interactions = new InteractionCoordinator(() -> sessionId);
        SessionEventHub events = new SessionEventHub(new NoopSink(), _ -> {});
        AtomicReference<String> model = new AtomicReference<>("sonnet");
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/interop", "Interop", 0, Instant.now(), ""),
            events,
            submission -> {
                events.onMessage(new SDKMessage.Assistant(
                    new AssistantMessage("a1", AssistantContent.of("m1",
                        List.of(new TextBlock("java:" + submission.prompt())))), Usage.EMPTY));
                Thread.ofVirtual().name("interop-permission").start(() -> {
                    var result = interactions.ask(PermissionAskContext.simple("Bash",
                        JsonUtils.getMapper().createObjectNode().put("command", "pwd"), "tool-1"));
                    assertTrue(result.allowed());
                    events.onTurnComplete(new TurnOutcome(false, false, false,
                        1L, null, Map.of(), null, "default"));
                });
                return CompletableFuture.completedFuture(null);
            },
            new SessionHostModelController() {
                @Override public SessionHostModelState get() {
                    return new SessionHostModelState(model.get(), List.of(
                        new SessionHostModelOption("sonnet", "Sonnet", "Balanced", "sol", false),
                        new SessionHostModelOption("opus", "Opus", "Most capable", "opus", false)));
                }

                @Override public SessionHostModelState set(String selected) {
                    model.set(selected);
                    return get();
                }
            });
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                assertEquals(sessionId, request.requestedSessionId());
                return CompletableFuture.completedFuture(session);
            }
            @Override public List<SessionHostInfo> list() { return List.of(session.info()); }
        });
        registry.activateLocal(session);
        Path socket = tempDir.resolve("interop.sock");

        try (interactions; SessionLinkServer server = new SessionLinkServer(
                new SessionLinkServer.Config(socket, token), registry, interactions)) {
            server.start();
            ProcessBuilder builder = new ProcessBuilder("go", "test", "./agent/sessionhost",
                "-run", "^TestJavaSessionLinkInterop$", "-count=1");
            builder.directory(ccConnect.toFile());
            builder.redirectErrorStream(true);
            builder.environment().put("CC_JAVA_INTEROP_ENDPOINT", "unix://" + socket);
            builder.environment().put("CC_JAVA_INTEROP_TOKEN", token);
            builder.environment().put("CC_JAVA_INTEROP_SESSION_ID", sessionId);
            Process process = builder.start();
            assertTrue(process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS),
                "Go interop test timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        }
    }

    private static Path locateCcConnect() {
        String configured = System.getenv("CC_CONNECT_SOURCE");
        if (StringUtils.isNotBlank(configured)) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            Path sibling = cursor.resolveSibling("cc-connect");
            if (Files.isRegularFile(sibling.resolve("go.mod"))) return sibling;
        }
        return current.resolveSibling("cc-connect");
    }

    private static final class NoopSink implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
