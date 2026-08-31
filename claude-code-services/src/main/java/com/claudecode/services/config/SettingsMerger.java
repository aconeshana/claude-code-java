package com.claudecode.services.config;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Immutable low-level merge operations for layered settings JSON trees.
 */
final class SettingsMerger {

    private SettingsMerger() {}

    /**
     * Deep-merges {@code override} over {@code base}: objects recurse, array pairs concatenate,
     * and all returned nodes are detached from both inputs.
     */
    static JsonNode merge(JsonNode base, JsonNode override) {
        ObjectNode result = base != null && base.isObject()
            ? ((ObjectNode) base).deepCopy()
            : JsonNodeFactory.instance.objectNode();
        if (override == null || !override.isObject()) return result;

        override.fields().forEachRemaining(entry -> {
            JsonNode baseValue = result.get(entry.getKey());
            JsonNode overrideValue = entry.getValue();
            result.set(entry.getKey(), mergeValue(baseValue, overrideValue));
        });
        return result;
    }

/** matches lodash's default deep merge when an existing array meets an object source. */
    private static JsonNode mergeValue(JsonNode base, JsonNode override) {
        JsonNode customized = customize(base, override);
        if (customized != null) return customized;
        if (base != null && base.isArray() && override.isObject()) {
            return mergeArrayWithObject((ArrayNode) base, (ObjectNode) override);
        }
        if (base != null && base.isObject() && override.isObject()) {
            return merge(base, override);
        }
        return override.deepCopy();
    }

    private static ArrayNode mergeArrayWithObject(ArrayNode base, ObjectNode override) {
        ArrayNode result = base.deepCopy();
        override.fields().forEachRemaining(entry -> {
            int index = arrayIndex(entry.getKey());
            if (index < 0) return;
            while (result.size() <= index) result.addNull();
            JsonNode current = result.get(index);
            result.set(index, mergeValue(current == null || current.isNull() ? null : current,
                entry.getValue()));
        });
        return result;
    }

    private static int arrayIndex(String key) {
        if (StringUtils.isEmpty(key) || (key.length() > 1 && key.charAt(0) == '0')) {
            return -1;
        }
        for (int i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) return -1;
        }
        try {
            long value = Long.parseLong(key);
            return value >= 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    /** Concatenates array layers, de-duplicating only primitive JSON values. */
    static ArrayNode mergeArrays(ArrayNode base, ArrayNode override) {
        ArrayNode result = JsonUtils.getMapper().createArrayNode();
        appendUnique(result, base);
        appendUnique(result, override);
        return result;
    }


    static JsonNode customize(JsonNode base, JsonNode override) {
        if (base != null && override != null && base.isArray() && override.isArray()) {
            return mergeArrays((ArrayNode) base, (ArrayNode) override);
        }
        return null;
    }

    private static void appendUnique(ArrayNode result, ArrayNode source) {
        for (JsonNode value : source) {
            if (!containsPrimitive(result, value)) {
                result.add(value.deepCopy());
            }
        }
    }

    private static boolean containsPrimitive(ArrayNode values, JsonNode candidate) {
        if (candidate.isObject() || candidate.isArray()) return false;
        if (candidate.isNumber()) {
            double target = candidate.doubleValue();
            for (JsonNode existing : values) {
                if (!existing.isNumber()) continue;
                double current = existing.doubleValue();
                // SameValueZero considers +0 and -0 equivalent; Double.compare handles equal
                // finite values and NaN consistently with the same relation.
                if ((target == 0.0d && current == 0.0d)
                        || Double.compare(target, current) == 0) {
                    return true;
                }
            }
            return false;
        }
        for (JsonNode existing : values) {
            if (existing.equals(candidate)) return true;
        }
        return false;
    }
}
