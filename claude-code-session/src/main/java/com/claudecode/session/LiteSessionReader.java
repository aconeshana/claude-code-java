package com.claudecode.session;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import org.apache.commons.lang3.Strings;

/** Bounded head/tail reader used by the resume-session catalog. */
final class LiteSessionReader {
    static final int LITE_READ_BYTES = 64 * 1024;

    /**
     * Extra budget for {@link #findLatchedMarker}, on top of the head/tail windows.
     *
     * <p>A deliberate tradeoff. The catalog enriches every transcript under
     * {@code ~/.claude/projects}, so an unbounded "scan until found" would make a
     * listing read every large file end to end just to prove a marker is absent.
     * This caps that at a fixed cost and gives up beyond it: a session archived and
     * then resumed for roughly this many bytes still resolves, one resumed for far
     * longer reverts to visible — the pre-existing failure mode, but pushed from
     * {@link #LITE_READ_BYTES} out to here.
     *
     * <p>Sized against real histories, where the overwhelming majority of
     * transcripts are small enough that the tail window alone settles the flag and
     * this budget is never touched.
     */
    static final int MARKER_SCAN_BYTES = 256 * 1024;

    /** Scan granularity; overlapped by {@link #MARKER_OVERLAP} so no row is split. */
    private static final int MARKER_CHUNK_BYTES = 64 * 1024;

    /** Longest marker row we tolerate being split across a chunk boundary. */
    private static final int MARKER_OVERLAP = 4 * 1024;

    Optional<LiteSessionFile> read(Path path, long knownSize, byte[] buffer) {
        if (path == null || buffer == null || buffer.length < LITE_READ_BYTES) {
            return Optional.empty();
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class);
            long size = channel.size();
            if (size <= 0) return Optional.empty();

            int headLength = readAt(channel, buffer, 0, (int) Math.min(size, LITE_READ_BYTES));
            if (headLength <= 0) return Optional.empty();
            String head = new String(buffer, 0, headLength, UTF_8);
            long bytesRead = headLength;

            long effectiveSize = knownSize > 0 ? Math.min(knownSize, size) : size;
            long tailOffset = Math.max(0, effectiveSize - LITE_READ_BYTES);
            String tail = head;
            if (tailOffset > 0) {
                int tailLength = readAt(channel, buffer, tailOffset,
                    (int) Math.min(LITE_READ_BYTES, size - tailOffset));
                tail = new String(buffer, 0, Math.max(0, tailLength), UTF_8);
                bytesRead += Math.max(0, tailLength);
            }
            return Optional.of(new LiteSessionFile(size, head, tail, bytesRead,
                attributes.lastModifiedTime().toMillis(), attributes.creationTime().toMillis(),
                tailOffset));
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    private static int readAt(FileChannel channel, byte[] buffer, long position, int length)
            throws IOException {
        if (length <= 0) return 0;
        ByteBuffer target = ByteBuffer.wrap(buffer, 0, length);
        int total = 0;
        while (target.hasRemaining()) {
            int read = channel.read(target, position + total);
            if (read <= 0) break;
            total += read;
        }
        return total;
    }

    /**
     * Searches backwards from the end of the tail window for a latched metadata
     * marker, returning the newest matching row or {@code null}.
     *
     * <p>Exists because the head/tail windows are <em>position</em>-bounded while a
     * latching flag such as {@code archived} is <em>semantically</em> permanent. The
     * marker is appended once, so it starts at the very end — but a session may be
     * resumed after archiving, and once the conversation appends more than
     * {@link #LITE_READ_BYTES} past it the row falls out of the tail window and the
     * flag silently reverts. Unlike {@code customTitle}/{@code gitBranch}, which the
     * user re-asserts on the next edit, an archived session is hidden, so the user
     * cannot even reach it to archive it again.
     *
     * <p>Walks fixed {@link #MARKER_CHUNK_BYTES} chunks from the tail backwards and
     * stops at the first hit, so the common cases cost nothing beyond what the caller
     * already decided to spend: a session that was never archived is settled by the
     * cheap {@code contains} precheck the caller applies to the tail, and a session
     * archived recently hits on the first chunk. Only a resumed-after-archiving
     * transcript pays the walk, and never more than {@link #MARKER_SCAN_BYTES}.
     *
     * @param scanFrom byte offset the tail window already covered; the scan searches
     *                 strictly before this and never re-reads the tail
     * @param markers  alternative spellings of the row marker; a line matching any
     *                 of them is a hit
     * @return the newest line containing one of {@code markers}, or {@code null}
     */
    static String findLatchedMarker(Path path, long scanFrom, String... markers) {
        if (path == null || markers == null || markers.length == 0 || scanFrom <= 0) return null;
        byte[] chunk = new byte[MARKER_CHUNK_BYTES + MARKER_OVERLAP];
        long floor = Math.max(0, scanFrom - MARKER_SCAN_BYTES);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            for (long end = scanFrom; end > floor; end -= MARKER_CHUNK_BYTES) {
                long start = Math.max(floor, end - MARKER_CHUNK_BYTES);
                // Overlap forward so a row straddling the boundary is still whole.
                int length = (int) Math.min(chunk.length, scanFrom - start);
                int read = readAt(channel, chunk, start, length);
                if (read <= 0) break;
                String text = new String(chunk, 0, read, UTF_8);
                // The chunk starts mid-row unless it starts at the file's beginning;
                // the overlap keeps the forward edge whole, this drops the truncated
                // leading fragment so a marker is never matched against half a row.
                // A row split here is still found by the next (earlier) chunk, whose
                // overlap covers it.
                if (start > 0) {
                    int firstBreak = text.indexOf('\n');
                    if (firstBreak < 0) continue;
                    text = text.substring(firstBreak + 1);
                }
                String hit = lastLineContaining(text, markers);
                if (hit != null) return hit;
            }
        } catch (IOException | RuntimeException _) {
            return null;
        }
        return null;
    }

    private static String lastLineContaining(String text, String... markers) {
        String found = null;
        for (String line : text.split("\n")) {
            for (String marker : markers) {
                if (Strings.CS.contains(line, marker)) { found = line; break; }
            }
        }
        return found;
    }

    /**
     * @param tailOffset byte offset where the tail window begins; {@code 0} means the
     *                   whole file was read and {@code tail == head}, so there is
     *                   nothing before it left to scan
     */
    record LiteSessionFile(long size, String head, String tail, long bytesRead,
                           long mtime, long ctime, long tailOffset) {}
}
