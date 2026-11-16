package com.claudecode.gateway;

import com.claudecode.session.FileProjectIndexStore;
import com.claudecode.session.ProjectCatalog;
import com.claudecode.session.ProjectSessionRef;
import com.claudecode.session.SessionInfo;
import com.claudecode.session.SessionManager;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Serves the session listing from the configuration home's transcripts when the
 * composition root wires no catalog of its own.
 *
 * <p>The standalone {@code claude web} gateway runs no engine, and its
 * composition root hands it an empty registry and an empty catalog port. The
 * listing therefore fell through to live sessions only — the active TUI session
 * and every open headless session — and a session that existed purely on disk
 * (a fork, a row whose TUI had exited) could never appear, no matter how many
 * turns it held. The same is true of the metadata only a transcript carries:
 * a rename's {@code custom_title} and an archive's flag both live in rows this
 * process never read.
 *
 * <p>{@link ProjectCatalog} is the fingerprint-validated, disk-persisted
 * project→session aggregation the TUI's {@code /resume} picker already uses, and
 * it is revalidated on every listing call, so reusing it here costs one stat
 * pass over unchanged directories. It is built against the configuration home
 * derived from the worker directory — the same home {@link TranscriptSessionActions}
 * resolves its transcripts under — and not against this process's working
 * directory, because every session a web client addresses belongs to a TUI
 * somewhere else on the machine.
 */
final class TranscriptSessionCatalog implements GatewaySessionCatalogPort {

    private final ProjectCatalog catalog;

    /**
     * @param configurationHome the home whose {@code projects/} tree holds every
     *                          transcript, including those of sessions this
     *                          process does not host
     */
    TranscriptSessionCatalog(Path configurationHome) {
        this(configurationHome,
            new FileProjectIndexStore(
                configurationHome.resolve("cache").resolve("project-index.json")),
            // Null takes the catalog's own default (a single background virtual
            // thread), which is what the TUI's catalog uses too.
            null);
    }

    /**
     * @param store      the project index backing this listing; a home other than
     *                   the user's own needs its own file, or the catalog writes
     *                   one home's rows under another home's name
     * @param persistOn  the executor the index is flushed on ({@code Runnable::run}
     *                   keeps a test's assertions off a background thread)
     */
    TranscriptSessionCatalog(Path configurationHome, FileProjectIndexStore store,
                             Executor persistOn) {
        // The picker's visibility rules hide command-only and programmatic
        // sessions; with no command registry here, the predicate only ever sees
        // rows it should keep, so every built-in name is treated as a command.
        this(new ProjectCatalog(
            // The manager only supplies the projects root the catalog enumerates;
            // its own project directory is never consulted by a full listing.
            new SessionManager(configurationHome, null), store, null, persistOn));
    }

    /** Package-private seam: build over an already-constructed catalog. */
    TranscriptSessionCatalog(ProjectCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Installs this catalog over the configured one only when that port answers
     * nothing — i.e. the composition root wired the empty default. A port that
     * already serves a listing (the interactive runtime's own, bound to the
     * process that hosts the sessions) is left untouched.
     *
     * @param configurationHome the home whose {@code projects/} tree holds the
     *                          transcripts, normally
     *                          {@link TranscriptSessionActions#configurationHomeOf}
     */
    static GatewaySessionCatalogPort decorate(GatewaySessionCatalogPort configured,
                                              Path configurationHome) {
        return decorate(configured, configurationHome, TranscriptSessionCatalog::new);
    }

    /** Test seam: substitutes the catalog the production path would build. */
    static GatewaySessionCatalogPort decorate(GatewaySessionCatalogPort configured,
                                              Path configurationHome,
                                              Function<Path,
                                                  TranscriptSessionCatalog> build) {
        if (!configured.listProjects().isEmpty()) return configured;
        return build.apply(configurationHome);
    }

    @Override
    public List<ProjectEntry> listProjects() {
        return listProjects(Integer.MAX_VALUE);
    }

    @Override
    public List<ProjectEntry> listProjects(int perProjectLimit) {
        int limit = perProjectLimit <= 0 ? Integer.MAX_VALUE : perProjectLimit;
        return catalog.listProjects().stream()
            .map(project -> new ProjectEntry(project.projectPath(), project.projectName(),
                project.sessionCount(), project.lastActivityMs(),
                entries(project.sessions(), limit)))
            .toList();
    }

    @Override
    public String canonicalizeProjectPath(String workDir) {
        return SessionManager.canonicalizePath(workDir);
    }

    private static List<SessionEntry> entries(List<ProjectSessionRef> sessions, int limit) {
        List<ProjectSessionRef> page =
            limit >= sessions.size() ? sessions : sessions.subList(0, limit);
        return page.stream().map(ref -> {
            SessionInfo info = ref.info();
            return new SessionEntry(info.id(), info.summary(), info.messageCount(),
                info.lastModified(), info.gitBranch(), info.cwd(), info.customTitle(),
                info.firstPrompt());
        }).toList();
    }
}
