package com.claudecode.runtime.sessionhost;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds the model choices projected by Session Link endpoints.
 *
 * <p>Every choice names a concrete model: there is no {@code Default
 * (recommended)} row, because a remote picker that offers it has to render
 * "Default" as the seated selection too, which tells the user nothing about
 * which model actually reaches the wire. Callers report the resolved model id
 * as {@code current} instead. {@code "default"} stays accepted by the
 * {@code set} side as a legacy input — a Session Link client that round-trips
 * an older {@code current} must not be rejected.
 */
@Explanation("Projects the native /model catalogue onto semantic remote endpoints")
public final class SessionHostModelOptions {

    /**
     * The legacy {@code "default"} input every {@code set} side keeps
     * accepting: it no longer appears in {@link #build}'s choices, but a
     * Session Link client that round-trips an older {@code current} still
     * sends it, and it means "clear the preference".
     */
    public static final String DEFAULT_SELECTION = "default";

    private SessionHostModelOptions() {}

    /**
     * The choices for one session. {@code includeBuiltIns} is the caller's
     * provider/credential gate ({@code ModelAvailability.showBuiltInModelFamilies}):
     * there is deliberately no overload that defaults it, because a caller
     * that silently gets {@code true} advertises official families a custom
     * endpoint cannot serve, duplicating its own catalogue entries.
     */
    public static List<SessionHostModelOption> build(
            String current,
            Predicate<String> allowed,
            List<CustomModelConfig> customModels,
            boolean includeBuiltIns) {
        List<SessionHostModelOption> candidates =
            ModelCatalog.pickerFamilies(includeBuiltIns, SubprocessEnvironment::get).stream()
                .map(SessionHostModelOptions::familyOption)
                .toList();
        Predicate<String> predicate = allowed != null ? allowed : _ -> true;
        List<SessionHostModelOption> selected = new ArrayList<>();
        candidates.stream()
            .filter(option -> safelyAllowed(predicate, option.name()))
            .forEach(selected::add);
        if (customModels != null) {
            for (CustomModelConfig custom : customModels) {
                if (custom != null) {
                    selected.add(option(custom.modelName(), custom.modelName(),
                        custom.protocol().displayName() + " · " + custom.baseUrl(), "", false));
                }
            }
        }
        // The current-model fallback row carries the same guard the TUI picker
        // applies (ModelPickerDialog.buildOptions): with built-ins gated off,
        // a built-in selection must not reappear as a candidate — this endpoint
        // cannot call it, and offering it is how the official families leak
        // back alongside the custom catalogue.
        if (StringUtils.isNotBlank(current)
                && (includeBuiltIns || !ModelCatalog.isBuiltInSelection(current))
                && selected.stream().noneMatch(option ->
                    ModelCatalog.sameModel(current, option.name()))) {
            selected.add(option(current,
                Strings.CI.equals("opusplan", current)
                    ? "Opus Plan Mode" : ModelNames.displayName(current),
                Strings.CI.equals("opusplan", current)
                    ? "Use Opus in plan mode, Sonnet otherwise" : "Current session model",
                "", false));
        }
        LinkedHashMap<String, SessionHostModelOption> unique = new LinkedHashMap<>();
        selected.forEach(option -> unique.putIfAbsent(option.name(), option));
        return List.copyOf(unique.values());
    }

    /**
     * The seated model id for a remote picker: {@code modelPreference} when the
     * user picked something, otherwise {@code effectiveModel} — the concrete id
     * that actually reaches the wire. {@code opusplan} survives verbatim
     * because it resolves to two different models by mode, so its own choice
     * row is the only honest seat for it.
     */
    public static String currentSelection(String modelPreference, String effectiveModel) {
        if (Strings.CI.equals("opusplan", StringUtils.trimToEmpty(modelPreference))) {
            return "opusplan";
        }
        return StringUtils.isNotBlank(modelPreference)
            ? modelPreference.strip() : StringUtils.trimToEmpty(effectiveModel);
    }

    /**
     * True when {@code selected} names one of {@code options} or is
     * {@link #DEFAULT_SELECTION}. Callers guard their {@code set} side with
     * this so the legacy input can never be rejected by an unlisted-choice
     * check.
     */
    public static boolean isSelectable(
            List<SessionHostModelOption> options, String selected) {
        if (Strings.CS.equals(DEFAULT_SELECTION, selected)) return true;
        return options != null && options.stream()
            .anyMatch(option -> Strings.CS.equals(selected, option.name()));
    }

    private static SessionHostModelOption familyOption(ModelCatalog.Family family) {
        return option(family.alias(), ModelCatalog.label(family, SubprocessEnvironment::get),
            ModelCatalog.description(family, SubprocessEnvironment::get), family.alias(), false);
    }

    private static SessionHostModelOption option(
            String name, String label, String description, String alias, boolean defaultOption) {
        return new SessionHostModelOption(name, label, description, alias, defaultOption);
    }

    private static boolean safelyAllowed(Predicate<String> predicate, String model) {
        try {
            return predicate.test(model);
        } catch (RuntimeException _) {
            return false;
        }
    }
}
