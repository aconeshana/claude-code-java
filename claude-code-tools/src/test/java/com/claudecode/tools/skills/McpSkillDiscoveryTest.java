package com.claudecode.tools.skills;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.mcp.McpConnection;
import com.claudecode.mcp.McpConnectionView;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpTransport;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpSkillDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void directSkillUsesIndexNameStripsPrivilegeFrontmatterAndCachesSkillMd() {
        RecordingTransport transport = new RecordingTransport();
        transport.skillMarkdown = """
            ---
            name: ignored-content-name
            description: WIRE197 MCP direct skill description
            allowed-tools: [Bash]
            model: opus
            effort: high
            context: fork
            ---
            Follow the WIRE197 MCP direct skill body. Marker: $ARGUMENTS
            """;
        McpConnection connection = connection("wire-skills", transport, true, false);

        List<Skill> skills = new McpSkillDiscovery(tempDir).fetch(McpConnectionView.of(connection));

        assertEquals(1, skills.size());
        Skill skill = skills.getFirst();
        assertEquals("wire-skills:wire-probe", skill.name());
        assertEquals("WIRE197 MCP direct skill description", skill.description());
        assertEquals(Skill.SkillSource.MCP, skill.source());
        assertEquals(List.of(), skill.allowedTools());
        assertNull(skill.model());
        assertNull(skill.effort());
        assertNull(skill.context());
        assertEquals("wire-skills", skill.frontmatter().get("mcpServer"));
        assertEquals("skill://wire-probe", skill.frontmatter().get("mcpResourceRoot"));
        assertFalse((Boolean) skill.frontmatter().get("mcpDirectoryRead"));
        assertTrue(Files.isRegularFile(skill.sourceFile()));
        assertEquals(transport.skillMarkdown, assertDoesNotThrowRead(skill.sourceFile()));
        assertEquals(List.of("skill://index.json", "skill://wire-probe/SKILL.md"),
            transport.readUris);
    }

    @Test
    void startupResourcePrefetchRunsBetweenIndexAndDirectSkillReads() {
        RecordingTransport transport = new RecordingTransport();
        transport.skillMarkdown = "---\nname: wire-probe\n---\nbody\n";
        List<String> events = transport.events;

        new McpSkillDiscovery(tempDir).fetch(
            McpConnectionView.of(connection("wire-skills", transport, true, false)),
            () -> events.add("resources/list"));

        assertEquals(List.of(
            "resources/read skill://index.json",
            "resources/list",
            "resources/read skill://wire-probe/SKILL.md"), events);
    }

    @Test
    void directResourceUrlStillCarriesMcpServerAttribution() {
        RecordingTransport transport = new RecordingTransport();
        transport.skillUrl = "wire://skills/wire-probe";
        transport.skillMarkdown = "---\nname: wire-probe\n---\nbody\n";

        Skill skill = new McpSkillDiscovery(tempDir).fetch(
            McpConnectionView.of(connection("wire-skills", transport, true, false)))
            .getFirst();

        assertEquals("wire-skills", skill.frontmatter().get("mcpServer"));
        assertFalse(skill.frontmatter().containsKey("mcpResourceRoot"));
    }

    @Test
    void serverWithoutSkillsExtensionIsIgnoredWithoutReadingResources() {
        RecordingTransport transport = new RecordingTransport();

        List<Skill> skills = new McpSkillDiscovery(tempDir)
            .fetch(McpConnectionView.of(connection("plain", transport, false, false)));

        assertEquals(List.of(), skills);
        assertEquals(List.of(), transport.readUris);
    }

    @Test
    void skillLoaderAppendsMcpSkillsAfterLocalAndBundledInventory() {
        SkillLoader loader = new SkillLoader();
        loader.setBundledSkills(List.of(skill("builtin", Skill.SkillSource.BUILTIN)));
        loader.setMcpSkills(List.of(skill("server:remote", Skill.SkillSource.MCP)));

        assertEquals(List.of("builtin", "server:remote"),
            loader.loadAll().stream().map(Skill::name).toList());
    }

    private static Skill skill(String name, Skill.SkillSource source) {
        return new Skill(name, name, List.of(), "body", null, source,
            null, null, null, Map.of());
    }

    private static McpConnection connection(
            String name, RecordingTransport transport,
            boolean skillsExtension, boolean directoryRead) {
        ObjectNode capabilities = JsonUtils.getMapper().createObjectNode();
        if (skillsExtension) {
            ObjectNode extension = capabilities.putObject("extensions")
                .putObject("io.modelcontextprotocol/skills");
            if (directoryRead) extension.put("directoryRead", true);
        }
        McpServerConfig config = new McpServerConfig(
            name, "fake", List.of(), Map.of(), false, "stdio");
        return new McpConnection(config, transport) {
            @Override public JsonNode getServerCapabilities() { return capabilities; }
        };
    }

    private static String assertDoesNotThrowRead(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingTransport implements McpTransport {
        private final List<String> readUris = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private String skillUrl = "skill://wire-probe/SKILL.md";
        private String skillMarkdown;

        @Override
        public JsonNode sendRequest(String method, JsonNode params) {
            if (!Strings.CS.equals("resources/read", method)) {
                throw new AssertionError("Unexpected method: " + method);
            }
            String uri = params.path("uri").asText();
            readUris.add(uri);
            events.add("resources/read " + uri);
            ObjectNode result = JsonUtils.getMapper().createObjectNode();
            var contents = result.putArray("contents");
            ObjectNode content = contents.addObject();
            content.put("uri", uri);
            if (Strings.CS.equals("skill://index.json", uri)) {
                content.put("text", """
                    {"skills":[{"frontmatter":{"name":"wire-probe"},
                    "url":"%s"}]}
                    """.formatted(skillUrl));
            } else {
                content.put("text", skillMarkdown);
            }
            return result;
        }

        @Override public boolean isConnected() { return true; }
        @Override public void close() { }
    }
}
