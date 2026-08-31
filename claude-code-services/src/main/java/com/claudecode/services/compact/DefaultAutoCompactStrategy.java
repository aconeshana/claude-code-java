package com.claudecode.services.compact;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.model.ModelContextWindows;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.services.model.ModelOutputTokens;

import com.claudecode.core.message.Message;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link AutoCompactStrategy}: threshold calculation, model/window source selection, and
 * proactive auto-compaction.
 */
final class DefaultAutoCompactStrategy implements AutoCompactStrategy {

    private static final long MIN_AUTO_COMPACT_WINDOW = 100_000L;
    private static final long MAX_AUTO_COMPACT_WINDOW = 1_000_000L;
    private static final Pattern DECIMAL_PREFIX = Pattern.compile("^\\s*([+-]?\\d+)");


    private static final Set<String> FOUR_CHARS_PER_TOKEN_FAMILIES = Set.of(
        "claude-3-opus",
        "claude-3-sonnet",
        "claude-3-haiku",
        "claude-3-5-sonnet",
        "claude-3-5-haiku",
        "claude-3-7-sonnet",
        "claude-opus-4-0",
        "claude-opus-4-1",
        "claude-opus-4-5",
        "claude-opus-4-6",
        "claude-sonnet-4-0",
        "claude-sonnet-4-5",
        "claude-sonnet-4-6",
        "claude-haiku-4-5"
    );

    private final TokenEstimator tokenEstimator;
    private Function<String, Long> customContextWindowResolver = _ -> null;

    DefaultAutoCompactStrategy(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    void setCustomContextWindowResolver(Function<String, Long> resolver) {
        this.customContextWindowResolver = resolver != null ? resolver : _ -> null;
    }

    @Override
    public boolean shouldTrigger(List<Message> messages, String model, String querySource, boolean autoCompactEnabled) {
        return shouldTrigger(messages, model, querySource, autoCompactEnabled, 0L);
    }

    @Override
    public boolean shouldTrigger(List<Message> messages, String model, String querySource,
                                 boolean autoCompactEnabled, long snipTokensFreed) {
        // Recursive protection: compact itself and session_memory don't trigger
        if (Strings.CS.equals("session_memory", querySource) || Strings.CS.equals("compact", querySource)) {
            return false;
        }

        if (!autoCompactEnabled) {
            return false;
        }


        long tokenCount = Math.max(0L,
            tokenEstimator.tokenCountWithEstimation(
                messages, model, charsPerTokenForModel(model))
                - snipTokensFreed);
        long threshold = getAutoCompactThreshold(model);
        return tokenCount >= threshold;
    }

    @Override
    public CompactService.TokenWarningState calculateTokenWarningState(long tokenUsage, String model, boolean autoCompactEnabled) {
        long threshold = autoCompactEnabled
            ? getAutoCompactThreshold(model)
            : getEffectiveContextWindowSize(model);
        long percentLeft = Math.max(0L, Math.round(((double) (threshold - tokenUsage) / threshold) * 100));

        long warningThreshold = threshold - WARNING_THRESHOLD_BUFFER_TOKENS;
        long errorThreshold   = threshold - ERROR_THRESHOLD_BUFFER_TOKENS;

        boolean isAboveWarningThreshold     = tokenUsage >= warningThreshold;
        boolean isAboveErrorThreshold       = tokenUsage >= errorThreshold;
        boolean isAboveAutoCompactThreshold = autoCompactEnabled && tokenUsage >= threshold;

        long defaultBlockingLimit = getDefaultBlockingLimit(model);
// Support CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE for testing.
        String limitOverride = SubprocessEnvironment.get(
            "CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE");
        long blockingLimit = defaultBlockingLimit;
        if (StringUtils.isNotBlank(limitOverride)) {
            try {
                long parsed = Long.parseLong(limitOverride);
                if (parsed > 0) blockingLimit = parsed;
            } catch (NumberFormatException _) {}
        }

        boolean isAtBlockingLimit = tokenUsage >= blockingLimit;

        return new CompactService.TokenWarningState(
            percentLeft, isAboveWarningThreshold, isAboveErrorThreshold,
            isAboveAutoCompactThreshold, isAtBlockingLimit
        );
    }

    @Override
    public boolean isAtBlockingLimit(long tokenUsage, String model, boolean autoCompactEnabled) {
        return calculateTokenWarningState(tokenUsage, model, autoCompactEnabled).isAtBlockingLimit();
    }

    @Override
    public boolean isAtBlockingLimit(List<Message> messages, String model, boolean autoCompactEnabled) {
        long tokenUsage = tokenEstimator.tokenCountWithEstimation(
            messages, model, charsPerTokenForModel(model));
        return isAtBlockingLimit(tokenUsage, model, autoCompactEnabled);
    }


    static int charsPerTokenForModel(String model) {
        if (StringUtils.isBlank(model)) return 4;
        if (TokenEstimator.isGptModel(model)) return 4;
        String normalized = model.toLowerCase(Locale.ROOT)
            .replace('_', '-')
            .replace('.', '-');
        for (String family : FOUR_CHARS_PER_TOKEN_FAMILIES) {
            if (normalized.equals(family) || Strings.CS.startsWith(normalized, family + "-")) {
                return 4;
            }
        }
        return 3;
    }

    /**
     * Auto-compact threshold: effective context window minus a fixed buffer.
     */
    @Override
    public long getAutoCompactThreshold(String model) {
        return getAutoCompactThreshold(
            model,
            SubprocessEnvironment.get("CLAUDE_CODE_AUTO_COMPACT_WINDOW"),
            SubprocessEnvironment.get("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"));
    }


    @Override
    public String getAutoCompactSource(String model) {
        if (parseAutoCompactWindowOverride(
                SubprocessEnvironment.get(
                    "CLAUDE_CODE_AUTO_COMPACT_WINDOW")) != null) {
            return "env";
        }
        if (customContextWindow(model) != null) return "custom-model";
        return hasModelDefaultWindow(model) ? "model-default" : "auto";
    }

    long getAutoCompactThreshold(String model, String windowOverrideValue, String pctOverride) {
        long effectiveWindow = getEffectiveContextWindowSize(model, windowOverrideValue);

        if (StringUtils.isNotBlank(pctOverride)) {
            try {
                double pct = Double.parseDouble(pctOverride);
                if (pct > 0 && pct <= 100) {
                    long pctThreshold = (long) (effectiveWindow * pct / 100.0);
                    return Math.min(pctThreshold, effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS);
                }
            } catch (NumberFormatException _) {}
        }
        return effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS;
    }

    /**
     * Effective context window = model context window - reserved summary tokens.
     */
    long getEffectiveContextWindowSize(String model) {
        return getEffectiveContextWindowSize(
            model, SubprocessEnvironment.get(
                "CLAUDE_CODE_AUTO_COMPACT_WINDOW"));
    }

    long getEffectiveContextWindowSize(String model, String windowOverrideValue) {
        long contextWindow = resolveModelContextWindow(model);
        Long windowOverride = parseAutoCompactWindowOverride(windowOverrideValue);
        if (windowOverride != null) {
            contextWindow = Math.min(contextWindow, windowOverride);
        }
        return contextWindow - reservedSummaryTokens(model);
    }

    long getDefaultBlockingLimit(String model) {
        return resolveModelContextWindow(model)
            - reservedSummaryTokens(model)
            - MANUAL_COMPACT_BUFFER_TOKENS;
    }

    private static long reservedSummaryTokens(String model) {
        return Math.min(
            ModelOutputTokens.getMaxOutputTokensForModel(model), SYSTEM_PROMPT_RESERVE);
    }

    static Long parseAutoCompactWindowOverride(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        Matcher matcher = DECIMAL_PREFIX.matcher(raw);
        if (!matcher.find()) return null;
        BigInteger parsed = new BigInteger(matcher.group(1));
        if (parsed.signum() <= 0) return null;
        if (parsed.compareTo(BigInteger.valueOf(MAX_AUTO_COMPACT_WINDOW)) > 0) {
            return MAX_AUTO_COMPACT_WINDOW;
        }
        return Math.max(MIN_AUTO_COMPACT_WINDOW, parsed.longValue());
    }

    /**
     * Get the context window size for a model.
     */
    private long resolveModelContextWindow(String model) {
        Long customWindow = customContextWindow(model);
        if (customWindow != null) return customWindow;
        return getModelContextWindow(model);
    }

    static long getModelContextWindow(String model) {
        return ModelContextWindows.defaultContextWindow(model);
    }

    private Long customContextWindow(String model) {
        Long value = customContextWindowResolver.apply(model);
        return value != null && value > 0 ? value : null;
    }

    private static boolean hasModelDefaultWindow(String model) {
        if (StringUtils.isBlank(model)) return false;
        String normalized = model.toLowerCase(Locale.ROOT)
            .replace('_', '-')
            .replace('.', '-');
        return Strings.CS.equals(normalized, "claude-sonnet-4-6")
            || Strings.CS.startsWith(normalized, "claude-sonnet-4-6-")
            || Strings.CS.equals(normalized, "claude-opus-4-6")
            || Strings.CS.startsWith(normalized, "claude-opus-4-6-")
            || ModelContextWindows.isGpt56(model);
    }
}
