package com.claudecode.services.statusline;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.services.config.SettingsSnapshots;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;


public record StatusLineConfig(String command, int padding) {

    /**
     * Loads the merged {@code statusLine} config from the standard tiers for
     * {@code cwd}, or {@link Optional#empty} when no tier configures a valid
     * command-type status line.
     */
    public static Optional<StatusLineConfig> load(String cwd) {

// getSettings_DEPRECATED call. In particular, --settings and SDK
        // apply_flag_settings live in the flagSettings tier and are not on
        // disk, so loading the four file paths directly silently ignored them.
        JsonNode snapshot = SettingsSnapshots.withSources(cwd);
        JsonNode effective = snapshot.path("effective");
        JsonNode policy = null;
        JsonNode sources = snapshot.path("sources");
        if (sources.isArray()) {
            for (JsonNode source : sources) {
                if (Strings.CS.equals("policySettings", source.path("source").asText())) {
                    policy = source.path("settings");
                    break;
                }
            }
        }
        boolean policyDisablesAll = policy != null
            && policy.path("disableAllHooks").asBoolean(false);
        if (policyDisablesAll) {

            // selecting a source: a managed disableAllHooks suppresses every
            // status-line command, including the policy-defined one.
            return Optional.empty();
        }
        boolean managedOnly = (policy != null
                && policy.path("allowManagedHooksOnly").asBoolean(false))
            || effective.path("disableAllHooks").asBoolean(false);
        JsonNode selected = managedOnly
            ? (policy == null ? null : policy.get("statusLine"))
            : effective.get("statusLine");
        return parse(selected);
    }

    /**
     * Testable core: merges the {@code statusLine} node across {@code tiers}
     * (later wins) and validates it. Package-private-friendly signature so
     * tests can point at temp-dir settings files instead of the real ones.
     */
    public static Optional<StatusLineConfig> load(List<Path> tiers) {
        JsonNode winning = null;
        for (Path tier : tiers) {
            JsonNode node = readStatusLineNode(tier);
            if (node != null) winning = node;
        }
        return parse(winning);
    }

    /** Parses (and validates) a raw {@code statusLine} JSON node. */
    static Optional<StatusLineConfig> parse(JsonNode node) {
        if (node == null || !node.isObject()) return Optional.empty();

        if (!Strings.CS.equals("command", node.path("type").asText(null))) return Optional.empty();
        String command = node.path("command").asText(null);
        if (StringUtils.isBlank(command)) return Optional.empty();

        JsonNode paddingNode = node.get("padding");
        int padding = paddingNode != null && paddingNode.isNumber()
            ? Math.max(0, paddingNode.asInt())
            : 0;
        return Optional.of(new StatusLineConfig(command, padding));
    }

    private static JsonNode readStatusLineNode(Path settingsPath) {
        if (settingsPath == null || !Files.isReadable(settingsPath)) return null;
        try {
            JsonNode root = JsonUtils.readJson(settingsPath);
            JsonNode node = root.get("statusLine");
            return node != null && node.isObject() ? node : null;
        } catch (Exception _) {
            return null;
        }
    }
}
