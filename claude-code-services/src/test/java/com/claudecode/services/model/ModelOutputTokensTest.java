package com.claudecode.services.model;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.attachment.FeatureFlag;
import com.claudecode.core.attachment.FeatureFlagRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ModelOutputTokens} — the per-model output-token ladder and the
 * {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} resolver. Env-set paths are exercised via
 * {@link com.claudecode.core.config.EnvValidation} tests; here we cover the
 * ladder (pure) and the env-unset fallbacks (deterministic in a clean test env).
 */
class ModelOutputTokensTest {

    @Test
    void ladder_opus5() {
        var b = ModelOutputTokens.getModelMaxOutputTokens("claude-opus-5");
        assertEquals(64_000, b.defaultTokens());
        assertEquals(128_000, b.upperLimit());
    }

    @Test
    void ladder_opus46() {
        var b = ModelOutputTokens.getModelMaxOutputTokens("claude-opus-4-6");
        assertEquals(64_000, b.defaultTokens());
        assertEquals(128_000, b.upperLimit());
    }

    @Test
    void ladder_sonnet46() {
        var b = ModelOutputTokens.getModelMaxOutputTokens("claude-sonnet-4-6");
        assertEquals(32_000, b.defaultTokens());
        assertEquals(128_000, b.upperLimit());
    }

    @Test
    void ladder_opus4_family() {
        assertEquals(32_000, ModelOutputTokens.getModelMaxOutputTokens("claude-opus-4-1").defaultTokens());
        assertEquals(32_000, ModelOutputTokens.getModelMaxOutputTokens("claude-opus-4-1").upperLimit());
    }

    @Test
    void ladder_legacyClaude3() {
        assertEquals(4_096, ModelOutputTokens.getModelMaxOutputTokens("claude-3-haiku-20240307").upperLimit());
        assertEquals(8_192, ModelOutputTokens.getModelMaxOutputTokens("claude-3-5-sonnet-20241022").upperLimit());
    }

    @Test
    void ladder_unknownModelFallsBackToDefaults() {
        var b = ModelOutputTokens.getModelMaxOutputTokens("some-future-model");
        assertEquals(32_000, b.defaultTokens());
        assertEquals(64_000, b.upperLimit());
    }

    @Test
    void ladder_nullModelDoesNotThrow() {
        assertDoesNotThrow(() -> ModelOutputTokens.getModelMaxOutputTokens(null));
    }

    @Test
    void resolve_envUnset_returnsCliFallback() {
        // CLAUDE_CODE_MAX_OUTPUT_TOKENS is not set in the test environment, so the
        // CLI/config value must be preserved (Java's --max-tokens behaviour intact).
        assumeEnvUnset();
        assertEquals(16_384, ModelOutputTokens.resolveMaxOutputTokens("claude-opus-4-6", 16_384));
    }

    @Test
    void getMaxOutputTokensForModel_envUnset_returnsModelDefault() {
        assumeEnvUnset();
        assertEquals(64_000, ModelOutputTokens.getMaxOutputTokensForModel("claude-opus-4-6"));
    }

    @Test
    void outputTokenSlotCapsModelDerivedDefaultOnlyWhenEnabled() {
        assumeEnvUnset();
        FeatureFlagRegistry off = FeatureFlagRegistry.allOff();
        FeatureFlagRegistry on = FeatureFlagRegistry.builder()
            .enable(FeatureFlag.MAX_OUTPUT_TOKENS_SLOT)
            .build();

        assertEquals(64_000,
            ModelOutputTokens.getMaxOutputTokensForModel("claude-opus-4-6", off));
        assertEquals(8_000,
            ModelOutputTokens.getMaxOutputTokensForModel("claude-opus-4-6", on));
        assertEquals(4_096,
            ModelOutputTokens.getMaxOutputTokensForModel("claude-3-opus-20240229", on),
            "the slot cap must not raise a model whose native default is below 8K");
    }

    @Test
    void explicitCliMaxIsNotCappedByOutputTokenSlot() {
        assumeEnvUnset();
        FeatureFlagRegistry on = FeatureFlagRegistry.builder()
            .enable(FeatureFlag.MAX_OUTPUT_TOKENS_SLOT)
            .build();

        assertEquals(64_000,
            ModelOutputTokens.resolveMaxOutputTokens(
                "claude-opus-4-6", 64_000, on, true));
    }

    private static void assumeEnvUnset() {
        String v = System.getenv("CLAUDE_CODE_MAX_OUTPUT_TOKENS");
        Assumptions.assumeTrue(StringUtils.isBlank(v),
            "CLAUDE_CODE_MAX_OUTPUT_TOKENS is set in this environment; skipping env-unset assertion");
    }
}
