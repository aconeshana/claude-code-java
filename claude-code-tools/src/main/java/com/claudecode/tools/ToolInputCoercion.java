package com.claudecode.tools;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


final class ToolInputCoercion {
    private static final String TASK_CREATE_COLLECTION_STEER =
        "TaskCreate creates ONE task per call and has no `tasks` or `todos` parameter. "
            + "Call TaskCreate once per task, passing `subject` (a brief title) and "
            + "`description` (what needs to be done) as top-level string parameters.";
    private static final String TASK_CREATE_AGENT_STEER =
        "This call used Agent-tool parameters (`prompt`/`subagent_type`). TaskCreate adds an "
            + "item to the task list and takes `subject` and `description` string parameters. "
            + "To delegate work to a subagent, use the Agent tool instead.";
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Map<String, Set<String>> BOOLEANS = Map.of(
        "Bash", Set.of("run_in_background", "dangerouslyDisableSandbox"),
        "PowerShell", Set.of("run_in_background", "dangerouslyDisableSandbox"),
        "Grep", Set.of("-n", "-i", "multiline"),
        "Edit", Set.of("replace_all"),
        "TaskOutput", Set.of("block"),
        "CronCreate", Set.of("recurring", "durable"),
        "SendMessage", Set.of("approve")
    );
    private static final Map<String, Set<String>> NUMBERS = Map.of(
        "Bash", Set.of("timeout"),
        "PowerShell", Set.of("timeout"),
        "Read", Set.of("offset", "limit"),
        "Grep", Set.of("-B", "-A", "-C", "context", "head_limit", "offset")
    );

    private ToolInputCoercion() {}

    static JsonNode coerce(String toolName, JsonNode input) {
        if (!(input instanceof ObjectNode object)) return input;
        ObjectNode copy = object.deepCopy();
        if (Strings.CS.equals("TaskCreate", toolName)) {
            coerceTaskCreate(copy);
        } else if (Strings.CS.equals("TaskUpdate", toolName)) {
            coerceTaskUpdate(copy);
        }
        coerceRecursive(copy, BOOLEANS.getOrDefault(toolName, Set.of()),
            NUMBERS.getOrDefault(toolName, Set.of()));
        return copy;
    }

    static String validationErrorSteer(String toolName, JsonNode input) {
        if (!Strings.CS.equals("TaskCreate", toolName) || !(input instanceof ObjectNode object)) {
            return null;
        }
        ObjectNode wrapped = object.get("task") instanceof ObjectNode task ? task : null;
        if (hasTaskCollection(object) || wrapped != null && hasTaskCollection(wrapped)) {
            return TASK_CREATE_COLLECTION_STEER;
        }
        boolean hasAgentParameters = object.has("prompt") || object.has("subagent_type")
            || wrapped != null && (wrapped.has("prompt") || wrapped.has("subagent_type"));
        if (hasAgentParameters
                && !(nonBlankText(object.get("subject"))
                    && nonBlankText(object.get("description")))) {
            return TASK_CREATE_AGENT_STEER;
        }
        return null;
    }

    private static void coerceTaskCreate(ObjectNode input) {
        if (hasTaskCollection(input) || hasAgentParametersWithoutTaskFields(input)) return;
        if (!input.has("subject") && !input.has("description") && input.has("task")) {
            JsonNode wrapper = input.get("task");
            if (nonBlankText(wrapper)) {
                input.remove("task");
                input.set("description", wrapper);
            } else if (wrapper instanceof ObjectNode wrapped) {
                if (hasTaskCollection(wrapped)
                        || hasAgentParametersWithoutTaskFields(wrapped)) {
                    return;
                }
                input.remove("task");
                wrapped.properties().forEach(entry -> input.set(
                    entry.getKey(), entry.getValue().deepCopy()));
            } else {
                return;
            }
        }

        applyAlias(input, List.of("title", "name"), "subject");
        applyAlias(input, List.of("content"), "description");
        applyAlias(input, List.of("active_form"), "activeForm");

        if (nonBlankText(input.get("subject")) && !input.has("description")) {
            input.set("description", input.get("subject"));
        } else if (nonBlankText(input.get("description")) && !input.has("subject")) {
            input.put("subject", taskSubject(input.get("description").asText()));
        }

        if (!nonBlankText(input.get("subject")) || !nonBlankText(input.get("description"))) {
            return;
        }
        input.properties().stream().map(Map.Entry::getKey).toList().stream()
            .filter(name -> !Set.of("subject", "description", "activeForm", "metadata")
                .contains(name))
            .forEach(input::remove);
        if (input.has("activeForm") && !input.get("activeForm").isTextual()) {
            input.remove("activeForm");
        }
        if (input.has("metadata") && !(input.get("metadata") instanceof ObjectNode)) {
            input.remove("metadata");
        }
    }

    private static void coerceTaskUpdate(ObjectNode input) {
        applyAlias(input, List.of("id", "task_id"), "taskId");
        applyAlias(input, List.of("active_form"), "activeForm");
    }

    private static void applyAlias(ObjectNode input, List<String> aliases, String target) {
        for (String alias : aliases) {
            if (input.has(alias) && !input.has(target) && nonBlankText(input.get(alias))) {
                input.set(target, input.get(alias));
                input.remove(alias);
            }
        }
    }

    private static boolean hasTaskCollection(ObjectNode input) {
        return input.has("tasks") || input.has("todos");
    }

    private static boolean hasAgentParametersWithoutTaskFields(ObjectNode input) {
        return (input.has("prompt") || input.has("subagent_type"))
            && !(nonBlankText(input.get("subject"))
                && nonBlankText(input.get("description")));
    }

    private static boolean nonBlankText(JsonNode value) {
        return value != null && value.isTextual()
            && !stripJavaScriptWhitespace(value.asText()).isEmpty();
    }

    private static String taskSubject(String description) {
        int newline = description.indexOf('\n');
        String firstLine = newline < 0 ? description : description.substring(0, newline);
        String trimmed = stripJavaScriptWhitespace(firstLine);
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints <= 80) return trimmed;
        int end = trimmed.offsetByCodePoints(0, 80);
        String prefix = trimmed.substring(0, end);
        int lastSpace = prefix.lastIndexOf(' ');
        return stripJavaScriptWhitespace(
            lastSpace > 40 ? prefix.substring(0, lastSpace) : prefix);
    }

    private static String stripJavaScriptWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJavaScriptWhitespace(value.charAt(start))) start++;
        while (end > start && isJavaScriptWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isJavaScriptWhitespace(char value) {
        return switch (value) {
            case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020,
                 0x00A0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F,
                 0x3000, 0xFEFF -> true;
            default -> value >= 0x2000 && value <= 0x200A;
        };
    }

    private static void coerceRecursive(ObjectNode object, Set<String> booleans, Set<String> numbers) {
        object.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isTextual() && booleans.contains(name)) {
                if (Strings.CS.equals("true", value.textValue())) object.put(name, true);
                else if (Strings.CS.equals("false", value.textValue())) object.put(name, false);
            } else if (value.isTextual() && numbers.contains(name)
                    && DECIMAL.matcher(value.textValue()).matches()) {
                try {
                    double parsed = Double.parseDouble(value.textValue());
                    if (Double.isFinite(parsed)) {
                        if (parsed == Math.rint(parsed) && parsed >= Long.MIN_VALUE && parsed <= Long.MAX_VALUE) {
                            object.put(name, (long) parsed);
                        } else object.put(name, parsed);
                    }
                } catch (NumberFormatException _) {}
            } else if (value instanceof ObjectNode nested) {
                coerceRecursive(nested, booleans, numbers);
            } else if (value.isArray()) {
                value.forEach(item -> {
                    if (item instanceof ObjectNode nested) coerceRecursive(nested, booleans, numbers);
                });
            }
        });
    }
}
