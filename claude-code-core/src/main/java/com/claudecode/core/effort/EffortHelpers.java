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

    /**
     * Session-only pseudo-level: {@code xhigh} effort plus standing dynamic-workflow
     * orchestration. Deliberately absent from {@link #LEVELS} and from every
     * capability list — it is a selectable UI/command value that folds to a real
     * level before anything reaches the wire.
     *
     * <p>Authoritative source: the 2.1.236 bundle's alias table {@code fNd},
     * whose sole entry is {@code {ultracode: "xhigh"}}.
     */
    public static final String ULTRACODE = "ultracode";

    /** The real level {@link #ULTRACODE} folds to on the wire (the 236 bundle's {@code fNd}). */
    private static final String ULTRACODE_WIRE_LEVEL = "xhigh";

    /**
     * Bare meaning of {@link #ULTRACODE}, with no session-scope suffix. 236 renders the
     * scope differently per surface, so callers append it: {@code /effort} help uses
     * {@code "... (this session only)"}, the status line uses {@code "...; this session only"},
     * and the confirmation line carries the scope in its own suffix before the colon.
     */
    public static final String ULTRACODE_DESCRIPTION =
        "xhigh + dynamic workflow orchestration";

    /** Session-scope suffix the status line appends to {@link #ULTRACODE_DESCRIPTION}. */
    public static final String ULTRACODE_SESSION_SCOPE = "; this session only";

    /** The effort slider's sublabel under the {@link #ULTRACODE} slot. */
    public static final String ULTRACODE_SUBLABEL = "xhigh + workflows";

    /**
     * Detail the effort notification appends when {@link #ULTRACODE} is the selection. 236's
     * {@code fFh} swaps the whole tail for it: the ordinary row ends in {@code " · /effort"},
     * the ultracode row ends in this instead.
     */
    public static final String ULTRACODE_NOTIFICATION_DETAIL =
        "xhigh effort + dynamic workflows for maximum thoroughness";

    /** Shown when {@link #ULTRACODE} is asked for but {@link #isUltracodeAvailable} is false. */
    public static final String ULTRACODE_UNAVAILABLE =
        "ultracode is not available for this session (dynamic workflows are off, "
        + "or the model / your organization does not allow xhigh effort)";

    /** Shown by the slider when an organization ceiling hides the stronger levels. */
    public static final String ORG_RESTRICTED_NOTICE =
        "Higher effort levels are restricted by your organization.";

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

    /** Whether {@code value} names the {@link #ULTRACODE} pseudo-level, case-insensitively. */
    public static boolean isUltracode(String value) {
        return value != null && Strings.CI.equals(ULTRACODE, value.trim());
    }

    /**
     * Whether {@code value} is something the user may select — a real level or the
     * {@link #ULTRACODE} pseudo-level. Use this for command/picker validation;
     * use {@link #isEffortLevel} for anything that feeds the wire.
     */
    public static boolean isSelectableEffort(String value) {
        return isEffortLevel(value) || isUltracode(value);
    }

    /**
     * Folds the {@link #ULTRACODE} pseudo-level onto the real level it requests,
     * leaving every other value untouched. Every path that produces a wire value
     * runs through this, so {@code ultracode} can never reach the API.
     */
    public static String foldPseudoLevel(String value) {
        return isUltracode(value) ? ULTRACODE_WIRE_LEVEL : value;
    }

    /**
     * Whether the {@link #ULTRACODE} slot is offered at all — the 236 bundle's
     * {@code Cte}: dynamic workflows must be available, and when a model is known
     * it must support {@code xhigh} and the organization must permit it.
     *
     * @param model            the active model, or blank when not yet resolved
     * @param workflowsEnabled whether dynamic-workflow orchestration is available
     *                         (the bundle's {@code ZM})
     * @param orgMaxLevel      the organization's effort ceiling, or {@code null}
     *                         when uncapped (the bundle's {@code F1r})
     */
    public static boolean isUltracodeAvailable(
            String model, boolean workflowsEnabled, String orgMaxLevel) {
        if (!workflowsEnabled) return false;
        if (StringUtils.isBlank(model)) return true;
        if (!capabilitiesForModel(model).supports(ULTRACODE_WIRE_LEVEL)) return false;
        return allowsLevel(ULTRACODE_WIRE_LEVEL, orgMaxLevel);
    }

    /**
     * Whether {@code level} sits at or below an organization's ceiling — the 236
     * bundle's {@code tpt}, comparing positions in {@link #ORDERED_LEVELS}
     * (the bundle's {@code KF}). A blank or unrecognized ceiling means uncapped.
     */
    public static boolean allowsLevel(String level, String orgMaxLevel) {
        if (StringUtils.isBlank(orgMaxLevel)) return true;
        int cap = ORDERED_LEVELS.indexOf(orgMaxLevel);
        int wanted = ORDERED_LEVELS.indexOf(level);
        return cap < 0 || wanted < 0 || wanted <= cap;
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
        // ultracode is a selectable pseudo-level, never a wire value: fold it to the
        // real level it requests before the capability gate and the return.
        String normalized = foldPseudoLevel(resolved.toLowerCase(Locale.ROOT));
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
        if (isUltracode(level)) return ULTRACODE_DESCRIPTION;
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
     * Maps an effort level to its single-glyph indicator character. {@link #ULTRACODE} keeps
     * its own glyph rather than borrowing {@code xhigh}'s — 236's {@code Edo} branches on the
     * pseudo-level before consulting the per-level table {@code kQi}.
     */
    public static String effortLevelToSymbol(String level) {
        if (isUltracode(level)) return Figures.EFFORT_ULTRACODE;
        return switch (level == null ? "" : level) {
            case "none", "minimal" -> Figures.EFFORT_LOW;
            case "low"    -> Figures.EFFORT_LOW;
            case "medium" -> Figures.EFFORT_MEDIUM;
            case "high"   -> Figures.EFFORT_HIGH;
            case "xhigh"  -> Figures.EFFORT_XHIGH;
            case "max"    -> Figures.EFFORT_MAX;
            default       -> Figures.EFFORT_HIGH;
        };
    }


    /**
     * The transient effort hint shown beside the prompt, e.g. {@code "◐ medium · /effort"}.
     * Returns {@code null} when the model has no effort control at all.
     *
     * <p>The {@link #ULTRACODE} row replaces the trailing {@code " · /effort"} with
     * {@link #ULTRACODE_NOTIFICATION_DETAIL} rather than appending to it, matching 236's
     * {@code fFh}, whose two branches share no tail.
     */
    public static String getEffortNotificationText(String effortValue, String model) {
        if (!modelSupportsEffort(model)) return null;
        String level = getDisplayedEffortSelection(model, effortValue);
        if (isUltracode(level)) {
            return Figures.EFFORT_ULTRACODE + " " + ULTRACODE + " · " + ULTRACODE_NOTIFICATION_DETAIL;
        }
        return effortLevelToSymbol(level) + " " + level + " · /effort";
    }


    public static String getDisplayedEffortLevel(String model, String appStateEffort) {
        String resolved = resolveAppliedEffort(model, appStateEffort);
        return resolved != null ? resolved : "high";
    }

    /**
     * The label status surfaces show for the user's current selection. Unlike
     * {@link #getDisplayedEffortLevel}, {@code ultracode} survives here rather than
     * appearing as the {@code xhigh} it folds to on the wire — matching 236, whose
     * status line reads {@code Current effort level: ultracode (...)}. It degrades to
     * the resolved level when the folded level is not actually usable.
     */
    public static String getDisplayedEffortSelection(String model, String appStateEffort) {
        if (isUltracode(appStateEffort) && resolveAppliedEffort(model, appStateEffort) != null) {
            return ULTRACODE;
        }
        return getDisplayedEffortLevel(model, appStateEffort);
    }


    public static String toPersistableEffort(String value) {
        // ultracode is session-scoped by contract: interactive toggles never persist it.
        // Stated explicitly so adding it to a level list later cannot silently make it sticky.
        if (isUltracode(value)) return null;
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
        return isSelectableEffort(value) ? value : "high";
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
