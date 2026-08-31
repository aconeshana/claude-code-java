package com.claudecode.services.plugins.marketplace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/**
 * a curated collection of installable plugins.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketplaceManifest(
    String name,
    PluginAuthor owner,
    List<MarketplacePluginEntry> plugins,
    Boolean forceRemoveDeletedPlugins,
    Metadata metadata,
    List<String> allowCrossMarketplaceDependenciesOn) {

    /** Optional marketplace metadata block. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(String pluginRoot, String version, String description) {}

    /** Finds a plugin entry by name. */
    public Optional<MarketplacePluginEntry> findPlugin(String pluginName) {
        if (plugins == null) {
            return Optional.empty();
        }
        return plugins.stream()
            .filter(entry -> pluginName.equals(entry.name()))
            .findFirst();
    }
}
