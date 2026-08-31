package com.claudecode.services.plugins.marketplace;


import com.claudecode.core.serialization.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads/writes (V2 format only).
 */
public final class InstalledPluginsStore {

    private static final Logger LOG = LoggerFactory.getLogger(InstalledPluginsStore.class);

    private final Path file;

    public InstalledPluginsStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public InstalledPlugins load() {
        if (!Files.exists(file)) {
            return InstalledPlugins.empty();
        }
        try {
            InstalledPlugins loaded =
                JsonUtils.getMapper().readValue(file.toFile(), InstalledPlugins.class);
            if (loaded.version() != InstalledPlugins.CURRENT_VERSION) {
                LOG.warn("installed_plugins.json has unsupported version {}, starting empty",
                    loaded.version());
                return InstalledPlugins.empty();
            }
            return loaded;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to load installed_plugins.json: {}. Starting with empty state.",
                e.getMessage());
            return InstalledPlugins.empty();
        }
    }

    public void save(InstalledPlugins data) {
        try {
            Files.createDirectories(file.getParent());
            JsonUtils.writeJson(file, data, true);
        } catch (IOException e) {
            throw new PluginOperationException(
                "Failed to save installed plugins: " + e.getMessage(), e);
        }
    }
}
