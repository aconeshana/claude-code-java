package com.claudecode.session;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * JSON-file {@link ProjectIndexStore} — a single cache file under the Claude
 * config home's own {@code cache/} directory (deliberately NOT inside
 * {@code projects/}, which the official CLI and tools like Wake also read and
 * write; a foreign change must only cost us a rebuild, never a parse of someone
 * else's format). Follows the {@code StatsCacheStore} persistence pattern:
 * version-gated tolerant load, null-normalization, and
 * {@link FileUtils#atomicReplace} saves with {@code force(true)}.
 *
 * <p>No 197 counterpart — released 2.1.197 keeps no project index; this cache
 * exists so the project panel can open from a fingerprint-validated snapshot
 * instead of re-reading every transcript's head/tail.
 */
public final class FileProjectIndexStore implements ProjectIndexStore {

    private static final Logger LOG = LoggerFactory.getLogger(FileProjectIndexStore.class);
    private static final String FILENAME = "project-index.json";

    private final Path cachePath;

    /** Production wiring: {@code $CLAUDE_HOME/cache/project-index.json}. */
    public FileProjectIndexStore() {
        this(ClaudePaths.CLAUDE_HOME.resolve("cache").resolve(FILENAME));
    }

    /** Explicit path (tests). */
    public FileProjectIndexStore(Path cachePath) {
        this.cachePath = cachePath;
    }

    @Override
    public ProjectIndexSnapshot load() {
        if (!Files.isReadable(cachePath)) return ProjectIndexSnapshot.empty();
        try {
            ProjectIndexSnapshot parsed = JsonUtils.getMapper()
                .readValue(Files.readString(cachePath), ProjectIndexSnapshot.class);
            if (parsed == null || parsed.version() != ProjectIndexSnapshot.CURRENT_VERSION) {
                LOG.debug("Project index cache version mismatch, rebuilding empty");
                return ProjectIndexSnapshot.empty();
            }
            return normalize(parsed);
        } catch (Exception e) {
            LOG.debug("Failed to load project index cache: {}", e.getMessage());
            return ProjectIndexSnapshot.empty();
        }
    }

    /** Nulls from older/foreign caches → empty collections. */
    private static ProjectIndexSnapshot normalize(ProjectIndexSnapshot s) {
        List<ProjectIndexSnapshot.CachedDir> dirs = s.dirs() == null
            ? List.of()
            : s.dirs().stream().map(FileProjectIndexStore::normalizeDir).toList();
        return new ProjectIndexSnapshot(
            ProjectIndexSnapshot.CURRENT_VERSION,
            dirs,
            s.pinnedProjects() != null ? List.copyOf(s.pinnedProjects()) : List.of(),
            s.collapsedProjects() != null ? Map.copyOf(s.collapsedProjects()) : Map.of());
    }

    private static ProjectIndexSnapshot.CachedDir normalizeDir(
            ProjectIndexSnapshot.CachedDir d) {
        if (d.sessions() != null) return d;
        return new ProjectIndexSnapshot.CachedDir(
            d.dirName(), d.fileCount(), d.maxFileMtimeMs(), List.of());
    }

    @Override
    public void save(ProjectIndexSnapshot snapshot) {
        try {
            // Compact, not pretty: nobody reads this by hand, and on a large
            // history the indentation is a third of the bytes written on a path
            // that runs every time the drawer opens.
            byte[] data = JsonUtils.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
            FileUtils.atomicReplace(cachePath, tempPath -> {
                try (var channel = FileChannel.open(tempPath,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    FileUtils.writeFully(channel, ByteBuffer.wrap(data));
                    channel.force(true);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Failed to save project index cache: {}", e.getMessage());
        }
    }

    @Override
    public void invalidate(String dirName) {
        ProjectIndexSnapshot current = load();
        List<ProjectIndexSnapshot.CachedDir> kept = current.dirs().stream()
            .filter(d -> !d.dirName().equals(dirName))
            .toList();
        if (kept.size() == current.dirs().size()) return;   // no-op: no file created
        save(new ProjectIndexSnapshot(ProjectIndexSnapshot.CURRENT_VERSION, kept,
            current.pinnedProjects(), current.collapsedProjects()));
    }
}
