package com.claudecode.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.claudecode.core.model.ModelNames;


class PromptCachingTest {

    private static Function<String, String> env(Map<String, String> m) {
        return m::get;
    }

    @Test
    void defaultEnabledForArbitraryModel() {
        assertTrue(PromptCaching.isEnabled("some-custom-model", env(Map.of())));
        assertTrue(PromptCaching.isEnabled(null, env(Map.of())));
    }

    @Test
    void globalKillSwitchDisablesEverything() {
        var e = env(Map.of("DISABLE_PROMPT_CACHING", "1"));
        assertFalse(PromptCaching.isEnabled("some-custom-model", e));
        assertFalse(PromptCaching.isEnabled(ModelNames.defaultOpusModel(), e));
    }

    @Test
    void haikuFlagDisablesOnlySmallFastModel() {
        var e = env(Map.of("DISABLE_PROMPT_CACHING_HAIKU", "true"));
        assertFalse(PromptCaching.isEnabled(ModelNames.defaultHaikuModel(), e));
        assertFalse(PromptCaching.isEnabled(ModelNames.DEFAULT_HAIKU_MODEL, e));
        // A custom small-fast override is honored too.
        var e2 = env(Map.of(
            "DISABLE_PROMPT_CACHING_HAIKU", "1",
            "ANTHROPIC_SMALL_FAST_MODEL", "my-fast"));
        assertFalse(PromptCaching.isEnabled("my-fast", e2));
        // Other models stay enabled.
        assertTrue(PromptCaching.isEnabled(ModelNames.defaultMainLoopModel(), e));
        assertTrue(PromptCaching.isEnabled(ModelNames.defaultOpusModel(), e));
    }

    @Test
    void sonnetFlagDisablesOnlyDefaultSonnet() {
        var e = env(Map.of("DISABLE_PROMPT_CACHING_SONNET", "1"));
        assertFalse(PromptCaching.isEnabled(ModelNames.defaultMainLoopModel(), e));
        assertTrue(PromptCaching.isEnabled(ModelNames.defaultOpusModel(), e));
    }

    @Test
    void opusFlagDisablesOnlyDefaultOpus() {
        var e = env(Map.of("DISABLE_PROMPT_CACHING_OPUS", "true"));
        assertFalse(PromptCaching.isEnabled(ModelNames.defaultOpusModel(), e));
        assertTrue(PromptCaching.isEnabled(ModelNames.defaultMainLoopModel(), e));
    }

    @Test
    void javaModeParsesThreeExplicitValuesAndFallsBackForUnsetOrInvalidInput() {
        assertEquals(PromptCaching.ConfiguredMode.LEGACY, PromptCaching.parseConfiguredMode(null));
        assertEquals(PromptCaching.ConfiguredMode.LEGACY, PromptCaching.parseConfiguredMode("  "));
        assertEquals(PromptCaching.ConfiguredMode.OFF, PromptCaching.parseConfiguredMode(" OFF "));
        assertEquals(PromptCaching.ConfiguredMode.FIVE_MINUTES, PromptCaching.parseConfiguredMode("5M"));
        assertEquals(PromptCaching.ConfiguredMode.ONE_HOUR, PromptCaching.parseConfiguredMode(" 1h "));
        assertEquals(PromptCaching.ConfiguredMode.LEGACY, PromptCaching.parseConfiguredMode("forever"));
    }

    @Test
    void explicitJavaModeOverridesLegacyDisableGates() {
        var disabledLegacy = env(Map.of(
            PromptCaching.JAVA_PROMPT_CACHE_MODE, "5m",
            PromptCaching.DISABLE_PROMPT_CACHING, "1"));
        var fiveMinutes = PromptCaching.resolve("some-model", disabledLegacy);
        assertTrue(fiveMinutes.enabled());
        assertEquals(CreateMessageRequest.PromptCacheTtl.FIVE_MINUTES, fiveMinutes.ttl());

        var oneHour = PromptCaching.resolve("some-model", env(Map.of(
            PromptCaching.JAVA_PROMPT_CACHE_MODE, "1h",
            PromptCaching.DISABLE_PROMPT_CACHING, "1")));
        assertTrue(oneHour.enabled());
        assertEquals(CreateMessageRequest.PromptCacheTtl.ONE_HOUR, oneHour.ttl());

        assertFalse(PromptCaching.resolve("some-model", env(Map.of(
            PromptCaching.JAVA_PROMPT_CACHE_MODE, "off"))).enabled());
    }

    @Test
    void unsetJavaModePreservesLegacyPerModelPolicy() {
        var legacy = env(Map.of(PromptCaching.DISABLE_PROMPT_CACHING_SONNET, "1"));
        assertFalse(PromptCaching.resolve(ModelNames.defaultMainLoopModel(), legacy).enabled());
        var opus = PromptCaching.resolve(ModelNames.defaultOpusModel(), legacy);
        assertTrue(opus.enabled());
        assertEquals(CreateMessageRequest.PromptCacheTtl.FIVE_MINUTES, opus.ttl());
    }

    @Test
    void configuredModeLatchNeverChangesAfterFirstRead() {
        AtomicReference<String> raw = new AtomicReference<>("1h");
        PromptCaching.ConfiguredModeLatch latch = new PromptCaching.ConfiguredModeLatch(raw::get);
        assertEquals(PromptCaching.ConfiguredMode.ONE_HOUR, latch.get());
        raw.set("off");
        assertEquals(PromptCaching.ConfiguredMode.ONE_HOUR, latch.get());
    }
}
