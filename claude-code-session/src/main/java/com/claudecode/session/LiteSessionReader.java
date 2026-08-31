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

/** Bounded head/tail reader used by the resume-session catalog. */
final class LiteSessionReader {
    static final int LITE_READ_BYTES = 64 * 1024;

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
                attributes.lastModifiedTime().toMillis(), attributes.creationTime().toMillis()));
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

    record LiteSessionFile(long size, String head, String tail, long bytesRead,
                           long mtime, long ctime) {}
}
