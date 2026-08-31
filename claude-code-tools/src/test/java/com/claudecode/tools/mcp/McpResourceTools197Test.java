package com.claudecode.tools.mcp;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.mcp.McpClientManager;
import com.claudecode.mcp.McpConnection;
import com.claudecode.mcp.McpException;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpTransport;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpResourceTools197Test {

    @Test
    void resourceToolNamesAndSchemasMatch197() {
        McpClientManager manager = new McpClientManager();

        ListMcpResourcesTool listTool = new ListMcpResourcesTool(manager);
        assertContract(listTool, "ListMcpResourcesTool", false, false);
        assertEquals(ToolTexts.description("ListMcpResourcesTool"), listTool.description());
        assertEquals(ToolTexts.prompt("ListMcpResourcesTool"),
            listTool.prompt(null));
        ReadMcpResourceTool readTool = new ReadMcpResourceTool(manager);
        assertContract(readTool, "ReadMcpResourceTool", true, false);
        assertEquals(ToolTexts.description("ReadMcpResourceTool"), readTool.description());
        assertEquals(ToolTexts.prompt("ReadMcpResourceTool"),
            readTool.prompt(null));
        ReadMcpResourceDirTool directoryTool = new ReadMcpResourceDirTool(manager);
        assertContract(directoryTool, "ReadMcpResourceDirTool", true, true);
        assertEquals(ToolTexts.description("ReadMcpResourceDirTool"),
            directoryTool.description());
        assertEquals(List.of("ReadMcpResourceDir"), directoryTool.aliases());
        assertTrue(directoryTool.shouldDefer());
    }

    @Test
    void listResourcesPreservesOfficialTextAndStructuredResultChannels() {
        var manager = new ResourceManager();
        Object raw = new ListMcpResourcesTool(manager).call(
            JsonUtils.getMapper().createObjectNode(), null);

        StructuredToolOutput output = assertInstanceOf(StructuredToolOutput.class, raw);
        assertEquals("[{\"name\":\"Wire README\",\"uri\":\"wire://catalog/readme\","
            + "\"description\":\"Deterministic text resource.\",\"mimeType\":\"text/plain\","
            + "\"server\":\"wirecatalog\"}]", output.text());
        assertEquals(output.text(), JsonUtils.getMapper().valueToTree(output.toolUseResult()).toString());
    }

    @Test
    void listResourcesRejectsUnknownServerLike197() {
        var input = JsonUtils.getMapper().createObjectNode().put("server", "missing");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new ListMcpResourcesTool(new ResourceManager()).call(input, null));

        assertEquals("Server \"missing\" not found. Available servers: wirecatalog",
            error.getMessage());
    }

    @Test
    void readResourcePreservesOfficialTextAndStructuredResultChannels() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("server", "wirecatalog")
            .put("uri", "wire://catalog/readme");

        Object raw = new ReadMcpResourceTool(new ResourceManager()).call(input, null);

        StructuredToolOutput output = assertInstanceOf(StructuredToolOutput.class, raw);
        assertEquals("{\"contents\":[{\"uri\":\"wire://catalog/readme\","
            + "\"mimeType\":\"text/plain\",\"text\":\"WIRE197 resource body\"}]}",
            output.text());
        assertEquals(output.text(), JsonUtils.getMapper().valueToTree(output.toolUseResult()).toString());
    }

    @Test
    void readBinaryResourcePersistsRawBytesAndReplacesBase64(@TempDir Path tempDir)
            throws Exception {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("server", "wireblob")
            .put("uri", "wire://catalog/blob");
        Path toolResults = tempDir.resolve("tool-results");
        var storage = new McpBinaryResourceStorage(
            (_, _) -> toolResults,
            () -> 1_785_490_214_827L,
            () -> "kz74uw");
        var context = ToolExecutionContext.builder(new AbortController(), "session-197").workingDirectory(tempDir.toString()).build();

        StructuredToolOutput output = new ReadMcpResourceTool(
            new BlobResourceManager(), storage).call(input, context);
        Path persisted = toolResults.resolve(
            "mcp-resource-1785490214827-0-kz74uw.pdf");

        assertEquals("WIRE197-BINARY",
            Files.readString(persisted, StandardCharsets.UTF_8));
        assertEquals("{\"contents\":[{\"uri\":\"wire://catalog/blob\","
            + "\"mimeType\":\"application/pdf\",\"blobSavedTo\":\""
            + persisted + "\",\"text\":\"[Resource from wireblob at "
            + "wire://catalog/blob] Binary content (application/pdf, 14 bytes) saved to "
            + persisted + "\"}]}", output.text());
        assertEquals(output.text(),
            JsonUtils.getMapper().valueToTree(output.toolUseResult()).toString());
        assertFalse(Strings.CS.contains(output.text(), "V0lSRTE5Ny1CSU5BUlk="));
        assertFalse(Strings.CS.contains(output.text(), "\"blob\""));
    }

    @Test
    void directoryReadGateOffReturnsTheReleasedStructuredError() {
        var manager = new DirectoryManager(directoryCapabilities(true),
            new DirectoryTransport(List.of(), -1));
        var input = directoryInput("wire://catalog/root");

        StructuredToolOutput output = new ReadMcpResourceDirTool(manager, false)
            .call(input, null);

        assertEquals("Directory listing is not enabled in this build.", output.text());
        assertEquals("{\"resources\":[],\"error\":\"Directory listing is not enabled in this build.\"}",
            JsonUtils.getMapper().valueToTree(output.toolUseResult()).toString());
    }

    @Test
    void directoryReadRejectsServersWithoutTheExtensionCapability() {
        var manager = new DirectoryManager(directoryCapabilities(false),
            new DirectoryTransport(List.of(), -1));

        StructuredToolOutput output = new ReadMcpResourceDirTool(manager, true)
            .call(directoryInput("wire://catalog/root"), null);

        assertEquals("Server \"wirecatalog\" does not support directory listing.", output.text());
        assertEquals("{\"resources\":[],\"error\":\"Server \\\"wirecatalog\\\" does not support directory listing.\"}",
            JsonUtils.getMapper().valueToTree(output.toolUseResult()).toString());
    }

    @Test
    void directoryReadPaginatesSanitizesAndFormatsTheReleasedDualChannelResult() {
        ObjectNode pageOne = directoryPage("cursor-1",
            directoryEntry("wire://catalog/\u200Bskills", "\uFF21\u200B", "inode/directory"));
        ObjectNode pageTwo = directoryPage(null,
            directoryEntry("wire://catalog/notes.txt", "notes.txt", null));
        DirectoryTransport transport = new DirectoryTransport(List.of(pageOne, pageTwo), -1);
        var manager = new DirectoryManager(directoryCapabilities(true), transport);

        StructuredToolOutput output = new ReadMcpResourceDirTool(manager, true)
            .call(directoryInput("wire://catalog/root"), null);

        assertEquals("""
            Directory listing (2 entries):
            A/
            notes.txt

            {"resources":[{"uri":"wire://catalog/skills","name":"A",\
            "mimeType":"inode/directory"},{"uri":"wire://catalog/notes.txt",\
            "name":"notes.txt"}]}\
            """, output.text());
        assertEquals("{\"resources\":[{\"uri\":\"wire://catalog/skills\",\"name\":\"A\","
            + "\"mimeType\":\"inode/directory\"},{\"uri\":\"wire://catalog/notes.txt\","
            + "\"name\":\"notes.txt\"}]}",
            JsonUtils.getMapper().valueToTree(output.toolUseResult()).toString());
        assertEquals(2, transport.params().size());
        assertFalse(transport.params().getFirst().has("cursor"));
        assertEquals("cursor-1", transport.params().get(1).path("cursor").asText());
    }

    @Test
    void directoryReadFormatsAnEmptyDirectoryExactly() {
        var manager = new DirectoryManager(directoryCapabilities(true),
            new DirectoryTransport(List.of(directoryPage(null)), -1));

        StructuredToolOutput output = new ReadMcpResourceDirTool(manager, true)
            .call(directoryInput("wire://catalog/empty"), null);

        assertEquals("Directory is empty.\n\n{\"resources\":[]}", output.text());
        assertEquals("{\"resources\":[]}",
            JsonUtils.getMapper().valueToTree(output.toolUseResult()).toString());
    }

    @Test
    void directoryReadMapsFirstPageInvalidParamsToTheReleasedFileHint() {
        var manager = new DirectoryManager(directoryCapabilities(true),
            new DirectoryTransport(List.of(), 0));

        StructuredToolOutput output = new ReadMcpResourceDirTool(manager, true)
            .call(directoryInput("wire://catalog/file.txt"), null);

        assertEquals("Not a directory resource: wire://catalog/file.txt. "
            + "If it is a file resource, use ReadMcpResourceTool instead.", output.text());
        assertEquals("{\"resources\":[],\"error\":\"Not a directory resource: "
            + "wire://catalog/file.txt. If it is a file resource, use "
            + "ReadMcpResourceTool instead.\"}",
            JsonUtils.getMapper().valueToTree(output.toolUseResult()).toString());
    }

    @Test
    void directoryReadReturnsPriorPagesWhenAContinuationCursorIsRejected() {
        ObjectNode pageOne = directoryPage("cursor-1",
            directoryEntry("wire://catalog/one", "one", null));
        DirectoryTransport transport = new DirectoryTransport(List.of(pageOne), 1);
        var manager = new DirectoryManager(directoryCapabilities(true), transport);

        StructuredToolOutput output = new ReadMcpResourceDirTool(manager, true)
            .call(directoryInput("wire://catalog/root"), null);

        assertEquals("""
            Directory listing (1 entry):
            one

            {"resources":[{"uri":"wire://catalog/one","name":"one"}]}\
            """,
            output.text());
        assertEquals(2, transport.params().size());
    }

    @Test
    void directoryReadLooksUpTheServerBeforeApplyingTheFeatureGate() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("server", "missing")
            .put("uri", "wire://catalog/root");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new ReadMcpResourceDirTool(new DirectoryManager(
                directoryCapabilities(true), new DirectoryTransport(List.of(), -1)), false)
                .call(input, null));

        assertEquals("Server \"missing\" not found. Available servers: wirecatalog",
            error.getMessage());
    }

    private static ObjectNode directoryInput(String uri) {
        return JsonUtils.getMapper().createObjectNode()
            .put("server", "wirecatalog")
            .put("uri", uri);
    }

    private static ObjectNode directoryCapabilities(boolean directoryRead) {
        ObjectNode capabilities = JsonUtils.getMapper().createObjectNode();
        capabilities.putObject("resources");
        if (directoryRead) {
            capabilities.putObject("extensions")
                .putObject("io.modelcontextprotocol/skills")
                .put("directoryRead", true);
        }
        return capabilities;
    }

    private static ObjectNode directoryPage(String nextCursor, ObjectNode... entries) {
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        ArrayNode resources = result.putArray("resources");
        for (ObjectNode entry : entries) resources.add(entry);
        if (nextCursor != null) result.put("nextCursor", nextCursor);
        return result;
    }

    private static ObjectNode directoryEntry(String uri, String name, String mimeType) {
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("uri", uri);
        entry.put("name", name);
        if (mimeType != null) entry.put("mimeType", mimeType);
        return entry;
    }

    private static void assertContract(
            Tool<?, ?> tool, String name, boolean uriRequired,
            boolean strict) {
        assertEquals(name, tool.name());
        JsonNode schema = tool.inputSchema();
        assertEquals("https://json-schema.org/draft/2020-12/schema",
            schema.path("$schema").asText());
        if (strict) {
            assertFalse(schema.path("additionalProperties").asBoolean(true));
        } else {
            assertTrue(schema.path("additionalProperties").isMissingNode()
                || schema.path("additionalProperties").asBoolean());
        }
        assertTrue(schema.path("properties").has("server"));
        assertEquals(uriRequired, schema.path("properties").has("uri"));
        if (uriRequired) {
            assertEquals("server", schema.path("required").get(0).asText());
            assertEquals("uri", schema.path("required").get(1).asText());
        } else {
            assertTrue(schema.path("required").isMissingNode());
        }
    }

    private static final class ResourceManager extends McpClientManager {
        private final McpConnection connection = new McpConnection(
            new McpServerConfig("wirecatalog", "fake", List.of(), Map.of(), false, "stdio"),
            new ResourceTransport()) {
                @Override public boolean hasCapability(String key) {
                    return Strings.CS.equals("resources", key);
                }
            };

        @Override
        public Set<String> getConnectedServerIds() {
            return Set.of("wirecatalog");
        }

        @Override
        public Optional<McpConnection> getConnection(String serverId) {
            return Strings.CS.equals("wirecatalog", serverId) ? Optional.of(connection) : Optional.empty();
        }
    }

    private static final class BlobResourceManager extends McpClientManager {
        private final McpConnection connection = new McpConnection(
            new McpServerConfig("wireblob", "fake", List.of(), Map.of(), false, "stdio"),
            new BlobResourceTransport()) {
                @Override public boolean hasCapability(String key) {
                    return Strings.CS.equals("resources", key);
                }
            };

        @Override
        public Set<String> getConnectedServerIds() {
            return Set.of("wireblob");
        }

        @Override
        public Optional<McpConnection> getConnection(String serverId) {
            return Strings.CS.equals("wireblob", serverId) ? Optional.of(connection) : Optional.empty();
        }
    }

    private static final class DirectoryManager extends McpClientManager {
        private final McpConnection connection;

        private DirectoryManager(JsonNode capabilities, DirectoryTransport transport) {
            this.connection = new McpConnection(
                new McpServerConfig("wirecatalog", "fake", List.of(), Map.of(), false, "stdio"),
                transport) {
                    @Override public JsonNode getServerCapabilities() {
                        return capabilities;
                    }

                    @Override public boolean hasCapability(String key) {
                        return capabilities.has(key);
                    }
                };
        }

        @Override
        public Set<String> getConnectedServerIds() {
            return Set.of("wirecatalog");
        }

        @Override
        public Optional<McpConnection> getConnection(String serverId) {
            return Strings.CS.equals("wirecatalog", serverId) ? Optional.of(connection) : Optional.empty();
        }
    }

    private static final class DirectoryTransport implements McpTransport {
        private final List<JsonNode> responses;
        private final int invalidParamsPage;
        private final List<JsonNode> params = new ArrayList<>();
        private int page;

        private DirectoryTransport(List<? extends JsonNode> responses, int invalidParamsPage) {
            this.responses = List.copyOf(responses);
            this.invalidParamsPage = invalidParamsPage;
        }

        @Override
        public JsonNode sendRequest(String method, JsonNode requestParams) {
            if (!Strings.CS.equals("resources/directory/read", method)) {
                throw new AssertionError("Unexpected method: " + method);
            }
            params.add(requestParams.deepCopy());
            int current = page++;
            if (current == invalidParamsPage) {
                throw new McpException("{\"code\":-32602,\"message\":\"Invalid params\"}");
            }
            if (current >= responses.size()) {
                throw new AssertionError("No response configured for page " + current);
            }
            return responses.get(current);
        }

        List<JsonNode> params() {
            return List.copyOf(params);
        }

        @Override public boolean isConnected() { return true; }
        @Override public void close() { }
    }

    private static final class ResourceTransport implements McpTransport {
        @Override
        public JsonNode sendRequest(String method, JsonNode params) {
            var mapper = JsonUtils.getMapper();
            if (Strings.CS.equals("resources/list", method)) {
                var result = mapper.createObjectNode();
                var resource = mapper.createObjectNode();
                resource.put("uri", "wire://catalog/readme");
                resource.put("name", "Wire README");
                resource.put("mimeType", "text/plain");
                resource.put("description", "Deterministic text resource.");
                result.putArray("resources").add(resource);
                return result;
            }
            if (Strings.CS.equals("resources/read", method)) {
                var result = mapper.createObjectNode();
                var content = mapper.createObjectNode();
                content.put("uri", "wire://catalog/readme");
                content.put("mimeType", "text/plain");
                content.put("text", "WIRE197 resource body");
                result.putArray("contents").add(content);
                return result;
            }
            throw new AssertionError("Unexpected method: " + method);
        }

        @Override public boolean isConnected() { return true; }
        @Override public void close() { }
    }

    private static final class BlobResourceTransport implements McpTransport {
        @Override
        public JsonNode sendRequest(String method, JsonNode params) {
            if (!Strings.CS.equals("resources/read", method)) {
                throw new AssertionError("Unexpected method: " + method);
            }
            var mapper = JsonUtils.getMapper();
            var result = mapper.createObjectNode();
            var content = mapper.createObjectNode();
            content.put("uri", "wire://catalog/blob");
            content.put("mimeType", "application/pdf");
            content.put("blob", "V0lSRTE5Ny1CSU5BUlk=");
            result.putArray("contents").add(content);
            return result;
        }

        @Override public boolean isConnected() { return true; }
        @Override public void close() { }
    }
}
