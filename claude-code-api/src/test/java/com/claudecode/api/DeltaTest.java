package com.claudecode.api;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Delta types serialization/deserialization.
 */
class DeltaTest {

    @Test
    void textDeltaSerializesCorrectly() {
        Delta.TextDelta delta = new Delta.TextDelta("Hello");
        String json = JsonUtils.toJson(delta);

        assertTrue(Strings.CS.contains(json, "\"text\":\"Hello\""));
        assertTrue(Strings.CS.contains(json, "\"type\":\"text_delta\""));
    }

    @Test
    void inputJsonDeltaSerializesCorrectly() {
        Delta.InputJsonDelta delta = new Delta.InputJsonDelta("{\"key\": \"value\"}");
        String json = JsonUtils.toJson(delta);

        assertTrue(Strings.CS.contains(json, "\"partial_json\""));
        assertTrue(Strings.CS.contains(json, "\"type\":\"input_json_delta\""));
    }

    @Test
    void thinkingDeltaSerializesCorrectly() {
        Delta.ThinkingDelta delta = new Delta.ThinkingDelta("Let me think...");
        String json = JsonUtils.toJson(delta);

        assertTrue(Strings.CS.contains(json, "\"thinking\":\"Let me think...\""));
        assertTrue(Strings.CS.contains(json, "\"type\":\"thinking_delta\""));
    }

    @Test
    void textDeltaDeserializes() {
        String json = """
                {"type": "text_delta", "text": "world"}
                """;
        Delta delta = JsonUtils.fromJson(json, Delta.class);
        assertInstanceOf(Delta.TextDelta.class, delta);
        assertEquals("world", ((Delta.TextDelta) delta).text());
    }

    @Test
    void inputJsonDeltaDeserializes() {
        String json = """
                {"type": "input_json_delta", "partial_json": "{\\"cmd\\": \\"ls\\"}"}
                """;
        Delta delta = JsonUtils.fromJson(json, Delta.class);
        assertInstanceOf(Delta.InputJsonDelta.class, delta);
    }

    @Test
    void thinkingDeltaDeserializes() {
        String json = """
                {"type": "thinking_delta", "thinking": "reasoning..."}
                """;
        Delta delta = JsonUtils.fromJson(json, Delta.class);
        assertInstanceOf(Delta.ThinkingDelta.class, delta);
        assertEquals("reasoning...", ((Delta.ThinkingDelta) delta).thinking());
    }
}
