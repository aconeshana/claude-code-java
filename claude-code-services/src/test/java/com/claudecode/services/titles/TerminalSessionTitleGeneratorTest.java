package com.claudecode.services.titles;

import org.apache.commons.lang3.Strings;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.Delta;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level unit guard for the first-real-prompt terminal title helper.
 */
class TerminalSessionTitleGeneratorTest {

    @AfterEach
    void resetCostState() {
        SessionCostState.get().reset();
    }

    @Test
    void sendsThe197StructuredStreamingRequestAndParsesTitle() throws Exception {
        CapturingClient client = new CapturingClient("{\"title\":\"修复登录按钮\"}");
        AtomicReference<String> dumped = new AtomicReference<>();
        TerminalSessionTitleGenerator generator = new TerminalSessionTitleGenerator(
            client, "glm-5.2", (_, wire) -> dumped.set(wire));
        JsonNode metadata = JsonUtils.getMapper().createObjectNode()
            .put("user_id", "{\"device_id\":\"device\",\"account_uuid\":\"\",\"session_id\":\"sid\"}");

        String title = generator.generateAsync(
            "请帮我修复移动端登录按钮问题", "sid", metadata, "high").get(2, TimeUnit.SECONDS);

        assertEquals("修复登录按钮", title);
        CreateMessageRequest request = client.request.get();
        assertNotNull(request);
        assertEquals("glm-5.2", request.model());
        assertEquals(32_000, request.maxTokens());
        assertTrue(request.stream());
        assertEquals(List.of(), request.tools());
        assertTrue(request.skipCacheWrite());
        assertFalse(request.promptCachingEnabled());
        assertEquals("generate_session_title", request.querySource());
        assertNull(request.thinking());
        assertNull(request.temperature());
        assertNull(request.contextManagement());
        assertEquals(metadata, request.metadata());
        assertEquals("high", request.outputConfig().effort());
        assertEquals("json_schema", request.outputConfig().format().path("type").asText());
        assertEquals(SystemPromptConstants.CLI_SYSPROMPT_PREFIX + "\n\n"
            + TerminalSessionTitleGenerator.SESSION_TITLE_PROMPT, request.systemPrompt());

        JsonNode content = (JsonNode) request.messages().getFirst().content();
        assertEquals("""
            <session>
            请帮我修复移动端登录按钮问题
            </session>

            Write the title in the language the user wrote in, regardless of the language of the examples above.""",
            content.get(0).path("text").asText());

        JsonNode wire = JsonUtils.getMapper().readTree(dumped.get());
        assertEquals(3, wire.path("system").size());
        assertFalse(wire.at("/system/1").has("cache_control"));
        assertFalse(wire.at("/system/2").has("cache_control"));
        assertFalse(wire.at("/messages/0/content/0").has("cache_control"));
        assertEquals(1, SessionCostState.get().usageByModel().get("glm-5.2").inputTokens());
        assertEquals(2, SessionCostState.get().usageByModel().get("glm-5.2").outputTokens());
    }

    @Test
    void canonicalClaude46TitleUsesMainModelWithExplicitDisabledThinking() throws Exception {
        CapturingClient client = new CapturingClient("{\"title\":\"Title\"}");
        TerminalSessionTitleGenerator generator = new TerminalSessionTitleGenerator(
            client, "claude-sonnet-4-6");

        generator.generateAsync("a sufficiently long topic", "sid", null, "high")
            .get(2, TimeUnit.SECONDS);

        CreateMessageRequest request = client.request.get();
        assertEquals("claude-sonnet-4-6", request.model());
        assertEquals(CreateMessageRequest.ThinkingConfig.disabled(), request.thinking());
        assertEquals(1.0, request.temperature());
        assertEquals("high", request.outputConfig().effort());
    }

    @Test
    void returnsImmediatelyWithoutWaitingForResponseHeadersOrBody() throws Exception {
        CountDownLatch releaseHeaders = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(1);
        LlmClient delayedClient = new CapturingClient("{\"title\":\"Delayed title\"}") {
            @Override
            public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
                this.request.set(request);
                submitted.countDown();
                try {
                    assertTrue(releaseHeaders.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return responseEvents();
            }
        };
        TerminalSessionTitleGenerator generator = new TerminalSessionTitleGenerator(
            delayedClient, "claude-sonnet-4-6");

        CompletableFuture<String> result = generator.generateAsync(
            "a sufficiently long delayed title topic", "sid", null, "high");

        assertTrue(submitted.await(2, TimeUnit.SECONDS));
        assertFalse(result.isDone(),
            "title response headers/body must remain fully off the caller thread");
        releaseHeaders.countDown();
        assertEquals("Delayed title", result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void nonInteractiveTitleUsesAgentSdkIdentityAndSdkCliAttribution() throws Exception {
        AtomicReference<String> dumped = new AtomicReference<>();
        CapturingClient client = new CapturingClient("{\"title\":\"Title\"}");
        TerminalSessionTitleGenerator generator = new TerminalSessionTitleGenerator(
            client, "claude-sonnet-4-6", (_, wire) -> dumped.set(wire));

        generator.generateAsync("a sufficiently long topic", "sid", null, "high", true)
            .get(2, TimeUnit.SECONDS);

        CreateMessageRequest request = client.request.get();
        assertEquals(SystemPromptConstants.AGENT_SDK_SYSPROMPT_PREFIX + "\n\n"
            + TerminalSessionTitleGenerator.SESSION_TITLE_PROMPT, request.systemPrompt());
        JsonNode wire = JsonUtils.getMapper().readTree(dumped.get());
// The billing attribution must sit in its own leading block.
        assertTrue(Strings.CS.startsWith(wire.at("/system/0/text").asText(),
            "x-anthropic-billing-header:"));
        assertEquals(SystemPromptConstants.AGENT_SDK_SYSPROMPT_PREFIX,
            wire.at("/system/1/text").asText());
    }

    @Test
    void blankOrUnparseableResponsesReturnNullWithoutThrowing() throws Exception {
        TerminalSessionTitleGenerator blankInput = new TerminalSessionTitleGenerator(
            new CapturingClient("{\"title\":\"unused\"}"), "glm-5.2", (_, _) -> { });
        assertNull(blankInput.generateAsync("  ", "sid", null, null).get(2, TimeUnit.SECONDS));

        TerminalSessionTitleGenerator invalid = new TerminalSessionTitleGenerator(
            new CapturingClient("not-json"), "glm-5.2", (_, _) -> { });
        assertNull(invalid.generateAsync("real prompt", "sid", null, null).get(2, TimeUnit.SECONDS));
    }

    @Test
    void released197SkipsDescriptionsShorterThanTenCharacters() throws Exception {
        CapturingClient client = new CapturingClient("{\"title\":\"unused\"}");
        TerminalSessionTitleGenerator generator = new TerminalSessionTitleGenerator(
            client, "glm-5.2", (_, _) -> { });

        assertNull(generator.generateAsync("123456789", "sid", null, null)
            .get(2, TimeUnit.SECONDS));
        assertNull(client.request.get(), "the binary returns before creating a side request");
    }

    private static class CapturingClient implements LlmClient {
        private final String responseText;
        protected final AtomicReference<CreateMessageRequest> request = new AtomicReference<>();

        private CapturingClient(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            this.request.set(request);
            return responseEvents();
        }

        protected Iterator<StreamEvent> responseEvents() {
            return List.<StreamEvent>of(
                new StreamEvent.MessageStart(ApiMessage.builder()
                    .model(request.get().model()).usage(new Usage(1, 0, 0, 0)).build()),
                new StreamEvent.ContentBlockDelta(0, new Delta.TextDelta(responseText)),
                new StreamEvent.MessageDelta(null, new Usage(0, 2, 0, 0)),
                new StreamEvent.MessageStop()).iterator();
        }

        @Override public ApiMessage createMessage(CreateMessageRequest request) {
            throw new AssertionError("title generation must preserve stream:true");
        }

        @Override public String getModel() { return "glm-5.2"; }
    }
}
