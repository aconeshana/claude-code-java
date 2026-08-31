package com.claudecode.services.hooks;

import com.claudecode.core.engine.HookDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session-scoped watcher that dispatches.
 */
public final class FileChangedHookWatcher implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FileChangedHookWatcher.class);
    private static final Duration STABLE_WINDOW = Duration.ofMillis(500);

    @FunctionalInterface
    interface EventConsumer {
        void accept(Path path, String event);
    }

    interface Backend extends AutoCloseable {
        @Override void close();
    }

    @FunctionalInterface
    interface BackendFactory {
        Backend create(Set<Path> targets, EventConsumer consumer);
    }

    interface Debouncer extends AutoCloseable {
        void submit(Path path, Runnable task);
        @Override void close();
    }

    private final HookDispatcher hooks;
    private final BackendFactory backendFactory;
    private final Debouncer debouncer;
    private final Supplier<List<String>> matcherSupplier;
    private final Object lock = new Object();
    private final Map<Path, String> latestEvents = new LinkedHashMap<>();
    private List<String> staticMatchers = List.of();
    private Set<Path> dynamicPaths = Set.of();
    private Path cwd;
    private Backend backend;
    private boolean closed;
    private volatile Consumer<String> diagnosticSink = _ -> { };

    public FileChangedHookWatcher(HookDispatcher hooks) {
        this(hooks, NioBackend::new, new ScheduledDebouncer(STABLE_WINDOW),
            hooks instanceof HookEngine
                ? () -> ((HookEngine) hooks).configuredFileChangedMatchers()
                : List::of);
    }

    FileChangedHookWatcher(HookDispatcher hooks, BackendFactory backendFactory,
                           Debouncer debouncer) {
        this(hooks, backendFactory, debouncer, null);
    }

    private FileChangedHookWatcher(HookDispatcher hooks, BackendFactory backendFactory,
                                   Debouncer debouncer,
                                   Supplier<List<String>> matcherSupplier) {
        this.hooks = Objects.requireNonNull(hooks);
        this.backendFactory = Objects.requireNonNull(backendFactory);
        this.debouncer = Objects.requireNonNull(debouncer);
        this.matcherSupplier = matcherSupplier;
    }

    public void setDiagnosticSink(Consumer<String> sink) {
        diagnosticSink = sink == null ? _ -> { } : sink;
    }

    public void initialize(Path initialCwd, List<String> matchers) {
        synchronized (lock) {
            ensureOpen();
            cwd = normalize(initialCwd);
            staticMatchers = matchers == null ? List.of() : List.copyOf(matchers);
            restartLocked();
        }
    }

    /** Replaces, rather than accumulates, dynamic paths returned by lifecycle hooks. */
    public void replaceWatchPaths(List<Path> paths) {
        synchronized (lock) {
            ensureOpen();
            dynamicPaths = validDynamicPaths(paths);
            restartLocked();
        }
    }

    public void onCwdChanged(Path newCwd) {
        Path normalized = normalize(newCwd);
        Path previous;
        synchronized (lock) {
            ensureOpen();
            previous = cwd;
            if (Objects.equals(previous, normalized)) return;
            cwd = normalized;
        }

        HookDispatcher.HookOutcome outcome = hooks.dispatchCwdChangedWithOutcome(
            previous == null ? "" : previous.toString(), normalized.toString());
        Set<Path> returnedPaths = outcome.specificOutput("CwdChanged")
            .map(FileChangedHookWatcher::readWatchPaths)
            .map(this::validDynamicPaths)
            .orElse(Set.of());

        synchronized (lock) {
            if (closed) return;
            dynamicPaths = returnedPaths;
            restartLocked();
        }
    }

    /** Rebase static matchers when the hook was dispatched by the runtime itself. */
    public void rebase(Path newCwd) {
        synchronized (lock) {
            ensureOpen();
            cwd = normalize(newCwd);
            refreshStaticMatchersLocked();
            restartLocked();
        }
    }

    private void receive(Path path, String event) {
        Path normalized = normalize(path);
        synchronized (lock) {
            if (closed) return;
            latestEvents.put(normalized, event);
        }
        debouncer.submit(normalized, () -> dispatchLatest(normalized));
    }

    private void dispatchLatest(Path path) {
        String event;
        synchronized (lock) {
            if (closed) return;
            event = latestEvents.remove(path);
        }
        if (event == null) return;
        HookDispatcher.HookOutcome outcome = hooks.dispatchFileChangedWithOutcome(
            path.toString(), event);
        outcome.specificOutput("FileChanged")
            .map(FileChangedHookWatcher::readWatchPaths)
            .ifPresent(paths -> replaceWatchPaths(new ArrayList<>(paths)));
    }

    private void restartLocked() {
        closeBackendLocked();
        Set<Path> targets = resolvedTargetsLocked();
        if (targets.isEmpty()) return;
        try {
            backend = backendFactory.create(targets, this::receive);
        } catch (RuntimeException exception) {
            LOG.warn("Unable to start FileChanged hook watcher", exception);
            diagnosticSink.accept("Unable to watch hook environment files: "
                + exception.getMessage());
        }
    }

    private Set<Path> resolvedTargetsLocked() {
        LinkedHashSet<Path> targets = new LinkedHashSet<>(dynamicPaths);
        if (cwd == null) return Set.copyOf(targets);
        for (String matcher : staticMatchers) {
            if (matcher == null) continue;
            for (String token : matcher.split("\\|", -1)) {
                String value = token.trim();
                if (value.isEmpty() || isRemote(value)) continue;
                try {
                    Path candidate = Path.of(value);
                    targets.add(normalize(candidate.isAbsolute() ? candidate : cwd.resolve(candidate)));
                } catch (RuntimeException exception) {
                    LOG.debug("Ignoring invalid FileChanged matcher path: {}", value, exception);
                }
            }
        }
        return Set.copyOf(targets);
    }

    private Set<Path> validDynamicPaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return Set.of();
        LinkedHashSet<Path> valid = new LinkedHashSet<>();
        for (Path path : paths) {
            if (path == null || !path.isAbsolute() || isRemote(path.toString())) {
                LOG.debug("Ignoring invalid dynamic hook watch path: {}", path);
                diagnosticSink.accept("Ignoring invalid hook watch path: " + path);
                continue;
            }
            valid.add(normalize(path));
        }
        return Set.copyOf(valid);
    }

    private static List<Path> readWatchPaths(JsonNode fields) {
        JsonNode watchPaths = fields == null ? null : fields.get("watchPaths");
        if (watchPaths == null || !watchPaths.isArray()) return List.of();
        List<Path> paths = new ArrayList<>();
        for (JsonNode value : watchPaths) {
            if (!value.isTextual()) continue;
            try {
                paths.add(Path.of(value.textValue()));
            } catch (RuntimeException _) {
                // Invalid hook output is diagnostic-only and never ends the session.
            }
        }
        return paths;
    }

    private static boolean isRemote(String value) {
        return Strings.CS.startsWith(value, "//")
            || Strings.CS.startsWith(value, "\\\\");
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("FileChangedHookWatcher is closed");
    }

    private void refreshStaticMatchersLocked() {
        if (matcherSupplier == null) return;
        List<String> current = matcherSupplier.get();
        staticMatchers = current == null ? List.of() : List.copyOf(current);
    }

    private void closeBackendLocked() {
        if (backend == null) return;
        backend.close();
        backend = null;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            latestEvents.clear();
            closeBackendLocked();
        }
        debouncer.close();
    }

    private static final class ScheduledDebouncer implements Debouncer {
        private final long delayMillis;
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("hook-file-debounce-", 0).factory());
        private final Map<Path, ScheduledFuture<?>> pending = new LinkedHashMap<>();

        private ScheduledDebouncer(Duration delay) {
            delayMillis = delay.toMillis();
        }

        @Override
        public synchronized void submit(Path path, Runnable task) {
            ScheduledFuture<?> previous = pending.remove(path);
            if (previous != null) previous.cancel(false);
            pending.put(path, scheduler.schedule(() -> {
                synchronized (ScheduledDebouncer.this) {
                    pending.remove(path);
                }
                task.run();
            }, delayMillis, TimeUnit.MILLISECONDS));
        }

        @Override
        public synchronized void close() {
            pending.values().forEach(future -> future.cancel(false));
            pending.clear();
            scheduler.shutdownNow();
        }
    }

    private static final class NioBackend implements Backend {
        private final WatchService watchService;
        private final Set<Path> targets;
        private final Thread thread;

        private NioBackend(Set<Path> targets, EventConsumer consumer) {
            this.targets = Set.copyOf(targets);
            try {
                watchService = FileSystems.getDefault().newWatchService();
                LinkedHashSet<Path> parents = new LinkedHashSet<>();
                for (Path target : targets) {
                    Path parent = target.getParent();
                    if (parent != null && Files.isDirectory(parent)) parents.add(parent);
                }
                for (Path parent : parents) {
                    parent.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to register FileChanged paths", exception);
            }
            thread = Thread.ofVirtual().name("hook-file-watcher").start(() -> run(consumer));
        }

        private void run(EventConsumer consumer) {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watchService.take();
                    Path parent = (Path) key.watchable();
                    for (WatchEvent<?> raw : key.pollEvents()) {
                        if (!(raw.context() instanceof Path relative)) continue;
                        Path target = parent.resolve(relative).toAbsolutePath().normalize();
                        if (!targets.contains(target)) continue;
                        String event = switch (raw.kind().name()) {
                            case "ENTRY_CREATE" -> "add";
                            case "ENTRY_DELETE" -> "unlink";
                            default -> "change";
                        };
                        consumer.accept(target, event);
                    }
                    if (!key.reset()) LOG.debug("FileChanged watch key became invalid: {}", parent);
                }
            } catch (ClosedWatchServiceException _) {
                // Normal shutdown.
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                LOG.warn("FileChanged hook watcher stopped unexpectedly", exception);
            }
        }

        @Override
        public void close() {
            try {
                watchService.close();
            } catch (IOException exception) {
                LOG.debug("Unable to close FileChanged watch service", exception);
            }
            thread.interrupt();
        }
    }
}
