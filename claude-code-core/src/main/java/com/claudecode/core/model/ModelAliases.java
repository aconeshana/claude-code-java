package com.claudecode.core.model;

import com.claudecode.core.annotation.Explanation;
import java.util.List;
import java.util.Locale;

/**
 * Model alias constants + recognition — the short predefined names the
 * {@code /model} command accepts without a live validation API call.
 *
 * <ul>
 *   <li>{@code MODEL_ALIASES}
 *       and {@code isModelAlias}, i.e. the {@code isKnownAlias} /
 *       {@code validateModel} alias short-circuit used by
 *.</li>
 * </ul>
 */
public final class ModelAliases {

    private ModelAliases() {}

    /** Predefined aliases accepted by {@code /model} without an API call. */
    @Explanation("Adds Java-specific sol/luna aliases for the built-in GPT-5.6 custom routes")
    public static final List<String> MODEL_ALIASES = List.of(
        "sonnet", "opus", "haiku", "fable", "best", "sonnet[1m]", "opus[1m]",
        "fable[1m]", "opusplan", "sol", "luna"
    );

    /**
     * matches {@code isKnownAlias} / {@code validateModel}'s alias check in
     * lowercase + trim before matching. Model names are
     * otherwise case-sensitive, but the predefined aliases are not.
     */
    public static boolean isModelAlias(String modelInput) {
        if (modelInput == null) return false;
        return MODEL_ALIASES.contains(modelInput.toLowerCase(Locale.ROOT).trim());
    }
}
