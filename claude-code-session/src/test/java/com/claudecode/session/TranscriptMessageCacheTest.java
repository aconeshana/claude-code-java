package com.claudecode.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.claudecode.core.message.Message;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscriptMessageCacheTest {

    @TempDir Path dir;

    private CountingStorage storage;
    private TranscriptMessageCache cache;

    /** Counts the lines handed to the parser, i.e. the work each read did. */
    private static final class CountingStorage extends SessionStorage {
        final AtomicLong parsedLines = new AtomicLong();

        @Override
        List<Message> parseMessageLines(List<String> lines, Path file, long firstLineNumber) {
            parsedLines.addAndGet(lines.size());
            return super.parseMessageLines(lines, file, firstLineNumber);
        }
    }

    @BeforeEach
    void setUp() {
        storage = new CountingStorage();
        cache = new TranscriptMessageCache(storage, Long.MAX_VALUE);
    }

    private static String user(String uuid) {
        return "{\"type\":\"user\",\"uuid\":\"" + uuid
            + "\",\"message\":{\"role\":\"user\",\"content\":\"" + uuid + "\"}}";
    }

    private static List<String> users(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(i -> user(prefix + i)).toList();
    }

    private static void append(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static List<String> uuids(List<Message> messages) {
        return messages.stream().map(Message::uuid).toList();
    }

    /** The cache must always agree with a whole-file read of the file as it is now. */
    private void assertMatchesWholeFileRead(Path file) {
        List<String> expected = uuids(new SessionStorage().readMessages(file));
        assertEquals(expected, uuids(cache.read(file)));
    }

    @Test
    void firstReadMatchesWholeFileRead() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, users("a", 5));

        assertMatchesWholeFileRead(file);
        assertEquals(5, storage.parsedLines.get());
    }

    @Test
    void unchangedFileIsServedWithoutParsing() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, users("a", 5));
        List<Message> first = cache.read(file);
        storage.parsedLines.set(0);

        List<Message> second = cache.read(file);

        assertEquals(0, storage.parsedLines.get());
        assertEquals(uuids(first), uuids(second));
    }

    @Test
    void appendedLinesAreTheOnlyOnesParsed() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, users("a", 50));
        cache.read(file);
        storage.parsedLines.set(0);

        append(file, String.join("\n", users("b", 3)) + "\n");

        assertMatchesWholeFileRead(file);
        assertEquals(3, storage.parsedLines.get());
    }

    @Test
    void unfinishedLastLineReadsLikeAWholeFileReadUntilItCompletes() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, users("a", 2));
        String next = user("late");
        append(file, next.substring(0, 20));

        assertMatchesWholeFileRead(file);
        assertEquals(List.of("a0", "a1"), uuids(cache.read(file)));

        append(file, next.substring(20) + "\n");

        assertMatchesWholeFileRead(file);
        assertEquals(List.of("a0", "a1", "late"), uuids(cache.read(file)));
    }

    @Test
    void completeLastLineWithoutNewlineIsIncluded() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.writeString(file, user("a") + "\n" + user("b"));

        assertMatchesWholeFileRead(file);
        assertEquals(List.of("a", "b"), uuids(cache.read(file)));
    }

    @Test
    void fileReplacedByAtomicMoveIsParsedAgain() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, users("a", 3));
        cache.read(file);

        Path replacement = dir.resolve("replacement.jsonl");
        Files.write(replacement, users("z", 4));
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);

        assertMatchesWholeFileRead(file);
    }

    @Test
    void fileRewrittenInPlaceIsParsedAgain() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, users("a", 3));
        cache.read(file);

        // Same inode, larger, but the already-parsed prefix changed.
        Files.write(file, users("b", 5), StandardOpenOption.TRUNCATE_EXISTING);

        assertMatchesWholeFileRead(file);
    }

    @Test
    void truncatedFileIsParsedAgain() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, users("a", 5));
        cache.read(file);

        Files.write(file, users("a", 2), StandardOpenOption.TRUNCATE_EXISTING);

        assertMatchesWholeFileRead(file);
    }

    @Test
    void missingFileReadsEmpty() {
        assertEquals(List.of(), cache.read(dir.resolve("absent.jsonl")));
    }

    @Test
    void deletedFileStopsBeingServed() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, users("a", 2));
        cache.read(file);
        Files.delete(file);

        assertEquals(List.of(), cache.read(file));
    }

    @Test
    void malformedUtf8FailsLikeAWholeFileRead() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, new byte[] {'{', (byte) 0xC3, '}', '\n'});

        assertThrows(UncheckedIOException.class, () -> new SessionStorage().readMessages(file));
        assertThrows(UncheckedIOException.class, () -> cache.read(file));
    }

    @Test
    void returnedListIsImmutable() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, users("a", 2));
        cache.read(file);
        append(file, user("b") + "\n");

        List<Message> messages = cache.read(file);

        assertThrows(UnsupportedOperationException.class, messages::clear);
    }

    @Test
    void leastRecentlyReadTranscriptIsEvictedBeyondTheBudget() throws Exception {
        Path first = dir.resolve("first.jsonl");
        Path second = dir.resolve("second.jsonl");
        Files.write(first, users("a", 10));
        Files.write(second, users("b", 10));
        cache = new TranscriptMessageCache(storage, Files.size(first));

        cache.read(first);
        cache.read(second);
        storage.parsedLines.set(0);
        cache.read(second);
        assertEquals(0, storage.parsedLines.get(), "the most recent read is kept");

        cache.read(first);
        assertEquals(10, storage.parsedLines.get(), "the older one was evicted");
    }

    @Test
    void concurrentReadsOfOneFileShareOneParse() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.write(file, users("a", 200));
        int readers = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Message>>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < readers; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return cache.read(file);
                }));
            }
            start.countDown();
            for (Future<List<Message>> result : results) {
                assertEquals(200, result.get().size());
            }
        }
        assertEquals(200, storage.parsedLines.get());
    }

    @Test
    void manyAppendsMatchWholeFileReadThroughout() throws Exception {
        Path file = dir.resolve("s.jsonl");
        for (int round = 0; round < 30; round++) {
            String chunk = String.join("\n", users("r" + round + "-", round % 4)) + "\n";
            append(file, round % 5 == 0 ? chunk + "\n" : chunk);
            assertMatchesWholeFileRead(file);
        }
        assertFalse(cache.read(file).isEmpty());
    }
}
