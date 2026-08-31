package com.claudecode.session;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.util.UuidUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/** Local transcript operations used by the public Agent SDK session facade. */
public final class SessionOperationsService {
    private static final Set<String> FORK_TYPES = Set.of(
        "user", "assistant", "attachment", "system", "progress");

    private final Path configHome;
    private final Clock clock;
    private final Supplier<String> uuidSupplier;

    public SessionOperationsService(Path configHome) {
        this(configHome, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    SessionOperationsService(Path configHome, Clock clock, Supplier<String> uuidSupplier) {
        this.configHome = configHome;
        this.clock = clock;
        this.uuidSupplier = uuidSupplier;
    }

    public Optional<SessionInfo> getSessionInfo(String sessionId, String dir) {
        if (!UuidUtils.isValid(sessionId)) return Optional.empty();
        Optional<ResolvedSession> resolved = resolve(sessionId, dir);
        if (resolved.isEmpty()) return Optional.empty();
        ResolvedSession target = resolved.get();
        try {
            BasicFileAttributes stat = Files.readAttributes(target.file(), BasicFileAttributes.class);
            SessionCatalog.Candidate candidate = new SessionCatalog.Candidate(sessionId, target.file(),
                target.projectPath(), stat.lastModifiedTime().toMillis(),
                stat.creationTime().toMillis(), stat.size(), false);
            return SessionCatalog.enrichSdk(candidate, _ -> false, true).map(
                SessionOperationsService::sdkInfo);
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    public Optional<SessionInfo> getSessionInfo(String sessionId, List<JsonNode> entries) {
        if (!UuidUtils.isValid(sessionId) || entries == null || entries.isEmpty()) return Optional.empty();
        String jsonl = toJsonl(entries);
        String head = jsonl.substring(0, Math.min(jsonl.length(), LiteSessionReader.LITE_READ_BYTES));
        String tail = jsonl.substring(Math.max(0, jsonl.length() - LiteSessionReader.LITE_READ_BYTES));
        String firstLine = firstLine(head);
        if (Strings.CS.contains(firstLine, "\"isSidechain\":true")
                || Strings.CS.contains(firstLine, "\"isSidechain\": true")) return Optional.empty();
        String custom = lastField(tail, "customTitle", lastField(head, "customTitle", null));
        String ai = lastField(tail, "aiTitle", lastField(head, "aiTitle", null));
        String lastPrompt = lastField(tail, "lastPrompt", null);
        String summaryField = lastTypedField(tail, "summary", "summary");
        String firstPrompt = firstPrompt(entries);
        String summary = firstNonBlank(custom, ai, lastPrompt, summaryField, firstPrompt);
        if (summary == null) return Optional.empty();
        String timestamp = firstField(head, "timestamp");
        Instant created = parseInstant(timestamp, clock.instant());
        String latestTimestamp = entries.stream().map(e -> text(e, "timestamp"))
            .filter(StringUtils::isNotBlank).reduce((_, right) -> right).orElse(null);
        long modified = parseInstant(latestTimestamp, clock.instant()).toEpochMilli();
        String cwd = firstNonBlank(lastTypedField(tail, "relocated", "relocatedCwd"),
            firstField(head, "cwd"));
        String tag = lastTypedField(tail, "tag", "tag");
        String git = firstNonBlank(lastField(tail, "gitBranch", null), firstField(head, "gitBranch"));
        return Optional.of(new SessionInfo(sessionId, modified, created, -1, summary, git, cwd,
            StringUtils.isBlank(tag) ? null : tag, jsonl.getBytes(StandardCharsets.UTF_8).length,
            firstNonBlank(custom, ai), firstPrompt));
    }

    public List<HistoryMessage> getSessionMessages(String sessionId, String dir, Integer limit,
                                                    Integer offset, boolean includeSystem) {
        if (!UuidUtils.isValid(sessionId)) return List.of();
        return resolve(sessionId, dir).map(r -> historyFromFile(r.file(), limit, offset,
            includeSystem)).orElseGet(List::of);
    }

    public List<HistoryMessage> getSessionMessages(String sessionId, List<JsonNode> entries,
                                                    Integer limit, Integer offset,
                                                    boolean includeSystem) {
        if (!UuidUtils.isValid(sessionId) || entries == null || entries.isEmpty()) return List.of();
        return historyFromEntries(entries, limit, offset, includeSystem);
    }

    public void renameSession(String sessionId, String title, String dir) {
        requireUuid(sessionId);
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("title must be non-empty");
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "custom-title").put("customTitle", normalized).put("sessionId", sessionId);
        appendExisting(sessionId, dir, entry);
    }

    public void tagSession(String sessionId, String tag, String dir) {
        requireUuid(sessionId);
        String normalized = tag;
        if (normalized != null) {
            normalized = normalized.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("tag must be non-empty (use null to clear)");
            }
        }
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "tag").put("tag", normalized == null ? "" : normalized)
            .put("sessionId", sessionId);
        appendExisting(sessionId, dir, entry);
    }

    public ForkedSession forkSession(String sessionId, String dir, String upToMessageId,
                                     String title) {
        requireUuid(sessionId);
        if (StringUtils.isNotBlank(upToMessageId) && !UuidUtils.isValid(upToMessageId)) {
            throw new IllegalArgumentException("Invalid upToMessageId: " + upToMessageId);
        }
        ResolvedSession source = resolve(sessionId, dir).orElseThrow(() ->
            new SessionOperationException("Session " + sessionId + " not found"));
        List<JsonNode> entries = parseJsonl(source.file());
        ForkTransform transformed = forkEntries(sessionId, entries, upToMessageId, title);
        Path target = source.file().resolveSibling(transformed.sessionId() + ".jsonl");
        try {
            Files.writeString(target, toJsonl(transformed.entries()), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return new ForkedSession(transformed.sessionId(), transformed.entries());
    }

    public ForkedSession forkSession(String sessionId, List<JsonNode> entries,
                                     String upToMessageId, String title) {
        requireUuid(sessionId);
        if (StringUtils.isNotBlank(upToMessageId) && !UuidUtils.isValid(upToMessageId)) {
            throw new IllegalArgumentException("Invalid upToMessageId: " + upToMessageId);
        }
        ForkTransform result = forkEntries(sessionId, entries, upToMessageId, title);
        return new ForkedSession(result.sessionId(), result.entries());
    }

    private List<HistoryMessage> historyFromFile(Path file, Integer limit, Integer offset,
                                                 boolean includeSystem) {
        try {
            TranscriptLoader.TranscriptFile loaded = new TranscriptLoader().loadTranscriptFile(file);
            if (loaded.leafUuids().isEmpty()) return List.of();
            String leaf = loaded.leafUuids().iterator().next();
            return page(projectHistory(TranscriptLoader.buildConversationChain(loaded, leaf),
                includeSystem), limit, offset);
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    private List<HistoryMessage> historyFromEntries(List<JsonNode> entries, Integer limit,
                                                    Integer offset, boolean includeSystem) {
        TranscriptLoader.TranscriptFile loaded = new TranscriptLoader().loadTranscriptEntries(entries);
        if (loaded.leafUuids().isEmpty()) return List.of();
        String leaf = loaded.leafUuids().iterator().next();
        return page(projectHistory(TranscriptLoader.buildConversationChain(loaded, leaf),
            includeSystem), limit, offset);
    }

    private static List<HistoryMessage> projectHistory(List<JsonNode> chain, boolean includeSystem) {
        List<HistoryMessage> result = new ArrayList<>();
        for (JsonNode entry : chain) {
            String type = text(entry, "type");
            if (!Strings.CS.equalsAny(type, "user", "assistant")
                    && !(includeSystem && Strings.CS.equals("system", type))) continue;
            if (entry.path("isMeta").asBoolean(false) || entry.path("isSidechain").asBoolean(false)
                    || entry.hasNonNull("teamName")) continue;
            result.add(new HistoryMessage(type, text(entry, "uuid"), text(entry, "sessionId"),
                entry.get("message"), null, text(entry, "timestamp")));
        }
        return result;
    }

    private static List<HistoryMessage> page(List<HistoryMessage> messages, Integer limit,
                                             Integer offset) {
        int size = messages.size();
        int start = offset == null ? 0 : offset >= 0 ? Math.min(offset, size)
            : Math.max(0, size + offset);
        int end = limit != null && limit > 0 ? Math.min(size, start + limit) : size;
        return List.copyOf(messages.subList(start, end));
    }

    private ForkTransform forkEntries(String sourceId, List<JsonNode> raw, String upTo, String title) {
        List<ObjectNode> transcript = new ArrayList<>();
        ArrayNode replacements = JsonUtils.getMapper().createArrayNode();
        String relocated = null;
        for (JsonNode value : raw == null ? List.<JsonNode>of() : raw) {
            if (!(value instanceof ObjectNode node)) continue;
            String type = text(node, "type");
            if (FORK_TYPES.contains(type) && node.hasNonNull("uuid")) transcript.add(node.deepCopy());
            else if (Strings.CS.equals("content-replacement", type)
                    && Strings.CS.equals(sourceId, text(node, "sessionId"))
                    && node.path("replacements").isArray()) {
                node.path("replacements").forEach(item -> replacements.add(item.deepCopy()));
            } else if (Strings.CS.equals("relocated", type)
                    && Strings.CS.equals(sourceId, text(node, "sessionId"))) {
                relocated = text(node, "relocatedCwd");
            }
        }
        transcript.removeIf(node -> node.path("isSidechain").asBoolean(false));
        if (transcript.isEmpty()) throw new SessionOperationException(
            "Session " + sourceId + " has no messages to fork");
        if (StringUtils.isNotBlank(upTo)) {
            int index = -1;
            for (int i = 0; i < transcript.size(); i++) {
                if (Strings.CS.equals(upTo, text(transcript.get(i), "uuid"))) { index = i; break; }
            }
            if (index < 0) throw new SessionOperationException(
                "Message " + upTo + " not found in session " + sourceId);
            transcript = new ArrayList<>(transcript.subList(0, index + 1));
        }
        Map<String, String> ids = new HashMap<>();
        for (ObjectNode node : transcript) ids.put(text(node, "uuid"), uuidSupplier.get());
        Map<String, ObjectNode> sourceById = new HashMap<>();
        transcript.forEach(node -> sourceById.put(text(node, "uuid"), node));
        List<ObjectNode> kept = transcript.stream()
            .filter(node -> !Strings.CS.equals("progress", text(node, "type"))).toList();
        if (kept.isEmpty()) throw new SessionOperationException(
            "Session " + sourceId + " has no messages to fork");
        String forkId = uuidSupplier.get();
        String now = clock.instant().toString();
        List<JsonNode> output = new ArrayList<>();
        for (int index = 0; index < kept.size(); index++) {
            ObjectNode source = kept.get(index);
            String originalId = text(source, "uuid");
            ObjectNode copy = source.deepCopy();
            copy.put("uuid", ids.get(originalId)).put("sessionId", forkId).put("isSidechain", false);
            String parent = text(source, "parentUuid");
            while (parent != null) {
                ObjectNode parentNode = sourceById.get(parent);
                if (parentNode == null || !Strings.CS.equals("progress", text(parentNode, "type"))) break;
                parent = text(parentNode, "parentUuid");
            }
            if (parent == null) copy.putNull("parentUuid"); else copy.put("parentUuid", ids.get(parent));
            String logical = text(source, "logicalParentUuid");
            if (source.has("logicalParentUuid")) {
                if (logical == null || !ids.containsKey(logical)) copy.putNull("logicalParentUuid");
                else copy.put("logicalParentUuid", ids.get(logical));
            }
            if (Strings.CS.equals("system", text(source, "type"))
                    && Strings.CS.equals("model_refusal_fallback", text(source, "subtype"))) {
                copy.put("neutralizedByFork", true);
            }
            for (String field : List.of("teamName", "agentName", "sessionKind", "slug",
                    "sourceToolAssistantUUID")) copy.remove(field);
            ObjectNode from = JsonUtils.getMapper().createObjectNode();
            from.put("sessionId", sourceId).put("messageUuid", originalId);
            copy.set("forkedFrom", from);
            if (index == kept.size() - 1) copy.put("timestamp", now);
            output.add(copy);
        }
        if (!replacements.isEmpty()) {
            ObjectNode entry = JsonUtils.getMapper().createObjectNode();
            entry.put("type", "content-replacement").put("sessionId", forkId)
                .put("uuid", uuidSupplier.get()).put("timestamp", now);
            entry.set("replacements", replacements);
            output.add(entry);
        }
        if (relocated != null) {
            ObjectNode entry = JsonUtils.getMapper().createObjectNode();
            entry.put("type", "relocated").put("sessionId", forkId).put("relocatedCwd", relocated);
            output.add(entry);
        }
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) normalized = sourceTitle(raw) + " (fork)";
        ObjectNode titleEntry = JsonUtils.getMapper().createObjectNode();
        titleEntry.put("type", "custom-title").put("sessionId", forkId)
            .put("customTitle", normalized).put("uuid", uuidSupplier.get()).put("timestamp", now);
        output.add(titleEntry);
        return new ForkTransform(forkId, List.copyOf(output));
    }

    private String sourceTitle(List<JsonNode> raw) {
        String custom = null;
        String ai = null;
        for (JsonNode node : raw) {
            if (node.hasNonNull("customTitle")) custom = node.path("customTitle").asText();
            if (node.hasNonNull("aiTitle")) ai = node.path("aiTitle").asText();
        }
        return firstNonBlank(custom, ai, firstPrompt(raw), "Forked session");
    }

    private void appendExisting(String sessionId, String dir, ObjectNode entry) {
        ResolvedSession target = resolve(sessionId, dir).orElseThrow(() ->
            new SessionOperationException(dir == null
                ? "Session " + sessionId + " not found in any project directory"
                : "Session " + sessionId + " not found in project directory for " + dir));
        byte[] bytes = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(target.file(), StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            if (channel.size() <= 0) throw new SessionOperationException("Session " + sessionId + " not found");
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
// write advances buffer.position; the loop condition is the
                // authoritative partial-write check.
                //noinspection ResultOfMethodCallIgnored
                channel.write(buffer);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private Optional<ResolvedSession> resolve(String sessionId, String dir) {
        String filename = sessionId + ".jsonl";
        if (StringUtils.isNotBlank(dir)) {
            List<String> paths = new ArrayList<>();
            paths.add(dir);
            try { paths.addAll(SessionSearch.detectWorktreePaths(dir)); } catch (RuntimeException _) { }
            for (String path : paths) {
                SessionManager manager = new SessionManager(configHome, path);
                for (Path project : manager.compatibleProjectDirectories()) {
                    Path file = project.resolve(filename);
                    if (nonEmpty(file)) return Optional.of(new ResolvedSession(file, manager.projectPath()));
                }
            }
            return Optional.empty();
        }
        Path root = configHome.resolve("projects");
        if (!Files.isDirectory(root)) return Optional.empty();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path project : dirs) {
                Path file = project.resolve(filename);
                if (nonEmpty(file)) return Optional.of(new ResolvedSession(file, null));
            }
        } catch (IOException _) { }
        return Optional.empty();
    }

    private static boolean nonEmpty(Path file) {
        try { return Files.isRegularFile(file) && Files.size(file) > 0; }
        catch (IOException _) { return false; }
    }

    private static List<JsonNode> parseJsonl(Path file) {
        List<JsonNode> result = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try { result.add(JsonUtils.getMapper().readTree(line)); } catch (Exception _) { }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return result;
    }

    private static SessionInfo sdkInfo(SessionCatalog.Entry entry) {
        SessionInfo info = entry.info();
        String title = firstNonBlank(info.customTitle(), entry.aiTitle());
        return new SessionInfo(info.id(), info.lastModified(), info.createdAt(), info.messageCount(),
            info.summary(), info.gitBranch(), info.cwd(), info.tag(), info.fileSize(), title,
            info.firstPrompt());
    }

    private static String firstPrompt(List<JsonNode> entries) {
        for (JsonNode entry : entries) {
            if (!Strings.CS.equals("user", text(entry, "type"))
                    || entry.path("isMeta").asBoolean(false)) continue;
            JsonNode content = entry.path("message").path("content");
            if (content.isTextual() && StringUtils.isNotBlank(content.asText())) return content.asText().trim();
            if (content.isArray()) for (JsonNode block : content) {
                if (Strings.CS.equals("text", text(block, "type"))
                        && StringUtils.isNotBlank(text(block, "text"))) return text(block, "text").trim();
            }
        }
        return null;
    }

    private static String toJsonl(List<JsonNode> entries) {
        StringBuilder result = new StringBuilder();
        for (JsonNode entry : entries) result.append(entry).append('\n');
        return result.toString();
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private static String firstField(String jsonl, String field) {
        for (String line : jsonl.split("\n")) {
            try {
                JsonNode node = JsonUtils.getMapper().readTree(line);
                String value = text(node, field);
                if (value != null) return value;
            } catch (Exception _) { }
        }
        return null;
    }

    private static String lastField(String jsonl, String field, String fallback) {
        String result = fallback;
        for (String line : jsonl.split("\n")) {
            try {
                String value = text(JsonUtils.getMapper().readTree(line), field);
                if (value != null) result = value;
            } catch (Exception _) { }
        }
        return result;
    }

    private static String lastTypedField(String jsonl, String type, String field) {
        String result = null;
        for (String line : jsonl.split("\n")) {
            try {
                JsonNode node = JsonUtils.getMapper().readTree(line);
                if (Strings.CS.equals(type, text(node, "type")) && node.has(field)) {
                    result = text(node, field);
                }
            } catch (Exception _) { }
        }
        return result;
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value != null) try { return Instant.parse(value); } catch (RuntimeException _) { }
        return fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.isNotBlank(value)) return value;
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private static void requireUuid(String value) {
        if (!UuidUtils.isValid(value)) throw new IllegalArgumentException("Invalid sessionId: " + value);
    }

    public record HistoryMessage(String type, String uuid, String sessionId, JsonNode message,
                                 String parentToolUseId, String timestamp) {}
    public record ForkedSession(String sessionId, List<JsonNode> entries) {}
    private record ForkTransform(String sessionId, List<JsonNode> entries) {}
    private record ResolvedSession(Path file, String projectPath) {}

    public static final class SessionOperationException extends RuntimeException {
        public SessionOperationException(String message) { super(message); }
    }
}
