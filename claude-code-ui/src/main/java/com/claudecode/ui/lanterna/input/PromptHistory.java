package com.claudecode.ui.lanterna.input;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.paste.PastedRefParser;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.paste.PasteStore;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PromptHistory implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PromptHistory.class);
    private static final int MAX_HISTORY = 100;
    private static final int MAX_PASTED_CONTENT_LENGTH = 1024;
    private static final Executor HISTORY_READER = command ->
        Thread.ofVirtual().name("history-read").start(command);
    private static final long DEFAULT_FLUSH_DELAY_MS = 0;
    private static final long HISTORY_LOCK_STALE_MS = 10_000;
    private static final long HISTORY_LOCK_UPDATE_MS = HISTORY_LOCK_STALE_MS / 2;
    private static final ScheduledExecutorService HISTORY_FLUSH_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "history-flush-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    private static volatile Boolean childSessionExportedInTmuxGlobal;

    // ── Entry ─────────────────────────────────────────────────────────────────

    public record Entry(
        String display,
        String sessionId,
        long   timestamp,
        String project,
        String cwd,
        Map<Integer, PastedContent> pastedContents  // text-only; images live in image-cache
    ) {}


    public enum HistoryScope {
        SESSION("session"), PROJECT("project"), EVERYWHERE("everywhere");

        private final String label;

        HistoryScope(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public HistoryScope next() {
            return switch (this) {
                case EVERYWHERE -> SESSION;
                case SESSION -> PROJECT;
                case PROJECT -> EVERYWHERE;
            };
        }
    }

    /** Lightweight Ctrl+R row; pasted content is resolved only after selection. */
    public static final class TimestampedEntry {
        private final String display;
        private final long timestamp;
        private final Supplier<CompletableFuture<Entry>> resolver;

        private TimestampedEntry(String display, long timestamp,
                                 Supplier<CompletableFuture<Entry>> resolver) {
            this.display = display;
            this.timestamp = timestamp;
            this.resolver = resolver;
        }

        public String display() { return display; }
        public long timestamp() { return timestamp; }
        public CompletableFuture<Entry> resolveAsync() { return resolver.get(); }

        public static TimestampedEntry resolved(Entry entry) {
            return deferred(entry.display(), entry.timestamp(),
                () -> CompletableFuture.completedFuture(entry));
        }

        public static TimestampedEntry deferred(
            String display,
            long timestamp,
            Supplier<CompletableFuture<Entry>> resolver
        ) {
            return new TimestampedEntry(display, timestamp, resolver);
        }
    }

    /** Lazy global newest-first reader used by the legacy reverse-i-search path. */
    final class HistoryReader implements AutoCloseable {
        private final List<RawEntry> pendingSnapshot;
        private final Set<EntryIdentity> pendingIdentities;
        private final Object readLock = new Object();
        private final AtomicReference<Thread> activeRead = new AtomicReference<>();
        private int pendingIndex;
        private volatile ReverseLineReader disk;
        private volatile boolean closedReader;
        private boolean exhaustedDiskForSuppliedEntries;

        private HistoryReader() {
            this(null);
        }

        HistoryReader(List<Entry> suppliedEntries) {
            List<RawEntry> snapshot = new ArrayList<>();
            Set<EntryIdentity> identities = new LinkedHashSet<>();
            if (suppliedEntries != null) {
                for (Entry entry : suppliedEntries) {
                    RawEntry raw = RawEntry.resolved(entry);
                    snapshot.add(raw);
                    identities.add(raw.identity());
                }
                exhaustedDiskForSuppliedEntries = true;
            } else synchronized (pending) {
                for (int i = pending.size() - 1; i >= 0; i--) {
                    RawEntry raw = RawEntry.resolved(pending.get(i));
                    snapshot.add(raw);
                    identities.add(raw.identity());
                }
            }
            pendingSnapshot = List.copyOf(snapshot);
            pendingIdentities = Set.copyOf(identities);
        }

        CompletableFuture<Entry> findNextAsync(String query, Set<String> seenDisplays) {
            String needle = query.toLowerCase(Locale.ROOT);
            CompletableFuture<Entry> result = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().name("history-search-read").unstarted(() -> {
                activeRead.set(Thread.currentThread());
                try {
                    synchronized (readLock) {
                    while (!closedReader) {
                        Entry entry = next();
                        if (entry == null) {
                            result.complete(null);
                            return;
                        }
                        if (closedReader || Thread.currentThread().isInterrupted()) {
                            result.complete(null);
                            return;
                        }
                        String display = entry.display();
                        if (display.toLowerCase(Locale.ROOT).lastIndexOf(needle) >= 0
                                && seenDisplays.add(display)) {
                            result.complete(entry);
                            return;
                        }
                    }
                    result.complete(null);
                    }
                } catch (Throwable failure) {
                    if (closedReader || Thread.currentThread().isInterrupted()) result.complete(null);
                    else result.completeExceptionally(failure);
                } finally {
                    activeRead.compareAndSet(Thread.currentThread(), null);
                    if (closedReader && disk != null) disk.close();
                }
            });
            worker.start();
            return result;
        }

        private Entry next() {
            while (pendingIndex < pendingSnapshot.size()) {
                if (closedReader || Thread.currentThread().isInterrupted()) return null;
                RawEntry raw = pendingSnapshot.get(pendingIndex++);
                if (!skippedEntries.contains(raw.identity())) return raw.resolve();
            }
            if (closedReader || exhaustedDiskForSuppliedEntries) return null;
            try {
                if (disk == null) disk = new ReverseLineReader(historyFile);
                String line;
                while ((line = disk.nextLine()) != null) {
                    if (closedReader || Thread.currentThread().isInterrupted()) return null;
                    try {
                        JsonNode node = JsonUtils.getMapper().readTree(line.trim());
                        String display = node.has("display") && node.get("display").isTextual()
                            ? node.get("display").asText() : null;
                        if (StringUtils.isEmpty(display)) continue;
                        long timestamp = node.path("timestamp").asLong(0);
                        String sessionId = node.path("sessionId").asText(null);
                        EntryIdentity identity = new EntryIdentity(timestamp, sessionId);
                        if (skippedEntries.contains(identity) || pendingIdentities.contains(identity)) {
                            continue;
                        }
                        String project = node.has("project") && node.get("project").isTextual()
                            ? node.get("project").asText() : null;
                        return RawEntry.stored(display, sessionId, timestamp, project,
                            node.path("cwd").asText(null), node.path("pastedContents")).resolve();
                    } catch (Exception _) {

                    }
                }
            } catch (IOException failure) {
                LOG.debug("Failed to lazily read global history: {}", failure.getMessage());
            }
            closedReader = true;
            if (disk != null) disk.close();
            return null;
        }

        @Override public void close() {
            closedReader = true;
            Thread worker = activeRead.get();
            if (worker != null) worker.interrupt();
            else if (disk != null) disk.close();
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final Path historyFile;
    private final long flushDelayMs;

    /** In-memory buffer of entries not yet flushed. Newest is last. */
    private final List<Entry> pending = Collections.synchronizedList(new ArrayList<>());
    /** The entry most recently passed to addEntry(), for removeLastEntry(). */
    private volatile Entry lastAdded = null;

    private volatile boolean suppressedDuplicate = false;
    /** Session-qualified identities already flushed but hidden by one-shot undo. */
    private final Set<EntryIdentity> skippedEntries = ConcurrentHashMap.newKeySet();
    /** Prevents concurrent flush operations. */
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    /** Stops new background flushes once the owning REPL begins shutdown. */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Coordinates add/close with publication of the active virtual thread. */
    private final Object lifecycleLock = new Object();
    private volatile Thread activeFlush;
    private ScheduledFuture<?> pendingFlush;
    private final Thread shutdownHook;
    /**
     * Serializes {@link #flushPending()} itself. The {@code flushing} flag only
     * gates the async path; the shutdown hook (and tests) call flushPending
     * directly. Distinct from the {@code pending} monitor so readers are never
     * blocked behind lock acquisition or disk I/O.
     */
    private final Object flushLock = new Object();

    // ── Construction ──────────────────────────────────────────────────────────

/** Default: uses. */
    public PromptHistory() {
        this(ClaudePaths.HISTORY_JSONL);
    }

    public PromptHistory(Path historyFile) {
        this(historyFile, DEFAULT_FLUSH_DELAY_MS);
    }

    PromptHistory(Path historyFile, long flushDelayMs) {
        this.historyFile = historyFile;
        this.flushDelayMs = Math.max(0, flushDelayMs);

        shutdownHook = Thread.ofVirtual().name("history-shutdown-flush").unstarted(() -> {
            if (!pending.isEmpty()) flushPending();
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    // ── Public API ────────────────────────────────────────────────────────────


    public void addEntry(String text, String sessionId, String cwd) {
        addEntry(text, sessionId, cwd, Map.of());
    }

    /**
     * Records an entry carrying pasted contents.
     */
    public void addEntry(String text, String sessionId, String cwd,
                         Map<Integer, PastedContent> pastedContents) {
        addEntry(text, sessionId, cwd, resolveProject(cwd), pastedContents);
    }

    /**
     * Records an entry with the already-resolved stable project identity.
     * The interactive REPL uses this overload so Enter remains an in-memory
     * operation before the asynchronous history flush starts.
     */
    public void addEntry(String text, String sessionId, String cwd, String project,
                         Map<Integer, PastedContent> pastedContents) {
        if (shouldSkipCurrentSessionHistory()) return;
        if (text == null) return;
        Map<Integer, PastedContent> stored = filterForStorage(pastedContents);
        synchronized (lifecycleLock) {
            if (closed.get()) return;
            boolean incomingHasPasted = pastedContents != null && !pastedContents.isEmpty();
            if (lastAdded != null
                    && Objects.equals(lastAdded.display(), text)
                    && Objects.equals(lastAdded.project(), project)
                    && Objects.equals(lastAdded.sessionId(), sessionId)
                    && lastAdded.pastedContents().isEmpty()
                    && !incomingHasPasted) {
                suppressedDuplicate = true;
                return;
            }
            Entry entry = new Entry(text, sessionId, System.currentTimeMillis(),
                project, cwd, stored);
            pending.add(entry);
            lastAdded = entry;
            suppressedDuplicate = false;
            scheduleFlush();
        }
    }


    static boolean shouldSkipPromptHistory(
            Map<String, String> environment,
            boolean childSessionIsInTmuxGlobalEnvironment) {
        return shouldSkipPromptHistory(environment,
            childSessionIsInTmuxGlobalEnvironment, true, false);
    }

    static boolean shouldSkipPromptHistory(
            Map<String, String> environment,
            boolean childSessionIsInTmuxGlobalEnvironment,
            boolean interactive,
            boolean teammate) {
        if (EnvUtils.isEnvTruthy(environment.get("CLAUDE_CODE_SKIP_PROMPT_HISTORY"))) {
            return true;
        }
        if (EnvUtils.isEnvTruthy(environment.get("CLAUDE_CODE_FORCE_SESSION_PERSISTENCE"))) {
            return false;
        }
        if (!EnvUtils.isEnvTruthy(environment.get("CLAUDE_CODE_CHILD_SESSION"))) {
            return false;
        }
        if (!interactive || teammate) return false;
        return !childSessionIsInTmuxGlobalEnvironment;
    }

    private static boolean shouldSkipCurrentSessionHistory() {
        Map<String, String> environment = SubprocessEnvironment.snapshot();
        boolean childInTmuxGlobal = false;
        if (EnvUtils.isEnvTruthy(environment.get("CLAUDE_CODE_CHILD_SESSION"))
                && !EnvUtils.isEnvTruthy(
                    environment.get("CLAUDE_CODE_FORCE_SESSION_PERSISTENCE"))) {
            childInTmuxGlobal = childSessionExportedInTmuxGlobal(environment);
        }
        return shouldSkipPromptHistory(environment, childInTmuxGlobal,
            true, TeammateContextHolder.get() != null);
    }


    private static boolean childSessionExportedInTmuxGlobal(Map<String, String> environment) {
        Boolean cached = childSessionExportedInTmuxGlobal;
        if (cached != null) return cached;
        synchronized (PromptHistory.class) {
            cached = childSessionExportedInTmuxGlobal;
            if (cached != null) return cached;
            boolean detected = false;
            if (StringUtils.isNotBlank(environment.get("TMUX"))) {
                Process process = null;
                try {
                    process = new ProcessBuilder(
                        "tmux", "show-environment", "-g", "CLAUDE_CODE_CHILD_SESSION")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                    if (process.waitFor(250, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
                        String output = new String(
                            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        detected = output.lines().anyMatch(
                            line -> Strings.CS.startsWith(
                                line, "CLAUDE_CODE_CHILD_SESSION="));
                    } else {
                        process.destroyForcibly();
                    }
                } catch (IOException _) {
// Probe failure keeps the conservative default: not exported.
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            childSessionExportedInTmuxGlobal = detected;
            return detected;
        }
    }

    private void scheduleFlush() {
        if (pendingFlush != null && !pendingFlush.isDone()) return;
        pendingFlush = HISTORY_FLUSH_SCHEDULER.schedule(() -> {
            synchronized (lifecycleLock) {
                pendingFlush = null;
                if (closed.get()) return;
            }
            flushAsyncWithRetries(0);
        }, flushDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Filters pastedContents for disk storage.
     */
    private static Map<Integer, PastedContent> filterForStorage(Map<Integer, PastedContent> pastedContents) {
        if (pastedContents == null || pastedContents.isEmpty()) return Map.of();
        Map<Integer, PastedContent> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, PastedContent> e : pastedContents.entrySet()) {
            PastedContent c = e.getValue();
            if (c == null) continue;
            if (c.isImage()) continue;  // images live in image-cache, not history
            if (c.content() == null) continue;
            if (c.content().length() > MAX_PASTED_CONTENT_LENGTH) {
                String hash = PasteStore.hashPastedText(c.content());
                PasteStore.storePastedTextAsync(hash, c.content());
            }
            out.put(e.getKey(), c);  // keep ALL text pastes (large ones get hashed on flush)
        }
        return out;
    }

    /**
     * Undoes the most recent {@link #addEntry} call.
     */
    public void removeLastEntry() {
        if (suppressedDuplicate) {
            suppressedDuplicate = false;
            return;
        }
        Entry entry = lastAdded;
        if (entry == null) return;
        lastAdded = null;
        synchronized (pending) {
            int idx = pending.lastIndexOf(entry);
            if (idx >= 0) {
                pending.remove(idx);  // fast path: still in buffer
                return;
            }
        }
        skippedEntries.add(identityOf(entry));  // slow path
    }

    /**
     * Returns full {@link Entry} objects so the caller can restore {@code pastedContents} when
     * navigating history.
     */
    public List<Entry> getEntriesWithPasted(String project, String sessionId, String modeFilter) {
        return getEntriesWithPasted(MAX_HISTORY, project, sessionId, modeFilter);
    }


    HistoryReader openGlobalHistoryReader() {
        return new HistoryReader();
    }

    /**
     * Ctrl+R entries for the requested.
     */
    public List<TimestampedEntry> getTimestampedEntries(
            HistoryScope scope, String project, String sessionId) {
        List<TimestampedEntry> result = new ArrayList<>(MAX_HISTORY);
        Set<String> seen = new LinkedHashSet<>();
        Set<EntryIdentity> pendingEntries = new LinkedHashSet<>();

        synchronized (pending) {
            for (int i = pending.size() - 1; i >= 0 && result.size() < MAX_HISTORY; i--) {
                Entry entry = pending.get(i);
                EntryIdentity identity = identityOf(entry);
                pendingEntries.add(identity);
                if (skippedEntries.contains(identity)) continue;
                if (!scopeMatches(scope, entry.project(), entry.sessionId(), project, sessionId)) {
                    continue;
                }
                if (!seen.add(entry.display())) continue;
                result.add(TimestampedEntry.resolved(entry));
            }
        }

        if (result.size() < MAX_HISTORY && Files.isRegularFile(historyFile)) {
            try {
                FileUtils.forEachLineReverse(historyFile, rawLine -> {
                    if (Thread.currentThread().isInterrupted()) return false;
                    String line = rawLine.trim();
                    if (line.isEmpty()) return true;
                    try {
                        JsonNode node = JsonUtils.getMapper().readTree(line);
                        long timestamp = node.path("timestamp").asLong(0);
                        String entrySession = node.path("sessionId").asText(null);
                        EntryIdentity identity = new EntryIdentity(timestamp, entrySession);
                        if (skippedEntries.contains(identity) || pendingEntries.contains(identity)) {
                            return true;
                        }
                        String entryProject = node.has("project") && node.get("project").isTextual()
                            ? node.get("project").asText() : null;
                        if (!scopeMatches(scope, entryProject, entrySession, project, sessionId)) {
                            return true;
                        }
                        String display = node.path("display").asText(null);
                        if (StringUtils.isBlank(display) || !seen.add(display)) return true;
                        RawEntry stored = RawEntry.stored(display, entrySession, timestamp, entryProject,
                            node.path("cwd").asText(null), node.path("pastedContents"));
                        result.add(TimestampedEntry.deferred(display, timestamp,
                            () -> CompletableFuture.supplyAsync(stored::resolve, HISTORY_READER)));
                        return result.size() < MAX_HISTORY;
                    } catch (Exception _) {
                        return true;
                    }
                });
            } catch (IOException e) {
                LOG.debug("Failed to read history picker metadata: {}", e.getMessage());
            }
        }
        return List.copyOf(result);
    }


    int countEntries(String project, String modeFilter) {
        int physical = 0;
        int matching = 0;
        Set<EntryIdentity> pendingEntries = new LinkedHashSet<>();
        synchronized (pending) {
            for (int i = pending.size() - 1; i >= 0 && physical < MAX_HISTORY; i--) {
                Entry entry = pending.get(i);
                EntryIdentity identity = identityOf(entry);
                pendingEntries.add(identity);
                if (skippedEntries.contains(identity) || !projectMatches(entry.project(), project)) {
                    continue;
                }
                physical++;
                if (matchesMode(entry.display(), modeFilter)) matching++;
            }
        }
        if (physical >= MAX_HISTORY || !Files.isRegularFile(historyFile)) return matching;
        int[] counts = {physical, matching};
        try {
            FileUtils.forEachLineReverse(historyFile, rawLine -> {
                try {
                    JsonNode node = JsonUtils.getMapper().readTree(rawLine.trim());
                    EntryIdentity identity = new EntryIdentity(
                        node.path("timestamp").asLong(0), node.path("sessionId").asText(null));
                    if (skippedEntries.contains(identity) || pendingEntries.contains(identity)) {
                        return true;
                    }
                    String entryProject = node.has("project") && node.get("project").isTextual()
                        ? node.get("project").asText() : null;
                    if (!projectMatches(entryProject, project)) return true;
                    counts[0]++;
                    if (matchesMode(node.path("display").asText(""), modeFilter)) counts[1]++;
                    return counts[0] < MAX_HISTORY;
                } catch (Exception _) {
                    return true;
                }
            });
        } catch (IOException _) {
            return -1;
        }
        return counts[1];
    }

    public CompletableFuture<Integer> countEntriesAsync(String project, String modeFilter) {
        return CompletableFuture.supplyAsync(() -> {
            int count = countEntries(project, modeFilter);
            return count < 0 ? null : count;
        }, HISTORY_READER);
    }


    public CompletableFuture<List<TimestampedEntry>> getTimestampedEntriesAsync(
            HistoryScope scope, String project, String sessionId) {
        return cancellableVirtualFuture(
            () -> getTimestampedEntries(scope, project, sessionId));
    }

    private static <T> CompletableFuture<T> cancellableVirtualFuture(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().name("history-read").unstarted(() -> {
            try {
                if (!future.isCancelled()) future.complete(supplier.get());
            } catch (Throwable failure) {
                if (!future.isCancelled()) future.completeExceptionally(failure);
            }
        });
        worker.set(thread);
        future.whenComplete((_, _) -> {
            if (future.isCancelled()) {
                Thread running = worker.get();
                if (running != null) running.interrupt();
            }
        });
        thread.start();
        return future;
    }

    /**
     * Asynchronous arrow/search-history snapshot. Interactive input preloads
     * through this boundary before handling Up or reverse search.
     */
    public CompletableFuture<List<Entry>> getEntriesWithPastedAsync(
            String project, String sessionId, String modeFilter) {
        return CompletableFuture.supplyAsync(
            () -> getEntriesWithPasted(project, sessionId, modeFilter), HISTORY_READER);
    }

    /** Reads only the requested arrow-history chunk on a virtual thread. */
    public CompletableFuture<List<Entry>> getEntriesWithPastedAsync(
            int limit, String project, String sessionId, String modeFilter) {
        if (limit >= MAX_HISTORY) {
            // Preserve the original overridable boundary for full-history callers.
            return getEntriesWithPastedAsync(project, sessionId, modeFilter);
        }
        return CompletableFuture.supplyAsync(
            () -> getEntriesWithPasted(limit, project, sessionId, modeFilter), HISTORY_READER);
    }

    public List<Entry> getEntriesWithPasted(int limit, String project, String sessionId, String modeFilter) {
        if (limit <= 0) return List.of();
        List<Entry> currentSession = new ArrayList<>(Math.min(limit, MAX_HISTORY));
        List<RawEntry> otherSessions = new ArrayList<>(MAX_HISTORY);
        Set<EntryIdentity> pendingEntries = new LinkedHashSet<>();
        int[] physical = {0};


        synchronized (pending) {
            for (int i = pending.size() - 1;
                 i >= 0 && physical[0] < MAX_HISTORY && currentSession.size() < limit; i--) {
                Entry e = pending.get(i);
                EntryIdentity identity = identityOf(e);
                pendingEntries.add(identity);
                if (skippedEntries.contains(identity)) continue;
                if (!projectMatches(e.project(), project)) continue;
                physical[0]++;
                RawEntry raw = RawEntry.resolved(e);
                if (Objects.equals(e.sessionId(), sessionId)) {
                    if (matchesMode(e.display(), modeFilter)) currentSession.add(e);
                } else {
                    otherSessions.add(raw);
                }
            }
        }

        if (currentSession.size() < limit && physical[0] < MAX_HISTORY
                && Files.isRegularFile(historyFile)) {
            try {
                FileUtils.forEachLineReverse(historyFile, rawLine -> {
                    String line = rawLine.trim();
                    if (line.isEmpty()) return true;
                    try {
                        var node = JsonUtils.getMapper().readTree(line);
                        long ts = node.path("timestamp").asLong(0);
                        String sid = node.path("sessionId").asText(null);
                        EntryIdentity identity = new EntryIdentity(ts, sid);
                        if (skippedEntries.contains(identity) || pendingEntries.contains(identity)) {
                            return true;
                        }
                        String entryProject = node.has("project") && node.get("project").isTextual()
                            ? node.get("project").asText() : null;
                        if (!projectMatches(entryProject, project)) return true;
                        String display = node.has("display") && node.get("display").isTextual()
                            ? node.get("display").asText() : null;
                        if (display == null) return true;
                        RawEntry raw = RawEntry.stored(display, sid, ts, entryProject,
                            node.path("cwd").asText(null), node.path("pastedContents"));
                        physical[0]++;
                        if (Objects.equals(sid, sessionId)) {
                            if (matchesMode(display, modeFilter)) currentSession.add(raw.resolve());
                        } else {
                            otherSessions.add(raw);
                        }
                        return physical[0] < MAX_HISTORY && currentSession.size() < limit;
                    } catch (Exception _) {}
                    return true;
                });
            } catch (IOException e) {
                LOG.debug("Failed to read history: {}", e.getMessage());
            }
        }

        List<Entry> result = new ArrayList<>(Math.min(limit,
            currentSession.size() + otherSessions.size()));
        result.addAll(currentSession);
        for (RawEntry entry : otherSessions) {
            if (!matchesMode(entry.display(), modeFilter)) continue;
            result.add(entry.resolve());
            if (result.size() >= limit) break;
        }
        return List.copyOf(result);
    }

    private record EntryIdentity(long timestamp, String sessionId) {
        private EntryIdentity {
            sessionId = sessionId == null ? "" : sessionId;
        }
    }

    /** Incremental UTF-8 reverse-line reader; opening is deferred until the first search. */
    private static final class ReverseLineReader implements AutoCloseable {
        private static final int CHUNK_SIZE = 4 * 1024;
        private final Path path;
        private final ArrayDeque<String> ready = new ArrayDeque<>();
        private FileChannel channel;
        private long position;
        private byte[] partial = new byte[0];
        private boolean exhausted;

        ReverseLineReader(Path path) {
            this.path = path;
        }

        String nextLine() throws IOException {
            while (ready.isEmpty() && !exhausted) fill();
            return ready.pollFirst();
        }

        private void fill() throws IOException {
            if (channel == null) {
                if (!Files.isRegularFile(path)) {
                    exhausted = true;
                    return;
                }
                channel = FileChannel.open(path, StandardOpenOption.READ);
                position = channel.size();
            }
            if (position == 0) {
                exhausted = true;
                if (partial.length > 0) {
                    ready.addLast(decodeLine(partial, 0, partial.length));
                    partial = new byte[0];
                }
                closeChannel();
                return;
            }
            int count = (int) Math.min(CHUNK_SIZE, position);
            long start = position - count;
            byte[] chunk = new byte[count];
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.wrap(chunk);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {}
            position = start;

            byte[] data = new byte[chunk.length + partial.length];
            System.arraycopy(chunk, 0, data, 0, chunk.length);
            System.arraycopy(partial, 0, data, chunk.length, partial.length);
            int end = data.length;
            for (int i = data.length - 1; i >= 0; i--) {
                if (data[i] != '\n') continue;
                if (i + 1 < end) ready.addLast(decodeLine(data, i + 1, end - i - 1));
                end = i;
            }
            partial = Arrays.copyOfRange(data, 0, end);
        }

        private static String decodeLine(byte[] data, int offset, int length) {
            int actual = length;
            if (actual > 0 && data[offset + actual - 1] == '\r') actual--;
            return new String(data, offset, actual, StandardCharsets.UTF_8);
        }

        private void closeChannel() {
            if (channel == null) return;
            try {
                channel.close();
            } catch (IOException _) {}
            channel = null;
        }

        @Override public void close() {
            exhausted = true;
            ready.clear();
            partial = new byte[0];
            closeChannel();
        }
    }

    private static EntryIdentity identityOf(Entry entry) {
        return new EntryIdentity(entry.timestamp(), entry.sessionId());
    }

    private record RawEntry(
            String display, String sessionId, long timestamp, String project, String cwd,
            Map<Integer, PastedContent> resolvedPasted, JsonNode storedPasted) {

        static RawEntry resolved(Entry entry) {
            return new RawEntry(entry.display(), entry.sessionId(), entry.timestamp(),
                entry.project(), entry.cwd(), entry.pastedContents(), null);
        }

        static RawEntry stored(String display, String sessionId, long timestamp,
                               String project, String cwd, JsonNode pasted) {
            return new RawEntry(display, sessionId, timestamp, project, cwd, null,
                pasted == null ? null : pasted.deepCopy());
        }

        EntryIdentity identity() {
            return new EntryIdentity(timestamp, sessionId);
        }

        Entry resolve() {
            if (resolvedPasted != null) {
                return new Entry(display, sessionId, timestamp, project, cwd, resolvedPasted);
            }
            ResolvedPastes resolved = parsePastedContents(storedPasted);
            return new Entry(rewriteMissingTextReferences(display, resolved.missingTextIds()),
                sessionId, timestamp, project, cwd, resolved.contents());
        }
    }

    private record ResolvedPastes(
        Map<Integer, PastedContent> contents,
        Set<Integer> missingTextIds
    ) {}

    /**
     * Parse the {@code pastedContents} field from a JSONL node.
     */
    @SuppressWarnings("unchecked")
    private static ResolvedPastes parsePastedContents(JsonNode node) {
        JsonNode pc = node != null && node.isObject() && node.has("pastedContents")
            ? node.path("pastedContents") : node;
        if (pc == null || !pc.isObject() || pc.isEmpty()) {
            return new ResolvedPastes(Map.of(), Set.of());
        }
        Map<Integer, PastedContent> out = new LinkedHashMap<>();
        Set<Integer> missingTextIds = new LinkedHashSet<>();
        var fields = pc.fields();
        while (fields.hasNext()) {
            var f = fields.next();
            try {
                int id = Integer.parseInt(f.getKey());
                JsonNode v = f.getValue();
                String content = null;
                if (v.hasNonNull("content") && !v.path("content").asText().isEmpty()) {
                    // Inline small paste
                    content = v.path("content").asText();
                } else if (v.hasNonNull("contentHash")) {
// Large paste: retrieve from PasteStore.
                    String hash = v.path("contentHash").asText();
                    content = PasteStore.retrievePastedText(hash);

                }
                if (StringUtils.isEmpty(content)) {
                    if (Strings.CS.equals("text", v.path("type").asText(null))) {
                        missingTextIds.add(id);
                    }
                    continue;
                }
                out.put(id, new PastedContent(
                    id,
                    v.path("type").asText("text"),
                    content,
                    v.path("mediaType").asText(null),
                    v.path("filename").asText(null),
                    null,
                    null));
            } catch (NumberFormatException _) {}
        }
        return new ResolvedPastes(Map.copyOf(out), Set.copyOf(missingTextIds));
    }

    private static String rewriteMissingTextReferences(String display, Set<Integer> missingIds) {
        if (missingIds.isEmpty()) return display;
        String rewritten = display;
        List<PastedRefParser.Ref> references = PastedRefParser.parseReferences(display);
        for (int i = references.size() - 1; i >= 0; i--) {
            PastedRefParser.Ref reference = references.get(i);
            if (!missingIds.contains(reference.id())
                    || Strings.CS.startsWith(reference.match(), "[Image")) continue;
            String replacement = Strings.CS.startsWith(reference.match(), "[...Truncated text")
                ? "[...Truncated text #" + reference.id()
                    + " — content no longer available...]"
                : "[Pasted text #" + reference.id() + " — content no longer available]";
            rewritten = rewritten.substring(0, reference.index()) + replacement
                + rewritten.substring(reference.index() + reference.match().length());
        }
        return rewritten;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Normalizes the stable project identity supplied by the caller.
     */
    public static String resolveProject(String cwd) {
        String candidate = StringUtils.isBlank(cwd)
            ? System.getProperty("user.dir") : cwd;
        try {
            return Path.of(candidate).toAbsolutePath().normalize().toString();
        } catch (RuntimeException _) {
            return candidate;
        }
    }


    private static boolean projectMatches(String entryProject, String currentProject) {
        return entryProject != null && Objects.equals(entryProject, currentProject);
    }

    private static boolean scopeMatches(
            HistoryScope scope,
            String entryProject,
            String entrySession,
            String currentProject,
            String currentSession) {
        if (entryProject == null) return false;
        return switch (scope) {
            case EVERYWHERE -> true;
            case PROJECT -> Objects.equals(entryProject, currentProject);
            case SESSION -> Objects.equals(entrySession, currentSession);
        };
    }


    private static boolean matchesMode(String display, String modeFilter) {
        if (Strings.CS.equals("!", modeFilter)) return Strings.CS.startsWith(display, "!");
        return true;
    }


    private void flushAsyncWithRetries(int retries) {
        synchronized (lifecycleLock) {
            if (closed.get() || retries > 5) return;
            if (!flushing.compareAndSet(false, true)) return;
            Thread worker = Thread.ofVirtual().name("history-flush").unstarted(
                () -> runFlushLoop(retries));
            activeFlush = worker;
            worker.start();
        }
    }

    private void runFlushLoop(int firstAttempt) {
        int attempt = firstAttempt;
        boolean retryBudgetExhausted = false;
        try {
            while (true) {
                flushPending();
                boolean retry;
                synchronized (pending) {
                    retry = !pending.isEmpty();
                }
                if (!retry || closed.get()) return;
                if (attempt >= 5) {
                    retryBudgetExhausted = true;
                    return;
                }
                attempt++;
// Wait OUTSIDE the pending lock. close notifies this monitor,
                // so shutdown never pays the whole 500 ms retry back-off.
                synchronized (lifecycleLock) {
                    if (!closed.get()) {
                        try {
                            lifecycleLock.wait(500);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        } finally {
            synchronized (lifecycleLock) {
                activeFlush = null;
                flushing.set(false);
                lifecycleLock.notifyAll();
            }
            // Cover an add that arrived after the final pending check but
            // before flushing=false. Exhausted failures intentionally wait
// for the next user prompt, matching the compatibility retry contract.
            if (!retryBudgetExhausted && !closed.get()) {
                boolean needsAnotherFlush;
                synchronized (pending) {
                    needsAnotherFlush = !pending.isEmpty();
                }
                if (needsAnotherFlush) flushAsyncWithRetries(0);
            }
        }
    }

    /**
     * Completes the original cleanup-promise contract without putting file
     * I/O on the GUI thread: normal submissions remain fire-and-forget, while
     * the owning REPL waits here only after its window has closed.
     */
    @Override
    public void close() {
        Thread worker;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            if (pendingFlush != null) {
                pendingFlush.cancel(false);
                pendingFlush = null;
            }
            worker = activeFlush;
            lifecycleLock.notifyAll();
        }
        if (worker != null && worker != Thread.currentThread()) {
            boolean interrupted = false;
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException _) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (!pending.isEmpty()) flushPending();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException _) {
            // JVM shutdown already started; the hook is either running or queued.
        }
    }

    // Package-private so tests can drive flush cycles deterministically.
    void flushPending() {
        synchronized (flushLock) {
            flushPendingLocked();
        }
    }

    private void flushPendingLocked() {
        try {
            Files.createDirectories(historyFile.getParent());
            ensureHistoryFile();
            try (HistoryFileLock _ = acquireHistoryLock(historyFile)) {
                byte[] bytes;
                synchronized (pending) {
                    if (pending.isEmpty()) return;

                    StringBuilder sb = new StringBuilder();
                    for (Entry e : pending) {
                        ObjectNode node = JsonUtils.getMapper().createObjectNode();
                        node.put("display",   e.display());
                        node.put("sessionId", e.sessionId() != null ? e.sessionId() : "");
                        node.put("timestamp", e.timestamp());
                        node.put("project",   e.project()   != null ? e.project()   : "");
                        node.put("cwd",       e.cwd()       != null ? e.cwd()       : "");
                        if (e.pastedContents() != null && !e.pastedContents().isEmpty()) {
                            ObjectNode pc = node.putObject("pastedContents");
                            for (var kv : e.pastedContents().entrySet()) {
                                PastedContent c = kv.getValue();
                                ObjectNode item = pc.putObject(String.valueOf(kv.getKey()));
                                item.put("id",   c.id());
                                item.put("type", c.type() != null ? c.type() : "text");
                                if (c.mediaType() != null) item.put("mediaType", c.mediaType());
                                if (c.filename() != null) item.put("filename", c.filename());
                                if (c.content() != null) {
                                    if (c.content().length() <= MAX_PASTED_CONTENT_LENGTH) {
                                        item.put("content", c.content());
                                    } else {
                                        item.put("contentHash",
                                            PasteStore.hashPastedText(c.content()));
                                    }
                                }
                            }
                        }
                        sb.append(node).append('\n');
                    }
                    bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                    pending.clear();
                }
                try (FileChannel ch = FileChannel.open(historyFile,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    FileUtils.writeFully(ch, ByteBuffer.wrap(bytes));
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to flush history: {}", e.getMessage());
        }
    }

    private void ensureHistoryFile() throws IOException {
        if (!Files.exists(historyFile)) {
            try {
                Files.createFile(historyFile,
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException _) {
                try {
                    Files.createFile(historyFile);
                } catch (FileAlreadyExistsException _) {
                    // Another writer won the create race.
                }
            } catch (FileAlreadyExistsException _) {
                // Another writer won the create race.
            }
        }

        try (FileChannel ignored = FileChannel.open(historyFile,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {

        }
    }


    private static HistoryFileLock acquireHistoryLock(Path historyFile) throws IOException {
        Path canonical = historyFile.toRealPath();
        Path lockDirectory = Path.of(canonical + ".lock");
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                Files.createDirectory(lockDirectory);
                return new HistoryFileLock(lockDirectory);
            } catch (FileAlreadyExistsException _) {
                if (isStaleLock(lockDirectory)) {
                    try {
                        Files.delete(lockDirectory);
                        continue;
                    } catch (IOException _) {
// The owner may have refreshed or.
                    }
                }
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(50L << attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for history lock", e);
                }
            }
        }
        throw new IOException("Timed out waiting for history lock: " + lockDirectory);
    }

    private static boolean isStaleLock(Path lockDirectory) {
        try {
            return Files.getLastModifiedTime(lockDirectory).toMillis()
                < System.currentTimeMillis() - HISTORY_LOCK_STALE_MS;
        } catch (IOException _) {
            return false;
        }
    }

    private static final class HistoryFileLock implements AutoCloseable {
        private final Path directory;
        private final ScheduledFuture<?> heartbeat;

        private HistoryFileLock(Path directory) {
            this.directory = directory;
            this.heartbeat = HISTORY_FLUSH_SCHEDULER.scheduleAtFixedRate(() -> {
                try {
                    Files.setLastModifiedTime(directory,
                        FileTime.fromMillis(System.currentTimeMillis()));
                } catch (IOException failure) {
// matches proper-lockfile's onCompromised callback.
                    LOG.error("History lock compromised: {}", failure.getMessage());
                }
            }, HISTORY_LOCK_UPDATE_MS, HISTORY_LOCK_UPDATE_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            heartbeat.cancel(false);
            try {
                Files.deleteIfExists(directory);
            } catch (IOException failure) {
                LOG.debug("Failed to release history lock {}: {}",
                    directory, failure.getMessage());
            }
        }
    }

}
