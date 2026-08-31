package com.claudecode.api;

import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <ul>
 *   <li>OpenCode  OpenAI projection cases.</li>
 * </ul>
 */
class OpenAiToolSchemaProjectionTest {

    @Test
    void flattensTopLevelObjectUnionAndRemovesNullableBranches() {
        var schema = JsonUtils.parseTree("""
            {
              "anyOf": [
                {
                  "type": "object",
                  "properties": {
                    "path": {"type": "string"},
                    "maybe": {"anyOf": [{"type": "string"}, {"type": "null"}]}
                  }
                },
                {"type": "object", "properties": {"resource": {"type": "string"}}}
              ]
            }
            """);

        assertEquals(JsonUtils.parseTree("""
            {
              "type": "object",
              "properties": {
                "path": {"type": "string"},
                "maybe": {"type": "string"},
                "resource": {"type": "string"}
              },
              "additionalProperties": false
            }
            """), OpenAiToolSchemaProjection.project(schema));
    }

    @Test
    void makesNonUnionSchemaAnObjectWithoutDroppingFields() {
        var schema = JsonUtils.parseTree("""
            {"description":"input","properties":{"query":{"type":"string"}},"required":["query"]}
            """);

        assertEquals(JsonUtils.parseTree("""
            {"description":"input","properties":{"query":{"type":"string"}},"required":["query"],"type":"object"}
            """), OpenAiToolSchemaProjection.project(schema));
    }

    @Test
    void validatesOpenAiImageMimeBase64AndDataUrlConsistency() {
        var unsupported = JsonUtils.parseTree(
            "{\"type\":\"base64\",\"media_type\":\"image/svg+xml\",\"data\":\"PHN2Zz4=\"}");
        assertEquals("OpenAI does not support image media type image/svg+xml",
            Assertions.assertThrows(ApiException.class,
                () -> OpenAiWireSupport.imageUrl(unsupported)).getMessage());

        var malformed = JsonUtils.parseTree(
            "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"not-base64\"}");
        Assertions.assertThrows(ApiException.class,
            () -> OpenAiWireSupport.imageUrl(malformed));

        var mismatch = JsonUtils.parseTree(
            "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"data:image/jpeg;base64,/9j/\"}");
        Assertions.assertThrows(ApiException.class,
            () -> OpenAiWireSupport.imageUrl(mismatch));

        var valid = JsonUtils.parseTree(
            "{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\"data:image/jpeg;base64,/9j/\"}");
        assertEquals("data:image/jpeg;base64,/9j/", OpenAiWireSupport.imageUrl(valid));
    }
}
