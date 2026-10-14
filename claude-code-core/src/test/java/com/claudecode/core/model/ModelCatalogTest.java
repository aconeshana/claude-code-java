package com.claudecode.core.model;

import java.util.function.Function;
import java.util.List;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ModelCatalogTest {

    private static final Function<String, String> NO_ENV = _ -> null;

    /**
     * The catalogue is the single source of truth for the shipped defaults, so
     * these assertions read the constants rather than pinning a generation:
     * bumping the catalogue must not require editing this test, and an alias
     * that stopped resolving to its own family is what this actually guards.
     */
    @Test
    void latestFamilyModelsComeFromOneCatalogue() {
        assertEquals(3, ModelCatalog.catalogVersion());
        assertEquals(ModelCatalog.LATEST_SONNET, ModelCatalog.resolve("sonnet", NO_ENV));
        assertEquals(ModelCatalog.LATEST_OPUS, ModelCatalog.resolve("opus", NO_ENV));
        assertEquals(ModelCatalog.LATEST_HAIKU, ModelCatalog.resolve("haiku", NO_ENV));
        assertEquals(ModelCatalog.LATEST_FABLE, ModelCatalog.resolve("fable", NO_ENV));
        assertEquals(List.of(
            ModelCatalog.FABLE, ModelCatalog.OPUS, ModelCatalog.SONNET, ModelCatalog.HAIKU),
            ModelCatalog.pickerFamilies());
    }

    @Test
    void nonFirstPartyPickerStartsEmptyWithoutExplicitFamilyMappings() {
        assertEquals(List.of(), ModelCatalog.pickerFamilies(false, NO_ENV, _ -> null));
    }

    @Test
    void nonFirstPartyPickerIncludesOnlyExplicitlyMappedFamilies() {
        Function<String, String> env = key ->
            Strings.CS.equals("ANTHROPIC_DEFAULT_SONNET_MODEL", key) ? "gateway-sonnet" : null;
        Function<String, String> overrides = modelId ->
            Strings.CS.equals(ModelCatalog.LATEST_OPUS, modelId) ? "gateway-opus" : null;

        assertEquals(List.of(ModelCatalog.OPUS, ModelCatalog.SONNET),
            ModelCatalog.pickerFamilies(false, env, overrides));
    }

    @Test
    void nonFirstPartyPickerIncludesFableOnlyWhenExplicitlyMapped() {
        Function<String, String> overrides = modelId ->
            Strings.CS.equals("claude-fable-5", modelId) ? "gateway-fable" : null;

        assertEquals(List.of(ModelCatalog.FABLE),
            ModelCatalog.pickerFamilies(false, NO_ENV, overrides));
    }

    @Test
    void environmentOverridePrecedesCatalogueFallback() {
        assertEquals("gateway-sonnet",
            ModelCatalog.resolve("sonnet", key ->
                Strings.CS.equals("ANTHROPIC_DEFAULT_SONNET_MODEL", key) ? "gateway-sonnet" : null));
    }

    @Test
    void settingsModelOverridePrecedesCatalogueFallbackButNotEnvironmentPin() {
        Function<String, String> override = modelId ->
            Strings.CS.equals(ModelCatalog.LATEST_SONNET, modelId) ? "provider-sonnet" : null;
        assertEquals("provider-sonnet",
            ModelCatalog.resolve("sonnet", NO_ENV, override));
        assertEquals("env-sonnet", ModelCatalog.resolve("sonnet",
            key -> Strings.CS.equals("ANTHROPIC_DEFAULT_SONNET_MODEL", key) ? "env-sonnet" : null,
            override));
    }

    @Test
    void pickerMetadataComesFromTheSameFamilyEntry() {
        assertEquals("Sonnet 5.5", ModelCatalog.label(ModelCatalog.SONNET, NO_ENV));
        assertEquals("Best for everyday tasks",
            ModelCatalog.description(ModelCatalog.SONNET, NO_ENV));
        Function<String, String> env = key -> switch (key) {
            case "ANTHROPIC_DEFAULT_SONNET_MODEL" -> "gateway-sonnet";
            case "ANTHROPIC_DEFAULT_SONNET_MODEL_NAME" -> "Gateway Sonnet";
            case "ANTHROPIC_DEFAULT_SONNET_MODEL_DESCRIPTION" -> "Gateway description";
            default -> null;
        };
        assertEquals("Gateway Sonnet", ModelCatalog.label(ModelCatalog.SONNET, env));
        assertEquals("Gateway description", ModelCatalog.description(ModelCatalog.SONNET, env));
    }

    @Test
    void aliasAndResolvedIdHaveTheSameSemanticIdentity() {
        assertTrue(ModelCatalog.sameModel("sonnet", ModelCatalog.LATEST_SONNET, NO_ENV));
        assertTrue(ModelCatalog.sameModel("opus", ModelCatalog.LATEST_OPUS, NO_ENV));
        assertTrue(ModelCatalog.sameModel("best", "opus", NO_ENV));
        assertTrue(ModelCatalog.sameModel(null, null, NO_ENV));
        assertFalse(ModelCatalog.sameModel(null, "sonnet", NO_ENV));
        assertFalse(ModelCatalog.sameModel("sonnet", null, NO_ENV));
        Function<String, String> overrides = modelId ->
            Strings.CS.equals(ModelCatalog.LATEST_SONNET, modelId) ? "provider-sonnet" : null;
        assertTrue(ModelCatalog.sameModel(
            "sonnet", "provider-sonnet", NO_ENV, overrides));
    }

    @Test
    void recognizesAliasesCurrentAndLegacyOfficialIdsAsBuiltInSelections() {
        assertTrue(ModelCatalog.isBuiltInSelection("sonnet"));
        assertTrue(ModelCatalog.isBuiltInSelection("claude-sonnet-5[1m]"));
        assertTrue(ModelCatalog.isBuiltInSelection("claude-sonnet-4-6"));
        assertFalse(ModelCatalog.isBuiltInSelection("gateway-sonnet"));
    }
}
