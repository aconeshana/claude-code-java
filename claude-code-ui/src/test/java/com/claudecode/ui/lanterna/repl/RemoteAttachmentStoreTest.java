package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.runtime.sessionhost.SessionHostSubmission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteAttachmentStoreTest {

    @TempDir Path tempDir;

    @Test
    void persistsUnderWorkspaceWithPrivateModesAndNoOverwrite() throws Exception {
        var attachment = new SessionHostSubmission.Attachment(
            "text/plain", "../../report.txt", new byte[] {1, 2, 3});

        Path stored = RemoteAttachmentStore.persist(tempDir.toString(),
            "../session", "../message", attachment);

        assertTrue(stored.startsWith(tempDir.toRealPath().resolve(".claude/remote-attachments")));
        assertEquals("__.._report.txt", stored.getFileName().toString());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(stored));
        assertEquals("rw-------", Files.getPosixFilePermissions(stored).stream()
            .collect(Collectors.collectingAndThen(
                Collectors.toSet(),
                PosixFilePermissions::toString)));
        assertThrows(IllegalArgumentException.class, () -> RemoteAttachmentStore.persist(
            tempDir.toString(), "../session", "../message", attachment));
    }

    @Test
    void rejectsSymbolicLinkInStoragePath() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = tempDir.resolve(".claude");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.abort("symbolic links unavailable: " + unavailable.getMessage());
        }

        var attachment = new SessionHostSubmission.Attachment(
            "text/plain", "report.txt", new byte[] {1});
        assertThrows(IllegalStateException.class, () -> RemoteAttachmentStore.persist(
            tempDir.toString(), "session", "message", attachment));
        try (var entries = Files.list(outside)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }
}
