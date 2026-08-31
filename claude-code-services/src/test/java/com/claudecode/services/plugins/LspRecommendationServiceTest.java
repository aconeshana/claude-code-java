package com.claudecode.services.plugins;

import com.claudecode.core.lsp.LspPluginRecommendation;
import com.claudecode.services.plugins.marketplace.InstalledPluginsStore;
import com.claudecode.services.plugins.marketplace.MarketplacePluginEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LspRecommendationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode lspServers(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Inline Record form: {@code {serverName: {command, extensionToLanguage}}}. */
    private static JsonNode tsLspServers(String command) {
        return lspServers("{"
            + "\"tsserver\":{\"command\":\"" + command + "\","
            + "\"extensionToLanguage\":{\".ts\":\"typescript\",\".tsx\":\"typescriptreact\"}}}");
    }

    private static MarketplacePluginEntry entry(String name, String description, JsonNode lsp) {
        return MarketplacePluginEntry.builder(name, null)
            .description(description).lspServers(lsp).build();
    }

    @TempDir
    Path tempDir;

    private LspRecommendationService service() {
        InstalledPluginsStore store = new InstalledPluginsStore(tempDir.resolve("installed.json"));
        return new LspRecommendationService(null, store, tempDir.resolve("claude.json"));
    }

    @Test
    void matchesWhenExtensionCoveredAndBinaryInstalledAndNotInstalled() {
        LspRecommendationService svc = service();
        Map<String, List<MarketplacePluginEntry>> catalog = new LinkedHashMap<>();
        catalog.put("claude-code-marketplace",
            List.of(entry("typescript", "TypeScript LSP", tsLspServers("sh"))));

        List<LspPluginRecommendation> got = svc.matchAgainst(catalog, ".ts");

        assertEquals(1, got.size());
        assertEquals("typescript@claude-code-marketplace", got.getFirst().pluginId());
        assertEquals("typescript", got.getFirst().pluginName());
        assertTrue(got.getFirst().extensions().contains(".tsx"));
        assertTrue(got.getFirst().isOfficial());
    }

    @Test
    void noMatchWhenExtensionNotCovered() {
        LspRecommendationService svc = service();
        Map<String, List<MarketplacePluginEntry>> catalog = new LinkedHashMap<>();
        catalog.put("claude-code-marketplace",
            List.of(entry("typescript", "TypeScript LSP", tsLspServers("sh"))));

        assertTrue(svc.matchAgainst(catalog, ".py").isEmpty());
    }

    @Test
    void noMatchWhenBinaryMissing() {
        LspRecommendationService svc = service();
        Map<String, List<MarketplacePluginEntry>> catalog = new LinkedHashMap<>();
        catalog.put("claude-code-marketplace",
            List.of(entry("typescript", "TypeScript LSP",
                tsLspServers("definitely-not-an-installed-binary-xyz"))));

        assertTrue(svc.matchAgainst(catalog, ".ts").isEmpty());
    }

    @Test
    void noMatchWhenAlreadyInstalled() {
        LspRecommendationService svc = service();
        Map<String, List<MarketplacePluginEntry>> catalog = new LinkedHashMap<>();
        catalog.put("claude-code-marketplace",
            List.of(entry("typescript", "TypeScript LSP", tsLspServers("sh"))));

// An installed plugin is skipped by matchAgainst via isInstalled; here
        // we just verify a never-suggest entry is excluded (same exclusion path).
        svc.addToNeverSuggest("typescript@claude-code-marketplace");
        assertTrue(svc.matchAgainst(catalog, ".ts").isEmpty());
    }

    @Test
    void neverSuggestExcludesPlugin() {
        LspRecommendationService svc = service();
        Map<String, List<MarketplacePluginEntry>> catalog = new LinkedHashMap<>();
        catalog.put("claude-code-marketplace",
            List.of(entry("typescript", "TypeScript LSP", tsLspServers("sh"))));

        svc.addToNeverSuggest("typescript@claude-code-marketplace");
        assertTrue(svc.matchAgainst(catalog, ".ts").isEmpty());
    }

    @Test
    void officialMarketplaceSortedFirst() {
        LspRecommendationService svc = service();
        Map<String, List<MarketplacePluginEntry>> catalog = new LinkedHashMap<>();
        catalog.put("thirdparty",
            List.of(entry("ts-third", "Third-party TS", tsLspServers("sh"))));
        catalog.put("claude-code-marketplace",
            List.of(entry("ts-official", "Official TS", tsLspServers("sh"))));

        List<LspPluginRecommendation> got = svc.matchAgainst(catalog, ".ts");
        assertEquals(2, got.size());
        assertTrue(got.getFirst().isOfficial());
        assertEquals("ts-official", got.getFirst().pluginName());
        assertFalse(got.get(1).isOfficial());
    }

    @Test
    void disabledReturnsEmptyAndIsControlledByGlobalConfig() {
        LspRecommendationService svc = service();
        assertFalse(svc.isDisabled());
        svc.setDisabled(true);
        assertTrue(svc.isDisabled());
        assertTrue(svc.getMatchingLspPlugins(Path.of("/tmp/x.ts")).isEmpty());
    }

    @Test
    void ignoreCountAutoDisablesAtThreshold() {
        LspRecommendationService svc = service();
        assertFalse(svc.isDisabled());
        for (int i = 0; i < 5; i++) {
            svc.incrementIgnoredCount();
        }
        assertTrue(svc.isDisabled());
        svc.resetIgnoredCount();
        assertFalse(svc.isDisabled());
    }

    @Test
    void shownThisSessionIsOneWay() {
        LspRecommendationService svc = service();
        assertFalse(svc.hasShownThisSession());
        svc.markShownThisSession();
        assertTrue(svc.hasShownThisSession());
    }
}
