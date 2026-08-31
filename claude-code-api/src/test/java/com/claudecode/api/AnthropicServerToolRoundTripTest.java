package com.claudecode.api;

import com.claudecode.core.message.ServerToolResultBlock;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * <ul>
 *   <li>OpenCode  provider-executed server-tool round trip.</li>
 * </ul>
 */
class AnthropicServerToolRoundTripTest {

    @Test
    void echoesCanonicalServerToolCallAndResultAsNativeAnthropicBlocks() throws Exception {
        var content = JsonUtils.parseTree("{\"type\":\"code_execution_result\",\"stdout\":\"2\\n\"}");
        var request = CreateMessageRequest.builder()
            .model("claude-test")
            .messages(List.of(new CreateMessageRequest.RequestMessage("assistant", List.of(
                new ServerToolUseBlock("srv_1", "code_execution",
                    JsonUtils.parseTree("{\"code\":\"print(1+1)\"}")),
                new ServerToolResultBlock("srv_1", "code_execution", content, false,
                    "code_execution_tool_result")))))
            .stream(false)
            .promptCachingEnabled(false)
            .build();

        String json = AnthropicSdkClient.serializeWithCacheControl(request);
        var block = JsonUtils.parseTree(json)
            .path("messages").get(0).path("content").get(1);
        assertEquals("code_execution_tool_result", block.path("type").asText(), json);
        assertEquals("srv_1", block.path("tool_use_id").asText());
        assertEquals("2\n", block.path("content").path("stdout").asText());
        assertFalse(block.has("name"));
        assertFalse(block.has("provider_type"));
        assertFalse(block.has("is_error"));
    }
}
