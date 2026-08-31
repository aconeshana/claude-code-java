package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.ModelNames;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calculates USD cost based on token usage and model pricing.
 * Pricing is per million tokens.
 *
 * <ul>
 *   <li>pricing tiers, per-model resolution,
 *       unknown-model fallback, prompt-cache pricing, and
 *       {@code web_search_requests} billing from {@code calculateUSDCost}.</li>
 * </ul>
 */
public class CostCalculator {

    /**
     * Per-model instance cache for {@link #forModel(String)} — instances are
     * immutable, so callers that resolve pricing on every request (e.g.
     * {@code QueryEngine.getCostCalculator} after a {@code /model} switch)
     * share one calculator per model string instead of reallocating. Sonnet 5
     * bypasses this cache because its introductory price changes on a fixed
     * date.
     */
    private static final Map<String, CostCalculator> BY_MODEL = new ConcurrentHashMap<>();

    // Default pricing for Claude Sonnet (per million tokens)
    private static final double DEFAULT_INPUT_COST_PER_M = 3.0;
    private static final double DEFAULT_OUTPUT_COST_PER_M = 15.0;
    private static final double DEFAULT_CACHE_WRITE_COST_PER_M = 3.75;
    private static final double DEFAULT_CACHE_READ_COST_PER_M = 0.30;

    private static final double WEB_SEARCH_COST_PER_REQUEST = 0.01;
    private static final LocalDate SONNET_5_INTRO_PRICE_END = LocalDate.of(2026, 8, 31);

    private static final CostCalculator COST_TIER_10_50 =
        new CostCalculator(10.0, 50.0, 12.5, 1.0);
    private static final CostCalculator COST_TIER_5_25 =
        new CostCalculator(5.0, 25.0, 6.25, 0.5);
    private static final CostCalculator COST_TIER_15_75 =
        new CostCalculator(15.0, 75.0, 18.75, 1.5);
    private static final CostCalculator COST_TIER_3_15 = new CostCalculator();
    private static final CostCalculator SONNET_5_INTRO_COST =
        new CostCalculator(2.0, 10.0, 2.5, 0.2);
    private static final CostCalculator COST_HAIKU_45 =
        new CostCalculator(1.0, 5.0, 1.25, 0.1);
    private static final CostCalculator COST_HAIKU_35 =
        new CostCalculator(0.8, 4.0, 1.0, 0.08);

    private final double inputCostPerM;
    private final double outputCostPerM;
    private final double cacheWriteCostPerM;
    private final double cacheReadCostPerM;

    public CostCalculator() {
        this(DEFAULT_INPUT_COST_PER_M, DEFAULT_OUTPUT_COST_PER_M,
             DEFAULT_CACHE_WRITE_COST_PER_M, DEFAULT_CACHE_READ_COST_PER_M);
    }

    public CostCalculator(double inputCostPerM, double outputCostPerM,
                           double cacheWriteCostPerM, double cacheReadCostPerM) {
        this.inputCostPerM = inputCostPerM;
        this.outputCostPerM = outputCostPerM;
        this.cacheWriteCostPerM = cacheWriteCostPerM;
        this.cacheReadCostPerM = cacheReadCostPerM;
    }

    /**
     * Calculates the USD cost for the given token usage.
     */
    public double calculateCost(Usage usage) {
        if (usage == null) {
            return 0.0;
        }
        double inputCost = (usage.inputTokens() / 1_000_000.0) * inputCostPerM;
        double outputCost = (usage.outputTokens() / 1_000_000.0) * outputCostPerM;
        double cacheWriteCost = (usage.cacheCreationInputTokens() / 1_000_000.0) * cacheWriteCostPerM;
        double cacheReadCost = (usage.cacheReadInputTokens() / 1_000_000.0) * cacheReadCostPerM;
        double webSearchCost = usage.webSearchRequests() * WEB_SEARCH_COST_PER_REQUEST;
        return inputCost + outputCost + cacheWriteCost + cacheReadCost + webSearchCost;
    }

    /**
     * Returns pricing for a given model name. Permanent pricing is cached per
     * model string; the returned instances are immutable and shared.
     */
    public static CostCalculator forModel(String model) {
        return forModel(model, LocalDate.now(ZoneOffset.UTC));
    }

    /** Pricing-date seam for deterministic promotion-boundary tests. */
    static CostCalculator forModel(String model, LocalDate pricingDate) {
        Objects.requireNonNull(pricingDate, "pricingDate");
        if (StringUtils.isBlank(model)) {
            return COST_TIER_3_15;
        }

        // Cost callers normally pass the resolved request model, but status
        // paths can still hold a user-facing alias. Resolve it with the same
        // defaults as the request path before selecting a pricing tier.
        String normalized = ModelNames.parseUserSpecifiedModel(model.trim())
            .toLowerCase(Locale.ROOT);
        // Sonnet 5 has time-limited introductory pricing, so it must not be
        // cached solely by model name across the 2026-09-01 price boundary.
        if (isSonnetFive(normalized)) {
            return sonnetFivePricing(pricingDate);
        }
        return BY_MODEL.computeIfAbsent(normalized, CostCalculator::resolvePricing);
    }

    private static CostCalculator resolvePricing(String model) {
        String m = model.toLowerCase(Locale.ROOT);

        // Fable 5 and Mythos 5: $10/$50.
        if (Strings.CS.contains(m, "fable-5") || Strings.CS.contains(m, "mythos-5")) {
            return COST_TIER_10_50;
        }

        if (containsVersion(m, "opus", "5")
                || containsVersion(m, "opus", "4-8")
                || containsVersion(m, "opus", "4-7")
                || containsVersion(m, "opus", "4-6")
                || containsVersion(m, "opus", "4-5")) {
            return COST_TIER_5_25;
        }

        // Opus 4/4.1 retain their legacy $15/$75 tier. This check comes after
        // 4.5+ so dated IDs such as claude-opus-4-8 remain on the current tier.
        if (Strings.CS.contains(m, "opus-4") || Strings.CS.contains(m, "opus-4.0") || Strings.CS.contains(m, "opus-4.1")) {
            return COST_TIER_15_75;
        }

        // Sonnet 4.x and older known Sonnet releases: $3/$15.
        if (Strings.CS.contains(m, "sonnet")) {
            return COST_TIER_3_15;
        }

        // Haiku 4.5: $1/$5.
        if (Strings.CS.contains(m, "haiku-4-5") || Strings.CS.contains(m, "haiku-4.5")) {
            return COST_HAIKU_45;
        }
        // Haiku 3.5: $0.80/$4.
        if (Strings.CS.contains(m, "haiku-3-5") || Strings.CS.contains(m, "haiku-3.5")
                || Strings.CS.contains(m, "3-5-haiku") || Strings.CS.contains(m, "3.5-haiku")) {
            return COST_HAIKU_35;
        }

        return COST_TIER_5_25;
    }

    private static boolean isSonnetFive(String model) {
        return containsVersion(model, "sonnet", "5");
    }

    private static CostCalculator sonnetFivePricing(LocalDate pricingDate) {
        return pricingDate.isAfter(SONNET_5_INTRO_PRICE_END)
            ? COST_TIER_3_15
            : SONNET_5_INTRO_COST;
    }

    private static boolean containsVersion(String model, String family, String version) {
        return Strings.CS.contains(model, family + "-" + version)
            || Strings.CS.contains(model, family + "-" + version.replace('-', '.'));
    }
}
