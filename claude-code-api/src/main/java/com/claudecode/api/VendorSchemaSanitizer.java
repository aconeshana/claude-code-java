package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Vendor-specific JSON Schema normalizers for DeepSeek (ECMAScript {@code \0} regex escape),
 * Kimi/Moonshot (untyped {@code enum}/{@code const} schemas), and Google Gemini / Vertex AI
 * (OpenAPI uppercase types, boolean schemas, {@code x-*} vendor annotations, and untyped schemas).
 *
 * <p>Replicated from {@code yetone/magpie} ({@code deepseek_schema.go}, {@code kimi_schema.go},
 * and {@code gemini_schema.go}).
 */
@Explanation("Vendor-specific JSON Schema normalization for DeepSeek, Moonshot/Kimi, and Gemini")
final class VendorSchemaSanitizer {

    private static final Set<String> SCHEMA_MAP_CONTAINERS = Set.of(
        "properties", "$defs", "definitions", "dependentSchemas", "patternProperties"
    );
    private static final Set<String> SCHEMA_CHILD_KEYWORDS = Set.of(
        "items", "additionalProperties", "contains", "propertyNames", "not", "if", "then", "else",
        "allOf", "anyOf", "oneOf", "prefixItems", "unevaluatedProperties", "unevaluatedItems",
        "contentSchema"
    );
    private static final Set<String> BOOLEAN_ALLOWED_KEYWORDS = Set.of(
        "additionalProperties", "unevaluatedProperties", "unevaluatedItems"
    );

    private VendorSchemaSanitizer() {}

    static JsonNode sanitizeToolSchema(JsonNode schema, String baseUrl, String model) {
        if (schema == null || !schema.isObject()) return schema;
        JsonNode current = schema.deepCopy();
        if (isDeepSeekEndpoint(baseUrl, model)) {
            current = normalizeDeepSeekPatterns(current);
        }
        if (isKimiEndpoint(baseUrl, model)) {
            current = normalizeKimiEnumTypes(current);
        }
        if (GeminiThoughtSignature.isGeminiCompatEndpoint(baseUrl, model)) {
            current = normalizeForGemini(current);
        }
        return current;
    }

    static boolean isDeepSeekEndpoint(String baseUrl, String model) {
        return (StringUtils.isNotBlank(baseUrl) && Strings.CI.contains(baseUrl, "deepseek"))
            || (StringUtils.isNotBlank(model) && Strings.CI.contains(model, "deepseek"));
    }

    static boolean isKimiEndpoint(String baseUrl, String model) {
        if (StringUtils.isNotBlank(baseUrl)) {
            String lower = baseUrl.toLowerCase(Locale.ROOT);
            if (Strings.CS.contains(lower, "moonshot") || Strings.CS.contains(lower, "kimi")) {
                return true;
            }
        }
        if (StringUtils.isNotBlank(model)) {
            String lower = model.toLowerCase(Locale.ROOT);
            int slash = lower.lastIndexOf('/');
            String leaf = slash >= 0 ? lower.substring(slash + 1) : lower;
            return Strings.CS.startsWith(leaf, "kimi") || Strings.CS.startsWith(leaf, "moonshot");
        }
        return false;
    }

    /**
     * Converts DeepSeek-rejected ECMAScript {@code \0} escapes (not followed by {@code 0-9})
     * inside schema {@code pattern} fields to Unicode {@code \u0000}.
     */
    static JsonNode normalizeDeepSeekPatterns(JsonNode schema) {
        if (schema == null || !schema.isObject()) return schema;
        ObjectNode out = (ObjectNode) schema.deepCopy();
        walkDeepSeekPatterns(out);
        return out;
    }

    static String unicodeNullEscape(String pattern) {
        if (StringUtils.isEmpty(pattern) || !Strings.CS.contains(pattern, "\\0")) {
            return pattern;
        }
        StringBuilder out = new StringBuilder(pattern.length() + 8);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                char next = pattern.charAt(i + 1);
                if (next == '\\') {
                    out.append("\\\\");
                    i++;
                    continue;
                }
                if (next == '0' && (i + 2 >= pattern.length()
                        || pattern.charAt(i + 2) < '0' || pattern.charAt(i + 2) > '9')) {
                    out.append("\\u0000");
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static void walkDeepSeekPatterns(JsonNode node) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode child : node) walkDeepSeekPatterns(child);
            return;
        }
        if (!node.isObject()) return;
        ObjectNode obj = (ObjectNode) node;
        if (obj.has("pattern") && obj.get("pattern").isTextual()) {
            String fixed = unicodeNullEscape(obj.get("pattern").asText());
            obj.put("pattern", fixed);
        }
        for (String mapKey : SCHEMA_MAP_CONTAINERS) {
            JsonNode mapNode = obj.get(mapKey);
            if (mapNode != null && mapNode.isObject()) {
                mapNode.fields().forEachRemaining(entry -> walkDeepSeekPatterns(entry.getValue()));
            }
        }
        for (String childKey : SCHEMA_CHILD_KEYWORDS) {
            if (obj.has(childKey)) {
                walkDeepSeekPatterns(obj.get(childKey));
            }
        }
    }

    /**
     * Infers missing {@code type} on schemas carrying {@code enum} or {@code const} for
     * Moonshot/Kimi ("moonshot flavored json schema" rejects untyped enums with HTTP 400).
     */
    static JsonNode normalizeKimiEnumTypes(JsonNode schema) {
        if (schema == null || !schema.isObject()) return schema;
        ObjectNode out = (ObjectNode) schema.deepCopy();
        walkKimiEnums(out);
        return out;
    }

    private static void walkKimiEnums(JsonNode node) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode child : node) walkKimiEnums(child);
            return;
        }
        if (!node.isObject()) return;
        ObjectNode obj = (ObjectNode) node;
        if (!obj.has("type") && (obj.has("enum") || obj.has("const"))) {
            String kwType = keywordType(obj);
            if (Strings.CS.equals("object", kwType) || Strings.CS.equals("array", kwType)) {
                obj.put("type", kwType);
            } else {
                JsonNode inferred = inferKimiEnumTypeNode(obj);
                if (inferred != null) {
                    prependTypeField(obj, inferred);
                }
            }
        }
        for (String mapKey : SCHEMA_MAP_CONTAINERS) {
            JsonNode mapNode = obj.get(mapKey);
            if (mapNode != null && mapNode.isObject()) {
                mapNode.fields().forEachRemaining(entry -> walkKimiEnums(entry.getValue()));
            }
        }
        for (String childKey : SCHEMA_CHILD_KEYWORDS) {
            if (obj.has(childKey)) {
                walkKimiEnums(obj.get(childKey));
            }
        }
    }

    private static JsonNode inferKimiEnumTypeNode(ObjectNode schema) {
        ArrayNode values = JsonUtils.getMapper().createArrayNode();
        if (schema.has("enum") && schema.get("enum").isArray()) {
            schema.get("enum").forEach(values::add);
        } else if (schema.has("const")) {
            values.add(schema.get("const"));
        }
        if (values.isEmpty()) return null;
        boolean sawNull = false;
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode v : values) {
            if (v == null || v.isNull()) {
                sawNull = true;
            } else {
                String t = scalarJsonType(v);
                if (t == null) return null;
                types.add(t);
            }
        }
        if (types.contains("integer") && types.contains("number")) {
            types.remove("integer");
        }
        if (types.size() != 1) return null;
        String baseType = types.iterator().next();
        if (!sawNull) {
            return JsonUtils.getMapper().getNodeFactory().textNode(baseType);
        }
        ArrayNode pair = JsonUtils.getMapper().createArrayNode();
        pair.add(baseType);
        pair.add("null");
        return pair;
    }

    /**
     * Sanitizes a JSON Schema for Google Gemini / Vertex AI:
     * strips {@code x-*} vendor annotations (preserving {@code x-*} user property names),
     * rewrites boolean schemas ({@code true -> {"type":"string"}}, {@code false} omitted),
     * and infers missing {@code type} fields across nested schemas.
     */
    static ObjectNode normalizeForGemini(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return JsonUtils.getMapper().createObjectNode().put("type", "object");
        }
        JsonNode shaped = sanitizeGeminiShapes(schema);
        ObjectNode root = shaped != null && shaped.isObject()
            ? (ObjectNode) shaped
            : JsonUtils.getMapper().createObjectNode().put("type", "object");
        fillGeminiMissingTypes(root, root, true);
        return root;
    }

    /**
     * Converts Gemini's OpenAPI-style schema (uppercase {@code OBJECT}, {@code STRING},
     * {@code nullable}, {@code propertyOrdering}) into standard lowercase JSON Schema.
     */
    static JsonNode fromGeminiOpenApiSchema(JsonNode schema) {
        if (schema == null || schema.isNull()) return null;
        if (schema.isArray()) {
            ArrayNode out = JsonUtils.getMapper().createArrayNode();
            for (JsonNode el : schema) out.add(fromGeminiOpenApiSchema(el));
            return out;
        }
        if (!schema.isObject()) return schema.deepCopy();
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode val = entry.getValue();
            switch (key) {
                case "type" -> {
                    if (val.isTextual()) {
                        out.put("type", val.asText().toLowerCase(Locale.ROOT));
                    } else if (val.isArray()) {
                        ArrayNode arr = out.putArray("type");
                        for (JsonNode t : val) {
                            arr.add(t.isTextual() ? t.asText().toLowerCase(Locale.ROOT) : t.asText());
                        }
                    } else {
                        out.set("type", val.deepCopy());
                    }
                }
                case "nullable" -> {
                    if (val.isBoolean() && val.asBoolean()) {
                        out.put("x-nullable", true);
                    }
                }
                case "propertyOrdering", "example" -> {
                    // Omit Gemini OpenAPI extras.
                }
                default -> out.set(key, fromGeminiOpenApiSchema(val));
            }
        }
        return out;
    }

    private static JsonNode sanitizeGeminiShapes(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isBoolean()) {
            return node.asBoolean() ? JsonUtils.getMapper().createObjectNode() : null;
        }
        if (node.isArray()) {
            ArrayNode out = JsonUtils.getMapper().createArrayNode();
            for (JsonNode item : node) {
                JsonNode cleaned = sanitizeGeminiShapes(item);
                if (cleaned != null) out.add(cleaned);
            }
            return out;
        }
        if (!node.isObject()) return node.deepCopy();

        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        Set<String> droppedFalseProps = new LinkedHashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode val = entry.getValue();
            if (Strings.CS.startsWith(key, "x-")) {
                // Drop vendor x-* schema keywords (user property names live inside "properties").
                continue;
            }
            if (SCHEMA_MAP_CONTAINERS.contains(key) && val.isObject()) {
                ObjectNode mapOut = JsonUtils.getMapper().createObjectNode();
                val.fields().forEachRemaining(prop -> {
                    JsonNode propSchema = prop.getValue();
                    if (propSchema.isBoolean()) {
                        if (propSchema.asBoolean()) {
                            mapOut.set(prop.getKey(), JsonUtils.getMapper().createObjectNode());
                        } else if (Strings.CS.equals("properties", key)) {
                            droppedFalseProps.add(prop.getKey());
                        }
                    } else {
                        JsonNode cleaned = sanitizeGeminiShapes(propSchema);
                        if (cleaned != null) mapOut.set(prop.getKey(), cleaned);
                    }
                });
                out.set(key, mapOut);
                continue;
            }
            if (BOOLEAN_ALLOWED_KEYWORDS.contains(key) && val.isBoolean()) {
                out.set(key, val);
                continue;
            }
            if (SCHEMA_CHILD_KEYWORDS.contains(key)) {
                if (val.isBoolean()) {
                    if (val.asBoolean()) {
                        out.set(key, JsonUtils.getMapper().createObjectNode());
                    }
                    continue;
                }
                JsonNode cleaned = sanitizeGeminiShapes(val);
                if (cleaned != null) out.set(key, cleaned);
                continue;
            }
            out.set(key, val.deepCopy());
        }
        if (!droppedFalseProps.isEmpty() && out.has("required") && out.get("required").isArray()) {
            ArrayNode keptReq = JsonUtils.getMapper().createArrayNode();
            for (JsonNode req : out.get("required")) {
                if (!req.isTextual() || !droppedFalseProps.contains(req.asText())) {
                    keptReq.add(req);
                }
            }
            out.set("required", keptReq);
        }
        return out;
    }

    private static void fillGeminiMissingTypes(ObjectNode root, JsonNode node, boolean isRoot) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode item : node) {
                fillGeminiMissingTypes(root, item, false);
            }
            return;
        }
        if (!node.isObject()) return;
        ObjectNode obj = (ObjectNode) node;
        if (obj.has("type") && obj.get("type").isTextual()) {
            obj.put("type", obj.get("type").asText().toLowerCase(Locale.ROOT));
        }
        if (!obj.has("type") && !obj.has("not") && !obj.has("if")) {
            String inferred = inferGeminiType(root, obj, isRoot);
            if (StringUtils.isNotEmpty(inferred)) {
                prependTypeField(obj, JsonUtils.getMapper().getNodeFactory().textNode(inferred));
            }
        }
        for (String mapKey : SCHEMA_MAP_CONTAINERS) {
            JsonNode mapNode = obj.get(mapKey);
            if (mapNode != null && mapNode.isObject()) {
                mapNode.fields().forEachRemaining(entry -> fillGeminiMissingTypes(root, entry.getValue(), false));
            }
        }
        for (String childKey : SCHEMA_CHILD_KEYWORDS) {
            if (Strings.CS.equals("not", childKey) || Strings.CS.equals("if", childKey)) continue;
            if (obj.has(childKey)) {
                fillGeminiMissingTypes(root, obj.get(childKey), false);
            }
        }
    }

    private static String inferGeminiType(ObjectNode root, ObjectNode s, boolean isRoot) {
        String kw = keywordType(s);
        if (Strings.CS.equals("object", kw) || Strings.CS.equals("array", kw)) {
            return kw;
        }
        if (s.has("enum") && s.get("enum").isArray()) {
            return valuesSingleType(s.get("enum"));
        }
        if (s.has("const") && !s.get("const").isNull()) {
            return scalarOrContainerType(s.get("const"));
        }
        Set<String> branchTypes = collectSchemaTypes(root, s, 0);
        branchTypes.remove("null");
        if (branchTypes.contains("integer") && branchTypes.contains("number")) {
            branchTypes.remove("integer");
        }
        if (branchTypes.size() == 1) {
            return branchTypes.iterator().next();
        }
        if (branchTypes.size() > 1) {
            return "";
        }
        if (isRoot) return "object";
        if (s.has("$ref") || s.has("anyOf") || s.has("oneOf") || s.has("allOf")) {
            return "";
        }
        if (s.has("default") && !s.get("default").isNull()) {
            String dt = scalarOrContainerType(s.get("default"));
            if (StringUtils.isNotEmpty(dt)) return dt;
        }
        return "string";
    }

    private static Set<String> collectSchemaTypes(ObjectNode root, JsonNode s, int depth) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null || !s.isObject() || depth > 8) return out;
        ObjectNode obj = (ObjectNode) s;
        if (obj.has("type")) {
            JsonNode t = obj.get("type");
            if (t.isTextual()) out.add(t.asText().toLowerCase(Locale.ROOT));
            else if (t.isArray()) {
                for (JsonNode el : t) {
                    if (el.isTextual()) out.add(el.asText().toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }
        String kw = keywordType(obj);
        if (StringUtils.isNotEmpty(kw)) {
            out.add(kw);
            return out;
        }
        if (obj.has("enum") && obj.get("enum").isArray()) {
            String vt = valuesSingleType(obj.get("enum"));
            if (StringUtils.isNotEmpty(vt)) out.add(vt);
            return out;
        }
        if (obj.has("const") && !obj.get("const").isNull()) {
            String ct = scalarOrContainerType(obj.get("const"));
            if (StringUtils.isNotEmpty(ct)) out.add(ct);
            return out;
        }
        for (String union : new String[] {"anyOf", "oneOf", "allOf"}) {
            if (obj.has(union) && obj.get(union).isArray()) {
                for (JsonNode branch : obj.get(union)) {
                    out.addAll(collectSchemaTypes(root, branch, depth + 1));
                }
            }
        }
        if (obj.has("$ref") && obj.get("$ref").isTextual()) {
            JsonNode target = resolveLocalRef(root, obj.get("$ref").asText());
            if (target != null) {
                out.addAll(collectSchemaTypes(root, target, depth + 1));
            }
        }
        return out;
    }

    private static JsonNode resolveLocalRef(ObjectNode root, String ref) {
        if (!Strings.CS.startsWith(ref, "#/")) return null;
        String[] parts = ref.substring(2).split("/");
        JsonNode cur = root;
        for (String part : parts) {
            if (cur == null || !cur.isObject()) return null;
            cur = cur.get(part);
        }
        return cur;
    }

    private static String keywordType(ObjectNode s) {
        if (s.has("properties") || s.has("required") || s.has("additionalProperties")
                || s.has("patternProperties") || s.has("minProperties") || s.has("maxProperties")) {
            return "object";
        }
        if (s.has("items") || s.has("prefixItems") || s.has("minItems") || s.has("maxItems")
                || s.has("uniqueItems")) {
            return "array";
        }
        if (s.has("pattern") || s.has("minLength") || s.has("maxLength") || s.has("format")) {
            return "string";
        }
        if (s.has("minimum") || s.has("maximum") || s.has("exclusiveMinimum")
                || s.has("exclusiveMaximum") || s.has("multipleOf")) {
            return "number";
        }
        return "";
    }

    private static String valuesSingleType(JsonNode arr) {
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode v : arr) {
            if (v == null || v.isNull()) continue;
            String t = scalarOrContainerType(v);
            if (StringUtils.isEmpty(t)) return "";
            types.add(t);
        }
        if (types.contains("integer") && types.contains("number")) {
            types.remove("integer");
        }
        return types.size() == 1 ? types.iterator().next() : "";
    }

    private static String scalarOrContainerType(JsonNode v) {
        if (v == null || v.isNull()) return "";
        if (v.isObject()) return "object";
        if (v.isArray()) return "array";
        return scalarJsonType(v);
    }

    private static String scalarJsonType(JsonNode v) {
        if (v.isTextual()) return "string";
        if (v.isIntegralNumber()) return "integer";
        if (v.isNumber()) return "number";
        if (v.isBoolean()) return "boolean";
        return null;
    }

    private static void prependTypeField(ObjectNode target, JsonNode typeNode) {
        ObjectNode copy = target.deepCopy();
        target.removeAll();
        target.set("type", typeNode);
        copy.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
    }
}
