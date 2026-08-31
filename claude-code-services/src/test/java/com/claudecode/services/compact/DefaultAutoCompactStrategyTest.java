package com.claudecode.services.compact;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;




class DefaultAutoCompactStrategyTest {

    private final DefaultAutoCompactStrategy strategy =
        new DefaultAutoCompactStrategy(TokenEstimator.getInstance());

    @Test
    void sonnet46UsesThe200kModelDefaultAnd167kTrigger() {
        assertEquals(200_000, DefaultAutoCompactStrategy.getModelContextWindow("claude-sonnet-4-6"));
        assertEquals(180_000, strategy.getEffectiveContextWindowSize("claude-sonnet-4-6"));
        assertEquals(167_000, strategy.getAutoCompactThreshold("claude-sonnet-4-6"));
        assertEquals("model-default", strategy.getAutoCompactSource("claude-sonnet-4-6"));

        assertFalse(strategy.shouldTrigger(
            List.of(measured(166_999)), "claude-sonnet-4-6", "sdk", true));
        assertTrue(strategy.shouldTrigger(
            List.of(measured(167_000)), "claude-sonnet-4-6", "sdk", true));
    }

    @Test
    void unknownGatewayModelUsesTheOfficialFallbackWindowWithoutAWhitelist() {
        assertEquals("auto", strategy.getAutoCompactSource("glm-5.2"));
        assertFalse(strategy.shouldTrigger(
            List.of(measured(166_999)), "glm-5.2", "sdk", true));
        assertTrue(strategy.shouldTrigger(
            List.of(measured(167_000)), "glm-5.2", "sdk", true),
            "official auto-compact applies its 200k fallback to unknown model ids");
    }

    @Test
    void sonnet5UsesFallbackWindowAndTriggersAutoCompact() {
        assertEquals(200_000L,
            DefaultAutoCompactStrategy.getModelContextWindow("anthropic.claude-sonnet-5"));
        assertEquals(167_000L,
            strategy.getAutoCompactThreshold("anthropic.claude-sonnet-5"));
        assertTrue(strategy.shouldTrigger(
            List.of(measured(167_000)), "anthropic.claude-sonnet-5", "sdk", true));
    }

    @Test
    void configuredCustomModelWindowEnablesReactiveAutoCompact() {
        strategy.setCustomContextWindowResolver(
            model -> Strings.CS.equals("gpt-custom", model) ? 400_000L : null);

        assertEquals("custom-model", strategy.getAutoCompactSource("gpt-custom"));
        assertEquals(367_000L, strategy.getAutoCompactThreshold("gpt-custom"));
        assertFalse(strategy.shouldTrigger(
            List.of(measured(366_999)), "gpt-custom", "sdk", true));
        assertTrue(strategy.shouldTrigger(
            List.of(measured(367_000)), "gpt-custom", "sdk", true));
    }

    @Test
    void configuredCustomGptUsesCodexCacheAndUtf8Accounting() {
        strategy.setCustomContextWindowResolver(_ -> 200_000L);
        AssistantMessage measured = new AssistantMessage("assistant-gpt",
            AssistantContent.of("resp-gpt", List.of(new TextBlock("OK")),
                new Usage(80_000, 1_000, 0, 80_000)));
        var tail = new UserMessage("user-tail",
            MessageContent.ofText("你好世界"));

        assertEquals(4, DefaultAutoCompactStrategy.charsPerTokenForModel("gpt-5.6-sol"));
        assertFalse(strategy.shouldTrigger(
            List.of(measured, tail), "gpt-5.6-sol", "sdk", true),
            "provider adapters expose disjoint uncached and cache-read buckets");
    }

    @Test
    void gpt56Uses372kModelDefaultAnd339kTrigger() {
        assertEquals(372_000L,
            DefaultAutoCompactStrategy.getModelContextWindow("gpt-5.6-sol"));
        assertEquals(352_000L, strategy.getEffectiveContextWindowSize("gpt-5.6-sol"));
        assertEquals(339_000L, strategy.getAutoCompactThreshold("gpt-5.6-sol"));
        assertEquals("model-default", strategy.getAutoCompactSource("gpt-5.6-sol"));

        assertFalse(strategy.shouldTrigger(
            List.of(measured(338_999)), "gpt-5.6-sol", "sdk", true));
        assertTrue(strategy.shouldTrigger(
            List.of(measured(339_000)), "gpt-5.6-sol", "sdk", true));

        strategy.setCustomContextWindowResolver(
            model -> Strings.CS.equals("gpt-5.6-sol", model) ? 400_000L : null);
        assertEquals("custom-model", strategy.getAutoCompactSource("gpt-5.6-sol"));
        assertEquals(367_000L, strategy.getAutoCompactThreshold("gpt-5.6-sol"));
    }

    @Test
    void released197ClampsExplicitAutoCompactWindowsTo100kThrough1m() {
        assertEquals(100_000L, DefaultAutoCompactStrategy.parseAutoCompactWindowOverride("50000"));
        assertEquals(150_000L, DefaultAutoCompactStrategy.parseAutoCompactWindowOverride("150000"));
        assertEquals(150_000L, DefaultAutoCompactStrategy.parseAutoCompactWindowOverride(" 150000suffix"));
        assertEquals(1_000_000L, DefaultAutoCompactStrategy.parseAutoCompactWindowOverride("2000000"));
        assertNull(DefaultAutoCompactStrategy.parseAutoCompactWindowOverride("0"));
        assertNull(DefaultAutoCompactStrategy.parseAutoCompactWindowOverride("invalid"));

        assertEquals(67_000L,
            strategy.getAutoCompactThreshold("claude-sonnet-4-6", "50000", null));
    }

    @Test
    void summaryReserveUsesTheModelsOutputLimitInsteadOfAlways20k() {
        assertEquals(195_904L, strategy.getEffectiveContextWindowSize("claude-3-haiku-20240307"));
        assertEquals(182_904L, strategy.getAutoCompactThreshold("claude-3-haiku-20240307"));
    }

    @Test
    void disabledAutoCompactMeasuresWarningsAgainstTheEffectiveWindow() {
        CompactService.TokenWarningState enabled =
            strategy.calculateTokenWarningState(148_000L, "claude-sonnet-4-6", true);
        CompactService.TokenWarningState disabled =
            strategy.calculateTokenWarningState(148_000L, "claude-sonnet-4-6", false);

        assertTrue(enabled.isAboveWarningThreshold());
        assertFalse(disabled.isAboveWarningThreshold());
        assertEquals(18L, disabled.percentLeft());
    }

    @Test
    void explicitCompactWindowDoesNotLowerTheRawModelBlockingLimit() {
        assertEquals(80_000L,
            strategy.getEffectiveContextWindowSize("claude-sonnet-4-6", "50000"));
        assertEquals(177_000L, strategy.getDefaultBlockingLimit("claude-sonnet-4-6"));
    }

    private static AssistantMessage measured(long inputTokens) {
        return new AssistantMessage("assistant-" + inputTokens,
            AssistantContent.of("msg-" + inputTokens, List.of(new TextBlock("OK")),
                new Usage(inputTokens, 0, 0, 0)));
    }
}
