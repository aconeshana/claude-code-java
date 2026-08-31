package com.claudecode.tools;

import java.util.Locale;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.tools.validation.SchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-execution input validation gate for tools.
 */
final class ToolInputValidation {

    private ToolInputValidation() {}

    private static final Logger log = LoggerFactory.getLogger(ToolInputValidation.class);
    private static final SchemaValidator SCHEMA_VALIDATOR = SchemaValidator.shared();

    /**
     * Validates {@code input} against the tool's JSON Schema before execution.
     */
    static String validateInput(Tool<?, ?> tool, JsonNode input) {
        String toolName = tool.name();
        if (toolName != null && (Strings.CS.startsWith(toolName, "mcp__") || Strings.CS.equals("StructuredOutput", toolName))) {
            return null;
        }
        JsonNode schema;
        try {
            schema = tool.inputSchema();
        } catch (Exception _) {
            return null;
        }
        if (schema == null || !schema.isObject() || !Strings.CS.equals("object", schema.path("type").asText(null))) {
            return null;
        }

        JsonNode properties = schema.path("properties");
        List<String> missingParams = new ArrayList<>();
        List<String> unexpectedParams = new ArrayList<>();
        List<String[]> typeMismatches = new ArrayList<>(); // {param, expected, received}

        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode req : required) {
                String name = req.asText();
                if (input == null || !input.has(name)) {
                    missingParams.add(name);
                }
            }
        }

        if (input != null && input.isObject()) {

            // unless the schema explicitly opts out via additionalProperties: true
            // (or an additionalProperties sub-schema, which also permits extra keys).
            JsonNode additional = schema.get("additionalProperties");
            boolean strict = additional == null || (additional.isBoolean() && !additional.asBoolean());
            for (Iterator<Map.Entry<String, JsonNode>> it = input.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                String name = field.getKey();
                JsonNode value = field.getValue();
                JsonNode propSchema = properties.get(name);
                if (propSchema == null) {
                    if (strict && properties.isObject()) {
                        unexpectedParams.add(name);
                    }
                    continue;
                }
                String expected = expectedType(propSchema);
                if (expected != null && !matchesType(value, expected)) {
                    typeMismatches.add(new String[]{name, expected, receivedType(value)});
                }
            }
        }

        List<String> errorParts = new ArrayList<>();
        for (String param : missingParams) {
            errorParts.add("The required parameter `" + param + "` is missing");
        }
        for (String param : unexpectedParams) {
            errorParts.add("An unexpected parameter `" + param + "` was provided");
        }
        for (String[] mismatch : typeMismatches) {
            errorParts.add("The parameter `" + mismatch[0] + "` type is expected as `"
                    + mismatch[1] + "` but provided as `" + mismatch[2] + "`");
        }


        validateInputDeep(tool, input, schema, errorParts);

        if (errorParts.isEmpty()) {
            return null;
        }
        return toolName + " failed due to the following "
                + (errorParts.size() > 1 ? "issues" : "issue") + ":\n"
                + String.join("\n", errorParts);
    }

    /**
     * Deep JSON-Schema validation of {@code input} against the tool's schema.
     */
    static void validateInputDeep(Tool<?, ?> tool, JsonNode input, JsonNode schema,
                                  List<String> errorParts) {
        String mode = schemaValidationMode();
        if (Strings.CS.equals("off", mode)) {
            return;
        }
        var result = SCHEMA_VALIDATOR.validateAgainstJsonSchema(input, schema);
        if (result.isSuccess()) {
            return;
        }
        if (Strings.CS.equals("enforce", mode)) {
            for (String e : result.errors()) {
                // The lightweight check already reports top-level required/type/
                // additional-property errors. Other top-level constraints such as
                // enum, const, anyOf, and string bounds are only checked here.
                if (duplicatesLightweightError(e)) {
                    continue;
                }
                errorParts.add(e);
            }
        } else {
            log.warn("[SCHEMA_DRIFT] tool={} errors={}", tool.name(), result.errors());
        }
    }

    /** Returns whether a top-level deep error duplicates a lightweight validation error. */
    private static boolean duplicatesLightweightError(String error) {
        int colon = error.indexOf(": ");
        String path = colon >= 0 ? error.substring(0, colon) : error;
        long dots = path.chars().filter(ch -> ch == '.').count();
        if (dots > 1) return false;
        return Strings.CS.contains(error, ": required property '")
            || Strings.CS.contains(error, ": unexpected property '")
            || Strings.CS.contains(error, ": value of type ");
    }

    static String schemaValidationMode() {
        String v = System.getProperty("claudecode.schema.validation");
        if (v == null) v = SubprocessEnvironment.get(
            "CLAUDECODE_SCHEMA_VALIDATION");
        if (v == null) return "enforce";
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "enforce", "observe", "off" -> v.trim().toLowerCase(Locale.ROOT);
            default -> "enforce";
        };
    }

    /**
     * Resolves the zod-style expected type label from a property schema.
     * Returns {@code null} when no single-type check applies (missing "type",
     * union types, etc.) — those cases are skipped, matching the lightweight scope.
     * JSON Schema "integer" maps to zod's "number" (z.number().int() reports
     * invalid_type as expected "number"; the int refinement is a separate check).
     */
    private static String expectedType(JsonNode propSchema) {
        JsonNode typeNode = propSchema.path("type");
        if (!typeNode.isTextual()) {
            return null;
        }
        String type = typeNode.asText();
        return switch (type) {
            case "string", "number", "boolean", "array", "object" -> type;
            case "integer" -> "number";
            default -> null;
        };
    }

    private static boolean matchesType(JsonNode value, String expected) {
        return switch (expected) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> true;
        };
    }

    /** Maps a JsonNode to the zod "received" label used in type-mismatch messages. */
    private static String receivedType(JsonNode value) {
        if (value == null || value.isMissingNode()) return "undefined";
        if (value.isNull()) return "null";
        if (value.isTextual()) return "string";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        if (value.isArray()) return "array";
        if (value.isObject()) return "object";
        return "unknown";
    }
}
