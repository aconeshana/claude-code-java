package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
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
    void sessionsEndpointPagesEachProjectToTheRequestedRows() throws Exception {
        // Records the page size each listing call saw; the port's paging is
        // what bounds one request's row count.
        List<Integer> seenLimits = new CopyOnWriteArrayList<>();
        List<GatewaySessionCatalogPort.SessionEntry> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rows.add(new GatewaySessionCatalogPort.SessionEntry("sess-" + i, "session " + i, i,
                1_700_000_000_000L + i, "main", "/work/project-a", "", "row " + i));
        }
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {
                @Override public List<GatewaySessionCatalogPort.ProjectEntry> listProjects(
                        int perProjectLimit) {
                    seenLimits.add(perProjectLimit);
                    List<GatewaySessionCatalogPort.SessionEntry> visible =
                        perProjectLimit <= 0 || perProjectLimit >= rows.size()
                            ? rows : rows.subList(0, perProjectLimit);
                    return List.of(new GatewaySessionCatalogPort.ProjectEntry(
                        "/work/project-a", "project-a", rows.size(),
                        1_700_000_000_000L, visible));
                }
            });
        server.start();

        // Default: the paged default (5 rows), session_count keeps the total.
        try (Response authed = client.newCall(new Request.Builder()
                .url(url("/api/sessions?token=" + TOKEN)).build()).execute()) {
            assertThat(authed.code()).isEqualTo(200);
            String body = authed.body().string();
            assertThat(body).contains("\"session_count\":8");
            assertThat(body).contains("sess-4");
            assertThat(body).doesNotContain("sess-5");
        }
        // Explicit page size grows the page without another round-trip type.
        try (Response authed = client.newCall(new Request.Builder()
                .url(url("/api/sessions?token=" + TOKEN + "&per_project=7")).build()).execute()) {
            assertThat(authed.code()).isEqualTo(200);
            String body = authed.body().string();
            assertThat(body).contains("sess-6");
            assertThat(body).doesNotContain("sess-7");
        }
        // "all" lifts the limit entirely.
        try (Response authed = client.newCall(new Request.Builder()
                .url(url("/api/sessions?token=" + TOKEN + "&per_project=all")).build()).execute()) {
            assertThat(authed.code()).isEqualTo(200);
            assertThat(authed.body().string()).contains("sess-7");
        }
        assertThat(seenLimits).containsExactly(5, 7, 0);
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
    void turnCompletedFramesCarryTheDurableFoldDelta() throws Exception {
        // The completion frame's turn/turn_usage ride the durable fold
        // diffed against the turn-start baseline — raw integers, the same
        // projection the session-context endpoint serves, never a
        // client-side derivation (spec §6). No reader (unwired composition)
        // or an incomplete fold leaves the frame without the delta.
        AtomicReference<SessionMetricsSnapshot> fold =
            new AtomicReference<>(SessionMetricsSnapshot.INCOMPLETE);
        startServer(new GatewaySessionContextPort() {
            @Override public Optional<SessionMetricsSnapshot> metrics(String sessionId) {
                return Optional.ofNullable(fold.get());
            }
        });
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(null, events);

        // No complete fold at turn start: no baseline, the completion frame
        // stays without the delta fields.
        hub.onTurnStart(UserInput.of("one", "one", null, "default"));
        hub.onTurnComplete(new TurnOutcome(
            false, false, 5L, null, null, null, "default"));
        String bare = nextNonActivationFrame(events);
        assertThat(bare).contains("turn.started")
            .contains("\"time\":");
        assertThat(events.poll(5, TimeUnit.SECONDS))
            .contains("turn.completed")
            .contains("\"time\":")
            .doesNotContain("turn_usage");

        // Turn 1 starts over a complete fold of 2 turns; by completion the
        // fold counts 3 turns with the turn's buckets added.
        fold.set(new SessionMetricsSnapshot(
            true, 3, 4, 12_000, 4_000, 900, 4, 6_000, 400,
            1_000, 800, 200, 9_000));
        hub.onTurnStart(UserInput.of("two", "two", null, "default"));
        String baselineStart = events.poll(5, TimeUnit.SECONDS);
        assertThat(baselineStart).contains("turn.started");
        fold.set(new SessionMetricsSnapshot(
            true, 4, 5, 15_000, 5_000, 1_100, 5, 7_000, 500,
            1_200, 1_000, 300, 9_900));
        hub.onTurnComplete(new TurnOutcome(
            false, false, 7L, null, null, null, "default"));

        String completed = events.poll(5, TimeUnit.SECONDS);
        assertThat(completed).contains("turn.completed")
            .contains("\"turn\":4")
            .contains("\"time\":")
            .contains("\"uncached_input_tokens\":200")
            .contains("\"output_tokens\":200")
            .contains("\"cache_write_tokens\":100")
            .contains("\"cache_read_tokens\":900")
            .contains("\"total_tokens\":1400")
            .contains("\"elapsed_ms\":7");
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
    void toolCompletedFramesCarryLocationsForFileProducingTools() throws Exception {
        // Write/Edit/NotebookEdit results carry a structured toolUseResult
        // with the produced file path — the payload the deliverables chip
        // row on the webui side consumes. Other tool families carry none.
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(null, events);

        hub.onMessage(assistantMessage(List.of(
            new ToolUseBlock("toolu_write_1", "Write", null))));
        hub.onMessage(toolResultMessage("toolu_write_1",
            List.of(new TextBlock("File created successfully")), false,
            FileChangeResult.created("/work/new-file.txt", "hello")));

        hub.onMessage(assistantMessage(List.of(
            new ToolUseBlock("toolu_edit_2", "Edit", null))));
        hub.onMessage(toolResultMessage("toolu_edit_2",
            List.of(new TextBlock("the file has been updated")), false,
            FileChangeResult.edited("/work/existing-file.txt", List.of())));

        ObjectNode notebookResult = JsonUtils.getMapper().createObjectNode();
        notebookResult.put("notebook_path", "/work/notebook.ipynb");
        hub.onMessage(assistantMessage(List.of(
            new ToolUseBlock("toolu_notebook_1", "NotebookEdit", null))));
        hub.onMessage(toolResultMessage("toolu_notebook_1",
            List.of(new TextBlock("cell replaced")), false, notebookResult));

        hub.onMessage(assistantMessage(List.of(
            new ToolUseBlock("toolu_bash_2", "Bash", null))));
        hub.onMessage(toolResultMessage("toolu_bash_2",
            List.of(new TextBlock("ok")), false, null));

        String writeFrame = pollUntilFrameFor(events, "toolu_write_1",
            f -> Strings.CS.contains(f, "tool.completed")
                && Strings.CS.contains(f, "toolu_write_1"));
        assertThat(writeFrame).contains("\"locations\":[\"/work/new-file.txt\"]");

        String editFrame = pollUntilFrameFor(events, "toolu_edit_2",
            f -> Strings.CS.contains(f, "tool.completed")
                && Strings.CS.contains(f, "toolu_edit_2"));
        assertThat(editFrame).contains("\"locations\":[\"/work/existing-file.txt\"]");

        String notebookFrame = pollUntilFrameFor(events, "toolu_notebook_1",
            f -> Strings.CS.contains(f, "tool.completed")
                && Strings.CS.contains(f, "toolu_notebook_1"));
        assertThat(notebookFrame).contains("\"locations\":[\"/work/notebook.ipynb\"]");

        String bashFrame = pollUntilFrameFor(events, "toolu_bash_2",
            f -> Strings.CS.contains(f, "tool.completed")
                && Strings.CS.contains(f, "toolu_bash_2"));
        assertThat(bashFrame).doesNotContain("\"locations\"");
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

    @Test
    @Timeout(20)
    void staticRoutesServeWebuiIndexAndAssetsWithoutToken() throws Exception {
        startServer();

        // The landing page and bundled assets are unauthenticated by design:
        // the shell carries no secrets and the token reaches the API calls
        // through the landing URL's query parameter.
        try (Response index = client.newCall(new Request.Builder()
                .url(url("/")).build()).execute()) {
            assertThat(index.code()).isEqualTo(200);
            assertThat(index.header("Content-Type")).isEqualTo("text/html; charset=utf-8");
            assertThat(index.body().string()).contains("webui-index");
        }
        try (Response aliased = client.newCall(new Request.Builder()
                .url(url("/webui/")).build()).execute()) {
            assertThat(aliased.code()).isEqualTo(200);
            assertThat(aliased.body().string()).contains("webui-index");
        }
        try (Response asset = client.newCall(new Request.Builder()
                .url(url("/webui/assets/app.css")).build()).execute()) {
            assertThat(asset.code()).isEqualTo(200);
            assertThat(asset.header("Content-Type")).isEqualTo("text/css; charset=utf-8");
            assertThat(asset.body().string()).contains("--dsw-alias-label-primary");
        }
    }

    @Test
    @Timeout(20)
    void staticRoutesRejectTraversalAndUnknownAssets() throws Exception {
        startServer();

        try (Response traversal = client.newCall(new Request.Builder()
                .url(url("/webui/../../etc/passwd")).build()).execute()) {
            // OkHttp normalizes dot segments before sending (/etc/passwd),
            // which is not a static route, so the request reaches the API
            // fallback unauthenticated: 401, never a file read. A raw
            // traversal attempt that survived normalization would land on
            // the static handler's 400.
            assertThat(traversal.code()).isEqualTo(401);
        }
        try (Response missing = client.newCall(new Request.Builder()
                .url(url("/webui/assets/nope.css")).build()).execute()) {
            assertThat(missing.code()).isEqualTo(404);
        }
        // Non-GET verbs never hit the static handler.
        try (Response post = client.newCall(new Request.Builder()
                .url(url("/webui/index.html"))
                .post(RequestBody.create(new byte[0], null)).build()).execute()) {
            assertThat(post.code()).isEqualTo(401);
        }
    }

    @Test
    @Timeout(20)
    void settingsEndpointReadsSnapshotAppliesOpsAndRejectsBadInput() throws Exception {
        // The settings panel's port: one snapshot read, one op-based mutation
        // that answers with the refreshed snapshot, and 400s for malformed
        // requests the adapter never sees.
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new RecordingSettingsPort(),
            new GatewaySchedulePort() {});
        server.start();

        try (Response anonymous = client.newCall(new Request.Builder()
                .url(url("/api/settings")).build()).execute()) {
            assertThat(anonymous.code()).isEqualTo(401);
        }
        try (Response snapshot = client.newCall(new Request.Builder()
                .url(url("/api/settings?token=" + TOKEN)).build()).execute()) {
            assertThat(snapshot.code()).isEqualTo(200);
            String body = snapshot.body().string();
            assertThat(body).contains("\"effective\":")
                .contains("\"model\":\"claude-sonnet-5\"")
                .contains("\"sources\":[");
        }
        try (Response applied = client.newCall(new Request.Builder()
                .url(url("/api/settings?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"op\":\"userValue\",\"key\":\"model\",\"value\":\"claude-opus-5\"}",
                    MediaTypeJson.JSON)).build()).execute()) {
            assertThat(applied.code()).isEqualTo(200);
            assertThat(applied.body().string())
                .contains("\"model\":\"claude-opus-5\"");
        }
        try (Response unknownOp = client.newCall(new Request.Builder()
                .url(url("/api/settings?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"op\":\"nope\"}", MediaTypeJson.JSON)).build()).execute()) {
            assertThat(unknownOp.code()).isEqualTo(400);
            assertThat(unknownOp.body().string()).contains("unknown op");
        }
        try (Response badTier = client.newCall(new Request.Builder()
                .url(url("/api/settings?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"op\":\"permissionMode\",\"mode\":\"plan\",\"tier\":\"bogus\"}",
                    MediaTypeJson.JSON)).build()).execute()) {
            assertThat(badTier.code()).isEqualTo(400);
            assertThat(badTier.body().string()).contains("unknown settings tier");
        }
        try (Response badJson = client.newCall(new Request.Builder()
                .url(url("/api/settings?token=" + TOKEN))
                .post(RequestBody.create("not json", MediaTypeJson.JSON)).build()).execute()) {
            assertThat(badJson.code()).isEqualTo(400);
            assertThat(badJson.body().string()).contains("not valid JSON");
        }
    }

    @Test
    @Timeout(20)
    void settingsEndpointSurfacesPersistenceFailuresAs500() throws Exception {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {
                @Override public void writeUserValue(String key,
                        JsonNode value) {
                    throw new RuntimeException("disk full");
                }
            }, new GatewaySchedulePort() {});
        server.start();

        try (Response failure = client.newCall(new Request.Builder()
                .url(url("/api/settings?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"op\":\"userValue\",\"key\":\"model\",\"value\":\"x\"}",
                    MediaTypeJson.JSON)).build()).execute()) {
            assertThat(failure.code()).isEqualTo(500);
            assertThat(failure.body().string()).contains("disk full");
        }
    }

    @Test
    @Timeout(20)
    void scheduleEndpointListsAddsAndRemovesTasks() throws Exception {
        RecordingSchedulePort schedule = new RecordingSchedulePort();
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {}, schedule);
        server.start();

        try (Response anonymous = client.newCall(new Request.Builder()
                .url(url("/api/schedule")).build()).execute()) {
            assertThat(anonymous.code()).isEqualTo(401);
        }
        try (Response listed = client.newCall(new Request.Builder()
                .url(url("/api/schedule?token=" + TOKEN)).build()).execute()) {
            assertThat(listed.code()).isEqualTo(200);
            assertThat(listed.body().string())
                .contains("\"tasks\":[")
                .contains("\"cron\":\"0 9 * * *\"")
                .contains("\"recurring\":true");
        }
        try (Response added = client.newCall(new Request.Builder()
                .url(url("/api/schedule?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"cron\":\"*/5 * * * *\",\"prompt\":\"check the build\","
                        + "\"recurring\":true,\"durable\":false,\"model\":\"opus\"}",
                    MediaTypeJson.JSON)).build()).execute()) {
            assertThat(added.code()).isEqualTo(200);
            assertThat(added.body().string()).contains("\"id\":\"abcd1234\"");
        }
        try (Response listed = client.newCall(new Request.Builder()
                .url(url("/api/schedule?token=" + TOKEN)).build()).execute()) {
            assertThat(listed.code()).isEqualTo(200);
            assertThat(listed.body().string())
                .contains("\"model\":\"opus\"");
        }
        try (Response missingFields = client.newCall(new Request.Builder()
                .url(url("/api/schedule?token=" + TOKEN))
                .post(RequestBody.create("{\"cron\":\"\"}", MediaTypeJson.JSON)).build())
                .execute()) {
            assertThat(missingFields.code()).isEqualTo(400);
            assertThat(missingFields.body().string())
                .contains("cron and prompt are required");
        }
        try (Response invalidCron = client.newCall(new Request.Builder()
                .url(url("/api/schedule?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"cron\":\"0 9 * *\",\"prompt\":\"never stored\"}",
                    MediaTypeJson.JSON)).build()).execute()) {
            assertThat(invalidCron.code()).isEqualTo(400);
            assertThat(invalidCron.body().string())
                .contains("Invalid cron expression")
                .contains("Expected 5 fields");
        }
        assertThat(schedule.tasks.stream()
                .noneMatch(task -> Strings.CS.equals("never stored", task.prompt())))
            .as("a rejected add must not reach the store")
            .isTrue();
        try (Response removed = client.newCall(new Request.Builder()
                .url(url("/api/schedule/abcd1234?token=" + TOKEN))
                .delete().build()).execute()) {
            assertThat(removed.code()).isEqualTo(200);
            assertThat(removed.body().string()).contains("\"removed\":true");
        }
        try (Response unknown = client.newCall(new Request.Builder()
                .url(url("/api/schedule/none?token=" + TOKEN))
                .delete().build()).execute()) {
            assertThat(unknown.code()).isEqualTo(404);
            assertThat(unknown.body().string()).contains("unknown task");
        }
    }

    @Test
    @Timeout(20)
    void modelsEndpointListsSavesWithApiKeyTriStateAndRemoves() throws Exception {
        RecordingModelsPort models = new RecordingModelsPort();
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {},
            new GatewaySchedulePort() {}, models);
        server.start();

        try (Response anonymous = client.newCall(new Request.Builder()
                .url(url("/api/models")).build()).execute()) {
            assertThat(anonymous.code()).isEqualTo(401);
        }
        try (Response listed = client.newCall(new Request.Builder()
                .url(url("/api/models?token=" + TOKEN)).build()).execute()) {
            assertThat(listed.code()).isEqualTo(200);
            assertThat(listed.body().string())
                .contains("\"models\":[")
                .contains("\"model_name\":\"seed-model\"")
                .contains("\"has_api_key\":true");
        }
        // A text api_key sets a new value.
        try (Response added = client.newCall(new Request.Builder()
                .url(url("/api/models?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"model_name\":\"new-model\",\"protocol\":\"chat\","
                        + "\"base_url\":\"https://example.com/v1\",\"api_key\":\"sk-123\"}",
                    MediaTypeJson.JSON)).build()).execute()) {
            assertThat(added.code()).isEqualTo(200);
            assertThat(added.body().string())
                .contains("\"model_name\":\"new-model\"")
                .contains("\"has_api_key\":true");
        }
        // A missing api_key field keeps the existing key (edit without touching credentials).
        try (Response edited = client.newCall(new Request.Builder()
                .url(url("/api/models?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"model_name\":\"new-model\",\"protocol\":\"chat\","
                        + "\"base_url\":\"https://example.com/v2\"}",
                    MediaTypeJson.JSON)).build()).execute()) {
            assertThat(edited.code()).isEqualTo(200);
            assertThat(edited.body().string()).contains("\"base_url\":\"https://example.com/v2\"");
            assertThat(models.saved.get("new-model").apiKey).isEqualTo("sk-123");
        }
        // An explicit null api_key clears it.
        try (Response cleared = client.newCall(new Request.Builder()
                .url(url("/api/models?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"model_name\":\"new-model\",\"protocol\":\"chat\","
                        + "\"base_url\":\"https://example.com/v2\",\"api_key\":null}",
                    MediaTypeJson.JSON)).build()).execute()) {
            assertThat(cleared.code()).isEqualTo(200);
            assertThat(models.saved.get("new-model").apiKey).isNull();
        }
        try (Response missingFields = client.newCall(new Request.Builder()
                .url(url("/api/models?token=" + TOKEN))
                .post(RequestBody.create("{\"model_name\":\"x\"}", MediaTypeJson.JSON)).build())
                .execute()) {
            assertThat(missingFields.code()).isEqualTo(400);
            assertThat(missingFields.body().string())
                .contains("model_name, protocol, and base_url are required");
        }
        try (Response badProtocol = client.newCall(new Request.Builder()
                .url(url("/api/models?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"model_name\":\"x\",\"protocol\":\"bogus\",\"base_url\":\"https://x\"}",
                    MediaTypeJson.JSON)).build()).execute()) {
            assertThat(badProtocol.code()).isEqualTo(400);
            assertThat(badProtocol.body().string()).contains("unknown model protocol");
        }
        try (Response removed = client.newCall(new Request.Builder()
                .url(url("/api/models/new-model?token=" + TOKEN))
                .delete().build()).execute()) {
            assertThat(removed.code()).isEqualTo(200);
            assertThat(removed.body().string()).contains("\"removed\":true");
        }
        try (Response unknown = client.newCall(new Request.Builder()
                .url(url("/api/models/none?token=" + TOKEN))
                .delete().build()).execute()) {
            assertThat(unknown.code()).isEqualTo(404);
            assertThat(unknown.body().string()).contains("\"removed\":false");
        }
    }

    private static final class MediaTypeJson {
        static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");
    }

    @Test
    @Timeout(20)
    void sessionContextEndpointServesSelectionAndUsageAndAppliesSelections()
            throws Exception {
        // The composer seat's port: one selection read with the usage half
        // (claude-hud token accounting over the port's message list), one
        // model POST through the controller, one effort POST, and 400s for
        // rejected names the adapter surfaces.
        RecordingSessionContextPort context = new RecordingSessionContextPort();
        context.messages = List.of(new AssistantMessage(UUID.randomUUID().toString(),
            new AssistantContent("msg_usage", List.of(new TextBlock("hi")), null)));
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {},
            new GatewaySchedulePort() {}, new GatewayModelsPort() {},
            new GatewayCommandsPort() {}, context);
        server.start();

        try (Response anonymous = client.newCall(new Request.Builder()
                .url(url("/api/session/context")).build()).execute()) {
            assertThat(anonymous.code()).isEqualTo(401);
        }
        try (Response snapshot = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN)).build()).execute()) {
            assertThat(snapshot.code()).isEqualTo(200);
            String body = snapshot.body().string();
            assertThat(body)
                .contains("\"current\":\"sonnet\"")
                .contains("\"name\":\"default\"")
                .contains("\"name\":\"opus\"")
                .contains("\"effort\":")
                .contains("\"current\":\"auto\"")
                .contains("\"context_window\":200000");
            // No finalized usage anchor (Usage.EMPTY) leaves the meter
            // unmeasured: window served, no token counts.
            assertThat(body).doesNotContain("used_percentage");
        }
        try (Response applied = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN))
                .post(RequestBody.create("{\"model\":\"opus\"}", MediaTypeJson.JSON))
                .build()).execute()) {
            assertThat(applied.code()).isEqualTo(200);
            assertThat(applied.body().string()).contains("\"current\":\"opus\"");
            assertThat(context.selectedModel).isEqualTo("opus");
        }
        try (Response rejected = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"model\":\"nope\"}", MediaTypeJson.JSON))
                .build()).execute()) {
            assertThat(rejected.code()).isEqualTo(400);
            assertThat(rejected.body().string())
                .contains("model is not available for this session");
        }
        try (Response effort = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN))
                .post(RequestBody.create("{\"effort\":\"high\"}", MediaTypeJson.JSON))
                .build()).execute()) {
            assertThat(effort.code()).isEqualTo(200);
            assertThat(effort.body().string()).contains("\"current\":\"high\"");
            assertThat(context.selectedEffort).isEqualTo("high");
        }
        try (Response bothFields = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"model\":\"opus\",\"effort\":\"high\"}", MediaTypeJson.JSON))
                .build()).execute()) {
            assertThat(bothFields.code()).isEqualTo(400);
            assertThat(bothFields.body().string())
                .contains("cannot be changed in one request");
        }
    }

    @Test
    @Timeout(20)
    void sessionContextEndpointRoutesTheRequestedSessionId() throws Exception {
        // The addressed session arrives as ?session_id= (GET) or a session_id
        // body field (POST); a blank id keeps the active-session default the
        // earlier interop case exercises.
        RecordingSessionContextPort context = new RecordingSessionContextPort();
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {},
            new GatewaySchedulePort() {}, new GatewayModelsPort() {},
            new GatewayCommandsPort() {}, context);
        server.start();

        try (Response targeted = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN
                    + "&session_id=headless-42")).build()).execute()) {
            assertThat(targeted.code()).isEqualTo(200);
            assertThat(context.requestedSessionId).isEqualTo("headless-42");
        }
        try (Response applied = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"session_id\":\"headless-42\",\"model\":\"opus\"}",
                    MediaTypeJson.JSON))
                .build()).execute()) {
            assertThat(applied.code()).isEqualTo(200);
            assertThat(context.selectedModel).isEqualTo("opus");
            assertThat(context.requestedSessionId).isEqualTo("headless-42");
        }
        try (Response unaddressed = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN)).build()).execute()) {
            assertThat(unaddressed.code()).isEqualTo(200);
            assertThat(context.requestedSessionId).isNull();
        }
    }

    @Test
    @Timeout(20)
    void sessionContextUsageServesClaudeHudAccountingOverMessages() throws Exception {
        // The usage projection is the same computation the status line
        // serves: finalized usage anchor's input-token sum (input + cache
        // creation + cache read) over the model-resolved window. The
        // breakdown rides the same answer with the TUI /context analyzer's
        // category split.
        RecordingSessionContextPort context = new RecordingSessionContextPort();
        context.messages = List.of(new AssistantMessage(UUID.randomUUID().toString(),
            new AssistantContent("msg_usage", List.of(new TextBlock("hi")),
                new Usage(1_000L, 2_000L, 10_000L, 20_000L, Usage.ServerToolUse.ZERO,
                    "standard", Usage.CacheCreation.ZERO, "", List.of(), "standard", null),
                "claude-sonnet-5", "end_turn", null)));
        context.breakdown = new GatewaySessionContextPort.ContextBreakdown(
            5_000L, 5_000L, 21_000L);
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {},
            new GatewaySchedulePort() {}, new GatewayModelsPort() {},
            new GatewayCommandsPort() {}, context);
        server.start();

        try (Response snapshot = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN)).build()).execute()) {
            assertThat(snapshot.code()).isEqualTo(200);
            String body = snapshot.body().string();
            // 1000 input + 10000 cache creation + 20000 cache read = 31000
            // of the 200k default window = 16 percent (15.5 rounded).
            assertThat(body)
                .contains("\"used_tokens\":31000")
                .contains("\"used_percentage\":16")
                .contains("\"breakdown\":")
                .contains("\"system_tokens\":5000")
                .contains("\"tools_tokens\":5000")
                .contains("\"message_tokens\":21000");
        }
    }

    @Test
    @Timeout(20)
    void sessionContextServesDurableMetricsOnGetAndPost() throws Exception {
        // The metrics half projects the engine's durable fold verbatim:
        // raw integers on the wire (the client owns display formatting),
        // served null when coverage is incomplete so a partial fold is
        // never displayed as a session total (spec §6), and attached to
        // the POST answer too so a selection change never blanks the pills.
        RecordingSessionContextPort context = new RecordingSessionContextPort();
        context.metrics = new SessionMetricsSnapshot(
            true, 3, 5, 12_000, 4_000, 900, 5, 6_000, 400,
            1_000, 800, 200, 9_000);
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {},
            new GatewaySchedulePort() {}, new GatewayModelsPort() {},
            new GatewayCommandsPort() {}, context);
        server.start();

        try (Response snapshot = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN)).build()).execute()) {
            assertThat(snapshot.code()).isEqualTo(200);
            String body = snapshot.body().string();
            assertThat(body)
                .contains("\"metrics\":{")
                .contains("\"turns\":3")
                .contains("\"steps\":5")
                .contains("\"llm_ms\":12000")
                .contains("\"tool_ms\":4000")
                .contains("\"ttft_ms\":900")
                .contains("\"ttft_steps\":5")
                .contains("\"decode_ms\":6000")
                .contains("\"decode_tokens\":400")
                .contains("\"uncached_input_tokens\":1000")
                .contains("\"output_tokens\":800")
                .contains("\"cache_write_tokens\":200")
                .contains("\"cache_read_tokens\":9000");
        }
        try (Response applied = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN))
                .post(RequestBody.create("{\"model\":\"opus\"}", MediaTypeJson.JSON))
                .build()).execute()) {
            assertThat(applied.code()).isEqualTo(200);
            // The POST answer rides the metrics too: a model change must
            // not blank the stats pills.
            assertThat(applied.body().string())
                .contains("\"current\":\"opus\"")
                .contains("\"turns\":3");
        }

        // INCOMPLETE coverage serves null — never a partial total.
        context.metrics = SessionMetricsSnapshot.INCOMPLETE;
        try (Response partial = client.newCall(new Request.Builder()
                .url(url("/api/session/context?token=" + TOKEN)).build()).execute()) {
            assertThat(partial.code()).isEqualTo(200);
            assertThat(partial.body().string()).contains("\"metrics\":null");
        }
    }

    /** In-memory session-context double with one switchable selection. */
    private static final class RecordingSessionContextPort
            implements GatewaySessionContextPort {
        List<Message> messages = List.of();
        ContextBreakdown breakdown;
        SessionMetricsSnapshot metrics;
        String selectedModel;
        String selectedEffort;
        /** The last session id each method saw — blank for the active-session default. */
        String requestedSessionId;

        @Override public Optional<ContextBreakdown> breakdown(String sessionId) {
            requestedSessionId = sessionId;
            return Optional.ofNullable(breakdown);
        }

        @Override public Optional<SessionMetricsSnapshot> metrics(String sessionId) {
            return Optional.ofNullable(metrics);
        }

        @Override public Optional<ModelSelection> selection(String sessionId) {
            requestedSessionId = sessionId;
            return Optional.of(new ModelSelection("sonnet",
                List.of(
                    new ModelChoice("default", "Default (recommended)", "the launch model", true),
                    new ModelChoice("opus", "Opus", "deepest reasoning", false),
                    new ModelChoice("sonnet", "Sonnet", "balanced", false)),
                "auto", "auto", List.of("auto", "low", "medium", "high")));
        }

        @Override public Optional<List<Message>> messages(String sessionId) {
            requestedSessionId = sessionId;
            return Optional.of(messages);
        }

        @Override public SelectionResult selectModel(String sessionId, String model) {
            requestedSessionId = sessionId;
            Optional<ModelSelection> current = selection(sessionId);
            if (current.isPresent() && current.get().models().stream()
                    .anyMatch(choice -> choice.name().equals(model))) {
                selectedModel = model;
                return SelectionResult.accepted(new ModelSelection(model,
                    current.get().models(), "auto", "auto",
                    List.of("auto", "low", "medium", "high")));
            }
            return SelectionResult.rejected("model is not available for this session");
        }

        @Override public SelectionResult selectEffort(String sessionId, String effort) {
            requestedSessionId = sessionId;
            if (!List.of("auto", "low", "medium", "high").contains(effort)) {
                return SelectionResult.rejected("effort is not available for this session");
            }
            selectedEffort = effort;
            return SelectionResult.accepted(new ModelSelection("sonnet",
                selection(sessionId).map(ModelSelection::models).orElse(List.of()),
                effort, effort, List.of("auto", "low", "medium", "high")));
        }
    }

    /** In-memory settings port double: applies userValue writes to the snapshot. */
    private static final class RecordingSettingsPort implements GatewaySettingsPort {
        String model = "claude-sonnet-5";

        @Override public ObjectNode
                effectiveSettings() {
            ObjectNode root =
                JsonUtils.getMapper()
                    .createObjectNode();
            // Same shape as SettingsSnapshots.withSources: the merged
            // effective tree plus one row per contributing tier.
            root.putObject("effective").put("model", model);
            root.putArray("sources").addObject()
                .put("source", "userSettings");
            return root;
        }

        @Override public void writeUserValue(String key,
                JsonNode value) {
            if (Strings.CS.equals("model", key) && value != null && value.isTextual()) {
                model = value.asText();
            }
        }
    }

    /** In-memory schedule port double: one seeded task, adds and removes by id. */
    private static final class RecordingSchedulePort implements GatewaySchedulePort {
        final List<ScheduleEntry> tasks = new CopyOnWriteArrayList<>(
            List.of(new ScheduleEntry("seed00001", "0 9 * * *", "morning report",
                true, true, 1_700_000_000_000L, null, "user", null, null, null)));

        @Override public List<ScheduleEntry> list() {
            return List.copyOf(tasks);
        }

        @Override public String validateAdd(String cron) {
            // The composition root delegates to CronStore.validateNewJob; the
            // double mirrors its malformed-expression branch.
            return cron == null || StringUtils.isBlank(cron)
                || cron.trim().split("\\s+").length != 5
                ? "Invalid cron expression '" + cron + "'. Expected 5 fields: M H DoM Mon DoW."
                : null;
        }

        @Override public String add(String cron, String prompt,
                boolean recurring, boolean durable, String model) {
            String id = "abcd1234";
            tasks.add(new ScheduleEntry(id, cron, prompt, recurring, durable,
                1_700_000_000_001L, null, "user", null, null, model));
            return id;
        }

        @Override public boolean remove(String id) {
            return tasks.removeIf(task -> task.id().equals(id));
        }
    }

    /** In-memory models port double: one seeded model, tracks the api_key tri-state signal. */
    private static final class RecordingModelsPort implements GatewayModelsPort {
        record Saved(String protocol, String baseUrl, String apiKey,
            Map<String, String> headers, Long contextWindow, Boolean multimodal) {}

        final Map<String, Saved> saved = new ConcurrentHashMap<>(
            Map.of("seed-model", new Saved("anthropic", "https://api.example.com",
                "seed-key", Map.of(), null, null)));

        @Override public List<ModelEntry> list() {
            return saved.entrySet().stream()
                .map(e -> new ModelEntry(e.getKey(), e.getValue().protocol(),
                    e.getValue().baseUrl(), e.getValue().apiKey() != null,
                    e.getValue().headers(), e.getValue().contextWindow(),
                    e.getValue().multimodal()))
                .toList();
        }

        @Override public void save(String modelName, String protocol, String baseUrl,
                JsonNode apiKey, Map<String, String> headers,
                Long contextWindow, Boolean multimodal) {
            String resolvedKey = apiKey == null || apiKey.isMissingNode()
                ? saved.containsKey(modelName) ? saved.get(modelName).apiKey() : null
                : apiKey.isNull() ? null : apiKey.asText();
            saved.put(modelName, new Saved(protocol, baseUrl, resolvedKey, headers,
                contextWindow, multimodal));
        }

        @Override public boolean remove(String modelName) {
            return saved.remove(modelName) != null;
        }
    }

    private void startServer() throws IOException {
        startServer(new GatewaySessionContextPort() {});
    }

    private void startServer(GatewaySessionContextPort context) throws IOException {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {},
            new GatewaySchedulePort() {}, new GatewayModelsPort() {},
            new GatewayCommandsPort() {}, context);
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
        return toolResultMessage(toolUseId, content, isError, null);
    }

    private SDKMessage.User toolResultMessage(String toolUseId,
            List<ContentBlock> content, boolean isError, Object toolUseResult) {
        UserMessage message = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(
                new ToolResultBlock(toolUseId, content, isError))),
            false, false, toolUseResult, MessageOrigin.USER, null, null, null, null, null,
            null, null, null, null, null, null, null);
        return new SDKMessage.User(message);
    }
}
