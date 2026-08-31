package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class StoresRoundTripTest {

    @TempDir
    Path tempDir;



    @Test
    void knownMarketplacesRoundTrip() {
        KnownMarketplacesStore store = new KnownMarketplacesStore(
            tempDir.resolve("known_marketplaces.json"));
        KnownMarketplaces config = KnownMarketplaces.empty()
            .with("gh-mkt", new KnownMarketplaces.Entry(
                new MarketplaceSource.Github("owner/repo", "main", null, null),
                "/cache/marketplaces/gh-mkt", "2026-01-01T00:00:00Z", true))
            .with("local-mkt", new KnownMarketplaces.Entry(
                new MarketplaceSource.Directory("/home/me/mkt"),
                "/home/me/mkt", "2026-01-02T00:00:00Z", null));

        store.save(config);
        KnownMarketplaces loaded = store.load();

        assertEquals(config, loaded);
        assertEquals("owner/repo",
            ((MarketplaceSource.Github) loaded.get("gh-mkt").source()).repo());
        assertTrue(loaded.get("gh-mkt").autoUpdate());
    }

    @Test
    void missingKnownMarketplacesFileLoadsEmpty() {
        KnownMarketplacesStore store = new KnownMarketplacesStore(tempDir.resolve("absent.json"));
        assertEquals(KnownMarketplaces.empty(), store.load());
    }

    @Test
    void corruptedKnownMarketplacesThrowsButLoadSafeDegrades() throws Exception {
        Path file = tempDir.resolve("known_marketplaces.json");
        Files.writeString(file, "{not valid json");
        KnownMarketplacesStore store = new KnownMarketplacesStore(file);

        PluginOperationException error = assertThrows(PluginOperationException.class, store::load);
        assertTrue(Strings.CS.startsWith(error.getMessage(), "Failed to load marketplace configuration:"));
        assertEquals(KnownMarketplaces.empty(), store.loadSafe());
    }



    @Test
    void installedPluginsV2RoundTripWithMultipleScopes() {
        InstalledPluginsStore store = new InstalledPluginsStore(
            tempDir.resolve("installed_plugins.json"));
        InstalledPlugins data = InstalledPlugins.empty()
            .withInstallation("fmt@tools", new InstalledPlugins.InstallationEntry(
                PluginScope.USER, null, "/cache/tools/fmt/1.0.0", "1.0.0",
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null))
            .withInstallation("fmt@tools", new InstalledPlugins.InstallationEntry(
                PluginScope.PROJECT, "/path/to/project", "/cache/tools/fmt/1.1.0", "1.1.0",
                "2026-01-02T00:00:00Z", "2026-01-02T00:00:00Z", "a".repeat(40)));

        store.save(data);
        InstalledPlugins loaded = store.load();

        assertEquals(2, loaded.version());
        assertEquals(data, loaded);
        assertEquals(2, loaded.installationsOf("fmt@tools").size());
        assertEquals(PluginScope.PROJECT, loaded.installationsOf("fmt@tools").get(1).scope());
        assertEquals("/path/to/project", loaded.installationsOf("fmt@tools").get(1).projectPath());
    }

    @Test
    void missingOrCorruptInstalledPluginsLoadsEmpty() throws Exception {
        InstalledPluginsStore missing = new InstalledPluginsStore(tempDir.resolve("absent.json"));
        assertEquals(InstalledPlugins.empty(), missing.load());

        Path corrupt = tempDir.resolve("installed_plugins.json");
        Files.writeString(corrupt, "not json at all");
        assertEquals(InstalledPlugins.empty(), new InstalledPluginsStore(corrupt).load());
    }

    @Test
    void upsertReplacesSameScopeEntry() {
        InstalledPlugins data = InstalledPlugins.empty()
            .withInstallation("p@m", entry(PluginScope.USER, "1.0.0"))
            .withInstallation("p@m", entry(PluginScope.USER, "2.0.0"));
        assertEquals(1, data.installationsOf("p@m").size());
        assertEquals("2.0.0", data.installationsOf("p@m").getFirst().version());
    }

    @Test
    void removingLastInstallationDropsPluginKey() {
        InstalledPlugins data = InstalledPlugins.empty()
            .withInstallation("p@m", entry(PluginScope.USER, "1.0.0"))
            .withoutInstallation("p@m", PluginScope.USER, null);
        assertFalse(data.plugins().containsKey("p@m"));
    }

    @Test
    void withoutMarketplaceRemovesOnlyMatchingSuffix() {
        InstalledPlugins data = InstalledPlugins.empty()
            .withInstallation("a@mkt", entry(PluginScope.USER, "1"))
            .withInstallation("b@mkt", entry(PluginScope.USER, "1"))
            .withInstallation("c@other", entry(PluginScope.USER, "1"))
            .withoutMarketplace("mkt");
        assertEquals(Map.of("c@other", List.of(entry(PluginScope.USER, "1"))).keySet(),
            data.plugins().keySet());
    }

    private static InstalledPlugins.InstallationEntry entry(PluginScope scope, String version) {
        return new InstalledPlugins.InstallationEntry(
            scope, null, "/cache/x", version, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null);
    }
}
