/*
 * Acceptance scenarios adapted from yetone/magpie (https://github.com/yetone/magpie),
 * Copyright (c) 2026 yetone, licensed under the MIT License.
 */
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
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * HTTP+SSE and unary JSON wire acceptance suite adapted from {@code yetone/magpie}
 * ({@code gateway_test.go}).
 *
 * <p>Exercises the gateway's {@code /v1/messages}, {@code /v1/chat/completions}, and
 * {@code /v1/responses} endpoints against the strict wire invariants expected by standard
 * Anthropic, OpenAI Chat, and OpenAI Responses / Codex SDK clients.
 */
class MagpieGatewayProtocolAcceptanceTest {

    private static final String TOKEN = "m".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

    private final String sessionId = UUID.randomUUID().toString();
    private final SessionEventHub hub = new SessionEventHub(new NoopSink(), _ -> {});
    private final AtomicInteger submitCount = new AtomicInteger();
    private final List<SessionHostSubmission> submissions = new CopyOnWriteArrayList<>();

    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override
            public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public List<SessionHostInfo> list() {
                return List.of();
            }
        });

    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(15))
        .build();

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /** Adapted from magpie {@code gateway_test.go:TestRequestValidationBeforeRouting}. */
    @Test
    @Timeout(15)
    void requestValidationRejectsInvalidPayloadsBeforeSessionSubmission() throws Exception {
        startServerWithTurn(_ -> {});
        List<String> invalidBodies = List.of(
            "",
            "{\"model\":\"m1\",\"input\":",
            "null",
            "[]",
            "{}"
        );
        for (String path : List.of("/v1/messages", "/v1/chat/completions", "/v1/responses")) {
            for (String body : invalidBodies) {
                try (Response response = postRaw(path, body)) {
                    assertThat(response.code())
                        .as("path=%s body=%s", path, body)
                        .isEqualTo(400);
                    String respBody = response.body().string();
                    assertThat(JsonUtils.parseTree(respBody).has("error")).isTrue();
                }
            }
        }
        assertThat(submitCount.get()).isZero();
    }

    /** Adapted from magpie {@code gateway_test.go:TestAnthropicClientChatUpstream}. */
    @Test
    @Timeout(15)
    void messagesStreamEmitsOrderedThinkingTextAndToolUseBlocks() throws Exception {
        startServerWithTurn(_ -> {
            hub.onMessage(new SDKMessage.Assistant(
                new AssistantMessage(UUID.randomUUID().toString(),
                    new AssistantContent(null, List.of(
                        new ThinkingBlock("hmm", "sig_1"),
                        new TextBlock("Hi there"),
                        new ToolUseBlock("call_1", "read",
                            JsonUtils.parseTree("{\"path\":\"a.go\"}"))), null)),
                Usage.EMPTY, "claude-sonnet-5"));
            hub.onMessage(new SDKMessage.User(new UserMessage(UUID.randomUUID().toString(),
                new MessageContent(null, List.<ContentBlock>of(
                    new ToolResultBlock("call_1", List.of(new TextBlock("ok")),
                        false, false, false))))));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 5L, null, null, null, null));
        });

        try (Response response = postRaw("/v1/messages",
                "{\"model\":\"m1\",\"max_tokens\":100,\"stream\":true,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"read a.go\"}]}")) {
            assertThat(response.code()).isEqualTo(200);
            List<JsonNode> dataEvents = parseSseJsonData(response.body().string());
            List<String> eventTypes = dataEvents.stream()
                .map(e -> e.path("type").asText())
                .toList();
            assertThat(eventTypes).containsExactly(
                "message_start",
                "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop",
                "message_delta", "message_stop");
            assertThat(dataEvents.get(1).path("content_block").path("type").asText())
                .isEqualTo("thinking");
            assertThat(dataEvents.get(7).path("content_block").path("type").asText())
                .isEqualTo("tool_use");
            assertThat(dataEvents.get(7).path("content_block").path("name").asText())
                .isEqualTo("read");
        }
    }

    /** Adapted from magpie {@code chat.go:chatEncoder} & {@code chat_from_anthropic_test.go}. */
    @Test
    @Timeout(15)
    void chatCompletionsStreamMatchesStrictOpenAiChunkSchemaAndDoneSentinel() throws Exception {
        startServerWithTurn(_ -> {
            hub.onMessage(new SDKMessage.Assistant(
                new AssistantMessage(UUID.randomUUID().toString(),
                    new AssistantContent(null, List.of(
                        new ToolUseBlock("call_1", "Bash",
                            JsonUtils.parseTree("{\"command\":\"ls\"}"))), null)),
                Usage.EMPTY, "gpt-4o"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 3L, null, null, null, null));
        });

        try (Response response = postRaw("/v1/chat/completions",
                "{\"model\":\"gpt-4o\",\"stream\":true,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"ls\"}]}")) {
            assertThat(response.code()).isEqualTo(200);
            String rawSse = response.body().string();
            List<JsonNode> chunks = parseSseJsonData(rawSse);
            assertThat(chunks).isNotEmpty();

            JsonNode first = chunks.getFirst();
            assertThat(first.path("created").isIntegralNumber())
                .as("created must be an integer Unix timestamp, got: %s", first.get("created"))
                .isTrue();
            JsonNode openingRole = first.path("choices").get(0).path("delta").path("role");
            assertThat(openingRole.isTextual() && Strings.CS.equals("assistant", openingRole.asText()))
                .as("opening delta.role must be \"assistant\", got: %s", openingRole)
                .isTrue();

            JsonNode toolCallChunk = chunks.stream()
                .filter(c -> c.path("choices").get(0).path("delta").has("tool_calls"))
                .findFirst().orElseThrow();
            assertThat(toolCallChunk.path("choices").get(0).path("delta")
                .path("tool_calls").get(0).path("type").asText())
                .isEqualTo("function");
            assertThat(Strings.CS.contains(rawSse, "data: [DONE]")).isTrue();
        }
    }

    /** Adapted from magpie {@code gateway_test.go:TestResponsesEventsAreClientShaped}. */
    @Test
    @Timeout(15)
    void responsesStreamEventsAreClientShapedWithSequenceNumberCreatedAtAndPopulatedOutput()
            throws Exception {
        startServerWithTurn(_ -> {
            hub.onMessage(new SDKMessage.Assistant(
                new AssistantMessage(UUID.randomUUID().toString(),
                    new AssistantContent(null, List.of(new TextBlock("ok")), null)),
                Usage.EMPTY, "gpt-5"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 2L, null, null, null, null));
        });

        try (Response response = postRaw("/v1/responses",
                "{\"model\":\"gpt-5\",\"stream\":true,\"input\":\"hi\"}")) {
            assertThat(response.code()).isEqualTo(200);
            List<JsonNode> events = parseSseJsonData(response.body().string());
            assertThat(events).isNotEmpty();
            int expectedSeq = 0;
            for (JsonNode event : events) {
                String type = event.path("type").asText();
                assertThat(event.hasNonNull("sequence_number"))
                    .as("%s must carry sequence_number: %s", type, event)
                    .isTrue();
                assertThat(event.path("sequence_number").asInt()).isEqualTo(expectedSeq++);
                if (event.has("response") && event.get("response").isObject()) {
                    JsonNode resp = event.get("response");
                    for (String field : List.of("id", "object", "created_at", "status", "model", "output")) {
                        assertThat(resp.has(field))
                            .as("%s.response lacks %s: %s", type, field, resp)
                            .isTrue();
                    }
                }
            }
            JsonNode terminal = events.getLast();
            assertThat(terminal.path("type").asText()).isEqualTo("response.completed");
            assertThat(terminal.path("response").path("output")).isNotEmpty();
        }
    }

    /** Adapted from magpie {@code gateway_test.go:TestChatClientAnthropicUpstream}. */
    @Test
    @Timeout(15)
    void nonStreamingRequestsReturnUnaryJsonResponsesAcrossAllThreeFaces() throws Exception {
        startServerWithTurn(_ -> {
            hub.onMessage(new SDKMessage.Assistant(
                new AssistantMessage(UUID.randomUUID().toString(),
                    new AssistantContent(null, List.of(new TextBlock("pong")), null)),
                Usage.EMPTY, "m1"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 1L, null, null, null, null));
        });

        try (Response chatResp = postRaw("/v1/chat/completions",
                "{\"model\":\"m1\",\"stream\":false,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}")) {
            assertThat(chatResp.code()).isEqualTo(200);
            assertThat(chatResp.header("Content-Type")).contains("application/json");
            JsonNode json = JsonUtils.parseTree(chatResp.body().string());
            assertThat(json.path("object").asText()).isEqualTo("chat.completion");
            assertThat(json.path("choices").get(0).path("message").path("content").asText())
                .isEqualTo("pong");
        }

        try (Response msgResp = postRaw("/v1/messages",
                "{\"model\":\"m1\",\"max_tokens\":64,\"stream\":false,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}")) {
            assertThat(msgResp.code()).isEqualTo(200);
            assertThat(msgResp.header("Content-Type")).contains("application/json");
            JsonNode json = JsonUtils.parseTree(msgResp.body().string());
            assertThat(json.path("type").asText()).isEqualTo("message");
            assertThat(json.path("content").get(0).path("text").asText()).isEqualTo("pong");
        }

        try (Response respFace = postRaw("/v1/responses",
                "{\"model\":\"m1\",\"stream\":false,\"input\":\"ping\"}")) {
            assertThat(respFace.code()).isEqualTo(200);
            assertThat(respFace.header("Content-Type")).contains("application/json");
            JsonNode json = JsonUtils.parseTree(respFace.body().string());
            assertThat(json.path("object").asText()).isEqualTo("response");
            assertThat(json.path("output").get(0).path("content").get(0).path("text").asText())
                .isEqualTo("pong");
        }
    }

    /** Adapted from magpie {@code image_input_test.go}. */
    @Test
    @Timeout(15)
    void chatAndResponsesExtractDataUrlImageAndPdfAttachments() throws Exception {
        startServerWithTurn(_ -> hub.onTurnComplete(new TurnOutcome(
            false, false, false, false, false, 1L, null, null, null, null)));

        String pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
            + "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
        String chatBody = "{\"model\":\"gpt-4o\",\"stream\":true,\"messages\":[{\"role\":\"user\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"inspect\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,"
            + pngBase64 + "\"}}]}]}";

        try (Response response = postRaw("/v1/chat/completions", chatBody)) {
            assertThat(response.code()).isEqualTo(200);
            response.body().string();
        }

        String responsesBody = "{\"model\":\"gpt-5\",\"stream\":true,\"input\":[{\"type\":\"message\","
            + "\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"inspect\"},"
            + "{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64," + pngBase64 + "\"},"
            + "{\"type\":\"input_file\",\"filename\":\"a.pdf\","
            + "\"file_data\":\"data:application/pdf;base64,JVBERi0xLjQK\"}]}]}";

        try (Response response = postRaw("/v1/responses", responsesBody)) {
            assertThat(response.code()).isEqualTo(200);
            response.body().string();
        }

        assertThat(submissions).hasSize(2);
        assertThat(submissions.getFirst().images()).hasSize(1);
        assertThat(submissions.get(1).images()).hasSize(1);
        assertThat(submissions.get(1).attachments()).hasSize(1);
    }

    private Response postRaw(String path, String body) throws IOException {
        return client.newCall(new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + path)
            .header("Authorization", "Bearer " + TOKEN)
            .post(RequestBody.create(body, JSON))
            .build()).execute();
    }

    private static List<JsonNode> parseSseJsonData(String rawSse) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String line : rawSse.split("\n")) {
            String trimmed = line.trim();
            if (!Strings.CS.startsWith(trimmed, "data:")) continue;
            String payload = trimmed.substring("data:".length()).trim();
            if (payload.isEmpty() || Strings.CS.equals("[DONE]", payload)) continue;
            events.add(JsonUtils.parseTree(payload));
        }
        return events;
    }

    private void startServerWithTurn(Consumer<String> turnBody) throws IOException {
        server = GatewayServer.builder(
            new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry).build();
        server.start();
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "", 0, Instant.now(), ""),
            hub, submission -> {
                submitCount.incrementAndGet();
                submissions.add(submission);
                hub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(),
                    null, "default"));
                turnBody.accept(submission.prompt());
                return CompletableFuture.completedFuture(null);
            });
        registry.activateLocal(session);
    }

    private static final class NoopSink implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
