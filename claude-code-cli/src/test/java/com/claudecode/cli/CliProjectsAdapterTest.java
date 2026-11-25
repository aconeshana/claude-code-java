package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.state.CwdState;
import com.claudecode.gateway.GatewayProjectsPort;
import com.claudecode.gateway.GatewayServer;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.services.config.SettingsSchema;
import com.claudecode.services.config.SettingsSnapshots;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliProjectsAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void multiFolderProjectPreservesClaudeCodeSettingsSchemaAndCompatibility() throws Exception {
        Path configHome = Files.createDirectories(tempDir.resolve("claude-home"));
        Path primaryDir = Files.createDirectories(tempDir.resolve("primary-app"));
        Path extraDirA = Files.createDirectories(tempDir.resolve("shared-lib"));
        Path extraDirB = Files.createDirectories(tempDir.resolve("infra-repo"));

        // Pre-seed an existing Claude Code settings.local.json and .claude.json
        Path localSettingsFile = primaryDir.resolve(".claude/settings.local.json");
        Files.createDirectories(localSettingsFile.getParent());
        Files.writeString(localSettingsFile, """
            {
              "permissions": {
                "allow": ["Bash(git status:*)"]
              },
              "env": {
                "KEEP_ME": "yes"
              }
            }
            """, StandardCharsets.UTF_8);

        Path claudeJson = configHome.resolve(".claude.json");
        String originalClaudeJson = "{\"numStartups\":42}\n";
        Files.writeString(claudeJson, originalClaudeJson, StandardCharsets.UTF_8);

        CliProjectsAdapter adapter = new CliProjectsAdapter(configHome);

        // 1. Create project with custom name and two additional folders (local tier)
        GatewayProjectsPort.ProjectEntry created = adapter.createOrUpdateProject(
            primaryDir.toString(),
            "My Fullstack App",
            List.of(extraDirA.toString(), extraDirB.toString()),
            "local");

        assertThat(created.path()).isEqualTo(primaryDir.toAbsolutePath().normalize().toString());
        assertThat(created.name()).isEqualTo("My Fullstack App");
        assertThat(created.additionalDirectories()).contains(
            extraDirA.toAbsolutePath().normalize().toString(),
            extraDirB.toAbsolutePath().normalize().toString());

        // 2. Rename project — must ONLY touch web-projects.json, never settings.local.json or .claude.json
        GatewayProjectsPort.ProjectEntry renamed = adapter.renameProject(primaryDir.toString(), "Renamed App");
        assertThat(renamed.name()).isEqualTo("Renamed App");

        // 3. Verify Claude Code compatibility:
        //    - settings.local.json passes SettingsSchema validation with ZERO errors
        //    - existing keys (permissions.allow, env.KEEP_ME) are untouched
        //    - NO web-project custom keys leaked into settings.local.json
        //    - .claude.json is byte-for-byte untouched
        String savedLocalRaw = Files.readString(localSettingsFile, StandardCharsets.UTF_8);
        JsonNode savedLocal = JsonUtils.parseTree(savedLocalRaw);
        var schemaErrors = SettingsSchema.validate(savedLocal);
        assertThat(schemaErrors).isEmpty();
        assertThat(savedLocal.has("name")).isFalse();
        assertThat(savedLocal.has("projects")).isFalse();
        assertThat(savedLocal.path("env").path("KEEP_ME").asText()).isEqualTo("yes");
        assertThat(savedLocal.path("permissions").path("allow").get(0).asText()).isEqualTo("Bash(git status:*)");
        assertThat(savedLocal.path("permissions").path("additionalDirectories")).hasSize(2);
        assertThat(Files.readString(claudeJson, StandardCharsets.UTF_8)).isEqualTo(originalClaudeJson);
        assertThat(Files.isRegularFile(configHome.resolve("web-projects.json"))).isTrue();

        // 4. Verify SettingsSnapshots loads the additional directories cleanly when opened in primaryDir
        Path prevOriginal = CwdState.getOriginalCwd();
        CwdState.setOriginalCwd(primaryDir);
        try {
            ObjectNode effective = SettingsSnapshots.withSources(primaryDir.toString());
            assertThat(effective.path("effective").path("permissions").path("additionalDirectories").toString())
                .contains(extraDirA.getFileName().toString())
                .contains(extraDirB.getFileName().toString());
        } finally {
            CwdState.setOriginalCwd(prevOriginal);
        }

        // 5. Remove project from web registry — disk files and settings.local.json remain intact
        adapter.removeProject(primaryDir.toString());
        assertThat(adapter.listProjects()).isEmpty();
        assertThat(Files.isRegularFile(localSettingsFile)).isTrue();
    }

    @Test
    void projectCustomizationsInspectsFoldersRulesSkillsAndMcp() throws Exception {
        Path configHome = Files.createDirectories(tempDir.resolve("claude-home-cust"));
        Path primaryDir = Files.createDirectories(tempDir.resolve("app-with-cust"));
        Path extraDir = Files.createDirectories(tempDir.resolve("extra-dir"));

        Files.writeString(primaryDir.resolve("CLAUDE.md"), "# Project Rules\nAlways write tests.\n",
            StandardCharsets.UTF_8);
        Path skillFile = primaryDir.resolve(".claude/skills/demo-skill/SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
            ---
            name: demo-skill
            description: Demo project skill
            ---
            Use this skill for testing.
            """, StandardCharsets.UTF_8);
        Files.writeString(primaryDir.resolve(".mcp.json"), """
            {
              "mcpServers": {
                "demo-mcp": {
                  "command": "echo",
                  "args": ["hello"]
                }
              }
            }
            """, StandardCharsets.UTF_8);

        CliProjectsAdapter adapter = new CliProjectsAdapter(configHome);
        adapter.createOrUpdateProject(primaryDir.toString(), "Demo", List.of(extraDir.toString()), "local");

        ObjectNode cust = adapter.projectCustomizations(primaryDir.toString());
        assertThat(cust.path("folders").path("primary").asText())
            .isEqualTo(primaryDir.toAbsolutePath().normalize().toString());
        assertThat(cust.path("folders").path("local_directories").toString())
            .contains(extraDir.getFileName().toString());
        assertThat(cust.path("rules").path("total_tokens").asInt()).isGreaterThan(0);
        assertThat(cust.path("rules").path("files").toString()).contains("CLAUDE.md");
        assertThat(cust.path("skills").toString()).contains("demo-skill");
        assertThat(cust.path("mcp_servers").toString()).contains("demo-mcp");
    }

    @Test
    void gatewayEndpointsSupportPerProjectSettingsAndProjectsCrud() throws Exception {
        Path configHome = Files.createDirectories(tempDir.resolve("claude-home-http"));
        Path daemonCwd = Files.createDirectories(tempDir.resolve("daemon-cwd"));
        Path targetProject = Files.createDirectories(tempDir.resolve("target-project"));
        Path boundFolder = Files.createDirectories(tempDir.resolve("bound-folder"));

        // Simulate a running `claude web` daemon whose CwdState.originalCwd is daemonCwd
        Path previousOriginalCwd = CwdState.getOriginalCwd();
        CwdState.setOriginalCwd(daemonCwd);
        try {
            runGatewayCrudTest(configHome, daemonCwd, targetProject, boundFolder);
        } finally {
            CwdState.setOriginalCwd(previousOriginalCwd);
        }
    }

    private void runGatewayCrudTest(Path configHome, Path daemonCwd, Path targetProject, Path boundFolder)
            throws Exception {

        CliProjectsAdapter projectsAdapter = new CliProjectsAdapter(configHome);
        String token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });
        try (GatewayServer server = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), token, registry)
                .settings(CliInteractiveSessionRunner.gatewaySettings(daemonCwd.toString()))
                .projects(projectsAdapter)
                .build()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            HttpClient client = HttpClient.newHttpClient();

            // POST /api/projects
            String createBody = JsonUtils.toJson(Map.of(
                "path", targetProject.toString(),
                "name", "HTTP Project",
                "additional_directories", List.of(boundFolder.toString()),
                "tier", "local"));
            HttpResponse<String> createResp = client.send(HttpRequest.newBuilder(URI.create(base + "/api/projects"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(createResp.statusCode()).isEqualTo(200);
            assertThat(createResp.body()).contains("HTTP Project");

            // GET /api/projects/customizations?path=<targetProject> reads targetProject's local_directories (NOT daemonCwd)
            String encodedPath = URLEncoder.encode(targetProject.toString(), StandardCharsets.UTF_8);
            HttpResponse<String> custResp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/projects/customizations?path=" + encodedPath))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(custResp.statusCode()).isEqualTo(200);
            assertThat(custResp.body()).contains(boundFolder.getFileName().toString());
            // Ensure targetProject/.claude/settings.local.json WAS written and daemonCwd was NOT polluted
            assertThat(Files.exists(targetProject.resolve(".claude/settings.local.json"))).isTrue();
            assertThat(Files.exists(daemonCwd.resolve(".claude/settings.local.json"))).isFalse();

            // POST /api/settings with cwd removes boundFolder from targetProject
            String removeBody = JsonUtils.toJson(Map.of(
                "op", "removeDirectories",
                "cwd", targetProject.toString(),
                "directories", List.of(boundFolder.toAbsolutePath().normalize().toString()),
                "tier", "local"));
            HttpResponse<String> removeResp = client.send(HttpRequest.newBuilder(URI.create(base + "/api/settings"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(removeBody))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(removeResp.statusCode()).isEqualTo(200);

            // Verify targetProject's settings.local.json is still schema-valid and no longer contains boundFolder
            String afterRemoveRaw = Files.readString(targetProject.resolve(".claude/settings.local.json"), StandardCharsets.UTF_8);
            assertThat(afterRemoveRaw).doesNotContain(boundFolder.getFileName().toString());
            JsonNode afterRemove = JsonUtils.parseTree(afterRemoveRaw);
            assertThat(SettingsSchema.validate(afterRemove)).isEmpty();
        }
    }
}
