package com.claudecode.session.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class StatsStreamingMemoryTest {

    private static final int FILE_COUNT = 3;
    private static final int FILE_MIB = 24;
    private static final int LINE_BYTES = 1024;

    @TempDir Path tempDir;

    @Test
    void severalLargeTranscriptsFitInSmallHeap() throws Exception {
        Path projectDir = tempDir.resolve("projects").resolve("-Users-x-large");
        Files.createDirectories(projectDir);
        for (int i = 0; i < FILE_COUNT; i++) {
            writeLargeTranscript(projectDir.resolve("session-" + i + ".jsonl"));
        }

        String testClasspath = System.getProperty(
            "surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m",
            "-cp", testClasspath,
            StatsProbe.class.getName(),
            tempDir.resolve("projects").toString())
            .redirectErrorStream(true)
            .start();

        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(finished, "stats scan did not finish within 45 seconds; output:\n" + output);
        assertEquals(0, process.exitValue(),
            "stats must not retain three 24 MiB transcripts in a 64 MiB heap; output:\n" + output);
    }

    @Test
    void singleHugeIrrelevantJsonValueDoesNotAllocateTheWholeLine() throws Exception {
        Path transcript = tempDir.resolve("huge-line.jsonl");
        writeHugeProgressLine(transcript, 40);

        String testClasspath = System.getProperty(
            "surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx48m",
            "-cp", testClasspath,
            HugeLineProbe.class.getName(),
            transcript.toString())
            .redirectErrorStream(true)
            .start();

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(finished, "huge-line scan did not finish within 30 seconds; output:\n" + output);
        assertEquals(0, process.exitValue(),
            "an irrelevant 40 MiB JSON value must be skipped without a 40+ MiB line buffer; output:\n"
                + output);
    }

    private static void writeLargeTranscript(Path file) throws IOException {
        byte[] prefix = ("{\"type\":\"user\",\"timestamp\":\"2026-08-12T00:00:00Z\","
            + "\"isSidechain\":false,\"padding\":\"").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] line = new byte[LINE_BYTES];
        System.arraycopy(prefix, 0, line, 0, prefix.length);
        Arrays.fill(line, prefix.length, line.length - suffix.length, (byte) 'x');
        System.arraycopy(suffix, 0, line, line.length - suffix.length, suffix.length);

        byte[] block = new byte[1024 * 1024];
        for (int offset = 0; offset < block.length; offset += line.length) {
            System.arraycopy(line, 0, block, offset, line.length);
        }
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(block);
            for (int i = 0; i < FILE_MIB; i++) {
                buffer.rewind();
                while (buffer.hasRemaining()) channel.write(buffer);
            }
        }
    }

    private static void writeHugeProgressLine(Path file, int payloadMiB) throws IOException {
        byte[] block = new byte[1024 * 1024];
        Arrays.fill(block, (byte) 'x');
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(
                "{\"type\":\"progress\",\"payload\":\"".getBytes(StandardCharsets.UTF_8)));
            ByteBuffer payload = ByteBuffer.wrap(block);
            for (int i = 0; i < payloadMiB; i++) {
                payload.rewind();
                while (payload.hasRemaining()) channel.write(payload);
            }
            channel.write(ByteBuffer.wrap(("""
                "}
                {"type":"user","timestamp":"2026-08-12T00:00:00Z",\
                "isSidechain":false}
                """).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Runs the production aggregation path inside the deliberately small heap. */
    public static final class StatsProbe {
        private StatsProbe() {}

        public static void main(String[] args) {
            Path projectsDir = Path.of(args[0]);
            StatsAggregator aggregator = new StatsAggregator(
                new SessionFileEnumerator(projectsDir),
                new StatsCacheStore(projectsDir.resolve("stats-cache.json")),
                ZoneOffset.UTC);
            StatsAggregator.ProcessedStats stats = aggregator.processSessionFiles(
                new SessionFileEnumerator(projectsDir).listAllSessionFiles(), null, null);
            long expected = (long) FILE_COUNT * FILE_MIB * 1024 * 1024 / LINE_BYTES;
            if (stats.totalMessages() != expected) {
                throw new AssertionError(
                    "expected " + expected + " messages, got " + stats.totalMessages());
            }
        }
    }

    /** Proves that a single enormous JSONL row is skipped token-by-token. */
    public static final class HugeLineProbe {
        private HugeLineProbe() {}

        public static void main(String[] args) throws IOException {
            TranscriptStatsScanner.ScanResult result =
                new TranscriptStatsScanner().scan(Path.of(args[0]), false);
            if (result.mainCount() != 1) {
                throw new AssertionError("expected one user message, got " + result.mainCount());
            }
        }
    }
}
