package com.claudecode.mcp;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-path tests for scoped MCP persistence and project-level enablement in
 * {@code disabledMcpServers}.
 */
class McpConfigWriterTest {

    @TempDir Path fakeHome;
    @TempDir Path projectDir;

    private Path globalConfig;

    @BeforeEach
    void setup() {
        globalConfig = fakeHome.resolve(".claude.json");
        McpConfigLoader.configureEnabledFileScopes(
            Set.of(McpServerScope.USER, McpServerScope.PROJECT, McpServerScope.LOCAL));
    }

    @Test
    void pathForScope_routesOfficialFiles() {
        assertEquals(ClaudePaths.GLOBAL_JSON,
            McpConfigWriter.pathForScope(McpServerScope.USER, projectDir));
        assertEquals(projectDir.resolve(".mcp.json"),
            McpConfigWriter.pathForScope(McpServerScope.PROJECT, projectDir));
        assertEquals(ClaudePaths.GLOBAL_JSON,
            McpConfigWriter.pathForScope(McpServerScope.LOCAL, projectDir));
        assertNull(McpConfigWriter.pathForScope(McpServerScope.ENTERPRISE, projectDir));
        assertNull(McpConfigWriter.pathForScope(McpServerScope.DYNAMIC, projectDir));
        assertNull(McpConfigWriter.pathForScope(McpServerScope.PROJECT, null));
        assertEquals(ClaudePaths.GLOBAL_JSON,
            McpConfigWriter.pathForScope(McpServerScope.LOCAL, null));
    }

    @Test
    void addServer_user_writesGlobalMcpServers() throws IOException {
        Files.writeString(globalConfig, "{\"theme\":\"dark\"}");

        McpConfigWriter.addServer(McpServerScope.USER, projectDir,
            stdio("fs", "npx"), globalConfig);

        JsonNode root = read(globalConfig);
        assertEquals("dark", root.path("theme").asText());
        assertEquals("npx", root.path("mcpServers").path("fs").path("command").asText());
    }

    @Test
    void addServer_project_writesCwdDotMcpJson() throws IOException {
        McpConfigWriter.addServer(McpServerScope.PROJECT, projectDir,
            http("github", "https://example.test/mcp"), globalConfig);

        Path projectConfig = projectDir.resolve(".mcp.json");
        assertTrue(Files.isRegularFile(projectConfig));
        assertEquals("https://example.test/mcp",
            read(projectConfig).path("mcpServers").path("github").path("url").asText());
        assertFalse(Files.exists(projectDir.resolve(".claude/mcp.json")));
    }

    @Test
    void addServer_local_writesCurrentProjectEntryInGlobalConfig() throws IOException {
        McpConfigWriter.addServer(McpServerScope.LOCAL, projectDir,
            stdio("local", "node"), globalConfig);

        JsonNode project = currentProject(read(globalConfig));
        assertEquals("node", project.path("mcpServers").path("local").path("command").asText());
    }

    @Test
    void addServer_duplicateInTargetScope_throws() throws IOException {
        McpConfigWriter.addServer(McpServerScope.LOCAL, projectDir,
            stdio("same", "old"), globalConfig);

        assertThrows(IllegalArgumentException.class,
            () -> McpConfigWriter.addServer(McpServerScope.LOCAL, projectDir,
                stdio("same", "new"), globalConfig));
    }

    @Test
    void addServer_invalidName_doesNotTouchDisk() {
        assertThrows(IllegalArgumentException.class,
            () -> McpConfigWriter.addServer(McpServerScope.USER, projectDir,
                stdio("my server", "cmd"), globalConfig));
        assertFalse(Files.exists(globalConfig));
    }

    @Test
    void removeServer_removesFromEachOfficialScope() throws IOException {
        McpConfigWriter.addServer(McpServerScope.USER, projectDir, stdio("user", "u"), globalConfig);
        McpConfigWriter.addServer(McpServerScope.PROJECT, projectDir, stdio("project", "p"), globalConfig);
        McpConfigWriter.addServer(McpServerScope.LOCAL, projectDir, stdio("local", "l"), globalConfig);

        assertTrue(McpConfigWriter.removeServer(McpServerScope.USER, projectDir, "user", globalConfig));
        assertTrue(McpConfigWriter.removeServer(McpServerScope.PROJECT, projectDir, "project", globalConfig));
        assertTrue(McpConfigWriter.removeServer(McpServerScope.LOCAL, projectDir, "local", globalConfig));

        assertFalse(read(globalConfig).path("mcpServers").has("user"));
        assertFalse(read(projectDir.resolve(".mcp.json")).path("mcpServers").has("project"));
        assertFalse(currentProject(read(globalConfig)).path("mcpServers").has("local"));
    }

    @Test
    void setDisabled_updatesProjectDisabledList_notServerDefinition() throws IOException {
        McpConfigWriter.addServer(McpServerScope.USER, projectDir, stdio("user", "u"), globalConfig);

        assertTrue(McpConfigWriter.setDisabled(
            McpServerScope.USER, projectDir, "user", true, globalConfig));

        JsonNode root = read(globalConfig);
        assertFalse(root.path("mcpServers").path("user").has("disabled"));
        assertEquals(List.of("user"), JsonUtils.getMapper().convertValue(
            currentProject(root).path("disabledMcpServers"),
            JsonUtils.getMapper().getTypeFactory().constructCollectionType(List.class, String.class)));
    }

    @Test
    void setDisabled_isIdempotent_andEnableRemovesMembership() throws IOException {
        assertTrue(McpConfigWriter.setDisabled(
            McpServerScope.PROJECT, projectDir, "server", true, globalConfig));
        assertFalse(McpConfigWriter.setDisabled(
            McpServerScope.PROJECT, projectDir, "server", true, globalConfig));
        assertTrue(McpConfigWriter.setDisabled(
            McpServerScope.PROJECT, projectDir, "server", false, globalConfig));
        assertTrue(currentProject(read(globalConfig)).path("disabledMcpServers").isArray());
        assertEquals(0, currentProject(read(globalConfig)).path("disabledMcpServers").size());
    }

    @Test
    void unsupportedScopes_areRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> McpConfigWriter.addServer(McpServerScope.ENTERPRISE, projectDir,
                stdio("x", "cmd"), globalConfig));
        assertThrows(IllegalArgumentException.class,
            () -> McpConfigWriter.removeServer(McpServerScope.DYNAMIC, projectDir,
                "x", globalConfig));
    }

    @Test
    void projectWritesRespectSettingSourceGate() throws IOException {
        McpConfigLoader.configureEnabledFileScopes(
            Set.of(McpServerScope.USER, McpServerScope.LOCAL));

        Files.writeString(projectDir.resolve(".mcp.json"),
            "{\"other\":42,\"mcpServers\":{\"old\":{\"type\":\"stdio\",\"command\":\"old\"}}}");
        McpConfigWriter.addServer(McpServerScope.PROJECT, projectDir,
            stdio("project", "cmd"), globalConfig);

        JsonNode root = read(projectDir.resolve(".mcp.json"));
        assertEquals(42, root.path("other").asInt());
        assertTrue(root.path("mcpServers").has("project"));
        assertFalse(root.path("mcpServers").has("old"));
        assertFalse(McpConfigWriter.removeServer(McpServerScope.PROJECT, projectDir,
            "project", globalConfig));
    }

    private static McpServerConfig stdio(String name, String command) {
        return new McpServerConfig(name, command, List.of(), Map.of(), false, "stdio");
    }

    private static McpServerConfig http(String name, String url) {
        return new McpServerConfig(name, "", List.of(), Map.of(), false,
            "http", url, Map.of("X-Test", "yes"));
    }

    private static JsonNode read(Path path) throws IOException {
        return JsonUtils.getMapper().readTree(path.toFile());
    }

    private JsonNode currentProject(JsonNode root) {
        return root.path("projects").path(McpConfigLoader.projectConfigKey(projectDir));
    }
}
