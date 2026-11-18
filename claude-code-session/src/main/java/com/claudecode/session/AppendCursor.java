package com.claudecode.session;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * How far an append-only transcript has been read, and the evidence needed to
 * tell whether the file it was read from has since only grown.
 *
 * <p>A reader that keeps what it derived from a transcript holds one of these
 * and asks it for the lines written since. Transcripts are append-only, so the
 * rows already read stay valid as long as the bytes they came from are still
 * there; a file replaced by an atomic move, truncated, or rewritten in place
 * is reported as {@linkplain Appended#restarted() restarted} and read again
 * from its first byte.
 *
 * <p>Only complete lines advance the cursor. A last line still being written
 * is returned separately on every read, exactly as a whole-file read would
 * see it, and is never mistaken for settled bytes.
 *
 * @param fileKey the identity of the file version read
 * @param size its size when read
 * @param modified its modification time when read
 * @param parsedBytes the length of the prefix made of complete lines
 * @param parsedLines how many lines that prefix holds
 * @param prefixTail the last bytes of that prefix, the append check
 */
record AppendCursor(Object fileKey, long size, FileTime modified,
        long parsedBytes, long parsedLines, byte[] prefixTail) {

    /** Bytes before the parsed offset that must be unchanged for an append. */
    static final int TAIL_CHECK_BYTES = 4096;

    /** Nothing read yet. */
    static final AppendCursor START = new AppendCursor(null, 0, null, 0, 0, new byte[0]);

    /**
     * The lines written since one read.
     *
     * @param restarted whether the file was read from its start because it is
     *     not the previous version with lines appended; whatever was derived
     *     from earlier lines no longer applies
     * @param settledLines complete lines, which advance the cursor
     * @param firstSettledLine the 1-based file line number of the first one
     * @param unfinishedLines the unterminated last line, if any; never settled
     * @param firstUnfinishedLine the 1-based file line number of that line
     * @param cursor where the next read continues
     */
    record Appended(boolean restarted, List<String> settledLines, long firstSettledLine,
            List<String> unfinishedLines, long firstUnfinishedLine, AppendCursor cursor) {
    }

    /**
     * The lines of the file behind {@code channel} after {@code previous}, or
     * all of its lines when it is not {@code previous}'s file with lines
     * appended.
     *
     * @param previous the cursor of the last read, or {@code null} for none
     * @throws java.nio.charset.CharacterCodingException on malformed UTF-8,
     *     as {@code Files.readAllLines} fails
     */
    static Appended read(AppendCursor previous, FileChannel channel,
            BasicFileAttributes attributes) throws IOException {
        boolean continued = previous != null && previous.continuesInto(attributes, channel);
        return (continued ? previous : START).advance(channel, attributes, !continued);
    }

    /** Whether this cursor was taken at exactly the file version described. */
    boolean describes(BasicFileAttributes attributes) {
        return Objects.equals(fileKey, attributes.fileKey())
            && size == attributes.size()
            && Objects.equals(modified, attributes.lastModifiedTime());
    }

    /** Whether the file is this one with lines appended. */
    private boolean continuesInto(BasicFileAttributes attributes, FileChannel channel)
            throws IOException {
        if (fileKey == null || !fileKey.equals(attributes.fileKey())) return false;
        if (attributes.size() < parsedBytes) return false;
        ByteBuffer current = ByteBuffer.allocate(prefixTail.length);
        readFully(channel, current, parsedBytes - prefixTail.length);
        return Arrays.equals(current.array(), prefixTail);
    }

    private Appended advance(FileChannel channel, BasicFileAttributes attributes,
            boolean restarted) throws IOException {
        long fileSize = attributes.size();
        int appended = Math.toIntExact(fileSize - parsedBytes);
        ByteBuffer bytes = ByteBuffer.allocate(appended);
        readFully(channel, bytes, parsedBytes);
        byte[] raw = bytes.array();
        int complete = lastNewline(raw) + 1;

        List<String> settled = lines(raw, 0, complete);
        List<String> unfinished = lines(raw, complete, raw.length);
        long nextLines = parsedLines + settled.size();
        byte[] tail = complete == 0 ? prefixTail : tailOf(prefixTail, raw, complete);
        AppendCursor next = new AppendCursor(attributes.fileKey(), fileSize,
            attributes.lastModifiedTime(), parsedBytes + complete, nextLines, tail);
        return new Appended(restarted, settled, parsedLines + 1,
            unfinished, nextLines + 1, next);
    }

    private static byte[] tailOf(byte[] previousTail, byte[] raw, int complete) {
        if (complete >= TAIL_CHECK_BYTES) {
            return Arrays.copyOfRange(raw, complete - TAIL_CHECK_BYTES, complete);
        }
        int kept = Math.min(previousTail.length, TAIL_CHECK_BYTES - complete);
        byte[] tail = new byte[kept + complete];
        System.arraycopy(previousTail, previousTail.length - kept, tail, 0, kept);
        System.arraycopy(raw, 0, tail, kept, complete);
        return tail;
    }

    private static int lastNewline(byte[] raw) {
        for (int i = raw.length - 1; i >= 0; i--) {
            if (raw[i] == '\n') return i;
        }
        return -1;
    }

    /**
     * The lines of a byte range, split as {@code Files.readAllLines} splits
     * them and failing on malformed UTF-8 as it does. A range only ever ends
     * at a newline or at the end of the file, and a newline byte is never part
     * of a multi-byte character, so no character is cut.
     */
    private static List<String> lines(byte[] raw, int from, int to) throws IOException {
        if (from == to) return List.of();
        String text = StandardCharsets.UTF_8.newDecoder()
            .decode(ByteBuffer.wrap(raw, from, to - from))
            .toString();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            return reader.lines().toList();
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long position)
            throws IOException {
        long at = position;
        while (target.hasRemaining()) {
            int read = channel.read(target, at);
            if (read < 0) throw new IOException("Transcript shrank while being read");
            at += read;
        }
    }
}
