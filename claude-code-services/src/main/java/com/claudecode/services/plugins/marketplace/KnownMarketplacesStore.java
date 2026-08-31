package com.claudecode.services.plugins.marketplace;


import com.claudecode.core.serialization.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public final class KnownMarketplacesStore {

    private static final Logger LOG = LoggerFactory.getLogger(KnownMarketplacesStore.class);

    private final Path file;

    public KnownMarketplacesStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Throwing load — use on load→mutate→save paths. */
    public KnownMarketplaces load() {
        if (!Files.exists(file)) {
            return KnownMarketplaces.empty();
        }
        try {
            return JsonUtils.getMapper().readValue(file.toFile(), KnownMarketplaces.class);
        } catch (IOException | RuntimeException e) {
            throw new PluginOperationException(
                "Failed to load marketplace configuration: " + e.getMessage(), e);
        }
    }

    /** Graceful load for read-only paths — corrupted config degrades to empty. */
    public KnownMarketplaces loadSafe() {
        try {
            return load();
        } catch (PluginOperationException e) {
            LOG.warn("{}", e.getMessage());
            return KnownMarketplaces.empty();
        }
    }

    public void save(KnownMarketplaces config) {
        try {
            Files.createDirectories(file.getParent());
            JsonUtils.writeJson(file, config, true);
        } catch (IOException e) {
            throw new PluginOperationException(
                "Failed to save marketplace configuration: " + e.getMessage(), e);
        }
    }
}
