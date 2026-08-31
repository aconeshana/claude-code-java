package com.claudecode.core.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


public class JsonSchemaValidator {

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Shared stateless validator for production call paths. */
    public static JsonSchemaValidator shared() {
        return SharedHolder.INSTANCE;
    }

    private static final class SharedHolder {
        private static final JsonSchemaValidator INSTANCE = new JsonSchemaValidator();
    }

    public JsonSchemaValidator() {
    }

    /**
     * Validates an arbitrary JSON value against a JSON-Schema <em>document</em> (exactly the shape a
     * tool's {@code inputSchema} returns).
     */
    public ValidationResult validateAgainstJsonSchema(JsonNode value, JsonNode schema) {
        List<String> errors = new ArrayList<>();
        validateJson(value, schema, "$", errors);
        return errors.isEmpty() ? ValidationResult.ok() : new ValidationResult(false, errors);
    }

    /**
     * Recursive JSON-Schema dispatcher. Resolves enum / type / nullability at the
     * current node, then recurses into object properties, array items, or scalar
     * (string/number) constraints as appropriate. {@code path} is the JSON-Path
     * prefix used to label collected errors.
     */
    private void validateJson(JsonNode value, JsonNode schema, String path, List<String> errors) {
        if (schema == null || schema.isMissingNode() || !schema.isObject()) {
            return; // absence of schema = no constraint
        }
        JsonNode constant = schema.get("const");
        if (constant != null && !schemaValueEquals(constant, value)) {
            errors.add(path + ": value does not equal the required constant " + constant);
            return;
        }
        JsonNode anyOf = schema.get("anyOf");
        if (anyOf != null && anyOf.isArray()) {
            boolean matched = false;
            for (JsonNode branch : anyOf) {
                List<String> branchErrors = new ArrayList<>();
                validateJson(value, branch, path, branchErrors);
                if (branchErrors.isEmpty()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(path + ": value does not match any schema in anyOf");
                return;
            }
        }
        JsonNode en = schema.get("enum");
        if (en != null && en.isArray()) {
            if (!enumContains(en, value)) {
                errors.add(path + ": value is not one of the allowed enum values: " + en);
            }
            return; // enum fully constrains the value
        }
        List<String> types = typeList(schema);
        if (value.isNull()) {
            if (types.contains("null")) return;
            if (!types.isEmpty()) {
                errors.add(path + ": expected type(s) " + types + " but received null");
            }
            return;
        }
        if (!types.isEmpty()) {
            boolean matched = false;
            for (String t : types) {
                if (matchesTypeKind(value, t)) { matched = true; break; }
            }
            if (!matched) {
                errors.add(path + ": value of type " + kindName(value)
                        + " does not match expected type(s) " + types);
                return;
            }
        }
        if (value.isObject()) {
            validateObjectJson(value, schema, path, errors);
        } else if (value.isArray()) {
            validateArrayJson(value, schema, path, errors);
        } else if (value.isTextual()) {
            validateStringJson(value, schema, path, errors);
        } else if (value.isNumber()) {
            validateNumberJson(value, schema, path, errors);
        }
    }

    private List<String> typeList(JsonNode schema) {
        JsonNode t = schema.get("type");
        List<String> out = new ArrayList<>();
        if (t == null) return out;
        if (t.isArray()) {
            for (JsonNode n : t) if (n.isTextual()) out.add(n.asText());
        } else if (t.isTextual()) {
            out.add(t.asText());
        }
        return out;
    }

    private boolean enumContains(JsonNode en, JsonNode value) {
        for (JsonNode candidate : en) {
            if (schemaValueEquals(candidate, value)) return true;
        }
        return false;
    }

    private boolean schemaValueEquals(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        return expected.equals(actual);
    }

    private boolean matchesTypeKind(JsonNode value, String type) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "null" -> value.isNull();
            default -> true; // unknown type keyword -> don't reject
        };
    }

    private String kindName(JsonNode value) {
        if (value.isTextual()) return "string";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        if (value.isArray()) return "array";
        if (value.isObject()) return "object";
        if (value.isNull()) return "null";
        return "unknown";
    }

    /**
     * Validates an object node against a JSON-Schema object: required properties, per-property
     * recursion, and {@code additionalProperties: false} (which rejects any key not declared in {@code
     * properties}).
     */
    private void validateObjectJson(JsonNode value, JsonNode schema, String path, List<String> errors) {
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode r : required) {
                String name = r.asText();
                JsonNode field = value.get(name);
                if (field == null || field.isNull()) {
                    errors.add(path + ": required property '" + name + "' is missing or null");
                }
            }
        }
        if (props != null && props.isObject()) {
            var it = props.fields();
            while (it.hasNext()) {
                var e = it.next();
                JsonNode field = value.get(e.getKey());
                if (field != null && !field.isNull()) {
                    validateJson(field, e.getValue(), path + "." + e.getKey(), errors);
                }
            }
        }
        // additionalProperties: only boolean false rejects extra keys. A sub-schema

        JsonNode ap = schema.get("additionalProperties");
        if (ap != null && ap.isBoolean() && !ap.asBoolean()) {
            var it = value.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                if (props == null || !props.has(key)) {
                    errors.add(path + ": unexpected property '" + key + "' is not allowed");
                }
            }
        }
    }

    /** Validates array length bounds (min/maxItems) and recursively each item via {@code items}. */
    private void validateArrayJson(JsonNode value, JsonNode schema, String path, List<String> errors) {
        JsonNode min = schema.get("minItems");
        JsonNode max = schema.get("maxItems");
        if (min != null && min.isNumber() && value.size() < min.asInt()) {
            errors.add(path + ": array has fewer than " + min.asInt() + " items");
        }
        if (max != null && max.isNumber() && value.size() > max.asInt()) {
            errors.add(path + ": array has more than " + max.asInt() + " items");
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            for (int i = 0; i < value.size(); i++) {
                validateJson(value.get(i), items, path + "[" + i + "]", errors);
            }
        }
    }

    /** Validates string length bounds (min/maxLength), {@code pattern}, and {@code format}. */
    private void validateStringJson(JsonNode value, JsonNode schema, String path, List<String> errors) {
        String str = value.asText();
        JsonNode min = schema.get("minLength");
        JsonNode max = schema.get("maxLength");
        if (min != null && min.isNumber() && str.length() < min.asInt()) {
            errors.add(path + ": string shorter than minLength " + min.asInt());
        }
        if (max != null && max.isNumber() && str.length() > max.asInt()) {
            errors.add(path + ": string longer than maxLength " + max.asInt());
        }
        JsonNode pat = schema.get("pattern");
        if (pat != null && pat.isTextual()
                && !Pattern.compile(pat.asText()).matcher(str).matches()) {
            errors.add(path + ": string does not match pattern " + pat.asText());
        }
        JsonNode fmt = schema.get("format");
        if (fmt != null && fmt.isTextual() && !validateFormatValue(str, fmt.asText())) {
            errors.add(path + ": string does not match format " + fmt.asText());
        }
    }

    /** Validates numeric bounds: minimum/maximum, exclusiveMinimum/Maximum, and multipleOf. */
    private void validateNumberJson(JsonNode value, JsonNode schema, String path, List<String> errors) {
        double num = value.asDouble();
        JsonNode min = schema.get("minimum");
        JsonNode max = schema.get("maximum");
        JsonNode exMin = schema.get("exclusiveMinimum");
        JsonNode exMax = schema.get("exclusiveMaximum");
        JsonNode mult = schema.get("multipleOf");
        if (min != null && min.isNumber() && num < min.asDouble()) {
            errors.add(path + ": number below minimum " + min.asDouble());
        }
        if (max != null && max.isNumber() && num > max.asDouble()) {
            errors.add(path + ": number above maximum " + max.asDouble());
        }
        if (exMin != null && exMin.isNumber() && num <= exMin.asDouble()) {
            errors.add(path + ": number must be greater than " + exMin.asDouble());
        }
        if (exMax != null && exMax.isNumber() && num >= exMax.asDouble()) {
            errors.add(path + ": number must be less than " + exMax.asDouble());
        }
        if (mult != null && mult.isNumber() && mult.asDouble() != 0) {
            double m = mult.asDouble();
            if (Math.abs(num / m - Math.round(num / m)) > 1e-9) {
                errors.add(path + ": number must be a multiple of " + m);
            }
        }
    }

    /** Public wrapper so callers (e.g. ToolInputValidation) can reuse the format checks. */
    public boolean validateFormatValue(String value, String format) {
        return validateFormat(value, format);
    }

    /**
     * Dispatch table for JSON-Schema {@code format} keywords. Each case maps a
     * declared format (uri, email, uuid, date-time, date, time, ipv4, ipv6) to a
     * concrete checker. Unknown formats are accepted (no false rejection).
     */
    private boolean validateFormat(String value, String format) {
        return switch (format) {
            case "uri" -> validateUri(value);
            case "email" -> validateEmail(value);
            case "uuid" -> validateUuid(value);
            case "date-time" -> validateDateTime(value);
            case "date" -> validateDate(value);
            case "time" -> validateTime(value);
            case "ipv4" -> validateIpv4(value);
            case "ipv6" -> validateIpv6(value);
            default -> true;
        };
    }

    private boolean validateUri(String value) {
        try {
            URI uri = URI.create(value);
// create throws IllegalArgumentException on malformed input; a
            // successfully-parsed value is a valid URI reference once any of its
            // components is present (or it is the empty reference).
            return uri.getScheme() != null || uri.getSchemeSpecificPart() != null
                    || uri.getFragment() != null || value.isEmpty();
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    private boolean validateEmail(String value) {
        return EMAIL_PATTERN.matcher(value).matches();
    }

    private boolean validateUuid(String value) {
        return UUID_PATTERN.matcher(value).matches();
    }

    private boolean validateDateTime(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (Exception _) {
            return false;
        }
    }

    private boolean validateDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (Exception _) {
            return false;
        }
    }

    private boolean validateTime(String value) {
        try {
            LocalTime.parse(value);
            return true;
        } catch (Exception _) {
            return false;
        }
    }

    private boolean validateIpv4(String value) {
        String[] parts = value.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) return false;
            } catch (NumberFormatException _) {
                return false;
            }
        }
        return true;
    }

    private boolean validateIpv6(String value) {
        try {
            InetAddress addr = InetAddress.getByName(value);
            return addr instanceof Inet6Address;
        } catch (UnknownHostException _) {
            return false;
        }
    }

    /**
     * Result of a validation pass. {@link #isSuccess} is true when no
     * constraint was violated; otherwise {@link #errors} holds one
     * human-readable message per violation (used directly as the
     * {@code InputValidationError} body).
     */
    public record ValidationResult(boolean successful, List<String> errors) {
        private static final ValidationResult OK = new ValidationResult(true, List.of());

        public static ValidationResult ok() { return OK; }
        public static ValidationResult failure(String error) { return new ValidationResult(false, List.of(error)); }
        public static ValidationResult failure(List<String> errors) { return new ValidationResult(false, errors); }
        public boolean isSuccess() { return successful; }
        public boolean isFailure() { return !successful; }
    }
}
