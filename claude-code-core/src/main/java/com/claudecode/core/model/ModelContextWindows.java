package com.claudecode.core.model;

import com.claudecode.core.annotation.Explanation;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Shared built-in model context-window defaults.
 */
@Explanation("Adds the 372K built-in context window for GPT 5.6 models")
public final class ModelContextWindows {

    public static final long DEFAULT_CONTEXT_WINDOW = 200_000L;
    public static final long GPT_5_6_CONTEXT_WINDOW = 372_000L;
    public static final long ONE_MILLION_CONTEXT_WINDOW = 1_000_000L;

    private ModelContextWindows() {}

    /** Resolve Java's provider-independent built-in default for a model id. */
    public static long defaultContextWindow(String model) {
        if (model != null && Strings.CI.contains(model, "[1m]")) {
            return ONE_MILLION_CONTEXT_WINDOW;
        }
        if (model != null && Strings.CI.contains(model, "claude-opus-5")) {
            return ONE_MILLION_CONTEXT_WINDOW;
        }
        if (isGpt56(model)) return GPT_5_6_CONTEXT_WINDOW;
        return DEFAULT_CONTEXT_WINDOW;
    }

    /** Matches built-in and gateway-prefixed GPT 5.6 ids, including suffixes. */
    public static boolean isGpt56(String model) {
        if (StringUtils.isBlank(model)) return false;
        String normalized = model.toLowerCase(Locale.ROOT)
            .replace('_', '-')
            .replace('.', '-');
        String family = "gpt-5-6";
        int at = normalized.indexOf(family);
        while (at >= 0) {
            int end = at + family.length();
            if (end == normalized.length() || !Character.isDigit(normalized.charAt(end))) {
                return true;
            }
            at = normalized.indexOf(family, at + 1);
        }
        return false;
    }
}
