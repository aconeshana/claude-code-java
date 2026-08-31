package com.claudecode.core.engine;

import com.claudecode.core.message.Usage;
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

    @ParameterizedTest
    @CsvSource({
        "opus, 30.0",
        "best, 30.0",
        "sonnet, 12.0",
        "opusplan, 12.0",
        "haiku, 6.0"
    })
    void unversionedAliases_useTheirCurrentDefaultModelPricing(
            String alias, double expected) {
        assertEquals(expected,
            cost(alias, LocalDate.of(2026, 7, 29)), 0.000_001);
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

    private static double cost(String model, LocalDate date) {
        return CostCalculator.forModel(model, date).calculateCost(ONE_M_INPUT_AND_OUTPUT);
    }
}
