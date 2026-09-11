package com.claudecode.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the record-append invariant: one call to {@link TranscriptAppender} produces exactly one
 * line, even when the record exceeds the JDK's 8 KiB staging buffer and other threads append
 * concurrently. The regression this pins down destroyed a real transcript, where an 8804-byte
 * record was split at offset 8192 and a 613-byte record from another thread landed in the gap.
 */
class TranscriptAppenderTest {

    private static final int STAGING_BUFFER = 8192;

    @TempDir
    Path tempDir;

    @Test
    void concurrentAppendsOfOversizedRecordsNeverSplice() throws Exception {
        Path transcript = tempDir.resolve("session.jsonl");
        int writers = 16;
        int recordsPerWriter = 12;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int writer = 0; writer < writers; writer++) {
            int id = writer;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int index = 0; index < recordsPerWriter; index++) {
                        // Alternate above and below the staging buffer so the multi-syscall path and
                        // the single-syscall path race against each other, as they did in production.
                        int payload = index % 2 == 0 ? STAGING_BUFFER + 612 : 613;
                        TranscriptAppender.append(transcript, record(id, index, payload));
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "writers did not finish");
        if (failure.get() != null) throw new AssertionError("writer failed", failure.get());

        List<String> lines = Files.readAllLines(transcript, StandardCharsets.UTF_8);
        assertEquals(writers * recordsPerWriter, lines.size(), "record count");
        for (String line : lines) {
            assertTrue(line.startsWith("{\"id\":"), () -> "spliced line: " + preview(line));
            assertTrue(line.endsWith("\"}"), () -> "truncated line: " + preview(line));
            assertEquals(1, countOccurrences(line, "\"id\":"), () -> "spliced line: " + preview(line));
        }
    }

    @Test
    void appendCreatesTheTranscriptWhenAbsent() throws IOException {
        Path transcript = tempDir.resolve("fresh.jsonl");
        TranscriptAppender.append(transcript, "{\"first\":true}\n");
        assertEquals(List.of("{\"first\":true}"), Files.readAllLines(transcript));
    }

    @Test
    void appendToExistingRejectsMissingAndEmptyTranscripts() throws IOException {
        Path missing = tempDir.resolve("missing.jsonl");
        assertFalse(TranscriptAppender.appendToExisting(missing, "{}\n"));
        assertFalse(Files.exists(missing), "probe must not create the file");

        Path empty = Files.createFile(tempDir.resolve("empty.jsonl"));
        assertFalse(TranscriptAppender.appendToExisting(empty, "{}\n"));
        assertEquals(0, Files.size(empty));
    }

    @Test
    void appendToExistingAppendsToANonEmptyTranscript() throws IOException {
        Path transcript = tempDir.resolve("existing.jsonl");
        Files.writeString(transcript, "{\"seed\":true}\n", StandardCharsets.UTF_8);
        assertTrue(TranscriptAppender.appendToExisting(transcript, "{\"added\":true}\n"));
        assertEquals(List.of("{\"seed\":true}", "{\"added\":true}"), Files.readAllLines(transcript));
    }

    private static String record(int writer, int index, int payloadBytes) {
        String filler = "x".repeat(payloadBytes);
        return "{\"id\":\"" + writer + "-" + index + "\",\"payload\":\"" + filler + "\"}\n";
    }

    private static int countOccurrences(String line, String needle) {
        return (int) IntStream.iterate(line.indexOf(needle), at -> at >= 0,
            at -> line.indexOf(needle, at + needle.length())).count();
    }

    private static String preview(String line) {
        return line.length() <= 120 ? line : line.substring(0, 60) + "…" + line.substring(line.length() - 60);
    }
}
