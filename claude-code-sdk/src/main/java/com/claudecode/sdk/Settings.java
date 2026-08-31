package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Lossless SDK settings object that preserves unknown keys.
generated open {@code Settings} type.</li></ul>
 */
public record Settings(ObjectNode value) {
    public Settings {
        value = value == null ? JsonUtils.getMapper().createObjectNode() : value.deepCopy();
    }

    public static Settings of(Map<String, ?> values) {
        JsonNode node = JsonUtils.getMapper().valueToTree(values == null ? Map.of() : values);
        return new Settings(node.isObject() ? (ObjectNode) node : JsonUtils.getMapper().createObjectNode());
    }

    @Override public ObjectNode value() { return value.deepCopy(); }
}
