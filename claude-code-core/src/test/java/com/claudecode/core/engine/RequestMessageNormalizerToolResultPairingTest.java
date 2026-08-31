package com.claudecode.core.engine;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;


class RequestMessageNormalizerToolResultPairingTest {

    private static UserMessage userBlocks(String id, List<ContentBlock> blocks) {
        return new UserMessage(id, MessageContent.ofBlocks(blocks), false, false, null,
            MessageOrigin.USER, null, Instant.now(), null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blocksOf(
            StreamingClient.StreamRequest.RequestMessage message) {
        return (List<Map<String, Object>>) message.content();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> innerContent(Map<String, Object> toolResult) {
        return (List<Map<String, Object>>) toolResult.get("content");
    }

    private static Map<String, Object> block(String type, Map<String, Object> extra) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.putAll(extra);
        return map;
    }

    @Test
    void missingToolResult_noNextMessage_appendsSyntheticTailUserMessage() {
        AssistantMessage assistant = new AssistantMessage("a1", AssistantContent.of("msg-1",
            List.of(new ToolUseBlock("tu-1", "Bash", JsonNodeFactory.instance.objectNode()))));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(assistant), false, false, "claude-sonnet-4-6");

        assertEquals(List.of("assistant", "user"),
            wire.stream().map(StreamingClient.StreamRequest.RequestMessage::role).toList());
        Map<String, Object> synthetic = blocksOf(wire.getLast()).getFirst();
        assertEquals("tool_result", synthetic.get("type"));
        assertEquals("tu-1", synthetic.get("tool_use_id"));
        assertEquals(true, synthetic.get("is_error"));
        assertEquals(MessageConstants.SYNTHETIC_TOOL_RESULT_PLACEHOLDER, synthetic.get("content"));
    }

    @Test
    void missingToolResult_nextUserMessageWithoutResult_prependsSynthetic() {
        AssistantMessage assistant = new AssistantMessage("a1", AssistantContent.of("msg-1",
            List.of(new ToolUseBlock("tu-1", "Bash", JsonNodeFactory.instance.objectNode()))));
        UserMessage next = userBlocks("u1", List.of(new TextBlock("continue")));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(assistant, next), false, false, "claude-sonnet-4-6");

        assertEquals(2, wire.size());
        List<Map<String, Object>> patched = blocksOf(wire.getLast());
        assertEquals(List.of("tool_result", "text"),
            patched.stream().map(b -> b.get("type")).toList());
        assertEquals("tu-1", patched.getFirst().get("tool_use_id"));
        assertEquals("continue", patched.getLast().get("text"));
    }

    @Test
    void orphanedToolResult_atConversationStart_singleMessage_getsPlaceholder() {
        UserMessage lone = userBlocks("u1", List.of(
            new ToolResultBlock("tu-orphan", List.of(new TextBlock("stale")), false)));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(lone), false, false, "claude-sonnet-4-6");

        assertEquals(1, wire.size());
        Map<String, Object> onlyBlock = blocksOf(wire.getFirst()).getFirst();
        assertEquals("text", onlyBlock.get("type"));
        assertEquals(MessageConstants.ORPHANED_TOOL_RESULT_PLACEHOLDER, onlyBlock.get("text"));
    }

    @Test
    void orphanedToolResult_atConversationStart_mergedWithFollowingText_stripsSilently() {
        UserMessage orphanResult = userBlocks("u1", List.of(
            new ToolResultBlock("tu-orphan", List.of(new TextBlock("stale")), false)));
        UserMessage prompt = userBlocks("u2", List.of(new TextBlock("hello")));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(orphanResult, prompt), false, false, "claude-sonnet-4-6");

        assertEquals(1, wire.size());
        List<Map<String, Object>> blocks = blocksOf(wire.getFirst());
        assertEquals(1, blocks.size());
        assertEquals("text", blocks.getFirst().get("type"));
        assertEquals("hello", blocks.getFirst().get("text"));
    }

    @Test
    void duplicateToolResult_inSameUserMessage_keepsFirstOnly() {
        AssistantMessage assistant = new AssistantMessage("a1", AssistantContent.of("msg-1",
            List.of(new ToolUseBlock("tu-x", "Bash", JsonNodeFactory.instance.objectNode()))));
        UserMessage next = userBlocks("u1", List.of(
            new ToolResultBlock("tu-x", List.of(new TextBlock("first")), false, false, true),
            new ToolResultBlock("tu-x", List.of(new TextBlock("second")), false, false, true)));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(assistant, next), false, false, "claude-sonnet-4-6");

        assertEquals(2, wire.size());
        List<Map<String, Object>> patched = blocksOf(wire.getLast());
        assertEquals(1, patched.size());
        assertEquals("first", innerContent(patched.getFirst()).getFirst().get("text"));
    }

    @Test
    void crossMessageDuplicateToolUse_cascadesToInterruptedPlaceholderAndNoContentMessage() {
        AssistantMessage assistant1 = new AssistantMessage("a1", AssistantContent.of("msg-1",
            List.of(new ToolUseBlock("tu-1", "Bash", JsonNodeFactory.instance.objectNode()))));
        UserMessage result1 = userBlocks("u1", List.of(
            new ToolResultBlock("tu-1", List.of(new TextBlock("ok")), false, false, true)));
        // CC-1212: a different assistant message.id carrying the same tool_use id.
        AssistantMessage assistant2 = new AssistantMessage("a2", AssistantContent.of("msg-2",
            List.of(new ToolUseBlock("tu-1", "Bash", JsonNodeFactory.instance.objectNode()))));
        UserMessage result2 = userBlocks("u2", List.of(
            new ToolResultBlock("tu-1", List.of(new TextBlock("ok2")), false, false, true)));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(assistant1, result1, assistant2, result2), false, false, "claude-sonnet-4-6");

        assertEquals(List.of("assistant", "user", "assistant", "user"),
            wire.stream().map(StreamingClient.StreamRequest.RequestMessage::role).toList());
        assertEquals("tool_use", blocksOf(wire.getFirst()).getFirst().get("type"));
        assertEquals("ok", innerContent(blocksOf(wire.get(1)).getFirst()).getFirst().get("text"));
        Map<String, Object> interrupted = blocksOf(wire.get(2)).getFirst();
        assertEquals("text", interrupted.get("type"));
        assertEquals(MessageConstants.TOOL_USE_INTERRUPTED_PLACEHOLDER, interrupted.get("text"));
        Map<String, Object> noContent = blocksOf(wire.get(3)).getFirst();
        assertEquals(MessageConstants.NO_CONTENT_MESSAGE, noContent.get("text"));
    }

    @Test
    void thinkingFlankedDuplicateToolUse_substitutesRemovedPlaceholder() {
        AssistantMessage seed = new AssistantMessage("seed", AssistantContent.of("msg-seed",
            List.of(new ToolUseBlock("tu-dup", "Bash", JsonNodeFactory.instance.objectNode()))));
        UserMessage seedResult = userBlocks("seed-result", List.of(
            new ToolResultBlock("tu-dup", List.of(new TextBlock("first")), false)));
        AssistantMessage flanked = new AssistantMessage("flanked", AssistantContent.of("msg-flanked",
            List.of(new ThinkingBlock("before"),
                new ToolUseBlock("tu-dup", "Bash", JsonNodeFactory.instance.objectNode()),
                new ThinkingBlock("after"))));
        UserMessage tail = userBlocks("tail", List.of(new TextBlock("continue")));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(seed, seedResult, flanked, tail), true, false, "claude-sonnet-4-6");

        List<Map<String, Object>> flankedBlocks = blocksOf(wire.get(2));
        assertEquals(List.of("thinking", "text", "thinking"),
            flankedBlocks.stream().map(b -> b.get("type")).toList());
        assertEquals("before", flankedBlocks.getFirst().get("thinking"));
        assertEquals(MessageConstants.TOOL_USE_REMOVED_PLACEHOLDER, flankedBlocks.get(1).get("text"));
        assertEquals(List.of(), flankedBlocks.get(1).get("citations"));
        assertEquals("after", flankedBlocks.get(2).get("thinking"));
    }

    @Test
    void orphanedServerToolUse_withoutMatchingResult_isStrippedFromSameMessage() {
        Map<String, Object> serverToolUse = block("server_tool_use",
            Map.of("id", "stu-1", "name", "web_search"));
        Map<String, Object> text = block("text", Map.of("text", "thinking about it"));
        var assistant = new StreamingClient.StreamRequest.RequestMessage(
            "assistant", List.of(text, serverToolUse));

        var result = RequestMessageNormalizer.ensureToolResultPairing(List.of(assistant));

        assertEquals(1, result.size());
        List<Map<String, Object>> content = blocksOf(result.getFirst());
        assertEquals(1, content.size());
        assertEquals("text", content.getFirst().get("type"));
        assertEquals("thinking about it", content.getFirst().get("text"));
    }

    @Test
    void serverToolUse_withMatchingResultInSameMessage_isKept() {
        Map<String, Object> serverToolUse = block("server_tool_use",
            Map.of("id", "stu-1", "name", "web_search"));
        Map<String, Object> serverToolResult = block("server_tool_result",
            Map.of("tool_use_id", "stu-1", "content", "results"));
        var assistant = new StreamingClient.StreamRequest.RequestMessage(
            "assistant", List.of(serverToolUse, serverToolResult));

        var result = RequestMessageNormalizer.ensureToolResultPairing(List.of(assistant));

        assertSame(assistant, result.getFirst());
    }
}
