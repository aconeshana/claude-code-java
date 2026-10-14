package com.claudecode.core.engine;

import com.claudecode.core.message.Usage;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.model.ModelNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CostCalculatorPricingTest {

    private static final Usage ONE_M_INPUT_AND_OUTPUT =
        new Usage(1_000_000, 1_000_000, 0, 0);
    private static final Usage ONE_M_CACHE_WRITE_AND_READ =
        new Usage(0, 0, 1_000_000, 1_000_000);

    @ParameterizedTest
    @ValueSource(strings = {
        "claude-opus-4-5",
        "claude-opus-4-6",
        "claude-opus-4-7",
        "claude-opus-4-8",
        "claude-opus-5"
    })
    void currentOpusModels_useFiveTwentyFivePricing(String model) {
        assertEquals(30.0, cost(model, LocalDate.of(2026, 7, 29)), 0.000_001);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-opus-4", "claude-opus-4-1"})
    void legacyOpusModels_keepFifteenSeventyFivePricing(String model) {
        assertEquals(90.0, cost(model, LocalDate.of(2026, 7, 29)), 0.000_001);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-fable-5", "claude-mythos-5"})
    void fableAndMythos_useTenFiftyPricing(String model) {
        assertEquals(60.0, cost(model, LocalDate.of(2026, 7, 29)), 0.000_001);
    }

    @Test
    void sonnetFive_usesIntroductoryPricingThroughAugust2026() {
        assertEquals(12.0,
            cost("claude-sonnet-5", LocalDate.of(2026, 8, 31)), 0.000_001);
    }

    @Test
    void sonnetFive_usesStandardPricingStartingSeptember2026() {
        assertEquals(18.0,
            cost("claude-sonnet-5", LocalDate.of(2026, 9, 1)), 0.000_001);
    }

    @ParameterizedTest
    @CsvSource({
        "claude-fable-5, 13.50",
        "claude-opus-4-8, 6.75",
        "claude-sonnet-5, 2.70",
        "claude-haiku-4-5, 1.35"
    })
    void currentModels_useOfficialFiveMinuteCachePricing(String model, double expected) {
        CostCalculator calculator = CostCalculator.forModel(
            model, LocalDate.of(2026, 7, 29));

        assertEquals(expected,
            calculator.calculateCost(ONE_M_CACHE_WRITE_AND_READ), 0.000_001);
    }

    /**
     * The aliases resolve through {@link com.claudecode.core.model.ModelCatalog},
     * so their prices track whatever the catalogue currently points at rather
     * than a pinned generation. Asserting the alias equals its resolved model
     * keeps this meaningful across model bumps.
     */
    @ParameterizedTest
    @ValueSource(strings = {"opus", "best", "sonnet", "opusplan", "haiku"})
    void unversionedAliases_priceAsTheirResolvedDefaultModel(String alias) {
        String resolved = ModelNames.parseUserSpecifiedModel(alias);

        assertEquals(cost(resolved, LocalDate.of(2026, 7, 29)),
            cost(alias, LocalDate.of(2026, 7, 29)), 0.000_001);
    }

    @Test
    void unversionedAliases_followTheCatalogueRatherThanAPinnedGeneration() {
        // Guards the intent behind the assertion above: if the catalogue is
        // bumped to a new generation, these aliases must move with it.
        assertEquals(cost(ModelCatalog.LATEST_OPUS, LocalDate.of(2026, 7, 29)),
            cost("opus", LocalDate.of(2026, 7, 29)), 0.000_001);
        assertEquals(cost(ModelCatalog.LATEST_HAIKU, LocalDate.of(2026, 7, 29)),
            cost("haiku", LocalDate.of(2026, 7, 29)), 0.000_001);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "claude-3-5-haiku-20241022",
        "us.anthropic.claude-3-5-haiku-20241022-v1:0"
    })
    void haikuThreeFiveProviderIds_useZeroPointEightFourPricing(String model) {
        assertEquals(4.8, cost(model, LocalDate.of(2026, 7, 29)), 0.000_001);
    }

    @Test
    void webSearchRequests_areBilledAtOneCentEachWhileWebFetchIsFree() {
        Usage usage = new Usage(0, 0, 0, 0, new Usage.ServerToolUse(3, 7));

        assertEquals(0.03,
            CostCalculator.forModel("claude-sonnet-4-6").calculateCost(usage), 0.000_001);
    }

    @Test
    void unknownModels_fallBackTo197DefaultOpusPricing() {
        assertEquals(30.0,
            cost("third-party-unknown-model", LocalDate.of(2026, 7, 29)), 0.000_001);
    }

    @Test
    void originalHaikuThreeIdFallsBackLikeReleased197UnknownModelPricing() {
        assertEquals(30.0,
            cost("claude-3-haiku-20240307", LocalDate.of(2026, 7, 29)), 0.000_001);
    }

    // ── Generation 5.5 ─────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "claude-opus-5-5, 24.0",
        "claude-sonnet-5-5, 12.0"
    })
    void generationFiveFive_usesOfficialPricing(String model, double expected) {
        assertEquals(expected,
            cost(model, LocalDate.of(2026, 10, 13)), 0.000_001);
    }

    /**
     * "opus-5" is a substring of "opus-5-5", and the same holds for sonnet.
     * Without explicit 5.5 branches these would silently price at the
     * generation-5 tier ($5/$25 and $3/$15 respectively).
     */
    @ParameterizedTest
    @CsvSource({
        "claude-opus-5-5, 24.0",
        "anthropic.claude-opus-5-5, 24.0",
        "claude-sonnet-5-5, 12.0",
        "anthropic.claude-sonnet-5-5, 12.0"
    })
    void generationFiveFive_isNotShadowedByItsGenerationFivePrefix(
            String model, double expected) {
        assertEquals(expected,
            cost(model, LocalDate.of(2026, 10, 13)), 0.000_001);
    }

    @ParameterizedTest
    @CsvSource({
        "claude-opus-5-5, 5.20",
        "claude-sonnet-5-5, 2.60"
    })
    void generationFiveFive_usesFivePercentCacheReadMultiplier(
            String model, double expected) {
        // 1M cache-write at $5/$2.50 plus 1M cache-read at $0.20/$0.10.
        CostCalculator calculator = CostCalculator.forModel(
            model, LocalDate.of(2026, 10, 13));

        assertEquals(expected,
            calculator.calculateCost(ONE_M_CACHE_WRITE_AND_READ), 0.000_001);
    }

    @Test
    void sonnetFiveFive_doesNotInheritTheGenerationFivePromotion() {
        // Sonnet 5 launched with introductory pricing that expired 2026-08-31;
        // Sonnet 5.5 launched at its permanent $2/$10. containsVersion's
        // "sonnet-5" prefix must not drag 5.5 into that promotion's cutover.
        assertEquals(12.0,
            cost("claude-sonnet-5-5", LocalDate.of(2026, 8, 31)), 0.000_001);
        assertEquals(12.0,
            cost("claude-sonnet-5-5", LocalDate.of(2026, 9, 1)), 0.000_001);
    }

    // ── Haiku 5.5: length-tiered pricing ───────────────────────────────────

    @Test
    void haikuFiveFive_pricesAtTheShortTierAtTheThreshold() {
        // 100_000 is NOT over the threshold, so it stays on the cheap tier:
        // 100k input at $0.10/M + 100k output at $0.50/M = 0.06.
        Usage atThreshold = new Usage(100_000, 100_000, 0, 0);

        assertEquals(0.06,
            CostCalculator.forModel("claude-haiku-5-5").calculateCost(atThreshold),
            0.000_001);
    }

    @Test
    void haikuFiveFive_pricesAtTheLongTierOneTokenOverTheThreshold() {
        // One token past the line moves the WHOLE request to the long tier:
        // 100_001 input at $0.50/M + 100k output at $2.50/M.
        Usage overThreshold = new Usage(100_001, 100_000, 0, 0);
        double expected = (100_001 / 1_000_000.0) * 0.50
            + (100_000 / 1_000_000.0) * 2.50;

        assertEquals(expected,
            CostCalculator.forModel("claude-haiku-5-5").calculateCost(overThreshold),
            0.000_001);
    }

    /**
     * Anthropic: "A request's prompt length counts all of its input tokens,
     * including cache reads and cache writes." A request whose uncached input
     * is tiny but whose cached prefix is large is still over the threshold.
     */
    @Test
    void haikuFiveFive_countsCacheTokensTowardTheThreshold() {
        Usage cacheDominated = new Usage(1_000, 0, 0, 120_000);
        double expected = (120_000 / 1_000_000.0) * 0.05
            + (1_000 / 1_000_000.0) * 0.50;

        assertEquals(expected,
            CostCalculator.forModel("claude-haiku-5-5").calculateCost(cacheDominated),
            0.000_001);
    }

    @Test
    void haikuFiveFive_cacheWritesAlsoCountTowardTheThreshold() {
        Usage writeDominated = new Usage(0, 0, 150_000, 0);

        assertEquals((150_000 / 1_000_000.0) * 0.625,
            CostCalculator.forModel("claude-haiku-5-5").calculateCost(writeDominated),
            0.000_001);
    }

    @Test
    void haikuFiveFive_shortTierIsAnOrderOfMagnitudeCheaperThanHaikuFourFive() {
        // The headline of the 5.5 launch: $0.10/$0.50 against Haiku 4.5's
        // $1/$5, i.e. one tenth on the same usage. Kept under the 100k
        // threshold on purpose -- above it Haiku 5.5 moves to a tier that is
        // only 2x cheaper, not 10x.
        Usage usage = new Usage(50_000, 50_000, 0, 0);
        double haiku55 = CostCalculator.forModel("claude-haiku-5-5")
            .calculateCost(usage);
        double haiku45 = CostCalculator.forModel("claude-haiku-4-5")
            .calculateCost(usage);

        assertEquals(haiku45 / 10.0, haiku55, 0.000_001);
    }

    @Test
    void haikuFiveFive_longTierIsOnlyTwiceCheaperThanHaikuFourFive() {
        // Over the threshold the advantage narrows to 2x on input and output.
        Usage usage = new Usage(200_000, 200_000, 0, 0);
        double haiku55 = CostCalculator.forModel("claude-haiku-5-5")
            .calculateCost(usage);
        double haiku45 = CostCalculator.forModel("claude-haiku-4-5")
            .calculateCost(usage);

        assertEquals(haiku45 / 2.0, haiku55, 0.000_001);
    }

    @Test
    void haikuFiveFive_withProviderPrefixStillTiers() {
        Usage overThreshold = new Usage(150_000, 0, 0, 0);

        assertEquals((150_000 / 1_000_000.0) * 0.50,
            CostCalculator.forModel("anthropic.claude-haiku-5-5")
                .calculateCost(overThreshold),
            0.000_001);
    }

    @Test
    void haikuFiveFive_nullUsageCostsNothing() {
        assertEquals(0.0,
            CostCalculator.forModel("claude-haiku-5-5").calculateCost(null),
            0.000_001);
    }

    private static double cost(String model, LocalDate date) {
        return CostCalculator.forModel(model, date).calculateCost(ONE_M_INPUT_AND_OUTPUT);
    }
}
