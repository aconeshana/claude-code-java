package com.claudecode.core.effort;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.process.SubprocessEnvironment;

/**
 * Pure helpers for the {@code /effort} command and the request pipeline.
 */
public final class EffortHelpers {

    /**
     * Superset used by validation and persistence. The actual UI list is
     * model-specific and comes from {@link #supportedEffortLevels(String)}.
     */
    private static final Set<String> LEVELS =
        Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
/** Sentinel returned by {@link #getEffortEnvOverride} when env is {@code auto}/{@code unset}. */
    public static final String ENV_UNSET = "__UNSET__";

    private EffortHelpers() {}

    /**
     * Conservative ordered list for unknown model aliases. Known model families
     * use their exact capability list.
     */
    public static final List<String> ORDERED_LEVELS =
        List.of("low", "medium", "high", "xhigh", "max");
    private static final List<String> GPT_5_6_LEVELS =
        List.of("none", "low", "medium", "high", "xhigh", "max");
    private static final List<String> GPT_5_LEVELS =
        List.of("minimal", "low", "medium", "high");
    private static final List<String> CLAUDE_ALL_LEVELS =
        List.of("low", "medium", "high", "xhigh", "max");
    private static final List<String> CLAUDE_WITHOUT_XHIGH_LEVELS =
        List.of("low", "medium", "high", "max");
    private static final List<String> CLAUDE_BASE_LEVELS =
        List.of("low", "medium", "high");

    /** Model-family effort metadata. {@code known=false} means runtime negotiation is required. */
    public record EffortCapabilities(boolean known, List<String> levels, String defaultLevel) {
        public EffortCapabilities {
            levels = levels == null ? List.of() : List.copyOf(levels);
        }

        public boolean supports(String level) {
            return level != null && levels.contains(level);
        }
    }


    public static boolean isEffortLevel(String value) {
        return value != null && LEVELS.contains(value);
    }

    /**
     * Capability gate derived from the known model-family table. Unknown aliases
     * remain optimistic so a deliberate user choice can be negotiated at runtime.
     */
    public static boolean modelSupportsEffort(String model) {
        if (StringUtils.isBlank(model)) return false;
        if (isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_ALWAYS_ENABLE_EFFORT"))) return true;
        EffortCapabilities capabilities = capabilitiesForModel(model);
        return !capabilities.known() || !capabilities.levels().isEmpty();
    }

    /**
     * Returns whether the known model family explicitly supports {@code max}.
     */
    public static boolean modelSupportsMaxEffort(String model) {
        return capabilitiesForModel(model).supports("max");
    }

    /** Returns the ordered effort levels accepted by a known model family. */
    public static List<String> supportedEffortLevels(String model) {
        EffortCapabilities capabilities = capabilitiesForModel(model);
        return capabilities.known() ? capabilities.levels() : ORDERED_LEVELS;
    }

    /**
     * Resolves current public Claude/OpenAI model-family capability tables.
     * Unknown gateway aliases stay explicitly unknown instead of inheriting a
     * first-party default; callers may optimistically try a user-selected level
     * and let the protocol boundary learn from a parameter-specific rejection.
     */
    public static EffortCapabilities capabilitiesForModel(String model) {
        if (StringUtils.isBlank(model)) {
            return new EffortCapabilities(true, List.of(), null);
        }
        String m = model.trim().toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(m, "gpt-5.6")) {
            return new EffortCapabilities(true, GPT_5_6_LEVELS, "medium");
        }
        if (m.matches(".*(?:^|[./_-])gpt-5(?:$|[./_-]).*")) {
            return new EffortCapabilities(true, GPT_5_LEVELS, "medium");
        }
        if (Strings.CS.contains(m, "sonnet-5")
                || Strings.CS.contains(m, "opus-5")
                || Strings.CS.contains(m, "fable-5")
                || Strings.CS.contains(m, "mythos-5")
                || Strings.CS.contains(m, "opus-4-8")
                || Strings.CS.contains(m, "opus-4-7")) {
            return new EffortCapabilities(true, CLAUDE_ALL_LEVELS, "high");
        }
        if (Strings.CS.contains(m, "sonnet-4-6")
                || Strings.CS.contains(m, "opus-4-6")) {
            return new EffortCapabilities(true, CLAUDE_WITHOUT_XHIGH_LEVELS, "high");
        }
        if (Strings.CS.contains(m, "opus-4-5")) {
            return new EffortCapabilities(true, CLAUDE_BASE_LEVELS, "high");
        }
        if (Strings.CS.contains(m, "haiku")
                || Strings.CS.contains(m, "sonnet")
                || Strings.CS.contains(m, "opus")) {
            return new EffortCapabilities(true, List.of(), null);
        }
        return new EffortCapabilities(false, ORDERED_LEVELS, null);
    }


    public static String getEffortEnvOverride() {
        String raw = SubprocessEnvironment.get("CLAUDE_CODE_EFFORT_LEVEL");
        if (StringUtils.isBlank(raw)) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (Strings.CS.equals("auto", lower) || Strings.CS.equals("unset", lower)) return ENV_UNSET;
        if (LEVELS.contains(lower)) return lower;
        return null;
    }


    public static String resolveAppliedEffort(String model, String appStateEffort) {
        return resolveAppliedEffort(model, appStateEffort, false);
    }

    /** Resolves effort while keeping unknown user-defined endpoints on server-default auto. */
    public static String resolveAppliedEffort(
            String model, String appStateEffort, boolean customModel) {
        String env = getEffortEnvOverride();
        if (ENV_UNSET.equals(env)) return null;
        EffortCapabilities capabilities = capabilitiesForModel(model);
        String resolved = env != null ? env
            : (StringUtils.isNotBlank(appStateEffort)
                ? appStateEffort : getDefaultEffortForModel(model, customModel));
        if (StringUtils.isBlank(resolved)) return null;
        String normalized = resolved.toLowerCase(Locale.ROOT);
        if (!isEffortLevel(normalized)) return null;
        if (capabilities.known() && !capabilities.supports(normalized)) return null;
        return normalized;
    }


    public static String getEffortSuffix(String model, String effortValue) {
        if (StringUtils.isBlank(effortValue)) return "";
        String resolved = resolveAppliedEffort(model, effortValue);
        if (resolved == null) return "";
        return " with " + resolved + " effort";
    }


    public static String getEffortValueDescription(String level) {
        return switch (level) {
            case "none"    -> "Disable reasoning effort for supported GPT models";
            case "minimal" -> "Use the minimum reasoning supported by this GPT model";
            case "low"    -> "Quick, straightforward implementation with minimal overhead";
            case "medium" -> "Balanced approach with standard implementation and testing";
            case "high"   -> "Comprehensive implementation with extensive testing and documentation";
            case "xhigh"  -> "Deeper reasoning than high, just below maximum (Fable 5, Opus 4.7+)";
            case "max"    -> "Maximum capability with deepest reasoning (Opus 4.6 only)";
            default       -> "Balanced approach with standard implementation and testing";
        };
    }

    /**
     * Extra warning blurb shown in the slider dialog for levels that carry a token / latency cost the
     * user should be aware of.
     */
    public static String getEffortLevelWarning(String level) {
        if (Strings.CS.equals("max", level)) {
            return "May use excessive tokens resulting in long response times or overthinking. "
                + "Use sparingly for the hardest tasks.";
        }
        return "";
    }

    /**
     * Maps an effort level to its single-glyph indicator character.
     */
    public static String effortLevelToSymbol(String level) {
        return switch (level == null ? "" : level) {
            case "none", "minimal" -> Figures.EFFORT_LOW;
            case "low"    -> Figures.EFFORT_LOW;
            case "medium" -> Figures.EFFORT_MEDIUM;
            case "high"   -> Figures.EFFORT_HIGH;
            case "xhigh"  -> Figures.EFFORT_MAX;
            case "max"    -> Figures.EFFORT_MAX;
            default       -> Figures.EFFORT_HIGH;
        };
    }


    public static String getEffortNotificationText(String effortValue, String model) {
        if (!modelSupportsEffort(model)) return null;
        String level = getDisplayedEffortLevel(model, effortValue);
        return effortLevelToSymbol(level) + " " + level + " · /effort";
    }


    public static String getDisplayedEffortLevel(String model, String appStateEffort) {
        String resolved = resolveAppliedEffort(model, appStateEffort);
        return resolved != null ? resolved : "high";
    }


    public static String toPersistableEffort(String value) {
        if (Strings.CS.equals("none", value) || Strings.CS.equals("minimal", value)
                || Strings.CS.equals("low", value) || Strings.CS.equals("medium", value)
                || Strings.CS.equals("high", value) || Strings.CS.equals("xhigh", value)) {
            return value;
        }
        if (Strings.CS.equals("max", value) && Strings.CS.equals("ant", System.getenv("USER_TYPE"))) {
            return value;
        }
        return null;
    }

    private static boolean isEnvTruthy(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return !v.isEmpty() && !Strings.CS.equals("false", v) && !Strings.CS.equals("0", v) && !Strings.CS.equals("no", v);
    }

    // ──────────────────────────────────────────────────────────────────────
// Model-picker helpers (implemented for the /model picker's integrated effort
    // row — ← / → adjustment + "set model alone" persistence semantics).
    // ──────────────────────────────────────────────────────────────────────


    public static String convertEffortValueToLevel(String value) {
        return isEffortLevel(value) ? value : "high";
    }

    /**
     * Resolves the request default from Claude Code.
     */
    public static String getDefaultEffortForModel(String model) {
        return getDefaultEffortForModel(model, false);
    }

    /** Returns null for an unknown custom endpoint so its service default remains authoritative. */
    public static String getDefaultEffortForModel(String model, boolean customModel) {
        if (StringUtils.isBlank(model)) return null;
        EffortCapabilities capabilities = capabilitiesForModel(model);
        if (capabilities.known()) return capabilities.defaultLevel();
        return customModel ? null : "high";
    }

    /**
     * The default effort <em>level</em> shown for a model in the picker.
     */
    public static String defaultEffortLevelForModel(String model) {
        String d = getDefaultEffortForModel(model);
        return d != null ? convertEffortValueToLevel(d) : "high";
    }

    /**
     * Cycle the effort level for the model picker's ← / → keys.
     */
    public static String cycleEffortLevel(String current, int direction, String model) {
        List<String> levels = supportedEffortLevels(model);
        if (levels.isEmpty()) return current;
        int idx = levels.indexOf(current);
        String defaultLevel = capabilitiesForModel(model).defaultLevel();
        int defaultIndex = defaultLevel == null ? -1 : levels.indexOf(defaultLevel);
        int cur = idx >= 0 ? idx : (defaultIndex >= 0 ? defaultIndex : 0);
        int next = Math.floorMod(cur + Integer.signum(direction), levels.size());
        return levels.get(next);
    }


    public static String resolvePickerEffortPersistence(
            String picked, String modelDefault, String priorPersisted, boolean toggledInPicker) {
        boolean hadExplicit = priorPersisted != null || toggledInPicker;
        boolean keep = hadExplicit || !Objects.equals(picked, modelDefault);
        return keep ? picked : null;
    }
}
