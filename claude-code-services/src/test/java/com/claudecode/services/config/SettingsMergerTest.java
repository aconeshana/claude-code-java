package com.claudecode.services.config;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior tests for the immutable settings merge used by the layered settings loader.
 */
class SettingsMergerTest {

    @Test
    void mergeRecursesObjectsConcatenatesPrimitiveArraysAndDetachesInputs() throws Exception {
        ObjectNode base = (ObjectNode) JsonUtils.getMapper().readTree("""
            {
              "model": "base-model",
              "nested": {
                "fromBase": true,
                "shared": "base",
                "values": [1, "same", false, null, 0]
              }
            }
            """);
        ObjectNode override = (ObjectNode) JsonUtils.getMapper().readTree("""
            {
              "model": "override-model",
              "nested": {
                "shared": "override",
                "fromOverride": true,
                "values": [1.0, "same", false, null, -0.0, 2]
              }
            }
            """);

        ObjectNode merged = (ObjectNode) SettingsMerger.merge(base, override);

        assertEquals(JsonUtils.getMapper().readTree("""
            {
              "model": "override-model",
              "nested": {
                "fromBase": true,
                "shared": "override",
                "fromOverride": true,
                "values": [1, "same", false, null, 0, 2]
              }
            }
            """), merged);

        base.withObject("nested").put("fromBase", false);
        override.withObject("nested").put("fromOverride", false);
        ((ArrayNode) override.path("nested").path("values")).add("late-input-change");

        assertTrue(merged.path("nested").path("fromBase").asBoolean());
        assertTrue(merged.path("nested").path("fromOverride").asBoolean());
        assertEquals(6, merged.path("nested").path("values").size());
    }

    @Test
    void mergeArraysDoesNotStructurallyDeduplicateSeparateObjectsOrArrays() throws Exception {
        ArrayNode base = (ArrayNode) JsonUtils.getMapper().readTree("""
            [{"matcher":"same"}, ["command"]]
            """);
        ArrayNode override = (ArrayNode) JsonUtils.getMapper().readTree("""
            [{"matcher":"same"}, ["command"]]
            """);

        ArrayNode merged = SettingsMerger.mergeArrays(base, override);

        assertEquals(4, merged.size());
        assertEquals(base.get(0), merged.get(0));
        assertEquals(base.get(1), merged.get(1));
        assertEquals(override.get(0), merged.get(2));
        assertEquals(override.get(1), merged.get(3));

        ((ObjectNode) base.get(0)).put("matcher", "changed");
        ((ArrayNode) override.get(1)).add("changed");
        assertEquals("same", merged.get(0).path("matcher").asText());
        assertEquals(1, merged.get(3).size());
    }

    @Test
    void customizeOnlyHandlesPairsOfArrays() throws Exception {
        JsonNode object = JsonUtils.getMapper().readTree("{\"nested\":true}");
        JsonNode array = JsonUtils.getMapper().readTree("[\"base\"]");
        JsonNode override = JsonUtils.getMapper().readTree("[\"override\"]");

        assertNull(SettingsMerger.customize(object, object));
        assertNull(SettingsMerger.customize(array, object));

        JsonNode customized = SettingsMerger.customize(array, override);
        assertEquals(JsonUtils.getMapper().readTree("[\"base\",\"override\"]"), customized);
    }

    @Test
    void arrayBaseAndObjectOverrideKeepArrayAndMergeNumericIndexes() throws Exception {
        JsonNode base = JsonUtils.getMapper().readTree(
            "{\"unknown\":[{\"fromBase\":true},2],\"untouched\":true}");
        JsonNode override = JsonUtils.getMapper().readTree(
            "{\"unknown\":{\"0\":{\"fromOverride\":true},\"3\":\"new\",\"label\":\"ignored-by-json\"}} ");

        JsonNode merged = SettingsMerger.merge(base, override);

        assertEquals(JsonUtils.getMapper().readTree(
            "{\"unknown\":[{\"fromBase\":true,\"fromOverride\":true},2,null,\"new\"],\"untouched\":true}"),
            merged);
    }
}
