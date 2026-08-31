package com.claudecode.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.model.RefusalFallbackTarget.Inputs;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * A refusal only becomes a retry when a target model exists, and "exists" is a narrow question: the
 * flagged model has to be one of the models whose safeguards do the flagging, and the opus family
 * has to be reachable from the current provider.
 */
class RefusalFallbackTargetTest {

    private static Function<String, String> env(String... pairs) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values::get;
    }

    /** First-party-like provider, no environment pins, everything callable. */
    private static Inputs firstPartyLike(String... envPairs) {
        return new Inputs(true, env(envPairs), _ -> true);
    }

    @Test
    void aFlaggedFableModelRetriesOnTheOpusFamily() {
        Inputs inputs = firstPartyLike();

        assertEquals(ModelCatalog.OPUS.modelId(),
            RefusalFallbackTarget.resolve("claude-fable-5", inputs));
        assertTrue(RefusalFallbackTarget.exists("claude-fable-5", inputs));
    }

    @Test
    void aMythosModelNeverGetsATargetEvenThoughItsSafeguardsFlag() {
        Inputs inputs = firstPartyLike();

        assertNull(RefusalFallbackTarget.resolve("claude-mythos-5", inputs));
        assertFalse(RefusalFallbackTarget.exists("claude-mythos-5", inputs));
    }

    @Test
    void anOrdinaryModelHasNoRefusalFallbackTarget() {
        Inputs inputs = firstPartyLike();

        assertNull(RefusalFallbackTarget.resolve("claude-sonnet-5", inputs));
        assertNull(RefusalFallbackTarget.resolve("claude-haiku-4-5-20251001", inputs));
        assertFalse(RefusalFallbackTarget.exists("claude-sonnet-5", inputs));
    }

    @Test
    void anEarlyAccessModelIsTreatedAsAFlaggingSourceWhateverItsFamily() {
        Inputs inputs = firstPartyLike();

        assertEquals(ModelCatalog.OPUS.modelId(),
            RefusalFallbackTarget.resolve("claude-sonnet-5-eap", inputs));
        assertEquals(ModelCatalog.OPUS.modelId(),
            RefusalFallbackTarget.resolve("claude-sonnet-5-EAP[1m]", inputs));
        assertNull(RefusalFallbackTarget.resolve("claude-sonnet-5-eaper", inputs),
            "the suffix has to end the id or introduce the 1m tag");
    }

    @Test
    void aFableModelPinnedByEnvironmentIsRecognizedByItsPinAlone() {
        Inputs inputs = firstPartyLike("ANTHROPIC_DEFAULT_FABLE_MODEL", "house-blend-1");

        assertEquals(ModelCatalog.OPUS.modelId(),
            RefusalFallbackTarget.resolve("house-blend-1", inputs));
        assertEquals(ModelCatalog.OPUS.modelId(),
            RefusalFallbackTarget.resolve("house-blend-1[1m]", inputs),
            "the 1m tag is stripped before the pin is compared");
        assertNull(RefusalFallbackTarget.resolve("house-blend-2", inputs));
    }

    /**
     * A 1m-tagged source takes the opus default verbatim instead of the
     * tag-stripped form, so a session that paid for the long window does not get
     * quietly downgraded to the 200k variant by the retry.
     */
    @Test
    void aLongContextSourceKeepsTheOpusDefaultVerbatim() {
        Inputs inputs = firstPartyLike("ANTHROPIC_DEFAULT_OPUS_MODEL", "claude-opus-5[1m]");

        assertEquals("claude-opus-5[1m]",
            RefusalFallbackTarget.resolve("claude-fable-5[1m]", inputs));
    }

    @Test
    void anUntaggedSourceDropsTheTagFromTheOpusDefault() {
        Inputs inputs = firstPartyLike("ANTHROPIC_DEFAULT_OPUS_MODEL", "claude-opus-5[1m]");

        assertEquals("claude-opus-5",
            RefusalFallbackTarget.resolve("claude-fable-5", inputs));
    }

    /**
     * Dropping the tag is only safe while it does not shrink the window. A
     * source that already answers with a million-token context must not be
     * retried on a target that would truncate the conversation instead.
     */
    @Test
    void aTargetThatWouldShrinkTheContextWindowIsRefused() {
        Inputs inputs = firstPartyLike("ANTHROPIC_DEFAULT_OPUS_MODEL", "claude-opus-4-6[1m]");

        assertNull(RefusalFallbackTarget.resolve("claude-opus-5-eap", inputs));
        assertFalse(RefusalFallbackTarget.exists("claude-opus-5-eap", inputs));
    }

    @Test
    void aProviderThatIsNotFirstPartyLikeFallsBackToItsOpusPin() {
        assertEquals("gpt-5-mini", RefusalFallbackTarget.resolve("claude-fable-5",
            new Inputs(false, env("ANTHROPIC_DEFAULT_OPUS_MODEL", "gpt-5-mini"), _ -> true)));
        assertEquals(ModelCatalog.OPUS.modelId(), RefusalFallbackTarget.resolve(
            "claude-fable-5", new Inputs(false, env(), _ -> true)),
            "with no pin the catalogue opus family is still the target");
    }

    /**
     * A pin that names another Claude family is a misconfiguration rather than a
     * target: retrying a refusal on sonnet would just be refused again.
     */
    @Test
    void anOpusPinNamingAnotherClaudeFamilyIsRejected() {
        assertNull(RefusalFallbackTarget.resolve("claude-fable-5",
            new Inputs(false, env("ANTHROPIC_DEFAULT_OPUS_MODEL", "claude-sonnet-5"), _ -> true)));
        assertNull(RefusalFallbackTarget.resolve("claude-fable-5",
            new Inputs(true, env("ANTHROPIC_DEFAULT_OPUS_MODEL", "claude-sonnet-5"), _ -> true)));
    }

    @Test
    void aTargetTheAllowlistRejectsFallsBackToTheCatalogueOpusFamily() {
        Set<String> callable = Set.of(ModelCatalog.OPUS.modelId());
        Inputs inputs = new Inputs(true,
            env("ANTHROPIC_DEFAULT_OPUS_MODEL", "claude-opus-4-6"), callable::contains);

        assertEquals(ModelCatalog.OPUS.modelId(),
            RefusalFallbackTarget.resolve("claude-fable-5", inputs));
    }

    @Test
    void noCallableOpusModelMeansNoRetry() {
        Inputs inputs = new Inputs(true, env(), _ -> false);

        assertNull(RefusalFallbackTarget.resolve("claude-fable-5", inputs));
    }


    @Test
    void existenceIsDecidedBeforeTheAllowlistFilterRuns() {
        Inputs inputs = new Inputs(true, env(), _ -> false);

        assertTrue(RefusalFallbackTarget.exists("claude-fable-5", inputs));
        assertNull(RefusalFallbackTarget.resolve("claude-fable-5", inputs));
    }

    @Test
    void aBlankModelHasNoTarget() {
        Inputs inputs = firstPartyLike();

        assertNull(RefusalFallbackTarget.resolve(null, inputs));
        assertNull(RefusalFallbackTarget.resolve("  ", inputs));
        assertFalse(RefusalFallbackTarget.exists(null, inputs));
    }
}
