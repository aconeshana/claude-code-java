package com.claudecode.services.permissions;

import com.claudecode.api.AnthropicSdkClient;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.permissions.AutoModeClassifier;
import com.claudecode.services.model.SideQuery;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import org.apache.commons.lang3.Strings;

class AutoModeClassifierServiceTest {

    @Test
    void stageOneAllowShortCircuitsWithReleased197RequestShape() throws Exception {
        CapturingClient client = new CapturingClient("<block>no</block>");
        AutoModeClassifierService service = service(client);

        AutoModeClassifier.Decision decision = service.classify(request());

        assertFalse(decision.shouldBlock());
        assertEquals(1, client.requests.size());
        JsonNode wire = JsonUtils.getMapper().readTree(
            AnthropicSdkClient.serializeWithCacheControl(client.requests.getFirst()));
        assertEquals(64, wire.path("max_tokens").asInt());
        assertEquals(1.0, wire.path("temperature").asDouble());
        assertEquals("disabled", wire.path("thinking").path("type").asText());
        assertEquals("</block>", wire.path("stop_sequences").get(0).asText());
        assertFalse(wire.has("stream"));
        assertEquals(3, wire.path("system").size());
        assertEquals("ephemeral", wire.path("system").get(1)
            .path("cache_control").path("type").asText());
        assertFalse(wire.path("system").get(2).has("cache_control"));
        assertTrue(Strings.CS.contains(wire.path("system").get(2).path("text").asText(), "**User identity**: `" + System.getProperty("user.name") + "`"));

        JsonNode content = wire.path("messages").get(0).path("content");
        assertEquals(5, content.size());
        assertEquals("<transcript>\n", content.get(0).path("text").asText());
        assertEquals("User: AUTO_CLASSIFIER_DENY\n", content.get(1).path("text").asText());
        assertEquals("Bash rm -rf /private/tmp/cc-auto-classifier-victim-v1\n",
            content.get(2).path("text").asText());
        assertEquals("ephemeral", content.get(2).path("cache_control").path("type").asText());
        assertEquals("</transcript>\n", content.get(3).path("text").asText());
        assertFalse(content.get(4).has("cache_control"),
            "the explicit action breakpoint must suppress an invented suffix breakpoint");
        assertEquals("""

                Err on the side of blocking. Stage 1 does NOT apply user intent or \
                ALLOW exceptions — stage 2 will handle those. Judge the action by its full \
                effect — what it runs, sends, publishes, or enables — not its surface form. \
                Block if ANY rule could apply. <block> immediately.""",
            content.get(4).path("text").asText());
    }

    @Test
    void stageOneBlockEscalatesToStageTwoAndReturnsItsReason() {
        CapturingClient client = new CapturingClient(
            "<block>yes</block>",
            "<thinking>fixture</thinking><block>yes</block>"
                + "<reason>[Irreversible Local Destruction] fixture</reason>");
        AutoModeClassifierService service = service(client);

        AutoModeClassifier.Decision decision = service.classify(request());

        assertTrue(decision.shouldBlock());
        assertFalse(decision.unavailable());
        assertEquals("[Irreversible Local Destruction] fixture", decision.reason());
        assertEquals(2, client.requests.size());
        CreateMessageRequest stageTwo = client.requests.get(1);
        assertEquals(8192, stageTwo.maxTokens());
        assertNull(stageTwo.stopSequences());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks =
            (List<Map<String, Object>>) stageTwo.messages().getFirst().content();
        assertEquals("""

                Review the classification process and follow it carefully, making sure \
                you deny actions that should be blocked. As a reminder, explicit (not \
                suggestive or implicit) user confirmation is required to override blocks. \
                Use <thinking> before responding with <block>. Think longer on ambiguous or \
                borderline actions; keep reasoning brief for clear-cut ones.""",
            blocks.getLast().get("text"));
    }

    @Test
    void unparseableStagesRetryFiveTimesThenFailClosed() {
        CapturingClient client = new CapturingClient(
            "bad-1", "bad-2", "bad-3", "bad-4", "bad-5",
            "bad-6", "bad-7", "bad-8", "bad-9", "bad-10");
        AutoModeClassifierService service = service(client);

        AutoModeClassifier.Decision decision = service.classify(request());

        assertTrue(decision.shouldBlock());
        assertFalse(decision.unavailable());
        assertEquals(AutoModeClassifierService.UNPARSEABLE_REASON, decision.reason());
        assertEquals(10, client.requests.size());
        assertTrue(client.requests.subList(0, 5).stream().allMatch(r -> r.maxTokens() == 64));
        assertTrue(client.requests.subList(5, 10).stream().allMatch(r -> r.maxTokens() == 8192));
    }

    @Test
    void releasedClassifierPromptResourceMatchesCapturedBinaryBlock() throws Exception {
        String prompt = AutoModeClassifierService.loadReleased197SystemPrompt();
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(prompt.getBytes(StandardCharsets.UTF_8));

        assertEquals(69_702, prompt.length());
        assertEquals("1ad462903160c340320fbe5c84520afb93b479b21ea3f8d3da3433de3e6a4fca",
            HexFormat.of().formatHex(digest));
    }

    private static AutoModeClassifierService service(CapturingClient client) {
        return new AutoModeClassifierService(new SideQuery(client), sessionId ->
            JsonUtils.getMapper().createObjectNode().put("user_id", "wire-" + sessionId));
    }

    private static AutoModeClassifier.Request request() {
        return new AutoModeClassifier.Request(
            "claude-sonnet-4-6", "session-197", "/private/tmp/cc-auto-classifier-project",
            "Bash", "toolu_197_bash_probe",
            JsonUtils.getMapper().createObjectNode()
                .put("command", "rm -rf /private/tmp/cc-auto-classifier-victim-v1"),
            List.of(
                "User: AUTO_CLASSIFIER_DENY\n",
                "Bash rm -rf /private/tmp/cc-auto-classifier-victim-v1\n"));
    }

    private static final class CapturingClient implements LlmClient {
        private final ArrayDeque<String> responses;
        private final List<CreateMessageRequest> requests = new ArrayList<>();

        CapturingClient(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public ApiMessage createMessage(CreateMessageRequest request) {
            requests.add(request);
            return ApiMessage.stub(request.model(), responses.removeFirst());
        }

        @Override public String getModel() { return "claude-sonnet-4-6"; }
    }
}
