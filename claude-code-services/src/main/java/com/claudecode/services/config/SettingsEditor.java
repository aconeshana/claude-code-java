package com.claudecode.services.config;

import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists mutations to the three user-editable settings tiers without coupling settings readers to
 * write-side concerns.
 */
public final class SettingsEditor {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsEditor.class);

    private SettingsEditor() {}

    /** Writes one top-level boolean in the user settings tier. */
    public static void writeUserBoolean(String key, boolean value) {
        writeBoolean(SettingsPaths.userSettingsPath(), key, value);
    }

    /**
     * Writes or removes one top-level string in the user settings tier.
     */
    public static void writeUserString(String key, String value) {
        writeUserValue(key, value);
    }

    /** Writes or removes one Jackson-serializable top-level user setting. */
    public static void writeUserValue(String key, Object value) {
        try {
            SettingsFileStore.mutate(SettingsPaths.userSettingsPath(), root -> {
                if (value == null) {
                    root.remove(key);
                } else {
                    root.set(key, JsonUtils.getMapper().valueToTree(value));
                }
            });
        } catch (Exception e) {
            LOG.warn("Failed to persist a setting to user settings");
            throw persistFailure("save to", RuleSource.USER_SETTINGS, e);
        }
    }

    /** Writes one top-level boolean in the local settings tier for {@code cwd}. */
    public static void writeLocalBoolean(String cwd, String key, boolean value) {
        if (writeBoolean(SettingsPaths.sessionLocalSettingsPath(cwd), key, value)) {
            scheduleLocalSettingsGitignore(cwd, RuleSource.LOCAL_SETTINGS);
        }
    }

    /**
     * Adds one permission rule to {@code permissions.allow}, {@code .deny}, or
     * {@code .ask}, preserving the existing order and avoiding equivalent
     * legacy spellings.
     */
    public static void addPermissionRule(String cwd, PermissionBehavior behavior, String rule,
                                         RuleSource tier) {
        if (StringUtils.isBlank(rule)) return;
        Path settingsPath = editableSettingsPath(cwd, tier);
        String arrayKey = arrayKeyForBehavior(behavior);
        try {
            SettingsFileStore.mutate(settingsPath, root -> {
                ObjectNode permissions = permissionsObject(root);
                ArrayNode rules = stringArray(permissions, arrayKey);
                if (containsEquivalentRule(rules, rule, behavior, tier)) return;
                rules.add(rule);
                permissions.set(arrayKey, rules);
                root.set("permissions", permissions);
            });
        } catch (Exception e) {
            LOG.warn("Failed to persist a permission rule to {}", tier.displayName());
            throw persistFailure("save to", tier, e);
        }
        scheduleLocalSettingsGitignore(cwd, tier);
    }

    /**
     * Removes all normalized equivalents of {@code rule} from its permission
     * behavior array. Missing files and missing arrays are no-ops.
     */
    public static void removePermissionRule(String cwd, PermissionBehavior behavior, String rule,
                                            RuleSource tier) {
        Path settingsPath = editableSettingsPath(cwd, tier);
        String arrayKey = arrayKeyForBehavior(behavior);
        try {
// deletePermissionRuleFromSettings first asks the validated source reader for the
            // rule.  A schema-invalid source therefore behaves as a no-op; only the add/update
            // path is allowed to fall back to raw JSON so unrelated invalid fields survive.
            ObjectNode accepted = SettingsTreeReader.readAccepted(settingsPath, false);
            if (accepted == null) return;
            JsonNode acceptedPermissions = accepted.get("permissions");
            JsonNode acceptedRules = acceptedPermissions != null && acceptedPermissions.isObject()
                ? acceptedPermissions.get(arrayKey) : null;
            if (acceptedRules == null || !acceptedRules.isArray()) return;
            boolean present = false;
            for (JsonNode candidate : acceptedRules) {
                if (candidate.isTextual()
                        && permissionRulesEquivalent(candidate.asText(), rule, behavior, tier)) {
                    present = true;
                    break;
                }
            }
            if (!present) return;
            boolean changed = SettingsFileStore.mutateIfExists(settingsPath, root -> {
                JsonNode permissionsNode = root.get("permissions");
                if (permissionsNode == null || !permissionsNode.isObject()) return false;
                ObjectNode permissions = (ObjectNode) permissionsNode;
                JsonNode rulesNode = permissions.get(arrayKey);
                if (rulesNode == null || !rulesNode.isArray()) return false;
                ArrayNode rules = (ArrayNode) rulesNode;
                boolean removed = false;
                for (int i = rules.size() - 1; i >= 0; i--) {
                    JsonNode candidate = rules.get(i);
                    if (candidate.isTextual()
                            && permissionRulesEquivalent(candidate.asText(), rule, behavior, tier)) {
                        rules.remove(i);
                        removed = true;
                    }
                }
                if (!removed) return false;
                permissions.set(arrayKey, rules);
                root.set("permissions", permissions);
                return true;
            });
            if (changed) scheduleLocalSettingsGitignore(cwd, tier);
        } catch (Exception e) {
            LOG.warn("Failed to remove a permission rule from {}", tier.displayName());
            throw persistFailure("remove from", tier, e);
        }
    }

    /**
     * Persists a {@code PermissionUpdate.removeRules} operation.
     */
    public static void removePermissionRuleForUpdate(String cwd, PermissionBehavior behavior,
                                                     String rule, RuleSource tier) {
        Path settingsPath = editableSettingsPath(cwd, tier);
        String arrayKey = arrayKeyForBehavior(behavior);
        try {
            SettingsFileStore.mutate(settingsPath, root -> {
                ObjectNode permissions = permissionsObject(root);
                ArrayNode rules = stringArray(permissions, arrayKey);
                for (int i = rules.size() - 1; i >= 0; i--) {
                    JsonNode candidate = rules.get(i);
                    if (candidate.isTextual()
                            && permissionRulesEquivalent(candidate.asText(), rule, behavior, tier)) {
                        rules.remove(i);
                    }
                }
                permissions.set(arrayKey, rules);
                root.set("permissions", permissions);
            });
        } catch (Exception e) {
            LOG.warn("Failed to persist removal of a permission rule from {}", tier.displayName());
            throw persistFailure("remove from", tier, e);
        }
        scheduleLocalSettingsGitignore(cwd, tier);
    }

    /**
     * Replaces one permission behavior array, retaining supplied nonblank rule
     * order while preserving every other settings field.
     */
    public static void replacePermissionRules(String cwd, PermissionBehavior behavior,
                                              List<String> rules, RuleSource tier) {
        Path settingsPath = editableSettingsPath(cwd, tier);
        String arrayKey = arrayKeyForBehavior(behavior);
        try {
            SettingsFileStore.mutate(settingsPath, root -> {
                ObjectNode permissions = permissionsObject(root);
                ArrayNode replacement = JsonUtils.getMapper().createArrayNode();
                for (String rule : rules) {
                    if (StringUtils.isNotBlank(rule)) replacement.add(rule);
                }
                permissions.set(arrayKey, replacement);
                root.set("permissions", permissions);
            });
        } catch (Exception e) {
            LOG.warn("Failed to replace permission rules in {}", tier.displayName());
            throw persistFailure("save to", tier, e);
        }
        scheduleLocalSettingsGitignore(cwd, tier);
    }

    /**
     * Writes {@code permissions.defaultMode} to one editable tier, or removes
     * it when {@code mode} is {@code null}.
     */
    public static void writeDefaultPermissionMode(String cwd, String mode, RuleSource tier) {
        Path settingsPath = editableSettingsPath(cwd, tier);
        boolean saved = false;
        try {
            SettingsFileStore.mutate(settingsPath, root -> {
                ObjectNode permissions = permissionsObject(root);
                if (mode == null) {
                    permissions.remove("defaultMode");
                } else {
                    permissions.put("defaultMode", mode);
                }
                root.set("permissions", permissions);
            });
            saved = true;
        } catch (Exception _) {
            LOG.warn("Failed to persist permissions.defaultMode to {}", tier.displayName());
        }
        if (saved) scheduleLocalSettingsGitignore(cwd, tier);
    }

    /**
     * Appends unseen nonblank directories to
     * {@code permissions.additionalDirectories}, preserving first-seen order.
     */
    public static void addAdditionalDirectories(String cwd, List<String> directories,
                                                RuleSource tier) {
        if (directories == null || directories.isEmpty()) return;
        Path settingsPath = editableSettingsPath(cwd, tier);
        try {
            SettingsFileStore.mutate(settingsPath, root -> {
                ObjectNode permissions = permissionsObject(root);
                ArrayNode storedDirectories = stringArray(permissions, "additionalDirectories");
                Set<String> seen = new HashSet<>();
                for (JsonNode directory : storedDirectories) {
                    if (directory.isTextual()) seen.add(directory.asText());
                }
                for (String directory : directories) {
                    if (StringUtils.isNotBlank(directory) && seen.add(directory)) {
                        storedDirectories.add(directory);
                    }
                }
                permissions.set("additionalDirectories", storedDirectories);
                root.set("permissions", permissions);
            });
        } catch (Exception e) {
            LOG.warn("Failed to persist additional directories to {}", tier.displayName());
            throw persistFailure("save to", tier, e);
        }
        scheduleLocalSettingsGitignore(cwd, tier);
    }

    /** Removes the supplied directories from {@code permissions.additionalDirectories}. */
    public static void removeAdditionalDirectories(String cwd, List<String> directories,
                                                   RuleSource tier) {
        Path settingsPath = editableSettingsPath(cwd, tier);
        Set<String> directoriesToRemove = new HashSet<>(directories);
        try {


            // there is no matching rule.
            SettingsFileStore.mutate(settingsPath, root -> {
                JsonNode permissionsNode = root.get("permissions");
                ObjectNode permissions = permissionsNode != null && permissionsNode.isObject()
                    ? (ObjectNode) permissionsNode
                    : JsonUtils.getMapper().createObjectNode();
                JsonNode directoriesNode = permissions.get("additionalDirectories");
                if (directoriesNode != null && directoriesNode.isArray()) {
                    ArrayNode storedDirectories = (ArrayNode) directoriesNode;
                    for (int i = storedDirectories.size() - 1; i >= 0; i--) {
                        JsonNode directory = storedDirectories.get(i);
                        if (directory.isTextual()
                                && directoriesToRemove.contains(directory.asText())) {
                            storedDirectories.remove(i);
                        }
                    }
                }
                if (!permissions.has("additionalDirectories")) {
                    permissions.set("additionalDirectories", JsonUtils.getMapper().createArrayNode());
                }
                root.set("permissions", permissions);
            });
        } catch (Exception e) {
            LOG.warn("Failed to remove additional directories from {}", tier.displayName());
            throw persistFailure("remove from", tier, e);
        }
        scheduleLocalSettingsGitignore(cwd, tier);
    }

    /**
     * Adds one directory to local settings for the {@code /add-dir} remember
     * flow, preserving unrelated settings and avoiding duplicate entries.
     */
    public static void addAdditionalDirectoryToLocalSettings(String cwd, String absolutePath) {
        if (StringUtils.isBlank(absolutePath)) return;
        Path settingsPath = SettingsPaths.sessionLocalSettingsPath(cwd);
        try {
            SettingsFileStore.mutate(settingsPath, root -> {
                ObjectNode permissionObject = permissionsObject(root);
                ArrayNode directories = stringArray(permissionObject, "additionalDirectories");
                    for (JsonNode directory : directories) {
                        if (directory.isTextual() && directory.asText().equals(absolutePath)) return;
                    }
                directories.add(absolutePath);
                permissionObject.set("additionalDirectories", directories);
                root.set("permissions", permissionObject);
            });
        } catch (Exception e) {
            LOG.warn("Failed to persist an additional directory to local settings");
            throw new RuntimeException("Failed to save to local settings", e);
        }
        scheduleLocalSettingsGitignore(cwd, RuleSource.LOCAL_SETTINGS);
    }

    private static boolean writeBoolean(Path settingsPath, String key, boolean value) {
        try {
            SettingsFileStore.mutate(settingsPath, root -> root.put(key, value));
            return true;
        } catch (Exception _) {
            LOG.warn("Failed to persist a boolean setting");
            return false;
        }
    }

    private static Path editableSettingsPath(String cwd, RuleSource tier) {
        return switch (tier) {
            case USER_SETTINGS -> SettingsPaths.userSettingsPath();
            case PROJECT_SETTINGS -> SettingsPaths.sessionProjectSettingsPath(cwd);
            case LOCAL_SETTINGS -> SettingsPaths.sessionLocalSettingsPath(cwd);
            default -> throw new IllegalArgumentException(
                "Not a user-editable settings tier: " + tier);
        };
    }

    private static String arrayKeyForBehavior(PermissionBehavior behavior) {
        return switch (behavior) {
            case ALLOW -> "allow";
            case DENY -> "deny";
            case ASK -> "ask";
            case PASSTHROUGH -> throw new IllegalArgumentException(
                "PASSTHROUGH is not a persistable rule behavior");
        };
    }

    private static ObjectNode permissionsObject(ObjectNode root) {
        JsonNode existing = root.get("permissions");
        return existing != null && existing.isObject()
            ? (ObjectNode) existing
            : JsonUtils.getMapper().createObjectNode();
    }

    private static ArrayNode stringArray(ObjectNode parent, String key) {
        JsonNode existing = parent.get(key);
        return existing != null && existing.isArray()
            ? (ArrayNode) existing
            : JsonUtils.getMapper().createArrayNode();
    }

    private static boolean containsEquivalentRule(ArrayNode rules, String rule,
                                                  PermissionBehavior behavior, RuleSource tier) {
        for (JsonNode candidate : rules) {
            if (candidate.isTextual()
                    && permissionRulesEquivalent(candidate.asText(), rule, behavior, tier)) {
                return true;
            }
        }
        return false;
    }

    private static boolean permissionRulesEquivalent(String left, String right,
                                                     PermissionBehavior behavior, RuleSource tier) {
        PermissionRule leftRule = PermissionEngine.permissionRuleFromString(left, behavior, tier);
        PermissionRule rightRule = PermissionEngine.permissionRuleFromString(right, behavior, tier);
        return PermissionEngine.permissionRuleToString(leftRule)
            .equals(PermissionEngine.permissionRuleToString(rightRule));
    }

    private static RuntimeException persistFailure(String action, RuleSource tier, Exception cause) {
        return new RuntimeException("Failed to " + action + " " + tier.displayName(), cause);
    }

/** matches {@code updateSettingsForSource}: local writes keep the file gitignored. */
    private static void scheduleLocalSettingsGitignore(String cwd, RuleSource tier) {
        GitSettings.ensureLocalSettingsIgnored(cwd, tier);
    }
}
