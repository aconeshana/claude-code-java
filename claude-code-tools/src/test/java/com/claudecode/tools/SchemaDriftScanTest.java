package com.claudecode.tools;

import org.apache.commons.lang3.Strings;

import com.claudecode.tools.validation.SchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


class SchemaDriftScanTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemaValidator validator = new SchemaValidator();

    @Test
    void scanToolSchemasForNestedAdditionalPropertiesDrift() {
        // Use the production bootstrap so the scan runs against the exact tool
        // set offered to the model — not a hand-maintained subset.
        ToolRegistry registry = ToolBootstrap.buildBuiltInRegistry();

        // Guard: if the bootstrap ever registers nothing, the scan below would
        // pass vacuously. Ensure the real tool set is present.
        assertFalse(registry.getAll().isEmpty(),
                "ToolBootstrap registered no tools — scan would pass vacuously");
        for (String expected : new String[]{"Read", "Agent", "AskUserQuestion", "Bash"}) {
            assertTrue(registry.get(expected).isPresent(),
                    "expected built-in tool not registered by bootstrap: " + expected);
        }

        List<String> drifts = new ArrayList<>();

        for (Tool<?, ?> tool : registry.getAll()) {
            JsonNode schema = tool.inputSchema();
            if (schema == null || !schema.isObject()) continue;

            // 1) Static: nested additionalProperties:false at depth > 0.
            collectNestedStrict(schema, "$", drifts, tool.name());

            // 2) Live: synthetic input with an extra key at every object level.
            JsonNode synthetic = synthObject(schema);
            var result = validator.validateAgainstJsonSchema(synthetic, schema);
            for (String err : result.errors()) {
                if (Strings.CS.contains(err, "unexpected property") && !Strings.CS.startsWith(err, "$:")) {
                    drifts.add(tool.name() + " [live]: nested over-strict rejects extra key -> " + err);
                }
            }
        }

        if (!drifts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Schema/impl drift detected (Java schema stricter than TS):\n");
            for (String d : drifts) sb.append("  - ").append(d).append("\n");
            sb.append("\nFix: nested objects must be permissive (omit additionalProperties or set ")
              .append("true), mirroring TS z.object passthrough. Root-level strictObject is fine.");
            System.out.println("[SCHEMA_DRIFT_SUMMARY]\n" + sb);
            fail(sb.toString());
        }
    }

    // Walks a JSON Schema; reports any object (excluding the root, depth 0) that
    // declares additionalProperties:false — that is the over-strictness signal.
    private void collectNestedStrict(JsonNode schema, String path, List<String> drifts, String tool) {
        if (schema == null || !schema.isObject()) return;
        boolean isRoot = Strings.CS.equals("$", path);
        if (!isRoot) {
            JsonNode ap = schema.get("additionalProperties");
            if (ap != null && ap.isBoolean() && !ap.asBoolean()) {
                drifts.add(tool + " [static]: nested object at " + path + " sets additionalProperties:false");
            }
        }
        JsonNode props = schema.get("properties");
        if (props != null && props.isObject()) {
            Iterator<String> it = props.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                JsonNode prop = props.get(name);
                String childPath = path + "." + name;
                if (prop != null && prop.isObject()) {
                    collectNestedStrict(prop, childPath, drifts, tool);
                }
                JsonNode items = prop != null ? prop.get("items") : null;
                if (items != null && items.isObject()) {
                    collectNestedStrict(items, childPath + "[]", drifts, tool);
                }
            }
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            collectNestedStrict(items, path + "[]", drifts, tool);
        }
    }

    // Builds a synthetic object from a (object) schema, filling each property
    // with a type-appropriate value and injecting an unknown key "_EXTRA_KEY"
    // at every object level so the validator can detect over-strict
    // additionalProperties anywhere in the tree.
    private JsonNode synthObject(JsonNode schema) {
        ObjectNode node = mapper.createObjectNode();
        JsonNode props = schema.get("properties");
        if (props != null && props.isObject()) {
            var it = props.fields();
            while (it.hasNext()) {
                var e = it.next();
                node.set(e.getKey(), synthValue(e.getValue()));
            }
        }
        node.put("_EXTRA_KEY", "x");
        return node;
    }

    private JsonNode synthValue(JsonNode prop) {
        if (prop == null || !prop.isObject()) return mapper.createObjectNode().put("_EXTRA_KEY", "x");
        JsonNode en = prop.get("enum");
        if (en != null && en.isArray() && !en.isEmpty()) {
            JsonNode first = en.get(0);
            if (first.isTextual()) return mapper.createObjectNode().put("_EXTRA_KEY", first.asText());
            if (first.isNumber()) return mapper.createObjectNode().put("_EXTRA_KEY", first.asDouble());
            if (first.isBoolean()) return mapper.createObjectNode().put("_EXTRA_KEY", first.asBoolean());
            if (first.isNull()) return mapper.createObjectNode().put("_EXTRA_KEY", (String) null);
        }
        List<String> types = new ArrayList<>();
        JsonNode t = prop.get("type");
        if (t != null) {
            if (t.isArray()) t.forEach(n -> { if (n.isTextual()) types.add(n.asText()); });
            else if (t.isTextual()) types.add(t.asText());
        }
        String primary = types.isEmpty() ? "string" : types.getFirst();
        return switch (primary) {
            case "string" -> mapper.createObjectNode().put("_EXTRA_KEY", "x");
            case "number", "integer" -> mapper.createObjectNode().put("_EXTRA_KEY", 1);
            case "boolean" -> mapper.createObjectNode().put("_EXTRA_KEY", true);
            case "array" -> {
                ArrayNode arr = mapper.createArrayNode();
                JsonNode items = prop.get("items");
                if (items != null && items.isObject()) arr.add(synthValue(items));
                yield arr;
            }
            case "object" -> synthObject(prop);
            default -> mapper.createObjectNode().put("_EXTRA_KEY", "x");
        };
    }

}
