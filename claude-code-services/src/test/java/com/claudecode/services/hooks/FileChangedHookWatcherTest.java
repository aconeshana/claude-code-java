package com.claudecode.services.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.HookDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileChangedHookWatcherTest {

    @Test
    void watchesStaticAndDynamicTargetsAndDebouncesEventsWithoutSleeping(
            @TempDir Path cwd) {
        CapturingDispatcher hooks = new CapturingDispatcher();
        FakeBackendFactory backends = new FakeBackendFactory();
        FakeDebouncer debouncer = new FakeDebouncer();
        FileChangedHookWatcher watcher = new FileChangedHookWatcher(
            hooks, backends, debouncer);

        watcher.initialize(cwd, List.of(".env|config.json"));

        assertEquals(Set.of(cwd.resolve(".env"), cwd.resolve("config.json")),
            backends.latestTargets());
        backends.emit(cwd.resolve(".env"), "add");
        backends.emit(cwd.resolve(".env"), "change");
        assertTrue(hooks.fileEvents.isEmpty());

        debouncer.run(cwd.resolve(".env"));

        assertEquals(List.of(cwd.resolve(".env") + ":change"), hooks.fileEvents);

        Path dynamic = cwd.resolve("dynamic.txt").toAbsolutePath();
        watcher.replaceWatchPaths(List.of(dynamic));
        assertEquals(Set.of(cwd.resolve(".env"), cwd.resolve("config.json"), dynamic),
            backends.latestTargets());

        watcher.close();
        assertTrue(backends.latest.closed);
        assertTrue(debouncer.closed);
    }

    @Test
    void cwdChangeRebasesStaticMatchersAndAppliesReturnedDynamicPaths(
            @TempDir Path root) {
        Path oldCwd = root.resolve("old");
        Path newCwd = root.resolve("new");
        Path returned = root.resolve("returned.env").toAbsolutePath();
        CapturingDispatcher hooks = new CapturingDispatcher();
        hooks.cwdOutcome = new HookDispatcher.HookOutcome(
            true, null, List.of(), false, null, null, List.of(), List.of(
                new HookDispatcher.HookSpecificOutput("CwdChanged",
                    HookTestJson.specific("CwdChanged", returned))));
        FakeBackendFactory backends = new FakeBackendFactory();
        FileChangedHookWatcher watcher = new FileChangedHookWatcher(
            hooks, backends, new FakeDebouncer());
        watcher.initialize(oldCwd, List.of(".env"));

        watcher.onCwdChanged(newCwd);

        assertEquals(List.of(oldCwd + "->" + newCwd), hooks.cwdEvents);
        assertEquals(Set.of(newCwd.resolve(".env"), returned), backends.latestTargets());
    }

    private static final class CapturingDispatcher implements HookDispatcher {
        private final List<String> fileEvents = new ArrayList<>();
        private final List<String> cwdEvents = new ArrayList<>();
        private HookOutcome cwdOutcome = HookOutcome.PROCEED;

        @Override public HookOutcome dispatchFileChangedWithOutcome(
                String filePath, String fileEvent) {
            fileEvents.add(filePath + ":" + fileEvent);
            return HookOutcome.PROCEED;
        }

        @Override public HookOutcome dispatchCwdChangedWithOutcome(String oldCwd, String newCwd) {
            cwdEvents.add(oldCwd + "->" + newCwd);
            return cwdOutcome;
        }

        @Override public boolean dispatchPreToolUse(String toolName,
                JsonNode input, String toolUseId) { return true; }
        @Override public void dispatchPostToolUse(String toolName,
                JsonNode input, JsonNode output, String toolUseId) { }
        @Override public void dispatchUserPromptSubmit(String prompt) { }
        @Override public void dispatchSessionStart(String trigger) { }
        @Override public void dispatchStop(String reason) { }
        @Override public void dispatchSessionEnd(String reason) { }
    }

    private static final class FakeBackendFactory
            implements FileChangedHookWatcher.BackendFactory {
        private FakeBackend latest;

        @Override public FileChangedHookWatcher.Backend create(
                Set<Path> targets, FileChangedHookWatcher.EventConsumer consumer) {
            latest = new FakeBackend(Set.copyOf(targets), consumer);
            return latest;
        }

        Set<Path> latestTargets() { return latest.targets; }

        void emit(Path path, String event) { latest.consumer.accept(path, event); }
    }

    private static final class FakeBackend implements FileChangedHookWatcher.Backend {
        private final Set<Path> targets;
        private final FileChangedHookWatcher.EventConsumer consumer;
        private boolean closed;

        private FakeBackend(Set<Path> targets, FileChangedHookWatcher.EventConsumer consumer) {
            this.targets = targets;
            this.consumer = consumer;
        }

        @Override public void close() { closed = true; }
    }

    private static final class FakeDebouncer implements FileChangedHookWatcher.Debouncer {
        private final Map<Path, Runnable> pending = new LinkedHashMap<>();
        private boolean closed;

        @Override public void submit(Path path, Runnable task) { pending.put(path, task); }

        void run(Path path) { pending.remove(path).run(); }

        @Override public void close() { closed = true; }
    }
}
