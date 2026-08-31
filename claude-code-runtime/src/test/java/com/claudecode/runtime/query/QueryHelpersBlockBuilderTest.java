package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;

import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.WebSearchToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * <ul>
 *   <li>preserves delta-built
 *       {@code server_tool_use} and complete provider tool-result blocks.</li>
 *   <li>preserves
 *       the Read-reminder seam of an at-mentioned image attachment.</li>
 * </ul>
 */
class QueryHelpersBlockBuilderTest {

    @Test
    void buildsServerToolUseFromInputJsonDeltas() {
        var builder = new QueryHelpers.BlockBuilder("server_tool_use", "srv_1", "web_search");
        builder.inputJson.append("{\"query\":\"OpenCode\"}");

        var block = (ServerToolUseBlock) builder.build();

        assertEquals("srv_1", block.id());
        assertEquals("web_search", block.name());
        assertEquals("OpenCode", block.input().path("query").asText());
    }

    @Test
    void preservesCompleteProviderToolResultBlock() {
        var complete = new WebSearchToolResultBlock("srv_1",
            List.of(new WebSearchToolResultBlock.Hit("OpenCode", "https://opencode.ai")), null);

        assertSame(complete,
            new QueryHelpers.BlockBuilder("web_search_tool_result", null, null, complete).build());
    }

    @Test
    void atMentionedImageKeepsReadReminderSeparateAndMovesImageBeforeInventory() {
        Map<String, Object> inventory = text("inventory");
        Map<String, Object> reminder = text(
            """
            <system-reminder>
            Called the Read tool with the following input: \
            {"file_path":"/tmp/wire.png"}
            </system-reminder>""");
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("type", "image");
        image.put("source", Map.of("type", "base64", "data", "AA=="));
        Map<String, Object> prompt = text("inspect @wire.png");

        var actual = QueryHelpers.preserveAtMentionedImageReadSeam(List.of(
            new StreamingClient.StreamRequest.RequestMessage(
                "user", List.of(inventory, reminder, image, prompt))));

        assertEquals(2, actual.size());
        assertEquals(reminder.get("text"), actual.getFirst().content());
        assertEquals(List.of(image, inventory, prompt), actual.getLast().content());
    }

    private static Map<String, Object> text(String value) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", value);
        return block;
    }
}
