package com.claudecode.session;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Raw-JSONL conversation fork service.
 */
public final class SessionForkService {

    private static final Set<String> TRANSCRIPT_TYPES = Set.of(
        "user", "assistant", "attachment", "system");

    public ForkResult fork(Path source, Path target,
                           String sourceSessionId, String forkSessionId) throws IOException {
        if (source == null || !Files.isRegularFile(source) || Files.size(source) == 0) {
            throw new NoConversationToForkException();
        }

        List<ObjectNode> messages = new ArrayList<>();
        List<ObjectNode> metricEntries = new ArrayList<>();
        ArrayNode replacements = JsonUtils.getMapper().createArrayNode();
        for (String rawLine : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            JsonNode parsed;
            try {
                parsed = JsonUtils.getMapper().readTree(line);
            } catch (Exception _) {
                continue;
            }
            if (!(parsed instanceof ObjectNode entry)) continue;
            String type = entry.path("type").asText("");
            if (TRANSCRIPT_TYPES.contains(type) && !entry.path("isSidechain").asBoolean(false)) {
                messages.add(entry.deepCopy());
                continue;
            }
            if (Strings.CS.equals("content-replacement", type)
                    && sourceSessionId.equals(entry.path("sessionId").asText())
                    && entry.path("replacements").isArray()) {
                entry.path("replacements").forEach(item -> replacements.add(item.deepCopy()));
                continue;
            }
            if (Strings.CS.equals("java-session-metrics", type)
                    && sourceSessionId.equals(entry.path("sessionId").asText())) {
                metricEntries.add(entry.deepCopy());
            }
        }
        Path sourceSidecar = SessionMetricsFiles.sidecar(source);
        boolean metricsInSidecar = Files.isRegularFile(sourceSidecar)
            || SessionMetricsFiles.useSidecar();
        if (Files.isRegularFile(sourceSidecar)) {
            metricEntries.clear();
            for (String rawLine : Files.readAllLines(sourceSidecar, StandardCharsets.UTF_8)) {
                try {
                    JsonNode parsed = JsonUtils.getMapper().readTree(rawLine);
                    if (parsed instanceof ObjectNode entry
                            && Strings.CS.equals("java-session-metrics",
                                entry.path("type").asText())
                            && sourceSessionId.equals(entry.path("sessionId").asText())) {
                        metricEntries.add(entry.deepCopy());
                    }
                } catch (Exception _) {
                    // Match JSONL malformed-row tolerance.
                }
            }
        }

        if (messages.isEmpty()) throw new NoMessagesToForkException();

        List<String> outputLines = new ArrayList<>(messages.size() + 1);
        Set<String> retainedMessageIds = new HashSet<>();
        String parentUuid = null;
        for (ObjectNode entry : messages) {
            entry.put("sessionId", forkSessionId);
            entry.remove("slug");
            if (parentUuid == null) entry.putNull("parentUuid");
            else entry.put("parentUuid", parentUuid);
            entry.put("isSidechain", false);

            ObjectNode forkedFrom = JsonUtils.getMapper().createObjectNode();
            forkedFrom.put("sessionId", sourceSessionId);
            JsonNode uuid = entry.get("uuid");
            if (uuid != null && !uuid.isNull()) forkedFrom.set("messageUuid", uuid.deepCopy());
            entry.set("forkedFrom", forkedFrom);
            outputLines.add(JsonUtils.getMapper().writeValueAsString(entry));

            if (uuid != null && uuid.isTextual()) parentUuid = uuid.asText();
            if (uuid != null && uuid.isTextual()) retainedMessageIds.add(uuid.asText());
        }

        if (!replacements.isEmpty()) {
            ObjectNode replacementEntry = JsonUtils.getMapper().createObjectNode();
            replacementEntry.put("type", "content-replacement");
            replacementEntry.put("sessionId", forkSessionId);
            replacementEntry.set("replacements", replacements);
            outputLines.add(JsonUtils.getMapper().writeValueAsString(replacementEntry));
        }

        long metricSeq = 0;
        List<String> metricLines = new ArrayList<>();
        boolean hasStart = metricEntries.stream().anyMatch(entry ->
            Strings.CS.equals("session/start", entry.path("event").asText()));
        if (hasStart) {
            for (ObjectNode entry : metricEntries) {
                String event = entry.path("event").asText();
                String turnId = entry.path("turnId").asText(null);
                if (!Strings.CS.equals("session/start", event)
                        && (turnId == null || !retainedMessageIds.contains(turnId))) continue;
                entry.put("sessionId", forkSessionId);
                entry.put("seq", metricSeq++);
                metricLines.add(JsonUtils.getMapper().writeValueAsString(entry));
            }
        }

        if (!metricsInSidecar) outputLines.addAll(metricLines);

        writeAtomically(target, String.join("\n", outputLines) + "\n");
        if (metricsInSidecar && !metricLines.isEmpty()) {
            writeAtomically(SessionMetricsFiles.sidecar(target),
                String.join("\n", metricLines) + "\n");
        }
        return new ForkResult(messages.size(), replacements.size());
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Fork transcript has no parent directory: " + target);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".branch-", ".jsonl.tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(temporary, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException _) {
                // Windows/non-POSIX filesystems do not expose Unix modes.
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record ForkResult(int messageCount, int contentReplacementCount) {}

    public static final class NoConversationToForkException extends IOException {
        public NoConversationToForkException() {
            super("No conversation to branch");
        }
    }

    public static final class NoMessagesToForkException extends IOException {
        public NoMessagesToForkException() {
            super("No messages to branch");
        }
    }
}
