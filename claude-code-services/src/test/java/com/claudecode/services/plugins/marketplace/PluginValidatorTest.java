package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PluginValidatorTest {

    @TempDir
    Path tempDir;

    private Path writeManifest(String json) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("plugin/.claude-plugin"));
        Path manifest = dir.resolve("plugin.json");
        Files.writeString(manifest, json);
        return manifest;
    }

    private static List<String> errorMessages(PluginValidator.ValidationResult result) {
        return result.errors().stream().map(PluginValidator.ValidationError::message).toList();
    }

    private static List<String> warningMessages(PluginValidator.ValidationResult result) {
        return result.warnings().stream().map(PluginValidator.ValidationWarning::message).toList();
    }

    // ── manifest validation ───────────────────────────────────────────────────

    @Test
    void validManifestSucceedsWithoutWarnings() throws Exception {
        Path manifest = writeManifest("""
            {"name": "my-plugin", "version": "1.0.0", "description": "d",
             "author": {"name": "me"}}
            """);
        PluginValidator.ValidationResult result = PluginValidator.validatePluginManifest(manifest);
        assertTrue(result.success());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void missingFileReportsEnoent() {
        PluginValidator.ValidationResult result =
            PluginValidator.validatePluginManifest(tempDir.resolve("nope/plugin.json"));
        assertFalse(result.success());
        assertEquals("ENOENT", result.errors().getFirst().code());
        assertTrue(Strings.CS.startsWith(result.errors().getFirst().message(), "File not found: "));
    }

    @Test
    void invalidJsonReportsSyntaxError() throws Exception {
        Path manifest = writeManifest("{broken json");
        PluginValidator.ValidationResult result = PluginValidator.validatePluginManifest(manifest);
        assertFalse(result.success());
        assertTrue(Strings.CS.startsWith(result.errors().getFirst().message(), "Invalid JSON syntax:"));
    }

    @Test
    void nameWithSpacesIsAnError() throws Exception {
        Path manifest = writeManifest("{\"name\": \"My Plugin\"}");
        PluginValidator.ValidationResult result = PluginValidator.validatePluginManifest(manifest);
        assertFalse(result.success());
        assertTrue(errorMessages(result).contains(
            "Plugin name cannot contain spaces. Use kebab-case (e.g., \"my-plugin\")"));
    }

    @Test
    void emptyNameIsAnError() throws Exception {
        Path manifest = writeManifest("{\"name\": \"\"}");
        PluginValidator.ValidationResult result = PluginValidator.validatePluginManifest(manifest);
        assertTrue(errorMessages(result).contains("Plugin name cannot be empty"));
    }

    @Test
    void nonKebabNameIsOnlyAWarning() throws Exception {
        Path manifest = writeManifest(
            "{\"name\": \"MyPlugin\", \"version\": \"1.0.0\", \"description\": \"d\", "
                + "\"author\": {\"name\": \"me\"}}");
        PluginValidator.ValidationResult result = PluginValidator.validatePluginManifest(manifest);
        assertTrue(result.success());
        assertTrue(warningMessages(result).stream().anyMatch(w ->
            Strings.CS.contains(w, "is not kebab-case") && Strings.CS.contains(w, "Claude.ai marketplace sync")));
    }

    @Test
    void missingVersionDescriptionAuthorProduceTsWarnings() throws Exception {
        Path manifest = writeManifest("{\"name\": \"my-plugin\"}");
        PluginValidator.ValidationResult result = PluginValidator.validatePluginManifest(manifest);
        assertTrue(result.success());
        List<String> warnings = warningMessages(result);
        assertTrue(warnings.contains(
            "No version specified. Consider adding a version following semver (e.g., \"1.0.0\")"));
        assertTrue(warnings.contains(
            "No description provided. Adding a description helps users understand what your plugin does"));
        assertTrue(warnings.contains(
            "No author information provided. Consider adding author details for plugin attribution"));
    }

    @Test
    void nonSemverVersionWarns() throws Exception {
        Path manifest = writeManifest(
            "{\"name\": \"my-plugin\", \"version\": \"v1\", \"description\": \"d\", "
                + "\"author\": {\"name\": \"me\"}}");
        PluginValidator.ValidationResult result = PluginValidator.validatePluginManifest(manifest);
        assertTrue(result.success());
        assertTrue(warningMessages(result).contains(
            "Version \"v1\" does not follow semver (e.g., \"1.0.0\")"));
    }

    @Test
    void marketplaceOnlyFieldsWarnWithTsText() throws Exception {
        Path manifest = writeManifest(
            "{\"name\": \"my-plugin\", \"version\": \"1.0.0\", \"description\": \"d\", "
                + "\"author\": {\"name\": \"me\"}, \"category\": \"dev\", \"strict\": true}");
        PluginValidator.ValidationResult result = PluginValidator.validatePluginManifest(manifest);
        assertTrue(result.success());
        assertTrue(warningMessages(result).contains(
            "Field 'category' belongs in the marketplace entry (marketplace.json), not plugin.json. "
                + "It's harmless here but unused — Claude Code ignores it at load time."));
        assertEquals(2, result.warnings().size(), "category + strict and nothing else");
    }

    @Test
    void pathTraversalInCommandsIsAnError() throws Exception {
        Path manifest = writeManifest(
            "{\"name\": \"my-plugin\", \"commands\": [\"./ok.md\", \"../../etc/passwd\"]}");
        PluginValidator.ValidationResult result = PluginValidator.validatePluginManifest(manifest);
        assertFalse(result.success());
        assertTrue(errorMessages(result).contains(
            "Path contains \"..\" which could be a path traversal attempt: ../../etc/passwd"));
    }

    @Test
    void nonRelativeComponentPathIsAnError() throws Exception {
        Path manifest = writeManifest(
            "{\"name\": \"my-plugin\", \"agents\": \"agents/helper.md\"}");
        PluginValidator.ValidationResult result = PluginValidator.validatePluginManifest(manifest);
        assertFalse(result.success());
        assertTrue(errorMessages(result).contains(
            "Path must be relative to the plugin root and start with \"./\": agents/helper.md"));
    }

    // ── directory validation (component path existence) ──────────────────────

    @Test
    void validatePluginChecksDeclaredPathsExist() throws Exception {
        Path pluginDir = tempDir.resolve("plugin");
        writeManifest("""
            {"name": "my-plugin", "version": "1.0.0", "description": "d",
             "author": {"name": "me"},
             "commands": ["./commands/present.md", "./commands/missing.md"],
             "agents": "./agents/ghost.md",
             "skills": ["./skills/real-skill"],
             "hooks": "./hooks/hooks.json"}
            """);
        Files.createDirectories(pluginDir.resolve("commands"));
        Files.writeString(pluginDir.resolve("commands/present.md"), "# ok");
        Files.createDirectories(pluginDir.resolve("skills/real-skill"));

        PluginValidator.ValidationResult result = PluginValidator.validatePlugin(pluginDir);

        assertFalse(result.success());
        List<String> errors = errorMessages(result);
        assertTrue(errors.contains("Path not found: ./commands/missing.md (commands)"), errors.toString());
        assertTrue(errors.contains("Path not found: ./agents/ghost.md (agents)"), errors.toString());
        assertTrue(errors.contains("Path not found: ./hooks/hooks.json (hooks)"), errors.toString());
        assertFalse(errors.contains("Path not found: ./commands/present.md (commands)"));
        assertFalse(errors.contains("Path not found: ./skills/real-skill (skills)"));
    }

    @Test
    void validatePluginSucceedsWhenAllDeclaredPathsExist() throws Exception {
        Path pluginDir = tempDir.resolve("plugin");
        writeManifest("""
            {"name": "my-plugin", "version": "1.0.0", "description": "d",
             "author": {"name": "me"}, "commands": "./commands/hi.md"}
            """);
        Files.createDirectories(pluginDir.resolve("commands"));
        Files.writeString(pluginDir.resolve("commands/hi.md"), "# hi");

        assertTrue(PluginValidator.validatePlugin(pluginDir).success());
    }

    @Test
    void validatePluginWithoutManifestFailsWithFileNotFound() {
        Path pluginDir = tempDir.resolve("empty-plugin");
        PluginValidator.ValidationResult result = PluginValidator.validatePlugin(pluginDir);
        assertFalse(result.success());
        assertEquals("ENOENT", result.errors().getFirst().code());
    }
}
