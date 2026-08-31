package com.claudecode.core.model;

import org.apache.commons.lang3.Strings;

import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


class ModelNamesTest {

    @Test
    void displayName_mapsKnownModels() {
        assertEquals("Opus 5", ModelNames.displayName("claude-opus-5"));
        assertEquals("Opus 4.8", ModelNames.displayName("claude-opus-4-8"));
        assertEquals("Sonnet 4.6", ModelNames.displayName("claude-sonnet-4-6"));
        assertEquals("Haiku 4.5", ModelNames.displayName("claude-haiku-4-5-20251001"));
        assertEquals("Opus 4.6 (1M context)", ModelNames.displayName("claude-opus-4-6[1m]"));
        assertEquals("GPT-5.6 Sol", ModelNames.displayName("sol"));
        assertEquals("GPT-5.6 Luna", ModelNames.displayName("luna"));
        assertEquals("gpt-5.6-sol", ModelNames.displayName("gpt-5.6-sol"));
        assertEquals("gpt-5.6-luna", ModelNames.displayName("gpt-5.6-luna"));
    }

    @Test
    void displayName_fallsBackToInput() {
        assertEquals("some-custom-model", ModelNames.displayName("some-custom-model"));
        assertEquals("unknown", ModelNames.displayName(null));
        assertEquals("unknown", ModelNames.displayName(""));
    }

    @Test
    void renderModelLabel_nullIsDefaultWithSuffix() {
        // Injected empty lookup — independent of whatever ANTHROPIC_DEFAULT_SONNET_MODEL
        // is set to on the machine running this suite.
        assertEquals(ModelNames.displayName(ModelNames.DEFAULT_MAIN_LOOP_MODEL) + " (default)",
            ModelNames.renderModelLabel(null, _ -> null));
    }

    @Test
    void renderModelLabel_nullUsesSonnetEnvOverrideWhenSet() {
        assertEquals(ModelNames.displayName("custom-sonnet-id") + " (default)",
            ModelNames.renderModelLabel(null, key -> Strings.CS.equals("ANTHROPIC_DEFAULT_SONNET_MODEL", key)
                ? "custom-sonnet-id" : null));
    }

    @Test
    void defaultMainLoopModel_fallsBackWithoutOverride() {
        assertEquals(ModelNames.DEFAULT_MAIN_LOOP_MODEL, ModelNames.defaultMainLoopModel(_ -> null));
    }

    @Test
    void defaultMainLoopModel_prefersEnvOverride() {
        assertEquals("custom-sonnet-id",
            ModelNames.defaultMainLoopModel(key -> Strings.CS.equals("ANTHROPIC_DEFAULT_SONNET_MODEL", key)
                ? "custom-sonnet-id" : null));
    }

    @Test
    void renderModelLabel_concreteModelHasNoSuffix() {
        assertEquals("Opus 5", ModelNames.renderModelLabel("claude-opus-5"));
    }


    @Test
    void parseUserSpecifiedModel_aliases() {
        Function<String, String> noEnv = _ -> null;
        assertEquals("claude-sonnet-5",
            ModelNames.parseUserSpecifiedModel("sonnet", noEnv));
        assertEquals("claude-sonnet-5",
            ModelNames.parseUserSpecifiedModel("opusplan", noEnv),
            "opusplan base model is Sonnet — Opus only via plan-mode runtime swap");
        assertEquals("claude-opus-5",
            ModelNames.parseUserSpecifiedModel("opus", noEnv));
        assertEquals("claude-opus-5",
            ModelNames.parseUserSpecifiedModel("best", noEnv));
        assertEquals("claude-haiku-4-5-20251001",
            ModelNames.parseUserSpecifiedModel("haiku", noEnv));
        assertEquals("gpt-5.6-sol",
            ModelNames.parseUserSpecifiedModel(" SOL ", noEnv));
        assertEquals("gpt-5.6-luna",
            ModelNames.parseUserSpecifiedModel("Luna", noEnv));

        assertEquals("claude-sonnet-5[1m]",
            ModelNames.parseUserSpecifiedModel("sonnet[1m]", noEnv));
        // concrete id passes through untouched
        assertEquals("claude-opus-4-8",
            ModelNames.parseUserSpecifiedModel("claude-opus-4-8", noEnv));
    }

    @Test
    void parseUserSpecifiedModel_envOverride() {
        Function<String, String> env =
            k -> Strings.CS.equals("ANTHROPIC_DEFAULT_OPUS_MODEL", k) ? "claude-opus-4-8" : null;
        assertEquals("claude-opus-4-8",
            ModelNames.parseUserSpecifiedModel("opus", env));
    }

    @Test
    void normalizeModelStringForApi_stripsInternalContextTagsOnly() {
        assertEquals("claude-sonnet-5",
            ModelNames.normalizeModelStringForApi("claude-sonnet-5[1m]"));
        assertEquals("Gateway-GPT",
            ModelNames.normalizeModelStringForApi("Gateway-[2m]GPT"));
        assertEquals("plain-model",
            ModelNames.normalizeModelStringForApi("plain-model"));
        assertNull(ModelNames.normalizeModelStringForApi(null));
    }

    @Test
    void runtimeMainLoopModel_opusplanBranchMatrix() {
        // opusplan + plan → Opus
        assertEquals(ModelNames.defaultOpusModel(),
            ModelNames.runtimeMainLoopModel("opusplan", PermissionModeKind.PLAN, false));
        // opusplan + 非 plan → Sonnet（基础模型）
        assertEquals(ModelNames.defaultMainLoopModel(),
            ModelNames.runtimeMainLoopModel("opusplan", PermissionModeKind.DEFAULT, false));
        // opusplan + plan + 超 200k → 不换 Opus（escape hatch）
        assertEquals(ModelNames.defaultMainLoopModel(),
            ModelNames.runtimeMainLoopModel("opusplan", PermissionModeKind.PLAN, true));
        // haiku + plan → Sonnet（haiku 用户 plan 用 sonnet）
        assertEquals(ModelNames.defaultMainLoopModel(),
            ModelNames.runtimeMainLoopModel("haiku", PermissionModeKind.PLAN, false));
        // haiku + 非 plan → Haiku
        assertEquals(ModelNames.defaultHaikuModel(),
            ModelNames.runtimeMainLoopModel("haiku", PermissionModeKind.DEFAULT, false));
        // 具体 id 不受 plan 影响
        assertEquals("claude-opus-4-8",
            ModelNames.runtimeMainLoopModel("claude-opus-4-8", PermissionModeKind.PLAN, false));
        // null 模式 → 非 plan
        assertEquals(ModelNames.defaultMainLoopModel(),
            ModelNames.runtimeMainLoopModel("opusplan", null, false));
    }

    @Test
    void displayName_opusplan() {
        assertEquals("Opus Plan",
            ModelNames.displayName("opusplan"));
    }
}
