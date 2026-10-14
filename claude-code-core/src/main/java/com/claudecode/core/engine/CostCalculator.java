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

    /**
     * Claude Haiku 5.5 is the only current model priced by prompt length: a
     * request whose prompt exceeds this many tokens pays the higher prices.
     */
    private static final long HAIKU_55_LONG_PROMPT_THRESHOLD = 100_000L;

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

    // Generation 5.5. Opus 5.5 and Sonnet 5.5 take a 0.05x cache-read
    // multiplier rather than the standard 0.1x.
    private static final CostCalculator COST_OPUS_55 =
        new CostCalculator(4.0, 20.0, 5.0, 0.20);
    private static final CostCalculator COST_SONNET_55 =
        new CostCalculator(2.0, 10.0, 2.5, 0.10);
    private static final CostCalculator COST_HAIKU_55_SHORT =
        new CostCalculator(0.10, 0.50, 0.125, 0.01);
    private static final CostCalculator COST_HAIKU_55_LONG =
        new CostCalculator(0.50, 2.50, 0.625, 0.05);

    /**
     * Claude Haiku 5.5's length-tiered pricing.
     *
     * <p>Anthropic prices Haiku 5.5 by prompt length: a request whose prompt is
     * over 100,000 tokens pays the higher set of prices. The prompt length
     * counts <em>all</em> of the request's input tokens — including cache reads
     * and cache writes, not just the uncached {@code input_tokens}. Each request
     * is priced on its own, so a request over the threshold pays the higher
     * prices across every category (input, output, and both cache directions)
     * even when part of its prompt was a cache hit.
     */
    private static final class LengthTieredCostCalculator extends CostCalculator {

        private final CostCalculator shortPrompt;
        private final CostCalculator longPrompt;

        LengthTieredCostCalculator(CostCalculator shortPrompt, CostCalculator longPrompt) {
            super(shortPrompt.inputCostPerM, shortPrompt.outputCostPerM,
                  shortPrompt.cacheWriteCostPerM, shortPrompt.cacheReadCostPerM);
            this.shortPrompt = shortPrompt;
            this.longPrompt = longPrompt;
        }

        @Override
        public double calculateCost(Usage usage) {
            return promptLength(usage) > HAIKU_55_LONG_PROMPT_THRESHOLD
                ? longPrompt.calculateCost(usage)
                : shortPrompt.calculateCost(usage);
        }

        private static long promptLength(Usage usage) {
            if (usage == null) {
                return 0L;
            }
            return usage.inputTokens()
                + usage.cacheCreationInputTokens()
                + usage.cacheReadInputTokens();
        }
    }

    private static final CostCalculator COST_HAIKU_55 =
        new LengthTieredCostCalculator(COST_HAIKU_55_SHORT, COST_HAIKU_55_LONG);

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
        // Only the generation-5 Sonnet ever carried that promotion; Sonnet 5.5
        // launched at its permanent $2/$10 and must resolve through the normal
        // table. containsVersion("sonnet","5") is prefix-shaped and would
        // otherwise swallow "sonnet-5-5".
        if (isSonnetFive(normalized)) {
            return sonnetFivePricing(pricingDate);
        }
        return BY_MODEL.computeIfAbsent(normalized, CostCalculator::resolvePricing);
    }

    private static CostCalculator resolvePricing(String model) {
        String m = model.toLowerCase(Locale.ROOT);

        // Generation 5.5 resolves before every generation-5 check below:
        // "opus-5" is a substring of "opus-5-5", so the 5.5 branches have to
        // come first or a 5.5 model would silently take the 5.0 tier.
        if (containsVersion(m, "haiku", "5-5")) {
            return COST_HAIKU_55;
        }
        if (containsVersion(m, "opus", "5-5")) {
            return COST_OPUS_55;
        }
        if (containsVersion(m, "sonnet", "5-5")) {
            return COST_SONNET_55;
        }

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

    /**
     * True only for the generation-5 Sonnet that carried introductory pricing.
     * "sonnet-5" is a substring of "sonnet-5-5", so the 5.5 generation (which
     * launched at its permanent price) is explicitly excluded here; otherwise
     * it would inherit a promotion it never had.
     */
    private static boolean isSonnetFive(String model) {
        return containsVersion(model, "sonnet", "5")
            && !containsVersion(model, "sonnet", "5-5");
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
