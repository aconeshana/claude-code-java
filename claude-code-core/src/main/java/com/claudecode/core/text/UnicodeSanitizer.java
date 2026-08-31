package com.claudecode.core.text;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.Iterator;
import java.util.Map;

/**
 * Removes Unicode characters that can hide or reorder model-visible text.
 */
public final class UnicodeSanitizer {

    private static final int MAX_ITERATIONS = 10;

    private UnicodeSanitizer() {}

    public static String sanitize(String input) {
        if (input == null) return null;
        String current = input;
        String previous = null;
        int iterations = 0;
        while (!current.equals(previous) && iterations < MAX_ITERATIONS) {
            previous = current;
            String normalized = Normalizer.normalize(current, Normalizer.Form.NFKC);
            StringBuilder safe = new StringBuilder(normalized.length());
            normalized.codePoints().forEach(codePoint -> {
                int type = Character.getType(codePoint);
                if (type != Character.FORMAT
                        && type != Character.PRIVATE_USE
                        && type != Character.UNASSIGNED) {
                    safe.appendCodePoint(codePoint);
                }
            });
            current = safe.toString();
            iterations++;
        }
        if (iterations >= MAX_ITERATIONS && !current.equals(previous)) {
            String preview = input.length() <= 100 ? input : input.substring(0, 100);
            throw new IllegalArgumentException(
                "Unicode sanitization reached maximum iterations ("
                    + MAX_ITERATIONS + ") for input: " + preview);
        }
        return current;
    }

    /**
     * Recursively sanitizes JSON string values and object keys while preserving numbers, booleans and
     * null.
     */
    public static JsonNode sanitize(JsonNode value) {
        if (value == null || value.isNull() || value.isNumber() || value.isBoolean()) return value;
        if (value.isTextual()) return JsonUtils.getMapper().getNodeFactory().textNode(sanitize(value.textValue()));
        if (value.isArray()) {
            ArrayNode out = JsonUtils.getMapper().createArrayNode();
            value.forEach(item -> out.add(sanitize(item)));
            return out;
        }
        if (value.isObject()) {
            ObjectNode out = JsonUtils.getMapper().createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.set(sanitize(field.getKey()), sanitize(field.getValue()));
            }
            return out;
        }
        return value.deepCopy();
    }
}
