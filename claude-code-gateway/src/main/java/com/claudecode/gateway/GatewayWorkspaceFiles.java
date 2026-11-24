package com.claudecode.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Reads one file out of one workspace root, in the shape the file-preview tab
 * renders.
 *
 * <p>This is the shared read core behind {@link GatewayWorkspaceFilePort}. It
 * lives here rather than in a host module because two very different hosts
 * serve the same preview:
 * <ul>
 *   <li>a TUI process, whose own engine knows the workspace root directly;</li>
 *   <li>a standalone {@code claude web} gateway, which resolves the root from
 *       the work directory the session's owning process reported, so the
 *       preview answers for a session whose engine lives in another process.</li>
 * </ul>
 *
 * <p>The read itself is a pure function of {@code (root, path)}, so both hosts
 * share one implementation of the containment and decoding rules instead of
 * each carrying a near-copy that can drift: a path must stay inside the root
 * once symbolic links are followed, a file above {@link #MAX_TEXT_BYTES} is
 * reported as oversized, and content that is not valid UTF-8 or holds a NUL
 * byte is reported as binary.
 */
public final class GatewayWorkspaceFiles {

    /** The size budget for one previewed file. */
    public static final long MAX_TEXT_BYTES = 1024L * 1024L;
    /** Maximum entries returned for a single directory listing. */
    public static final int MAX_LIST_ENTRIES = 500;

    private GatewayWorkspaceFiles() {}

    /**
     * Lists immediate children of {@code dir} (or workspace root when blank) inside {@code root}.
     * Containment is enforced after following symbolic links.
     */
    public static Optional<GatewayWorkspaceFilePort.DirectoryListing> list(Path root, String dir) {
        try {
            Path realRoot = root.toRealPath();
            String trimmed = StringUtils.trimToEmpty(dir);
            Path target = trimmed.isEmpty()
                ? realRoot
                : realRoot.resolve(trimmed).normalize().toRealPath();
            if (!target.startsWith(realRoot) || !Files.isDirectory(target)) {
                return Optional.empty();
            }
            List<GatewayWorkspaceFilePort.DirectoryEntry> entries = new ArrayList<>();
            try (Stream<Path> stream = Files.list(target)) {
                List<Path> children = stream.toList();
                for (Path child : children) {
                    if (entries.size() >= MAX_LIST_ENTRIES) break;
                    Path fileName = child.getFileName();
                    if (fileName == null) continue;
                    String name = fileName.toString();
                    boolean isDir = Files.isDirectory(child);
                    long size = 0L;
                    if (!isDir) {
                        try {
                            size = Files.size(child);
                        } catch (IOException _) {
                            size = 0L;
                        }
                    }
                    String rel = realRoot.relativize(child).toString().replace('\\', '/');
                    entries.add(new GatewayWorkspaceFilePort.DirectoryEntry(name, rel, isDir, size));
                }
            }
            entries.sort(Comparator
                .comparing(GatewayWorkspaceFilePort.DirectoryEntry::directory).reversed()
                .thenComparing(GatewayWorkspaceFilePort.DirectoryEntry::name, String.CASE_INSENSITIVE_ORDER));
            String relDir = realRoot.equals(target)
                ? ""
                : realRoot.relativize(target).toString().replace('\\', '/');
            return Optional.of(new GatewayWorkspaceFilePort.DirectoryListing(relDir, entries));
        } catch (IOException | InvalidPathException _) {
            return Optional.empty();
        }
    }

    /**
     * Reads {@code path} inside {@code root}, or empty when it cannot be
     * served.
     *
     * <p>Containment is checked on the <em>resolved</em> path, so a symlink
     * pointing outside the root is refused exactly like a {@code ..} segment.
     * Every failure mode — a blank path, an unresolvable root, a path that
     * escapes the root, a directory, an unreadable file — answers empty: the
     * caller has one honest response, an unknown file.
     *
     * @param root the workspace root the path must resolve inside
     * @param path the requested path, absolute or relative to {@code root}
     * @return the file, or empty when it cannot be served
     */
    public static Optional<GatewayWorkspaceFilePort.TextFile> read(Path root, String path) {
        if (StringUtils.isBlank(path)) {
            return Optional.empty();
        }
        try {
            Path realRoot = root.toRealPath();
            Path target = realRoot.resolve(path).normalize().toRealPath();
            if (!target.startsWith(realRoot) || !Files.isRegularFile(target)) {
                return Optional.empty();
            }
            return Optional.of(content(path, target));
        } catch (IOException | InvalidPathException _) {
            return Optional.empty();
        }
    }

    /**
     * Opens or reveals {@code path} inside {@code root} using the host desktop launcher.
     * Refuses any path that escapes {@code root} after symlink resolution.
     */
    public static boolean openInHost(Path root, String path, boolean reveal) {
        if (StringUtils.isBlank(path)) {
            return false;
        }
        try {
            Path realRoot = root.toRealPath();
            Path target = realRoot.resolve(path).normalize().toRealPath();
            if (!target.startsWith(realRoot) || !Files.exists(target)) {
                return false;
            }
            String os = System.getProperty("os.name", "");
            ProcessBuilder pb;
            if (Strings.CI.contains(os, "mac")) {
                pb = reveal
                    ? new ProcessBuilder("/usr/bin/open", "-R", target.toString())
                    : new ProcessBuilder("/usr/bin/open", target.toString());
            } else if (Strings.CI.contains(os, "win")) {
                pb = reveal
                    ? new ProcessBuilder("explorer.exe", "/select," + target)
                    : new ProcessBuilder("cmd.exe", "/c", "start", "", target.toString());
            } else {
                Path toOpen = reveal && Files.isRegularFile(target) && target.getParent() != null
                    ? target.getParent()
                    : target;
                pb = new ProcessBuilder("xdg-open", toOpen.toString());
            }
            pb.redirectErrorStream(true);
            pb.start();
            return true;
        } catch (IOException | InvalidPathException _) {
            return false;
        }
    }

    private static GatewayWorkspaceFilePort.TextFile content(String path, Path target)
            throws IOException {
        byte[] bytes = readBounded(target);
        if (bytes.length > MAX_TEXT_BYTES) {
            return new GatewayWorkspaceFilePort.TextFile(
                path, GatewayWorkspaceFilePort.TextFile.Kind.OVERSIZED, "");
        }
        String text = decodeText(bytes);
        if (text == null) {
            return new GatewayWorkspaceFilePort.TextFile(
                path, GatewayWorkspaceFilePort.TextFile.Kind.BINARY, "");
        }
        return new GatewayWorkspaceFilePort.TextFile(
            path, GatewayWorkspaceFilePort.TextFile.Kind.TEXT, text);
    }

    /** Reads at most one byte past the budget, so a file that grew after a size check is still refused. */
    private static byte[] readBounded(Path target) throws IOException {
        try (InputStream in = Files.newInputStream(target)) {
            return in.readNBytes((int) MAX_TEXT_BYTES + 1);
        }
    }

    private static String decodeText(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException _) {
            return null;
        }
    }
}