package com.claudecode.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>Three properties keep the drawer's open latency bounded on a large history
 * (thousands of directories, gigabytes of transcripts):
 * <ul>
 *   <li>the whole revalidation is one flat stat pass plus <b>one</b> batched
 *       enrichment, both running on {@link SessionCatalog}'s shared concurrent
 *       pool, rather than a per-directory loop;</li>
 *   <li>directories whose transcripts are all filtered out cache an <b>empty</b>
 *       bucket, so they are not re-read on every call;</li>
 *   <li>index writes are handed to a background executor and collapsed, so a
 *       listing never blocks on serializing the full snapshot — the live project's
 *       directory changes fingerprint on every keystroke, so this path is hit
 *       every single time the drawer opens.</li>
 * </ul>
 * {@link #cachedProjects()} exposes the cache-only view so a caller can paint
 * immediately and refresh once {@link #listProjects()} has revalidated.
 */
public final class ProjectCatalog {

    private static final Logger log = LoggerFactory.getLogger(ProjectCatalog.class);

    /** Shared writer: index writes are rare, tiny and must never block a listing. */
    private static final Executor DEFAULT_PERSIST_EXECUTOR =
        Executors.newSingleThreadExecutor(
            runnable -> Thread.ofVirtual().name("project-index-writer").unstarted(runnable));

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
    private final Executor persistExecutor;

    private final Map<String, DirState> memory = new LinkedHashMap<>();
    private final AtomicReference<ProjectIndexSnapshot> pendingWrite = new AtomicReference<>();
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
        this(manager, store, builtInCommand, DEFAULT_PERSIST_EXECUTOR);
    }

    /** Test seam: a same-thread executor makes cache writes observable synchronously. */
    public ProjectCatalog(SessionManager manager, ProjectIndexStore store,
                          Predicate<String> builtInCommand, Executor persistExecutor) {
        this.manager = Objects.requireNonNull(manager);
        this.store = Objects.requireNonNull(store);
        this.builtInCommand = builtInCommand != null ? builtInCommand : _ -> false;
        this.persistExecutor = persistExecutor != null ? persistExecutor : DEFAULT_PERSIST_EXECUTOR;
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
        // An explicit user gesture, rare and small: write it through so it cannot
        // be lost to a process exit between the click and the background flush.
        persistNow();
    }

    /**
     * Fingerprint-validated project listing: unchanged directories are served from
     * the persisted cache without transcript reads; stale or new directories are
     * rescanned in one batch (stat-pass + lite head/tail enrich, same pipeline as
     * {@link SessionCatalog#forAllProjects}).
     */
    public synchronized List<ProjectInfo> listProjects() {
        ensureLoaded();
        revalidate();
        return aggregate();
    }

    /**
     * The cache as it stands, with no stat pass and no transcript reads — possibly
     * stale, always instant. Callers that want responsiveness paint this first and
     * then refresh with {@link #listProjects()}.
     */
    public synchronized List<ProjectInfo> cachedProjects() {
        ensureLoaded();
        return aggregate();
    }

    /** Rebuilds the cache without producing a listing (startup pre-warm). */
    public synchronized void warmUp() {
        ensureLoaded();
        revalidate();
    }

    /** Drops memory/disk state whose directory fingerprint moved, then rescans it. */
    private void revalidate() {
        Map<String, List<SessionCatalog.Candidate>> perDir =
            SessionCatalog.candidatesByDirectory(SessionCatalog.allProjectSources(manager));
        Map<String, DirFingerprint> live = new LinkedHashMap<>();
        perDir.forEach((dirName, candidates) -> live.put(dirName, fingerprint(candidates)));

        // A vanished directory has no live fingerprint, so it is evicted too.
        memory.entrySet().removeIf(
            entry -> !Objects.equals(live.get(entry.getKey()), entry.getValue().fingerprint()));

        List<String> staleDirs = new ArrayList<>();
        List<SessionCatalog.Candidate> stale = new ArrayList<>();
        perDir.forEach((dirName, candidates) -> {
            if (memory.containsKey(dirName)) return;
            staleDirs.add(dirName);
            stale.addAll(candidates);
        });
        if (staleDirs.isEmpty()) return;

        Map<String, List<ProjectSessionRef>> scanned = new LinkedHashMap<>();
        for (SessionCatalog.Entry entry
                : SessionCatalog.enrichBatch(stale, builtInCommand, SessionCatalog.Visibility.PICKER)) {
            Path parent = entry.transcript().getParent();
            if (parent == null) continue;
            scanned.computeIfAbsent(parent.getFileName().toString(), _ -> new ArrayList<>())
                .add(new ProjectSessionRef(entry.info(), entry.transcript()));
        }
        for (String dirName : staleDirs) {
            // Empty buckets are cached deliberately: a directory holding only
            // sidechains, SDK or /loop sessions filters down to nothing, and
            // without this it would be re-read on every single open.
            memory.put(dirName, new DirState(live.get(dirName),
                List.copyOf(scanned.getOrDefault(dirName, List.of()))));
        }
        dirty = true;
        persistLater();
    }

    /** Cross-dir merge then cwd grouping; pure in-memory, no I/O. */
    private List<ProjectInfo> aggregate() {
        // A session id may appear in several directories (aliases/copies) — keep
        // the newest — before grouping by content cwd.
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
            memory.put(dir.dirName(),
                new DirState(new DirFingerprint(dir.fileCount(), dir.maxFileMtimeMs()), sessions));
        }
        pinned = snapshot.pinnedProjects();
        collapsed = snapshot.collapsedProjects();
    }

    private void persistNow() {
        if (!dirty) return;
        dirty = false;
        store.save(snapshot());
    }

    private void persistLater() {
        if (!dirty) return;
        dirty = false;
        pendingWrite.set(snapshot());
        persistExecutor.execute(this::flushPending);
    }

    /** Collapses write bursts: an earlier task may already have flushed this state. */
    private void flushPending() {
        ProjectIndexSnapshot snapshot = pendingWrite.getAndSet(null);
        if (snapshot == null) return;
        try {
            store.save(snapshot);
        } catch (RuntimeException failure) {
            log.debug("Project index cache write failed", failure);
        }
    }

    private ProjectIndexSnapshot snapshot() {
        List<ProjectIndexSnapshot.CachedDir> dirs = memory.entrySet().stream()
            .map(e -> new ProjectIndexSnapshot.CachedDir(e.getKey(),
                e.getValue().fingerprint().fileCount(),
                e.getValue().fingerprint().maxMtimeMs(),
                e.getValue().sessions().stream()
                    .map(ref -> toCachedSession(ref.info())).toList()))
            .toList();
        return new ProjectIndexSnapshot(ProjectIndexSnapshot.CURRENT_VERSION,
            dirs, pinned, collapsed);
    }

    private static DirFingerprint fingerprint(List<SessionCatalog.Candidate> candidates) {
        long max = 0;
        for (SessionCatalog.Candidate candidate : candidates) {
            max = Math.max(max, candidate.mtime());
        }
        return new DirFingerprint(candidates.size(), max);
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
