package com.claudecode.core.model;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Resolves which model a refused turn may be retried on.
 */
public final class RefusalFallbackTarget {


    private static final Pattern EARLY_ACCESS = Pattern.compile("-eap($|\\[)",
        Pattern.CASE_INSENSITIVE);

    private static final String OPUS_ID_PREFIX = "claude-opus-";
    private static final String FABLE_ID_PREFIX = "claude-fable-";
    private static final String MYTHOS_ID_PREFIX = "claude-mythos-";
    private static final String LONG_CONTEXT_TAG = "[1m]";

    /**
     * The deployment facts the resolution reads.
     */
    public record Inputs(
        boolean firstPartyLikeProvider,
        Function<String, String> envLookup,
        Predicate<String> callable
    ) {

        public Inputs {
            if (envLookup == null) envLookup = _ -> null;
            if (callable == null) callable = _ -> true;
        }
    }

    private RefusalFallbackTarget() {
    }

    /**
     * The model a refused turn is retried on, or {@code null} when there is
     * none. This is the candidate narrowed to what the deployment may call.
     */
    public static String resolve(String currentModel, Inputs inputs) {
        String candidate = candidate(currentModel, inputs);
        if (candidate == null) return null;
        if (inputs.callable().test(candidate)) return candidate;
        String catalogueOpus = ModelCatalog.OPUS.modelId();
        return inputs.callable().test(catalogueOpus) ? catalogueOpus : null;
    }

    /**
     * Whether a target exists for {@code currentModel}, which.
     */
    public static boolean exists(String currentModel, Inputs inputs) {
        return candidate(currentModel, inputs) != null;
    }


    private static String candidate(String currentModel, Inputs inputs) {
        if (StringUtils.isBlank(currentModel)) return null;
        String resolved = ModelCatalog.resolve(currentModel, inputs.envLookup());
        if (Strings.CI.startsWith(resolved, MYTHOS_ID_PREFIX)) return null;
        if (!isFlaggingSource(currentModel, resolved, inputs)) return null;
        if (!inputs.firstPartyLikeProvider()) return validatedOpusPin(inputs);

        String opusDefault = opusDefault(inputs);
        if (!isOpusFamily(ModelCatalog.resolve(opusDefault, inputs.envLookup()))) return null;
        if (Strings.CI.contains(currentModel, LONG_CONTEXT_TAG)) return opusDefault;
        String untagged = stripLongContextTag(opusDefault);
        if (!Strings.CS.equals(untagged, opusDefault)
                && shrinksContextWindow(currentModel, untagged)) {
            return null;
        }
        return untagged;
    }

    /**
     * Whether this model's own safeguards are the ones that flag, which is what makes retrying on
     * another model worth doing.
     */
    private static boolean isFlaggingSource(String currentModel, String resolved, Inputs inputs) {
        if (Strings.CI.startsWith(resolved, FABLE_ID_PREFIX)) return true;
        if (EARLY_ACCESS.matcher(currentModel).find()) return true;
        String fablePin = inputs.envLookup().apply(ModelCatalog.FABLE.environmentVariable());
        return StringUtils.isNotBlank(fablePin)
            && Strings.CI.equals(stripTrailingLongContextTag(currentModel),
                stripTrailingLongContextTag(fablePin));
    }


    private static String opusDefault(Inputs inputs) {
        String pin = inputs.envLookup().apply(ModelCatalog.OPUS.environmentVariable());
        return StringUtils.isNotBlank(pin) ? pin.trim() : ModelCatalog.OPUS.modelId();
    }


    private static String validatedOpusPin(Inputs inputs) {
        String pin = inputs.envLookup().apply(ModelCatalog.OPUS.environmentVariable());
        if (StringUtils.isBlank(pin)) return ModelCatalog.OPUS.modelId();
        String resolved = ModelCatalog.resolve(pin.trim(), inputs.envLookup());
        boolean claudeModel = Strings.CI.startsWith(
            stripTrailingLongContextTag(resolved), "claude-");
        return claudeModel && !isOpusFamily(resolved) ? null : pin.trim();
    }


    private static boolean isOpusFamily(String resolvedModel) {
        return Strings.CI.startsWith(resolvedModel, OPUS_ID_PREFIX);
    }


    private static boolean shrinksContextWindow(String currentModel, String target) {
        return ModelContextWindows.defaultContextWindow(target)
            < ModelContextWindows.defaultContextWindow(currentModel);
    }


    private static String stripLongContextTag(String model) {
        return Strings.CI.replace(model, LONG_CONTEXT_TAG, "");
    }


    private static String stripTrailingLongContextTag(String model) {
        return Strings.CI.endsWith(model, LONG_CONTEXT_TAG)
            ? model.substring(0, model.length() - LONG_CONTEXT_TAG.length()) : model;
    }
}
