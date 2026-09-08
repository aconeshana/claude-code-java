package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Interop tests driving the real gateway server over loopback HTTP with the
 * product's own client stack (OkHttp + okhttp-sse). This is the gateway face
 * of the wire-level verification {@code GoJavaSessionLinkInteropTest} gives
 * the IM link: the server's wire output is consumed by a real HTTP/SSE
 * client, not by in-process fakes.
 */
class GatewayServerInteropTest {

    private static final String TOKEN = "a".repeat(48);

    private final String activeSessionId = UUID.randomUUID().toString();
    private final RecordingPrimarySink primary = new RecordingPrimarySink();
    private final SessionEventHub hub = new SessionEventHub(primary, _ -> {});
    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(
                    SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by these tests");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of(new SessionHostInfo(
                    activeSessionId, "/work", "a session", 3,
                    Instant.ofEpochMilli(1_700_000_000_000L), "main"));
            }
        });

    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(30)).build();

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Test
    @Timeout(20)
    void sessionsEndpointRejectsMissingTokenAndListsSessionsWhenAuthed() throws Exception {
        startServer();

        try (Response anonymous = client.newCall(new Request.Builder()
                .url(url("/api/sessions")).build()).execute()) {
            assertThat(anonymous.code()).isEqualTo(401);
        }
        try (Response authed = client.newCall(new Request.Builder()
                .url(url("/api/sessions?token=" + TOKEN)).build()).execute()) {
            assertThat(authed.code()).isEqualTo(200);
            String body = authed.body().string();
            assertThat(body).contains(activeSessionId);
            assertThat(body).contains("\"active\":true");
        }
    }

    @Test
    @Timeout(20)
    void sessionsEndpointServesTwoLevelProjectStructureWhenCatalogIsInjected()
            throws Exception {
        // The catalog adapter mirrors the /resume project picker's shape:
        // projects[] each containing their own sessions[].
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {
                @Override public List<ProjectEntry> listProjects() {
                    return List.of(new ProjectEntry(
                        "/work/project-a", "project-a", 2, 1_700_000_000_000L,
                        List.of(
                            new SessionEntry("sess-a1", "first session", 10,
                                1_700_000_000_000L, "main", "/work/project-a", "", "hello"),
                            new SessionEntry(activeSessionId, "active session", 3,
                                1_700_000_000_001L, "main", "/work/project-a", "custom", ""))));
                }
            });
        server.start();
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(activeSessionId, "/work", "a session", 3,
                Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null));
        registry.activateLocal(session);

        try (Response authed = client.newCall(new Request.Builder()
                .url(url("/api/sessions?token=" + TOKEN)).build()).execute()) {
            assertThat(authed.code()).isEqualTo(200);
            String body = authed.body().string();
            // Two-level shape: project rows with nested session rows.
            assertThat(body).contains("\"projects\":");
            assertThat(body).contains("\"project_path\":\"/work/project-a\"");
            assertThat(body).contains("\"project_name\":\"project-a\"");
            assertThat(body).contains("\"session_count\":2");
            // The active session is flagged inside its project's sessions.
            assertThat(body).contains("\"active\":true");
            // Flat fallback shape must not appear when the catalog serves data.
            assertThat(body).doesNotContain("\"sessions\":[{\"id\":\"" + activeSessionId);
        }
    }

    @Test
    @Timeout(20)
    void mirrorStreamStreamsLiveEventsThroughARealEventSource() throws Exception {
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(null, events);

        hub.onTurnStart(UserInput.of("hello", "hello", null, "default"));
        hub.onMessage(textMessage("Hello from the turn"));
        hub.onTurnComplete(new TurnOutcome(
            false, false, 5L, null, null, null, "default"));

        // The session-activation notice (published on activateLocal) may
        // precede the turn frames; skip it.
        assertThat(nextNonActivationFrame(events)).contains("turn.started");
        assertThat(events.poll(5, TimeUnit.SECONDS)).contains("Hello from the turn");
        assertThat(events.poll(5, TimeUnit.SECONDS)).contains("turn.completed");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void reconnectingWithLastEventIdReplaysMissedFrames() throws Exception {
        startServer();
        LinkedBlockingQueue<String> first = new LinkedBlockingQueue<>();
        EventSource source = openMirror(null, first);
        hub.onTurnStart(UserInput.of("one", "one", null, "default"));
        assertThat(nextNonActivationFrame(first)).contains("turn.started");
        String lastEventId = lastEventIdReference.get();
        source.cancel();

        // Two more frames arrive while disconnected.
        hub.onMessage(textMessage("missed text"));
        hub.onTurnComplete(new TurnOutcome(false, false, 5L, null, null, null, "default"));

        LinkedBlockingQueue<String> second = new LinkedBlockingQueue<>();
        EventSource resumed = openMirror(lastEventId, second);
        assertThat(second.poll(5, TimeUnit.SECONDS)).contains("missed text");
        assertThat(second.poll(5, TimeUnit.SECONDS)).contains("turn.completed");
        resumed.cancel();
    }

    @Test
    @Timeout(20)
    void mirrorFramesCarrySessionIdAndSessionFilterNarrowsTheStream() throws Exception {
        // Every frame carries the originating session id; ?session_id=
        // narrows the stream to one conversation view.
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(null, events, activeSessionId);

        hub.onTurnStart(UserInput.of("hello", "hello", null, "default"));
        hub.onMessage(textMessage("Hello from the active session"));
        hub.onTurnComplete(new TurnOutcome(
            false, false, 5L, null, null, null, "default"));

        String started = nextNonActivationFrame(events);
        assertThat(started).contains("turn.started")
            .contains("\"session_id\":\"" + activeSessionId + "\"");
        assertThat(events.poll(5, TimeUnit.SECONDS))
            .contains("Hello from the active session");
        assertThat(events.poll(5, TimeUnit.SECONDS)).contains("turn.completed");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void toolCompletedFramesCarryTheTypedResultObject() throws Exception {
        // The alignment spec's ToolResult schema: a type discriminator per
        // tool family (remembered from the earlier tool_use block), the
        // textual payload, and error fields on failed results.
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(null, events);

        hub.onMessage(assistantMessage(List.of(
            new ToolUseBlock("toolu_bash_1", "Bash", null))));
        hub.onMessage(toolResultMessage("toolu_bash_1",
            List.of(new TextBlock("hello\nworld")), false));
        hub.onMessage(assistantMessage(List.of(
            new ToolUseBlock("toolu_edit_1", "Edit", null))));
        hub.onMessage(toolResultMessage("toolu_edit_1",
            List.of(new TextBlock("the file has been updated"), new TextBlock("!")), false));
        hub.onMessage(toolResultMessage("toolu_missing",
            List.of(new TextBlock("stale result")), false));
        hub.onMessage(assistantMessage(List.of(
            new ToolUseBlock("toolu_fail_1", "Read", null))));
        hub.onMessage(toolResultMessage("toolu_fail_1",
            List.of(new TextBlock("File not found: /no/such/file")), true));

        String bashFrame = pollUntilFrameFor(events, "toolu_bash_1",
            f -> Strings.CS.contains(f, "tool.completed")
                && Strings.CS.contains(f, "toolu_bash_1"));
        assertThat(bashFrame).contains("\"status\":\"completed\"")
            .contains("\"result\":{\"type\":\"execute_command_tool_result\"")
            .contains("\"data\":\"hello\\nworld\"");

        String editFrame = pollUntilFrameFor(events, "toolu_edit_1",
            f -> Strings.CS.contains(f, "tool.completed")
                && Strings.CS.contains(f, "toolu_edit_1"));
        assertThat(editFrame).contains("\"result\":{\"type\":\"replace_in_file_tool_result\"")
            .contains("\"data\":\"the file has been updated!\"");

        // A result without a remembered tool_use falls back to the generic
        // discriminator rather than dropping the frame.
        String staleFrame = pollUntilFrameFor(events, "toolu_missing",
            f -> Strings.CS.contains(f, "tool.completed")
                && Strings.CS.contains(f, "toolu_missing"));
        assertThat(staleFrame).contains("\"result\":{\"type\":\"tool_result\"");

        String failedFrame = pollUntilFrameFor(events, "toolu_fail_1",
            f -> Strings.CS.contains(f, "tool.completed")
                && Strings.CS.contains(f, "toolu_fail_1"));
        assertThat(failedFrame).contains("\"status\":\"failed\"")
            .contains("\"result\":{\"type\":\"read_file_tool_result\"")
            .contains("\"errorMessage\":\"File not found: /no/such/file\"")
            .contains("\"errorCode\":\"tool_error\"");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void oversizedToolResultsAreTruncatedWithAMarker() throws Exception {
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(null, events);

        hub.onMessage(assistantMessage(List.of(
            new ToolUseBlock("toolu_big", "Bash", null))));
        hub.onMessage(toolResultMessage("toolu_big",
            List.of(new TextBlock("x".repeat(30_000))), false));

        String frame = pollUntilFrameFor(events, "toolu_big",
            f -> Strings.CS.contains(f, "tool.completed")
                && Strings.CS.contains(f, "toolu_big"));
        assertThat(frame).contains("[truncated]");
        assertThat(frame.length()).isLessThan(25_000);
        source.cancel();
    }

    @Test
    @Timeout(20)
    void agentToolCompletionCarriesTheSidechainTranscriptPath() throws Exception {
        // An Agent-family tool result with a structured toolUseResult payload
        // projects the sidechain transcript path the TUI local-agent viewer
        // polls, so a web client can follow the same file.
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(null, events);

        hub.onMessage(assistantMessage(List.of(
            new ToolUseBlock("toolu_agent_1", "Task", null))));
        UserMessage agentResult = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(new ToolResultBlock("toolu_agent_1",
                List.of(new TextBlock("agent finished")), false))),
            false, false,
            Map.of("status", "completed", "agentId", "a1b2c3",
                "prompt", "do the thing"),
            MessageOrigin.USER, null, null, null, null, null,
            null, null, null, null, null, null, null);
        hub.onMessage(new SDKMessage.User(agentResult));

        String frame = pollUntilFrameFor(events, "toolu_agent_1",
            f -> Strings.CS.contains(f, "tool.completed")
                && Strings.CS.contains(f, "toolu_agent_1"));
        assertThat(frame).contains("\"type\":\"task_tool_result\"")
            .contains("\"transcript_path\":\"/work/" + activeSessionId
                + "/subagents/agent-a1b2c3.jsonl\"");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void agentProgressStreamsAsToolProgressFrames() throws Exception {
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(null, events);

        hub.onMessage(new SDKMessage.Progress(new ProgressMessage(
            UUID.randomUUID().toString(), null, null, null,
            "toolu_agent_1", null,
            new ProgressMessage.ProgressData("agent_progress",
                "scanning files", null, 1.5, null, null, null,
                null, null, null, "do the thing", "a1b2c3",
                0.4, 1.0, "2/5 tools", null, null, null))));

        String frame = pollUntilFrameFor(events, "toolu_agent_1",
            f -> Strings.CS.contains(f, "tool.progress"));
        assertThat(frame).contains("\"tool_use_id\":\"toolu_agent_1\"")
            .contains("\"kind\":\"agent_progress\"")
            .contains("\"agent_id\":\"a1b2c3\"")
            .contains("\"prompt\":\"do the thing\"")
            .contains("\"content\":\"scanning files\"")
            .contains("\"progress\":0.4")
            .contains("\"message\":\"2/5 tools\"");
        source.cancel();
    }

    /**
     * Polls the next frame that is not a session-activation notice. The
     * activation frame is published by {@code activateLocal} (startServer)
     * and may still be in flight when the turn frames are asserted.
     */
    private static String nextNonActivationFrame(
            LinkedBlockingQueue<String> events) throws InterruptedException {
        String frame = events.poll(5, TimeUnit.SECONDS);
        assertThat(frame).as("expected a non-activation frame").isNotNull();
        while (Strings.CS.contains(frame, "session.activated")) {
            frame = events.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("expected a non-activation frame").isNotNull();
        }
        return frame;
    }

    /** The frame for the given tool use id, skipping unrelated frames, 5s cap. */
    private String pollUntilFrameFor(LinkedBlockingQueue<String> events,
            String toolUseId, Predicate<String> matcher) throws InterruptedException {
        String frame = events.poll(5, TimeUnit.SECONDS);
        assertThat(frame).as("frame for " + toolUseId).isNotNull();
        while (!matcher.test(frame)) {
            frame = events.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("frame for " + toolUseId).isNotNull();
        }
        return frame;
    }

    /** The highest frame id observed so far, for Last-Event-ID resume tests. */
    private final AtomicReference<String> lastEventIdReference =
        new AtomicReference<>("0");

    private EventSource openMirror(String lastEventId, LinkedBlockingQueue<String> events) {
        return openMirror(lastEventId, events, null);
    }

    private EventSource openMirror(String lastEventId, LinkedBlockingQueue<String> events,
            String sessionFilter) {
        Request.Builder builder = new Request.Builder().url(url(
            sessionFilter == null
                ? "/api/events?token=" + TOKEN
                : "/api/events?token=" + TOKEN + "&session_id=" + sessionFilter));
        if (lastEventId != null) builder.header("Last-Event-ID", lastEventId);
        return EventSources.createFactory(client).newEventSource(builder.build(),
            new EventSourceListener() {
                @Override public void onEvent(EventSource source, String id, String type,
                        String data) {
                    lastEventIdReference.updateAndGet(current ->
                        Long.parseLong(id) > Long.parseLong(current) ? id : current);
                    events.add((type == null ? "" : type) + "|" + data);
                }
            });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private void startServer() throws IOException {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry);
        server.start();
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(activeSessionId, "/work", "a session", 3,
                Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null));
        registry.activateLocal(session);
    }

    private static final class RecordingPrimarySink implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }

    private SDKMessage.Assistant textMessage(String text) {
        return assistantMessage(List.of(new TextBlock(text)));
    }

    private SDKMessage.Assistant assistantMessage(List<ContentBlock> blocks) {
        AssistantMessage message = new AssistantMessage(
            UUID.randomUUID().toString(),
            new AssistantContent(null, blocks, null));
        return new SDKMessage.Assistant(message, Usage.EMPTY, "claude-sonnet-5");
    }

    private SDKMessage.User toolResultMessage(String toolUseId,
            List<ContentBlock> content, boolean isError) {
        UserMessage message = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(
                new ToolResultBlock(toolUseId, content, isError))),
            false, false, null, MessageOrigin.USER, null, null, null, null, null,
            null, null, null, null, null, null, null);
        return new SDKMessage.User(message);
    }
}
