package com.claudecode.api;

import org.apache.commons.lang3.Strings;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;


class AnthropicSdkClientRetryTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<int[]> statusSequence = new AtomicReference<>(new int[]{200});
    private final AtomicReference<String> errorBody = new AtomicReference<>(
        "{\"error\":{\"type\":\"overloaded_error\",\"message\":\"stub\"}}");
    /** okhttp-sse requires a real {@code text/event-stream} response to treat a 200 as a successful connection. */
    private boolean sseResponse = false;
    private final AtomicReference<String> requestIdHeader = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            int idx = requestCount.getAndIncrement();
            int[] sequence = statusSequence.get();
            int status = idx < sequence.length ? sequence[idx] : sequence[sequence.length - 1];
            byte[] body;
            if (status == 200 && sseResponse) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                if (requestIdHeader.get() != null) {
                    exchange.getResponseHeaders().add("request-id", requestIdHeader.get());
                }
                body = ("""
                    event: message_start
                    data: {"type":"message_start",\
                    "message":{"id":"msg_1","type":"message",\
                    "role":"assistant","content":[],\
                    "model":"claude-sonnet-4-6","stop_reason":null,\
                    "stop_sequence":null,"usage":{"input_tokens":1,\
                    "output_tokens":0}}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """)
                        .getBytes(StandardCharsets.UTF_8);
            } else if (status == 200) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                body = "{\"id\":\"msg_1\",\"model\":\"claude-sonnet-4-6\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                body = errorBody.get().getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AnthropicSdkClient client() {
        return new AnthropicSdkClient(
            new ApiConfig.AnthropicConfig("test-key", null, "claude-sonnet-4-6", baseUrl));
    }

    private CreateMessageRequest request() {
        return CreateMessageRequest.builder().model("claude-sonnet-4-6").maxTokens(100).build();
    }

    @Test
    void succeedsOnFirstTry_defaultPath_unchanged() {
        statusSequence.set(new int[]{200});
        ApiMessage response = client().createMessage(request());
        assertEquals("msg_1", response.id());
        assertEquals(1, requestCount.get());
    }

    @Test
    void retriesOn429ThenSucceeds() {
        statusSequence.set(new int[]{429, 429, 200});
        ApiMessage response = client().createMessage(request());
        assertEquals("msg_1", response.id());
        assertEquals(3, requestCount.get(), "should have retried twice before succeeding");
    }

    @Test
    void exhausts529AfterThreeConsecutive_throwsApiException() {
        statusSequence.set(new int[]{529, 529, 529, 529, 529});
        ApiException thrown = assertThrows(ApiException.class, () -> client().createMessage(request()));
        assertEquals(529, thrown.statusCode());
        assertEquals(3, requestCount.get(), "529 has its own tighter 3-attempt cap");
    }

    @Test
    void doesNotRetryOn400() {
        statusSequence.set(new int[]{400, 200});
        assertThrows(ApiException.class, () -> client().createMessage(request()));
        assertEquals(1, requestCount.get(), "non-retryable status must fail immediately");
    }

    @Test
    void promptTooLongResponseUsesDedicatedException() {
        statusSequence.set(new int[]{400});
        errorBody.set("{\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"Prompt is too long: 210000 tokens > 200000 maximum\"}}");

        PromptTooLongException thrown = assertThrows(PromptTooLongException.class,
            () -> client().createMessage(request()));

        assertEquals(400, thrown.statusCode());
        assertTrue(Strings.CS.startsWith(thrown.getMessage(), "Prompt is too long"));
        assertEquals(1, requestCount.get());
    }

    @Test
    void promptTooLongPredicateMatchesOfficialPrefixExactly() {
        assertTrue(PromptTooLongException.matches("Prompt is too long: 210000 tokens"));
        assertFalse(PromptTooLongException.matches("prompt is too long: 210000 tokens"));
        assertFalse(PromptTooLongException.matches("API error: Prompt is too long"));
    }

    @Test
    void createMessageStream_retriesOn5xxThenSucceeds() {
        sseResponse = true;
        statusSequence.set(new int[]{500, 200});
        assertNotNull(client().createMessageStream(request()));
        assertEquals(2, requestCount.get());
    }

    @Test
    void createMessageStreamAttachesTheHttpRequestIdToMessageStart() {
        sseResponse = true;
        requestIdHeader.set("req_stream_197");

        StreamEvent.MessageStart start = assertInstanceOf(StreamEvent.MessageStart.class,
            client().createMessageStream(request()).next());

        assertEquals("req_stream_197", start.requestId());
        assertEquals("msg_1", start.message().id(),
            "the HTTP request id is distinct from the Anthropic message id");
    }
}
