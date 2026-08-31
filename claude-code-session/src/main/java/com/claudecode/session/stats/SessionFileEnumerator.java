package com.claudecode.session.stats;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Enumerates every session transcript on disk, across <em>all</em> project
 * directories — the shared data-discovery layer under both {@code /stats}
 * (via {@link StatsAggregator}) and {@code /insights}.
 *
 * <p>Reads the supported {@code ~/.claude/projects/} layout:
 * <pre>
 *   main transcripts
 *   subagent transcripts
 * </pre>
 * Main files are listed before that project's subagent files, matching the compatibility
 * ordering — {@link StatsAggregator} relies on parents preceding subagents so
 * a subagent's tool calls land on an already-created day row.
 *
 * <ul>
 *   <li>{@code getAllSessionFiles}
 *       (cross-project main + subagent enumeration) and
 *       {@code readSessionStartDate} (4&nbsp;KB head peek).</li>
 *   <li>{@code getProjectsDir}
 *       (the default-directory resolution).</li>
 * </ul>
 */
public final class SessionFileEnumerator {

    private static final Logger LOG = LoggerFactory.getLogger(SessionFileEnumerator.class);


    private static final Set<String> PEEK_TRANSCRIPT_TYPES =
        Set.of("user", "assistant", "attachment", "system", "progress");

    private static final int HEAD_PEEK_BYTES = 4096;

    private final Path projectsDir;

    /** Production wiring: {@code ~/.claude/projects} (env-overridable via CLAUDE_CONFIG_DIR). */
    public SessionFileEnumerator() {
        this(ClaudePaths.PROJECTS_DIR);
    }

    /** Explicit directory (tests). */
    public SessionFileEnumerator(Path projectsDir) {
        this.projectsDir = projectsDir;
    }

    /**
     * All session transcripts across every project: main plus {@code onl}.
     */
    public List<Path> listAllSessionFiles() {
        List<Path> projectDirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(projectsDir, Files::isDirectory)) {
            ds.forEach(projectDirs::add);
        } catch (IOException _) {
            return List.of();
        }

        List<Path> all = new ArrayList<>();
        for (Path projectDir : projectDirs) {
            try {
                List<Path> mainFiles = new ArrayList<>();
                List<Path> sessionDirs = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(projectDir)) {
                    for (Path entry : ds) {
                        if (Files.isRegularFile(entry) && Strings.CS.endsWith(entry.getFileName().toString(), ".jsonl")) {
                            mainFiles.add(entry);
                        } else if (Files.isDirectory(entry)) {
                            sessionDirs.add(entry);
                        }
                    }
                }
                all.addAll(mainFiles);
                for (Path sessionDir : sessionDirs) {
                    Path subagentsDir = sessionDir.resolve("subagents");
                    if (!Files.isDirectory(subagentsDir)) continue;
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(subagentsDir,
                            p -> Files.isRegularFile(p)
                                && Strings.CS.startsWith(p.getFileName().toString(), "agent-")
                                && Strings.CS.endsWith(p.getFileName().toString(), ".jsonl"))) {
                        ds.forEach(all::add);
                    } catch (IOException _) {
                        // subagents dir vanished mid-walk — skip
                    }
                }
            } catch (IOException e) {
                LOG.debug("Failed to read project directory {}: {}", projectDir, e.getMessage());
            }
        }
        return all;
    }


    public static boolean isSubagentFile(Path sessionFile) {
        Path parent = sessionFile.getParent();
        return parent != null && Strings.CS.equals("subagents", parent.getFileName().toString());
    }

    /**
     * Peeks at the first {@value #HEAD_PEEK_BYTES} bytes of a transcript to
     * find the session's start date (UTC {@code YYYY-MM-DD}) without reading
     * the whole file. Only complete lines are trusted; non-transcript prefix
     * entries ({@code last-prompt}, {@code mode}, {@code file-history-snapshot}
     * — whose nested {@code snapshot.timestamp} carries the <em>previous</em>
     * session's date and must not be string-matched) and sidechain messages are
     * skipped. Returns null when no transcript message fits in the head —
     * callers fall through to a full read (safe default).
     */
    public static String readSessionStartDate(Path filePath) {
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            byte[] buf = new byte[HEAD_PEEK_BYTES];
            int bytesRead = raf.read(buf, 0, buf.length);
            if (bytesRead <= 0) return null;
            String head = new String(buf, 0, bytesRead, StandardCharsets.UTF_8);

            int lastNewline = head.lastIndexOf('\n');
            if (lastNewline < 0) return null;

            for (String line : head.substring(0, lastNewline).split("\n")) {
                if (line.isEmpty()) continue;
                JsonNode entry;
                try {
                    entry = JsonUtils.getMapper().readTree(line);
                } catch (Exception _) {
                    continue;
                }
                if (entry == null || !entry.isObject()) continue;
                String type = entry.path("type").asText(null);
                if (type == null || !PEEK_TRANSCRIPT_TYPES.contains(type)) continue;
                if (entry.path("isSidechain").asBoolean(false)) continue;
                JsonNode ts = entry.get("timestamp");
                if (ts == null || !ts.isTextual()) return null;
                Instant instant = StatsDates.parseFlexible(ts.asText());
                if (instant == null) return null;
                return StatsDates.toDateString(instant);
            }
            return null;
        } catch (IOException _) {
            return null;
        }
    }
}
