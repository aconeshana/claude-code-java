package com.claudecode.tools.workflows;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Append-only resume journal for one workflow run.
 */
public final class WorkflowJournal {

    private static final Set<String> KEY_OPTIONS = Set.of(
        "schema", "model", "effort", "isolation", "agentType");
    private final Path path;

    public WorkflowJournal(Path transcriptDir) {
        this.path = transcriptDir.resolve("journal.jsonl");
    }

    String transcriptSubdir() {
        Path runDir = path.getParent();
        return runDir == null || runDir.getFileName() == null
            ? null : "workflows/" + runDir.getFileName();
    }

    public Snapshot load() {
        if (!Files.isRegularFile(path)) return Snapshot.empty();
        Map<String, ResultEntry> results = new LinkedHashMap<>();
        Map<String, List<StartedEntry>> started = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (StringUtils.isBlank(line)) continue;
                try {
                    JsonNode node = JsonUtils.parseTree(line);
                    String type = node.path("type").asText();
                    String key = node.path("key").asText();
                    String agentId = node.path("agentId").asText("");
                    if (Strings.CS.equals("result", type)) {
                        results.put(key, new ResultEntry(key, agentId,
                            node.path("result").asText()));
                    } else if (Strings.CS.equals("started", type)) {
                        started.computeIfAbsent(key, _ -> new ArrayList<>())
                            .add(new StartedEntry(key, agentId));
                    }
                } catch (RuntimeException _) {

                }
            }
        } catch (IOException e) {
            throw new WorkflowRuntimeException("Failed to load workflow journal: " + e.getMessage(), e);
        }
        Map<String, List<StartedEntry>> immutableStarted = new LinkedHashMap<>();
        started.forEach((key, value) -> immutableStarted.put(key, List.copyOf(value)));
        return new Snapshot(Map.copyOf(results), Map.copyOf(immutableStarted));
    }

    public synchronized void appendStarted(String key, String agentId) {
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "started");
        entry.put("key", key);
        entry.put("agentId", agentId == null ? "" : agentId);
        append(entry);
    }

    public synchronized void appendResult(String key, String agentId, String result) {
        if (result == null) return;
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "result");
        entry.put("key", key);
        entry.put("agentId", agentId == null ? "" : agentId);
        entry.put("result", result);
        append(entry);
    }

    public static String key(String previousKey, String prompt, JsonNode options) {
        String canonical = canonicalOptions(options);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((previousKey == null ? "" : previousKey).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((prompt == null ? "" : prompt).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            return "v2:" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new WorkflowRuntimeException("Failed to compute workflow journal key", e);
        }
    }

    private void append(ObjectNode entry) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, JsonUtils.toJson(entry) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new WorkflowRuntimeException("Failed to append workflow journal: " + e.getMessage(), e);
        }
    }

    private static String canonicalOptions(JsonNode options) {
        ObjectNode filtered = JsonUtils.getMapper().createObjectNode();
        if (options != null && options.isObject()) {
            for (String key : new TreeSet<>(KEY_OPTIONS)) {
                JsonNode value = options.get(key);
                if (value != null && !value.isNull()) filtered.set(key, sort(value));
            }
        }
        return JsonUtils.toJson(filtered);
    }

    private static JsonNode sort(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) return value;
        if (value.isArray()) {
            ArrayNode array = JsonUtils.getMapper().createArrayNode();
            value.forEach(item -> array.add(sort(item)));
            return array;
        }
        ObjectNode object = JsonUtils.getMapper().createObjectNode();
        TreeSet<String> names = new TreeSet<>();
        value.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (!Strings.CS.equals("__proto__", name)) object.set(name, sort(value.get(name)));
        }
        return object;
    }

    public record ResultEntry(String key, String agentId, String result) {}
    public record StartedEntry(String key, String agentId) {}
    public record Snapshot(Map<String, ResultEntry> results,
                           Map<String, List<StartedEntry>> started) {
        static Snapshot empty() { return new Snapshot(Map.of(), Map.of()); }
    }
}
