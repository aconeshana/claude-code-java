package com.claudecode.tools.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the reverse-index behaviour that powers the {@code /mcp} browser's
 * "used by: ..." line. Every test writes fresh agent files under a temp
 * {@code .claude/agents} dir and points {@link AgentDefinitionLoader} at it via
 * the {@code cwd} argument, so runs are hermetic.
 */
class AgentMcpServerIndexTest {

    @TempDir
    Path projectDir;

    @BeforeEach
    void resetCache() {
        AgentDefinitionLoader.clearCache();
    }

    @AfterEach
    void clearCacheAfter() {
        AgentDefinitionLoader.clearCache();
    }

    @Test
    void usedByForServer_returnsAgentsThatReferenceIt_sortedAlphabetically() throws IOException {
        writeAgent("zeta.md",  "notion-oauth", "gh");
        writeAgent("alpha.md", "notion-oauth");
        writeAgent("beta.md",  "gh");  // does not reference notion-oauth

        List<String> notionUsers = AgentMcpServerIndex.usedByForServer(
            projectDir.toString(), "notion-oauth");

        assertEquals(List.of("alpha", "zeta"), notionUsers,
            "only agents whose mcpServers list contains the server appear, sorted");
    }

    @Test
    void usedByForServer_returnsEmpty_whenNoAgentReferencesServer() throws IOException {
        writeAgent("solo.md", "gh");

        List<String> users = AgentMcpServerIndex.usedByForServer(
            projectDir.toString(), "nonexistent");

        assertTrue(users.isEmpty(),
            "servers not mentioned by any agent get an empty list, not null");
    }

    @Test
    void usedByForServer_ignoresBuiltInAgents_withNoFrontmatter() throws IOException {
        // No custom-agent files → the only agents in play are built-ins.
        // Built-ins carry no frontmatter and therefore no mcpServers list.
        List<String> users = AgentMcpServerIndex.usedByForServer(
            projectDir.toString(), "notion-oauth");

        assertTrue(users.isEmpty(),
            "built-ins have empty mcpServers by default — nothing to index");
    }

    @Test
    void usedByForServer_handlesBlankOrNullServerName() {
        assertTrue(AgentMcpServerIndex.usedByForServer(projectDir.toString(), null).isEmpty());
        assertTrue(AgentMcpServerIndex.usedByForServer(projectDir.toString(), "").isEmpty());
        assertTrue(AgentMcpServerIndex.usedByForServer(projectDir.toString(), "   ").isEmpty());
    }

    @Test
    void buildIndex_groupsAllServerReferences() throws IOException {
        writeAgent("writer.md",   "notion-oauth");
        writeAgent("reviewer.md", "notion-oauth", "gh");
        writeAgent("archivist.md", "gh");

        Map<String, List<String>> index = AgentMcpServerIndex.buildIndex(projectDir.toString());

        // Two servers indexed, each mapping to its agent set (sorted).
        assertEquals(List.of("reviewer", "writer"), index.get("notion-oauth"));
        assertEquals(List.of("archivist", "reviewer"), index.get("gh"));
        // Only servers actually referenced show up in the index.
        assertFalse(index.containsKey("unreferenced"));
    }

    /**
     * Writes a minimal agent markdown file under {@code projectDir/.claude/agents/}.
     * The frontmatter mixes {@code mcpServers: [a, b]} inline-array syntax to
     * lock in that {@link AgentDefinitionLoader#extractFrontmatter} routes it
     * through the same path a user would hand-edit.
     */
    private void writeAgent(String fileName, String... mcpServers) throws IOException {
        Path agentsDir = projectDir.resolve(".claude").resolve("agents");
        Files.createDirectories(agentsDir);
        String name = fileName.replace(".md", "");
        String mcpList = mcpServers.length == 0 ? ""
            : "\nmcpServers: [" + String.join(", ", mcpServers) + "]";
        Files.writeString(agentsDir.resolve(fileName), """
            ---
            name: %s
            description: Test agent %s%s
            ---
            body content
            """.formatted(name, name, mcpList));
    }
}
