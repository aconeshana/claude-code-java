package com.claudecode.ui.lanterna.plugin;

import com.claudecode.services.http.ServiceHttpClient;
import com.claudecode.services.plugins.marketplace.GitExecutor;
import com.claudecode.services.plugins.marketplace.MarketplaceManager;
import com.claudecode.services.plugins.marketplace.PluginError;
import com.claudecode.services.plugins.marketplace.PluginInstaller;
import com.claudecode.services.plugins.marketplace.PluginMarketplaceAdapter;
import com.claudecode.services.plugins.marketplace.PluginSettingsStore;
import com.claudecode.runtime.plugins.PluginMarketplacePort;

import com.claudecode.core.serialization.JsonUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Shared fixtures for the plugin-panel tests: real temp-dir-backed services
 * (directory-source marketplaces need no git/network) plus a synchronous
 * executor so every background flow completes inline and deterministically.
 */
final class PluginPanelTestHarness {

    private PluginPanelTestHarness() {}

    /** One plugin declared in a fixture marketplace. {@code userConfigJson} nullable. */
    record PluginSpec(String name, String description, String version, String userConfigJson) {

        static PluginSpec of(String name, String description, String version) {
            return new PluginSpec(name, description, version, null);
        }
    }

    static PluginPanelServices services(Path tmp) {
        return services(tmp, List.of(), () -> null);
    }

    static PluginPanelServices services(Path tmp, List<PluginError> errors) {
        return services(tmp, errors, () -> null);
    }

    static PluginPanelServices services(Path tmp, Supplier<Map<String, Long>> installCounts) {
        return services(tmp, List.of(), installCounts);
    }

    static PluginPanelServices services(Path tmp, List<PluginError> errors,
                                        Supplier<Map<String, Long>> installCounts) {
        GitExecutor fakeGit = (_, _) -> new GitExecutor.GitResult(1, "", "git disabled in tests");
        PluginSettingsStore store = new PluginSettingsStore(
            tmp.resolve("user-settings.json"),
            tmp.resolve("project-settings.json"),
            tmp.resolve("local-settings.json"),
            tmp.resolve("policy-settings.json"));
        MarketplaceManager marketplaces = new MarketplaceManager(
            tmp.resolve("plugins-root"), fakeGit, ServiceHttpClient.noRedirects(), store);
        PluginInstaller installer = new PluginInstaller(marketplaces, fakeGit, tmp.toString());
        return new PluginPanelServices(
            new PluginMarketplaceAdapter(
                marketplaces, installer, store, installCounts, () -> errors),
            Runnable::run);
    }





    static Path writeMarketplace(PluginPanelServices services, Path dir, String name,
                                 PluginSpec... plugins) {
        writeMarketplaceFiles(dir, name, plugins);
        var parsed = services.plugins().parseMarketplaceInput(dir.toString());
        services.plugins().addMarketplace(
            (PluginMarketplacePort.ParsedMarketplaceInput.Parsed) parsed, _ -> {});
        return dir;
    }

    /**
     * Writes a reserved-marketplace fixture and registers it directly. This
     * bypasses the public add flow, which reserves the marketplace name for
     * trusted GitHub sources. Overwrites the known-marketplaces file.
     */
    static Path writeOfficialMarketplace(PluginPanelServices services, Path dir,
                                         PluginSpec... plugins) {
        writeMarketplaceFiles(dir, "claude-plugins-official", plugins);
        try {
            Path knownFile = dir.getParent().resolve("plugins-root").resolve("known_marketplaces.json");
            Files.createDirectories(knownFile.getParent());
            Files.writeString(knownFile, """
                {
                  "claude-plugins-official": {
                    "source": {"source": "github", "repo": "anthropics/claude-plugins-official"},
                    "installLocation": %s
                  }
                }""".formatted(JsonUtils.toJson(dir.toString())));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeMarketplaceFiles(Path dir, String name, PluginSpec... plugins) {
        try {
            Path metaDir = dir.resolve(".claude-plugin");
            Files.createDirectories(metaDir);
            String entries = Arrays.stream(plugins)
                .map(p -> """
                    {"name": "%s", "source": "./plugins/%s", "description": "%s", "version": "%s"}"""
                    .formatted(p.name(), p.name(), p.description(), p.version()))
                .collect(Collectors.joining(",\n    "));
            Files.writeString(metaDir.resolve("marketplace.json"), """
                {
                  "name": "%s",
                  "owner": {"name": "Tester"},
                  "plugins": [
                    %s
                  ]
                }""".formatted(name, entries));
            for (PluginSpec plugin : plugins) {
                Path pluginMeta = dir.resolve("plugins").resolve(plugin.name())
                    .resolve(".claude-plugin");
                Files.createDirectories(pluginMeta);
                String userConfig = plugin.userConfigJson() == null
                    ? "" : ",\n  \"userConfig\": " + plugin.userConfigJson();
                Files.writeString(pluginMeta.resolve("plugin.json"), """
                    {
                      "name": "%s",
                      "version": "%s",
                      "description": "%s"%s
                    }""".formatted(plugin.name(), plugin.version(), plugin.description(), userConfig));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void install(PluginPanelServices services, String pluginName, String marketplaceName) {
        services.plugins().install(pluginName, marketplaceName, PluginMarketplacePort.Scope.USER);
    }
}
