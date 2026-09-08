package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Interop tests for the Chat Completions and Responses protocol faces over
 * real loopback HTTP — the wire frames each protocol's own client stack
 * (OpenAiCompatClient / OpenAiResponsesClient shapes) consumes.
 */
class ChatResponsesHandlerInteropTest {

    private static final String TOKEN = "c".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

    private final String sessionId = UUID.randomUUID().toString();
    private final SessionEventHub hub =
        new SessionEventHub(new NoopSink(), _ -> {});
    private final List<String> submittedPrompts = new CopyOnWriteArrayList<>();

    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(
                    SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by these tests");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
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
    void chatCompletionsStreamsChunksWithFinishReason() throws Exception {
        startServerWithTurn(_ -> {
            hub.onMessage(textMessage("Hello via chat"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 3L, null, null, null, null));
        });
        List<String> events = new CopyOnWriteArrayList<>();
        EventSource source = post("/v1/chat/completions",
            "{\"model\":\"gpt-4o\",\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
            events);

        pollUntil(events, "chat.completion.chunk");
        pollUntil(events, "Hello via chat");
        pollUntil(events, "\"finish_reason\":\"stop\"");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void chatCompletionsMapsToolCallsToToolCallsDelta() throws Exception {
        startServerWithTurn(_ -> {
            hub.onMessage(new SDKMessage.Assistant(
                new AssistantMessage(UUID.randomUUID().toString(),
                    new AssistantContent(null, List.of(
                        new ToolUseBlock("call_1", "Bash", null)), null)),
                Usage.EMPTY, "claude-sonnet-5"));
            hub.onMessage(toolResult("call_1", false));
            hub.onMessage(textMessage("done"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 5L, null, null, null, null));
        });
        List<String> events = new CopyOnWriteArrayList<>();
        EventSource source = post("/v1/chat/completions",
            "{\"model\":\"gpt-4o\",\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"run it\"}]}",
            events);

        pollUntil(events, "chat.completion.chunk");
        pollUntil(events, "\"tool_calls\"");
        pollUntil(events, "\"name\":\"Bash\"");
        pollUntil(events, "\"finish_reason\":\"tool_calls\"");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void responsesStreamsItemAndDeltaEvents() throws Exception {
        startServerWithTurn(_ -> {
            hub.onMessage(new SDKMessage.Assistant(
                new AssistantMessage(UUID.randomUUID().toString(),
                    new AssistantContent(null, List.of(
                        new ThinkingBlock("pondering", null),
                        new TextBlock("answer text")), null)),
                Usage.EMPTY, "claude-sonnet-5"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 4L, null, null, null, null));
        });
        List<String> events = new CopyOnWriteArrayList<>();
        EventSource source = post("/v1/responses",
            "{\"model\":\"gpt-5\",\"stream\":true,\"input\":\"tell me\"}",
            events);

        pollUntil(events, "response.created");
        pollUntil(events, "response.reasoning_text.delta");
        pollUntil(events, "pondering");
        pollUntil(events, "response.output_text.delta");
        pollUntil(events, "answer text");
        pollUntil(events, "response.output_item.done");
        pollUntil(events, "response.completed");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void responsesAcceptsArrayInputWithLastUserMessage() throws Exception {
        startServerWithTurn(_ -> {
            hub.onMessage(textMessage("array input works"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 2L, null, null, null, null));
        });
        List<String> events = new CopyOnWriteArrayList<>();
        EventSource source = post("/v1/responses",
            "{\"model\":\"gpt-5\",\"stream\":true,\"input\":["
                + "{\"type\":\"message\",\"role\":\"system\",\"content\":\"be brief\"},"
                + "{\"type\":\"message\",\"role\":\"user\",\"content\":["
                + "{\"type\":\"input_text\",\"text\":\"actual prompt\"}]}]}",
            events);

        pollUntil(events, "array input works");
        pollUntil(events, "response.completed");
        // The prompt reaching the session is the last user message only.
        assertThat(submittedPrompts).singleElement()
            .isEqualTo("actual prompt");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void chatCompletionsInvalidBodyReturnsOpenAiErrorShape() throws Exception {
        startServerWithTurn(_ -> {});
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/v1/chat/completions"))
                .header("Authorization", "Bearer " + TOKEN)
                .post(RequestBody.create(
                    "{\"model\":\"gpt-4o\",\"messages\":[]}", JSON))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("\"error\":")
                .contains("invalid_request_error");
        }
    }

    @Test
    @Timeout(20)
    void responsesInvalidBodyReturnsErrorShape() throws Exception {
        startServerWithTurn(_ -> {});
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/v1/responses"))
                .header("Authorization", "Bearer " + TOKEN)
                .post(RequestBody.create(
                    "{\"model\":\"gpt-5\",\"input\":123}", JSON))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("\"error\":")
                .contains("invalid_request");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private EventSource post(String path, String body, List<String> events) {
        return EventSources.createFactory(client).newEventSource(
            new Request.Builder()
                .url(url(path))
                .header("Authorization", "Bearer " + TOKEN)
                .post(RequestBody.create(body, JSON))
                .build(),
            new EventSourceListener() {
                @Override public void onEvent(EventSource source, String id, String type,
                        String data) {
                    events.add((type == null ? "" : type) + "|" + data);
                }
            });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    /** Installs the active session; the scripted turn body runs on submit. */
    private void startServerWithTurn(Consumer<String> turnBody)
            throws IOException {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry);
        server.start();
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "", 0, Instant.now(), ""),
            hub, submission -> {
                submittedPrompts.add(submission.prompt());
                // A real TurnEngine always opens the turn with onTurnStart
                // before any output; the scripted body matches that contract.
                hub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(),
                    null, "default"));
                turnBody.accept(submission.prompt());
                return CompletableFuture.completedFuture(null);
            });
        registry.activateLocal(session);
    }

    /**
     * Waits until any frame contains the expected text. Frames accumulate in
     * the list (not consumed) so successive assertions can match the same
     * frame — protocol chunks often carry several fields one assertion each.
     */
    private static void pollUntil(List<String> events, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (events) {
                for (String frame : events) {
                    if (Strings.CS.contains(frame, expected)) return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("frame containing '" + expected + "' did not arrive");
    }

    private static final class NoopSink implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }

    private static SDKMessage.Assistant textMessage(String text) {
        return new SDKMessage.Assistant(
            new AssistantMessage(UUID.randomUUID().toString(),
                new AssistantContent(null, List.of(new TextBlock(text)), null)),
            Usage.EMPTY, "claude-sonnet-5");
    }

    private static SDKMessage.User toolResult(String toolUseId, boolean error) {
        return new SDKMessage.User(new UserMessage(UUID.randomUUID().toString(),
            new MessageContent(null, List.<ContentBlock>of(new ToolResultBlock(
                toolUseId, List.of(new TextBlock("output")), error, false, false)))));
    }
}
