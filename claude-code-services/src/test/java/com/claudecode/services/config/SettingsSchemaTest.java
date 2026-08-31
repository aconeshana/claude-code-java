package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;
import com.claudecode.services.config.SettingsSchema.FieldError;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SettingsSchemaTest {

    private static List<FieldError> validate(String json) {
        JsonNode tree = JsonUtils.parseTree(json);
        return SettingsSchema.validate(tree);
    }

    private static boolean has(List<FieldError> errors, String path, String message) {
        return errors.stream().anyMatch(e -> e.path().equals(path) && e.message().equals(message));
    }

    private static void assertHas(List<FieldError> errors, String path, String message) {
        assertTrue(has(errors, path, message),
            "expected [" + path + "] \"" + message + "\" in " + errors);
    }

    // ── happy path + passthrough ─────────────────────────────────────────────

    @Test
    void validSettingsFileProducesZeroErrors() {
        assertEquals(List.of(), validate("""
            {
              "$schema": "https://json.schemastore.org/claude-code-settings.json",
              "model": "opus",
              "cleanupPeriodDays": 30,
              "env": {"FOO": "bar", "NUM": 42},
              "includeCoAuthoredBy": false,
              "enableWorkflows": "legacy passthrough",
              "disableWorkflows": {"future": true},
              "permissions": {
                "allow": ["Bash(git *)", "Read(src/**)", "mcp__github", "WebFetch(domain:example.com)"],
                "deny": ["Bash(rm:*)"],
                "ask": ["Write"],
                "defaultMode": "acceptEdits",
                "additionalDirectories": ["/tmp"]
              },
              "statusLine": {"type": "command", "command": "~/statusline.sh", "padding": 0},
              "hooks": {
                "PreToolUse": [
                  {"matcher": "Bash", "hooks": [{"type": "command", "command": "echo hi", "timeout": 5}]}
                ],
                "SessionStart": [
                  {"hooks": [{"type": "prompt", "prompt": "check $ARGUMENTS"}]}
                ]
              },
              "enabledPlugins": {"fmt@mkt": true, "lint@mkt": ["a", "b"]},
              "feedbackSurveyRate": 0.05,
              "effortLevel": "high",
              "defaultShell": "bash",
              "spinnerVerbs": {"mode": "append", "verbs": ["Vibing"]},
              "sandbox": {"enabled": true, "network": {"allowedDomains": ["example.com"]}},
              "allowedMcpServers": [{"serverName": "github"}],
              "sshConfigs": [{"id": "dev", "name": "Dev", "sshHost": "me@host"}]
            }
            """));
    }

    @Test
    void unknownTopLevelKeysPassThroughSilently() {

        assertEquals(List.of(), validate("{\"totallyUnknownKey\": 42, \"anotherOne\": {\"x\": []}}"));
    }

    @Test
    void removedWorkflowKeysRemainUnknownPassthrough() {

// these historical keys must follow the outer.passthrough behavior.
        assertEquals(List.of(), validate(
            "{\"enableWorkflows\": \"legacy\", \"disableWorkflows\": {\"future\": true}}"));
    }

    @Test
    void catchGuardedKeysNeverError() {

        // invalid values are dropped without a validation error.
        assertEquals(List.of(), validate(
            "{\"effortLevel\": \"ultra\", \"strictPluginOnlyCustomization\": \"skills\"}"));
    }

    @Test
    void transcriptClassifierKeysRemainPassthroughInTheExternalBuild() {



        assertEquals(List.of(), validate("{\"skipAutoPermissionPrompt\": \"future-value\"}"));
    }

    // ── root shape ───────────────────────────────────────────────────────────

    @Test
    void rootNullIsInvalidOrMalformedJson() {
        List<FieldError> errors = validate("null");
        assertHas(errors, "", "Invalid or malformed JSON");
    }

    @Test
    void rootArrayIsTypeError() {
        assertHas(validate("[]"), "", "Expected object, but received array");
    }

    // ── type errors ──────────────────────────────────────────────────────────

    @Test
    void typeErrors_topLevelPrimitives() {
        List<FieldError> errors = validate("""
            {"model": 5, "includeCoAuthoredBy": "yes", "availableModels": "opus",
             "env": "PATH", "cleanupPeriodDays": "30"}
            """);
        assertHas(errors, "model", "Expected string, but received number");
        assertHas(errors, "includeCoAuthoredBy", "Expected boolean, but received string");
        assertHas(errors, "availableModels", "Expected array, but received string");
        assertHas(errors, "env", "Expected record, but received string");
        assertHas(errors, "cleanupPeriodDays", "Expected number, but received string");
    }

    @Test
    void typeErrors_nullIsNotAcceptedForOptional() {
        // zod .optional() accepts undefined only — JSON null is invalid_type.
        assertHas(validate("{\"model\": null}"), "model", "Expected string, but received null");
    }

    @Test
    void typeErrors_arrayElementCarriesIndexInPath() {
        assertHas(validate("{\"availableModels\": [\"opus\", 42]}"),
            "availableModels.1", "Expected string, but received number");
    }

    // ── enum / literal errors ────────────────────────────────────────────────

    @Test
    void enumErrors_useInvalidValueMessage() {
        List<FieldError> errors = validate(
            "{\"forceLoginMethod\": \"sso\", \"autoUpdatesChannel\": \"nightly\", \"defaultShell\": \"zsh\"}");
        assertHas(errors, "forceLoginMethod",
            "Invalid value. Expected one of: \"claudeai\", \"console\"");
        assertHas(errors, "autoUpdatesChannel",
            "Invalid value. Expected one of: \"latest\", \"stable\"");
        assertHas(errors, "defaultShell",
            "Invalid value. Expected one of: \"bash\", \"powershell\"");
    }

    @Test
    void schemaLiteralMismatchIsEnumStyleError() {
        assertHas(validate("{\"$schema\": \"https://example.com/other.json\"}"),
            "$schema",
            "Invalid value. Expected one of: \"https://json.schemastore.org/claude-code-settings.json\"");
    }

    // ── number bounds ────────────────────────────────────────────────────────

    @Test
    void numberBounds_tooSmallAndInt() {
        List<FieldError> errors = validate("{\"cleanupPeriodDays\": -1.5}");
        assertHas(errors, "cleanupPeriodDays", "Number must be greater than or equal to 0");
        assertHas(errors, "cleanupPeriodDays", "Expected int, but received number");
        assertEquals(List.of(), validate("{\"cleanupPeriodDays\": 30.0}"));
    }

    @Test
    void subagentMaxDepth_requiresIntegralOneThroughFive() {
        assertEquals(List.of(), validate("{\"subagentMaxDepth\": 1}"));
        assertEquals(List.of(), validate("{\"subagentMaxDepth\": 5}"));
        assertHas(validate("{\"subagentMaxDepth\": 0}"),
            "subagentMaxDepth", "Number must be between 1 and 5");
        assertHas(validate("{\"subagentMaxDepth\": 6}"),
            "subagentMaxDepth", "Number must be between 1 and 5");
        assertHas(validate("{\"subagentMaxDepth\": 2.0}"),
            "subagentMaxDepth", "Expected int, but received number");
        assertHas(validate("{\"subagentMaxDepth\": \"2\"}"),
            "subagentMaxDepth", "Expected number, but received string");
    }

    @Test
    void feedbackSurveyRate_overOneKeepsZodTooBigMessage() {
        assertHas(validate("{\"feedbackSurveyRate\": 2}"),
            "feedbackSurveyRate", "Too big: expected number to be <=1");
    }

    // ── nested objects ───────────────────────────────────────────────────────

    @Test
    void statusLine_structureErrors() {
        List<FieldError> errors = validate(
            "{\"statusLine\": {\"type\": \"script\", \"padding\": \"2\"}}");
        assertHas(errors, "statusLine.type", "Invalid value. Expected one of: \"command\"");
        assertHas(errors, "statusLine.command", "Expected string, but received undefined");
        assertHas(errors, "statusLine.padding", "Expected number, but received string");
    }

    @Test
    void spinnerVerbs_requiredFields() {
        List<FieldError> errors = validate("{\"spinnerVerbs\": {\"mode\": \"add\"}}");
        assertHas(errors, "spinnerVerbs.mode", "Invalid value. Expected one of: \"append\", \"replace\"");
        assertHas(errors, "spinnerVerbs.verbs", "Expected array, but received undefined");
    }

    @Test
    void sandbox_nestedTypes() {
        List<FieldError> errors = validate("""
            {"sandbox": {"enabled": "yes", "network": {"httpProxyPort": "8080"},
             "filesystem": {"allowWrite": "not-an-array"}, "unknownSandboxKey": 1}}
            """);
        assertHas(errors, "sandbox.enabled", "Expected boolean, but received string");
        assertHas(errors, "sandbox.network.httpProxyPort", "Expected number, but received string");
        assertHas(errors, "sandbox.filesystem.allowWrite", "Expected array, but received string");
        // sandbox is .passthrough() — unknown keys inside it are fine.
        assertFalse(errors.stream().anyMatch(e -> Strings.CS.contains(e.path(), "unknownSandboxKey")));
    }

    @Test
    void sshConfigs_requiredFieldsAndIntPort() {
        List<FieldError> errors = validate(
            "{\"sshConfigs\": [{\"name\": \"Dev\", \"sshHost\": \"h\", \"sshPort\": 22.5}]}");
        assertHas(errors, "sshConfigs.0.id", "Expected string, but received undefined");
        assertHas(errors, "sshConfigs.0.sshPort", "Expected int, but received number");
    }

    // ── permissions ──────────────────────────────────────────────────────────

    @Nested
    class Permissions {

        @Test
        void defaultModeEnum() {
            assertHas(validate("{\"permissions\": {\"defaultMode\": \"yolo\"}}"),
                "permissions.defaultMode",
                "Invalid value. Expected one of: \"acceptEdits\", \"bypassPermissions\", \"default\", \"dontAsk\", \"plan\"");
        }

        @Test
        void allowMustBeArray() {
            assertHas(validate("{\"permissions\": {\"allow\": \"Bash\"}}"),
                "permissions.allow", "Expected array, but received string");
        }

        @Test
        void nonStringRuleIsRemovedWithWarning() {

            assertHas(validate("{\"permissions\": {\"allow\": [42]}}"),
                "permissions.allow", "Non-string value in allow array was removed");
        }

        @Test
        void invalidRuleContentIsSkippedWithWarning() {
            List<FieldError> errors = validate("""
                {"permissions": {"deny": ["bash(ls)", "", "Bash(rm -rf", "WebFetch(https://x.com)"]}}
                """);
            assertHas(errors, "permissions.deny",
                "Invalid permission rule \"bash(ls)\" was skipped: Tool names must start with uppercase. Use \"Bash\"");
            assertHas(errors, "permissions.deny",
                "Invalid permission rule \"\" was skipped: Permission rule cannot be empty");
            assertHas(errors, "permissions.deny",
                "Invalid permission rule \"Bash(rm -rf\" was skipped: Mismatched parentheses. "
                    + "Ensure all opening parentheses have matching closing parentheses");
            assertHas(errors, "permissions.deny",
                "Invalid permission rule \"WebFetch(https://x.com)\" was skipped: "
                    + "WebFetch permissions use domain format, not URLs. Use \"domain:hostname\" format");
        }

        @Test
        void mcpRuleWithParensIsInvalid() {
            assertHas(validate("{\"permissions\": {\"allow\": [\"mcp__srv__tool(x)\"]}}"),
                "permissions.allow",
                "Invalid permission rule \"mcp__srv__tool(x)\" was skipped: "
                    + "MCP rules do not support patterns in parentheses. "
                    + "Use \"mcp__srv__tool\" without parentheses, or use \"mcp__srv__*\" for all tools");
        }

        @Test
        void unknownPermissionsKeysPassThrough() {

            assertEquals(List.of(), validate("{\"permissions\": {\"futureKey\": 1}}"));
        }
    }

    // ── hooks ────────────────────────────────────────────────────────────────

    @Nested
    class Hooks {

        @Test
        void acceptsAllHookEventsAddedIn197() {
            assertEquals(List.of(), validate("""
                {"hooks": {
                  "PostToolBatch": [{"hooks": [{"type": "command", "command": "batch"}]}],
                  "UserPromptExpansion": [{"hooks": [{"type": "command", "command": "expand"}]}],
                  "MessageDisplay": [{"hooks": [{"type": "command", "command": "display"}]}]
                }}
                """));
        }

        @Test
        void unknownEventKeyIsInvalidKeyInRecord() {
            assertHas(validate("{\"hooks\": {\"BeforeToolUse\": []}}"),
                "hooks.BeforeToolUse", "Invalid key in record");
        }

        @Test
        void badDiscriminatorIsInvalidInputAtTypePath() {
            assertHas(validate("""
                    {"hooks": {"PreToolUse": [{"hooks": [{"type": "shell", "command": "x"}]}]}}
                    """),
                "hooks.PreToolUse.0.hooks.0.type", "Invalid input");
        }

        @Test
        void missingRequiredFieldInMatchedArm() {
            List<FieldError> errors = validate("""
                {"hooks": {"PreToolUse": [{"hooks": [{"type": "command"}]},
                                          {"matcher": "Write"}]}}
                """);
            assertHas(errors, "hooks.PreToolUse.0.hooks.0.command",
                "Expected string, but received undefined");
            assertHas(errors, "hooks.PreToolUse.1.hooks",
                "Expected array, but received undefined");
        }

        @Test
        void timeoutMustBePositive() {
            assertHas(validate("""
                    {"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "x", "timeout": 0}]}]}}
                    """),
                "hooks.Stop.0.hooks.0.timeout", "Number must be greater than or equal to 0");
        }

        @Test
        void httpHookUrlFormat() {
            assertHas(validate("""
                    {"hooks": {"Stop": [{"hooks": [{"type": "http", "url": "not a url"}]}]}}
                    """),
                "hooks.Stop.0.hooks.0.url", "Invalid URL");
        }
    }

    // ── plugin records ───────────────────────────────────────────────────────

    @Test
    void enabledPlugins_badUnionValueIsInvalidInput() {
        assertHas(validate("{\"enabledPlugins\": {\"a@mkt\": \"yes\"}}"),
            "enabledPlugins.a@mkt", "Invalid input");
    }

    @Test
    void allowedMcpServers_exactlyOneOfRefinement() {
        List<FieldError> errors = validate("""
            {"allowedMcpServers": [{}, {"serverName": "a", "serverUrl": "b"}]}
            """);
        String refineMsg =
            "Entry must have exactly one of \"serverName\", \"serverCommand\", or \"serverUrl\"";
        assertHas(errors, "allowedMcpServers.0", refineMsg);
        assertHas(errors, "allowedMcpServers.1", refineMsg);
    }

    @Test
    void allowedMcpServers_regexAndMinLength() {
        List<FieldError> errors = validate("""
            {"deniedMcpServers": [{"serverName": "bad name!"}, {"serverCommand": []}]}
            """);
        assertHas(errors, "deniedMcpServers.0.serverName",
            "Server name can only contain letters, numbers, hyphens, and underscores");

        assertHas(errors, "deniedMcpServers.1.serverCommand",
            "Number must be greater than or equal to 1");
    }

    @Test
    void marketplaceSource_discriminatorAndArmFields() {
        List<FieldError> errors = validate("""
            {"strictKnownMarketplaces": [{"source": "gitlab"},
                                         {"source": "github"},
                                         {"source": "npm", "package": "../evil"}]}
            """);
        assertHas(errors, "strictKnownMarketplaces.0.source", "Invalid input");
        assertHas(errors, "strictKnownMarketplaces.1.repo", "Expected string, but received undefined");
        assertHas(errors, "strictKnownMarketplaces.2.package",
            "Package name cannot contain path traversal patterns");
    }

    @Test
    void settingsMarketplaceValidatesRemotePluginEntriesAndOwner() {
        List<FieldError> errors = validate("""
            {"extraKnownMarketplaces": {"team": {"source": {
              "source": "settings", "name": "team",
              "plugins": [
                {"name": "bad plugin", "source": "./local"},
                {"name": "remote", "source": {
                  "source": "git-subdir", "url": "https://example.com/repo",
                  "path": "", "sha": "not-a-sha"}}
              ],
              "owner": {"email": 42}
            }}}}
            """);
        assertHas(errors, "extraKnownMarketplaces.team.source.plugins.0.name",
            "Plugin name cannot contain spaces. Use kebab-case (e.g., \"my-plugin\")");
        assertHas(errors, "extraKnownMarketplaces.team.source.plugins.0",
            "Plugins in a settings-sourced marketplace must use remote sources (github, git-subdir, npm, url, pip). Relative-path sources like \"./foo\" have no marketplace repository to resolve against.");
        assertHas(errors, "extraKnownMarketplaces.team.source.plugins.1.source.path",
            "Number must be greater than or equal to 1");
        assertHas(errors, "extraKnownMarketplaces.team.source.plugins.1.source.sha", "Invalid input");
        assertHas(errors, "extraKnownMarketplaces.team.source.owner.name",
            "Expected string, but received undefined");
        assertHas(errors, "extraKnownMarketplaces.team.source.owner.email",
            "Expected string, but received number");
    }

    @Test
    void worktreeBaseRefAcceptsFreshOrHeadOnly() {
        assertTrue(validate("{\"worktree\": {\"baseRef\": \"fresh\"}}").isEmpty());
        assertTrue(validate("{\"worktree\": {\"baseRef\": \"head\"}}").isEmpty());
        assertHas(validate("{\"worktree\": {\"baseRef\": \"branch\"}}"),
            "worktree.baseRef", "Invalid value. Expected one of: \"fresh\", \"head\"");
    }

    @Test
    void extraKnownMarketplaces_settingsNameMustMatchKey() {
        assertHas(validate("""
                {"extraKnownMarketplaces": {"mykey": {"source":
                  {"source": "settings", "name": "other-name", "plugins": []}}}}
                """),
            "extraKnownMarketplaces.mykey.source.name",
            "Settings-sourced marketplace name must match its extraKnownMarketplaces key "
                + "(got key \"mykey\" but source.name \"other-name\")");
    }

    @Test
    void marketplaceNamesMatchOfficialImpersonationAndSettingsReservations() {
        List<FieldError> errors = validate("""
            {"strictKnownMarketplaces": [
              {"source": "settings", "name": "claude-code-marketplace", "plugins": []},
              {"source": "settings", "name": "claude-official", "plugins": []},
              {"source": "settings", "name": "аnthropic-marketplace", "plugins": []}
            ]}
            """);
        assertHas(errors, "strictKnownMarketplaces.0.name",
            "Reserved official marketplace names cannot be used with settings sources. "
                + "validateOfficialNameSource only accepts github/git sources from anthropics/* "
                + "for these names; a settings source would be rejected after "
                + "loadAndCacheMarketplace has already written to disk with cleanupNeeded=false.");
        assertHas(errors, "strictKnownMarketplaces.1.name",
            "Marketplace name impersonates an official Anthropic/Claude marketplace");
        assertHas(errors, "strictKnownMarketplaces.2.name",
            "Marketplace name impersonates an official Anthropic/Claude marketplace");
    }

    // ── defensiveness ────────────────────────────────────────────────────────

    @Test
    void validateNeverThrows() {
        // Even a null tree degrades to a report, not an exception.
        assertEquals(List.of(new FieldError("", "Invalid or malformed JSON")),
            SettingsSchema.validate(null));
    }
}
