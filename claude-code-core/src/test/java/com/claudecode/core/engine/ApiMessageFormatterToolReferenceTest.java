package com.claudecode.core.engine;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolReferenceBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code tool_result} content containing a {@link ToolReferenceBlock} (produced
 * by {@code ToolSearchTool}'s rewritten {@code call}) must survive serialization
 * as a structured content array — not get silently dropped by the flatten-to-text
 * fast path that only ever collected {@link TextBlock}s. Regression test for the
 * bug found while wiring ToolSearch: before the fix, a {@code tool_result} with
 * ONLY a {@code ToolReferenceBlock} (no text) serialized to an empty string.
 */
class ApiMessageFormatterToolReferenceTest {

    private static List<StreamingClient.StreamRequest.RequestMessage> format(Message... messages) {
        return ApiMessageFormatter.toRequestMessages(List.of(messages));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstToolResultMap(StreamingClient.StreamRequest.RequestMessage rm) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) rm.content();
        return content.getFirst();
    }

    @Test
    void toolReferenceOnlyContent_serializesAsArrayNotEmptyString() {
        UserMessage um = new UserMessage("u1", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("tool-use-1", List.of(new ToolReferenceBlock("WebFetch")), false))));

        var out = format(um);

        assertEquals(1, out.size());
        assertEquals("user", out.getFirst().role());
        Map<String, Object> trMap = firstToolResultMap(out.getFirst());
        assertEquals("tool_result", trMap.get("type"));
        assertEquals("tool-use-1", trMap.get("tool_use_id"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> innerContent = (List<Map<String, Object>>) trMap.get("content");
        assertEquals(1, innerContent.size());
        assertEquals("tool_reference", innerContent.getFirst().get("type"));
        assertEquals("WebFetch", innerContent.getFirst().get("tool_name"));
    }

    @Test
    void multipleToolReferences_allSurvive() {
        UserMessage um = new UserMessage("u1", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("tool-use-1", List.of(
                new ToolReferenceBlock("WebFetch"), new ToolReferenceBlock("CronCreate")), false))));

        var out = format(um);

        Map<String, Object> trMap = firstToolResultMap(out.getFirst());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> innerContent = (List<Map<String, Object>>) trMap.get("content");
        assertEquals(2, innerContent.size());
        assertEquals("WebFetch", innerContent.getFirst().get("tool_name"));
        assertEquals("CronCreate", innerContent.get(1).get("tool_name"));
    }

    @Test
    void mixedTextAndToolReference_bothSurviveAsArray() {
        UserMessage um = new UserMessage("u1", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("tool-use-1", List.of(
                new TextBlock("No matching deferred tools found"), new ToolReferenceBlock("WebFetch")), false))));

        var out = format(um);

        Map<String, Object> trMap = firstToolResultMap(out.getFirst());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> innerContent = (List<Map<String, Object>>) trMap.get("content");
        assertEquals(2, innerContent.size());
        assertEquals("text", innerContent.getFirst().get("type"));
        assertEquals("No matching deferred tools found", innerContent.getFirst().get("text"));
        assertEquals("tool_reference", innerContent.get(1).get("type"));
    }

    @Test
    void textOnlyContent_stillFlattensToPlainString() {
        // No regression on the existing fast path: pure-text tool_result content
        // must still serialize as a plain string, not an array.
        UserMessage um = new UserMessage("u1", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("tool-use-1", List.of(new TextBlock("plain text result")), false))));

        var out = format(um);

        Map<String, Object> trMap = firstToolResultMap(out.getFirst());
        assertEquals("plain text result", trMap.get("content"));
    }

    @Test
    void agentResultPreservesTextBlockArray() {
        UserMessage um = new UserMessage("u1", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("tool-agent", List.of(new TextBlock("OK")), false, false, true))));

        Map<String, Object> trMap = firstToolResultMap(format(um).getFirst());

        assertInstanceOf(List.class, trMap.get("content"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) trMap.get("content");
        assertEquals("text", content.getFirst().get("type"));
        assertEquals("OK", content.getFirst().get("text"));
    }

    @Test
    void mcpTextContentStaysAnArrayWhenToolUseResultCarriesMcpContentBlocks() {
        var mcpContent = JsonUtils.getMapper().createArrayNode();
        mcpContent.addObject().put("type", "text").put("text", "echo:WIRE197");
        UserMessage um = new UserMessage(
            "u1",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu-mcp", List.of(new TextBlock("echo:WIRE197")), false))),
            false, false, mcpContent, MessageOrigin.USER, null, Instant.EPOCH,
            null, null);

        Map<String, Object> trMap = firstToolResultMap(format(um).getFirst());

        assertInstanceOf(List.class, trMap.get("content"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
            (List<Map<String, Object>>) trMap.get("content");
        assertEquals("text", content.getFirst().get("type"));
        assertEquals("echo:WIRE197", content.getFirst().get("text"));
    }

    @Test
    void resumedMcpTextContentStaysAnArrayAfterGenericJsonDeserialization() {
        UserMessage resumed = new UserMessage(
            "u1",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu-mcp", List.of(new TextBlock("echo:WIRE197")), false))),
            false, false,
            List.of(Map.of("type", "text", "text", "echo:WIRE197")),
            MessageOrigin.USER, null, Instant.EPOCH, null, null);

        Map<String, Object> trMap = firstToolResultMap(format(resumed).getFirst());

        assertInstanceOf(List.class, trMap.get("content"), "Jackson restores Object-typed JSON arrays as List, which must retain MCP wire shape");
    }

    @Test
    void structuredJsonArrayToolResultStillUsesReleasedScalarTextMapping() {
        var resources = JsonUtils.getMapper().createArrayNode();
        resources.addObject().put("name", "wire-list").put("uri", "wire://resource/list");
        String serialized = resources.toString();
        UserMessage um = new UserMessage(
            "u1",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu-list", List.of(new TextBlock(serialized)), false))),
            false, false, resources, MessageOrigin.USER, null, Instant.EPOCH,
            null, null);

        Map<String, Object> trMap = firstToolResultMap(format(um).getFirst());

        assertEquals(serialized, trMap.get("content"),
            "ListMcpResourcesTool has an array-shaped structured output, but its released "
                + "mapToolResultToToolResultBlockParam serializes that array to JSON text");
    }

    @Test
    void explicitFalseErrorFlagIsPreservedOnlyWhenTheToolContractRequiresIt() {
        ToolResultBlock ordinary = new ToolResultBlock(
            "ordinary", List.of(new TextBlock("ok")), false);
        ToolResultBlock explicit = new ToolResultBlock(
            "wait", List.of(new TextBlock("ready: true")), false, true);

        UserMessage um = new UserMessage("u1", MessageContent.ofBlocks(List.of(
            ordinary, explicit)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
            (List<Map<String, Object>>) format(um).getFirst().content();

        assertFalse(content.getFirst().containsKey("is_error"));
        assertEquals(false, content.get(1).get("is_error"));
        assertFalse(JsonUtils.getMapper().valueToTree(ordinary).has("is_error"),
            "ordinary successful tool results omit is_error in 2.1.197 JSONL");
        assertFalse(JsonUtils.getMapper().valueToTree(explicit).get("is_error").asBoolean(), "WaitForMcpServers explicitly persists is_error:false");
    }
}
