package com.claudecode.session;

import com.claudecode.core.annotation.Explanation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends single JSONL records to transcript files without splicing concurrent writers.
 *
 * <p>Covers the record-append half of {@code src/utils/sessionStorage.ts} — specifically the
 * {@code tryAppend} helper that opens the transcript {@code O_WRONLY|O_APPEND}, writes one whole
 * record from a single buffer, and drains short writes until nothing remains.
 *
 * <p>Two independent hazards make a plain {@code Files.writeString(..., APPEND)} unsafe here:
 *
 * <ul>
 *   <li>The JDK stages writes through an 8 KiB {@code IOUtil} buffer, so a record larger than that
 *       reaches the file as several {@code write(2)} calls. {@code O_APPEND} positions each call at
 *       end-of-file but does not fuse them, so another appender's record can land in the gap and
 *       splice two records into one unparseable line.</li>
 *   <li>A partial write leaves a truncated record behind unless the remainder is drained.</li>
 * </ul>
 *
 * <p>The per-file lock orders same-process writers, {@code APPEND} keeps separate processes from
 * overwriting each other, and the drain loop keeps each record whole.
 */
final class TranscriptAppender {

    @Explanation("""
        The original runs appends on Node's single-threaded event loop, which serializes them \
        implicitly; this port appends from virtual threads and needs an explicit per-file lock.""")
    private static final ConcurrentMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private TranscriptAppender() {
        // utility class
    }

    /** Appends one record, creating the file (but not its parent directories) when absent. */
    static void append(Path file, String line) throws IOException {
        writeLocked(file, line, true);
    }

    /**
     * Appends one record to a transcript that already exists and is non-empty.
     *
     * @return {@code false} when the file is absent or empty, matching the original's probe-then-
     *     append contract for locating a session across candidate project directories
     */
    static boolean appendToExisting(Path file, String line) throws IOException {
        return writeLocked(file, line, false);
    }

    private static boolean writeLocked(Path file, String line, boolean create) throws IOException {
        ReentrantLock lock = LOCKS.computeIfAbsent(lockKey(file), _ -> new ReentrantLock());
        lock.lock();
        try {
            return writeWholeRecord(file, line, create);
        } finally {
            lock.unlock();
        }
    }

    private static boolean writeWholeRecord(Path file, String line, boolean create)
            throws IOException {
        if (create) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                drain(channel, ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)), file);
                return true;
            }
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            if (channel.size() <= 0) return false;
            drain(channel, ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)), file);
            return true;
        } catch (NoSuchFileException | NotDirectoryException _) {
            // A transcript that is not there is simply not this candidate directory's session.
            return false;
        }
    }

    private static void drain(FileChannel channel, ByteBuffer buffer, Path file)
            throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) {
                throw new IOException("Zero-progress short write appending to " + file
                    + " with " + buffer.remaining() + " bytes remaining");
            }
        }
    }

    /**
     * Normalizes without touching the filesystem so that two spellings of the same transcript share
     * a lock. {@code toRealPath} is deliberately avoided: it fails on a file that does not exist
     * yet, which is exactly the create-on-first-append case.
     */
    private static Path lockKey(Path file) {
        return file.toAbsolutePath().normalize();
    }
}
