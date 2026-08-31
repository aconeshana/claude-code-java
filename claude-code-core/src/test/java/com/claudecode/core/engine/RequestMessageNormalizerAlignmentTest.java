package com.claudecode.core.engine;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolReferenceBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RequestMessageNormalizerAlignmentTest {

    private static UserMessage userBlocks(String id, List<ContentBlock> blocks) {
        return new UserMessage(id, MessageContent.ofBlocks(blocks), false, false, null,
            MessageOrigin.USER, null, Instant.now(), null, null);
    }

    /**
     * A preceding assistant {@code tool_use} turn for the given id, so a
     * following bare {@code ToolResultBlock} user message is not treated as an
     * orphaned tool_result by {@link RequestMessageNormalizer#ensureToolResultPairing}
     * (that pass only pairs against an immediately-preceding assistant turn).
     */
    private static AssistantMessage assistantToolUse(String messageId, String toolUseId) {
        return new AssistantMessage(messageId, AssistantContent.of(List.of(
            new ToolUseBlock(toolUseId, "Bash", JsonNodeFactory.instance.objectNode()))));
    }

    private static Map<String, Object> firstBlock(
            StreamingClient.StreamRequest.RequestMessage message) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) message.content();
        return blocks.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> innerBlocks(
            StreamingClient.StreamRequest.RequestMessage message) {
        Map<String, Object> result = firstBlock(message);
        return (List<Map<String, Object>>) result.get("content");
    }

    @Test
    void enabledToolSearch_filtersOnlyUnavailableReferencesAndUsesPlaceholder() {
        AssistantMessage toolUse = assistantToolUse("a1", "tu-1");
        UserMessage result = userBlocks("u1", List.of(new ToolResultBlock(
            "tu-1", List.of(new ToolReferenceBlock("Available"),
                new ToolReferenceBlock("Gone")), false, false, true)));

        var filtered = RequestMessageNormalizer.normalizeForApi(
            List.of(toolUse, result), false, true, "claude-sonnet-4-6", Set.of("Available"));
        assertEquals(List.of("Available"), innerBlocks(filtered.getLast()).stream()
            .map(block -> block.get("tool_name"))
            .toList());

        var allGone = RequestMessageNormalizer.normalizeForApi(
            List.of(toolUse, result), false, true, "claude-sonnet-4-6", Set.of());
        assertEquals("text", innerBlocks(allGone.getLast()).getFirst().get("type"));
        assertEquals("[Tool references removed - tools no longer available]",
            innerBlocks(allGone.getLast()).getFirst().get("text"));
    }

    @Test
    void assistantToolUse_canonicalizesInputAndCallerDependsOnToolSearch() {
        ObjectNode historicalInput = JsonNodeFactory.instance.objectNode();
        historicalInput.put("old_string", "old");
        historicalInput.put("new_string", "new");
        historicalInput.put("replace_all", true);
        historicalInput.putObject("edits").put("legacy", true);
        ObjectNode caller = JsonNodeFactory.instance.objectNode().put("type", "direct");
        AssistantMessage assistant = new AssistantMessage("a1", AssistantContent.of(List.of(
            new ToolUseBlock("tu-1", "LegacyEdit", historicalInput, caller))));

        var off = RequestMessageNormalizer.normalizeForApi(
            List.of(assistant), false, false, "claude-sonnet-4-6", Set.of(),
            Map.of("LegacyEdit", "Edit"));
        Map<String, Object> offTool = firstBlock(off.getFirst());
        assertEquals("Edit", offTool.get("name"));
        assertFalse(offTool.containsKey("caller"));
        @SuppressWarnings("unchecked")
        Map<String, Object> offInput = JsonUtils.getMapper()
            .convertValue(offTool.get("input"), Map.class);
        assertFalse(offInput.containsKey("old_string"));
        assertFalse(offInput.containsKey("new_string"));
        assertFalse(offInput.containsKey("replace_all"));
        assertTrue(offInput.containsKey("edits"));

        var on = RequestMessageNormalizer.normalizeForApi(
            List.of(assistant), false, true, "claude-sonnet-4-6", Set.of("Edit"),
            Map.of("LegacyEdit", "Edit"));
        Map<String, Object> onTool = firstBlock(on.getFirst());
        assertEquals("Edit", onTool.get("name"));
        assertNotNull(onTool.get("caller"));
    }

    @Test
    void assistantToolUse_normalizesTheReleased197LegacyToolNames() {
        AssistantMessage assistant = new AssistantMessage("a1", AssistantContent.of(List.of(
            new ToolUseBlock("tu-1", "KillBash", JsonNodeFactory.instance.objectNode(), null),
            new ToolUseBlock("tu-2", "ReadMcpResource", JsonNodeFactory.instance.objectNode(), null))));

        var normalized = RequestMessageNormalizer.normalizeForApi(
            List.of(assistant), false, false, "claude-sonnet-4-6", Set.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) normalized.getFirst().content();
        assertEquals(List.of("TaskStop", "ReadMcpResourceTool"),
            blocks.stream().map(block -> block.get("name")).toList());
    }

    @Test
    void sendMessageObservableAliasesStayOutOfSubsequentApiRequests() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", "researcher");
        input.put("summary", "assign task");
        input.put("message", "start now");
        input.put("type", "message");
        input.put("recipient", "researcher");
        input.put("content", "start now");
        AssistantMessage assistant = new AssistantMessage(
            "a-send", AssistantContent.of(List.of(
                new ToolUseBlock("tu-send", "SendMessage", input))));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(assistant), false, false, "claude-sonnet-4-6");
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = JsonUtils.getMapper().convertValue(
            firstBlock(wire.getFirst()).get("input"), Map.class);

        assertEquals("researcher", normalized.get("to"));
        assertEquals("start now", normalized.get("message"));
        assertFalse(normalized.containsKey("type"));
        assertFalse(normalized.containsKey("recipient"));
        assertFalse(normalized.containsKey("content"));
    }

    @Test
    void errorToolResult_keepsTextAndDropsImageAndDocument() {
        ObjectNode imageSource = JsonNodeFactory.instance.objectNode().put("type", "base64");
        ObjectNode documentSource = JsonNodeFactory.instance.objectNode().put("type", "base64");
        AssistantMessage toolUse = assistantToolUse("a1", "tu-1");
        UserMessage error = userBlocks("u1", List.of(new ToolResultBlock(
            "tu-1", List.of(new TextBlock("first"), new ImageBlock(imageSource),
                new DocumentBlock(documentSource), new TextBlock("second")),
            true, true, true)));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(toolUse, error), false, false, "claude-sonnet-4-6");
        Map<String, Object> toolResult = firstBlock(wire.getLast());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) toolResult.get("content");
        assertEquals(1, content.size());
        assertEquals("text", content.getFirst().get("type"));
        assertEquals("first\n\nsecond", content.getFirst().get("text"));
    }

    @Test
    void toolReferenceTextSibling_movesToNextPlainToolResultTurn() {
        UserMessage source = userBlocks("source", List.of(
            new ToolResultBlock("tu-1", List.of(new ToolReferenceBlock("Loaded")),
                false, false, true),
            new TextBlock("reminder sibling")));
        AssistantMessage boundary = new AssistantMessage("a1", AssistantContent.of(
            List.of(new TextBlock("ack"))));
        UserMessage target = userBlocks("target", List.of(
            new ToolResultBlock("tu-2", List.of(new TextBlock("done")), false)));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(source, boundary, target), false, true, "claude-sonnet-4-6",
            Set.of("Loaded"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceBlocks =
            (List<Map<String, Object>>) wire.getFirst().content();
        assertEquals(1, sourceBlocks.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targetBlocks =
            (List<Map<String, Object>>) wire.getLast().content();
        assertEquals("reminder sibling", targetBlocks.getLast().get("text"));
    }

    @Test
    void thinkingPostPasses_followReleasedOrder() {
        AssistantMessage whitespaceThenThinking = new AssistantMessage(
            "a-whitespace", AssistantContent.of("msg-whitespace",
                List.of(new TextBlock("\n\n"), new ThinkingBlock("internal"))));
        AssistantMessage emptyNonFinal = new AssistantMessage(
            "a-empty", AssistantContent.of("msg-empty", List.of()));
        UserMessage next = userBlocks("u1", List.of(new TextBlock("next")));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(emptyNonFinal, next, whitespaceThenThinking), true, false,
            "claude-sonnet-4-6");

        // trailing-thinking runs first; otherwise the whitespace-only assistant
        // would survive the whitespace filter as a thinking-bearing message.
        assertEquals(List.of("assistant", "user"), wire.stream()
            .map(StreamingClient.StreamRequest.RequestMessage::role).toList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repaired =
            (List<Map<String, Object>>) wire.getFirst().content();
        assertEquals("(no content)", repaired.getFirst().get("text"));
    }

    @Test
    void orphanedThinking_isDropped_butMatchingSiblingIsRetained() {
        AssistantMessage orphan = new AssistantMessage(
            "orphan", AssistantContent.of("orphan-id", List.of(new ThinkingBlock("drop"))));
        AssistantMessage thinkingSibling = new AssistantMessage(
            "thinking", AssistantContent.of("shared-id", List.of(new ThinkingBlock("keep"))));
        AssistantMessage textSibling = new AssistantMessage(
            "text", AssistantContent.of("shared-id", List.of(new TextBlock("answer"))));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(orphan, thinkingSibling, textSibling), true, false,
            "claude-sonnet-4-6");

        assertEquals(1, wire.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) wire.getFirst().content();
        assertEquals(List.of("thinking", "text"),
            blocks.stream().map(block -> block.get("type")).toList());
        assertEquals("keep", blocks.getFirst().get("thinking"));
    }

    @Test
    void finalAssistant_trailingThinkingIsRemoved() {
        AssistantMessage onlyThinking = new AssistantMessage(
            "a1", AssistantContent.of("msg-1",
                List.of(new TextBlock("answer"), new ThinkingBlock("internal"))));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(onlyThinking), true, false, "claude-sonnet-4-6");

        assertEquals(1, wire.size());
        assertEquals("text", firstBlock(wire.getFirst()).get("type"));
        assertEquals("answer", firstBlock(wire.getFirst()).get("text"));
    }
}
