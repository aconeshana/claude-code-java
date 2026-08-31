package com.claudecode.core.engine;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.ServerToolResultBlock;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.WebSearchToolResultBlock;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ApiMessageFormatterAssistantReplayTest {

    @Test
    @SuppressWarnings("unchecked")
    void replaysReleased197ServerToolBlocksWithoutDroppingTheirWireFields() throws Exception {
        var searchContent = JsonUtils.parseTree("""
            [{"type":"web_search_result","title":"Result","url":"https://example.com",
              "encrypted_content":"ciphertext","page_age":"2 days"}]
            """);
        var searchResult = WebSearchToolResultBlock.fromJson("srv-search", searchContent);
        var fetchContent = JsonUtils.parseTree("""
            {"type":"web_fetch_result","url":"https://example.com/page","content":"body"}
            """);
        var assistant = new AssistantMessage("a1", AssistantContent.of("msg-1", List.of(
            new ServerToolUseBlock("srv-search", "web_search", JsonUtils.parseTree("{\"query\":\"java\"}")),
            searchResult,
            new ServerToolResultBlock("srv-fetch", "web_fetch", fetchContent, false,
                "web_fetch_tool_result"))));

        var request = ApiMessageFormatter.toRequestMessages(List.of(assistant), false).getFirst();
        var blocks = assertInstanceOf(List.class, request.content());
        Map<String, Object> searchUse = (Map<String, Object>) blocks.get(0);
        Map<String, Object> searchWireResult = (Map<String, Object>) blocks.get(1);
        Map<String, Object> fetchWireResult = (Map<String, Object>) blocks.get(2);

        assertEquals("server_tool_use", searchUse.get("type"));
        assertEquals("web_search", searchUse.get("name"));
        assertEquals("web_search_tool_result", searchWireResult.get("type"));
        var hits = assertInstanceOf(List.class, searchWireResult.get("content"));
        Map<String, Object> hit = (Map<String, Object>) hits.getFirst();
        assertEquals("ciphertext", hit.get("encrypted_content"));
        assertEquals("2 days", hit.get("page_age"));
        assertEquals("web_fetch_tool_result", fetchWireResult.get("type"));
        assertEquals("body", ((Map<String, Object>) fetchWireResult.get("content")).get("content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaysRedactedThinkingWhenThinkingHistoryIsEnabled() {
        var assistant = new AssistantMessage("a1", AssistantContent.of("msg-1", List.of(
            new RedactedThinkingBlock("encrypted-thinking"))));

        var request = ApiMessageFormatter.toRequestMessages(List.of(assistant), true).getFirst();
        Map<String, Object> block = (Map<String, Object>) ((List<?>) request.content()).getFirst();

        assertEquals("redacted_thinking", block.get("type"));
        assertEquals("encrypted-thinking", block.get("data"));
    }
}
