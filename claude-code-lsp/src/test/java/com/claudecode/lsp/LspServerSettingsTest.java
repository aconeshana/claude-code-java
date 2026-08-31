package com.claudecode.lsp;

import org.junit.jupiter.api.Test;
import java.util.List;

import com.claudecode.core.serialization.JsonUtils;

import static org.junit.jupiter.api.Assertions.*;

class LspServerSettingsTest {

    @Test
    void fromNode_parsesPluginSchemaFields() throws Exception {
        String json = """
            {
              "command": "typescript-language-server",
              "args": ["--stdio"],
              "extensionToLanguage": {".ts": "typescript", ".tsx": "typescriptreact"},
              "env": {"NODE_ENV": "production"},
              "initializationOptions": {"hostInfo": "claude"},
              "settings": {"typescript": {"preferences": {"includeInlayHints": true}}},
              "workspaceFolder": "/ws",
              "transport": "stdio",
              "timeoutMs": 5000,
              "restart": true
            }""";
        LspServerSettings ts = LspServerSettings.fromNode(JsonUtils.getMapper().readTree(json));
        assertEquals("typescript-language-server", ts.command());
        assertEquals(List.of("--stdio"), ts.args());
        assertEquals("typescript", ts.extensionToLanguage().get(".ts"));
        assertEquals("typescriptreact", ts.extensionToLanguage().get(".tsx"));
        assertEquals("production", ts.env().get("NODE_ENV"));
        assertNotNull(ts.initializationOptions());
        assertEquals("claude", ts.initializationOptions().get("hostInfo").asText());
        assertNotNull(ts.settings());
        assertTrue(ts.settings().has("typescript"));
        assertEquals("/ws", ts.workspaceFolder());
        assertEquals("stdio", ts.transport());
        assertEquals(5000L, ts.timeoutMs());
        assertTrue(ts.restart());
        assertTrue(ts.enabled());
    }

    @Test
    void fromNode_defaultsPluginOnlyFieldsWhenAbsent() throws Exception {
        LspServerSettings s = LspServerSettings.fromNode(JsonUtils.getMapper().readTree(
            "{\"command\": \"x\", \"extensionToLanguage\": {\".ts\": \"typescript\"}}"));
        assertNull(s.initializationOptions());
        assertNull(s.settings());
        assertNull(s.workspaceFolder());
        assertEquals("stdio", s.transport());
        assertNull(s.timeoutMs());
        assertFalse(s.restart());
    }
}
