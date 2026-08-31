package com.claudecode.services.plugins.marketplace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Plugin / marketplace author information.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginAuthor(String name, String email, String url) {

    public static PluginAuthor of(String name) {
        return new PluginAuthor(name, null, null);
    }
}
