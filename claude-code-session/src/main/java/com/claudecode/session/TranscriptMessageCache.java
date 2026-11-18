package com.claudecode.session;

import com.claudecode.core.message.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * {@link SessionStorage#readMessages} for a reader that asks for the same
 * transcripts over and over: the webui gateway answering messages, context,
 * and changed-files requests about sessions another process is writing.
 *
 * <p>Every one of those requests used to parse the whole file. A long session
 * grows to tens of megabytes, and one page load fires dozens of requests at
 * the same transcript, each paying a second-long parse and all of them
 * competing for the same cores. Here a file is parsed once, and afterwards
 * only by its new tail: transcripts are append-only, so the rows already
 * parsed stay valid as long as the bytes they came from are still there.
 *
 * <p>The answer is always the one a whole-file read would give now:
 * <ul>
 *   <li>an unchanged file (same identity, size, and modification time) is
 *       served from memory;</li>
 *   <li>a grown file whose already-parsed prefix ends with the bytes it ended
 *       with before is parsed only from that prefix on;</li>
 *   <li>anything else — a file replaced by an atomic move, truncated, or
 *       rewritten in place — is parsed again from the start.</li>
 * </ul>
 * Only complete lines are kept as parsed prefix. A last line still being
 * written is parsed on every read, exactly as a whole-file read would parse
 * (and, while incomplete, skip) it, and is never mistaken for settled bytes.
 *
 * <p>Concurrent reads of one file share a single parse; reads of different
 * files do not wait for each other. Retained transcripts are bounded by the
 * total size of the files they came from, the least recently read evicted
 * first, so the reader never holds more than a few long sessions.
 */
public final class TranscriptMessageCache {

    private final SessionStorage storage;
    private final long byteBudget;
    private final ConcurrentMap<Path, Slot> slots = new ConcurrentHashMap<>();

    /**
     * @param storage the parser whose line semantics every read reproduces
     * @param byteBudget the total size of retained transcript files; the most
     *     recently read one is kept even when it alone exceeds this
     */
    public TranscriptMessageCache(SessionStorage storage, long byteBudget) {
        this.storage = Objects.requireNonNull(storage, "storage");
        if (byteBudget < 0) throw new IllegalArgumentException("byteBudget < 0");
        this.byteBudget = byteBudget;
    }

    /**
     * The messages of the transcript at {@code sessionFile}, as
     * {@link SessionStorage#readMessages} would return them now.
     *
     * @return an immutable list; empty when the file does not exist
     * @throws UncheckedIOException when the file cannot be read or decoded
     */
    public List<Message> read(Path sessionFile) {
        Path key = sessionFile.toAbsolutePath().normalize();
        Slot slot = slots.computeIfAbsent(key, _ -> new Slot());
        List<Message> messages;
        slot.lock.lock();
        try {
            slot.lastRead = System.nanoTime();
            messages = refresh(key, slot);
        } finally {
            slot.lock.unlock();
        }
        evictBeyondBudget(key);
        return messages;
    }

    private List<Message> refresh(Path file, Slot slot) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException _) {
            slot.snapshot = null;
            return List.of();
        } catch (IOException e) {
            slot.snapshot = null;
            throw new UncheckedIOException("Failed to read session file: " + file, e);
        }
        Snapshot cached = slot.snapshot;
        if (cached != null && cached.cursor().describes(attributes)) return cached.messages();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            AppendCursor.Appended appended = AppendCursor.read(
                cached == null ? null : cached.cursor(), channel, attributes);
            Snapshot next = (appended.restarted() ? Snapshot.EMPTY : cached)
                .extend(storage, file, appended);
            slot.snapshot = next;
            return next.messages();
        } catch (NoSuchFileException _) {
            slot.snapshot = null;
            return List.of();
        } catch (IOException e) {
            slot.snapshot = null;
            throw new UncheckedIOException("Failed to read session file: " + file, e);
        } catch (RuntimeException e) {
            slot.snapshot = null;
            throw e;
        }
    }

    /** Drops least recently read transcripts until the retained bytes fit. */
    private void evictBeyondBudget(Path justRead) {
        long retained = slots.values().stream().mapToLong(Slot::retainedBytes).sum();
        if (retained <= byteBudget) return;
        List<Path> oldestFirst = slots.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(justRead))
            .sorted(Comparator.comparingLong(entry -> entry.getValue().lastRead))
            .map(Map.Entry::getKey)
            .toList();
        for (Path victim : oldestFirst) {
            if (retained <= byteBudget) return;
            Slot slot = slots.get(victim);
            if (slot == null || !slot.lock.tryLock()) continue;
            try {
                retained -= slot.retainedBytes();
                slots.remove(victim, slot);
            } finally {
                slot.lock.unlock();
            }
        }
    }

    /** One file's parse, guarded so concurrent reads of it share one. */
    private static final class Slot {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile Snapshot snapshot;
        private volatile long lastRead;

        long retainedBytes() {
            Snapshot current = snapshot;
            return current == null ? 0 : current.size();
        }
    }

    /**
     * A parse of one file version.
     *
     * @param cursor how far the file has been read, and the append check
     * @param prefixMessages the messages of the complete lines read
     * @param messages the whole file's messages, including an unfinished last line
     */
    private record Snapshot(AppendCursor cursor,
            List<Message> prefixMessages, List<Message> messages) {

        static final Snapshot EMPTY = new Snapshot(AppendCursor.START, List.of(), List.of());

        long size() {
            return cursor.size();
        }

        /** This snapshot's prefix followed by the lines written after it. */
        Snapshot extend(SessionStorage storage, Path file, AppendCursor.Appended appended) {
            List<Message> settled = storage.parseMessageLines(
                appended.settledLines(), file, appended.firstSettledLine());
            List<Message> prefix = settled.isEmpty()
                ? prefixMessages
                : Stream.concat(prefixMessages.stream(), settled.stream()).toList();
            List<Message> unfinished = storage.parseMessageLines(
                appended.unfinishedLines(), file, appended.firstUnfinishedLine());
            List<Message> whole = unfinished.isEmpty()
                ? prefix
                : Stream.concat(prefix.stream(), unfinished.stream()).toList();
            return new Snapshot(appended.cursor(), prefix, whole);
        }
    }
}
