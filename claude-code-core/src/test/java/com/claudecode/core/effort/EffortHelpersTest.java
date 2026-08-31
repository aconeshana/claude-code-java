package com.claudecode.core.effort;

import org.apache.commons.lang3.Strings;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class EffortHelpersTest {

    @Test
    void modelSupportsEffort_releasedPickerModels() {
        assertTrue(EffortHelpers.modelSupportsEffort("claude-opus-4-6"));
        assertTrue(EffortHelpers.modelSupportsEffort("claude-sonnet-4-6"));
        assertTrue(EffortHelpers.modelSupportsEffort("claude-opus-4-8"));
        assertTrue(EffortHelpers.modelSupportsEffort("anthropic.claude-sonnet-5"));
        assertTrue(EffortHelpers.modelSupportsEffort("gpt-5.6-sol"));
        // Whitespace and case-insensitive
        assertTrue(EffortHelpers.modelSupportsEffort("CLAUDE-OPUS-4-6"));
    }

    @Test
    void modelSupportsEffort_excludesLegacyClaudeModels() {

        assertFalse(EffortHelpers.modelSupportsEffort("claude-3-5-haiku-20241022"));
        assertFalse(EffortHelpers.modelSupportsEffort("claude-3-5-sonnet-20241022"));
        assertFalse(EffortHelpers.modelSupportsEffort("claude-3-opus-20240229"));
    }

    @Test
    void modelSupportsEffort_unknownModelsDefaultTrue() {

        // as 1P-style since we don't have the 3P override table.
        assertTrue(EffortHelpers.modelSupportsEffort("some-future-model"));
    }

    @Test
    void modelSupportsEffort_nullOrBlank() {
        assertFalse(EffortHelpers.modelSupportsEffort(null));
        assertFalse(EffortHelpers.modelSupportsEffort(""));
    }

    @Test
    void modelSupportsMaxEffort_usesKnownModelCapabilities() {
        assertTrue(EffortHelpers.modelSupportsMaxEffort("claude-opus-4-6"));
        assertTrue(EffortHelpers.modelSupportsMaxEffort("claude-sonnet-4-6"));
        assertTrue(EffortHelpers.modelSupportsMaxEffort("anthropic.claude-sonnet-5"));
        assertTrue(EffortHelpers.modelSupportsMaxEffort("gpt-5.6-sol"));
        assertFalse(EffortHelpers.modelSupportsMaxEffort("claude-3-5-haiku-20241022"));
    }

    @Test
    void resolveAppliedEffort_preservesSupportedMax() {
        assertEquals("max",
            EffortHelpers.resolveAppliedEffort("claude-sonnet-4-6", "max"));
        assertEquals("max",
            EffortHelpers.resolveAppliedEffort("gpt-5.6-sol", "max"));
    }

    @Test
    void resolveAppliedEffort_maxOnOpus46Passes() {
        assertEquals("max",
            EffortHelpers.resolveAppliedEffort("claude-opus-4-6", "max"));
    }

    @Test
    void resolveAppliedEffort_unsupportedModelReturnsNull() {
        // Haiku doesn't support the effort parameter at all — we suppress it
        // entirely so the API gets a clean request body without the beta header.
        assertNull(EffortHelpers.resolveAppliedEffort("claude-3-5-haiku-20241022", "high"));
    }

    @Test
    void resolveAppliedEffort_nullAppStateUsesReleased197CatalogDefault() {
        assertEquals("high", EffortHelpers.resolveAppliedEffort("claude-opus-4-6", null));
        assertEquals("high", EffortHelpers.resolveAppliedEffort("claude-opus-4-6", ""));
        assertEquals("high", EffortHelpers.resolveAppliedEffort("claude-sonnet-4-6", null));
    }

    @Test
    void resolveAppliedEffort_unknownFirstPartyRetains197Default() {
        assertEquals("high", EffortHelpers.resolveAppliedEffort("glm-5.2", null));
    }

    @Test
    void resolveAppliedEffort_unknownCustomUsesAutoUntilExplicitlySelected() {
        assertNull(EffortHelpers.resolveAppliedEffort("gateway-alias", null, true));
        assertEquals("high",
            EffortHelpers.resolveAppliedEffort("gateway-alias", "high", true));
    }

    @Test
    void knownModelRejectsUnsupportedLevelInsteadOfSilentlyRemapping() {
        assertNull(EffortHelpers.resolveAppliedEffort("claude-sonnet-4-6", "xhigh"));
        assertNull(EffortHelpers.resolveAppliedEffort("claude-opus-4-6", "none"));
    }

    @Test
    void supportedLevelsAreModelSpecific() {
        assertEquals(
            List.of("none", "low", "medium", "high", "xhigh", "max"),
            EffortHelpers.supportedEffortLevels("gpt-5.6-sol"));
        assertEquals(
            List.of("minimal", "low", "medium", "high"),
            EffortHelpers.supportedEffortLevels("gpt-5"));
        assertEquals(
            List.of("low", "medium", "high", "max"),
            EffortHelpers.supportedEffortLevels("claude-sonnet-4-6"));
    }

    @Test
    void getEffortSuffix_supportedModel() {

        // (note the leading space) so the caller doesn't have to glue strings.
        assertEquals(" with high effort",
            EffortHelpers.getEffortSuffix("claude-opus-4-6", "high"));
        assertEquals(" with low effort",
            EffortHelpers.getEffortSuffix("claude-sonnet-4-6", "low"));
    }

    @Test
    void getEffortSuffix_maxOnSonnetShowsAppliedMax() {
        assertEquals(" with max effort",
            EffortHelpers.getEffortSuffix("claude-sonnet-4-6", "max"));
    }

    @Test
    void getEffortSuffix_emptyWhenNoEffortSent() {

        assertEquals("", EffortHelpers.getEffortSuffix("claude-opus-4-6", null));
        assertEquals("", EffortHelpers.getEffortSuffix("claude-opus-4-6", ""));
        // Unsupported model → no suffix even with a value set
        assertEquals("", EffortHelpers.getEffortSuffix("claude-3-5-haiku-20241022", "high"));
    }

    @Test
    void getEffortValueDescription_allFourLevels() {
        assertEquals("Quick, straightforward implementation with minimal overhead",
            EffortHelpers.getEffortValueDescription("low"));
        assertEquals("Balanced approach with standard implementation and testing",
            EffortHelpers.getEffortValueDescription("medium"));
        assertEquals("Comprehensive implementation with extensive testing and documentation",
            EffortHelpers.getEffortValueDescription("high"));
        assertEquals("Maximum capability with deepest reasoning (Opus 4.6 only)",
            EffortHelpers.getEffortValueDescription("max"));
    }

    @Test
    void toPersistableEffort_alwaysPersistsLowMediumHigh() {
        assertEquals("low",    EffortHelpers.toPersistableEffort("low"));
        assertEquals("medium", EffortHelpers.toPersistableEffort("medium"));
        assertEquals("high",   EffortHelpers.toPersistableEffort("high"));
    }

    @Test
    void toPersistableEffort_maxOnlyForAnt() {




        if (!Strings.CS.equals("ant", System.getenv("USER_TYPE"))) {
            assertNull(EffortHelpers.toPersistableEffort("max"),
                "non-ant builds must NOT persist max (session-only)");
        }
    }

    @Test
    void isEffortLevel() {
        for (String v : new String[]{"none", "minimal", "low", "medium", "high", "xhigh", "max"}) {
            assertTrue(EffortHelpers.isEffortLevel(v));
        }
        assertFalse(EffortHelpers.isEffortLevel("auto"));   // auto is a control word, not a level
        assertFalse(EffortHelpers.isEffortLevel("HIGH"));   // case-sensitive check (callers lowercase first)
        assertFalse(EffortHelpers.isEffortLevel(null));
        assertFalse(EffortHelpers.isEffortLevel(""));
    }

    // ── /model picker helpers ──────────────────────────────────────────────

    @Test
    void convertEffortValueToLevel_passesValidElseHigh() {
        assertEquals("low", EffortHelpers.convertEffortValueToLevel("low"));
        assertEquals("xhigh", EffortHelpers.convertEffortValueToLevel("xhigh"));
        assertEquals("high", EffortHelpers.convertEffortValueToLevel("bogus"));
        assertEquals("high", EffortHelpers.convertEffortValueToLevel(null));
    }

    @Test
    void defaultEffortLevelForModel_isHighForPublic() {


        assertEquals("high", EffortHelpers.getDefaultEffortForModel("claude-opus-4-6"));
        assertEquals("high", EffortHelpers.getDefaultEffortForModel("claude-sonnet-4-6"));
        assertEquals("high", EffortHelpers.defaultEffortLevelForModel("claude-opus-4-6"));
        assertEquals("high", EffortHelpers.defaultEffortLevelForModel("claude-sonnet-4-6"));
    }

    @Test
    void cycleEffortLevel_wrapsAndGatesMax() {
        // Opus 4.6 supports max → cycle includes max, wraps low→...→max→low.
        assertEquals("medium", EffortHelpers.cycleEffortLevel("low", 1, "claude-opus-4-6"));
        assertEquals("low", EffortHelpers.cycleEffortLevel("max", 1, "claude-opus-4-6")); // wrap
        assertEquals("max", EffortHelpers.cycleEffortLevel("low", -1, "claude-opus-4-6")); // wrap back
        // Sonnet 4.6 supports max but not xhigh.
        assertEquals("low", EffortHelpers.cycleEffortLevel("max", 1, "claude-sonnet-4-6"));
        assertEquals("max", EffortHelpers.cycleEffortLevel("high", 1, "claude-sonnet-4-6"));
        // current not in cycle clamps to the model default, then steps.
        assertEquals("max", EffortHelpers.cycleEffortLevel("xhigh", 1, "claude-sonnet-4-6"));
        assertEquals("low", EffortHelpers.cycleEffortLevel("none", 1, "gpt-5.6-sol"));
    }

    @Test
    void resolvePickerEffortPersistence_modelAloneWhenDefaultAndUntouched() {
        // Never toggled, no prior persisted, picked == model default → null (set model alone).
        assertNull(EffortHelpers.resolvePickerEffortPersistence("high", "high", null, false));
        // Toggled in picker → keep the picked value even if it equals default.
        assertEquals("high",
            EffortHelpers.resolvePickerEffortPersistence("high", "high", null, true));
        // Prior persisted exists → keep (sticky) even at default, untouched.
        assertEquals("high",
            EffortHelpers.resolvePickerEffortPersistence("high", "high", "high", false));
        // Picked differs from default → keep regardless.
        assertEquals("low",
            EffortHelpers.resolvePickerEffortPersistence("low", "high", null, false));
    }
}
