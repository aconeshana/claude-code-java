package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CliCompositeModelCatalogTest {

    private static final class InMemoryCatalog implements CustomModelCatalog {
        private final Map<String, CustomModelConfig> store = new LinkedHashMap<>();

        @Override public List<CustomModelConfig> list() { return new ArrayList<>(store.values()); }
        @Override public Optional<CustomModelConfig> find(String modelName) {
            return Optional.ofNullable(store.get(modelName));
        }
        @Override public void save(CustomModelConfig model) { store.put(model.modelName(), model); }
        @Override public boolean remove(String modelName) { return store.remove(modelName) != null; }
    }

    @Test
    void mergesDynamicModelsAndPreservesPrimaryPrecedence() {
        InMemoryCatalog primary = new InMemoryCatalog();
        CustomModelConfig userModel = new CustomModelConfig(
            "trae-cn/glm-5", ModelApiProtocol.OPENAI_CHAT, "https://user.example/v1", "user-key", Map.of());
        primary.save(userModel);

        CustomModelConfig shadowedPluginModel = new CustomModelConfig(
            "trae-cn/glm-5", ModelApiProtocol.OPENAI_CHAT, "http://127.0.0.1:19999/v1", "prov-tok", Map.of());
        CustomModelConfig freshPluginModel = new CustomModelConfig(
            "trae-cn/deepseek-v4", ModelApiProtocol.OPENAI_CHAT, "http://127.0.0.1:19999/v1", "prov-tok", Map.of(),
            200_000L, Boolean.TRUE);

        CliCompositeModelCatalog composite = new CliCompositeModelCatalog(
            primary,
            () -> List.of(
                new CliCompositeModelCatalog.ProvidedModel(
                    "opencode-provider-bridge@poco-plugins", "opencode-bridge", shadowedPluginModel),
                new CliCompositeModelCatalog.ProvidedModel(
                    "opencode-provider-bridge@poco-plugins", "opencode-bridge", freshPluginModel)));

        assertEquals(2, composite.list().size());
        assertEquals("https://user.example/v1", composite.find("trae-cn/glm-5").orElseThrow().baseUrl());
        assertEquals("http://127.0.0.1:19999/v1", composite.find("trae-cn/deepseek-v4").orElseThrow().baseUrl());
        assertNull(composite.sourcePluginOf("trae-cn/glm-5"));
        assertEquals("opencode-provider-bridge@poco-plugins", composite.sourcePluginOf("trae-cn/deepseek-v4"));

        // Plugin models are read-only; remove() only affects primary.
        assertFalse(composite.remove("trae-cn/deepseek-v4"));
        assertTrue(composite.find("trae-cn/deepseek-v4").isPresent());
        assertTrue(composite.remove("trae-cn/glm-5"));
    }
}
