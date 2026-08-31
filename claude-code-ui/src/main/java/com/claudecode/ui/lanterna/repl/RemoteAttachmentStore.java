package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Persists IM attachments beneath the active workspace without following attacker-controlled
 * symbolic links.
 */
@Explanation("Secure attachment ingress for remote Session Host turns")
final class RemoteAttachmentStore {

    private static final FileAttribute<?> DIRECTORY_MODE =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<?> FILE_MODE =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private RemoteAttachmentStore() {}

    static Path persist(
            String workingDirectory,
            String sessionId,
            String messageId,
            SessionHostSubmission.Attachment attachment) {
        try {
            Path workspace = Path.of(workingDirectory).toRealPath();
            String safeMessage = safePathSegment(
                StringUtils.isBlank(messageId) ? "remote" : messageId);
            String safeName = safePathSegment(
                StringUtils.isBlank(attachment.fileName()) ? "attachment.bin" : attachment.fileName());
            Path root = secureDirectory(workspace, ".claude", "remote-attachments",
                safePathSegment(sessionId), safeMessage);
            Path target = root.resolve(safeName).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("invalid attachment name");
            }
            writeNewFile(target, attachment.data());
            return target;
        } catch (FileAlreadyExistsException collision) {
            throw new IllegalArgumentException("attachment already exists", collision);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to persist remote attachment", failure);
        }
    }

    private static Path secureDirectory(Path workspace, String... segments) throws IOException {
        Path current = workspace;
        for (String segment : segments) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("remote attachment path is not a real directory: " + current);
                }
                continue;
            }
            try {
                Files.createDirectory(current, DIRECTORY_MODE);
            } catch (UnsupportedOperationException _) {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException raced) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw raced;
                }
            }
        }
        return current.toAbsolutePath().normalize();
    }

    private static void writeNewFile(Path target, byte[] data) throws IOException {
        Set<OpenOption> options = Set.of(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = openNewFile(target, options)) {
            ByteBuffer bytes = ByteBuffer.wrap(data);
            while (bytes.hasRemaining()) channel.write(bytes);
        }
    }

    private static SeekableByteChannel openNewFile(
            Path target, Set<OpenOption> options) throws IOException {
        try {
            return Files.newByteChannel(target, options, FILE_MODE);
        } catch (UnsupportedOperationException _) {
            return Files.newByteChannel(target, options);
        }
    }

    static String safePathSegment(String raw) {
        String value = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
        value = value.replaceAll("^[.]+", "_");
        if (StringUtils.isBlank(value) || Strings.CS.equals(".", value) || Strings.CS.equals("..", value)) {
            return "attachment";
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }
}
