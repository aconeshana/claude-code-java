package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shared on-disk protocol for the two Java task-store facades.
 */
final class TaskPersistence {

    private static final String HIGH_WATER_MARK_FILE = ".highwatermark";
    private static final String LIST_LOCK_TARGET = ".lock";
    private static final Pattern JAVASCRIPT_DECIMAL_NUMBER = Pattern.compile(
        "[+-]?(?:Infinity|(?:(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?))");
    private static final Pattern JAVASCRIPT_HEX_NUMBER = Pattern.compile("0[xX][0-9a-fA-F]+");
    private static final Pattern JAVASCRIPT_BINARY_NUMBER = Pattern.compile("0[bB][01]+");
    private static final Pattern JAVASCRIPT_OCTAL_NUMBER = Pattern.compile("0[oO][0-7]+");

    record StoredTask(String storageId, Task task) {}

    private TaskPersistence() {}

    static String sanitizePathComponent(String input) {
        return input == null ? "default" : input.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    static Path taskPath(Path tasksDir, String taskId) {
        return tasksDir.resolve(sanitizePathComponent(taskId) + ".json");
    }

    static String nextSequentialId(Path tasksDir) throws IOException {
        return withListLock(tasksDir, () -> {
            double next = Math.max(
                highestIdFromFiles(tasksDir), readHighWaterMark(tasksDir)) + 1;
            writeHighWaterMark(tasksDir, next);
            return javaScriptNumberToString(next);
        });
    }

    static <T> T createSequential(Path tasksDir, Function<String, T> factory)
            throws IOException {
        return createSequential(tasksDir, factory, false);
    }

    static <T> T createSequential(
            Path tasksDir, Function<String, T> factory, boolean pretty) throws IOException {
        return withListLock(tasksDir, () -> {
            double next = Math.max(
                highestIdFromFiles(tasksDir), readHighWaterMark(tasksDir)) + 1;
            String taskId = javaScriptNumberToString(next);
            T value = factory.apply(taskId);
            save(tasksDir, taskId, value, pretty);
            return value;
        });
    }

    static void ensureHighWaterMarkAtLeast(Path tasksDir, double value) throws IOException {
        if (value <= 0) return;
        withListLock(tasksDir, () -> {
            if (value > readHighWaterMark(tasksDir)) {
                writeHighWaterMark(tasksDir, value);
            }
            return null;
        });
    }

    static double readHighWaterMark(Path tasksDir) {
        try {
            String content = stripJavaScriptWhitespace(Files.readString(
                tasksDir.resolve(HIGH_WATER_MARK_FILE), StandardCharsets.UTF_8));
            return parseIntOrZero(content);
        } catch (IOException _) {
            return 0;
        }
    }

    private static void writeHighWaterMark(Path tasksDir, double value) throws IOException {
        FileUtils.writeString(tasksDir.resolve(HIGH_WATER_MARK_FILE),
                javaScriptNumberToString(value), StandardCharsets.UTF_8);
    }

    static double highestIdFromFiles(Path tasksDir) throws IOException {
        if (!Files.isDirectory(tasksDir)) return 0;
        double highest = 0;
        for (Path file : FileUtils.listFiles(tasksDir, "*.json")) {
            String fileName = file.getFileName().toString();
            String id = fileName.substring(0, fileName.length() - ".json".length());
            highest = Math.max(highest, parseIntOrZero(id));
        }
        return highest;
    }

    static <T> void save(Path tasksDir, String taskId, T value) throws IOException {
        save(tasksDir, taskId, value, false);
    }

    static <T> void save(
            Path tasksDir, String taskId, T value, boolean pretty) throws IOException {
        Path target = taskPath(tasksDir, taskId);
        FileUtils.atomicReplace(target, temp -> JsonUtils.writeJson(temp, value, pretty));
    }

    static <T> Optional<T> update(
            Path tasksDir, String taskId, Class<T> type, UnaryOperator<T> updater)
            throws IOException {
        Path target = taskPath(tasksDir, taskId);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return TaskFileLock.withLock(target, () -> {
                if (!Files.isRegularFile(target)) return Optional.empty();
                T current = JsonUtils.readJson(target, type);
                T updated = updater.apply(current);
                save(tasksDir, taskId, updated);
                return Optional.of(updated);
            });
        } catch (NoSuchFileException _) {
            return Optional.empty();
        }
    }

    static Optional<Task> updateTask(
            Path tasksDir, String taskId, UnaryOperator<Task> updater) throws IOException {
        Path target = taskPath(tasksDir, taskId);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return TaskFileLock.withLock(target, () -> {
                if (!Files.isRegularFile(target)) return Optional.empty();
                Task current;
                try {
                    current = readTask(target);
                } catch (IOException _) {
                    return Optional.empty();
                }
                Task updated = updater.apply(current).withId(taskId);
                save(tasksDir, taskId, updated, true);
                return Optional.of(updated);
            });
        } catch (NoSuchFileException _) {
            return Optional.empty();
        }
    }

    static Optional<Task> claimTask(
            Path tasksDir,
            String taskId,
            String owner,
            BiConsumer<Path, Exception> malformedFileHandler) throws IOException {
        Path target = taskPath(tasksDir, taskId);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return TaskFileLock.withLock(target, () -> {
                if (!Files.isRegularFile(target)) return Optional.empty();
                Task current;
                try {
                    current = readTask(target);
                } catch (IOException error) {
                    if (malformedFileHandler != null) {
                        malformedFileHandler.accept(target, error);
                    }
                    return Optional.empty();
                }
                String currentOwner = current.owner().orElse(null);
                if (isJavaScriptTruthyString(currentOwner)
                        && !currentOwner.equals(owner)) {
                    return Optional.empty();
                }
                if (current.status() == TodoStatus.COMPLETED) return Optional.empty();
                Set<String> unresolved = loadStoredTasks(tasksDir, malformedFileHandler).stream()
                    .map(StoredTask::task)
                    .filter(task -> task.status() != TodoStatus.COMPLETED)
                    .map(Task::id)
                    .collect(Collectors.toSet());
                if (current.blockedBy().stream().anyMatch(unresolved::contains)) {
                    return Optional.empty();
                }
                Task claimed = current.withOwner(owner).withId(taskId);
                save(tasksDir, taskId, claimed, true);
                return Optional.of(claimed);
            });
        } catch (NoSuchFileException _) {
            return Optional.empty();
        }
    }

    static void resetTaskList(Path tasksDir) throws IOException {
        withListLock(tasksDir, () -> {
            double highest = Math.max(
                highestIdFromFiles(tasksDir), readHighWaterMark(tasksDir));
            if (highest > 0) writeHighWaterMark(tasksDir, highest);
            for (Path file : FileUtils.listFiles(tasksDir, "*.json")) {
                Files.deleteIfExists(file);
            }
            return null;
        });
    }

    private static <T> T withListLock(
            Path tasksDir, TaskFileLock.Operation<T> operation) throws IOException {
        Files.createDirectories(tasksDir);
        Path target = tasksDir.resolve(LIST_LOCK_TARGET);
        try {
            Files.createFile(target);
        } catch (FileAlreadyExistsException _) {
            // Released 2.1.197 keeps this empty target file between lock acquisitions.
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("Task list lock target is not a regular file: " + target);
        }
        return TaskFileLock.withLock(target, operation);
    }

    static <T> List<T> loadAll(
            Path tasksDir, Class<T> type, BiConsumer<Path, Exception> malformedFileHandler)
            throws IOException {
        if (!Files.isDirectory(tasksDir)) return List.of();
        List<T> loaded = new ArrayList<>();
        for (Path file : FileUtils.listFiles(tasksDir, "*.json")) {
            try {
                loaded.add(JsonUtils.readJson(file, type));
            } catch (Exception e) {
                if (malformedFileHandler != null) {
                    malformedFileHandler.accept(file, e);
                }
            }
        }
        return loaded;
    }

    static List<StoredTask> loadStoredTasks(
            Path tasksDir, BiConsumer<Path, Exception> malformedFileHandler) throws IOException {
        if (!Files.isDirectory(tasksDir)) return List.of();
        List<StoredTask> loaded = new ArrayList<>();
        for (Path file : FileUtils.listFiles(tasksDir, "*.json")) {
            try {
                String fileName = file.getFileName().toString();
                String storageId = fileName.substring(0,
                    fileName.length() - ".json".length());
                loaded.add(new StoredTask(storageId, readTask(file)));
            } catch (Exception e) {
                if (malformedFileHandler != null) malformedFileHandler.accept(file, e);
            }
        }
        return loaded;
    }

    private static Task readTask(Path file) throws IOException {
        JsonNode node = JsonUtils.readJson(file);
        if (node == null || !node.isObject()) {
            throw new IOException("task must be a JSON object");
        }
        String id = requiredString(node, "id");
        String subject = requiredString(node, "subject");
        String description = requiredString(node, "description");
        Optional<String> activeForm = optionalString(node, "activeForm");
        Optional<String> owner = optionalString(node, "owner");
        String statusValue = requiredString(node, "status");
        TodoStatus status = TodoStatusWire.fromWire(statusValue);
        if (status == null) throw new IOException("status has an unsupported value");
        List<String> blocks = requiredStringArray(node, "blocks");
        List<String> blockedBy = requiredStringArray(node, "blockedBy");
        Optional<Map<String, Object>> metadata = Optional.empty();
        if (node.has("metadata")) {
            JsonNode metadataNode = node.get("metadata");
            if (!metadataNode.isObject()) throw new IOException("metadata must be an object");
            try {
                metadata = Optional.of(JsonUtils.getMapper().convertValue(metadataNode,
                    new TypeReference<LinkedHashMap<String, Object>>() { }));
            } catch (IllegalArgumentException e) {
                throw new IOException("metadata is invalid", e);
            }
        }
        return new Task(id, subject, description, activeForm, owner, status,
            blocks, blockedBy, metadata);
    }

    private static String requiredString(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IOException(field + " must be a string");
        }
        return value.textValue();
    }

    private static Optional<String> optionalString(JsonNode node, String field) throws IOException {
        if (!node.has(field)) return Optional.empty();
        JsonNode value = node.get(field);
        if (!value.isTextual()) throw new IOException(field + " must be a string");
        return Optional.of(value.textValue());
    }

    private static List<String> requiredStringArray(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IOException(field + " must be an array");
        }
        List<String> strings = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (!item.isTextual()) throw new IOException(field + " must contain only strings");
            strings.add(item.textValue());
        }
        return List.copyOf(strings);
    }

    static double parseIntOrZero(String value) {
        if (value == null) return 0;
        int index = 0;
        while (index < value.length() && isJavaScriptWhitespace(value.charAt(index))) index++;
        int numberStart = index;
        if (index < value.length() && (value.charAt(index) == '+' || value.charAt(index) == '-')) {
            index++;
        }
        int digitsStart = index;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current < '0' || current > '9') break;
            index++;
        }
        if (index == digitsStart) return 0;
        try {
            return Double.parseDouble(value.substring(numberStart, index));
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    static String javaScriptNumberToString(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (value == Double.POSITIVE_INFINITY) return "Infinity";
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
        if (value == 0.0d) return "0";
        double absolute = Math.abs(value);
        if (absolute >= 1e-6d && absolute < 1e21d) {
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
        String rendered = Double.toString(value);
        int exponentMarker = Math.max(rendered.indexOf('E'), rendered.indexOf('e'));
        if (exponentMarker < 0) return rendered;
        String mantissa = rendered.substring(0, exponentMarker);
        if (mantissa.endsWith(".0")) mantissa = mantissa.substring(0, mantissa.length() - 2);
        int exponent = Integer.parseInt(rendered.substring(exponentMarker + 1));
        return mantissa + "e" + (exponent >= 0 ? "+" : "") + exponent;
    }

    static List<Task> sortLikeReleasedTaskList(List<Task> tasks) {
        ArrayList<Task> sorted = new ArrayList<>(tasks);
        for (int index = 1; index < sorted.size(); index++) {
            Task value = sorted.get(index);
            int insertion = index;
            while (insertion > 0
                    && compareLikeJavaScriptNumber(
                        value.id(), sorted.get(insertion - 1).id()) < 0) {
                sorted.set(insertion, sorted.get(insertion - 1));
                insertion--;
            }
            sorted.set(insertion, value);
        }
        return List.copyOf(sorted);
    }

    private static int compareLikeJavaScriptNumber(String left, String right) {
        Double leftNumber = parseJavaScriptNumber(left);
        Double rightNumber = parseJavaScriptNumber(right);
        if (leftNumber == null || rightNumber == null) return 0;
        double difference = leftNumber - rightNumber;
        if (Double.isNaN(difference) || difference == 0.0d) return 0;
        return difference < 0.0d ? -1 : 1;
    }

    private static Double parseJavaScriptNumber(String id) {
        if (id == null) return 0.0d;
        String value = stripJavaScriptWhitespace(id);
        if (value.isEmpty()) return 0.0d;
        if (JAVASCRIPT_HEX_NUMBER.matcher(value).matches()) {
            return new BigInteger(value.substring(2), 16).doubleValue();
        }
        if (JAVASCRIPT_BINARY_NUMBER.matcher(value).matches()) {
            return new BigInteger(value.substring(2), 2).doubleValue();
        }
        if (JAVASCRIPT_OCTAL_NUMBER.matcher(value).matches()) {
            return new BigInteger(value.substring(2), 8).doubleValue();
        }
        if (!JAVASCRIPT_DECIMAL_NUMBER.matcher(value).matches()) return null;
        try {
            double parsed = Double.parseDouble(value);
            return Double.isNaN(parsed) ? null : parsed;
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static String stripJavaScriptWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJavaScriptWhitespace(value.charAt(start))) start++;
        while (end > start && isJavaScriptWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isJavaScriptTruthyString(String value) {
        return StringUtils.isNotEmpty(value);
    }

    private static boolean isJavaScriptWhitespace(char value) {
        return switch (value) {
            case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020,
                 0x00A0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F,
                 0x3000, 0xFEFF -> true;
            default -> value >= 0x2000 && value <= 0x200A;
        };
    }
}
