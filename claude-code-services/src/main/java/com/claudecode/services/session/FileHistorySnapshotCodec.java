package com.claudecode.services.session;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON &lt;-&gt; {@link FileHistoryManager.Snapshot} bridge for the
 * {@code file-history-snapshot} JSONL entry — kept in {@code claude-code-services}
 * (not {@code claude-code-session}) because it needs the concrete
 * {@code FileHistoryManager.Snapshot} type, and {@code claude-code-session}
 * only depends on {@code claude-code-core}'s message types, not its engine
 * package.
 *
 * <ul>
 *   <li>{@code recordFileHistorySnapshot}'s
 *       JSON shape (write side); {@code buildFileHistorySnapshotChain} → {@link #buildChain}.</li>
 * </ul>
 */
public final class FileHistorySnapshotCodec {

    private FileHistorySnapshotCodec() {}

    public static ObjectNode toJson(FileHistoryManager.Snapshot snapshot) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("messageId", snapshot.messageId());
        node.put("timestamp", snapshot.timestamp().toString());
        ObjectNode backups = JsonUtils.getMapper().createObjectNode();
        for (Map.Entry<String, FileHistoryManager.Backup> e : snapshot.trackedFileBackups().entrySet()) {
            FileHistoryManager.Backup backup = e.getValue();
            ObjectNode b = JsonUtils.getMapper().createObjectNode();
            if (backup.backupFileName() != null) {
                b.put("backupFileName", backup.backupFileName());
            } else {
                b.putNull("backupFileName");
            }
            b.put("version", backup.version());
            b.put("backupTime", backup.backupTime().toString());
            backups.set(e.getKey(), b);
        }
        node.set("trackedFileBackups", backups);
        return node;
    }

    public static FileHistoryManager.Snapshot fromJson(JsonNode node) {
        String messageId = node.hasNonNull("messageId") ? node.get("messageId").asText() : null;
        Instant timestamp = parseInstant(node.path("timestamp").asText(null));
        Map<String, FileHistoryManager.Backup> backups = new LinkedHashMap<>();
        JsonNode backupsNode = node.get("trackedFileBackups");
        if (backupsNode != null && backupsNode.isObject()) {
            backupsNode.fields().forEachRemaining(entry -> {
                JsonNode b = entry.getValue();
                String backupFileName = b.hasNonNull("backupFileName") ? b.get("backupFileName").asText() : null;
                int version = b.path("version").asInt(1);
                Instant backupTime = parseInstant(b.path("backupTime").asText(null));
                backups.put(entry.getKey(), new FileHistoryManager.Backup(backupFileName, version, backupTime));
            });
        }
        return new FileHistoryManager.Snapshot(messageId, backups, timestamp);
    }

    private static Instant parseInstant(String s) {
        if (StringUtils.isBlank(s)) return Instant.EPOCH;
        try {
            return Instant.parse(s);
        } catch (Exception _) {
            return Instant.EPOCH;
        }
    }

    /**
     * Rebuilds the snapshot chain from JSONL entries, already in file (chronological) order.
     */
    public static List<FileHistoryManager.Snapshot> buildChain(List<SessionStorage.FileHistorySnapshotEntry> raw) {
        List<FileHistoryManager.Snapshot> chain = new ArrayList<>();
        Map<String, Integer> indexByMessageId = new LinkedHashMap<>();
        for (SessionStorage.FileHistorySnapshotEntry entry : raw) {
            FileHistoryManager.Snapshot snapshot = fromJson(entry.snapshotJson());
            Integer existingIdx = indexByMessageId.get(entry.messageId());
            if (entry.isSnapshotUpdate() && existingIdx != null) {
                chain.set(existingIdx, snapshot);
            } else {
                indexByMessageId.put(entry.messageId(), chain.size());
                chain.add(snapshot);
            }
        }
        return chain;
    }
}
