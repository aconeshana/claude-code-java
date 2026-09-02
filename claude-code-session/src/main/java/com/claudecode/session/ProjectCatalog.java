package com.claudecode.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Groups all on-disk sessions into per-project aggregates for the project-management
 * panel — a Java-side extension with no 197 counterpart (released 2.1.197 lists
 * sessions only per current project + worktrees, or flat across all projects; it
 * never builds a project→session grouping). Project identity follows the same rule
 * as {@link SessionCatalog}'s enrichment and Wake's claude adapter: the transcript's
 * content {@code cwd} ({@code relocatedCwd} wins), because the sanitized directory
 * name is not reversible.
 *
 * <p>Revalidation is per transcript directory: a fingerprint of file count + newest
 * mtime is compared against the {@link ProjectIndexStore} cache (and this instance's
 * memory), so unchanged directories are served without re-reading transcripts while
 * appends/additions/deletions — ours or an external tool's — trigger a rescan of
 * exactly the affected directory. User preferences (pinned/collapsed) persist in
 * the same store and survive cache rebuilds.
 */
public final class ProjectCatalog {

    /** Panel-level user preferences, persisted alongside the cache. */
    public record ProjectPreferences(
        List<String> pinnedProjects,
        Map<String, Boolean> collapsedProjects
    ) {
        public ProjectPreferences {
            pinnedProjects = pinnedProjects != null ? List.copyOf(pinnedProjects) : List.of();
            collapsedProjects = collapsedProjects != null
                ? Map.copyOf(collapsedProjects) : Map.of();
        }
    }

    private record DirFingerprint(int fileCount, long maxMtimeMs) {}

    private record DirState(DirFingerprint fingerprint, List<ProjectSessionRef> sessions) {}

    private final SessionManager manager;
    private final ProjectIndexStore store;
    private final Predicate<String> builtInCommand;

    private final Map<String, DirState> memory = new LinkedHashMap<>();
    private List<String> pinned = List.of();
    private Map<String, Boolean> collapsed = Map.of();
    private boolean loaded;
    private boolean dirty;

    public ProjectCatalog(SessionManager manager, ProjectIndexStore store) {
        this(manager, store, _ -> false);
    }

    /** Production wiring: the commands module supplies the real builtin predicate. */
    public ProjectCatalog(SessionManager manager, ProjectIndexStore store,
                          Predicate<String> builtInCommand) {
        this.manager = Objects.requireNonNull(manager);
        this.store = Objects.requireNonNull(store);
        this.builtInCommand = builtInCommand != null ? builtInCommand : _ -> false;
    }

    public synchronized ProjectPreferences preferences() {
        ensureLoaded();
        return new ProjectPreferences(pinned, collapsed);
    }

    public synchronized void updatePreferences(List<String> pinnedProjects,
                                               Map<String, Boolean> collapsedProjects) {
        ensureLoaded();
        pinned = pinnedProjects != null ? List.copyOf(pinnedProjects) : List.of();
        collapsed = collapsedProjects != null ? Map.copyOf(collapsedProjects) : Map.of();
        dirty = true;
        persist();
    }

    /**
     * Fingerprint-validated project listing: unchanged directories are served from
     * the persisted cache without transcript reads; stale or new directories are
     * rescanned (stat-pass + lite head/tail enrich, same pipeline as
     * {@link SessionCatalog#forAllProjects}).
     */
    public synchronized List<ProjectInfo> listProjects() {
        ensureLoaded();

        // One stat pass over every transcript directory yields both the live
        // fingerprints and the rescan input for stale dirs.
        Map<String, List<SessionCatalog.Candidate>> perDir = new LinkedHashMap<>();
        Map<String, DirFingerprint> live = new LinkedHashMap<>();
        for (SessionCatalog.Source source : SessionCatalog.allProjectSources(manager)) {
            String dirName = source.directory().getFileName().toString();
            List<SessionCatalog.Candidate> candidates = SessionCatalog.candidates(List.of(source));
            perDir.put(dirName, candidates);
            live.put(dirName, fingerprint(candidates));
        }

        memory.entrySet().removeIf(e -> {
            DirFingerprint current = live.get(e.getKey());
            return current == null || !current.equals(e.getValue().fingerprint());
        });
        final boolean[] rescanned = {false};
        perDir.forEach((dirName, candidates) -> {
            if (memory.containsKey(dirName)) return;
            List<ProjectSessionRef> sessions = scan(candidates);
            if (!sessions.isEmpty()) {
                memory.put(dirName, new DirState(live.get(dirName), sessions));
                rescanned[0] = true;
            }
        });
        if (rescanned[0]) {
            dirty = true;
            persist();
        }

        // Cross-dir merge: a session id may appear in several directories
        // (aliases/copies) — keep the newest — then group by content cwd.
        Map<String, ProjectSessionRef> deduped = new LinkedHashMap<>();
        memory.values().stream()
            .flatMap(state -> state.sessions().stream())
            .sorted(Comparator.comparingLong(
                (ProjectSessionRef ref) -> ref.info().lastModified()).reversed())
            .forEach(ref -> deduped.putIfAbsent(ref.info().id(), ref));

        Map<String, List<ProjectSessionRef>> byProject = new LinkedHashMap<>();
        for (ProjectSessionRef ref : deduped.values()) {
            String key = SessionManager.canonicalizePath(ref.info().cwd());
            byProject.computeIfAbsent(key, _ -> new ArrayList<>()).add(ref);
        }
        return byProject.entrySet().stream()
            .map(e -> {
                List<ProjectSessionRef> sessions = e.getValue().stream()
                    .sorted(Comparator.comparingLong(
                        (ProjectSessionRef ref) -> ref.info().lastModified()).reversed())
                    .toList();
                return new ProjectInfo(e.getKey(), ProjectInfo.nameOf(e.getKey()),
                    sessions.size(), sessions.getFirst().info().lastModified(), sessions);
            })
            .sorted(Comparator.comparingLong(ProjectInfo::lastActivityMs).reversed())
            .toList();
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        ProjectIndexSnapshot snapshot = store.load();
        for (ProjectIndexSnapshot.CachedDir dir : snapshot.dirs()) {
            List<ProjectSessionRef> sessions = dir.sessions().stream()
                .map(cs -> new ProjectSessionRef(toSessionInfo(cs),
                    manager.projectsRoot().resolve(dir.dirName()).resolve(cs.id() + ".jsonl")))
                .toList();
            if (!sessions.isEmpty()) {
                memory.put(dir.dirName(),
                    new DirState(new DirFingerprint(dir.fileCount(), dir.maxFileMtimeMs()),
                        sessions));
            }
        }
        pinned = snapshot.pinnedProjects();
        collapsed = snapshot.collapsedProjects();
    }

    private void persist() {
        if (!dirty) return;
        dirty = false;
        List<ProjectIndexSnapshot.CachedDir> dirs = memory.entrySet().stream()
            .map(e -> new ProjectIndexSnapshot.CachedDir(e.getKey(),
                e.getValue().fingerprint().fileCount(),
                e.getValue().fingerprint().maxMtimeMs(),
                e.getValue().sessions().stream()
                    .map(ref -> toCachedSession(ref.info())).toList()))
            .toList();
        store.save(new ProjectIndexSnapshot(ProjectIndexSnapshot.CURRENT_VERSION,
            dirs, pinned, collapsed));
    }

    private static DirFingerprint fingerprint(List<SessionCatalog.Candidate> candidates) {
        long max = 0;
        for (SessionCatalog.Candidate candidate : candidates) {
            max = Math.max(max, candidate.mtime());
        }
        return new DirFingerprint(candidates.size(), max);
    }

    private List<ProjectSessionRef> scan(List<SessionCatalog.Candidate> candidates) {
        List<ProjectSessionRef> sessions = new ArrayList<>(candidates.size());
        for (SessionCatalog.Candidate candidate : candidates) {
            SessionCatalog.read(candidate)
                .flatMap(lite -> SessionCatalog.enrich(candidate, lite, builtInCommand,
                    SessionCatalog.Visibility.PICKER))
                .ifPresent(entry -> sessions.add(
                    new ProjectSessionRef(entry.info(), entry.transcript())));
        }
        return List.copyOf(sessions);
    }

    private static SessionInfo toSessionInfo(ProjectIndexSnapshot.CachedSession cs) {
        return new SessionInfo(cs.id(), cs.lastModifiedMs(),
            cs.createdAtMs() > 0 ? Instant.ofEpochMilli(cs.createdAtMs()) : null,
            cs.messageCount(), cs.summary(), cs.gitBranch(), cs.cwd(), cs.tag(),
            cs.fileSize(), cs.customTitle(), cs.firstPrompt());
    }

    private static ProjectIndexSnapshot.CachedSession toCachedSession(SessionInfo s) {
        return new ProjectIndexSnapshot.CachedSession(s.id(), s.cwd(), s.lastModified(),
            s.createdAt() != null ? s.createdAt().toEpochMilli() : 0,
            s.messageCount(), s.summary(), s.gitBranch(), s.tag(),
            s.customTitle(), s.firstPrompt(), s.fileSize());
    }
}
