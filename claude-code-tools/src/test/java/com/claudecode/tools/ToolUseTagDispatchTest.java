package com.claudecode.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolUseTagDispatchTest {

    @Test
    void registryValidatesInputAndDispatchesCanonicalNamesAndAliases() {
        ToolRegistry registry = new ToolRegistry();
        RecordingTagTool tool = new RecordingTagTool();
        registry.register(tool);

        ToolUseRenderContext context = new ToolUseRenderContext(
            "toolu_1", "result", List.of(), "main-model");

        assertEquals("value:toolu_1", registry.resolveToolUseTag(
            "tag-tool", "{\"value\":\"value\"}", context).orElseThrow().text());
        assertEquals("alias:toolu_1", registry.resolveToolUseTag(
            "legacy-tag", "{\"value\":\"alias\"}", context).orElseThrow().text());
        assertEquals(2, tool.calls.get());

        assertTrue(registry.resolveToolUseTag(
            "tag-tool", "{\"unexpected\":true}", context).isEmpty());
        assertTrue(registry.resolveToolUseTag(
            "tag-tool", "not-json", context).isEmpty());
        assertEquals(2, tool.calls.get());
    }

    @BuiltInTool(name = "tag-tool", aliases = "legacy-tag", strict = true)
    private static final class RecordingTagTool extends AnnotatedTool<JsonNode, String> {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public String description() { return "test"; }

        @Override
        public JsonNode inputSchema() {
            var schema = createObjectSchema();
            schema.path("properties");
            ((ObjectNode) schema.path("properties"))
                .putObject("value").put("type", "string");
            ((ArrayNode) schema.putArray("required"))
                .add("value");
            schema.put("additionalProperties", false);
            return schema;
        }

        @Override public String call(JsonNode input, ToolExecutionContext context) { return "ok"; }

        @Override
        public Optional<ToolUseTag> renderToolUseTag(
                JsonNode input, ToolUseRenderContext context) {
            calls.incrementAndGet();
            return Optional.of(ToolUseTag.dim(
                input.path("value").asText() + ":" + context.toolUseId()));
        }
    }
}
