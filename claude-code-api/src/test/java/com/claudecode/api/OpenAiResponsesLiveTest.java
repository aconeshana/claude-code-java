package com.claudecode.api;

import com.claudecode.core.message.TextBlock;
import com.claudecode.core.model.ModelApiProtocol;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * <ul>
 *   <li>Opt-in live smoke test for an OpenAI Responses-compatible endpoint.</li>
 * </ul>
 */
class OpenAiResponsesLiveTest {

    @Test
    void liveResponsesEndpointAcceptsTheJavaWireFormat() {
        String baseUrl = System.getenv("LIVE_RESPONSES_BASE_URL");
        String apiKey = System.getenv("LIVE_RESPONSES_API_KEY");
        String model = System.getenv("LIVE_RESPONSES_MODEL");
        Assumptions.assumeTrue(StringUtils.isNotBlank(baseUrl));
        Assumptions.assumeTrue(StringUtils.isNotBlank(apiKey));
        Assumptions.assumeTrue(StringUtils.isNotBlank(model));

        var client = new OpenAiResponsesClient(new ApiConfig.OpenAiConfig(
            apiKey, model, baseUrl, ModelApiProtocol.OPENAI_RESPONSES, Map.of()));
        ApiMessage response = client.createMessage(CreateMessageRequest.builder()
            .model(model)
            .maxTokens(32)
            .systemPrompt("Follow the latest user instruction.")
            .messages(List.of(
                new CreateMessageRequest.RequestMessage("user", "Reply with exactly: first"),
                new CreateMessageRequest.RequestMessage("assistant", "first"),
                new CreateMessageRequest.RequestMessage("user", "Now reply with exactly: pong")))
            .stream(false)
            .build(), 30_000);

        assertFalse(response.content().stream()
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::text)
            .allMatch(String::isBlank));
    }
}
