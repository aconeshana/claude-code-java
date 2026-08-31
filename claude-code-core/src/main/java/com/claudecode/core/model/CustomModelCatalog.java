package com.claudecode.core.model;

import com.claudecode.core.annotation.Explanation;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for user-defined model endpoints.
 */
@Explanation("Persistence boundary for user-defined model endpoints")
public interface CustomModelCatalog {
    List<CustomModelConfig> list();
    Optional<CustomModelConfig> find(String modelName);
    void save(CustomModelConfig model);

    /**
     * Removes the exact named model when present.
     *
     * @return {@code true} when a persisted entry was removed
     */
    boolean remove(String modelName);

    /**
     * Returns only an explicitly configured context window.
     */
    default Long contextWindow(String modelName) {
        return find(modelName).map(CustomModelConfig::contextWindow).orElse(null);
    }
}
