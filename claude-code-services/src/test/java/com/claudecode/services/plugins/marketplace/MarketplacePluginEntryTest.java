package com.claudecode.services.plugins.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link MarketplacePluginEntry}'s {@code lspServers} arm — the inline {@code
 * Record<serverName, LspServerConfig>} (or array) form is captured as a {@link JsonNode} and passed
 * through to {@link PluginManifest#toFallbackManifest}, while a string path (e.g.
 */
class MarketplacePluginEntryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode lspServers(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesInlineRecordLspServers() throws Exception {
        String json = "{"
            + "\"name\":\"typescript\",\"description\":\"TS\","
            + "\"lspServers\":{\"tsserver\":{\"command\":\"typescript-language-server\","
            + "\"extensionToLanguage\":{\".ts\":\"typescript\",\".tsx\":\"typescriptreact\"}}}}";
        MarketplacePluginEntry e = MAPPER.readValue(json, MarketplacePluginEntry.class);
        JsonNode lsp = e.lspServers();
        assertNotNull(lsp);
        assertTrue(lsp.has("tsserver"));
        assertEquals("typescript-language-server", lsp.get("tsserver").get("command").asText());
        Iterator<String> exts = lsp.get("tsserver").get("extensionToLanguage").fieldNames();
        assertTrue(exts.hasNext());
        assertEquals(".ts", exts.next());
    }

    @Test
    void keepsStringPathLspServersVerbatim() throws Exception {
        String json = "{\"name\":\"typescript\",\"lspServers\":\"./.lsp.json\"}";
        MarketplacePluginEntry e = MAPPER.readValue(json, MarketplacePluginEntry.class);
        assertTrue(e.lspServers().isTextual());
        assertEquals("./.lsp.json", e.lspServers().asText());
    }

    @Test
    void fallbackManifestPassesLspServersThrough() {
        JsonNode lsp = lspServers("{\"srv\":{\"command\":\"tsserver\"}}");
        MarketplacePluginEntry e = MarketplacePluginEntry.builder("typescript", null)
            .description("TS").lspServers(lsp).build();
        assertSame(lsp, e.toFallbackManifest().lspServers());
    }

    @Test
    void fallbackManifestRetainsAssistantModeChannels() throws Exception {
        MarketplacePluginEntry entry = MAPPER.readValue("""
            {"name":"chat","channels":[{"server":"telegram","displayName":"Telegram",
              "userConfig":{"token":{"type":"string","title":"Token",
                "description":"Bot token","required":true,"sensitive":true}}}]}
            """, MarketplacePluginEntry.class);

        PluginChannel channel = entry.toFallbackManifest().channels().getFirst();
        assertEquals("telegram", channel.server());
        assertEquals("Telegram", channel.displayName());
        assertEquals(Boolean.TRUE, channel.userConfig().get("token").sensitive());
    }
}
