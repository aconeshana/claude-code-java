package com.claudecode.tools.mcp;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.mcp.McpClientManager;
import com.claudecode.mcp.McpException;
import com.claudecode.mcp.McpToolInfo;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.Tool;
import com.claudecode.permissions.PermissionDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MCPTool}.
 */
class MCPToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private StubClientManager clientManager;
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        clientManager = new StubClientManager();

        context = ToolExecutionContext.of(
            new AbortController(), "test-session");
    }

    @Test
    void nameIncludesServerAndToolName() {
        McpToolInfo info = new McpToolInfo("my-server", "my-tool", "desc", MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);
        assertEquals("mcp__my-server__my-tool", tool.name());
        assertTrue(tool.aliases().isEmpty());
        assertSame(tool.identity(), tool.identity());
    }

    @Test
    void identityIsDerivedPerMcpToolInstance() {
        MCPTool first = new MCPTool(
            new McpToolInfo("server-a", "tool", "desc", MAPPER.createObjectNode()),
            clientManager);
        MCPTool second = new MCPTool(
            new McpToolInfo("server-b", "tool", "desc", MAPPER.createObjectNode()),
            clientManager);

        assertEquals("mcp__server-a__tool", first.name());
        assertEquals("mcp__server-b__tool", second.name());
        assertNotEquals(first.identity(), second.identity());
    }

    @Test
    void descriptionFromToolInfo() {
        McpToolInfo info = new McpToolInfo("srv", "tool", "A great tool", MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);
        assertEquals("A great tool", tool.description());
    }

    @Test
    void missingMcpDescriptionUsesTsEmptyStringFallback() {
        MCPTool tool = new MCPTool(
            new McpToolInfo("srv", "tool", null, MAPPER.createObjectNode()), clientManager);
        assertEquals("", tool.description());
        assertEquals("", tool.prompt(null));
    }

    @Test
    void promptUsesTheSameTsDescriptionTruncationAsApiDefinitions() {
        String longDescription = "x".repeat(2050);
        MCPTool tool = new MCPTool(new McpToolInfo(
            "srv", "tool", longDescription, MAPPER.createObjectNode()), clientManager);

        assertEquals(2048 + "… [truncated]".length(), tool.prompt(null).length());
        assertTrue(Strings.CS.endsWith(tool.prompt(null), "… [truncated]"));
        assertEquals(tool.prompt(null), tool.description(null, null));
    }

    @Test
    void inputSchemaFromToolInfo() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        McpToolInfo info = new McpToolInfo("srv", "tool", "desc", schema);
        MCPTool tool = new MCPTool(info, clientManager);
        assertEquals(schema, tool.inputSchema());
    }

    @Test
    void callPreservesMcpContentBlocksAndToolUseMetadata() {
        McpToolInfo info = new McpToolInfo("test-srv", "fake-tool", "desc", MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("arg1", "value1");

        ToolResult result = tool.call(input, context.withToolUseId("toolu_wire_197"));

        assertFalse(result.isError());
        TextBlock text = assertInstanceOf(TextBlock.class, result.content().getFirst());
        assertEquals("echo:value1", text.text());
        assertEquals(clientManager.response.path("content"), result.toolUseResult());
        assertEquals("toolu_wire_197", clientManager.lastToolUseId);
        assertSame(context.abortController(), clientManager.lastAbortController);
    }

    @Test
    void callReturnsErrorMessageOnFailure() {
        // Use a tool info pointing to a non-existent server
        McpToolInfo info = new McpToolInfo("nonexistent", "tool", "desc", MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);

        ToolResult result = tool.call(MAPPER.createObjectNode(), context);
        assertTrue(result.isError());
        String error = ((TextBlock) result.content().getFirst()).text();
        assertFalse(StringUtils.isBlank(error));
        assertEquals("Error: " + error, result.toolUseResult());
    }

    @Test
    void mcpIsErrorUsesTheFirstTextBlockAsTheToolError() {
        McpToolInfo info = new McpToolInfo("test-srv", "fake-tool", "desc", MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);
        clientManager.response.put("isError", true);
        ((ObjectNode) clientManager.response.path("content").get(0))
            .put("text", "WIRE197 MCP failure");

        ToolResult result = tool.call(MAPPER.createObjectNode(), context);

        assertTrue(result.isError());
        assertEquals("WIRE197 MCP failure",
            ((TextBlock) result.content().getFirst()).text());
    }

    @Test
    void mcpIsErrorPreservesRawMetaOnToolUseResult() {
        McpToolInfo info = new McpToolInfo("test-srv", "fake-tool", "desc", MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);
        clientManager.response.put("isError", true);
        ((ObjectNode) clientManager.response.path("content").get(0))
            .put("text", "WIRE197 MCP failure");
        clientManager.response.putObject("_meta").put("trace", "error-trace");

        ToolResult result = tool.call(MAPPER.createObjectNode(), context);

        assertTrue(result.isError());
        assertEquals("Error: WIRE197 MCP failure", result.toolUseResult());
        assertEquals("error-trace", result.mcpMeta().get("_meta") instanceof Map<?, ?> meta
            ? String.valueOf(meta.get("trace")) : null);
    }

    @Test
    void ideServerSkipsMcpLargeOutputPersistence() {
        McpToolInfo info = new McpToolInfo(
            "ide", "workspace_dump", "desc", MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);
        String large = "x".repeat(120_000);
        clientManager.response.remove("content");
        clientManager.response.putObject("structuredContent").put("text", large);

        ToolResult result = tool.call(MAPPER.createObjectNode(), context);

        TextBlock text = assertInstanceOf(TextBlock.class, result.content().getFirst());
        assertTrue(Strings.CS.contains(text.text(), large),
            "TS exempts the ide server from MCP large-output persistence");
        assertFalse(Strings.CS.contains(text.text(), "Output has been saved to"));
    }

    @Test
    void nonIdeLargeStructuredOutputUsesExtensionlessTsPersistId(@TempDir Path tempDir)
            throws Exception {
        McpToolInfo info = new McpToolInfo(
            "my-server", "workspace_dump", "desc", MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);
        clientManager.response.remove("content");
        clientManager.response.putObject("structuredContent")
            .put("text", "x".repeat(120_000));
        ToolExecutionContext largeContext = ToolExecutionContext.builder(new AbortController(), "session-large").workingDirectory(tempDir.toString()).build();

        ToolResult result = tool.call(MAPPER.createObjectNode(), largeContext);
        String text = assertInstanceOf(TextBlock.class, result.content().getFirst()).text();
        assertTrue(Strings.CS.contains(text, "Output has been saved to"), text);
        String pathText = text.substring(text.indexOf("Output has been saved to ")
            + "Output has been saved to ".length());
        String path = pathText.substring(0, pathText.indexOf('.'));
        assertTrue(Files.exists(Path.of(path)), path);
        assertFalse(Strings.CS.endsWith(path, ".txt"), path);
    }

    @Test
    void largeContentArrayUsesPersistedInstructionOnToolUseResult(@TempDir Path tempDir)
            throws Exception {
        MCPTool tool = new MCPTool(new McpToolInfo(
            "my-server", "workspace_dump", "desc", MAPPER.createObjectNode()), clientManager);
        clientManager.response.remove("structuredContent");
        clientManager.response.remove("content");
        clientManager.response.putArray("content").addObject()
            .put("type", "text").put("text", "x".repeat(120_000));
        ToolExecutionContext largeContext = ToolExecutionContext.builder(new AbortController(), "session-array-large").workingDirectory(tempDir.toString()).build();

        ToolResult result = tool.call(MAPPER.createObjectNode(), largeContext);

        assertEquals(1, result.content().size());
        String modelText = assertInstanceOf(TextBlock.class, result.content().getFirst()).text();
        assertEquals(modelText, result.toolUseResult(),
            "toolUseResult must carry the same persisted instruction the model received");
        assertTrue(Strings.CS.contains(modelText, "Output has been saved to"), modelText);
    }

    @Test
    void annotatedMaxResultSizeBypassesMcpPrePersistenceAndUsesFiveHundredKCeiling() {
        ObjectNode meta = MAPPER.createObjectNode()
            .put("anthropic/maxResultSizeChars", 300_000);
        MCPTool tool = new MCPTool(new McpToolInfo(
            "my-server", "workspace_dump", "desc", MAPPER.createObjectNode(),
            MAPPER.createObjectNode(), meta), clientManager);
        clientManager.response.remove("structuredContent");
        clientManager.response.remove("content");
        clientManager.response.putArray("content").addObject()
            .put("type", "text").put("text", "x".repeat(120_000));

        ToolResult result = tool.call(MAPPER.createObjectNode(), context);

        assertEquals(300_000, tool.maxResultSizeChars());
        assertEquals(500_000, tool.persistenceThresholdCeiling());
        assertEquals(120_000,
            assertInstanceOf(TextBlock.class, result.content().getFirst()).text().length());
        assertFalse(Strings.CS.contains(
            assertInstanceOf(TextBlock.class, result.content().getFirst()).text(),
            "Output has been saved to"));
    }

    @Test
    void annotatedMaxResultSizeIsCappedAndInvalidValuesFallBack() {
        ObjectNode capped = MAPPER.createObjectNode()
            .put("anthropic/maxResultSizeChars", 900_000);
        MCPTool cappedTool = new MCPTool(new McpToolInfo(
            "srv", "tool", "desc", MAPPER.createObjectNode(),
            MAPPER.createObjectNode(), capped), clientManager);
        ObjectNode invalid = MAPPER.createObjectNode()
            .put("anthropic/maxResultSizeChars", -1);
        MCPTool invalidTool = new MCPTool(new McpToolInfo(
            "srv", "tool", "desc", MAPPER.createObjectNode(),
            MAPPER.createObjectNode(), invalid), clientManager);

        assertEquals(500_000, cappedTool.maxResultSizeChars());
        assertEquals(100_000, invalidTool.maxResultSizeChars());
        assertEquals(50_000, invalidTool.persistenceThresholdCeiling());
    }

    @Test
    void imageResourceFallbackKeepsFullMimeType() {
        MCPTool tool = new MCPTool(new McpToolInfo(
            "srv", "resource_tool", "desc", MAPPER.createObjectNode()), clientManager);
        clientManager.response.remove("content");
        ArrayNode content = clientManager.response.putArray("content");
        ObjectNode resource = content.addObject().put("type", "resource");
        resource.putObject("resource")
            .put("uri", "file://invalid.png")
            .put("mimeType", "image/png")
            .put("blob", "aGVsbG8=");

        ToolResult result = tool.call(MAPPER.createObjectNode(), context);

        JsonNode image = ((JsonNode) result.toolUseResult()).get(1);
        assertEquals("image/png", image.path("source").path("media_type").asText());
    }

    @Test
    void binaryToolContentUsesMcpBlobFilenameAndHumanSize(@TempDir Path tempDir)
            throws Exception {
        MCPTool tool = new MCPTool(new McpToolInfo(
            "My Server", "audio_tool", "desc", MAPPER.createObjectNode()), clientManager);
        clientManager.response.remove("content");
        clientManager.response.putArray("content").addObject()
            .put("type", "audio")
            .put("data", Base64.getEncoder().encodeToString(
                "hello".getBytes(StandardCharsets.UTF_8)))
            .put("mimeType", "audio/mpeg");
        ToolExecutionContext binaryContext = ToolExecutionContext.builder(new AbortController(), "session-binary").workingDirectory(tempDir.toString()).build();

        ToolResult result = tool.call(MAPPER.createObjectNode(), binaryContext);
        String text = assertInstanceOf(TextBlock.class, result.content().getFirst()).text();

        assertTrue(Strings.CS.contains(text, "Binary content (audio/mpeg, 5 bytes) saved to"));
        assertTrue(Strings.CS.contains(text, "mcp-My_Server-blob-"), text);
        assertFalse(Strings.CS.contains(text, "mcp-resource-"), text);
        String filepath = text.substring(text.indexOf(" saved to ") + " saved to ".length());
        assertEquals("hello", Files.readString(Path.of(filepath), StandardCharsets.UTF_8));
    }

    @Test
    void readOnlyAnnotationMakesTheToolConcurrencySafe() {
        ObjectNode annotations = MAPPER.createObjectNode();
        annotations.put("readOnlyHint", true);
        McpToolInfo info = new McpToolInfo(
            "srv", "tool", "desc", MAPPER.createObjectNode(), annotations);
        MCPTool tool = new MCPTool(info, clientManager);
        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
    }

    @Test
    void mutatingMcpToolIsNotConcurrencySafeByDefault() {
        McpToolInfo info = new McpToolInfo(
            "srv", "tool", "desc", MAPPER.createObjectNode(),
            MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);
        assertFalse(tool.isReadOnly());
        assertFalse(tool.isConcurrencySafe());
    }

    @Test
    void exposesDynamicMetadataAndPreservesMcpMeta() {
        ObjectNode annotations = MAPPER.createObjectNode();
        annotations.put("readOnlyHint", true);
        annotations.put("destructiveHint", true);
        annotations.put("openWorldHint", true);
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("anthropic/searchHint", "  search   records  ");
        meta.put("anthropic/alwaysLoad", true);
        McpToolInfo info = new McpToolInfo(
            "srv", "searchRecords", "desc", MAPPER.createObjectNode(), annotations, meta);
        MCPTool tool = new MCPTool(info, clientManager);
        clientManager.response.putObject("_meta").put("trace", "abc");

        assertTrue(tool.isMcp());
        assertEquals(new Tool.ToolMcpInfo("srv", "searchRecords"), tool.mcpInfo());
        assertEquals("search records", tool.searchHint());
        assertTrue(tool.alwaysLoad());
        assertTrue(tool.isDestructive(MAPPER.createObjectNode()));
        assertTrue(tool.isOpenWorld(MAPPER.createObjectNode()));
        assertTrue(tool.searchReadClassification(MAPPER.createObjectNode()).isSearch());

        ToolResult result = tool.call(MAPPER.createObjectNode(), context);
        assertInstanceOf(JsonNode.class, result.toolUseResult());
        JsonNode payload = (JsonNode) result.toolUseResult();
        assertTrue(payload.isArray());
        assertEquals("echo:value1", payload.get(0).path("text").asText());
        assertEquals("abc", result.mcpMeta().get("_meta") instanceof Map<?, ?> m
            ? String.valueOf(m.get("trace")) : null);
    }

    @Test
    void usesTsAllowlistAndDoesNotInferUnknownMcpVerbs() {
        MCPTool search = new MCPTool(new McpToolInfo(
            "srv", "search_code", "desc", MAPPER.createObjectNode()), clientManager);
        MCPTool read = new MCPTool(new McpToolInfo(
            "srv", "get_file_contents", "desc", MAPPER.createObjectNode()), clientManager);
        MCPTool unknown = new MCPTool(new McpToolInfo(
            "srv", "update_search_index", "desc", MAPPER.createObjectNode()), clientManager);

        assertTrue(search.searchReadClassification(MAPPER.createObjectNode()).isSearch());
        assertTrue(read.searchReadClassification(MAPPER.createObjectNode()).isRead());
        assertEquals(Tool.SearchReadClassification.NONE,
            unknown.searchReadClassification(MAPPER.createObjectNode()));
    }

    @Test
    void encodesAutoClassifierInputAndOffersMcpRuleSuggestion() {
        MCPTool tool = new MCPTool(new McpToolInfo(
            "srv", "lookup", "desc", MAPPER.createObjectNode()), clientManager);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("query", "alpha");
        input.put("limit", 2);

        assertEquals("query=alpha limit=2", tool.toAutoClassifierInput(input));
        assertEquals("lookup", tool.toAutoClassifierInput(MAPPER.createObjectNode()));

        PermissionDecision decision = tool.checkPermissions(input, null);
        PermissionDecision.Ask ask = assertInstanceOf(PermissionDecision.Ask.class, decision);
        assertEquals("MCPTool requires permission.", ask.message());
        PermissionUpdate.AddRules update = assertInstanceOf(
            PermissionUpdate.AddRules.class, ask.suggestions().getFirst());
        assertEquals("mcp__srv__lookup", update.rules().getFirst().toolName());
        assertEquals(PermissionUpdate.Destination.LOCAL_SETTINGS, update.destination());
    }

    @Test
    void emitsStartedAndCompletedProgressEvents() {
        List<ToolExecutionContext.ProgressUpdate> updates = new ArrayList<>();
        ToolExecutionContext progressContext = ToolExecutionContext.of(
            new AbortController(), "test-session", updates::add).withToolUseId("toolu-progress");
        McpToolInfo info = new McpToolInfo("srv", "tool", "desc", MAPPER.createObjectNode());
        new MCPTool(info, clientManager).call(MAPPER.createObjectNode(), progressContext);

        assertEquals(List.of("started", "completed"), updates.stream()
            .map(ToolExecutionContext.ProgressUpdate::output).toList());
    }

    @Test
    void getToolInfoReturnsOriginal() {
        McpToolInfo info = new McpToolInfo("srv", "tool", "desc", MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);
        assertSame(info, tool.getToolInfo());
    }

    @Test
    void registryExposesRawMcpIdentityForAssistantAttribution() {
        McpToolInfo info = new McpToolInfo(
            "wire reconnect", "echo_marker", "desc", MAPPER.createObjectNode());
        MCPTool tool = new MCPTool(info, clientManager);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        assertEquals(new ToolExecutor.McpAttribution("wire reconnect", "echo_marker"),
            registry.mcpAttribution(tool.name()));
    }

    private static final class StubClientManager extends McpClientManager {
        private final ObjectNode response = MAPPER.createObjectNode();
        private String lastToolUseId;
        private AbortController lastAbortController;

        private StubClientManager() {
            ArrayNode content = response.putArray("content");
            content.addObject().put("type", "text").put("text", "echo:value1");
            response.put("isError", false);
        }

        @Override
        public JsonNode callTool(String serverId, String toolName, JsonNode args,
                                 String toolUseId, AbortController abortController) {
            if (Strings.CS.equals("nonexistent", serverId)) {
                throw new McpException("No active connection to server '" + serverId + "'");
            }
            lastToolUseId = toolUseId;
            lastAbortController = abortController;
            return response;
        }

        @Override
        public JsonNode callTool(String serverId, String toolName, JsonNode args,
                                 String toolUseId, AbortController abortController,
                                 Consumer<JsonNode> progressListener) {
            return callTool(serverId, toolName, args, toolUseId, abortController);
        }
    }
}
