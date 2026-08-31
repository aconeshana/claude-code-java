package com.claudecode.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiteSessionReaderTest {

    @Test
    void sparseTranscriptReadsAtMostHeadAndTailWindows(@TempDir Path tempDir) throws Exception {
        Path transcript = tempDir.resolve("large.jsonl");
        try (RandomAccessFile file = new RandomAccessFile(transcript.toFile(), "rw")) {
            file.write("{\"type\":\"user\",\"message\":{\"content\":\"head\"}}\n".getBytes());
            file.setLength(100L * 1024 * 1024);
            file.seek(file.length() - 64);
            file.write("{\"type\":\"custom-title\",\"customTitle\":\"tail\"}\n".getBytes());
        }

        byte[] buffer = new byte[LiteSessionReader.LITE_READ_BYTES];
        LiteSessionReader.LiteSessionFile result =
            new LiteSessionReader().read(transcript, Files.size(transcript), buffer).orElseThrow();

        assertTrue(Strings.CS.contains(result.head(), "head"));
        assertTrue(Strings.CS.contains(result.tail(), "tail"));
        assertTrue(result.bytesRead() <= 2L * LiteSessionReader.LITE_READ_BYTES);
    }

    @Test
    void smallTranscriptReusesHeadAsTailAndReadsOnce(@TempDir Path tempDir) throws Exception {
        Path transcript = tempDir.resolve("small.jsonl");
        Files.writeString(transcript, "{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}\n");

        LiteSessionReader.LiteSessionFile result = new LiteSessionReader().read(
            transcript, Files.size(transcript), new byte[LiteSessionReader.LITE_READ_BYTES])
            .orElseThrow();

        assertEquals(result.head(), result.tail());
        assertEquals(Files.size(transcript), result.bytesRead());
    }

    @Test
    void disappearingOrEmptyTranscriptIsTolerated(@TempDir Path tempDir) throws Exception {
        LiteSessionReader reader = new LiteSessionReader();
        assertTrue(reader.read(tempDir.resolve("missing.jsonl"), 10,
            new byte[LiteSessionReader.LITE_READ_BYTES]).isEmpty());

        Path empty = tempDir.resolve("empty.jsonl");
        Files.createFile(empty);
        assertTrue(reader.read(empty, 0, new byte[LiteSessionReader.LITE_READ_BYTES]).isEmpty());
    }
}
