package com.claudecode.core.lsp;

import java.util.List;

/**
 * A single LSP plugin recommended for a file the user just opened, derived from a marketplace
 * catalog entry whose {@code lspServers} cover the file's extension and whose server binary is
 * already installed.
 */
public record LspPluginRecommendation(
    /** {@code "pluginName@marketplaceName"} — stable install id. */
    String pluginId,
    String pluginName,
    String description,
    /** File extensions (with leading dot, lower-cased) this server covers. */
    List<String> extensions,
/**
     * LSP server launch command (e.g.
     */
    String command,
    /** From an official Anthropic marketplace? */
    boolean isOfficial,
    /** Marketplace the plugin is cataloged under (install-source resolution). */
    String marketplaceName) {
}
