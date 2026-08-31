package com.claudecode.services.config;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Low-level strict settings-file parsing boundary.
 */
final class SettingsTreeReader {

    record ParsedSettings(ObjectNode settings, List<SettingsValidationError> errors) {
        ParsedSettings {
            settings = settings == null ? null : settings.deepCopy();
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    private SettingsTreeReader() {}

    /** Reads strict JSON after removing a UTF-8 BOM, preserving parser and I/O failures. */
    static JsonNode readJson(Path settingsPath) throws IOException {
        String content = Files.readString(settingsPath, StandardCharsets.UTF_8);
// parseSettingsFileUncached treats an empty settings file as an accepted
        // empty object before calling safeParseJSON. Keep the raw settings-tree
        // boundary consistent too, including a UTF-8 BOM followed only by
        // whitespace; this is distinct from a literal JSON null root.
        if (StringUtils.isBlank(JsonUtils.stripBom(content))) {
            return JsonUtils.getMapper().createObjectNode();
        }
        try {
            return JsonUtils.parseTree(JsonUtils.stripBom(content));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Cache-aware strict read.  The caller supplies whether the current source-selection policy
     * should be honored; raw editor and fixed-source sandbox reads deliberately bypass it.
     */
    static JsonNode readCached(Path settingsPath, boolean honorSourceSelection) throws IOException {
        if (honorSourceSelection && SettingsSources.isReadPathDisabled(settingsPath)) {
            return JsonUtils.getMapper().createObjectNode();
        }
        Path key = settingsPath.toAbsolutePath().normalize();
        FileTime modified = Files.getLastModifiedTime(settingsPath);
        long size = Files.size(settingsPath);
        SettingsCache.CachedTree cached = SettingsCache.TREES.get(key);
        if (cached != null && cached.modified().equals(modified) && cached.size() == size) {
            return cached.tree();
        }
        JsonNode tree = readJson(settingsPath);
        if (tree != null) {
            SettingsCache.TREES.put(key, new SettingsCache.CachedTree(modified, size, tree));
        }
        return tree;
    }

    static void invalidateCache() {
        SettingsCache.clearTrees();
    }

    /**
     * Returns the accepted view of an ordinary settings source. Invalid permission rules are
     * removed before schema validation; any other schema error rejects the entire source.
     */
    static ObjectNode accepted(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) return null;
        ObjectNode copy = candidate.deepCopy();
        coerceEnvironmentValues(copy);
        removeInvalidPermissionRules(copy);
        return SettingsSchema.validate(copy).isEmpty() ? SettingsSchema.sanitize(copy) : null;
    }

    /**
     * Validates the SDK inline overlay. Unlike file sources, an invalid inline permission rule
     * rejects the complete overlay instead of being silently pruned.
     */
    static ObjectNode acceptedInline(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) return null;
        ObjectNode copy = candidate.deepCopy();
        coerceEnvironmentValues(copy);
        return SettingsSchema.validate(copy).isEmpty() ? SettingsSchema.sanitize(copy) : null;
    }

    /** Shared managed-policy validation, retaining the diagnostics needed by MDM and snapshots. */
    static ManagedValidation validateManaged(JsonNode candidate, String file) {
        if (candidate == null || !candidate.isObject()) {
            return new ManagedValidation(JsonUtils.getMapper().createObjectNode(), List.of());
        }
        ObjectNode copy = candidate.deepCopy();
        coerceEnvironmentValues(copy);
        normalizeCatchGuardedSettings(copy);
        List<SettingsValidationError> diagnostics = new ArrayList<>();
        collectPermissionWarnings(copy, file, diagnostics);
        removeInvalidPermissionRules(copy);
        List<SettingsSchema.FieldError> schemaErrors = SettingsSchema.validate(copy);
        for (SettingsSchema.FieldError error : schemaErrors) {
            diagnostics.add(new SettingsValidationError(file, error.path(), error.message()));
        }
        if (!schemaErrors.isEmpty()) {
            return new ManagedValidation(JsonUtils.getMapper().createObjectNode(), diagnostics);
        }
        return new ManagedValidation(SettingsSchema.sanitize(copy), diagnostics);
    }

    static ObjectNode readAccepted(Path path, boolean honorSourceSelection) {
        if (path == null || !Files.isReadable(path)) return null;
        try {
            return accepted(readCached(path, honorSourceSelection));
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /**
     * Parses one source for diagnostic aggregation. It deliberately separates accepted data from
     * warnings/errors so a malformed source cannot leak a partial configuration into execution.
     */
    static ParsedSettings parseForDiagnostics(Path path, boolean honorSourceSelection) {
        if (path == null || !Files.isReadable(path)
                || (honorSourceSelection && SettingsSources.isReadPathDisabled(path))) {
            return new ParsedSettings(null, List.of());
        }
        String file = path.toAbsolutePath().normalize().toString();
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
// parseSettingsFileUncached returns an accepted empty object for blank
            // files, including a UTF-8 BOM followed only by whitespace. Preserve
            // that shape for diagnostics as well; a blank source is valid and empty,
            // not an absent or malformed settings tree.
            if (StringUtils.isBlank(JsonUtils.stripBom(content))) {
                return new ParsedSettings(JsonUtils.getMapper().createObjectNode(), List.of());
            }
            JsonNode raw = readJson(path);
            if (raw == null) {
                return new ParsedSettings(null,
                    List.of(new SettingsValidationError(file, "", "Invalid or malformed JSON")));
            }
            if (!raw.isObject()) {
                List<SettingsValidationError> errors = SettingsSchema.validate(raw).stream()
                    .map(error -> new SettingsValidationError(file, error.path(), error.message()))
                    .toList();
                return new ParsedSettings(null, errors);
            }
            ManagedValidation validation = validateManaged(raw, file);
            return new ParsedSettings(accepted(raw), validation.errors());
        } catch (IOException | RuntimeException _) {
            return new ParsedSettings(null,
                List.of(new SettingsValidationError(file, "", "Invalid or malformed JSON")));
        }
    }

    static Boolean booleanValue(JsonNode root, String key) {
        if (root == null || !root.isObject()) return null;
        JsonNode node = root.get(key);
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }

    static Integer integerValue(JsonNode node) {
        return node != null && node.isNumber()
                && SettingsSchema.isInteger(node)
                && node.canConvertToInt()
            ? node.intValue() : null;
    }

    static Integer integerValue(JsonNode root, String key) {
        return root == null || !root.isObject() ? null : integerValue(root.get(key));
    }

    static String stringValue(JsonNode root, String key, boolean trim) {
        if (root == null || !root.isObject()) return null;
        JsonNode node = root.get(key);
        if (node == null || !node.isTextual()) return null;
        String value = node.asText();
        if (!trim) return value;
        value = value.strip();
        return value.isEmpty() ? null : value;
    }

    static String nestedStringValue(JsonNode root, String parentKey, String childKey) {
        if (root == null || !root.isObject()) return null;
        JsonNode parent = root.get(parentKey);
        if (parent == null || !parent.isObject()) return null;
        return stringValue(parent, childKey, true);
    }

    static JsonNode objectValue(JsonNode root, String key) {
        if (root == null || !root.isObject()) return null;
        JsonNode node = root.get(key);
        return node != null && node.isObject() ? node : null;
    }

    private static void removeInvalidPermissionRules(ObjectNode settings) {
        JsonNode permissions = settings.get("permissions");
        if (permissions == null || !permissions.isObject()) return;
        for (String key : List.of("allow", "deny", "ask")) {
            JsonNode rules = permissions.get(key);
            if (rules == null || !rules.isArray()) continue;
            ArrayNode array = (ArrayNode) rules;
            for (int index = array.size() - 1; index >= 0; index--) {
                JsonNode rule = array.get(index);
                if (!rule.isTextual()
                        || !PermissionRuleValidation.validatePermissionRule(rule.asText()).valid()) {
                    array.remove(index);
                }
            }
        }
    }

    private static void collectPermissionWarnings(ObjectNode settings, String file,
                                                   List<SettingsValidationError> out) {
        JsonNode permissions = settings.get("permissions");
        if (permissions == null || !permissions.isObject()) return;
        for (String key : List.of("allow", "deny", "ask")) {
            JsonNode rules = permissions.get(key);
            if (rules == null || !rules.isArray()) continue;
            for (JsonNode rule : rules) {
                if (rule.isTextual()) {
                    PermissionRuleValidation.Result result =
                        PermissionRuleValidation.validatePermissionRule(rule.asText());
                    if (result.valid()) continue;
                    StringBuilder message = new StringBuilder(
                        "Invalid permission rule \"" + rule.asText() + "\" was skipped");
                    if (result.error() != null) message.append(": ").append(result.error());
                    if (result.suggestion() != null) message.append(". ").append(result.suggestion());
                    out.add(new SettingsValidationError(file, "permissions." + key, message.toString()));
                } else {
                    out.add(new SettingsValidationError(file, "permissions." + key,
                        "Non-string value in " + key + " array was removed"));
                }
            }
        }
    }

    private static void coerceEnvironmentValues(ObjectNode settings) {
        normalizeCatchGuardedSettings(settings);
        JsonNode env = settings.get("env");
        if (env == null || !env.isObject()) return;
        ObjectNode normalized = JsonUtils.getMapper().createObjectNode();
        env.fields().forEachRemaining(entry ->
            normalized.put(entry.getKey(), javascriptString(entry.getValue(), false)));
        settings.set("env", normalized);
    }

    private static void normalizeCatchGuardedSettings(ObjectNode settings) {
        JsonNode effort = settings.get("effortLevel");
        if (effort != null) {
            Set<String> allowedEffort = Strings.CS.equals("ant", System.getenv("USER_TYPE"))
                ? Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max")
                : Set.of("none", "minimal", "low", "medium", "high", "xhigh");
            if (!effort.isTextual() || !allowedEffort.contains(effort.asText())) {
                settings.remove("effortLevel");
            }
        }

        JsonNode strict = settings.get("strictPluginOnlyCustomization");
        if (strict == null || strict.isBoolean()) return;
        if (!strict.isArray()) {
            settings.remove("strictPluginOnlyCustomization");
            return;
        }
        ArrayNode filtered = JsonUtils.getMapper().createArrayNode();
        for (JsonNode surface : strict) {
            if (surface.isTextual() && Set.of("skills", "agents", "hooks", "mcp")
                    .contains(surface.asText())) {
                filtered.add(surface.asText());
            }
        }
        settings.set("strictPluginOnlyCustomization", filtered);
    }

    private static String javascriptString(JsonNode value, boolean arrayElement) {
        if (value == null || value.isNull()) return arrayElement ? "" : "null";
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return Boolean.toString(value.asBoolean());
        if (value.isNumber()) return javascriptNumberString(value);
        if (value.isArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode item : value) {
                if (!joined.isEmpty()) joined.append(',');
                joined.append(javascriptString(item, true));
            }
            return joined.toString();
        }
        if (value.isObject()) return "[object Object]";
        return value.asText();
    }

    private static String javascriptNumberString(JsonNode value) {
        double number = value.doubleValue();
        if (number == 0.0d) return "0";
        BigDecimal decimal = BigDecimal.valueOf(number).stripTrailingZeros();
        double magnitude = Math.abs(number);
        if (magnitude >= 1.0e-6 && magnitude < 1.0e21) {
            return decimal.toPlainString();
        }
        return decimal.toString().replace('E', 'e');
    }
}
