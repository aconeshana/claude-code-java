package com.claudecode.tools.output;

import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.validation.SchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;

/**
 * StructuredOutput tool — lets the model return its final answer as JSON validated against a
 * caller-supplied (CLI {@code --json-schema}) schema.
 */
@BuiltInTool(
    name = SyntheticOutputTool.NAME,
    readOnly = true,
    concurrencySafe = true
)
public class SyntheticOutputTool extends AnnotatedTool<JsonNode, Object> {


    @Override
    public String searchHint() {
        return "return the final response as structured JSON";
    }


    public static final String NAME = "StructuredOutput";

    private final JsonNode jsonSchema;
    private final SchemaValidator schemaValidator;

    public SyntheticOutputTool(JsonNode jsonSchema) {
        this.jsonSchema = Objects.requireNonNull(jsonSchema, "jsonSchema");
        this.schemaValidator = SchemaValidator.shared();
    }

    @Override
    public String description() {
        return ToolTexts.description("StructuredOutput");
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("StructuredOutput");
    }


    @Override
    public JsonNode inputSchema() {
        return jsonSchema;
    }

    /**
     * Validates {@code input} against the injected schema and, on success, hands it back unchanged as
     * the structured payload.
     */
    @Override
    public Object call(JsonNode input, ToolExecutionContext context) {
        SchemaValidator.ValidationResult result = schemaValidator.validateAgainstJsonSchema(input, jsonSchema);
        if (!result.isSuccess()) {
            String errors = String.join(", ", result.errors());
            throw new RuntimeException("Output does not match required schema: " + errors);
        }
        return new StructuredToolOutput("Structured output provided successfully", input);
    }




    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }


    public static CreateResult create(JsonNode jsonSchema) {
        if (jsonSchema == null || !jsonSchema.isObject()) {
            return new CreateResult.Err("Schema must be a JSON object");
        }
        String schemaErr = schemaWellFormedError(jsonSchema);
        if (schemaErr != null) {
            return new CreateResult.Err("Invalid --json-schema: " + schemaErr);
        }

        String patternErr = invalidPatternError(jsonSchema);
        if (patternErr != null) {
            return new CreateResult.Err("Invalid --json-schema: " + patternErr);
        }
        try {
            return new CreateResult.Ok(new SyntheticOutputTool(jsonSchema));
        } catch (Exception e) {
            return new CreateResult.Err(e.getMessage() != null ? e.getMessage() : String.valueOf(e));
        }
    }

    /** Valid JSON-Schema {@code type} values (draft-07 core). */
    private static final Set<String> KNOWN_TYPES = Set.of(
        "string", "number", "integer", "boolean", "array", "object", "null");


    private static String schemaWellFormedError(JsonNode schema) {
        return schemaWellFormedError(schema, "$");
    }

    private static String schemaWellFormedError(JsonNode schema, String path) {
        if (schema == null || schema.isMissingNode() || schema.isBoolean()) {
            return null; // absent or boolean schema = valid (no constraint)
        }
        if (!schema.isObject()) {
            return path + ": schema must be a JSON object or boolean";
        }
        JsonNode type = schema.get("type");
        if (type != null) {
            if (type.isArray()) {
                for (JsonNode t : type) {
                    if (!t.isTextual() || !KNOWN_TYPES.contains(t.asText())) {
                        return path + ".type: invalid type value " + t;
                    }
                }
            } else if (!type.isTextual() || !KNOWN_TYPES.contains(type.asText())) {
                return path + ".type: invalid type value " + type;
            }
        }
        JsonNode props = schema.get("properties");
        if (props != null) {
            if (!props.isObject()) {
                return path + ".properties: must be an object";
            }
            Iterator<Map.Entry<String, JsonNode>> pit = props.fields();
            while (pit.hasNext()) {
                Map.Entry<String, JsonNode> e = pit.next();
                String err = schemaWellFormedError(e.getValue(), path + ".properties." + e.getKey());
                if (err != null) {
                    return err;
                }
            }
        }
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray()) {
                return path + ".required: must be an array";
            }
            for (JsonNode r : required) {
                if (!r.isTextual()) {
                    return path + ".required: each entry must be a string";
                }
            }
        }
        JsonNode items = schema.get("items");
        if (items != null) {
            String err = schemaWellFormedError(items, path + ".items");
            if (err != null) {
                return err;
            }
        }
        JsonNode ap = schema.get("additionalProperties");
        if (ap != null && !ap.isBoolean()) {
            String err = schemaWellFormedError(ap, path + ".additionalProperties");
            if (err != null) {
                return err;
            }
        }
        JsonNode en = schema.get("enum");
        if (en != null && !en.isArray()) {
            return path + ".enum: must be an array";
        }
        for (String comb : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode arr = schema.get(comb);
            if (arr != null) {
                if (!arr.isArray()) {
                    return path + "." + comb + ": must be an array";
                }
                for (int i = 0; i < arr.size(); i++) {
                    String err = schemaWellFormedError(arr.get(i), path + "." + comb + "[" + i + "]");
                    if (err != null) {
                        return err;
                    }
                }
            }
        }
        JsonNode not = schema.get("not");
        if (not != null) {
            String err = schemaWellFormedError(not, path + ".not");
            if (err != null) {
                return err;
            }
        }
        JsonNode defs = schema.get("definitions");
        if (defs != null && defs.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> dit = defs.fields();
            while (dit.hasNext()) {
                Map.Entry<String, JsonNode> e = dit.next();
                String err = schemaWellFormedError(e.getValue(), path + ".definitions." + e.getKey());
                if (err != null) {
                    return err;
                }
            }
        }
        return null;
    }


    private static String invalidPatternError(JsonNode schema) {
        return invalidPatternError(schema, "$");
    }

    private static String invalidPatternError(JsonNode schema, String path) {
        if (schema == null || schema.isMissingNode() || schema.isBoolean()) {
            return null; // absent or boolean schema = valid (no constraint)
        }
        if (!schema.isObject()) {
            return null; // patterns only live on object schemas
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null && pattern.isTextual()) {
            try {
                Pattern.compile(pattern.asText());
            } catch (PatternSyntaxException e) {
                return path + ".pattern: invalid regular expression: " + e.getMessage();
            }
        }
        for (String key : List.of("additionalProperties", "items", "not")) {
            JsonNode child = schema.get(key);
            if (child != null && child.isObject()) {
                String err = invalidPatternError(child, path + "." + key);
                if (err != null) {
                    return err;
                }
            }
        }

// schemaWellFormedError, which iterates defs.fields). Each named entry
        // must be recursed, not treated as a single schema — otherwise e.g.
        // definitions.foo.pattern escapes pattern validation.
        JsonNode defs = schema.get("definitions");
        if (defs != null && defs.isObject()) {
            var dit = defs.fields();
            while (dit.hasNext()) {
                var e = dit.next();
                String err = invalidPatternError(e.getValue(), path + ".definitions." + e.getKey());
                if (err != null) {
                    return err;
                }
            }
        }

// schemaWellFormedError, which iterates props.fields).
        JsonNode props = schema.get("properties");
        if (props != null && props.isObject()) {
            var pit = props.fields();
            while (pit.hasNext()) {
                var e = pit.next();
                String err = invalidPatternError(e.getValue(), path + ".properties." + e.getKey());
                if (err != null) {
                    return err;
                }
            }
        }
        for (String comb : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode arr = schema.get(comb);
            if (arr != null && arr.isArray()) {
                for (int i = 0; i < arr.size(); i++) {
                    String err = invalidPatternError(arr.get(i), path + "." + comb + "[" + i + "]");
                    if (err != null) {
                        return err;
                    }
                }
            }
        }
        return null;
    }


    public sealed interface CreateResult {
        record Ok(SyntheticOutputTool tool) implements CreateResult {}

        record Err(String error) implements CreateResult {}
    }
}
