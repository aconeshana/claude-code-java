package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;

import com.claudecode.services.config.SettingsSchema.FieldError;
import com.claudecode.services.plugins.marketplace.MarketplaceNames;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.claudecode.services.config.SettingsSchema.checkBoolean;
import static com.claudecode.services.config.SettingsSchema.checkRequiredString;
import static com.claudecode.services.config.SettingsSchema.checkString;
import static com.claudecode.services.config.SettingsSchema.checkStringArray;
import static com.claudecode.services.config.SettingsSchema.err;
import static com.claudecode.services.config.SettingsSchema.expectedMsg;
import static com.claudecode.services.config.SettingsSchema.isValidUrl;
import static com.claudecode.services.config.SettingsSchema.present;

/**
 * Validates the plugin-marketplace and enterprise-MCP structures referenced by {@link
 * SettingsSchema}.
 */
final class MarketplaceSchemaValidator {

    private MarketplaceSchemaValidator() {}

    private static final Pattern SERVER_NAME = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final Pattern NPM_SCOPED =
        Pattern.compile("^@[a-z0-9][a-z0-9-._]*/[a-z0-9][a-z0-9-._]*$");
    private static final Pattern NPM_REGULAR = Pattern.compile("^[a-z0-9][a-z0-9-._]*$");

    // ── allowedMcpServers / deniedMcpServers ─────────────────────────────────


    static void validateMcpServerEntries(JsonNode node, String path, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isArray()) {
            err(errors, path, expectedMsg("array", node));
            return;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            String entryPath = path + "." + i;
            if (!entry.isObject()) {
                err(errors, entryPath, expectedMsg("object", entry));
                continue;
            }
            int before = errors.size();
            JsonNode serverName = entry.get("serverName");
            if (present(serverName)) {
                if (!serverName.isTextual()) {
                    err(errors, entryPath + ".serverName", expectedMsg("string", serverName));
                } else if (!SERVER_NAME.matcher(serverName.asText()).matches()) {
                    // zod invalid_format keeps its custom message through formatZodError.
                    err(errors, entryPath + ".serverName",
                        "Server name can only contain letters, numbers, hyphens, and underscores");
                }
            }
            JsonNode serverCommand = entry.get("serverCommand");
            if (present(serverCommand)) {
                if (!serverCommand.isArray()) {
                    err(errors, entryPath + ".serverCommand", expectedMsg("array", serverCommand));
                } else {
                    if (serverCommand.isEmpty()) {
                        // too_small(min 1): formatZodError overrides even the custom
                        // "at least one element" message with its numeric wording.
                        err(errors, entryPath + ".serverCommand",
                            "Number must be greater than or equal to 1");
                    }
                    for (int j = 0; j < serverCommand.size(); j++) {
                        if (!serverCommand.get(j).isTextual()) {
                            err(errors, entryPath + ".serverCommand." + j,
                                expectedMsg("string", serverCommand.get(j)));
                        }
                    }
                }
            }
            checkString(entry, entryPath, "serverUrl", errors);

            // zod .refine only runs when the base object parsed cleanly.
            if (errors.size() == before) {
                int defined = 0;
                if (entry.has("serverName")) defined++;
                if (entry.has("serverCommand")) defined++;
                if (entry.has("serverUrl")) defined++;
                if (defined != 1) {
                    err(errors, entryPath,
                        "Entry must have exactly one of \"serverName\", \"serverCommand\", or \"serverUrl\"");
                }
            }
        }
    }

    // ── extraKnownMarketplaces ───────────────────────────────────────────────


    static void validateExtraKnownMarketplaces(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "extraKnownMarketplaces", expectedMsg("record", node));
            return;
        }
        int before = errors.size();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String path = "extraKnownMarketplaces." + field.getKey();
            JsonNode entry = field.getValue();
            if (!entry.isObject()) {
                err(errors, path, expectedMsg("object", entry));
                continue;
            }
            validateSource(entry.get("source"), path + ".source", errors);
            checkString(entry, path, "installLocation", errors);
            checkBoolean(entry, path, "autoUpdate", errors);
        }

        if (errors.size() != before) return;
        fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode source = field.getValue().get("source");
            if (source != null && source.isObject()
                && Strings.CS.equals("settings", source.path("source").asText())
                && source.path("name").isTextual()
                && !field.getKey().equals(source.get("name").asText())) {
                err(errors, "extraKnownMarketplaces." + field.getKey() + ".source.name",
                    "Settings-sourced marketplace name must match its extraKnownMarketplaces key "
                        + "(got key \"" + field.getKey() + "\" but source.name \""
                        + source.get("name").asText() + "\")");
            }
        }
    }

    // ── strictKnownMarketplaces / blockedMarketplaces ────────────────────────


    static void validateSourceArray(JsonNode node, String path, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isArray()) {
            err(errors, path, expectedMsg("array", node));
            return;
        }
        for (int i = 0; i < node.size(); i++) {
            validateSource(node.get(i), path + "." + i, errors);
        }
    }

    // ── MarketplaceSourceSchema (discriminated union on "source") ────────────

    private static void validateSource(JsonNode source, String path, List<FieldError> errors) {
        if (source == null || !source.isObject()) {
            err(errors, path, expectedMsg("object", source));
            return;
        }
        JsonNode kind = source.get("source");
        String discriminator = kind != null && kind.isTextual() ? kind.asText() : null;
        switch (discriminator == null ? "" : discriminator) {
            case "url" -> {
                JsonNode url = source.get("url");
                if (url == null || !url.isTextual()) {
                    err(errors, path + ".url", expectedMsg("string", url));
                } else if (!isValidUrl(url.asText())) {
                    err(errors, path + ".url", "Invalid URL");
                }
                JsonNode headers = source.get("headers");
                if (present(headers)) {
                    if (!headers.isObject()) {
                        err(errors, path + ".headers", expectedMsg("record", headers));
                    } else {
                        Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> field = fields.next();
                            if (!field.getValue().isTextual()) {
                                err(errors, path + ".headers." + field.getKey(),
                                    expectedMsg("string", field.getValue()));
                            }
                        }
                    }
                }
            }
            case "github" -> {
                checkRequiredString(source, path, "repo", errors);
                checkString(source, path, "ref", errors);
                checkString(source, path, "path", errors);
                checkStringArray(source, "sparsePaths", path + ".sparsePaths", errors);
            }
            case "git" -> {
                checkRequiredString(source, path, "url", errors);
                checkString(source, path, "ref", errors);
                checkString(source, path, "path", errors);
                checkStringArray(source, "sparsePaths", path + ".sparsePaths", errors);
            }
            case "npm" -> {
                JsonNode pkg = source.get("package");
                if (pkg == null || !pkg.isTextual()) {
                    err(errors, path + ".package", expectedMsg("string", pkg));
                } else {
                    String name = pkg.asText();
                    if (Strings.CS.contains(name, "..") || Strings.CS.contains(name, "//")) {
                        err(errors, path + ".package",
                            "Package name cannot contain path traversal patterns");
                    }
                    if (!NPM_SCOPED.matcher(name).matches() && !NPM_REGULAR.matcher(name).matches()) {
                        err(errors, path + ".package", "Invalid npm package name format");
                    }
                }
            }
            case "file", "directory" -> checkRequiredString(source, path, "path", errors);
            case "hostPattern" -> checkRequiredString(source, path, "hostPattern", errors);
            case "pathPattern" -> checkRequiredString(source, path, "pathPattern", errors);
            case "settings" -> {
                JsonNode name = source.get("name");
                if (name == null || !name.isTextual()) {
                    err(errors, path + ".name", expectedMsg("string", name));
                } else {
                    String marketplaceName = name.asText();
                    validateMarketplaceName(marketplaceName, path + ".name", errors);
                    if (MarketplaceNames.ALLOWED_OFFICIAL_MARKETPLACE_NAMES.contains(
                            marketplaceName.toLowerCase(Locale.ROOT))) {
                        err(errors, path + ".name",
                            "Reserved official marketplace names cannot be used with settings sources. "
                                + "validateOfficialNameSource only accepts github/git sources from anthropics/* "
                                + "for these names; a settings source would be rejected after "
                                + "loadAndCacheMarketplace has already written to disk with cleanupNeeded=false.");
                    }
                }
                JsonNode plugins = source.get("plugins");
                if (plugins == null || !plugins.isArray()) {
                    err(errors, path + ".plugins", expectedMsg("array", plugins));
                } else {
                    for (int i = 0; i < plugins.size(); i++) {
                        validateSettingsMarketplacePlugin(
                            plugins.get(i), path + ".plugins." + i, errors);
                    }
                }
                JsonNode owner = source.get("owner");
                if (present(owner)) {
                    if (!owner.isObject()) {
                        err(errors, path + ".owner", expectedMsg("object", owner));
                    } else {
                        checkRequiredString(owner, path + ".owner", "name", errors);
                        JsonNode ownerName = owner.get("name");
                        if (ownerName != null && ownerName.isTextual() && ownerName.asText().isEmpty()) {
                            err(errors, path + ".owner.name",
                                "Number must be greater than or equal to 1");
                        }
                        checkString(owner, path + ".owner", "email", errors);
                        checkString(owner, path + ".owner", "url", errors);
                    }
                }
            }
            // Unmatched (or missing) discriminator: zod invalid_union at the
            // discriminator path, default message.
            default -> err(errors, path + ".source", "Invalid input");
        }
    }


    private static void validateMarketplaceName(String name, String path, List<FieldError> errors) {
        if (name.isEmpty()) {
// z.string.min(1,...): too_small → formatZodError numeric wording.
            err(errors, path, "Number must be greater than or equal to 1");
        }
        if (Strings.CS.contains(name, " ")) {
            err(errors, path,
                "Marketplace name cannot contain spaces. Use kebab-case (e.g., \"my-marketplace\")");
        }
        if (Strings.CS.contains(name, "/") || Strings.CS.contains(name, "\\") || Strings.CS.contains(name, "..") || Strings.CS.equals(name, ".")) {
            err(errors, path,
                "Marketplace name cannot contain path separators (/ or \\), \"..\" sequences, or be \".\"");
        }
        if (MarketplaceNames.isBlockedOfficialName(name)) {
            err(errors, path,
                "Marketplace name impersonates an official Anthropic/Claude marketplace");
        }
        if (Strings.CI.equals(name, "inline")) {
            err(errors, path,
                "Marketplace name \"inline\" is reserved for --plugin-dir session plugins");
        }
        if (Strings.CI.equals(name, "builtin")) {
            err(errors, path, "Marketplace name \"builtin\" is reserved for built-in plugins");
        }
    }


    private static void validateSettingsMarketplacePlugin(
            JsonNode plugin, String path, List<FieldError> errors) {
        if (plugin == null || !plugin.isObject()) {
            err(errors, path, expectedMsg("object", plugin));
            return;
        }
        JsonNode name = plugin.get("name");
        if (name == null || !name.isTextual()) {
            err(errors, path + ".name", expectedMsg("string", name));
        } else {
            if (name.asText().isEmpty()) {
                err(errors, path + ".name", "Number must be greater than or equal to 1");
            }
            if (Strings.CS.contains(name.asText(), " ")) {
                err(errors, path + ".name",
                    "Plugin name cannot contain spaces. Use kebab-case (e.g., \"my-plugin\")");
            }
        }

        JsonNode source = plugin.get("source");
        if (source == null) {
            err(errors, path + ".source", expectedMsg("object", null));
        } else if (source.isTextual()) {
            // PluginSourceSchema accepts relative paths, then the refine below rejects them
            // for settings-sourced marketplaces because there is no marketplace root.
            err(errors, path,
                "Plugins in a settings-sourced marketplace must use remote sources (github, git-subdir, npm, url, pip). Relative-path sources like \"./foo\" have no marketplace repository to resolve against.");
        } else if (!source.isObject()) {
            err(errors, path + ".source", expectedMsg("object", source));
        } else {
            validatePluginSource(source, path + ".source", errors);
        }
        checkString(plugin, path, "description", errors);
        checkString(plugin, path, "version", errors);
        checkBoolean(plugin, path, "strict", errors);
    }


    private static void validatePluginSource(JsonNode source, String path, List<FieldError> errors) {
        String kind = source.path("source").isTextual() ? source.path("source").asText() : "";
        switch (kind) {
            case "npm", "pip" -> {
                checkRequiredString(source, path, "package", errors);
                checkString(source, path, "version", errors);
                JsonNode registry = source.get("registry");
                if (present(registry)) {
                    if (!registry.isTextual()) {
                        err(errors, path + ".registry", expectedMsg("string", registry));
                    } else if (!isValidUrl(registry.asText())) {
                        err(errors, path + ".registry", "Invalid URL");
                    }
                }
            }
            case "url", "github" -> {
                String required =Strings.CS.equals( "url", kind) ? "url" : "repo";
                checkRequiredString(source, path, required, errors);
                checkString(source, path, "ref", errors);
                validateGitSha(source.get("sha"), path + ".sha", errors);
            }
            case "git-subdir" -> {
                checkRequiredString(source, path, "url", errors);
                JsonNode subdir = source.get("path");
                if (subdir == null || !subdir.isTextual()) {
                    err(errors, path + ".path", expectedMsg("string", subdir));
                } else if (subdir.asText().isEmpty()) {
                    err(errors, path + ".path", "Number must be greater than or equal to 1");
                }
                checkString(source, path, "ref", errors);
                validateGitSha(source.get("sha"), path + ".sha", errors);
            }
            default -> err(errors, path + ".source", "Invalid input");
        }
    }

    private static void validateGitSha(JsonNode sha, String path, List<FieldError> errors) {
        if (!present(sha)) return;
        if (!sha.isTextual()) {
            err(errors, path, expectedMsg("string", sha));
            return;
        }
        if (!sha.asText().matches("[a-f0-9]{40}")) {
            err(errors, path, "Invalid input");
        }
    }
}
