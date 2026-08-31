package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.runtime.sessionhost.SessionHostModelOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds the model choices projected by Session Link endpoints.
 */
@Explanation("Projects the native /model catalogue onto semantic remote endpoints")
final class SessionHostModelOptions {

    private SessionHostModelOptions() {}

    static List<SessionHostModelOption> build(
            String current,
            Predicate<String> allowed,
            List<CustomModelConfig> customModels) {
        return build(current, allowed, customModels, true);
    }

    static List<SessionHostModelOption> build(
            String current,
            Predicate<String> allowed,
            List<CustomModelConfig> customModels,
            boolean includeBuiltIns) {
        List<SessionHostModelOption> candidates = new ArrayList<>();
        if (includeBuiltIns) {
            candidates.add(option("default", "Default (recommended)",
                "Use the default model (currently "
                    + ModelNames.displayName(ModelNames.defaultMainLoopModel()) + ")",
                "default", true));
        }
        ModelCatalog.pickerFamilies(includeBuiltIns, SubprocessEnvironment::get).stream()
            .map(SessionHostModelOptions::familyOption)
            .forEach(candidates::add);
        Predicate<String> predicate = allowed != null ? allowed : _ -> true;
        List<SessionHostModelOption> selected = new ArrayList<>();
        candidates.stream()
            .filter(option -> option.defaultOption() || safelyAllowed(predicate, option.name()))
            .forEach(selected::add);
        if (customModels != null) {
            for (CustomModelConfig custom : customModels) {
                if (custom != null) {
                    selected.add(option(custom.modelName(), custom.modelName(),
                        custom.protocol().displayName() + " · " + custom.baseUrl(), "", false));
                }
            }
        }
        if (StringUtils.isNotBlank(current)
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
