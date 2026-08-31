package com.claudecode.keybindings;


import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class UserKeybindingsStore {

    /** Legacy preview flag retained for callers that still reference the old setting name. */
    public static final String FEATURE_ENV = "CLAUDE_CODE_ENABLE_KEYBINDINGS";

/**
     * File-stability threshold (ms) before a reload fires.
     */
    private static final long STABILITY_MS = 500;

/**
     * The merged result handed to subscribers.
     */
    public record KeybindingsLoadResult(
        List<KeybindingResolver.ParsedBinding> bindings,
        List<KeybindingValidator.KeybindingWarning> warnings
    ) {}

/** Subscription handle returned by {@link #subscribe}; call {@link #close} to unsubscribe. */
    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    private final Path file;
    private final boolean enabled;

    /** Current merged resolver — swapped atomically on reload. */
    private volatile KeybindingResolver current;
    /** Validation warnings from the last load/reload. */
    private volatile List<KeybindingValidator.KeybindingWarning> warnings;

    private final List<Consumer<KeybindingsLoadResult>> listeners = new CopyOnWriteArrayList<>();
    private final Object reloadLock = new Object();

    private volatile WatchService watcher;
    private volatile boolean closed = false;

    // ── construction ─────────────────────────────────────────────────────────

/** Build the released store over the user's keybindings file. */
    @Explanation("Claude Code 2.1.197 enables keybinding customization without the former preview gate")
    public static UserKeybindingsStore create() {
        return create(ClaudePaths.KEYBINDINGS_JSON, true);
    }

    /** Package/test entry: build a store over {@code file} with an explicit gate. */
    static UserKeybindingsStore create(Path file, boolean enabled) {
        UserKeybindingsStore store = new UserKeybindingsStore(file, enabled);
        store.init();
        return store;
    }

    private UserKeybindingsStore(Path file, boolean enabled) {
        this.file = file;
        this.enabled = enabled;
        this.current = KeybindingResolver.defaultResolver();
        this.warnings = List.of();
    }

    private void init() {
        if (!enabled) {
            // External users always use defaults; never read the file or watch it.
            return;
        }
        synchronized (reloadLock) {
            reloadLocked();
            startWatcher();
        }
    }

    // ── accessors ──────────────────────────────────────────────────────────────

    /** The currently-resolved keybindings (merged defaults + user). Read atomically on each keypress. */
    public KeybindingResolver currentResolver() {
        return current;
    }

    /** Validation warnings from the last load/reload. */
    public List<KeybindingValidator.KeybindingWarning> warnings() {
        return warnings;
    }

    /** True when user customization is active (gate on). */
    public boolean isEnabled() {
        return enabled;
    }

/** Subscribe to keybinding changes. Returns a handle whose {@link #close} unsubscribes. */
    public Subscription subscribe(Consumer<KeybindingsLoadResult> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    // ── loading ─────────────────────────────────────────────────────────────────

    /** Reload from disk. No-op when the gate is off (defaults are immutable in that mode). */
    public void reload() {
        if (!enabled) return;
        synchronized (reloadLock) {
            reloadLocked();
        }
    }

    private void reloadLocked() {
        List<KeybindingResolver.ParsedBinding> defaults = KeybindingResolver.defaultsAsBindings();
        List<KeybindingResolver.ParsedBinding> merged;
        List<KeybindingValidator.KeybindingWarning> newWarnings;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = JsonUtils.getMapper().readTree(content);
            List<KeybindingResolver.ParsedBinding> userParsed =
                KeybindingResolver.fromUserJson(extractUserBlocks(root));
            merged = userParsed.isEmpty()
                ? defaults : KeybindingResolver.merge(defaults, userParsed);
            newWarnings = KeybindingValidator.validateFile(content);
        } catch (NoSuchFileException _) {
            // File absent — user can run /keybindings to create it. Defaults only, no warning.
            merged = defaults;
            newWarnings = List.of();
        } catch (IOException e) {
            // Other read error — fall back to defaults with a warning.
            merged = defaults;
            newWarnings = List.of(parseError("Failed to read keybindings.json: " + e.getMessage()));
        } catch (Exception e) {
            // Parse/validate error — fall back to defaults with a warning.
            merged = defaults;
            newWarnings = List.of(parseError("Failed to parse keybindings.json: " + e.getMessage()));
        }
        current = merged == defaults
            ? KeybindingResolver.defaultResolver()
            : new KeybindingResolver(merged);
        warnings = List.copyOf(newWarnings);
        notifyListeners();
    }

    /** Extract the user bindings array from a parsed file — supports both bare array and {@code {bindings:[...]}} wrapper. */
    private static JsonNode extractUserBlocks(JsonNode root) {
        if (root.isArray()) return root;
        if (root.isObject() && root.has("bindings") && root.get("bindings").isArray()) {
            return root.get("bindings");
        }
        // No usable array — merge yields defaults only; validateFile(content) emits the real error.
        return JsonUtils.getMapper().createArrayNode();
    }

    private static KeybindingValidator.KeybindingWarning parseError(String message) {
        return new KeybindingValidator.KeybindingWarning(
            KeybindingValidator.WarningType.PARSE_ERROR,
            KeybindingValidator.Severity.ERROR,
            message, null, null, null, null);
    }


    private void handleDelete() {
        synchronized (reloadLock) {
            current = KeybindingResolver.defaultResolver();
            warnings = List.of();
        }
        notifyListeners();
    }

    private void notifyListeners() {
        KeybindingsLoadResult result = new KeybindingsLoadResult(current.bindings(), warnings);
        for (Consumer<KeybindingsLoadResult> l : listeners) {
            try {
                l.accept(result);
            } catch (RuntimeException _) {
                // A misbehaving listener must not break the reload.
            }
        }
    }

    // ── hot reload ──────────────────────────────────────────────────────────────

    private void startWatcher() {
        Path dir = file.getParent();
        if (dir == null || !Files.isDirectory(dir)) return;
        WatchService ws;
        try {
            ws = FileSystems.getDefault().newWatchService();
            dir.register(ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException _) {
            return; // best-effort; keybindings still work without hot reload
        }
        this.watcher = ws;
        Path fileName = file.getFileName();
        Thread.startVirtualThread(() -> watchLoop(ws, fileName));
    }

    private void watchLoop(WatchService ws, Path fileName) {
        try {
            while (!closed) {
                WatchKey key = ws.take();
                if (closed) break;
                boolean relevant = false;
                for (WatchEvent<?> ev : key.pollEvents()) {
                    if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Path name = asFileName(ev.context());
                    if (name != null && name.equals(fileName)) relevant = true;
                }
                key.reset();
                if (!relevant) continue;

                // Debounce until STABILITY_MS has elapsed since the last event for the
                // target file. WatchService already blocks for the remaining quiet time;

                // polling interval. Unrelated sibling events must not reset the deadline.
                long quietDeadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(STABILITY_MS);
                while (!closed) {
                    long remaining = quietDeadline - System.nanoTime();
                    if (remaining <= 0) break;
                    WatchKey k = ws.poll(remaining, TimeUnit.NANOSECONDS);
                    if (k == null) break; // quiet period elapsed -> file is stable
                    boolean rel2 = false;
                    for (WatchEvent<?> ev : k.pollEvents()) {
                        if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        Path n = asFileName(ev.context());
                        if (n != null && n.equals(fileName)) rel2 = true;
                    }
                    k.reset();
                    if (rel2) {
                        quietDeadline = System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(STABILITY_MS);
                    }
                }
                if (closed) break;

                if (Files.exists(file)) reload();
                else handleDelete();
            }
        } catch (ClosedWatchServiceException | InterruptedException _) {
            // watcher closed or thread interrupted — stop the loop.
        }
    }

    private static Path asFileName(Object context) {
        return context instanceof Path p ? p.getFileName() : null;
    }

    // ── disposal / testing ──────────────────────────────────────────────────────

/**
     * Dispose the watcher and clear listeners.
     */
    public void dispose() {
        closed = true;
        WatchService ws = this.watcher;
        if (ws != null) {
            try {
                ws.close();
            } catch (IOException _) {
                // best-effort
            }
        }
        listeners.clear();
    }
}
