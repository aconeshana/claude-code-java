package com.claudecode.core.model;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Model id → human display name, the default main-loop model constant, and the {@code /model} label
 * renderer.
 */
public final class ModelNames {

    private static final Pattern API_CONTEXT_TAG = Pattern.compile("\\[(1|2)m\\]", Pattern.CASE_INSENSITIVE);
    private static final String GPT_5_6_SOL_MODEL = "gpt-5.6-sol";
    private static final String GPT_5_6_LUNA_MODEL = "gpt-5.6-luna";

    private ModelNames() {}

    /**
     * Fallback main-loop model when {@code ANTHROPIC_DEFAULT_SONNET_MODEL} is unset.
     */
    public static final String DEFAULT_MAIN_LOOP_MODEL = ModelCatalog.LATEST_SONNET;


    public static String defaultMainLoopModel() {
        return ModelCatalog.resolve("sonnet");
    }

    /**
     * {@link #defaultMainLoopModel} with an injectable env lookup — pure
     * function, testable without touching the real process environment.
     * The production overload observes settings.env and SDK runtime overlays
     * through {@link SubprocessEnvironment}.
     */
    public static String defaultMainLoopModel(Function<String, String> envLookup) {
        return ModelCatalog.resolve("sonnet", envLookup);
    }


    public static final String DEFAULT_HAIKU_MODEL = ModelCatalog.LATEST_HAIKU;


    public static String defaultOpusModel() {
        return ModelCatalog.resolve("opus");
    }

    public static String defaultOpusModel(Function<String, String> envLookup) {
        return ModelCatalog.resolve("opus", envLookup);
    }


    public static String defaultHaikuModel() {
        return ModelCatalog.resolve("haiku");
    }

    public static String defaultHaikuModel(Function<String, String> envLookup) {
        return ModelCatalog.resolve("haiku", envLookup);
    }


    public static String parseUserSpecifiedModel(String modelInput) {
        return parseUserSpecifiedModelWithResolver(modelInput, ModelCatalog::resolve);
    }

    public static String parseUserSpecifiedModel(String modelInput,
            Function<String, String> envLookup) {
        return parseUserSpecifiedModelWithResolver(modelInput,
            model -> ModelCatalog.resolve(model, envLookup));
    }

    @Explanation("Resolves Java's built-in sol/luna aliases to GPT-5.6 custom model ids")
    private static String parseUserSpecifiedModelWithResolver(String modelInput,
            Function<String, String> familyResolver) {
        if (StringUtils.isBlank(modelInput)) return modelInput;
        String normalized = modelInput.trim().toLowerCase(Locale.ROOT);
        boolean has1m = Strings.CS.endsWith(normalized, "[1m]");
        String base = has1m ? normalized.substring(0, normalized.length() - 4).trim() : normalized;
        String tag = has1m ? "[1m]" : "";
        return switch (base) {
            case "opusplan", "sonnet" -> familyResolver.apply("sonnet" + tag);
            case "haiku", "opus", "fable" -> familyResolver.apply(base + tag);
            case "best" -> familyResolver.apply("opus");
            case "sol" -> has1m ? modelInput : GPT_5_6_SOL_MODEL;
            case "luna" -> has1m ? modelInput : GPT_5_6_LUNA_MODEL;
            default -> modelInput;  // concrete id — pass through unchanged
        };
    }

    /**
     * Removes context-window tags that are meaningful to the client but are not
     * part of a provider model id. Internal model selection and accounting keep
     * the tagged value; wire serializers call this only when emitting a request.
     */
    public static String normalizeModelStringForApi(String model) {
        return model == null ? null : API_CONTEXT_TAG.matcher(model).replaceAll("");
    }


    public static String runtimeMainLoopModel(String modelSetting, PermissionModeKind permissionMode,
                                              boolean exceeds200kTokens) {
        String setting = modelSetting == null ? "" : modelSetting.trim().toLowerCase(Locale.ROOT);
        boolean planMode = permissionMode == PermissionModeKind.PLAN;
        if (Strings.CS.equals("opusplan", setting) && planMode && !exceeds200kTokens) {
            return defaultOpusModel();
        }
        if (Strings.CS.equals("haiku", setting) && planMode) {
            return defaultMainLoopModel();
        }
        return parseUserSpecifiedModel(modelSetting);
    }

    /**
     * Convert a raw model id (e.g. {@code "claude-sonnet-4-6"}) to a display
     * name (e.g. {@code "Sonnet 4.6"}). Returns the input unchanged if no
     * mapping is found, or {@code "unknown"} for null/empty.
     */
    public static String displayName(String model) {
        if (StringUtils.isEmpty(model)) return "unknown";

        String m = model.toLowerCase(Locale.ROOT);


        if (Strings.CS.equals(m, "opusplan")) return "Opus Plan";
        if (Strings.CS.equals(m, "sol")) return "GPT-5.6 Sol";
        if (Strings.CS.equals(m, "luna")) return "GPT-5.6 Luna";

        if (Strings.CS.contains(m, "fable-5")) return "Fable 5";

        // Opus models
        if (Strings.CS.contains(m, "opus-5")) return "Opus 5";
        if (Strings.CS.contains(m, "opus-4-8") || Strings.CS.contains(m, "opus-4.8")) return "Opus 4.8";
        if (Strings.CS.contains(m, "opus-4-7") || Strings.CS.contains(m, "opus-4.7")) return "Opus 4.7";
        if (Strings.CS.contains(m, "opus-4-6") || Strings.CS.contains(m, "opus-4.6")) {
            return Strings.CS.contains(m, "1m") ? "Opus 4.6 (1M context)" : "Opus 4.6";
        }
        if (Strings.CS.contains(m, "opus-4-5") || Strings.CS.contains(m, "opus-4.5")) return "Opus 4.5";
        if (Strings.CS.contains(m, "opus-4-1") || Strings.CS.contains(m, "opus-4.1")) return "Opus 4.1";
        if (Strings.CS.contains(m, "opus-4-0") || Strings.CS.contains(m, "opus-4.0")) return "Opus 4";

        // Sonnet models
        if (Strings.CS.contains(m, "sonnet-5")) {
            return Strings.CS.contains(m, "1m") ? "Sonnet 5 (1M context)" : "Sonnet 5";
        }
        if (Strings.CS.contains(m, "sonnet-4-6") || Strings.CS.contains(m, "sonnet-4.6")) {
            return Strings.CS.contains(m, "1m") ? "Sonnet 4.6 (1M context)" : "Sonnet 4.6";
        }
        if (Strings.CS.contains(m, "sonnet-4-5") || Strings.CS.contains(m, "sonnet-4.5")) {
            return Strings.CS.contains(m, "1m") ? "Sonnet 4.5 (1M context)" : "Sonnet 4.5";
        }
        if (Strings.CS.contains(m, "sonnet-4")) return "Sonnet 4";
        if (Strings.CS.contains(m, "sonnet-3-7") || Strings.CS.contains(m, "sonnet-3.7")) return "Sonnet 3.7";
        if (Strings.CS.contains(m, "sonnet-3-5") || Strings.CS.contains(m, "sonnet-3.5")) return "Sonnet 3.5";

        // Haiku models
        if (Strings.CS.contains(m, "haiku-4-5") || Strings.CS.contains(m, "haiku-4.5")) return "Haiku 4.5";
        if (Strings.CS.contains(m, "haiku-3-5") || Strings.CS.contains(m, "haiku-3.5")) return "Haiku 3.5";
        if (Strings.CS.contains(m, "haiku-3")) return "Haiku 3";

        // Fallback: return the original model name
        return model;
    }


    public static String renderModelLabel(String modelOrNull) {
        return renderModelLabel(modelOrNull, SubprocessEnvironment::get);
    }

    /** {@link #renderModelLabel(String)} with an injectable env lookup — pure, testable. */
    public static String renderModelLabel(String modelOrNull, Function<String, String> envLookup) {
        if (modelOrNull == null) {
            return displayName(defaultMainLoopModel(envLookup)) + " (default)";
        }
        return displayName(modelOrNull);
    }
}
