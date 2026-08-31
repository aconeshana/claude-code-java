package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared output-token target for one top-level user turn.
 */
public final class TurnTokenBudget {

    private static final Pattern START = Pattern.compile(
        "^\\s*\\+(\\d+(?:\\.\\d+)?)\\s*([kmb])\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern END = Pattern.compile(
        "\\s\\+(\\d+(?:\\.\\d+)?)\\s*([kmb])\\s*[.!?]?\\s*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern VERBOSE = Pattern.compile(
        "\\b(?:use|spend)\\s+(\\d+(?:\\.\\d+)?)\\s*([kmb])\\s*tokens?\\b",
        Pattern.CASE_INSENSITIVE);

    private final Long total;
    private final AtomicLong spent = new AtomicLong();

    public TurnTokenBudget(Long total) {
        this.total = total != null && total > 0 ? total : null;
    }

    public static TurnTokenBudget fromPrompt(Object prompt) {
        return new TurnTokenBudget(parseTarget(promptText(prompt)));
    }

    public static TurnTokenBudget unlimited() {
        return new TurnTokenBudget(null);
    }

    public Long total() {
        return total;
    }

    public long spent() {
        return spent.get();
    }

    public long remaining() {
        return total == null ? Long.MAX_VALUE : Math.max(0L, total - spent());
    }

    public void addOutputTokens(long outputTokens) {
        if (outputTokens > 0) spent.addAndGet(outputTokens);
    }

    public static Long parseTarget(String prompt) {
        if (StringUtils.isBlank(prompt)) return null;
        for (Pattern pattern : new Pattern[] {START, END, VERBOSE}) {
            Matcher matcher = pattern.matcher(prompt);
            if (!matcher.find()) continue;
            double amount;
            try {
                amount = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException _) {
                return null;
            }
            long multiplier = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "k" -> 1_000L;
                case "m" -> 1_000_000L;
                case "b" -> 1_000_000_000L;
                default -> 1L;
            };
            double scaled = amount * multiplier;
            if (!Double.isFinite(scaled) || scaled <= 0 || scaled > Long.MAX_VALUE) return null;
            return Math.round(scaled);
        }
        return null;
    }

    private static String promptText(Object prompt) {
        return switch (prompt) {
            case null -> null;
            case String text -> text;
            case JsonNode node -> node.isTextual() ? node.asText() : node.toString();
            case Collection<?> values -> values.stream().map(TurnTokenBudget::promptText)
                .filter(StringUtils::isNotBlank)
                .reduce((left, right) -> left + "\n" + right).orElse("");
            default -> String.valueOf(prompt);
        };
    }
}
