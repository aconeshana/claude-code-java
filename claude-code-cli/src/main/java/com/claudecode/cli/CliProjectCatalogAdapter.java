package com.claudecode.cli;

import com.claudecode.session.FileProjectIndexStore;
import com.claudecode.session.ProjectCatalog;
import com.claudecode.session.ProjectInfo;
import com.claudecode.session.ProjectSessionRef;
import com.claudecode.session.SessionInfo;
import com.claudecode.session.SessionManager;
import com.claudecode.ui.lanterna.repl.ProjectCatalogPort;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * CLI leaf adapter from the UI-owned project-catalog boundary to the session
 * module's {@link ProjectCatalog} (fingerprint-validated, file-persisted
 * project→session aggregation). A Java-side extension with no 197 counterpart.
 *
 * <p>The catalog instance is long-lived: its in-memory layer is revalidated by
 * per-directory fingerprints on every {@link #listProjects()} call, so reuse is
 * both safe and cheaper than rebuilding (the persisted store alone would still
 * serve unchanged directories, but through a disk read).
 */
public final class CliProjectCatalogAdapter implements ProjectCatalogPort {
    private final ProjectCatalog catalog;

    /** Production wiring: current-process cwd + the default cache location. */
    public CliProjectCatalogAdapter(Predicate<String> builtInCommand) {
        this(new ProjectCatalog(new SessionManager(System.getProperty("user.dir")),
            new FileProjectIndexStore(), builtInCommand));
    }

    /** Test seam: inject a catalog rooted at a fixture base directory. */
    CliProjectCatalogAdapter(ProjectCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    @Override
    public List<ProjectEntry> listProjects() {
        return catalog.listProjects().stream()
            .map(CliProjectCatalogAdapter::entry).toList();
    }

    @Override
    public ProjectPreferences projectPreferences() {
        ProjectCatalog.ProjectPreferences prefs = catalog.preferences();
        return new ProjectPreferences(prefs.pinnedProjects(), prefs.collapsedProjects());
    }

    @Override
    public void updateProjectPreferences(List<String> pinnedProjects,
                                         Map<String, Boolean> collapsedProjects) {
        catalog.updatePreferences(pinnedProjects, collapsedProjects);
    }

    private static ProjectEntry entry(ProjectInfo info) {
        return new ProjectEntry(info.projectPath(), info.projectName(), info.sessionCount(),
            info.lastActivityMs(),
            info.sessions().stream().map(CliProjectCatalogAdapter::sessionEntry).toList());
    }

    private static ProjectSessionEntry sessionEntry(ProjectSessionRef ref) {
        SessionInfo info = ref.info();
        return new ProjectSessionEntry(info.id(), info.lastModified(), info.createdAt(),
            info.messageCount(), info.summary(), info.gitBranch(), info.cwd(), info.tag(),
            info.customTitle(), info.firstPrompt(), info.fileSize(), ref.transcriptPath());
    }
}
