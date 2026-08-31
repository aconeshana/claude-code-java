package com.claudecode.core.io;

import com.claudecode.core.platform.Platform;
import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filesystem helpers shared across modules.
 */
public final class FileUtils {

    public static final String FILE_NOT_FOUND_CWD_NOTE = "Note: your current working directory is";

    private static final Logger LOG = LoggerFactory.getLogger(FileUtils.class);
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final int REVERSE_READ_CHUNK_SIZE = 4 * 1024;

    private FileUtils() {}

    /** Writes content into a temporary path prepared by {@link #atomicReplace}. */
    @FunctionalInterface
    public interface TempFileWriter {
        void write(Path tempFile) throws IOException;
    }

    public record ResolvedPath(Path path, boolean symlink, boolean canonical) {}
    public record FileRange(String content, int bytesRead, long bytesTotal) {}

    /** Fail-open realpath resolution used by file loading and permission checks. */
    public static ResolvedPath safeResolvePath(Path path) {
        String raw = path.toString();
        if (Strings.CS.startsWith(raw, "//") || Strings.CS.startsWith(raw, "\\\\")) {
            return new ResolvedPath(path, false, false);
        }
        try {
            Path resolved = path.toRealPath();
            return new ResolvedPath(resolved, !resolved.equals(path), true);
        } catch (IOException | SecurityException _) {
            return new ResolvedPath(path, false, false);
        }
    }

    /** Resolves a live or dangling symlink in the deepest existing path component. */
    public static Path resolveDeepestExistingAncestor(Path absolutePath) {
        Path current = absolutePath.toAbsolutePath().normalize();
        List<Path> tail = new ArrayList<>();
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                try {
                    Path target = Files.readSymbolicLink(current);
                    Path resolved = target.isAbsolute() ? target : current.getParent().resolve(target).normalize();
                    for (int i = tail.size() - 1; i >= 0; i--) resolved = resolved.resolve(tail.get(i));
                    return resolved.normalize();
                } catch (IOException | SecurityException _) {
                    return null;
                }
            }
            if (Files.exists(current)) {
                try {
                    Path resolved = current.toRealPath();
                    if (!resolved.equals(current)) {
                        for (int i = tail.size() - 1; i >= 0; i--) resolved = resolved.resolve(tail.get(i));
                        return resolved.normalize();
                    }
                } catch (IOException | SecurityException _) {}
                return null;
            }
            Path name = current.getFileName();
            if (name != null) tail.add(name);
            current = current.getParent();
        }
        return null;
    }

    /** Original path plus every symlink target relevant to a permission decision. */
    public static List<Path> pathsForPermissionCheck(Path input) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        Path original = Path.of(PathUtils.expandTilde(input.toString())).toAbsolutePath().normalize();
        paths.add(original);
        String raw = input.toString();
        if (Strings.CS.startsWith(raw, "//") || Strings.CS.startsWith(raw, "\\\\")) return List.copyOf(paths);
        Path current = original;
        Set<Path> visited = new LinkedHashSet<>();
        try {
            for (int depth = 0; depth < 40 && visited.add(current); depth++) {
                if (!Files.exists(current)) {
                    Path resolved = resolveDeepestExistingAncestor(current);
                    if (resolved != null) paths.add(resolved);
                    break;
                }
                if (!Files.isSymbolicLink(current)) break;
                Path target = Files.readSymbolicLink(current);
                current = target.isAbsolute() ? target : current.getParent().resolve(target).normalize();
                paths.add(current);
            }
        } catch (IOException | SecurityException _) {}
        ResolvedPath finalPath = safeResolvePath(original);
        if (finalPath.symlink()) paths.add(finalPath.path());
        return List.copyOf(paths);
    }

    /** Adds the canonical path to {@code loadedPaths}; returns true when already present. */
    public static boolean isDuplicatePath(Path path, Set<Path> loadedPaths) {
        Path resolved = safeResolvePath(path).path().toAbsolutePath().normalize();
        return !loadedPaths.add(resolved);
    }

    /** Reads up to {@code maxBytes} at {@code offset}, returning null when offset is at EOF. */
    public static FileRange readFileRange(Path path, long offset, int maxBytes) throws IOException {
        long size = Files.size(path);
        if (size <= offset) return null;
        int amount = (int) Math.min(size - offset, maxBytes);
        byte[] bytes = new byte[amount];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int bytesRead = channel.read(buffer);
                if (bytesRead <= 0) {
                    break;
                }
            }
            int read = buffer.position();
            return new FileRange(new String(bytes, 0, read, StandardCharsets.UTF_8), read, size);
        }
    }

    public static FileRange tailFile(Path path, int maxBytes) throws IOException {
        long size = Files.size(path);
        if (size == 0) return new FileRange("", 0, 0);
        return readFileRange(path, Math.max(0, size - maxBytes), maxBytes);
    }


    public static String readStringOrNull(Path path) {
        try {
            return Files.readString(path, DEFAULT_CHARSET);
        } catch (IOException | SecurityException e) {
            LOG.debug("Could not read {}: {}", path, e.getMessage());
            return null;
        }
    }


    public static long modificationTimeMillis(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toMillis();
    }


    public static boolean isDirectoryEmpty(Path path) {
        if (!Files.exists(path)) return true;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            return !entries.iterator().hasNext();
        } catch (IOException | SecurityException _) {
            return false;
        }
    }

    public static Optional<String> findSimilarFile(Path missingPath) {
        Path parent = missingPath.getParent();
        if (parent == null) parent = Path.of(".");
        String name = missingPath.getFileName().toString();
        String base = stripExtension(name);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                String candidate = entry.getFileName().toString();
                if (!candidate.equals(name) && stripExtension(candidate).equals(base)) {
                    return Optional.of(candidate);
                }
            }
        } catch (IOException | SecurityException _) {}
        return Optional.empty();
    }


    public static Optional<Path> suggestPathUnderWorkingDirectory(Path requested, Path cwd) {
        Path absoluteCwd = cwd.toAbsolutePath().normalize();
        Path parent = absoluteCwd.getParent();
        if (parent == null) return Optional.empty();
        Path normalized = requested.toAbsolutePath().normalize();
        Path requestedParent = normalized.getParent();
        if (requestedParent != null) {
            try {
                normalized = requestedParent.toRealPath().resolve(normalized.getFileName());
            } catch (IOException | SecurityException _) {}
        }
        if (!normalized.startsWith(parent) || normalized.startsWith(absoluteCwd)) {
            return Optional.empty();
        }
        Path corrected = absoluteCwd.resolve(parent.relativize(normalized)).normalize();
        return Files.exists(corrected) ? Optional.of(corrected) : Optional.empty();
    }

    public static String fileNotFoundMessage(Path requested, Path cwd) {
        String message = "File does not exist. " + FILE_NOT_FOUND_CWD_NOTE + " "
            + cwd.toAbsolutePath().normalize() + ".";
        Optional<Path> cwdSuggestion = suggestPathUnderWorkingDirectory(requested, cwd);
        if (cwdSuggestion.isPresent()) return message + " Did you mean " + cwdSuggestion.get() + "?";
        Optional<String> similar = findSimilarFile(requested);
        return similar.map(value -> message + " Did you mean " + value + "?").orElse(message);
    }

    public static String normalizePathForComparison(Path path) {
        String normalized = path.normalize().toString();
        return Platform.IS_WINDOWS
            ? normalized.replace('/', '\\').toLowerCase(Locale.ROOT)
            : normalized;
    }


    public static boolean pathsEqual(Path first, Path second) {
        return normalizePathForComparison(first).equals(normalizePathForComparison(second));
    }

    /** Reads at most {@code maxLines} non-empty UTF-8 lines from newest to oldest. */
    public static List<String> readLinesReverse(Path path, int maxLines) throws IOException {
        if (maxLines <= 0) return List.of();
        List<String> lines = new ArrayList<>();
        forEachLineReverse(path, line -> {
            lines.add(line);
            return lines.size() < maxLines;
        });
        return List.copyOf(lines);
    }

    /** Streams UTF-8 lines newest-first until {@code visitor} returns false. */
    public static void forEachLineReverse(Path path, Predicate<String> visitor) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long position = channel.size();
            byte[] remainder = new byte[0];

            while (position > 0) {
                int chunkSize = (int) Math.min(REVERSE_READ_CHUNK_SIZE, position);
                position -= chunkSize;
                byte[] chunk = new byte[chunkSize];
                ByteBuffer buffer = ByteBuffer.wrap(chunk);
                long readPosition = position;
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer, readPosition);
                    if (read <= 0) throw new IOException("Unexpected EOF while reading " + path);
                    readPosition += read;
                }

                byte[] combined = Arrays.copyOf(chunk, chunk.length + remainder.length);
                System.arraycopy(remainder, 0, combined, chunk.length, remainder.length);
                int firstNewline = indexOf(combined, (byte) '\n');
                if (firstNewline < 0) {
                    remainder = combined;
                    continue;
                }

                remainder = Arrays.copyOfRange(combined, 0, firstNewline);
                String[] lines = new String(combined, firstNewline + 1,
                    combined.length - firstNewline - 1, StandardCharsets.UTF_8).split("\n", -1);
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (!lines[i].isEmpty() && !visitor.test(lines[i])) return;
                }
            }

            if (remainder.length > 0) {
                visitor.test(new String(remainder, StandardCharsets.UTF_8));
            }
        }
    }

    private static int indexOf(byte[] bytes, byte target) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == target) return i;
        }
        return -1;
    }

    // -----------------------------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------------------------

    /**
     * Writes {@code content} to {@code path} as UTF-8, creating parent directories first.
     * Uses the default {@link Files#writeString} open options (CREATE, TRUNCATE_EXISTING, WRITE).
     *
     * @throws IOException if creating directories or writing fails
     */
    public static void writeString(Path path, String content) throws IOException {
        writeString(path, content, DEFAULT_CHARSET);
    }

    /**
     * Writes {@code content} to {@code path} using the given charset, creating parent directories
     * first. Uses the default {@link Files#writeString} open options (CREATE, TRUNCATE_EXISTING, WRITE).
     *
     * @throws IOException if creating directories or writing fails
     */
    public static void writeString(Path path, String content, Charset charset) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, charset);
    }

    /**
     * Writes {@code content} to {@code path} with explicit open options (e.g. {@link StandardOpenOption#APPEND},
     * {@link StandardOpenOption#CREATE_NEW}), creating parent directories first.
     *
     * @throws IOException if creating directories or writing fails
     */
    public static void writeString(Path path, String content, Charset charset,
            StandardOpenOption... options) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, charset, options);
    }

    /**
     * Writes {@code bytes} to {@code path}, creating parent directories first. Defaults to
     * CREATE + TRUNCATE_EXISTING when no options are supplied.
     *
     * @throws IOException if creating directories or writing fails
     */
    public static void writeBytes(Path path, byte[] bytes, StandardOpenOption... options)
            throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, bytes, options);
    }

    /**
     * Replaces {@code target} only after {@code writer} has successfully produced a complete
     * same-directory temporary file. A final-file symlink is followed once, matching the compatibility
     * writer's "write through symlink" behavior; existing POSIX permissions are copied to the
     * temporary file before the rename. The final rename is atomic when the filesystem supports
     * it; otherwise it falls back to a regular replacing move. Failed writes leave an existing
     * target untouched and always clean up the temporary file.
     */
    public static void atomicReplace(Path target, TempFileWriter writer) throws IOException {
        Path destination = resolveAtomicDestination(target);
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Cannot resolve parent directory for " + target);
        }
        Files.createDirectories(parent);

        String fileName = destination.getFileName().toString();
        Path temp = Files.createTempFile(parent, "." + fileName + ".", ".tmp");
        try {
            writer.write(temp);
            copyExistingPosixPermissions(destination, temp);
            try {
                Files.move(temp, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }


    private static Path resolveAtomicDestination(Path target) throws IOException {
        Path destination = target.toAbsolutePath().normalize();
        if (!Files.isSymbolicLink(destination)) return destination;
        Path linkTarget = Files.readSymbolicLink(destination);
        Path parent = destination.getParent();
        return (linkTarget.isAbsolute() ? linkTarget : parent.resolve(linkTarget)).normalize();
    }

    /** Preserve an existing target's POSIX mode across the temp-file rename. */
    private static void copyExistingPosixPermissions(Path destination, Path temp) {
        try {
            if (Files.exists(destination)
                    && destination.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(destination));
            }
        } catch (IOException | UnsupportedOperationException e) {
            LOG.debug("Could not preserve permissions for {}: {}", destination, e.getMessage());
        }
    }

    /** Writes until {@code buffer} has no remaining bytes. */
    public static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        int expected = buffer.remaining();
        int written = 0;
        while (buffer.hasRemaining()) {
            written += channel.write(buffer);
        }
        if (written != expected) {
            throw new IOException("Incomplete write: " + written + "/" + expected + " bytes");
        }
    }

    /**
     * Best-effort POSIX {@code 0600}. Non-POSIX filesystems and permission failures are logged at
     * debug level and otherwise ignored so callers keep their existing portable fallback behavior.
     */
    public static void trySetOwnerOnlyPermissions(Path path) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            }
        } catch (IOException | UnsupportedOperationException e) {
            LOG.debug("Could not set 0600 on {}: {}", path, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------------------------
    // Copy
    // -----------------------------------------------------------------------------------------

    /**
     * Copies a single file, creating the destination's parent directories and replacing any
     * existing destination. matches {@code fs.cp(src, dest, { force: true })} for one file.
     *
     * @throws IOException if the copy fails
     */
    public static void copyFile(Path src, Path dest) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Recursively copies {@code src} into {@code dest}, matching {@code fs.cp(src, dest, { recursive: true, force: true })}.
     * Directory structure is reproduced via {@link Path#relativize}, directories are recreated, and files are
     * copied with {@link StandardCopyOption#REPLACE_EXISTING}. An {@link IOException} during the walk is
     * rethrown wrapped in {@link java.io.UncheckedIOException} so it can be caught by the caller's stream
     * terminal operation.
     *
     * @throws IOException if traversing or copying fails
     */
    public static void copyDirectory(Path src, Path dest) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            try {
                walk.forEach(sourcePath -> {
                    Path targetPath = dest.resolve(src.relativize(sourcePath).toString());
                    try {
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(targetPath);
                        } else {
                            copyFile(sourcePath, targetPath);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Path helpers
    // -----------------------------------------------------------------------------------------

    public static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Removes a trailing {@code suffix} from {@code name} case-insensitively (anchored at end of string).
     * Unlike {@link #stripExtension}, only the exact suffix is stripped — e.g. {@code stripSuffix("a.txt", ".md")}
     * returns {@code "a.txt"} unchanged. matches the duplicated {@code replaceAll("(?i)\\.md$", "")} pattern.
     */
    public static String stripSuffix(String name, String suffix) {
        return name.replaceAll("(?i)" + Pattern.quote(suffix) + "$", "");
    }

    // -----------------------------------------------------------------------------------------
    // Directory listing
    // -----------------------------------------------------------------------------------------

    /**
     * Lists immediate children of {@code dir} matching a glob pattern (e.g. ).
     * Non-recursive, matching {@code fs.readdirSync(dir, { withFileTypes: true })} filtered by glob.
     *
     * @throws IOException if the directory cannot be listed
     */
    public static List<Path> listFiles(Path dir, String glob) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Lists immediate children of {@code dir} accepted by {@code filter}. Non-recursive.
     *
     * @throws IOException if the directory cannot be listed
     */
    public static List<Path> listFiles(Path dir, Predicate<Path> filter) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, filter::test)) {
            for (Path p : ds) {
                result.add(p);
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------------------------
    // Temp files
    // -----------------------------------------------------------------------------------------

    /** Creates a temporary file in the system temp directory. */
    public static Path createTempFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(prefix, suffix);
    }

    /** Creates a temporary directory in the system temp directory. */
    public static Path createTempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    /** Creates a temporary directory with the given {@code prefix} inside {@code dir}. */
    public static Path createTempDir(Path dir, String prefix) throws IOException {
        Files.createDirectories(dir);
        return Files.createTempDirectory(dir, prefix);
    }

    // -----------------------------------------------------------------------------------------
    // Existence guards
    // -----------------------------------------------------------------------------------------

    /** Returns true if {@code path} is non-null and exists. */
    public static boolean exists(Path path) {
        return path != null && Files.exists(path);
    }

    /** Returns true if {@code path} is non-null and a regular file. */
    public static boolean isRegularFile(Path path) {
        return path != null && Files.isRegularFile(path);
    }

    // -----------------------------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------------------------

    /**
     * Recursively delete a file or directory tree.
     *
     * <ul>
     *   <li><b>Symlink-safe:</b> uses {@link Files#walk} with no
     *       {@code FOLLOW_LINKS} option, so a symlink is removed as a link and
     *       its target is never traversed or deleted.</li>
     *   <li><b>Best-effort:</b> a file that can't be deleted is logged and
     *       skipped; cleanup continues for the rest of the tree.</li>
     *   <li><b>Idempotent:</b> a non-existent path is a silent no-op.</li>
     * </ul>
     */
    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            // Deepest paths first so a directory is emptied before it is removed.
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warn("Failed to delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to traverse {} for deletion: {}", path, e.getMessage());
        }
    }
}
