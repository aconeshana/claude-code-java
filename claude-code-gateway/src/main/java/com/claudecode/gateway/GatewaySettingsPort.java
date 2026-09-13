package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Consumer-owned boundary for reading and mutating the three user-editable
 * settings tiers from the web client.
 *
 * <p>The gateway must not depend on {@code claude-code-services} (settings
 * persistence) or {@code claude-code-permissions} (rule/tier enums), so this
 * port speaks only strings and JSON: {@code tier} is one of
 * {@code "user"|"project"|"local"} and {@code behavior} is one of
 * {@code "allow"|"deny"|"ask"}. The CLI composition root implements the
 * adapter, translating those strings to {@code RuleSource}/
 * {@code PermissionBehavior} and delegating to {@code SettingsEditor} and
 * {@code SettingsSnapshots}.
 */
public interface GatewaySettingsPort {

    /** The effective settings merged across tiers, with per-tier source attribution. */
    default ObjectNode effectiveSettings() {
        return JsonUtils.getMapper().createObjectNode();
    }

    /** Writes or removes ({@code value == null}) one top-level user-tier setting. */
    default void writeUserValue(String key, JsonNode value) {}

    /** Writes or removes ({@code mode == null}) {@code permissions.defaultMode} for one tier. */
    default void writeDefaultPermissionMode(String mode, String tier) {}

    /** Replaces one permission behavior's rule array for one tier. */
    default void replacePermissionRules(String behavior, List<String> rules, String tier) {}

    /** Appends unseen directories to {@code permissions.additionalDirectories} for one tier. */
    default void addAdditionalDirectories(List<String> directories, String tier) {}

    /** Removes directories from {@code permissions.additionalDirectories} for one tier. */
    default void removeAdditionalDirectories(List<String> directories, String tier) {}
}
