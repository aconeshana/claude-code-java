package com.claudecode.core.engine;

import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ToolReferenceBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelApiProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolSearchGate#extractDiscoveredToolNames} and the parameterized
 * third-party proxy guard — the halves that don't depend on process environment.
 */
class ToolSearchGateTest {

    @AfterEach
    void resetProcessConfiguration() {
        ToolSearchGate.configureResolvedBaseUrl(null);
        ToolSearchGate.configureProtocolResolver(null);
    }

    @Test
    void enabledOnlyForAnthropicProtocol() {
        ToolSearchGate.configureResolvedBaseUrl("https://api.anthropic.com");
        ToolSearchGate.configureProtocolResolver(model -> switch (model) {
            case "chat-model" -> ModelApiProtocol.OPENAI_CHAT;
            case "responses-model" -> ModelApiProtocol.OPENAI_RESPONSES;
            default -> ModelApiProtocol.ANTHROPIC;
        });

        assertTrue(ToolSearchGate.isEnabled("claude-model"));
        assertFalse(ToolSearchGate.isEnabled("chat-model"));
        assertFalse(ToolSearchGate.isEnabled("responses-model"));
    }

    @Test
    void extractDiscoveredToolNames_emptyForNoMessages() {
        assertTrue(ToolSearchGate.extractDiscoveredToolNames(List.of()).isEmpty());
    }

    @Test
    void extractDiscoveredToolNames_findsToolReferenceInsideToolResult() {
        UserMessage um = new UserMessage("u1", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("id1", List.of(new ToolReferenceBlock("WebFetch")), false))));

        assertEquals(Set.of("WebFetch"), ToolSearchGate.extractDiscoveredToolNames(List.of(um)));
    }

    @Test
    void extractDiscoveredToolNames_accumulatesAcrossMultipleMessages() {
        UserMessage first = new UserMessage("u1", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("id1", List.of(new ToolReferenceBlock("WebFetch")), false))));
        UserMessage second = new UserMessage("u2", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("id2", List.of(new ToolReferenceBlock("CronCreate")), false))));

        assertEquals(Set.of("WebFetch", "CronCreate"),
            ToolSearchGate.extractDiscoveredToolNames(List.of(first, second)));
    }

    @Test
    void extractDiscoveredToolNames_ignoresPlainTextToolResults() {
        UserMessage um = new UserMessage("u1", MessageContent.ofText("hello"));

        assertTrue(ToolSearchGate.extractDiscoveredToolNames(List.of(um)).isEmpty());
    }

    @Test
    void resolvedCliBaseUrlParticipatesInTheThirdPartyToolSearchGuard() {
        assertTrue(ToolSearchGate.isThirdPartyProxyDefaultDisabled(
            "https://gateway.example.com", null, false, false));
        assertFalse(ToolSearchGate.isThirdPartyProxyDefaultDisabled(
            "https://api.anthropic.com", null, false, false));
        assertFalse(ToolSearchGate.isThirdPartyProxyDefaultDisabled(
            "https://gateway.example.com", "true", false, false));
    }
}
