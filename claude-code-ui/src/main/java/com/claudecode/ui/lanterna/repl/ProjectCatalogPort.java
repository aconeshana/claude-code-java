package com.claudecode.ui.lanterna.repl;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Consumer-owned boundary for the project-management panel's data: a two-level
 * project→session listing plus panel preferences. A Java-side extension with no
 * 197 counterpart (released 2.1.197 keeps projects implicit and never groups
 * sessions by project). UI code must not depend on claude-code-session
 * implementations — the CLI composition root injects the adapter, mirroring
 * {@link InteractiveSessionPort}.
 *
 * <p>Delete and transcript reads stay on {@link InteractiveSessionPort}; this
 * port is deliberately limited to the project aggregation seam.
 */
public interface ProjectCatalogPort {

    /** Panel-facing projection of one session (with its physical transcript path). */
    record ProjectSessionEntry(
        String id,
        long lastModified,
        Instant createdAt,
        int messageCount,
        String summary,
        String gitBranch,
        String cwd,
        String tag,
        String customTitle,
        String firstPrompt,
        long fileSize,
        Path transcriptPath
    ) {}

    /** One project row: aggregate plus its sessions (lastModified descending). */
    record ProjectEntry(
        String projectPath,
        String projectName,
        int sessionCount,
        long lastActivityMs,
        List<ProjectSessionEntry> sessions
    ) {
        public ProjectEntry {
            sessions = sessions != null ? List.copyOf(sessions) : List.of();
        }
    }

    /** Persisted panel preferences (pinned order and collapse state). */
    record ProjectPreferences(
        List<String> pinnedProjects,
        Map<String, Boolean> collapsedProjects
    ) {
        public ProjectPreferences {
            pinnedProjects = pinnedProjects != null ? List.copyOf(pinnedProjects) : List.of();
            collapsedProjects = collapsedProjects != null
                ? Map.copyOf(collapsedProjects) : Map.of();
        }

        public static ProjectPreferences empty() {
            return new ProjectPreferences(List.of(), Map.of());
        }
    }

    /**
     * Fingerprint-validated project listing; may block on a stat pass plus lite
     * transcript reads for stale directories — call off the GUI thread.
     */
    default List<ProjectEntry> listProjects() { return List.of(); }

    /**
     * The cached listing with no revalidation — possibly stale, always cheap.
     * The drawer paints this first so the user sees content immediately, then
     * refreshes with {@link #listProjects()}.
     */
    default List<ProjectEntry> cachedProjects() { return List.of(); }

    /**
     * Rebuilds the cache without producing a listing. Called once after startup
     * so the first open does not pay for a cold index — must run off the GUI
     * thread like {@link #listProjects()}.
     */
    default void warmUp() {}

    default ProjectPreferences projectPreferences() { return ProjectPreferences.empty(); }

    default void updateProjectPreferences(List<String> pinnedProjects,
                                          Map<String, Boolean> collapsedProjects) {}

    static ProjectCatalogPort none() { return new ProjectCatalogPort() {}; }
}
