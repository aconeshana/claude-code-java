package com.claudecode.cli;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import java.util.*;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;

/**
 * Composite {@link CustomModelCatalog} merging persistent {@code ~/.claude/model.json} entries
 * with live read-only models discovered from enabled plugin {@code modelProviders} sidecars.
 *
 * <p>User-configured entries in {@code primary} always take precedence over plugin-provided
 * entries with the same {@code modelName}. Mutations ({@link #save}, {@link #remove}) apply only
 * to {@code primary}; plugin models are read-only projections.
 */
@Explanation("Merges persistent model.json entries with dynamic plugin model-provider sidecar models")
final class CliCompositeModelCatalog implements CustomModelCatalog {

    record ProvidedModel(String pluginId, String providerId, CustomModelConfig config) {}

    private final CustomModelCatalog primary;
    private final Supplier<List<ProvidedModel>> dynamicSupplier;

    CliCompositeModelCatalog(
            CustomModelCatalog primary,
            Supplier<List<ProvidedModel>> dynamicSupplier) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.dynamicSupplier = dynamicSupplier != null ? dynamicSupplier : List::of;
    }

    @Override
    public List<CustomModelConfig> list() {
        List<CustomModelConfig> primaryList = primary.list();
        Map<String, CustomModelConfig> merged = new LinkedHashMap<>();
        for (CustomModelConfig cfg : primaryList) {
            merged.put(cfg.modelName(), cfg);
        }
        for (ProvidedModel pm : safeDynamicModels()) {
            if (pm != null && pm.config() != null) {
                merged.putIfAbsent(pm.config().modelName(), pm.config());
            }
        }
        return List.copyOf(merged.values());
    }

    @Override
    public Optional<CustomModelConfig> find(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return Optional.empty();
        }
        Optional<CustomModelConfig> local = primary.find(modelName);
        if (local.isPresent()) {
            return local;
        }
        for (ProvidedModel pm : safeDynamicModels()) {
            if (pm != null && pm.config() != null && modelName.equals(pm.config().modelName())) {
                return Optional.of(pm.config());
            }
        }
        return Optional.empty();
    }

    @Override
    public void save(CustomModelConfig model) {
        primary.save(model);
    }

    @Override
    public boolean remove(String modelName) {
        return primary.remove(modelName);
    }

    /** Returns the plugin ID providing {@code modelName}, or {@code null} for persistent models. */
    String sourcePluginOf(String modelName) {
        if (modelName == null || primary.find(modelName).isPresent()) {
            return null;
        }
        for (ProvidedModel pm : safeDynamicModels()) {
            if (pm != null && pm.config() != null && modelName.equals(pm.config().modelName())) {
                return pm.pluginId();
            }
        }
        return null;
    }

    /** Snapshot map of modelName -> pluginId for all non-shadowed plugin-provided models. */
    Map<String, String> pluginSourcesByModel() {
        Map<String, String> out = new LinkedHashMap<>();
        List<CustomModelConfig> primaryList = primary.list();
        Set<String> primaryNames = new HashSet<>();
        for (CustomModelConfig cfg : primaryList) {
            primaryNames.add(cfg.modelName());
        }
        for (ProvidedModel pm : safeDynamicModels()) {
            if (pm != null && pm.config() != null) {
                String name = pm.config().modelName();
                if (!primaryNames.contains(name)) {
                    out.putIfAbsent(name, pm.pluginId());
                }
            }
        }
        return out;
    }

    private List<ProvidedModel> safeDynamicModels() {
        try {
            List<ProvidedModel> list = dynamicSupplier.get();
            return list == null ? List.of() : new ArrayList<>(list);
        } catch (RuntimeException _) {
            return List.of();
        }
    }
}
