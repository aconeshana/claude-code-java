package com.claudecode.session;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Aggregated view of one project directory's sessions for the project-management
 * panel — a Java-side extension with no 197 counterpart: released 2.1.197 keeps
 * "project" implicit (a {@code sanitizePath(cwd)} directory under {@code projects/},
 * see {@code src/utils/sessionStorage.ts#getProjectDir}) and never groups sessions
 * by project. Project identity here follows Wake's read-only claude adapter: the
 * jsonl {@code cwd} field is the single source of truth because sanitizePath is
 * not reversible ({@code /}, {@code _}, {@code .} all collapse to {@code -}).
 *
 * @param projectPath   real working directory read from session jsonl content
 * @param projectName   display name — last path segment ({@link #nameOf})
 * @param sessionCount  total sessions in the project (may exceed {@code sessions.size()}
 *                      when the list is a page)
 * @param lastActivityMs max session mtime, epoch millis
 * @param sessions      sessions (with their physical transcript paths) sorted by
 *                      lastModified descending
 */
public record ProjectInfo(
    String projectPath,
    String projectName,
    int sessionCount,
    long lastActivityMs,
    List<ProjectSessionRef> sessions
) {
    public ProjectInfo {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }

    /**
     * Display name for a project path: the last segment, tolerating both
     * separators and a trailing separator; blank/root → "Unknown project"
     * (Wake {@code project_name_of} parity).
     */
    public static String nameOf(String projectPath) {
        if (StringUtils.isBlank(projectPath)) return "Unknown project";
        int end = projectPath.length();
        while (end > 0 && (projectPath.charAt(end - 1) == '/'
                || projectPath.charAt(end - 1) == '\\')) {
            end--;
        }
        if (end == 0) return "Unknown project";
        int start = Math.max(projectPath.lastIndexOf('/', end - 1),
            projectPath.lastIndexOf('\\', end - 1)) + 1;
        String name = projectPath.substring(start, end);
        return name.isEmpty() ? "Unknown project" : name;
    }
}
