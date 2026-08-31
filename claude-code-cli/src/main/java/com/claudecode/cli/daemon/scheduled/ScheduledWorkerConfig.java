package com.claudecode.cli.daemon.scheduled;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.tools.cron.CronSchedule;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

record ScheduledWorkerConfig(List<ScheduledTaskConfig> tasks, int maxConcurrent) {

    private static final Set<String> ROOT_FIELDS = Set.of("tasks", "maxConcurrent");
    private static final Set<String> TASK_FIELDS = Set.of(
        "id", "cron", "prompt", "directory", "enabled", "permissionMode",
        "model", "runTimeoutMinutes", "maxQueued");

    ScheduledWorkerConfig {
        tasks = List.copyOf(tasks);
    }

    static ScheduledWorkerConfig parse(String raw) {
        try {
            JsonNode root = JsonUtils.getMapper().readTree(raw);
            requireObject(root, "scheduled worker config");
            rejectUnknown(root, ROOT_FIELDS, "scheduled worker config");
            int maxConcurrent = positiveInt(root, "maxConcurrent", 1, Integer.MAX_VALUE);
            JsonNode taskNodes = root.get("tasks");
            if (taskNodes == null) taskNodes = JsonUtils.getMapper().createArrayNode();
            if (!taskNodes.isArray()) throw invalid("tasks must be an array");
            List<ScheduledTaskConfig> tasks = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonNode taskNode : taskNodes) {
                ScheduledTaskConfig task = parseTask(taskNode);
                if (!ids.add(task.id())) throw invalid("duplicate task id: " + task.id());
                tasks.add(task);
            }
            return new ScheduledWorkerConfig(tasks, maxConcurrent);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("invalid scheduled worker config: " + failure.getMessage());
        }
    }

    private static ScheduledTaskConfig parseTask(JsonNode node) {
        requireObject(node, "scheduled task");
        rejectUnknown(node, TASK_FIELDS, "scheduled task");
        String id = requiredText(node, "id");
        String cron = requiredText(node, "cron");
        if (!CronSchedule.isValid(cron)) throw invalid("invalid cron for task " + id + ": " + cron);
        String prompt = requiredText(node, "prompt");
        String directoryText = requiredText(node, "directory");
        Path directory;
        try {
            directory = Path.of(directoryText).toAbsolutePath().normalize();
        } catch (InvalidPathException failure) {
            throw invalid("invalid directory for task " + id + ": " + failure.getMessage());
        }
        boolean enabled = booleanValue(node, "enabled", true);
        String permission = textValue(node, "permissionMode", "dontAsk");
        ScheduledPermissionMode permissionMode = ScheduledPermissionMode.parse(permission);
        String model = nullableText(node, "model");
        int runTimeoutMinutes = positiveInt(node, "runTimeoutMinutes", 30, 10_080);
        int maxQueued = positiveInt(node, "maxQueued", 1, Integer.MAX_VALUE);
        return new ScheduledTaskConfig(id, cron, prompt, directory, enabled,
            permissionMode, model, runTimeoutMinutes, maxQueued);
    }

    private static void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) throw invalid(label + " must be an object");
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String label) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw invalid("unknown " + label + " field: " + field);
        });
    }

    private static String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (StringUtils.isBlank(value)) throw invalid(field + " must be a non-empty string");
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid(field + " must be a string");
        return value.asText();
    }

    private static String textValue(JsonNode node, String field, String fallback) {
        String value = nullableText(node, field);
        return value == null ? fallback : value;
    }

    private static boolean booleanValue(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw invalid(field + " must be a boolean");
        return value.asBoolean();
    }

    private static int positiveInt(JsonNode node, String field, int fallback, int maximum) {
        JsonNode value = node.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be an integer");
        }
        int parsed = value.asInt();
        if (parsed <= 0 || parsed > maximum) {
            throw invalid(field + " must be between 1 and " + maximum);
        }
        return parsed;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
