package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.gateway.GatewaySessionCatalogPort.ProjectEntry;
import com.claudecode.gateway.GatewaySessionCatalogPort.SessionEntry;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.apache.commons.lang3.StringUtils;

/**
 * Folds the live sessions into the filesystem-backed catalog listing.
 *
 * <p>The catalog discovers sessions by globbing transcripts, and a transcript is only written on
 * the first append. A session that was just created — the TUI's own session at startup, or one the
 * web client just opened — therefore has no catalog row at all, so the sessions endpoint has
 * nowhere to stamp {@code active} or {@code headless_open} and the client ends up selecting an
 * arbitrary historical session instead of the one the user is looking at.
 *
 * <p>Rows synthesized here carry only what the registry knows. They are deliberately not enriched:
 * {@code customTitle} and {@code firstPrompt} live in the transcript that does not exist yet.
 *
 * @see GatewayServer#sessionsBody(int)
 */
@Explanation("The gateway's session listing must include sessions that have no transcript yet")
final class LiveSessionMerge {

    private LiveSessionMerge() {}

    /**
     * Returns {@code catalogProjects} with every live session that has no catalog row folded in.
     *
     * <p>A live session whose directory already has a project row is prepended to that project's
     * rows; the rest are grouped into synthesized projects placed ahead of the catalog's own, since
     * a live session is by definition the most recently active one.
     *
     * <p>Only the live directories are passed through {@code canonicalize}: the catalog's project
     * paths are already canonical, and the operator may touch the filesystem, so running it over a
     * long project history would cost a syscall per project for no gain.
     *
     * @param canonicalize maps a working directory onto the catalog's project-path form; the
     *                     identity operator is correct when the catalog is not canonicalizing
     *                     either
     */
    static List<ProjectEntry> merge(
            List<ProjectEntry> catalogProjects,
            List<SessionHostInfo> live,
            UnaryOperator<String> canonicalize) {
        Map<String, List<SessionEntry>> pending = groupByProject(catalogProjects, live, canonicalize);
        if (pending.isEmpty()) return catalogProjects;

        List<ProjectEntry> absorbed = new ArrayList<>(catalogProjects.size());
        for (ProjectEntry project : catalogProjects) {
            List<SessionEntry> fresh = pending.remove(project.projectPath());
            absorbed.add(fresh == null ? project : absorb(project, fresh));
        }

        List<ProjectEntry> merged = new ArrayList<>(absorbed.size() + pending.size());
        pending.forEach((projectPath, rows) -> merged.add(synthesize(projectPath, rows)));
        merged.addAll(absorbed);
        return List.copyOf(merged);
    }

    /** Live sessions with no catalog row, keyed by their canonical project path. */
    private static Map<String, List<SessionEntry>> groupByProject(
            List<ProjectEntry> catalogProjects,
            List<SessionHostInfo> live,
            UnaryOperator<String> canonicalize) {
        Set<String> known = new HashSet<>();
        for (ProjectEntry project : catalogProjects) {
            for (SessionEntry session : project.sessions()) known.add(session.id());
        }

        Map<String, List<SessionEntry>> pending = new LinkedHashMap<>();
        for (SessionHostInfo info : live) {
            // A blank working directory cannot be grouped: every such row would collapse into one
            // bogus project whose path is the empty string.
            if (StringUtils.isBlank(info.id()) || StringUtils.isBlank(info.workDir())) continue;
            if (!known.add(info.id())) continue;
            pending.computeIfAbsent(canonicalize.apply(info.workDir()), _ -> new ArrayList<>())
                .add(toEntry(info));
        }
        return pending;
    }

    private static SessionEntry toEntry(SessionHostInfo info) {
        return new SessionEntry(
            info.id(),
            info.summary(),
            Math.max(0, info.messageCount()),
            info.modifiedAt() == null ? 0L : info.modifiedAt().toEpochMilli(),
            info.gitBranch(),
            info.workDir(),
            null,
            null);
    }

    private static ProjectEntry absorb(ProjectEntry project, List<SessionEntry> fresh) {
        List<SessionEntry> sessions = new ArrayList<>(fresh.size() + project.sessions().size());
        sessions.addAll(fresh);
        sessions.addAll(project.sessions());
        return new ProjectEntry(
            project.projectPath(),
            project.projectName(),
            project.sessionCount() + fresh.size(),
            Math.max(project.lastActivityMs(), lastActivityOf(fresh)),
            sessions);
    }

    private static ProjectEntry synthesize(String projectPath, List<SessionEntry> rows) {
        return new ProjectEntry(
            projectPath, projectName(projectPath), rows.size(), lastActivityOf(rows), rows);
    }

    private static long lastActivityOf(List<SessionEntry> rows) {
        long latest = 0L;
        for (SessionEntry row : rows) latest = Math.max(latest, row.lastModifiedMs());
        return latest;
    }

    /** The catalog labels a project by its directory name; a synthesized row must match. */
    private static String projectName(String projectPath) {
        try {
            Path name = Path.of(projectPath).getFileName();
            return name == null ? projectPath : name.toString();
        } catch (InvalidPathException _) {
            return projectPath;
        }
    }
}
