package com.claudecode.services.config;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.core.io.PathUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Converts layered Claude settings into the sandbox runtime snapshot.
 */
public final class SandboxSettings {

    private SandboxSettings() {}

    /**
     * Builds a fresh sandbox configuration from the current settings sources.
     *
     * <p>The merged sandbox and permissions trees follow the configured enabled-source order.
     * Filesystem rules intentionally remain different: they inspect each fixed settings source,
     * including disabled editable sources, so every rule retains the root of its declaring file.
     */
    public static SandboxConfig loadSandboxConfig() {
        String cwd = SettingsPaths.sessionProjectRoot(System.getProperty("user.dir")).toString();
        Path projectRoot = SettingsPaths.sessionProjectRoot(cwd);
        Path userSettings = SettingsPaths.userSettingsPath();
        Path flagRoot = SettingsSources.flagSettingsRootPath(cwd);
        List<SettingsLayer> sourceTiers = List.of(
            new SettingsLayer(userSettings, null, RuleSource.USER_SETTINGS,
                userSettings.getParent()),
            new SettingsLayer(SettingsPaths.sessionProjectSettingsPath(cwd), null,
                RuleSource.PROJECT_SETTINGS, projectRoot),
            new SettingsLayer(SettingsPaths.sessionLocalSettingsPath(cwd), null,
                RuleSource.LOCAL_SETTINGS, projectRoot),
            new SettingsLayer(flagRoot, SettingsSources.flagSettingsSnapshot(),
                RuleSource.FLAG_SETTINGS, flagRoot),
            new SettingsLayer(SettingsPaths.policySettingsPath(), SettingsSnapshots.policySnapshot(),
                RuleSource.POLICY_SETTINGS, projectRoot));

        Map<RuleSource, SettingsLayer> bySource = new EnumMap<>(RuleSource.class);
        for (SettingsLayer tier : sourceTiers) {
            bySource.put(tier.source(), tier);
        }
        List<SettingsLayer> effectiveTiers = new ArrayList<>();
        for (RuleSource source : SettingsSources.enabledOrder()) {
            SettingsLayer tier = bySource.get(source);
            if (tier != null) effectiveTiers.add(tier);
        }
        return loadSandboxConfigLayers(effectiveTiers, sourceTiers);
    }

    /**
     * Whether flag or managed settings override locally writable sandbox mode fields.
     *
     * <p>The historic method name is retained for UI callers. Flag settings are intentionally
     * included because they are an externally controlled, non-local source just like policy.
     */
    public static boolean areSandboxSettingsLockedByPolicy() {
        JsonNode policySandbox = SettingsTreeReader.objectValue(
            SettingsSnapshots.policySnapshot(), "sandbox");
        JsonNode flagSandbox = SettingsTreeReader.objectValue(
            SettingsSources.flagSettingsSnapshot(), "sandbox");
        for (JsonNode sandbox : new JsonNode[] {policySandbox, flagSandbox}) {
            if (sandbox != null && (sandbox.has("enabled")
                    || sandbox.has("autoAllowBashIfSandboxed")
                    || sandbox.has("allowUnsandboxedCommands"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Package-private path seam for tests; tiers are user, project, local, then policy.
     * A five-tier input additionally contains flag settings before policy.
     */
    static SandboxConfig loadSandboxConfig(List<Path> tiers) {
        return loadSandboxConfig(tiers, tiers);
    }

    /**
     * Package-private path seam that separates enabled merged layers from the fixed source list.
     * Paths in {@code effectiveTiers} must refer to an entry in {@code sourceTiers}.
     */
    static SandboxConfig loadSandboxConfig(List<Path> effectiveTiers, List<Path> sourceTiers) {
        List<SettingsLayer> allLayers = pathLayers(sourceTiers);
        Map<Path, SettingsLayer> layerByPath = new HashMap<>();
        for (SettingsLayer layer : allLayers) {
            layerByPath.put(layer.settingsPath(), layer);
        }
        List<SettingsLayer> effectiveLayers = new ArrayList<>();
        for (Path path : effectiveTiers) {
            if (path == null) continue;
            SettingsLayer layer = layerByPath.get(path.toAbsolutePath().normalize());
            if (layer != null) effectiveLayers.add(layer);
        }
        return loadSandboxConfigLayers(effectiveLayers, allLayers);
    }

    private static List<SettingsLayer> pathLayers(List<Path> paths) {
        RuleSource[] sources = paths.size() >= 5
            ? new RuleSource[] {
                RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS, RuleSource.LOCAL_SETTINGS,
                RuleSource.FLAG_SETTINGS, RuleSource.POLICY_SETTINGS
            }
            : new RuleSource[] {
                RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS,
                RuleSource.LOCAL_SETTINGS, RuleSource.POLICY_SETTINGS
            };
        List<SettingsLayer> layers = new ArrayList<>();
        for (int index = 0; index < paths.size(); index++) {
            Path path = paths.get(index).toAbsolutePath().normalize();
            layers.add(new SettingsLayer(path, null,
                sources[Math.min(index, sources.length - 1)], sourceRootFor(path)));
        }
        return layers;
    }

    private static Path sourceRootFor(Path settingsPath) {
        Path parent = settingsPath.getParent();
        if (parent != null && parent.getFileName() != null
                &&Strings.CI.equals( ".claude", parent.getFileName().toString())
                && parent.getParent() != null) {
            return parent.getParent();
        }
        return parent;
    }

    private static SandboxConfig loadSandboxConfigLayers(
            List<SettingsLayer> effectiveTiers, List<SettingsLayer> sourceTiers) {
        JsonNode mergedSandbox = null;
        JsonNode mergedPermissions = null;
        for (SettingsLayer tier : effectiveTiers) {
            JsonNode sandbox = readLayerObject(tier, "sandbox", true);
            if (sandbox != null) {
                mergedSandbox = SettingsMerger.merge(mergedSandbox, sandbox);
            }
            JsonNode permissions = readLayerObject(tier, "permissions", true);
            if (permissions != null) {
                mergedPermissions = SettingsMerger.merge(mergedPermissions, permissions);
            }
        }
        SandboxConfig base = SandboxConfig.fromJson(mergedSandbox);

        // pathLayers always appends a POLICY_SETTINGS tier (it is the final
        // element of both sources arrays and the Math.min fallback target),
        // so the policy layer is an invariant of sourceTiers, not a maybe.
        SettingsLayer policyLayer = sourceTiers.stream()
            .filter(tier -> tier.source() == RuleSource.POLICY_SETTINGS)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "sourceTiers must contain a POLICY_SETTINGS layer"));
        JsonNode policySandbox = readLayerObject(policyLayer, "sandbox", false);
        JsonNode policyNetwork = SettingsTreeReader.objectValue(policySandbox, "network");
        JsonNode policyFilesystem = SettingsTreeReader.objectValue(policySandbox, "filesystem");
        boolean managedDomainsOnly = policyNetwork != null
            && policyNetwork.path("allowManagedDomainsOnly").asBoolean(false);
        boolean managedReadPathsOnly = policyFilesystem != null
            && policyFilesystem.path("allowManagedReadPathsOnly").asBoolean(false);

        List<String> allowWrite = new ArrayList<>();
        List<String> denyWrite = new ArrayList<>();
        List<String> denyRead = new ArrayList<>();
        List<String> allowRead = new ArrayList<>();
        addAdditionalDirectories(mergedPermissions, allowWrite);
        for (String directory : SettingsSources.sessionAdditionalDirectories()) {
            if (!allowWrite.contains(directory)) allowWrite.add(directory);
        }
        for (SettingsLayer tier : sourceTiers) {
            JsonNode permissions = readLayerObject(tier, "permissions", false);
            extractFilesystemPaths(permissions, tier.sourceDir(), tier.source(),
                allowWrite, denyWrite, denyRead);

            JsonNode sandbox = readLayerObject(tier, "sandbox", false);
            JsonNode filesystem = SettingsTreeReader.objectValue(sandbox, "filesystem");
            if (filesystem == null) continue;
            addResolvedFilesystemPaths(filesystem.get("allowWrite"), tier.sourceDir(), allowWrite);
            addResolvedFilesystemPaths(filesystem.get("denyWrite"), tier.sourceDir(), denyWrite);
            addResolvedFilesystemPaths(filesystem.get("denyRead"), tier.sourceDir(), denyRead);
            if (!managedReadPathsOnly || tier.source() == RuleSource.POLICY_SETTINGS) {
                addResolvedFilesystemPaths(filesystem.get("allowRead"), tier.sourceDir(), allowRead);
            }
        }

        List<String> allowedDomains = new ArrayList<>(base.network().allowedDomains());
        List<String> deniedDomains = new ArrayList<>(base.network().deniedDomains());
        extractWebFetchDomains(mergedPermissions, RuleSource.USER_SETTINGS, true, allowedDomains);
        extractWebFetchDomains(mergedPermissions, RuleSource.USER_SETTINGS, false, deniedDomains);
        if (managedDomainsOnly) {
            allowedDomains.clear();
            SandboxConfig policyConfig = SandboxConfig.fromJson(policySandbox);
            allowedDomains.addAll(policyConfig.network().allowedDomains());
            JsonNode policyPermissions = readLayerObject(policyLayer, "permissions", false);
            extractWebFetchDomains(policyPermissions, RuleSource.POLICY_SETTINGS, true, allowedDomains);
        }

        SandboxConfig.SandboxNetworkConfig network = base.network();
        SandboxConfig.SandboxNetworkConfig resolvedNetwork =
            new SandboxConfig.SandboxNetworkConfig(
                List.copyOf(allowedDomains), List.copyOf(deniedDomains),
                network.allowUnixSockets(), managedDomainsOnly,
                network.allowAllUnixSockets(), network.allowLocalBinding(),
                network.httpProxyPort(), network.socksProxyPort());
        SandboxConfig.SandboxFilesystemConfig filesystem =
            new SandboxConfig.SandboxFilesystemConfig(
                List.copyOf(allowWrite), List.copyOf(denyWrite), List.copyOf(denyRead),
                List.copyOf(allowRead), managedReadPathsOnly);
        List<String> permissionGlobWarnings = base.enabled()
            ? collectPermissionGlobWarnings(mergedPermissions) : List.of();
        return new SandboxConfig(base.enabled(), base.failIfUnavailable(),
            base.autoAllowBashIfSandboxed(), base.allowUnsandboxedCommands(),
            base.enableWeakerNestedSandbox(), base.enableWeakerNetworkIsolation(),
            base.excludedCommands(), base.enabledPlatforms(), base.ignoreViolations(),
            resolvedNetwork, filesystem, base.ripgrep(), permissionGlobWarnings);
    }


    private static List<String> collectPermissionGlobWarnings(JsonNode permissions) {
        if (permissions == null || !permissions.isObject()) return List.of();
        List<String> warnings = new ArrayList<>();
        collectPermissionGlobWarnings(permissions.get("allow"), PermissionBehavior.ALLOW,
            warnings);
        collectPermissionGlobWarnings(permissions.get("deny"), PermissionBehavior.DENY,
            warnings);
        return List.copyOf(warnings);
    }

    private static void collectPermissionGlobWarnings(JsonNode rules,
                                                      PermissionBehavior behavior,
                                                      List<String> warnings) {
        if (rules == null || !rules.isArray()) return;
        for (JsonNode rule : rules) {
            String raw = rule.asText();
            PermissionRule parsed = PermissionEngine.permissionRuleFromString(
                raw, behavior, RuleSource.USER_SETTINGS);
            if ((Strings.CI.equals("Edit", parsed.toolName())
                    ||Strings.CI.equals( "Read", parsed.toolName()))
                    && parsed.pattern().isPresent()
                    && hasGlobCharacters(parsed.pattern().get())) {
                warnings.add(raw);
            }
        }
    }

    private static boolean hasGlobCharacters(String pattern) {
        String stripped =Strings.CS.endsWith( pattern, "/**")
            ? pattern.substring(0, pattern.length() - 3) : pattern;
        return stripped.indexOf('*') >= 0 || stripped.indexOf('?') >= 0
            || stripped.indexOf('[') >= 0 || stripped.indexOf(']') >= 0;
    }

    private static JsonNode readLayerObject(
            SettingsLayer layer, String key, boolean honorSourceSelection) {
        if (layer.inline() != null) {
            return SettingsTreeReader.objectValue(layer.inline(), key);
        }
        return SettingsTreeReader.objectValue(
            SettingsTreeReader.readAccepted(layer.settingsPath(), honorSourceSelection), key);
    }

    private static void addAdditionalDirectories(JsonNode permissions, List<String> allowWrite) {
        JsonNode directories = permissions != null && permissions.isObject()
            ? permissions.get("additionalDirectories") : null;
        if (directories == null || !directories.isArray()) return;
        for (JsonNode directory : directories) {
            if (directory.isTextual() && !StringUtils.isBlank(directory.asText())) {
                allowWrite.add(directory.asText());
            }
        }
    }

    private static void extractFilesystemPaths(JsonNode permissions, Path sourceRoot,
                                               RuleSource source, List<String> allowWrite,
                                               List<String> denyWrite, List<String> denyRead) {
        if (permissions == null) return;
        JsonNode allow = permissions.get("allow");
        if (allow != null && allow.isArray()) {
            for (JsonNode rule : allow) {
                PermissionRule parsed = PermissionEngine.permissionRuleFromString(
                    rule.asText(), PermissionBehavior.ALLOW, source);
                if (Strings.CI.equals("Edit", parsed.toolName()) && parsed.pattern().isPresent()) {
                    allowWrite.add(resolvePermissionPath(parsed.pattern().get(), sourceRoot));
                }
            }
        }
        JsonNode deny = permissions.get("deny");
        if (deny == null || !deny.isArray()) return;
        for (JsonNode rule : deny) {
            PermissionRule parsed = PermissionEngine.permissionRuleFromString(
                rule.asText(), PermissionBehavior.DENY, source);
            if (parsed.pattern().isEmpty()) continue;
            String pattern = parsed.pattern().get();
            if (Strings.CI.equals("Edit", parsed.toolName())) {
                denyWrite.add(resolvePermissionPath(pattern, sourceRoot));
            } else if (Strings.CI.equals("Read", parsed.toolName())) {
                denyRead.add(resolvePermissionPath(pattern, sourceRoot));
            }
        }
    }

    private static String resolvePermissionPath(String pattern, Path sourceRoot) {
        String expanded = PathUtils.expandTilde(pattern);
        Path resolved =Strings.CS.startsWith( expanded, "/")
            ? sourceRoot.resolve(expanded.substring(1))
            : sourceRoot.resolve(expanded);
        return stripTrailingGlob(resolved.normalize().toString());
    }

    private static void addResolvedFilesystemPaths(JsonNode paths, Path sourceRoot,
                                                   List<String> output) {
        if (paths == null || !paths.isArray()) return;
        for (JsonNode path : paths) {
            if (path.isTextual() && !StringUtils.isBlank(path.asText())) {
                output.add(resolveSandboxFilesystemPath(path.asText(), sourceRoot));
            }
        }
    }

    private static String resolveSandboxFilesystemPath(String pattern, Path sourceRoot) {
        String expanded = PathUtils.expandTilde(pattern);
        if (Strings.CS.startsWith(expanded, "//")) expanded = expanded.substring(1);
        if (Strings.CS.startsWith(expanded, "/")) return stripTrailingGlob(expanded);
        return stripTrailingGlob(sourceRoot.resolve(expanded).normalize().toString());
    }

    private static String stripTrailingGlob(String path) {
        if (Strings.CS.endsWith(path, "/**")) return path.substring(0, path.length() - 3);
        if (Strings.CS.endsWith(path, "/*")) return path.substring(0, path.length() - 2);
        return path;
    }

    private static void extractWebFetchDomains(JsonNode permissions, RuleSource source,
                                               boolean allow, List<String> output) {
        if (permissions == null) return;
        JsonNode rules = permissions.get(allow ? "allow" : "deny");
        if (rules == null || !rules.isArray()) return;
        for (JsonNode rule : rules) {
            PermissionRule parsed = PermissionEngine.permissionRuleFromString(
                rule.asText(), allow ? PermissionBehavior.ALLOW : PermissionBehavior.DENY, source);
            if (Strings.CI.equals("WebFetch", parsed.toolName()) && parsed.pattern().isPresent()
                    &&Strings.CS.startsWith( parsed.pattern().get(), "domain:")) {
                output.add(parsed.pattern().get().substring("domain:".length()));
            }
        }
    }

    private record SettingsLayer(Path settingsPath, JsonNode inline, RuleSource source,
                                 Path sourceDir) {
        SettingsLayer {
            sourceDir = sourceDir == null
                ? settingsPath : sourceDir.toAbsolutePath().normalize();
        }
    }
}
