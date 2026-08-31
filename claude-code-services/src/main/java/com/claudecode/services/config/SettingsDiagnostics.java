package com.claudecode.services.config;

import com.claudecode.mcp.McpConfigLoader;
import com.claudecode.mcp.McpConfigWarning;
import com.claudecode.mcp.McpServerScope;
import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Settings validation diagnostics and safety-oriented error aggregation.
 */
public final class SettingsDiagnostics {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsDiagnostics.class);

    private static final Set<String> MANAGED_LOGGING_TOP_LEVEL_KEYS = Set.of(
        "$schema", "apiKeyHelper", "awsCredentialExport", "awsAuthRefresh", "gcpAuthRefresh",
        "fileSuggestion", "respectGitignore", "cleanupPeriodDays", "env", "attribution",
        "includeCoAuthoredBy", "includeGitInstructions", "permissions", "model",
        "availableModels", "modelOverrides", "enableAllProjectMcpServers",
        "enabledMcpjsonServers", "disabledMcpjsonServers", "allowedMcpServers",
        "deniedMcpServers", "hooks", "worktree", "disableAllHooks", "defaultShell",
        "allowManagedHooksOnly", "allowedHttpHookUrls", "httpHookAllowedEnvVars",
        "allowManagedPermissionRulesOnly", "allowManagedMcpServersOnly",
        "strictPluginOnlyCustomization", "statusLine", "enabledPlugins",
        "extraKnownMarketplaces", "strictKnownMarketplaces", "blockedMarketplaces",
        "forceLoginMethod", "forceLoginOrgUUID", "otelHeadersHelper", "outputStyle",
        "language", "skipWebFetchPreflight", "sandbox", "feedbackSurveyRate",
        "spinnerTipsEnabled", "spinnerVerbs", "spinnerTipsOverride",
        "syntaxHighlightingDisabled", "terminalTitleFromRename", "alwaysThinkingEnabled",
        "effortLevel", "advisorModel", "fastMode", "fastModePerSessionOptIn",
        "promptSuggestionEnabled", "showClearContextOnPlanAccept", "agent",
        "companyAnnouncements", "pluginConfigs", "remote", "autoUpdatesChannel",
        "minimumVersion", "plansDirectory", "channelsEnabled", "allowedChannelPlugins",
        "prefersReducedMotion", "autoMemoryEnabled", "autoMemoryDirectory", "autoDreamEnabled",
        "showThinkingSummaries", "skipDangerousModePermissionPrompt", "disableAutoMode",
        "sshConfigs", "claudeMdExcludes", "pluginTrustMessage", "switchModelsOnFlag");

    private static volatile SettingsWithErrors sessionSettings;
    private static final ScopedValue<Boolean> LOADING = ScopedValue.newInstance();

    /** Presence information for file-backed managed settings. */
    public record ManagedFileSettingsPresence(boolean hasBase, boolean hasDropIns) {}

    private SettingsDiagnostics() {}

    /** Returns the process-cached merged accepted snapshot and its de-duplicated diagnostics. */
    public static SettingsWithErrors getSettingsWithErrors() {
        SettingsWithErrors cached = sessionSettings;
        if (cached != null) return cached;
        synchronized (SettingsDiagnostics.class) {
            cached = sessionSettings;
            if (cached == null) {
                cached = loadUncached(
                    currentSettingsCwd());
                sessionSettings = cached;
            }
            return cached;
        }
    }

    /** Fresh diagnostic read for a supplied cwd. */
    public static SettingsWithErrors loadSettingsWithErrors(String cwd) {
        invalidateForReload();
        return loadUncached(cwd);
    }

    /** Whether the current thread is recursively resolving diagnostics. */
    public static boolean isLoadingSettings() {
        return LOADING.isBound();
    }

    /** Adds enabled user/project/local MCP diagnostics to the settings diagnostics. */
    public static SettingsWithErrors getSettingsWithAllErrors() {
        SettingsWithErrors settings = getSettingsWithErrors();
        List<SettingsValidationError> errors = new ArrayList<>(settings.errors());
        Path cwd = Path.of(currentSettingsCwd()).toAbsolutePath().normalize();
        try {
            for (McpConfigWarning warning : McpConfigLoader.loadConfig(cwd).diagnostics()) {
                if (warning.scope() != McpServerScope.USER
                        && warning.scope() != McpServerScope.PROJECT
                        && warning.scope() != McpServerScope.LOCAL) {
                    continue;
                }

                // parses each scope independently and does not expose that merge
                // bookkeeping as a validation error, so it must not leak through
// getSettingsWithAllErrors.
                if (warning.message() != null
                        &&Strings.CS.contains( warning.message(), "overrides an earlier entry")) {
                    continue;
                }

                // parses a scope.  Do the same here: --setting-sources is an
                // isolation boundary for diagnostics as well as execution.
                if (!isMcpScopeEnabled(warning.scope())) continue;
                addUniqueError(errors, new SettingsValidationError(
                    McpConfigLoader.describeConfigPath(warning.scope(), cwd),
                    warning.path(), warning.message()));
            }
        } catch (RuntimeException e) {
            LOG.debug("Failed to aggregate MCP settings diagnostics: {}", e.getMessage());
        }
        return new SettingsWithErrors(settings.settings(), errors);
    }

    /** Returns true when raw enabled non-policy settings contain {@code key}. */
    public static boolean rawSettingsContainsKey(String key) {
        if (StringUtils.isBlank(key)) return false;
        String cwd = currentSettingsCwd();
        for (RuleSource source : SettingsSources.enabledOrder()) {
            if (source == RuleSource.POLICY_SETTINGS) continue;
            Path path = source == RuleSource.FLAG_SETTINGS
                ? SettingsSources.flagSettingsPath() : SettingsSources.editablePath(source, cwd);
            if (rawFileContainsKey(path, key)) return true;
        }
        return false;
    }

    /** Keeps cleanup from using a destructive default while an explicit raw retention value is bad. */
    public static boolean shouldSkipFileHistoryCleanup() {
        return hasSettingsValidationErrors() && rawSettingsContainsKey("cleanupPeriodDays");
    }

    /** Reports managed base validity plus visible drop-in presence. */
    public static ManagedFileSettingsPresence getManagedFileSettingsPresence() {
        Path base = SettingsPaths.policySettingsPath().toAbsolutePath().normalize();
        ObjectNode baseSettings = SettingsTreeReader.readAccepted(base, false);
        boolean hasBase = baseSettings != null && !baseSettings.isEmpty();
        boolean hasDropIns = false;
        Path directory = SettingsPaths.policySettingsDropInDirectory();
        try (var entries = Files.list(directory)) {
            hasDropIns = entries.anyMatch(path -> {
                String name = path.getFileName().toString();
                return !Strings.CS.startsWith(name, ".") &&Strings.CS.endsWith( name, ".json")
                    && (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(path));
            });
        } catch (IOException | RuntimeException _) {
            // An absent policy drop-in directory is ordinary.
        }
        return new ManagedFileSettingsPresence(hasBase, hasDropIns);
    }

    /** Returns {@code plist}, {@code hklm}, {@code file}, {@code hkcu}, or null. */
    public static String getPolicySettingsOrigin() {
        ObjectNode admin = MdmSettingsStore.readAdminSettings();
        if (admin != null && !admin.isEmpty()) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

            // reader normally returns an empty source, but preserving this label
            // keeps the origin contract correct for injected/tested admin results.
            return Strings.CS.contains( os, "mac") ? "plist" : "hklm";
        }
        ObjectNode file = SettingsSnapshots.managedFileSettings();
        if (file != null && !file.isEmpty()) return "file";
        ObjectNode user = MdmSettingsStore.readUserSettings();
        return user != null && !user.isEmpty() ? "hkcu" : null;
    }

    /** Returns the managed schema keys permitted in privacy-safe diagnostic logging. */
    public static List<String> getManagedSettingsKeysForLogging(JsonNode settings) {
        ObjectNode accepted = SettingsTreeReader.validateManaged(settings, "managed settings").settings();
        if (accepted == null) return List.of();
        Set<String> keys = new HashSet<>();
        Set<String> expanded = Set.of("permissions", "sandbox", "hooks");
        Set<String> permissionKeys = Set.of("allow", "deny", "ask", "defaultMode",
            "disableBypassPermissionsMode", "additionalDirectories");
        Set<String> sandboxKeys = Set.of("enabled", "failIfUnavailable", "allowUnsandboxedCommands",
            "network", "filesystem", "ignoreViolations", "excludedCommands",
            "autoAllowBashIfSandboxed", "enableWeakerNestedSandbox", "enableWeakerNetworkIsolation", "ripgrep");
        Set<String> hookKeys = Set.of("PreToolUse", "PostToolUse", "Notification", "UserPromptSubmit",
            "SessionStart", "SessionEnd", "Stop", "SubagentStop", "PreCompact", "PostCompact",
            "TeammateIdle", "TaskCreated", "TaskCompleted");
        accepted.fieldNames().forEachRemaining(key -> {
            if (!MANAGED_LOGGING_TOP_LEVEL_KEYS.contains(key)) return;
            if (!expanded.contains(key)) {
                keys.add(key);
                return;
            }
            JsonNode nested = accepted.get(key);
            if (nested == null || !nested.isObject()) return;
            Set<String> allowed =Strings.CS.equals( "permissions", key) ? permissionKeys
                :Strings.CS.equals( "sandbox", key) ? sandboxKeys : hookKeys;
            nested.fieldNames().forEachRemaining(child -> {
                if (allowed.contains(child)) keys.add(key + "." + child);
            });
        });
        return keys.stream().sorted().toList();
    }

    /** Cache invalidation seam shared by writers, source changes, and reload observers. */
    static void invalidateForReload() {
        sessionSettings = null;
    }

    private static SettingsWithErrors loadUncached(String cwd) {
        if (isLoadingSettings()) {
            return new SettingsWithErrors(JsonUtils.getMapper().createObjectNode(), List.of());
        }
        return withLoadingSettings(() -> loadUncachedImpl(cwd));
    }

    static <T> T withLoadingSettings(Supplier<T> action) {
        return ScopedValue.where(LOADING, Boolean.TRUE).call(action::get);
    }

    private static SettingsWithErrors loadUncachedImpl(String cwd) {
        // Keep one effective merge protocol: diagnostics contribute errors only, while the
        // accepted settings view comes from the same snapshot engine used by SDK/status readers.
        ObjectNode effective = ((ObjectNode) SettingsSnapshots.withSources(cwd)
            .path("effective")).deepCopy();
        List<SettingsValidationError> errors = new ArrayList<>();
        Set<String> seenErrors = new HashSet<>();
        Set<Path> seenFiles = new HashSet<>();
        for (RuleSource source : SettingsSources.enabledOrder()) {
            if (source == RuleSource.POLICY_SETTINGS) {
                MdmSettingsStore.ReadResult admin = MdmSettingsStore.readAdminResult();

                // invalid/empty admin candidate is discarded before policy fallback, while
                // warnings attached to an accepted admin payload remain visible here.
                addUniqueErrors(errors, seenErrors, admin.errors());
                if (admin.settings() != null && !admin.settings().isEmpty()) {
                    continue;
                }
                boolean filePolicyHasValues = false;
                for (Path path : SettingsSnapshots.policyFiles()) {
                    SettingsTreeReader.ParsedSettings parsed =
                        SettingsTreeReader.parseForDiagnostics(path, false);
                    addUniqueErrors(errors, seenErrors, parsed.errors());
                    if (parsed.settings() != null && !parsed.settings().isEmpty()) {
                        filePolicyHasValues = true;
                    }
                }
                if (!filePolicyHasValues) {
                    MdmSettingsStore.ReadResult user = MdmSettingsStore.readUserResult();
                    addUniqueErrors(errors, seenErrors, user.errors());
                }
                continue;
            }
            Path path = source == RuleSource.FLAG_SETTINGS
                ? SettingsSources.flagSettingsPath() : SettingsSources.editablePath(source, cwd);
            if (path != null && seenFiles.add(path.toAbsolutePath().normalize())) {
                SettingsTreeReader.ParsedSettings parsed = SettingsTreeReader.parseForDiagnostics(
                    path, source != RuleSource.FLAG_SETTINGS);
                addUniqueErrors(errors, seenErrors, parsed.errors());
            }
        }
        return new SettingsWithErrors(effective, errors);
    }

    private static boolean hasSettingsValidationErrors() {
        return !getSettingsWithErrors().errors().isEmpty() || hasMcpConfigurationErrors();
    }

    private static boolean hasMcpConfigurationErrors() {
        Path cwd = Path.of(currentSettingsCwd()).toAbsolutePath().normalize();
        try {
            for (McpConfigWarning diagnostic : McpConfigLoader.loadConfig(cwd).diagnostics()) {
                if (diagnostic.message() != null
                        &&Strings.CS.contains( diagnostic.message(), "overrides an earlier entry")) {
                    continue;
                }
                if (isMcpScopeEnabled(diagnostic.scope())) return true;
            }
        } catch (RuntimeException e) {
            LOG.warn("Failed to inspect MCP configuration for cleanup safety: {}", e.getMessage());
            return true;
        }
        return false;
    }

    private static boolean isMcpScopeEnabled(McpServerScope scope) {
        return switch (scope) {
            case USER -> SettingsSources.isEnabled(RuleSource.USER_SETTINGS);
            case PROJECT -> SettingsSources.isEnabled(RuleSource.PROJECT_SETTINGS);
            case LOCAL -> SettingsSources.isEnabled(RuleSource.LOCAL_SETTINGS);
            case ENTERPRISE, DYNAMIC -> false;
        };
    }

    private static boolean rawFileContainsKey(Path path, String key) {
        if (path == null || !Files.isReadable(path)) return false;
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(JsonUtils.stripBom(content))) return false;
            JsonNode root = SettingsTreeReader.readJson(path);
            return root != null && root.isObject() && root.has(key);
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    private static String currentSettingsCwd() {
        return SettingsPaths.sessionProjectRoot(System.getProperty("user.dir")).toString();
    }

    private static void addUniqueErrors(List<SettingsValidationError> target, Set<String> seen,
                                        List<SettingsValidationError> additions) {
        for (SettingsValidationError error : additions) {
            String key = error.file() + ":" + error.path() + ":" + error.message();
            if (seen.add(key)) target.add(error);
        }
    }

    private static void addUniqueError(List<SettingsValidationError> target,
                                       SettingsValidationError candidate) {
        String key = candidate.file() + ":" + candidate.path() + ":" + candidate.message();
        for (SettingsValidationError existing : target) {
            if ((existing.file() + ":" + existing.path() + ":" + existing.message()).equals(key)) {
                return;
            }
        }
        target.add(candidate);
    }
}
