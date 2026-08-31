package com.claudecode.services.session;

import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.session.SessionStorage;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileHistorySnapshotCodecTest {

    @Test
    void toJson_fromJson_roundTripsExactly() {
        Map<String, FileHistoryManager.Backup> backups = new LinkedHashMap<>();
        backups.put("a.txt", new FileHistoryManager.Backup("hash1@v1", 1, Instant.parse("2026-07-15T10:00:00Z")));
        backups.put("new.txt", new FileHistoryManager.Backup(null, 1, Instant.parse("2026-07-15T10:00:01Z")));
        FileHistoryManager.Snapshot original = new FileHistoryManager.Snapshot(
            "msg-1", backups, Instant.parse("2026-07-15T10:00:02Z"));

        ObjectNode json = FileHistorySnapshotCodec.toJson(original);
        FileHistoryManager.Snapshot roundTripped = FileHistorySnapshotCodec.fromJson(json);

        assertEquals(original, roundTripped);
    }

    @Test
    void buildChain_appendsNewMessageIds_inOrder() {
        List<SessionStorage.FileHistorySnapshotEntry> raw = List.of(
            new SessionStorage.FileHistorySnapshotEntry("msg-1",
                FileHistorySnapshotCodec.toJson(snapshotFor("msg-1")), false),
            new SessionStorage.FileHistorySnapshotEntry("msg-2",
                FileHistorySnapshotCodec.toJson(snapshotFor("msg-2")), false)
        );

        List<FileHistoryManager.Snapshot> chain = FileHistorySnapshotCodec.buildChain(raw);

        assertEquals(2, chain.size());
        assertEquals("msg-1", chain.getFirst().messageId());
        assertEquals("msg-2", chain.get(1).messageId());
    }

    @Test
    void buildChain_isSnapshotUpdate_overwritesExistingEntry_notAppends() {
        FileHistoryManager.Snapshot initial = snapshotFor("msg-1");
        FileHistoryManager.Snapshot updated = new FileHistoryManager.Snapshot("msg-1",
            Map.of("a.txt", new FileHistoryManager.Backup("hash-updated@v1", 1, Instant.now())),
            initial.timestamp());

        List<SessionStorage.FileHistorySnapshotEntry> raw = List.of(
            new SessionStorage.FileHistorySnapshotEntry("msg-1", FileHistorySnapshotCodec.toJson(initial), false),
            new SessionStorage.FileHistorySnapshotEntry("msg-1", FileHistorySnapshotCodec.toJson(updated), true)
        );

        List<FileHistoryManager.Snapshot> chain = FileHistorySnapshotCodec.buildChain(raw);

        assertEquals(1, chain.size(), "the update must overwrite, not append, a second entry");
        assertEquals("hash-updated@v1", chain.getFirst().trackedFileBackups().get("a.txt").backupFileName());
    }

    private static FileHistoryManager.Snapshot snapshotFor(String messageId) {
        return new FileHistoryManager.Snapshot(messageId,
            Map.of("a.txt", new FileHistoryManager.Backup("hash@v1", 1, Instant.now())),
            Instant.now());
    }
}
