package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Developer-facing plugin validation ({@code claude plugin validate} core): manifest shape,
 * path-traversal checks, marketplace-only stray fields, and declared component-path existence.
 */
public final class PluginValidator {


    static final Set<String> MARKETPLACE_ONLY_MANIFEST_FIELDS =
        Set.of("category", "source", "tags", "strict", "id");

    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern SEMVER = Pattern.compile(
        "^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z-.]+)?(\\+[0-9A-Za-z-.]+)?$");

    public record ValidationError(String path, String message, String code) {}

    public record ValidationWarning(String path, String message) {}

    public record ValidationResult(
        boolean success,
        List<ValidationError> errors,
        List<ValidationWarning> warnings,
        Path filePath,
        String fileType) {}

    private PluginValidator() {}

/** Validates a  manifest file. */
    public static ValidationResult validatePluginManifest(Path filePath) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        Path absolutePath = filePath.toAbsolutePath().normalize();

        if (!Files.exists(absolutePath)) {
            errors.add(new ValidationError("file", "File not found: " + absolutePath, "ENOENT"));
            return result(errors, warnings, absolutePath);
        }
        if (Files.isDirectory(absolutePath)) {
            errors.add(new ValidationError("file", "Path is not a file: " + absolutePath, "EISDIR"));
            return result(errors, warnings, absolutePath);
        }

        JsonNode parsed;
        try {
            parsed = JsonUtils.getMapper().readTree(Files.readString(absolutePath));
        } catch (IOException e) {
            errors.add(new ValidationError("json", "Invalid JSON syntax: " + e.getMessage(), null));
            return result(errors, warnings, absolutePath);
        }
        if (parsed == null || !parsed.isObject()) {
            errors.add(new ValidationError("json", "Invalid JSON syntax: manifest must be a JSON object", null));
            return result(errors, warnings, absolutePath);
        }

        checkComponentPathShapes(parsed, errors);
        warnMarketplaceOnlyFields(parsed, warnings);

        PluginManifest manifest;
        try {
            manifest = JsonUtils.getMapper().treeToValue(parsed, PluginManifest.class);
        } catch (IOException e) {
            errors.add(new ValidationError("root", "Manifest validation failed: " + e.getMessage(), null));
            return result(errors, warnings, absolutePath);
        }

        validateName(manifest.name(), errors, warnings);
        if (errors.isEmpty()) {
            addCommonWarnings(manifest, warnings);
        }
        return result(errors, warnings, absolutePath);
    }

    /**
     * Validates a plugin directory: its manifest plus existence of every
     * component path the manifest declares (commands/agents/skills/hooks).
     * Missing paths use the {@code path-not-found} error text from
     * {@code getPluginErrorMessage}.
     */
    public static ValidationResult validatePlugin(Path pluginDir) {
        Path manifestPath = pluginDir.resolve(".claude-plugin").resolve("plugin.json");
        ValidationResult manifestResult = validatePluginManifest(manifestPath);
        if (!manifestResult.success()) {
            return manifestResult;
        }

        List<ValidationError> errors = new ArrayList<>(manifestResult.errors());
        List<ValidationWarning> warnings = new ArrayList<>(manifestResult.warnings());

        PluginManifest manifest;
        try {
            manifest = JsonUtils.getMapper().readValue(manifestPath.toFile(), PluginManifest.class);
        } catch (IOException e) {
            errors.add(new ValidationError("json", "Invalid JSON syntax: " + e.getMessage(), null));
            return result(errors, warnings, manifestResult.filePath());
        }

        checkPathsExist(pluginDir, manifest.commandPaths(), PluginError.Component.COMMANDS, errors);
        checkPathsExist(pluginDir, manifest.agentPaths(), PluginError.Component.AGENTS, errors);
        checkPathsExist(pluginDir, manifest.skillPaths(), PluginError.Component.SKILLS, errors);
        checkPathsExist(pluginDir, manifest.hookPaths(), PluginError.Component.HOOKS, errors);

        return result(errors, warnings, manifestResult.filePath());
    }

    // ── checks ────────────────────────────────────────────────────────────────

    private static void validateName(String name, List<ValidationError> errors,
                                     List<ValidationWarning> warnings) {
        if (StringUtils.isEmpty(name)) {
            errors.add(new ValidationError("name", "Plugin name cannot be empty", null));
            return;
        }
        if (Strings.CS.contains(name, " ")) {
            errors.add(new ValidationError("name",
                "Plugin name cannot contain spaces. Use kebab-case (e.g., \"my-plugin\")", null));
            return;
        }
        // CC's schema only rejects spaces, but the Claude.ai marketplace sync
        // requires kebab-case — warn so authors catch it in CI.
        if (!KEBAB_CASE.matcher(name).matches()) {
            warnings.add(new ValidationWarning("name",
                "Plugin name \"" + name + "\" is not kebab-case. Claude Code accepts it, but the "
                    + "Claude.ai marketplace sync requires kebab-case (lowercase letters, digits, "
                    + "and hyphens only, e.g., \"my-plugin\")."));
        }
    }

    private static void addCommonWarnings(PluginManifest manifest, List<ValidationWarning> warnings) {
        if (StringUtils.isEmpty(manifest.version())) {
            warnings.add(new ValidationWarning("version",
                "No version specified. Consider adding a version following semver (e.g., \"1.0.0\")"));
        } else if (!SEMVER.matcher(manifest.version()).matches()) {

            // versioned cache paths tidy and update comparisons meaningful.
            warnings.add(new ValidationWarning("version",
                "Version \"" + manifest.version() + "\" does not follow semver (e.g., \"1.0.0\")"));
        }
        if (StringUtils.isEmpty(manifest.description())) {
            warnings.add(new ValidationWarning("description",
                "No description provided. Adding a description helps users understand what your plugin does"));
        }
        if (manifest.author() == null) {
            warnings.add(new ValidationWarning("author",
                "No author information provided. Consider adding author details for plugin attribution"));
        }
    }

    /**
     * Path-traversal + relative-shape checks on the raw JSON (pre-deserialization,
     * so security issues surface even if typed parsing would fail).
     */
    private static void checkComponentPathShapes(JsonNode parsed, List<ValidationError> errors) {
        for (String field : List.of("commands", "agents", "skills")) {
            JsonNode node = parsed.get(field);
            if (node == null || node.isNull()) {
                continue;
            }
            List<JsonNode> items = node.isArray()
                ? toList(node)
                : List.of(node);
            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                if (!item.isTextual()) {
                    continue;
                }
                String path = item.asText();
                if (Strings.CS.contains(path, "..")) {
                    errors.add(new ValidationError(field + "[" + i + "]",
                        "Path contains \"..\" which could be a path traversal attempt: " + path, null));
                } else if (!Strings.CS.startsWith(path, "./")) {
                    errors.add(new ValidationError(field + "[" + i + "]",
                        "Path must be relative to the plugin root and start with \"./\": " + path, null));
                }
            }
        }
    }

    private static void warnMarketplaceOnlyFields(JsonNode parsed, List<ValidationWarning> warnings) {
        parsed.fieldNames().forEachRemaining(field -> {
            if (MARKETPLACE_ONLY_MANIFEST_FIELDS.contains(field)) {
                warnings.add(new ValidationWarning(field,
                    "Field '" + field + "' belongs in the marketplace entry (marketplace.json), "
                        + "not plugin.json. It's harmless here but unused — Claude Code ignores "
                        + "it at load time."));
            }
        });
    }

    private static void checkPathsExist(Path pluginDir, List<String> declaredPaths,
                                        PluginError.Component component,
                                        List<ValidationError> errors) {
        for (String declared : declaredPaths) {
            if (Strings.CS.contains(declared, "..")) {
                continue; // Already reported as traversal by the manifest pass.
            }
            Path resolved = pluginDir.resolve(declared).normalize();
            if (!Files.exists(resolved)) {
                errors.add(new ValidationError(component.wire(),
                    new PluginError.PathNotFound(pluginDir.toString(), null, declared, component)
                        .getMessage(),
                    null));
            }
        }
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items;
    }

    private static ValidationResult result(List<ValidationError> errors,
                                           List<ValidationWarning> warnings, Path filePath) {
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors),
            List.copyOf(warnings), filePath, "plugin");
    }
}
