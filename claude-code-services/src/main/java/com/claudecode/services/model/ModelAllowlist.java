package com.claudecode.services.model;

import com.claudecode.core.model.ModelAliases;
import com.claudecode.core.model.ModelNames;
import com.claudecode.services.config.RuntimeSettings;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Evaluates the settings-backed {@code availableModels} organization allowlist.
 */
public final class ModelAllowlist {

    private static final List<String> MODEL_FAMILY_ALIASES = List.of("sonnet", "opus", "haiku", "fable");

    private ModelAllowlist() {}

    /** Returns whether a user-specified model is permitted by effective settings. */
    public static boolean isAllowed(String model) {
        if (StringUtils.isBlank(model)) return true;
        JsonNode configured = RuntimeSettings.loadEffectiveSetting("availableModels");
        if (configured == null || !configured.isArray()) return true;

        List<String> allowlist = new ArrayList<>();
        for (JsonNode entry : configured) {
            if (entry.isTextual()) allowlist.add(entry.asText().trim().toLowerCase(Locale.ROOT));
        }
        if (allowlist.isEmpty()) return false;

        String resolvedModel = resolveOverriddenModel(model);
        String normalizedModel = resolvedModel.trim().toLowerCase(Locale.ROOT);

        if (allowlist.contains(normalizedModel)
                && (!isFamilyAlias(normalizedModel)
                    || !familyHasSpecificEntries(normalizedModel, allowlist))) {
            return true;
        }

        for (String entry : allowlist) {
            if (isFamilyAlias(entry)
                    && !familyHasSpecificEntries(entry, allowlist)
                    && modelBelongsToFamily(normalizedModel, entry)) {
                return true;
            }
        }

        if (ModelAliases.isModelAlias(normalizedModel)) {
            String resolved = ModelNames.parseUserSpecifiedModel(normalizedModel)
                .toLowerCase(Locale.ROOT);
            if (allowlist.contains(resolved)) return true;
        }

        for (String entry : allowlist) {
            if (!isFamilyAlias(entry) && ModelAliases.isModelAlias(entry)) {
                String resolved = ModelNames.parseUserSpecifiedModel(entry)
                    .toLowerCase(Locale.ROOT);
                if (resolved.equals(normalizedModel)) return true;
            }
        }

        for (String entry : allowlist) {
            if (!isFamilyAlias(entry) && !ModelAliases.isModelAlias(entry)
                    && modelMatchesVersionPrefix(normalizedModel, entry)) {
                return true;
            }
        }
        return false;
    }

    /** Exact user-facing rejection text from the original model command. */
    public static String rejectionMessage(String model) {
        return "Model '" + model + "' is not available. Your organization restricts model selection.";
    }

    private static boolean modelBelongsToFamily(String model, String family) {
        if (Strings.CS.contains(model, family)) return true;
        if (ModelAliases.isModelAlias(model)) {
            return Strings.CS.contains( ModelNames.parseUserSpecifiedModel(model).toLowerCase(Locale.ROOT), family);
        }
        return false;
    }

    private static boolean modelMatchesVersionPrefix(String model, String entry) {
        String resolved = ModelAliases.isModelAlias(model)
            ? ModelNames.parseUserSpecifiedModel(model).toLowerCase(Locale.ROOT) : model;
        if (prefixMatches(resolved, entry)) return true;
        return !Strings.CS.startsWith(entry, "claude-") && prefixMatches(resolved, "claude-" + entry);
    }

    private static boolean prefixMatches(String model, String prefix) {
        return Strings.CS.startsWith( model, prefix)
            && (model.length() == prefix.length() || model.charAt(prefix.length()) == '-');
    }

    private static boolean familyHasSpecificEntries(String family, List<String> allowlist) {
        for (String entry : allowlist) {
            if (isFamilyAlias(entry)) continue;
            int index = entry.indexOf(family);
            if (index < 0) continue;
            int after = index + family.length();
            if (after == entry.length() || entry.charAt(after) == '-') return true;
        }
        return false;
    }

    private static boolean isFamilyAlias(String value) {
        return MODEL_FAMILY_ALIASES.contains(value);
    }

    /** Resolves settings.modelOverrides values back to their canonical key. */
    private static String resolveOverriddenModel(String model) {
        JsonNode overrides = RuntimeSettings.loadEffectiveSetting("modelOverrides");
        if (overrides == null || !overrides.isObject()) return model;
        var fields = overrides.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getValue().isTextual() && entry.getValue().asText().equals(model)) {
                return entry.getKey();
            }
        }
        return model;
    }
}
