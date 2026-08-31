package com.claudecode.services.insights;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.util.UuidUtils;
import com.claudecode.core.serialization.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Filesystem layer of {@code /insights}: discovers session transcripts across every project
 * directory and reads/writes the per-session JSON caches under {@code ~/.claude/usage-data/} (, .
 */
public final class InsightsStore {

    private static final Logger LOG = LoggerFactory.getLogger(InsightsStore.class);


    private static final Set<PosixFilePermission> OWNER_RW =
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path dataDir;
    private final Path projectsDir;

    /** Production wiring: {@code ~/.claude/usage-data} + {@code ~/.claude/projects}. */
    public InsightsStore() {
        this(ClaudePaths.CLAUDE_HOME.resolve("usage-data"),
             ClaudePaths.CLAUDE_HOME.resolve("projects"));
    }

    /** Explicit roots (tests). */
    public InsightsStore(Path dataDir, Path projectsDir) {
        this.dataDir = dataDir;
        this.projectsDir = projectsDir;
    }


    public record LiteSessionInfo(String sessionId, Path path, long mtime, long size) {}



    public Path dataDir() {
        return dataDir;
    }

    public Path facetsDir() {
        return dataDir.resolve("facets");
    }

    public Path sessionMetaDir() {
        return dataDir.resolve("session-meta");
    }



    /**
     * Scans every project directory for  transcripts using
     * filesystem metadata only. Missing projects dir yields an empty list;
     * unreadable project dirs and un-statable files are skipped. Sorted by
     * mtime descending (most recent first).
     */
    public List<LiteSessionInfo> scanAllSessions() {
        List<Path> projectDirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(projectsDir, Files::isDirectory)) {
            ds.forEach(projectDirs::add);
        } catch (IOException _) {
            return List.of();
        }

        List<LiteSessionInfo> allSessions = new ArrayList<>();
        for (Path projectDir : projectDirs) {
            collectSessionFiles(projectDir, allSessions);
        }
        allSessions.sort(Comparator.comparingLong(LiteSessionInfo::mtime).reversed());
        return allSessions;
    }


    private static void collectSessionFiles(Path projectDir, List<LiteSessionInfo> out) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(projectDir, Files::isRegularFile)) {
            for (Path file : ds) {
                String name = file.getFileName().toString();
                if (!Strings.CS.endsWith(name, ".jsonl")) continue;
                String sessionId = name.substring(0, name.length() - ".jsonl".length());
                if (!UuidUtils.isValid(sessionId)) continue;
                try {
                    BasicFileAttributes attrs =
                        Files.readAttributes(file, BasicFileAttributes.class);
                    out.add(new LiteSessionInfo(
                        sessionId, file, attrs.lastModifiedTime().toMillis(), attrs.size()));
                } catch (IOException _) {
                    LOG.debug("Failed to stat session file: {}", file);
                }
            }
        } catch (IOException _) {

        }
    }



    /** Cached {@link SessionMeta} for {@code sessionId}, or null when absent/unreadable. */
    public SessionMeta loadCachedSessionMeta(String sessionId) {
        Path metaPath = sessionMetaDir().resolve(sessionId + ".json");
        try {
            return JsonUtils.getMapper()
                .readValue(Files.readString(metaPath, StandardCharsets.UTF_8), SessionMeta.class);
        } catch (Exception _) {
            return null;
        }
    }

/**
     * Persists {@code meta} to; failures are silent.
     */
    public void saveSessionMeta(SessionMeta meta) {
        writeCacheFile(sessionMetaDir(), meta.sessionId(), meta);
    }



    /**
     * Cached {@link SessionFacets} for {@code sessionId}, or null when absent, unreadable, or invalid.
     */
    public SessionFacets loadCachedFacets(String sessionId) {
        Path facetPath = facetsDir().resolve(sessionId + ".json");
        SessionFacets parsed;
        try {
            parsed = JsonUtils.getMapper()
                .readValue(Files.readString(facetPath, StandardCharsets.UTF_8), SessionFacets.class);
        } catch (Exception _) {
            return null;
        }
        if (parsed == null || !parsed.isValid()) {
            try {
                Files.deleteIfExists(facetPath);
            } catch (IOException _) {

            }
            return null;
        }
        return parsed;
    }

/**
     * Persists {@code facets} to; failures are silent.
     */
    public void saveFacets(SessionFacets facets) {
        writeCacheFile(facetsDir(), facets.sessionId(), facets);
    }

    // ── shared write path ────────────────────────────────────────────────────

    private static void writeCacheFile(Path dir, String sessionId, Object value) {
        if (StringUtils.isEmpty(sessionId)) {
            LOG.debug("Skipping cache write without a session id under {}", dir);
            return;
        }
        Path target = dir.resolve(sessionId + ".json");
        try {
            Files.createDirectories(dir);
            String json = JsonUtils.toPrettyJson(value);
            Files.writeString(target, json, StandardCharsets.UTF_8);
            restrictToOwner(target);
        } catch (IOException e) {
            LOG.debug("Failed to write cache file {}: {}", target, e.getMessage());
        }
    }


    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_RW);
        } catch (UnsupportedOperationException | IOException _) {
            // Non-POSIX filesystem or permission race — keep the write
        }
    }
}
