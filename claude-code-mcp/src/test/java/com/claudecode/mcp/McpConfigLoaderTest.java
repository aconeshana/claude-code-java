package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.git.GitUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpConfigLoader}.
 */
class McpConfigLoaderTest {

    @TempDir
    Path tempDir;

    /**
     * The merged {@link McpConfig#scopes} map records only the winning scope
     * (local overrides project overrides user), so it cannot answer "which
     * scopes define this name?". {@code claude mcp remove} needs that answer to
     * avoid silently deleting one of several same-named registrations.
     */
    @Test
    void loadScope_isolatesEachScopeForSameServerName() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("repo/subdir"));
        Path global = tempDir.resolve(".claude.json");
        Path canonical = GitUtils.findCanonicalGitRoot(project);
        String projectKey = (canonical != null
            ? canonical.toString() : project.toAbsolutePath().normalize().toString())
            .replace('\\', '/');
        Files.writeString(global, """
            {
              "mcpServers": {"shared": {"command": "from-user"}},
              "projects": {
                "%s": {
                  "mcpServers": {"shared": {"command": "from-local"}}
                }
              }
            }
            """.formatted(projectKey.replace("\\", "\\\\").replace("\"", "\\\"")));
        Files.writeString(tempDir.resolve("repo/.mcp.json"), """
            {"mcpServers": {"shared": {"command": "from-project"}}}
            """);

        // Merged view keeps only the last writer, which is why it must not be
        // used to pick a removal target.
        McpConfig merged = McpConfigLoader.loadConfig(project, global);
        assertEquals(McpServerScope.LOCAL, merged.scopes().get("shared"));

        // Per-scope reads see all three independently.
        assertEquals("from-user",
            McpConfigLoader.loadScope(project, global, McpServerScope.USER)
                .get("shared").command());
        assertEquals("from-project",
            McpConfigLoader.loadScope(project, global, McpServerScope.PROJECT)
                .get("shared").command());
        assertEquals("from-local",
            McpConfigLoader.loadScope(project, global, McpServerScope.LOCAL)
                .get("shared").command());
    }

    @Test
    void loadScope_returnsEmptyForNonFileBackedScopes() {
        Path global = tempDir.resolve(".claude.json");
        assertTrue(McpConfigLoader.loadScope(tempDir, global, McpServerScope.ENTERPRISE).isEmpty());
        assertTrue(McpConfigLoader.loadScope(tempDir, global, McpServerScope.DYNAMIC).isEmpty());
    }

    @Test
    void loadScope_omitsNamesDefinedOnlyInOtherScopes() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path global = tempDir.resolve(".claude.json");
        Files.writeString(global, """
            {"mcpServers": {"only-user": {"command": "u"}}}
            """);
        Files.writeString(project.resolve(".mcp.json"), """
            {"mcpServers": {"only-project": {"command": "p"}}}
            """);

        Map<String, McpServerConfig> userScope =
            McpConfigLoader.loadScope(project, global, McpServerScope.USER);
        Map<String, McpServerConfig> projectScope =
            McpConfigLoader.loadScope(project, global, McpServerScope.PROJECT);

        assertTrue(userScope.containsKey("only-user"));
        assertFalse(userScope.containsKey("only-project"));
        assertTrue(projectScope.containsKey("only-project"));
        assertFalse(projectScope.containsKey("only-user"));
    }

    @Test
    void loadsOfficialUserProjectAndLocalScopes() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("repo/subdir"));
        Path global = tempDir.resolve(".claude.json");
        String projectKey = GitUtils.findCanonicalGitRoot(project) != null
            ? GitUtils.findCanonicalGitRoot(project).toString().replace('\\', '/')
            : project.toAbsolutePath().normalize().toString().replace('\\', '/');
        Files.writeString(global, """
            {
              "mcpServers": {"user-srv": {"command": "user"}},
              "projects": {
                "%s": {
                  "mcpServers": {"local-srv": {"command": "local"}},
                  "disabledMcpServers": ["user-srv", "project-srv"]
                }
              }
            }
            """.formatted(projectKey.replace("\\", "\\\\").replace("\"", "\\\"")));
        Files.writeString(tempDir.resolve("repo/.mcp.json"), """
            {"mcpServers": {"project-srv": {"command": "project"}}}
            """);

        McpConfig config = McpConfigLoader.loadConfig(project, global);

        assertEquals(Set.of("user-srv", "project-srv", "local-srv"), config.servers().keySet());
        assertEquals(McpServerScope.USER, config.scopes().get("user-srv"));
        assertEquals(McpServerScope.PROJECT, config.scopes().get("project-srv"));
        assertEquals(McpServerScope.LOCAL, config.scopes().get("local-srv"));
        assertTrue(config.servers().get("user-srv").disabled());
        assertTrue(config.servers().get("project-srv").disabled());
        assertFalse(config.servers().get("local-srv").disabled());
    }

    @Test
    void loadConfigFromEmptyDir() {
        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        assertNotNull(config);
        assertTrue(config.servers().isEmpty());
    }

    @Test
    void explicitMcpConfigOverridesNormalFilesAndStrictDropsThem() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"),
            "{\"mcpServers\":{\"shared\":{\"command\":\"project\"},\"fileOnly\":{\"command\":\"file\"}}}");

        McpConfig merged = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"),
            List.of("{\"mcpServers\":{\"shared\":{\"command\":\"inline\"},\"inlineOnly\":{\"command\":\"inline-only\"}}}"),
            false);
        assertEquals(Set.of("shared", "fileOnly", "inlineOnly"), merged.servers().keySet());
        assertEquals("inline", merged.servers().get("shared").command());
        assertEquals(McpServerScope.DYNAMIC, merged.scopeOf("shared"));

        McpConfig strict = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"),
            List.of("{\"mcpServers\":{\"only\":{\"command\":\"strict\"}}}"), true);
        assertEquals(Set.of("only"), strict.servers().keySet());
        assertEquals("strict", strict.servers().get("only").command());
    }

    @Test
    void settingSourceGateSkipsDisabledMcpScopesBeforeMerge() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("repo"));
        Path global = tempDir.resolve(".claude.json");
        String projectKey = project.toAbsolutePath().normalize().toString()
            .replace('\\', '/');
        Files.writeString(global, """
            {
              "mcpServers": {"shared": {"command": "user"}},
              "projects": {
                "%s": {"mcpServers": {"shared": {"command": "local"}}}
              }
            }
            """.formatted(projectKey.replace("\\", "\\\\").replace("\"", "\\\"")));
        Files.writeString(project.resolve(".mcp.json"),
            "{\"mcpServers\":{\"shared\":{\"command\":\"project\"}}}");

        McpConfig userOnly = McpConfigLoader.loadConfig(project, global,
            Set.of(McpServerScope.USER));
        assertEquals("user", userOnly.servers().get("shared").command());
        assertEquals(McpServerScope.USER, userOnly.scopeOf("shared"));

        McpConfig projectOnly = McpConfigLoader.loadConfig(project, global,
            Set.of(McpServerScope.PROJECT));
        assertEquals("project", projectOnly.servers().get("shared").command());
        assertEquals(McpServerScope.PROJECT, projectOnly.scopeOf("shared"));

        McpConfig localOnly = McpConfigLoader.loadConfig(project, global,
            Set.of(McpServerScope.LOCAL));
        assertEquals("local", localOnly.servers().get("shared").command());
        assertEquals(McpServerScope.LOCAL, localOnly.scopeOf("shared"));
    }

    @Test
    void loadConfigFromWorkspaceLevel() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {
              "mcpServers": {
                "my-server": {
                  "command": "node",
                  "args": ["server.js"],
                  "env": {"PORT": "3000"},
                  "type": "stdio"
                }
              }
            }
            """);

        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        assertEquals(1, config.servers().size());

        McpServerConfig server = config.servers().get("my-server");
        assertNotNull(server);
        assertEquals("my-server", server.name());
        assertEquals("node", server.command());
        assertEquals(1, server.args().size());
        assertEquals("server.js", server.args().getFirst());
        assertEquals("3000", server.env().get("PORT"));
        assertEquals("stdio", server.transportType());
        assertFalse(server.disabled());
    }

    @Test
    void inlineDisabledFieldIsNotTheOfficialEnablementStore() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {"mcpServers": {"server": {"command": "python", "disabled": true}}}
            """);

        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        assertFalse(config.servers().get("server").disabled());
    }

    @Test
    void mergeFromOverridesExistingEntries() {
        Map<String, McpServerConfig> target = new LinkedHashMap<>();
        target.put("srv", new McpServerConfig("srv", "old-cmd", null, null, false, "stdio"));

        // Create a config file that overrides "srv"
        try {
            Path claudeDir = tempDir.resolve(".claude");
            Files.createDirectories(claudeDir);
            Path configPath = tempDir.resolve(".mcp.json");
            Files.writeString(configPath, """
                {
                  "mcpServers": {
                    "srv": {
                      "command": "new-cmd"
                    }
                  }
                }
                """);

            Map<String, McpServerScope> scopes = new LinkedHashMap<>();
            McpConfigLoader.mergeFrom(configPath, target, scopes, McpServerScope.PROJECT,
                new ArrayList<>(), new ArrayList<>());

            assertEquals("new-cmd", target.get("srv").command());
            assertEquals(McpServerScope.PROJECT, scopes.get("srv"));
        } catch (IOException e) {
            fail("Unexpected IOException", e);
        }
    }

    @Test
    void parseServerConfigDefaults() {
        // Test that missing fields get sensible defaults
        var mapper = new ObjectMapper();
        var node = mapper.createObjectNode();
        node.put("command", "test-cmd");

        McpServerConfig config = McpConfigLoader.parseServerConfig("test", node);
        assertEquals("test", config.name());
        assertEquals("test-cmd", config.command());
        assertTrue(config.args().isEmpty());
        assertTrue(config.env().isEmpty());
        assertFalse(config.disabled());
        assertEquals("stdio", config.transportType());
    }

    @Test
    void loadConfigWithSseTransportType() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {
              "mcpServers": {
                "sse-srv": {
                  "command": "http://localhost:8080",
                  "type": "sse"
                }
              }
            }
            """);

        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        assertEquals("sse", config.servers().get("sse-srv").transportType());
    }

    @Test
    void loadConfigNullProjectDir() {
        // Should not throw, just skip workspace-level
        McpConfig config = McpConfigLoader.loadConfig(null, tempDir.resolve("global.json"));
        assertNotNull(config);
    }

    @Test
    void loadConfigWithFlatFormat() throws IOException {

        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {
              "flat-srv": {
                "command": "flat-cmd"
              }
            }
            """);

        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        McpServerConfig server = config.servers().get("flat-srv");
        assertNotNull(server);
        assertEquals("flat-cmd", server.command());
    }

    @Test
    void loadConfigParsesUrlAndHeaders() throws IOException {
        // M1b-α: remote transports (sse / http) carry url + headers instead of
        // command + args. Verify both fields survive round-trip through the loader.
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {
              "mcpServers": {
                "github-http": {
                  "type": "http",
                  "url": "https://api.githubcopilot.com/mcp/",
                  "headers": {
                    "Authorization": "Bearer ghp_test",
                    "X-Extra": "value"
                  }
                }
              }
            }
            """);

        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        McpServerConfig server = config.servers().get("github-http");
        assertNotNull(server);
        assertEquals("http", server.transportType());
        assertEquals("https://api.githubcopilot.com/mcp/", server.url());
        assertEquals("Bearer ghp_test", server.headers().get("Authorization"));
        assertEquals("value", server.headers().get("X-Extra"));
        assertEquals(2, server.headers().size());
    }

    @Test
    void loadConfigStdioServerHasNullUrlAndEmptyHeaders() throws IOException {
        // Stdio servers must not accidentally acquire a URL from missing field defaults.
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {
              "mcpServers": {
                "local-stdio": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "."]
                }
              }
            }
            """);

        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        McpServerConfig server = config.servers().get("local-stdio");
        assertNull(server.url());
        assertTrue(server.headers().isEmpty());
    }

    // ── parsing warnings (M4-C) ──────────────────────────────────────────────

    @Test
    void loadConfig_httpServerMissingUrl_emitsParseWarning() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {
              "mcpServers": {
                "bad-http": { "type": "http" }
              }
            }
            """);

        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        // Server still registers (user can fix via /mcp), but a warning is
        // surfaced so they can see why it won't connect.
        assertNotNull(config.servers().get("bad-http"));
        assertFalse(config.warnings().isEmpty(),
            "http server without url must produce a parse warning");
        assertTrue(Strings.CS.contains(config.warnings().getFirst(), "bad-http"),
            "warning must name the offending server");
        assertTrue(Strings.CI.contains(config.warnings().getFirst(), "url"),
            "warning must explain the missing url");
    }

    @Test
    void loadConfig_stdioServerMissingCommand_emitsParseWarning() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {"mcpServers": {"bad-stdio": {"type": "stdio"}}}
            """);
        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        assertTrue(config.warnings().stream().anyMatch(w -> Strings.CS.contains(w, "command")),
            "stdio server without command must produce a parse warning");
    }

    @Test
    void loadConfig_unknownTransport_emitsParseWarning() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {"mcpServers": {"weird": {"type": "grpc", "url": "http://x"}}}
            """);
        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        assertTrue(config.warnings().stream().anyMatch(w -> Strings.CS.contains(w, "grpc")),
            "unknown transport type must be flagged with the actual value");
    }

    @Test
    void loadConfig_serverEntryNotObject_emitsParseWarning() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {"mcpServers": {"weird": "just-a-string"}}
            """);
        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        assertNull(config.servers().get("weird"),
            "non-object server entries must be skipped, not registered as garbage");
        assertTrue(config.warnings().stream().anyMatch(w -> Strings.CS.contains(w, "weird")));
    }

    @Test
    void loadConfig_cleanConfig_producesNoWarnings() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {"mcpServers": {"good": {"type": "stdio", "command": "npx", "args": ["-y", "x"]}}}
            """);
        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));
        assertTrue(config.warnings().isEmpty(),
            "well-formed config must not produce spurious warnings");
        assertTrue(config.diagnostics().isEmpty(),
            "well-formed config must not produce structured diagnostics either");
    }

    // ── structured diagnostics (consumed by /doctor) ────────────────────────

    @Test
    void loadConfig_structuredDiagnostics_carryScopeSeverityAndServerName() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {"mcpServers": {"bad-http": {"type": "http"}}}
            """);
        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));

        // Same cardinality as the flat list — the two are populated together.
        assertEquals(config.warnings().size(), config.diagnostics().size());
        McpConfigWarning d = config.diagnostics().getFirst();
        assertEquals(McpServerScope.PROJECT, d.scope());
        assertEquals(McpConfigWarning.Severity.WARNING, d.severity());
        assertEquals("bad-http", d.serverName());
        assertTrue(Strings.CI.contains(d.message(), "url"));
    }

    @Test
    void loadConfig_topLevelNotMcpServersMap_isFatalDiagnostic() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(tempDir.resolve(".mcp.json"), """
            {"mcpServers": ["not", "a", "map"]}
            """);
        McpConfig config = McpConfigLoader.loadConfig(tempDir, tempDir.resolve("global.json"));

        assertFalse(config.diagnostics().isEmpty());
        McpConfigWarning d = config.diagnostics().getFirst();
        assertEquals(McpConfigWarning.Severity.FATAL, d.severity());
        assertEquals(McpServerScope.PROJECT, d.scope());
        assertNull(d.serverName());
    }

    @Test
    void describeConfigPath_reportsPerScopePaths() {
        assertTrue(Strings.CS.endsWith(McpConfigLoader.describeConfigPath(McpServerScope.PROJECT, tempDir), ".mcp.json"));
        assertTrue(Strings.CS.contains(McpConfigLoader.describeConfigPath(McpServerScope.LOCAL, tempDir), ".claude.json [project:"));
        assertTrue(Strings.CS.endsWith(McpConfigLoader.describeConfigPath(McpServerScope.USER, tempDir), ".claude.json"));
    }
}
