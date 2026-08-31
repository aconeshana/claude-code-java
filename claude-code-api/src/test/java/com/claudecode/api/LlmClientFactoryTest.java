package com.claudecode.api;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LlmClientFactory.
 */
class LlmClientFactoryTest {

    @Test
    void createsAnthropicClient() {
        ApiConfig config = ApiConfig.anthropic("test-key", "claude-sonnet-4-20250514");
        LlmClient client = LlmClientFactory.create(config);

        assertInstanceOf(AnthropicSdkClient.class, client);
        assertEquals("claude-sonnet-4-20250514", client.getModel());
    }

    @Test
    void createsOpenAiCompatClientStub() throws Exception {
        String jsonResponse = """
            {
                "id": "chatcmpl-123",
                "model": "gpt-4",
                "choices": [{"message": {"content": "Hello"}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5}
            }
            """;
        try (MockWebServer server = new MockWebServer()) {
            // Two enqueued responses: createMessage (below) consumes the
            // first; createMessageStream needs its own text/event-stream
            // response, so a second (SSE-shaped) one is queued too.
            server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body(jsonResponse).build());
            server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "text/event-stream")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n")
                .build());
            server.start();

            ApiConfig config = new ApiConfig(
                    ApiConfig.ApiProvider.OPENAI_COMPAT,
                    null,
                    new ApiConfig.OpenAiConfig("key", "gpt-4", server.url("/v1").toString()),
                    null, null);
            LlmClient client = LlmClientFactory.create(config, HttpClientFactory.shared());
            assertInstanceOf(OpenAiCompatClient.class, client);
            assertEquals("gpt-4", client.getModel());

            var request = CreateMessageRequest.builder().model("gpt-4").build();
            ApiMessage response = client.createMessage(request);
            assertNotNull(response);
            assertEquals("gpt-4", response.model());

            var stream = client.createMessageStream(request);
            assertTrue(stream.hasNext());
        }
    }

    @Test
    void createsBedrockClientStub() {
        ApiConfig config = new ApiConfig(
                ApiConfig.ApiProvider.BEDROCK,
                null, null,
                new ApiConfig.BedrockConfig("us-east-1", "anthropic.claude-v2"),
                null);

        LlmClient client = LlmClientFactory.create(config);
        assertInstanceOf(BedrockClient.class, client);
        assertEquals("anthropic.claude-v2", client.getModel());

        var request = CreateMessageRequest.builder().model("anthropic.claude-v2").build();
        ApiMessage response = client.createMessage(request);
        assertNotNull(response);
    }

    @Test
    void createsVertexClientStub() {
        ApiConfig config = new ApiConfig(
                ApiConfig.ApiProvider.VERTEX,
                null, null, null,
                new ApiConfig.VertexConfig("my-project", "us-central1", "claude-sonnet-4-20250514"));

        LlmClient client = LlmClientFactory.create(config);
        assertInstanceOf(VertexClient.class, client);
        assertEquals("claude-sonnet-4-20250514", client.getModel());

        var request = CreateMessageRequest.builder().model("claude-sonnet-4-20250514").build();
        ApiMessage response = client.createMessage(request);
        assertNotNull(response);
    }
}
