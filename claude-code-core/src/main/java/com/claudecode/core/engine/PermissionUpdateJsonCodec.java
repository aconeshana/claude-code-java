package com.claudecode.core.engine;

import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Exact JSON codec for the cross-process permission-update union.
 *
 * <ul>
 *   <li>field names,
 *       discriminators, behavior values, destinations, and mode values.</li>
 *   <li>permission suggestions
 *       and updated-permission payloads crossing the SDK control channel.</li>
 * </ul>
 */
public final class PermissionUpdateJsonCodec {
    private PermissionUpdateJsonCodec() { }

    public static ArrayNode toJson(List<PermissionUpdate> updates) {
        ArrayNode array = JsonUtils.getMapper().createArrayNode();
        if (updates != null) updates.forEach(update -> array.add(toJson(update)));
        return array;
    }

    public static ObjectNode toJson(PermissionUpdate update) {
        ObjectNode json = JsonUtils.getMapper().createObjectNode();
        switch (update) {
            case PermissionUpdate.AddRules add -> {
                json.put("type", "addRules");
                writeRules(json, add.rules());
                json.put("behavior", add.behavior().wireValue());
            }
            case PermissionUpdate.ReplaceRules replace -> {
                json.put("type", "replaceRules");
                writeRules(json, replace.rules());
                json.put("behavior", replace.behavior().wireValue());
            }
            case PermissionUpdate.RemoveRules remove -> {
                json.put("type", "removeRules");
                writeRules(json, remove.rules());
                json.put("behavior", remove.behavior().wireValue());
            }
            case PermissionUpdate.SetMode mode -> {
                json.put("type", "setMode");
                json.put("mode", mode.mode().wireValue());
            }
            case PermissionUpdate.AddDirectories add -> {
                json.put("type", "addDirectories");
                ArrayNode dirs = json.putArray("directories");
                add.directories().forEach(dirs::add);
            }
            case PermissionUpdate.RemoveDirectories remove -> {
                json.put("type", "removeDirectories");
                ArrayNode dirs = json.putArray("directories");
                remove.directories().forEach(dirs::add);
            }
        }
        json.put("destination", update.destination().wireValue());
        return json;
    }

    public static List<PermissionUpdate> fromJson(JsonNode array) {
        if (array == null || !array.isArray()) return List.of();
        List<PermissionUpdate> updates = new ArrayList<>();
        for (JsonNode node : array) {
            PermissionUpdate update = fromJsonObject(node);
            if (update != null) updates.add(update);
        }
        return List.copyOf(updates);
    }

    private static PermissionUpdate fromJsonObject(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        PermissionUpdate.Destination destination = destination(
            node.path("destination").asText(null));
        if (destination == null) return null;
        return switch (node.path("type").asText("")) {
            case "addRules" -> new PermissionUpdate.AddRules(
                rules(node.path("rules")), behavior(node.path("behavior").asText(null)), destination);
            case "replaceRules" -> new PermissionUpdate.ReplaceRules(
                rules(node.path("rules")), behavior(node.path("behavior").asText(null)), destination);
            case "removeRules" -> new PermissionUpdate.RemoveRules(
                rules(node.path("rules")), behavior(node.path("behavior").asText(null)), destination);
            case "setMode" -> {
                PermissionModeKind mode = mode(node.path("mode").asText(null));
                yield mode == null ? null : new PermissionUpdate.SetMode(mode, destination);
            }
            case "addDirectories" -> new PermissionUpdate.AddDirectories(
                strings(node.path("directories")), destination);
            case "removeDirectories" -> new PermissionUpdate.RemoveDirectories(
                strings(node.path("directories")), destination);
            default -> null;
        };
    }

    private static void writeRules(ObjectNode json, List<PermissionUpdate.RuleValue> values) {
        ArrayNode rules = json.putArray("rules");
        for (PermissionUpdate.RuleValue value : values) {
            ObjectNode rule = rules.addObject();
            rule.put("toolName", value.toolName());
            if (value.ruleContent() != null) rule.put("ruleContent", value.ruleContent());
        }
    }

    private static List<PermissionUpdate.RuleValue> rules(JsonNode array) {
        if (!array.isArray()) return List.of();
        List<PermissionUpdate.RuleValue> rules = new ArrayList<>();
        for (JsonNode node : array) {
            if (node.isTextual()) {
                String text = node.asText();
                int open = text.indexOf('(');
                if (open > 0 &&Strings.CS.endsWith( text, ")")) {
                    rules.add(new PermissionUpdate.RuleValue(
                        text.substring(0, open), text.substring(open + 1, text.length() - 1)));
                } else if (!StringUtils.isBlank(text)) {
                    rules.add(new PermissionUpdate.RuleValue(text, null));
                }
            } else if (node.isObject()) {
                String toolName = node.path("toolName").asText(null);
                if (StringUtils.isNotBlank(toolName)) {
                    JsonNode content = node.get("ruleContent");
                    rules.add(new PermissionUpdate.RuleValue(toolName,
                        content != null && content.isTextual() ? content.asText() : null));
                }
            }
        }
        return rules;
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            if (node.isTextual() && !StringUtils.isBlank(node.asText())) values.add(node.asText());
        }
        return values;
    }

    private static PermissionUpdate.Behavior behavior(String value) {
        return switch (value == null ? "" : value) {
            case "deny" -> PermissionUpdate.Behavior.DENY;
            case "ask" -> PermissionUpdate.Behavior.ASK;
            default -> PermissionUpdate.Behavior.ALLOW;
        };
    }

    private static PermissionUpdate.Destination destination(String value) {
        return switch (value == null ? "" : value) {
            case "userSettings" -> PermissionUpdate.Destination.USER_SETTINGS;
            case "projectSettings" -> PermissionUpdate.Destination.PROJECT_SETTINGS;
            case "localSettings" -> PermissionUpdate.Destination.LOCAL_SETTINGS;
            case "session" -> PermissionUpdate.Destination.SESSION;
            case "cliArg" -> PermissionUpdate.Destination.CLI_ARG;
            default -> null;
        };
    }

    private static PermissionModeKind mode(String value) {
        return switch (value == null ? "" : value) {
            case "default" -> PermissionModeKind.DEFAULT;
            case "plan" -> PermissionModeKind.PLAN;
            case "acceptEdits" -> PermissionModeKind.ACCEPT_EDITS;
            case "bypassPermissions" -> PermissionModeKind.BYPASS_PERMISSIONS;
            case "dontAsk" -> PermissionModeKind.DONT_ASK;
            case "auto" -> PermissionModeKind.AUTO;
            default -> null;
        };
    }
}
