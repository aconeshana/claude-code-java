package com.claudecode.services.config;


import com.claudecode.permissions.RuleSource;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeEvent.EventType;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.visitor.FileTreeVisitor;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.commons.lang3.Strings;

/**
 * Watches the three paths (user, project, local) and the managed policy file/drop-in directory
 * invokes a {@link SettingsChangeListener} when a stable change is detected on disk.
 */
public final class SettingsHotReloader implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsHotReloader.class);

    /** Debounce window: writes to the same file within this many ms coalesce. */
    static final long DEFAULT_DEBOUNCE_MS = 1000;

    /**
     * Delete grace: a DELETE event only fires the listener after this delay,
     * giving IDE atomic-save patterns time to write the replacement.
     */
    static final long DEFAULT_DELETE_GRACE_MS = 1700;


    static final long DEFAULT_INTERNAL_WRITE_WINDOW_MS = 5000;

    static final long DEFAULT_MDM_REFRESH_MS = 30 * 60 * 1000L;

    private final Map<Path, RuleSource> pathToSource;
    private final Set<Path> watchedDirs;
    private final Path policyDropInDir;
    private final SettingsChangeListener listener;
    private final long debounceMs;
    private final long deleteGraceMs;
    private final long internalWriteWindowMs;

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> mdmRefresh;
    private volatile String lastMdmSnapshot;
    private final Map<Path, ScheduledFuture<?>> pendingDebounce = new ConcurrentHashMap<>();
    private final Map<Path, ScheduledFuture<?>> pendingDeletion = new ConcurrentHashMap<>();

    private DirectoryWatcher watcher;
    private volatile boolean started;
    private volatile boolean closed;

    /**
     * Constructs a reloader using default timing constants.
     *
     * @param userPath     resolved path to
     * @param projectPath  resolved path to {@code on}
     * @param localPath    resolved path to {@code on}
     * @param listener     called after debounce/grace with the changed source
     */
    public SettingsHotReloader(Path userPath, Path projectPath, Path localPath,
                                SettingsChangeListener listener) {
        this(userPath, projectPath, localPath, listener,
            DEFAULT_DEBOUNCE_MS, DEFAULT_DELETE_GRACE_MS, DEFAULT_INTERNAL_WRITE_WINDOW_MS);
    }

    /** Constructs a reloader that also watches the managed policy source. */
    public SettingsHotReloader(Path userPath, Path projectPath, Path localPath,
                                Path policyPath, SettingsChangeListener listener) {
        this(userPath, projectPath, localPath, policyPath, listener,
            DEFAULT_DEBOUNCE_MS, DEFAULT_DELETE_GRACE_MS, DEFAULT_INTERNAL_WRITE_WINDOW_MS);
    }

    /** Package-private constructor for tests that need shorter timings. */
    SettingsHotReloader(Path userPath, Path projectPath, Path localPath,
                        SettingsChangeListener listener,
                        long debounceMs, long deleteGraceMs, long internalWriteWindowMs) {
        this(userPath, projectPath, localPath, null, listener,
            debounceMs, deleteGraceMs, internalWriteWindowMs);
    }

    /** Package-private constructor for tests that need a policy path/timings. */
    SettingsHotReloader(Path userPath, Path projectPath, Path localPath,
                        Path policyPath, SettingsChangeListener listener,
                        long debounceMs, long deleteGraceMs, long internalWriteWindowMs) {
        this.listener = listener;
        this.debounceMs = debounceMs;
        this.deleteGraceMs = deleteGraceMs;
        this.internalWriteWindowMs = internalWriteWindowMs;

        this.pathToSource = new LinkedHashMap<>();
        // Order matters: user > project > local. If cwd == $HOME (user runs
// `java -jar` from ~), userSettingsPath and projectSettingsPath(cwd)
        // resolve to the same file — putIfAbsent keeps the first mapping so
// the user-visible label for an edit of ~/on stays
        // "user settings", not "project settings". Merge semantics are
        // unaffected: the settings snapshot engine always reads all three files on reload.
        this.pathToSource.putIfAbsent(normalize(userPath),    RuleSource.USER_SETTINGS);
        this.pathToSource.putIfAbsent(normalize(projectPath), RuleSource.PROJECT_SETTINGS);
        this.pathToSource.putIfAbsent(normalize(localPath),   RuleSource.LOCAL_SETTINGS);
        if (policyPath != null) {
            this.pathToSource.putIfAbsent(normalize(policyPath), RuleSource.POLICY_SETTINGS);
        }
        this.policyDropInDir = policyPath == null || policyPath.getParent() == null
            ? null : normalize(policyPath).getParent().resolve("managed-settings.d");

        // Deduplicated set of parent directories to hand to directory-watcher.

        // of that source's settings files already exists. Keep that distinction:
        // a fresh ~/.claude/ directory is not watched until a settings file is
        // present; the independent MDM poll above still runs immediately.
// Skips dirs that don't exist — watcher.build would otherwise throw.
        this.watchedDirs = new LinkedHashSet<>();
        Map<Path, Boolean> sourceDirHasFile = new LinkedHashMap<>();
        for (Path p : new Path[] { userPath, projectPath, localPath }) {
            if (p == null) continue;
            Path normalized = normalize(p);
            Path dir = normalized.getParent();
            if (dir != null && Files.isDirectory(dir)) {
                sourceDirHasFile.merge(dir, Files.isRegularFile(normalized), Boolean::logicalOr);
            }
        }
        sourceDirHasFile.forEach((dir, hasFile) -> {
            if (Boolean.TRUE.equals(hasFile)) watchedDirs.add(dir);
        });
        // The managed drop-in directory is watched whenever it exists, matching

        if (policyPath != null) {
            Path policy = normalize(policyPath);
            Path dir = policy.getParent();
            if (dir != null && Files.isDirectory(dir) && Files.isRegularFile(policy)) {
                watchedDirs.add(dir);
            }
        }
        if (policyDropInDir != null && Files.isDirectory(policyDropInDir)) {
            watchedDirs.add(policyDropInDir);
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "settings-hot-reload-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the underlying file watcher. Subsequent calls are no-ops.
     * If no watched directory exists on disk (fresh install with no
     * {@code ~/.claude/} yet), the filesystem watcher remains idle, but the
     * independent MDM poll still starts so policy changes are detected.
     */
    public synchronized void start() throws IOException {
        if (started || closed) return;
        started = true;

        // MDM/registry settings are not represented by filesystem events and
        // must be polled even on a fresh install where none of the settings
        // directories exists yet. Keep this independent from the directory

        // implementation.
        lastMdmSnapshot = MdmSettingsStore.snapshot();
        mdmRefresh = scheduler.scheduleAtFixedRate(() -> {
            try {
                MdmSettingsStore.clearCache();
                String current = MdmSettingsStore.snapshot();
                if (!Objects.equals(lastMdmSnapshot, current)) {
                    lastMdmSnapshot = current;

                    // fanOut('policySettings') directly.  It does not pass
                    // through the file-event path, so ConfigChange hooks must
                    // not be dispatched for registry/plist-only changes.
                    fireProgrammatic(RuleSource.POLICY_SETTINGS);
                }
            } catch (RuntimeException e) {
                LOG.debug("MDM settings refresh failed: {}", e.getMessage());
            }
        }, DEFAULT_MDM_REFRESH_MS, DEFAULT_MDM_REFRESH_MS, TimeUnit.MILLISECONDS);

        if (watchedDirs.isEmpty()) {
            LOG.debug("No settings directories exist yet — filesystem hot-reload idle; MDM polling active");
            return;
        }
        this.watcher = DirectoryWatcher.builder()
            .paths(watchedDirs.stream().toList())
            .listener(this::handleEvent)
            // macOS FSEvents reports descendant activity even though our shallow
            // visitor only registers the settings directory itself. The library's
            // DEBUG logger otherwise writes one or more lines for every transcript,
            // lock, file-history, and plugin-cache update below ~/.claude. Keep our
            // own targeted settings logs, but silence that raw event firehose.
            .logger(NOPLogger.NOP_LOGGER)
            // Skip hash computation: we already coalesce events in scheduleChange
            // via debounce. Hashing every file in ~/.claude/ (potentially 1GB+
            // when the user has plugins/projects/file-history subdirectories)
            // was blocking startup for tens of seconds — the wall-clock cost of
            // MurmurHash3 across gigabytes of transcript JSONL.
            .fileHashing(false)
            // Shallow file tree visitor: register + inspect only the direct
            // children of each watched directory. Without this, directory-watcher
            // walks every subdirectory of ~/.claude/ recursively to attach an
            // inotify/FSEvents watch to it — pointless for us because we only
            // care about the two known filenames in the top-level directory

            .fileTreeVisitor(shallowVisitor())
            .build();
        this.watcher.watchAsync();
        LOG.info("Settings hot-reload watching {} directory(ies): {}",
            watchedDirs.size(), watchedDirs);
    }

    private static FileTreeVisitor shallowVisitor() {
        return (root, dirCallback, fileCallback) -> {
            // directory-watcher calls this both at build() time (for the
            // initial tree scan) and on every incoming event so it can register
            // freshly-created subdirectories. On macOS FSEvents can deliver
            // events for short-lived files (lock files, atomic renames, IDE
            // temp files) that are already gone by the time we look — and any
            // IOException we let escape here propagates into a JNA callback
            // and kills the entire watch service. So we swallow aggressively.
            if (!Files.isDirectory(root)) {
                // Not a directory, or gone. Nothing to walk.
                return;
            }
            try {
                dirCallback.call(root);
            } catch (IOException e) {
                LOG.debug("shallowVisitor: dirCallback failed on {}: {}",
                    root, e.getMessage());
                return;
            }
            try (Stream<Path> stream = Files.list(root)) {
                stream
                    .filter(p -> Files.isRegularFile(p) || Files.isSymbolicLink(p))
                    .forEach(p -> {
                        try {
                            fileCallback.call(p);
                        } catch (IOException e) {
                            LOG.debug("shallowVisitor: fileCallback failed on {}: {}",
                                p, e.getMessage());
                        }
                    });
            } catch (NoSuchFileException | NotDirectoryException _) {
                // Raced with rename / delete between isDirectory() and list().
// Common for lock files (~/onl.lock) and
                // IDE atomic saves — nothing pathological, just retry on the
                // next event.
                LOG.debug("shallowVisitor: {} vanished mid-scan", root);
            } catch (IOException e) {
                LOG.warn("shallowVisitor: unexpected IO error at {}: {}",
                    root, e.getMessage());
            }
        };
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                LOG.warn("Error closing directory watcher: {}", e.getMessage());
            }
        }
        // Cancel all pending timers.
        pendingDebounce.values().forEach(f -> f.cancel(false));
        pendingDebounce.clear();
        pendingDeletion.values().forEach(f -> f.cancel(false));
        pendingDeletion.clear();
        if (mdmRefresh != null) mdmRefresh.cancel(false);
        lastMdmSnapshot = null;
        // changeDetector.dispose() clears the module-level internal-write map;
        // otherwise a later in-process watcher could consume a stale marker
        // from a previous session and suppress a real external edit.
        InternalWrites.clearInternalWrites();
        scheduler.shutdownNow();
    }

    /**
     * Handles one file-system event. Package-private so tests can drive the
     * state machine directly without a real filesystem watcher.
     */
    void handleEvent(DirectoryChangeEvent event) {
        Path abs = normalize(event.path());
        RuleSource source = pathToSource.get(abs);
        if (source == null && isPolicyDropInFile(abs)) {
            source = RuleSource.POLICY_SETTINGS;
        }
        if (source == null) {
            // Sibling file in the same directory — ignore.
            return;
        }

        EventType kind = event.eventType();
        LOG.debug("settings event: {} on {}", kind, abs);

        switch (kind) {
            case DELETE -> scheduleDeletion(abs, source);
            case CREATE, MODIFY -> {

                // change/add events.  An unlink must still enter the delete
                // grace path; otherwise an atomic replace that emits only a
                // delete would be silently lost.
                if (InternalWrites.consumeInternalWrite(abs, internalWriteWindowMs)) {
                    LOG.debug("Suppressed internal-write echo for {}", abs);
                    return;
                }
                scheduleChange(abs, source);
            }
            case OVERFLOW -> LOG.warn("File watch overflow at {}", abs);
        }
    }

    private void scheduleChange(Path abs, RuleSource source) {
        // Cancel any pending deletion — the file is back / never really gone.
        cancel(pendingDeletion, abs);

        cancel(pendingDebounce, abs);
        ScheduledFuture<?> f = scheduler.schedule(() -> {
            pendingDebounce.remove(abs);
            fire(source, abs);
        }, debounceMs, TimeUnit.MILLISECONDS);
        pendingDebounce.put(abs, f);
    }

    private void scheduleDeletion(Path abs, RuleSource source) {
        // A pending change is now moot — the file is gone.
        cancel(pendingDebounce, abs);

        // Coalesce back-to-back DELETEs (chokidar sometimes emits two).
        if (pendingDeletion.containsKey(abs)) return;

        ScheduledFuture<?> f = scheduler.schedule(() -> {
            pendingDeletion.remove(abs);
            fire(source, abs);
        }, deleteGraceMs, TimeUnit.MILLISECONDS);
        pendingDeletion.put(abs, f);
    }

    private void fire(RuleSource source, Path path) {
        try {
            listener.onChange(source, path);
        } catch (Exception e) {
            LOG.warn("SettingsChangeListener threw for {}: {}", source, e.getMessage(), e);
        }
    }

    private void fireProgrammatic(RuleSource source) {
        try {
            // A null path is the explicit programmatic-change marker.  The
            // live orchestrator uses it to skip the file-only ConfigChange
            // hook while still applying the refreshed settings snapshot.
            listener.onChange(source, null);
        } catch (Exception e) {
            LOG.warn("SettingsChangeListener threw for programmatic {}: {}",
                source, e.getMessage(), e);
        }
    }

    private static void cancel(Map<Path, ScheduledFuture<?>> map, Path key) {
        ScheduledFuture<?> f = map.remove(key);
        if (f != null) f.cancel(false);
    }

    private static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }

    private boolean isPolicyDropInFile(Path path) {
        if (policyDropInDir == null || !policyDropInDir.equals(path.getParent())) return false;
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return !Strings.CS.startsWith(name, ".") &&Strings.CS.endsWith( name, ".json");
    }
}
