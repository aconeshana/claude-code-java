package com.claudecode.api;

import com.claudecode.core.model.ModelApiProtocol;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiRetryIntegrationTest {

    @Test
    void responsesRetriesTransient500ThenSucceeds() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(serverError());
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"resp_retry","model":"gpt-test","status":"completed",
                     "output":[{"type":"message","role":"assistant",
                                "content":[{"type":"output_text","text":"ok"}]}],
                     "usage":{"input_tokens":1,"output_tokens":1}}
                    """).build());
            server.start();

            var client = new OpenAiResponsesClient(config(server, ModelApiProtocol.OPENAI_RESPONSES));
            ApiMessage response = client.createMessage(request());

            assertEquals("resp_retry", response.id());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void chatRetriesTransient500ThenSucceeds() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(serverError());
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"chat_retry","model":"gpt-test",
                     "choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1}}
                    """).build());
            server.start();

            var client = new OpenAiCompatClient(config(server, ModelApiProtocol.OPENAI_CHAT));
            ApiMessage response = client.createMessage(request());

            assertEquals("chat_retry", response.id());
            assertEquals(2, server.getRequestCount());
        }
    }

    private static ApiConfig.OpenAiConfig config(MockWebServer server, ModelApiProtocol protocol) {
        return new ApiConfig.OpenAiConfig(
            "key", "gpt-test", server.url("/v1").toString(), protocol, Map.of());
    }

    private static CreateMessageRequest request() {
        return CreateMessageRequest.builder().model("gpt-test").stream(false).build();
    }

    private static MockResponse serverError() {
        return new MockResponse.Builder().code(500)
            .addHeader("Content-Type", "application/json")
            .body("{\"error\":{\"type\":\"server_error\",\"message\":\"transient\"}}")
            .build();
    }
}
