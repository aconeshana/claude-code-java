package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of {@code settings.sandbox.*} consumed by the bash sandbox runtime.
 */
public record SandboxConfig(
    boolean enabled,
    boolean failIfUnavailable,
    boolean autoAllowBashIfSandboxed,
    boolean allowUnsandboxedCommands,
    boolean enableWeakerNestedSandbox,
    boolean enableWeakerNetworkIsolation,
    List<String> excludedCommands,

    List<String> enabledPlatforms,
    Map<String, List<String>> ignoreViolations,
    SandboxNetworkConfig network,
    SandboxFilesystemConfig filesystem,
    SandboxRipgrepConfig ripgrep,
    /**
     * Raw effective {@code Edit}/{@code Read} permission rules containing glob
     * characters that bubblewrap cannot enforce.  A {@code null} value denotes
     * a legacy directly-constructed snapshot; callers may then use their
     * compatibility fallback.  Configurations built by the settings adapter
     * always provide a non-null list, including an empty list when sandboxing is
     * disabled.
     */
    List<String> permissionGlobWarnings
) {

    /**
     * Compatibility constructor for callers that predate the raw warning
     * snapshot.  Production settings loading uses the canonical constructor so
     * the warning list follows the merged settings exactly.
     */
    public SandboxConfig(boolean enabled, boolean failIfUnavailable,
                         boolean autoAllowBashIfSandboxed,
                         boolean allowUnsandboxedCommands,
                         boolean enableWeakerNestedSandbox,
                         boolean enableWeakerNetworkIsolation,
                         List<String> excludedCommands,
                         List<String> enabledPlatforms,
                         Map<String, List<String>> ignoreViolations,
                         SandboxNetworkConfig network,
                         SandboxFilesystemConfig filesystem,
                         SandboxRipgrepConfig ripgrep) {
        this(enabled, failIfUnavailable, autoAllowBashIfSandboxed,
            allowUnsandboxedCommands, enableWeakerNestedSandbox,
            enableWeakerNetworkIsolation, excludedCommands, enabledPlatforms,
            ignoreViolations, network, filesystem, ripgrep, null);
    }

    /** Disabled by default — preserves today's fully-unsandboxed behavior. */
    public static SandboxConfig disabled() {
        return new SandboxConfig(false, true, false, false, false, false,
            List.of(), null, Map.of(),
            SandboxNetworkConfig.DEFAULT, SandboxFilesystemConfig.DEFAULT, null, List.of());
    }

    /**
     * Build from the {@code settings.sandbox} JSON node. A null/non-object node
     * (no sandbox configured) yields {@link #disabled}.
     */
    public static SandboxConfig fromJson(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return disabled();
        }
        boolean enabled = node.path("enabled").asBoolean(false);

        // A missing binary then degrades to a startup warning + unsandboxed run, never a per-command reject.
        boolean failIfUnavailable = node.path("failIfUnavailable").asBoolean(false);

        boolean autoAllowBashIfSandboxed = node.path("autoAllowBashIfSandboxed").asBoolean(true);
        boolean allowUnsandboxedCommands = node.path("allowUnsandboxedCommands").asBoolean(true);
        boolean enableWeakerNestedSandbox = node.path("enableWeakerNestedSandbox").asBoolean(false);
        boolean enableWeakerNetworkIsolation = node.path("enableWeakerNetworkIsolation").asBoolean(false);
        List<String> excluded = strArray(node.get("excludedCommands"));
        List<String> enabledPlatforms = strArrayOrNull(node.get("enabledPlatforms"));
        Map<String, List<String>> ignore = readIgnore(node.get("ignoreViolations"));
        SandboxNetworkConfig network = SandboxNetworkConfig.fromJson(node.get("network"));
        SandboxFilesystemConfig filesystem = SandboxFilesystemConfig.fromJson(node.get("filesystem"));
        SandboxRipgrepConfig ripgrep = SandboxRipgrepConfig.fromJson(node.get("ripgrep"));
        return new SandboxConfig(enabled, failIfUnavailable, autoAllowBashIfSandboxed,
            allowUnsandboxedCommands, enableWeakerNestedSandbox, enableWeakerNetworkIsolation,
            excluded, enabledPlatforms, ignore, network, filesystem, ripgrep, List.of());
    }

    private static List<String> strArray(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        n.forEach(e -> out.add(e.asText()));
        return List.copyOf(out);
    }


    private static List<String> strArrayOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || !n.isArray()) return null;
        List<String> out = new ArrayList<>();
        n.forEach(e -> out.add(e.asText()));
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static Map<String, List<String>> readIgnore(JsonNode n) {
        if (n == null || !n.isObject()) return Map.of();
        Map<String, List<String>> m = new LinkedHashMap<>();
        n.fields().forEachRemaining(e -> m.put(e.getKey(), strArray(e.getValue())));
        return Map.copyOf(m);
    }

    // ── nested network config ────────────────────────────────────────────────

/** matches {@code SandboxSettingsSchema.network}. */
    public static record SandboxNetworkConfig(
        List<String> allowedDomains,
        List<String> deniedDomains,
        List<String> allowUnixSockets,
        boolean allowManagedDomainsOnly,
        boolean allowAllUnixSockets,
        boolean allowLocalBinding,
        Integer httpProxyPort,
        Integer socksProxyPort
    ) {
        public static final SandboxNetworkConfig DEFAULT = new SandboxNetworkConfig(
            List.of(), List.of(), List.of(), false, false, false, null, null);

        public static SandboxNetworkConfig fromJson(JsonNode n) {
            if (n == null || !n.isObject()) return DEFAULT;
            return new SandboxNetworkConfig(
                strArray(n.get("allowedDomains")),
                strArray(n.get("deniedDomains")),
                strArray(n.get("allowUnixSockets")),
                n.path("allowManagedDomainsOnly").asBoolean(false),
                n.path("allowAllUnixSockets").asBoolean(false),
                n.path("allowLocalBinding").asBoolean(false),
                n.path("httpProxyPort").isNumber() ? n.path("httpProxyPort").asInt() : null,
                n.path("socksProxyPort").isNumber() ? n.path("socksProxyPort").asInt() : null);
        }

        /** Whether any network access is permitted (relaxes the default deny). */
        public boolean networkAllowed() {
            return !allowedDomains.isEmpty() || allowAllUnixSockets
                || allowLocalBinding || allowManagedDomainsOnly
                || httpProxyPort != null || socksProxyPort != null;
        }
    }

    // ── nested filesystem config ─────────────────────────────────────────────

/** matches {@code SandboxSettingsSchema.filesystem}. */
    public static record SandboxFilesystemConfig(
        List<String> allowWrite,
        List<String> denyWrite,
        List<String> denyRead,
        List<String> allowRead,
        boolean allowManagedReadPathsOnly
    ) {
        public static final SandboxFilesystemConfig DEFAULT = new SandboxFilesystemConfig(
            List.of(), List.of(), List.of(), List.of(), false);

        public static SandboxFilesystemConfig fromJson(JsonNode n) {
            if (n == null || !n.isObject()) return DEFAULT;
            return new SandboxFilesystemConfig(
                strArray(n.get("allowWrite")),
                strArray(n.get("denyWrite")),
                strArray(n.get("denyRead")),
                strArray(n.get("allowRead")),
                n.path("allowManagedReadPathsOnly").asBoolean(false));
        }
    }

    // ── nested ripgrep config ──────────────────────────────────────────────────

/** matches {@code SandboxSettingsSchema.ripgrep} (command is required when present). */
    public static record SandboxRipgrepConfig(String command, List<String> args) {
        public static SandboxRipgrepConfig fromJson(JsonNode n) {
            if (n == null || !n.isObject()) return null;
            String cmd = n.path("command").asText(null);
            if (StringUtils.isBlank(cmd)) return null;
            return new SandboxRipgrepConfig(cmd, strArray(n.get("args")));
        }
    }
}
