package com.claudecode.services.plugins.marketplace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


class PluginErrorTest {

    @Test
    void pathNotFoundMessage() {
        assertEquals("Path not found: ./commands/x.md (commands)",
            new PluginError.PathNotFound("mkt", "p", "./commands/x.md",
                PluginError.Component.COMMANDS).getMessage());
    }

    @Test
    void gitAndNetworkMessages() {
        assertEquals("Git authentication failed (ssh): git@github.com:a/b.git",
            new PluginError.GitAuthFailed("mkt", null, "git@github.com:a/b.git", "ssh").getMessage());
        assertEquals("Git clone timeout: https://x.git",
            new PluginError.GitTimeout("mkt", null, "https://x.git", "clone").getMessage());
        assertEquals("Network error: https://x - refused",
            new PluginError.NetworkError("mkt", null, "https://x", "refused").getMessage());
        assertEquals("Network error: https://x",
            new PluginError.NetworkError("mkt", null, "https://x", null).getMessage());
    }

    @Test
    void manifestAndMarketplaceMessages() {
        assertEquals("Manifest parse error: bad token",
            new PluginError.ManifestParseError("m", null, "/p", "bad token").getMessage());
        assertEquals("Manifest validation failed: a, b",
            new PluginError.ManifestValidationError("m", null, "/p", List.of("a", "b")).getMessage());
        assertEquals("Plugin x@m not found in marketplace m",
            new PluginError.PluginNotFound("m", "x@m", "m").getMessage());
        assertEquals("Marketplace m not found",
            new PluginError.MarketplaceNotFound("s", "m", List.of()).getMessage());
        assertEquals("Marketplace m failed to load: boom",
            new PluginError.MarketplaceLoadFailed("s", "m", "boom").getMessage());
    }

    @Test
    void policyAndDependencyMessages() {
        assertEquals("Marketplace 'm' is blocked by enterprise policy",
            new PluginError.MarketplaceBlockedByPolicy("s", null, "m", true, List.of()).getMessage());
        assertEquals("Marketplace 'm' is not in the allowed marketplace list",
            new PluginError.MarketplaceBlockedByPolicy("s", null, "m", false, List.of()).getMessage());
        assertEquals("Dependency \"d@m\" is disabled — enable it or remove the dependency",
            new PluginError.DependencyUnsatisfied("s", "p", "d@m", "not-enabled").getMessage());
        assertEquals("Dependency \"d@m\" is not found in any configured marketplace",
            new PluginError.DependencyUnsatisfied("s", "p", "d@m", "not-found").getMessage());
    }

    @Test
    void lspAndMcpMessages() {
        assertEquals("MCP server srv invalid: bad env",
            new PluginError.McpConfigInvalid("s", "p", "srv", "bad env").getMessage());
        assertEquals("MCP server \"srv\" skipped — same command/URL as server provided by plugin \"other\"",
            new PluginError.McpServerSuppressedDuplicate("s", "p", "srv", "plugin:other").getMessage());
        assertEquals("MCP server \"srv\" skipped — same command/URL as already-configured \"cfg\"",
            new PluginError.McpServerSuppressedDuplicate("s", "p", "srv", "cfg").getMessage());
        assertEquals("Plugin \"p\" LSP server \"srv\" crashed with signal SIGKILL",
            new PluginError.LspServerCrashed("s", "p", "srv", null, "SIGKILL").getMessage());
        assertEquals("Plugin \"p\" LSP server \"srv\" crashed with exit code 1",
            new PluginError.LspServerCrashed("s", "p", "srv", 1, null).getMessage());
        assertEquals("Plugin \"p\" LSP server \"srv\" crashed with exit code unknown",
            new PluginError.LspServerCrashed("s", "p", "srv", null, null).getMessage());
        assertEquals("Plugin \"p\" LSP server \"srv\" timed out on init request after 500ms",
            new PluginError.LspRequestTimeout("s", "p", "srv", "init", 500).getMessage());
    }

    @Test
    void cacheMissAndGenericMessages() {
        assertEquals("Plugin \"p\" not cached at /x — run /plugins to refresh",
            new PluginError.PluginCacheMiss("s", "p", "/x").getMessage());
        assertEquals("anything", new PluginError.GenericError("s", null, "anything").getMessage());
    }
}
