package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.Strings;

import java.util.Iterator;
import java.util.Map;

/**
 * Normalizes function input schemas for OpenAI Chat and Responses endpoints.
 */
@Explanation("OpenAI tool-schema compatibility projection")
final class OpenAiToolSchemaProjection {

    private OpenAiToolSchemaProjection() {}

    static ObjectNode project(JsonNode schema) {
        ObjectNode flattened = JsonUtils.getMapper().createObjectNode();
        if (schema != null && schema.isObject()) {
            schema.fields().forEachRemaining(field -> {
                if (!Strings.CS.equals("anyOf", field.getKey())) {
                    flattened.set(field.getKey(), field.getValue().deepCopy());
                }
            });
        }
        flattened.put("type", "object");

        JsonNode variants = schema == null ? null : schema.get("anyOf");
        if (variants != null && variants.isArray()) {
            ObjectNode properties = JsonUtils.getMapper().createObjectNode();
            for (JsonNode variant : variants) {
                JsonNode variantProperties = variant.get("properties");
                if (variantProperties == null || !variantProperties.isObject()) continue;
                variantProperties.fields().forEachRemaining(field -> {
                    if (!properties.has(field.getKey())) {
                        properties.set(field.getKey(), field.getValue().deepCopy());
                    }
                });
            }
            flattened.set("properties", properties);
            flattened.put("additionalProperties", false);
        }

        JsonNode normalized = removeNullSchemas(flattened);
        return normalized instanceof ObjectNode object ? object
            : JsonUtils.getMapper().createObjectNode().put("type", "object");
    }

    private static JsonNode removeNullSchemas(JsonNode value) {
        if (value == null || value.isNull()) return value;
        if (value.isArray()) {
            ArrayNode projected = JsonUtils.getMapper().createArrayNode();
            value.forEach(item -> projected.add(removeNullSchemas(item)));
            return projected;
        }
        if (!value.isObject()) return value.deepCopy();

        ObjectNode fields = JsonUtils.getMapper().createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> iterator = value.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> field = iterator.next();
            if (!Strings.CS.equals("anyOf", field.getKey())) {
                fields.set(field.getKey(), removeNullSchemas(field.getValue()));
            }
        }

        JsonNode anyOf = value.get("anyOf");
        if (anyOf == null || !anyOf.isArray()) return fields;

        ArrayNode variants = JsonUtils.getMapper().createArrayNode();
        for (JsonNode variant : anyOf) {
            if (variant.isObject()
                    && Strings.CS.equals("null", variant.path("type").asText())) continue;
            variants.add(removeNullSchemas(variant));
        }
        if (variants.size() == 1 && variants.get(0).isObject()) {
            variants.get(0).fields().forEachRemaining(field -> fields.set(field.getKey(), field.getValue()));
            return fields;
        }
        fields.set("anyOf", variants);
        return fields;
    }
}
