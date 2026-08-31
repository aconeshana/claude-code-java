package com.claudecode.services.config;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class SettingsSchema {

    private SettingsSchema() {}

    /** One field-level validation error: dot-notation path + human-readable message. */
    public record FieldError(String path, String message) {}


    static final String SETTINGS_SCHEMA_URL =
        "https://json.schemastore.org/claude-code-settings.json";


    private static final List<String> PERMISSION_MODES =
        List.of("acceptEdits", "bypassPermissions", "default", "dontAsk", "plan");

    /**
     * Validates a parsed settings tree. Never throws — any unexpected internal
     * failure returns an empty list (diagnostics must not break doctor).
     */
    public static List<FieldError> validate(JsonNode root) {
        try {
            return validateRoot(root);
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    /**
     * Applies Zod's default object parsing semantics to an already validated
     * settings tree. The outer settings object, permissions, sandbox, and
     * record-shaped values are passthrough; ordinary nested {@code z.object}
     * values strip unknown keys. Jackson's hand-written validator only checks
     * types, so this normalization is kept separate from diagnostics and is
     * applied by the settings loader after a source has been accepted.
     */
    public static ObjectNode sanitize(JsonNode root) {
        if (root == null || !root.isObject()) return null;
        ObjectNode copy = root.deepCopy();

        stripObject(copy, "attribution", Set.of("commit", "pr"));
        stripObject(copy, "fileSuggestion", Set.of("type", "command"));

        stripObject(copy, "worktree",
            Set.of("symlinkDirectories", "sparsePaths", "baseRef"));
        stripObject(copy, "statusLine", Set.of("type", "command", "padding"));
        stripObject(copy, "spinnerVerbs", Set.of("mode", "verbs"));
        stripObject(copy, "spinnerTipsOverride", Set.of("excludeDefault", "tips"));
        stripObject(copy, "remote", Set.of("defaultEnvironmentId"));

        sanitizeAllowedChannelPlugins(copy.get("allowedChannelPlugins"));
        sanitizeSshConfigs(copy.get("sshConfigs"));
        sanitizeMcpServerEntries(copy.get("allowedMcpServers"));
        sanitizeMcpServerEntries(copy.get("deniedMcpServers"));
        sanitizeHooks(copy.get("hooks"));
        sanitizeSandbox(copy.get("sandbox"));
        sanitizePluginConfigs(copy.get("pluginConfigs"));
        sanitizeExtraKnownMarketplaces(copy.get("extraKnownMarketplaces"));
        sanitizeMarketplaceSources(copy.get("strictKnownMarketplaces"));
        sanitizeMarketplaceSources(copy.get("blockedMarketplaces"));
        return copy;
    }

    private static void stripObject(ObjectNode parent, String key, Set<String> allowed) {
        JsonNode child = parent.get(key);
        if (child != null && child.isObject()) stripObject((ObjectNode) child, allowed);
    }

    private static void stripObject(ObjectNode object, Set<String> allowed) {
        Set<String> remove = new HashSet<>();
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) remove.add(name);
        });
        remove.forEach(object::remove);
    }

    private static void sanitizeAllowedChannelPlugins(JsonNode node) {
        if (node == null || !node.isArray()) return;
        for (JsonNode entry : node) {
            if (entry.isObject()) stripObject((ObjectNode) entry, Set.of("marketplace", "plugin"));
        }
    }

    private static void sanitizeSshConfigs(JsonNode node) {
        if (node == null || !node.isArray()) return;
        Set<String> allowed = Set.of("id", "name", "sshHost", "sshPort",
            "sshIdentityFile", "startDirectory");
        for (JsonNode entry : node) {
            if (entry.isObject()) stripObject((ObjectNode) entry, allowed);
        }
    }

    private static void sanitizeMcpServerEntries(JsonNode node) {
        if (node == null || !node.isArray()) return;
        Set<String> allowed = Set.of("serverName", "serverCommand", "serverUrl");
        for (JsonNode entry : node) {
            if (entry.isObject()) stripObject((ObjectNode) entry, allowed);
        }
    }

    private static void sanitizeHooks(JsonNode node) {
        if (node == null || !node.isObject()) return;
        node.fields().forEachRemaining(event -> {
            JsonNode matchers = event.getValue();
            if (matchers == null || !matchers.isArray()) return;
            for (JsonNode matcher : matchers) {
                if (!matcher.isObject()) continue;
                ObjectNode matcherObject = (ObjectNode) matcher;
                stripObject(matcherObject, Set.of("matcher", "hooks"));
                JsonNode hooks = matcherObject.get("hooks");
                if (hooks == null || !hooks.isArray()) continue;
                for (JsonNode hook : hooks) sanitizeHook(hook);
            }
        });
    }

    private static void sanitizeHook(JsonNode node) {
        if (node == null || !node.isObject()) return;
        ObjectNode hook = (ObjectNode) node;
        String type = hook.path("type").asText("");
        Set<String> allowed = switch (type) {
            case "command" -> Set.of("type", "command", "if", "shell", "timeout",
                "statusMessage", "once", "async", "asyncRewake");
            case "prompt", "agent" -> Set.of("type", "prompt", "if", "timeout",
                "model", "statusMessage", "once");
            case "http" -> Set.of("type", "url", "if", "timeout", "headers",
                "allowedEnvVars", "statusMessage", "once");
            default -> Set.of("type");
        };
        stripObject(hook, allowed);
    }

    private static void sanitizeSandbox(JsonNode node) {
        if (node == null || !node.isObject()) return;
        ObjectNode sandbox = (ObjectNode) node;
        JsonNode network = sandbox.get("network");
        if (network != null && network.isObject()) {
            stripObject((ObjectNode) network, Set.of("allowedDomains", "allowManagedDomainsOnly",
                "allowUnixSockets", "allowAllUnixSockets", "allowLocalBinding",
                "httpProxyPort", "socksProxyPort"));
        }
        JsonNode filesystem = sandbox.get("filesystem");
        if (filesystem != null && filesystem.isObject()) {
            stripObject((ObjectNode) filesystem, Set.of("allowWrite", "denyWrite", "denyRead",
                "allowRead", "allowManagedReadPathsOnly"));
        }
        JsonNode ripgrep = sandbox.get("ripgrep");
        if (ripgrep != null && ripgrep.isObject()) {
            stripObject((ObjectNode) ripgrep, Set.of("command", "args"));
        }
    }

    private static void sanitizePluginConfigs(JsonNode node) {
        if (node == null || !node.isObject()) return;
        node.fields().forEachRemaining(plugin -> {
            JsonNode config = plugin.getValue();
            if (config.isObject()) stripObject((ObjectNode) config, Set.of("mcpServers", "options"));
        });
    }

    private static void sanitizeExtraKnownMarketplaces(JsonNode node) {
        if (node == null || !node.isObject()) return;
        node.fields().forEachRemaining(entry -> {
            JsonNode marketplace = entry.getValue();
            if (!marketplace.isObject()) return;
            ObjectNode object = (ObjectNode) marketplace;
            stripObject(object, Set.of("source", "installLocation", "autoUpdate"));
            sanitizeMarketplaceSource(object.get("source"));
        });
    }

    private static void sanitizeMarketplaceSources(JsonNode node) {
        if (node == null || !node.isArray()) return;
        for (JsonNode source : node) sanitizeMarketplaceSource(source);
    }

    private static void sanitizeMarketplaceSource(JsonNode node) {
        if (node == null || !node.isObject()) return;
        ObjectNode source = (ObjectNode) node;
        switch (source.path("source").asText("")) {
            case "url" -> stripObject(source, Set.of("source", "url", "headers"));
            case "github" -> stripObject(source,
                Set.of("source", "repo", "ref", "path", "sparsePaths"));
            case "git" -> stripObject(source,
                Set.of("source", "url", "ref", "path", "sparsePaths"));
            case "npm" -> stripObject(source, Set.of("source", "package"));
            case "file", "directory" -> stripObject(source, Set.of("source", "path"));
            case "hostPattern" -> stripObject(source, Set.of("source", "hostPattern"));
            case "pathPattern" -> stripObject(source, Set.of("source", "pathPattern"));
            case "settings" -> {
                stripObject(source, Set.of("source", "name", "plugins", "owner"));
                JsonNode owner = source.get("owner");
                if (owner != null && owner.isObject()) {
                    stripObject((ObjectNode) owner, Set.of("name", "email", "url"));
                }
                JsonNode plugins = source.get("plugins");
                if (plugins != null && plugins.isArray()) {
                    for (JsonNode plugin : plugins) sanitizeSettingsMarketplacePlugin(plugin);
                }
            }
            default -> { /* invalid sources are rejected before sanitization */ }
        }
    }

    private static void sanitizeSettingsMarketplacePlugin(JsonNode node) {
        if (node == null || !node.isObject()) return;
        ObjectNode plugin = (ObjectNode) node;
        stripObject(plugin, Set.of("name", "source", "description", "version", "strict"));
        JsonNode source = plugin.get("source");
        if (source != null && source.isObject()) {
            ObjectNode sourceObject = (ObjectNode) source;
            switch (sourceObject.path("source").asText("")) {
                case "npm", "pip" -> stripObject(sourceObject,
                    Set.of("source", "package", "version", "registry"));
                case "url" -> stripObject(sourceObject, Set.of("source", "url", "ref", "sha"));
                case "github" -> stripObject(sourceObject, Set.of("source", "repo", "ref", "sha"));
                case "git-subdir" -> stripObject(sourceObject,
                    Set.of("source", "url", "path", "ref", "sha"));
                default -> { /* source validation handles unsupported variants */ }
            }
        }
    }

    private static List<FieldError> validateRoot(JsonNode root) {
        List<FieldError> errors = new ArrayList<>();
        if (root == null || root.isNull()) {

            errors.add(new FieldError("", "Invalid or malformed JSON"));
            return errors;
        }
        if (!root.isObject()) {
            errors.add(new FieldError("", "Expected object, but received " + typeName(root)));
            return errors;
        }


        validatePermissionRuleArrays(root.get("permissions"), errors);

        // ── strings ──────────────────────────────────────────────────────────
        for (String key : List.of(
            "apiKeyHelper", "awsCredentialExport", "awsAuthRefresh", "gcpAuthRefresh",
            "model", "forceLoginOrgUUID", "otelHeadersHelper", "outputStyle", "language",
            "advisorModel", "agent", "minimumVersion", "plansDirectory",
            "autoMemoryDirectory", "pluginTrustMessage")) {
            checkString(root, key, errors);
        }

        // ── booleans ─────────────────────────────────────────────────────────
        for (String key : List.of(
            "respectGitignore", "includeCoAuthoredBy", "includeGitInstructions",
            "enableAllProjectMcpServers", "disableAllHooks", "allowManagedHooksOnly",
            "allowManagedPermissionRulesOnly", "allowManagedMcpServersOnly",
            "skipWebFetchPreflight", "spinnerTipsEnabled", "syntaxHighlightingDisabled",
            "terminalTitleFromRename", "alwaysThinkingEnabled", "fastMode",
            "fastModePerSessionOptIn", "promptSuggestionEnabled",
            "showClearContextOnPlanAccept", "channelsEnabled", "prefersReducedMotion",
            "autoMemoryEnabled", "autoDreamEnabled", "showThinkingSummaries",
            "skipDangerousModePermissionPrompt", "switchModelsOnFlag")) {
            checkBoolean(root, key, errors);
        }

        // ── string arrays ────────────────────────────────────────────────────
        for (String key : List.of(
            "availableModels", "enabledMcpjsonServers", "disabledMcpjsonServers",
            "allowedHttpHookUrls", "httpHookAllowedEnvVars", "companyAnnouncements",
            "claudeMdExcludes")) {
            checkStringArray(root, key, key, errors);
        }

        // ── enums / literal ──────────────────────────────────────────────────
        checkEnum(root, "defaultShell", List.of("bash", "powershell"), errors);
        checkEnum(root, "forceLoginMethod", List.of("claudeai", "console"), errors);
        checkEnum(root, "autoUpdatesChannel", List.of("latest", "stable"), errors);
        checkEnum(root, "disableAutoMode", List.of("disable"), errors);
        checkLiteral(root, "$schema", SETTINGS_SCHEMA_URL, errors);

        // — invalid values are silently dropped, never reported. Do not validate.

        // ── numbers ──────────────────────────────────────────────────────────
        JsonNode cleanup = root.get("cleanupPeriodDays");
        if (present(cleanup)) {
            if (!cleanup.isNumber()) {
                err(errors, "cleanupPeriodDays", expectedMsg("number", cleanup));
            } else {
                if (cleanup.asDouble() < 0) {
                    err(errors, "cleanupPeriodDays", "Number must be greater than or equal to 0");
                }
                if (!isInteger(cleanup)) {
                    err(errors, "cleanupPeriodDays", "Expected int, but received number");
                }
            }
        }
        JsonNode subagentMaxDepth = root.get("subagentMaxDepth");
        if (present(subagentMaxDepth)) {
            if (!subagentMaxDepth.isNumber()) {
                err(errors, "subagentMaxDepth", expectedMsg("number", subagentMaxDepth));
            } else if (!subagentMaxDepth.isIntegralNumber()) {
                err(errors, "subagentMaxDepth", "Expected int, but received number");
            } else if (subagentMaxDepth.asInt() < 1 || subagentMaxDepth.asInt() > 5) {
                err(errors, "subagentMaxDepth", "Number must be between 1 and 5");
            }
        }
        JsonNode surveyRate = root.get("feedbackSurveyRate");
        if (present(surveyRate)) {
            if (!surveyRate.isNumber()) {
                err(errors, "feedbackSurveyRate", expectedMsg("number", surveyRate));
            } else {
                if (surveyRate.asDouble() < 0) {
                    err(errors, "feedbackSurveyRate", "Number must be greater than or equal to 0");
                }
                if (surveyRate.asDouble() > 1) {
                    // too_big has no formatZodError branch — zod v4's own message survives.
                    err(errors, "feedbackSurveyRate", "Too big: expected number to be <=1");
                }
            }
        }

        // ── env: record(string, z.coerce.string()) — every JSON value coerces,
        //    so only the record shape itself can fail ─────────────────────────
        JsonNode env = root.get("env");
        if (present(env) && !env.isObject()) {
            err(errors, "env", expectedMsg("record", env));
        }

        // ── modelOverrides: record(string, string) ───────────────────────────
        checkStringRecord(root.get("modelOverrides"), "modelOverrides", errors);

        // ── simple nested objects ────────────────────────────────────────────
        validateAttribution(root.get("attribution"), errors);
        validateFileSuggestion(root.get("fileSuggestion"), errors);
        validateStatusLine(root.get("statusLine"), errors);
        validateWorktree(root.get("worktree"), errors);
        validateSpinnerVerbs(root.get("spinnerVerbs"), errors);
        validateSpinnerTipsOverride(root.get("spinnerTipsOverride"), errors);
        validateRemote(root.get("remote"), errors);
        validateAllowedChannelPlugins(root.get("allowedChannelPlugins"), errors);
        validateSshConfigs(root.get("sshConfigs"), errors);
        validatePermissions(root.get("permissions"), errors);
        validateSandbox(root.get("sandbox"), errors);
        validateEnabledPlugins(root.get("enabledPlugins"), errors);
        validatePluginConfigs(root.get("pluginConfigs"), errors);

        // ── deep structures with their own validators ────────────────────────
        HooksSchemaValidator.validate(root.get("hooks"), errors);
        MarketplaceSchemaValidator.validateMcpServerEntries(
            root.get("allowedMcpServers"), "allowedMcpServers", errors);
        MarketplaceSchemaValidator.validateMcpServerEntries(
            root.get("deniedMcpServers"), "deniedMcpServers", errors);
        MarketplaceSchemaValidator.validateExtraKnownMarketplaces(
            root.get("extraKnownMarketplaces"), errors);
        MarketplaceSchemaValidator.validateSourceArray(
            root.get("strictKnownMarketplaces"), "strictKnownMarketplaces", errors);
        MarketplaceSchemaValidator.validateSourceArray(
            root.get("blockedMarketplaces"), "blockedMarketplaces", errors);

        // Outer schema is .passthrough(): unknown top-level keys are accepted silently.
        return errors;
    }

    // ── permissions ──────────────────────────────────────────────────────────


    private static void validatePermissionRuleArrays(JsonNode permissions, List<FieldError> errors) {
        if (permissions == null || !permissions.isObject()) return;
        for (String key : List.of("allow", "deny", "ask")) {
            JsonNode rules = permissions.get(key);
            if (rules == null || !rules.isArray()) continue;
            for (JsonNode rule : rules) {
                if (!rule.isTextual()) {
                    err(errors, "permissions." + key,
                        "Non-string value in " + key + " array was removed");
                    continue;
                }
                PermissionRuleValidation.Result result =
                    PermissionRuleValidation.validatePermissionRule(rule.asText());
                if (!result.valid()) {
                    StringBuilder message = new StringBuilder(
                        "Invalid permission rule \"" + rule.asText() + "\" was skipped");
                    if (result.error() != null) message.append(": ").append(result.error());
                    if (result.suggestion() != null) message.append(". ").append(result.suggestion());
                    err(errors, "permissions." + key, message.toString());
                }
            }
        }
    }


    private static void validatePermissions(JsonNode permissions, List<FieldError> errors) {
        if (!present(permissions)) return;
        if (!permissions.isObject()) {
            err(errors, "permissions", expectedMsg("object", permissions));
            return;
        }
        for (String key : List.of("allow", "deny", "ask")) {
            JsonNode rules = permissions.get(key);
            if (present(rules) && !rules.isArray()) {
                err(errors, "permissions." + key, expectedMsg("array", rules));
            }

            // filter removed them before PermissionRuleSchema could fire).
        }
        checkEnum(permissions, "permissions", "defaultMode", PERMISSION_MODES, errors);
        checkEnum(permissions, "permissions", "disableBypassPermissionsMode", List.of("disable"), errors);
        checkStringArray(permissions, "additionalDirectories", "permissions.additionalDirectories", errors);
    }

    // ── simple nested objects ────────────────────────────────────────────────


    private static void validateAttribution(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "attribution", expectedMsg("object", node));
            return;
        }
        checkString(node, "attribution", "commit", errors);
        checkString(node, "attribution", "pr", errors);
    }


    private static void validateFileSuggestion(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "fileSuggestion", expectedMsg("object", node));
            return;
        }
        checkRequiredLiteral(node, "fileSuggestion", "type", "command", errors);
        checkRequiredString(node, "fileSuggestion", "command", errors);
    }


    private static void validateStatusLine(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "statusLine", expectedMsg("object", node));
            return;
        }
        checkRequiredLiteral(node, "statusLine", "type", "command", errors);
        checkRequiredString(node, "statusLine", "command", errors);
        checkNumber(node, "statusLine", "padding", errors);
    }


    private static void validateWorktree(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "worktree", expectedMsg("object", node));
            return;
        }
        checkStringArray(node, "symlinkDirectories", "worktree.symlinkDirectories", errors);
        checkStringArray(node, "sparsePaths", "worktree.sparsePaths", errors);
        checkEnum(node, "worktree", "baseRef", List.of("fresh", "head"), errors);
    }


    private static void validateSpinnerVerbs(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "spinnerVerbs", expectedMsg("object", node));
            return;
        }
        JsonNode mode = node.get("mode");
        if (mode == null || !mode.isTextual() || !List.of("append", "replace").contains(mode.asText())) {
            err(errors, "spinnerVerbs.mode", enumMsg(List.of("append", "replace")));
        }
        JsonNode verbs = node.get("verbs");
        if (verbs == null) {
            err(errors, "spinnerVerbs.verbs", "Expected array, but received undefined");
        } else {
            checkStringArray(node, "verbs", "spinnerVerbs.verbs", errors);
        }
    }


    private static void validateSpinnerTipsOverride(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "spinnerTipsOverride", expectedMsg("object", node));
            return;
        }
        checkBoolean(node, "spinnerTipsOverride", "excludeDefault", errors);
        JsonNode tips = node.get("tips");
        if (tips == null) {
            err(errors, "spinnerTipsOverride.tips", "Expected array, but received undefined");
        } else {
            checkStringArray(node, "tips", "spinnerTipsOverride.tips", errors);
        }
    }


    private static void validateRemote(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "remote", expectedMsg("object", node));
            return;
        }
        checkString(node, "remote", "defaultEnvironmentId", errors);
    }


    private static void validateAllowedChannelPlugins(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isArray()) {
            err(errors, "allowedChannelPlugins", expectedMsg("array", node));
            return;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            String path = "allowedChannelPlugins." + i;
            if (!entry.isObject()) {
                err(errors, path, expectedMsg("object", entry));
                continue;
            }
            checkRequiredString(entry, path, "marketplace", errors);
            checkRequiredString(entry, path, "plugin", errors);
        }
    }


    private static void validateSshConfigs(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isArray()) {
            err(errors, "sshConfigs", expectedMsg("array", node));
            return;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            String path = "sshConfigs." + i;
            if (!entry.isObject()) {
                err(errors, path, expectedMsg("object", entry));
                continue;
            }
            checkRequiredString(entry, path, "id", errors);
            checkRequiredString(entry, path, "name", errors);
            checkRequiredString(entry, path, "sshHost", errors);
            JsonNode port = entry.get("sshPort");
            if (present(port)) {
                if (!port.isNumber()) {
                    err(errors, path + ".sshPort", expectedMsg("number", port));
                } else if (!isInteger(port)) {
                    err(errors, path + ".sshPort", "Expected int, but received number");
                }
            }
            checkString(entry, path, "sshIdentityFile", errors);
            checkString(entry, path, "startDirectory", errors);
        }
    }

// ── sandbox () ────────────────────────────────


    private static void validateSandbox(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "sandbox", expectedMsg("object", node));
            return;
        }
        for (String key : List.of("enabled", "failIfUnavailable", "autoAllowBashIfSandboxed",
            "allowUnsandboxedCommands", "enableWeakerNestedSandbox",
            "enableWeakerNetworkIsolation")) {
            checkBoolean(node, "sandbox", key, errors);
        }
        checkStringArray(node, "excludedCommands", "sandbox.excludedCommands", errors);

        // sandbox adapter validates/handles malformed values at use time.

        JsonNode violations = node.get("ignoreViolations");
        if (present(violations)) {
            if (!violations.isObject()) {
                err(errors, "sandbox.ignoreViolations", expectedMsg("record", violations));
            } else {
                Iterator<Map.Entry<String, JsonNode>> fields = violations.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    checkStringArray(violations, field.getKey(),
                        "sandbox.ignoreViolations." + field.getKey(), errors);
                }
            }
        }

        JsonNode network = node.get("network");
        if (present(network)) {
            if (!network.isObject()) {
                err(errors, "sandbox.network", expectedMsg("object", network));
            } else {
                checkStringArray(network, "allowedDomains", "sandbox.network.allowedDomains", errors);
                checkStringArray(network, "allowUnixSockets", "sandbox.network.allowUnixSockets", errors);
                checkBoolean(network, "sandbox.network", "allowManagedDomainsOnly", errors);
                checkBoolean(network, "sandbox.network", "allowAllUnixSockets", errors);
                checkBoolean(network, "sandbox.network", "allowLocalBinding", errors);
                checkNumber(network, "sandbox.network", "httpProxyPort", errors);
                checkNumber(network, "sandbox.network", "socksProxyPort", errors);
            }
        }

        JsonNode fs = node.get("filesystem");
        if (present(fs)) {
            if (!fs.isObject()) {
                err(errors, "sandbox.filesystem", expectedMsg("object", fs));
            } else {
                for (String key : List.of("allowWrite", "denyWrite", "denyRead", "allowRead")) {
                    checkStringArray(fs, key, "sandbox.filesystem." + key, errors);
                }
                checkBoolean(fs, "sandbox.filesystem", "allowManagedReadPathsOnly", errors);
            }
        }

        JsonNode ripgrep = node.get("ripgrep");
        if (present(ripgrep)) {
            if (!ripgrep.isObject()) {
                err(errors, "sandbox.ripgrep", expectedMsg("object", ripgrep));
            } else {
                checkRequiredString(ripgrep, "sandbox.ripgrep", "command", errors);
                checkStringArray(ripgrep, "args", "sandbox.ripgrep.args", errors);
            }
        }
    }

    // ── plugin-related records ───────────────────────────────────────────────


    private static void validateEnabledPlugins(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "enabledPlugins", expectedMsg("record", node));
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            boolean ok = value.isBoolean() || isStringArray(value);
            if (!ok) {
                // zod reports a failed union at the union root with "Invalid input".
                err(errors, "enabledPlugins." + field.getKey(), "Invalid input");
            }
        }
    }


    private static void validatePluginConfigs(JsonNode node, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, "pluginConfigs", expectedMsg("record", node));
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> plugins = node.fields();
        while (plugins.hasNext()) {
            Map.Entry<String, JsonNode> plugin = plugins.next();
            String base = "pluginConfigs." + plugin.getKey();
            JsonNode config = plugin.getValue();
            if (!config.isObject()) {
                err(errors, base, expectedMsg("object", config));
                continue;
            }
            JsonNode mcpServers = config.get("mcpServers");
            if (present(mcpServers)) {
                if (!mcpServers.isObject()) {
                    err(errors, base + ".mcpServers", expectedMsg("record", mcpServers));
                } else {
                    Iterator<Map.Entry<String, JsonNode>> servers = mcpServers.fields();
                    while (servers.hasNext()) {
                        Map.Entry<String, JsonNode> server = servers.next();
                        String serverPath = base + ".mcpServers." + server.getKey();
                        if (!server.getValue().isObject()) {
                            err(errors, serverPath, expectedMsg("record", server.getValue()));
                            continue;
                        }
                        checkConfigValueRecord(server.getValue(), serverPath, errors);
                    }
                }
            }
            JsonNode options = config.get("options");
            if (present(options)) {
                if (!options.isObject()) {
                    err(errors, base + ".options", expectedMsg("record", options));
                } else {
                    checkConfigValueRecord(options, base + ".options", errors);
                }
            }
        }
    }

    /** Each value must satisfy {@code union[string, number, boolean, string[]]}. */
    private static void checkConfigValueRecord(JsonNode record, String path, List<FieldError> errors) {
        Iterator<Map.Entry<String, JsonNode>> fields = record.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode v = field.getValue();
            boolean ok = v.isTextual() || v.isNumber() || v.isBoolean() || isStringArray(v);
            if (!ok) {
                err(errors, path + "." + field.getKey(), "Invalid input");
            }
        }
    }

    // ── shared checker helpers (package-private for the sibling validators) ──

    static void err(List<FieldError> errors, String path, String message) {
        errors.add(new FieldError(path, message));
    }

    /** Present = key exists (JSON {@code null} counts — zod optional only skips undefined). */
    static boolean present(JsonNode node) {
        return node != null;
    }


    static String typeName(JsonNode node) {
        if (node == null) return "undefined";
        if (node.isNull()) return "null";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        return "object";
    }


    static String expectedMsg(String expected, JsonNode received) {
        return "Expected " + expected + ", but received " + typeName(received);
    }


    static String enumMsg(List<String> values) {
        StringBuilder sb = new StringBuilder("Invalid value. Expected one of: ");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(values.get(i)).append('"');
        }
        return sb.toString();
    }

    static boolean isInteger(JsonNode number) {
        double d = number.asDouble();
        return d == Math.rint(d) && !Double.isInfinite(d);
    }

    static boolean isStringArray(JsonNode node) {
        if (!node.isArray()) return false;
        for (JsonNode item : node) {
            if (!item.isTextual()) return false;
        }
        return true;
    }

    private static void checkString(JsonNode parent, String key, List<FieldError> errors) {
        checkString(parent, null, key, errors);
    }

    static void checkString(JsonNode parent, String pathPrefix, String key, List<FieldError> errors) {
        JsonNode node = parent.get(key);
        if (present(node) && !node.isTextual()) {
            err(errors, joinPath(pathPrefix, key), expectedMsg("string", node));
        }
    }

    static void checkRequiredString(JsonNode parent, String pathPrefix, String key, List<FieldError> errors) {
        JsonNode node = parent.get(key);
        if (node == null || !node.isTextual()) {
            err(errors, joinPath(pathPrefix, key), expectedMsg("string", node));
        }
    }

    private static void checkBoolean(JsonNode parent, String key, List<FieldError> errors) {
        checkBoolean(parent, null, key, errors);
    }

    static void checkBoolean(JsonNode parent, String pathPrefix, String key, List<FieldError> errors) {
        JsonNode node = parent.get(key);
        if (present(node) && !node.isBoolean()) {
            err(errors, joinPath(pathPrefix, key), expectedMsg("boolean", node));
        }
    }

    static void checkNumber(JsonNode parent, String pathPrefix, String key, List<FieldError> errors) {
        JsonNode node = parent.get(key);
        if (present(node) && !node.isNumber()) {
            err(errors, joinPath(pathPrefix, key), expectedMsg("number", node));
        }
    }

    /** Optional {@code z.array(z.string())} — element errors carry the index in the path. */
    static void checkStringArray(JsonNode parent, String key, String path, List<FieldError> errors) {
        JsonNode node = parent.get(key);
        if (!present(node)) return;
        if (!node.isArray()) {
            err(errors, path, expectedMsg("array", node));
            return;
        }
        for (int i = 0; i < node.size(); i++) {
            if (!node.get(i).isTextual()) {
                err(errors, path + "." + i, expectedMsg("string", node.get(i)));
            }
        }
    }

    /** Optional {@code z.record(z.string(), z.string())}. */
    private static void checkStringRecord(JsonNode node, String path, List<FieldError> errors) {
        if (!present(node)) return;
        if (!node.isObject()) {
            err(errors, path, expectedMsg("record", node));
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                err(errors, path + "." + field.getKey(), expectedMsg("string", field.getValue()));
            }
        }
    }

    private static void checkEnum(JsonNode parent, String key, List<String> values, List<FieldError> errors) {
        checkEnum(parent, null, key, values, errors);
    }

    static void checkEnum(JsonNode parent, String pathPrefix, String key,
                          List<String> values, List<FieldError> errors) {
        JsonNode node = parent.get(key);
        if (present(node) && (!node.isTextual() || !values.contains(node.asText()))) {
            err(errors, joinPath(pathPrefix, key), enumMsg(values));
        }
    }

    /** Optional {@code z.literal(value)} — mismatch is an invalid_value like a 1-element enum. */
    private static void checkLiteral(JsonNode parent, String key, String literal, List<FieldError> errors) {
        JsonNode node = parent.get(key);
        if (present(node) && (!node.isTextual() || !literal.equals(node.asText()))) {
            err(errors, key, enumMsg(List.of(literal)));
        }
    }

    /** Required {@code z.literal(value)} — missing behaves like a mismatch (zod invalid_value). */
    static void checkRequiredLiteral(JsonNode parent, String pathPrefix, String key,
                                     String literal, List<FieldError> errors) {
        JsonNode node = parent.get(key);
        if (node == null || !node.isTextual() || !literal.equals(node.asText())) {
            err(errors, joinPath(pathPrefix, key), enumMsg(List.of(literal)));
        }
    }

    /**
     * Approximation of zod v4 {@code z.string().url()} (WHATWG {@code new URL}):
     * requires a parseable absolute URI with a scheme. Known divergence: exotic
     * WHATWG-valid strings Java's URI rejects (and vice versa) — acceptable for
     * diagnostics.
     */
    static boolean isValidUrl(String value) {
        try {
            URI uri = new URI(value);
            return uri.isAbsolute();
        } catch (URISyntaxException _) {
            return false;
        }
    }

    static String joinPath(String prefix, String key) {
        return StringUtils.isEmpty(prefix) ? key : prefix + "." + key;
    }
}
