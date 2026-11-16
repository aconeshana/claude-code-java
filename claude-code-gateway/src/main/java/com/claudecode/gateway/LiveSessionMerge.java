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
 * <p>The same applies to a live session that <em>does</em> have a catalog row: the catalog's copy
 * is a snapshot taken when the transcript was globbed, so its {@code message_count} freezes while
 * the session keeps growing. {@link #reconcile} overlays the live counts onto that row.
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
        // A live session that already has a catalog row must still take the live row's counts:
        // the catalog reads the transcript once, so its message_count stops advancing the moment
        // the session keeps growing in a process the catalog cannot see. Overlay first, or the
        // freshly-lived numbers are discarded along with the duplicate.
        Map<String, SessionEntry> liveById = indexLive(live);
        List<ProjectEntry> catalog = reconcile(catalogProjects, liveById);
        Map<String, List<SessionEntry>> pending = groupByProject(catalog, live, canonicalize);
        if (pending.isEmpty()) return catalog;

        List<ProjectEntry> absorbed = new ArrayList<>(catalog.size());
        for (ProjectEntry project : catalog) {
            List<SessionEntry> fresh = pending.remove(project.projectPath());
            absorbed.add(fresh == null ? project : absorb(project, fresh));
        }

        List<ProjectEntry> merged = new ArrayList<>(absorbed.size() + pending.size());
        pending.forEach((projectPath, rows) -> merged.add(synthesize(projectPath, rows)));
        merged.addAll(absorbed);
        return List.copyOf(merged);
    }

    private static Map<String, SessionEntry> indexLive(List<SessionHostInfo> live) {
        Map<String, SessionEntry> byId = new LinkedHashMap<>();
        for (SessionHostInfo info : live) {
            if (StringUtils.isBlank(info.id())) continue;
            byId.put(info.id(), toEntry(info));
        }
        return byId;
    }

    /**
     * Replaces each catalog row whose session is live with the live row, keeping the catalog's
     * custom title and first prompt — those are not in {@link SessionHostInfo}, and dropping them
     * would make a running session lose its name in the picker while it runs.
     */
    private static List<ProjectEntry> reconcile(
            List<ProjectEntry> catalogProjects, Map<String, SessionEntry> liveById) {
        if (liveById.isEmpty()) return catalogProjects;
        List<ProjectEntry> reconciled = new ArrayList<>(catalogProjects.size());
        for (ProjectEntry project : catalogProjects) {
            List<SessionEntry> rows = new ArrayList<>(project.sessions().size());
            boolean changed = false;
            for (SessionEntry row : project.sessions()) {
                SessionEntry live = liveById.get(row.id());
                if (live == null) {
                    rows.add(row);
                    continue;
                }
                changed = true;
                rows.add(new SessionEntry(
                    row.id(),
                    row.summary(),
                    live.messageCount(),
                    live.lastModifiedMs(),
                    row.gitBranch(),
                    row.cwd(),
                    row.customTitle(),
                    row.firstPrompt()));
            }
            if (!changed) {
                reconciled.add(project);
                continue;
            }
            reconciled.add(new ProjectEntry(project.projectPath(), project.projectName(),
                project.sessionCount(), Math.max(project.lastActivityMs(),
                    lastActivityOf(rows)), rows));
        }
        return reconciled;
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
