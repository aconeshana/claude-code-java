package com.claudecode.keybindings;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.KeybindingValidator.WarningType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;


class UserKeybindingsStoreTest {

    private UserKeybindingsStore store;

    @AfterEach
    void tearDown() {
        if (store != null) store.dispose();
    }

    // ── gate off ─────────────────────────────────────────────────────────────

    @Test
    void gateOff_servesDefaultsOnly_andNoWatcher() throws IOException {
        store = UserKeybindingsStore.create(tempFile("{}"), false);
        assertFalse(store.isEnabled());
        // Defaults still resolve.
        var r = store.currentResolver().resolve(List.of("Chat"), KeystrokeParser.parseKeystroke("enter"));
        assertInstanceOf(KeybindingResolver.ResolveResult.Match.class, r);
        assertEquals("chat:submit", ((KeybindingResolver.ResolveResult.Match) r).action());
        // No watcher started; reload is a no-op.
        store.reload();
        assertTrue(store.warnings().isEmpty());
    }

    // ── parse + positional merge ──────────────────────────────────────────────

    @Test
    void gateOn_mergesUserOverrides_lastWins() throws IOException {
        Path file = tempFile(wrapper(List.of(
            block("Chat", "up", "history:next"))));
        store = UserKeybindingsStore.create(file, true);

        // User overrides the default Chat.up ("history:previous").
        var up = store.currentResolver().resolve(List.of("Chat"), KeystrokeParser.parseKeystroke("up"));
        assertInstanceOf(KeybindingResolver.ResolveResult.Match.class, up);
        assertEquals("history:next", ((KeybindingResolver.ResolveResult.Match) up).action());

        // Untouched defaults still resolve.
        var enter = store.currentResolver().resolve(List.of("Chat"), KeystrokeParser.parseKeystroke("enter"));
        assertInstanceOf(KeybindingResolver.ResolveResult.Match.class, enter);
        assertEquals("chat:submit", ((KeybindingResolver.ResolveResult.Match) enter).action());
    }

    @Test
    void gateOn_bareArrayFormAccepted() throws IOException {
        Path file = tempFile(bareArray(List.of(
            block("Chat", "ctrl+g", "chat:externalEditor"))));
        store = UserKeybindingsStore.create(file, true);
        var g = store.currentResolver().resolve(List.of("Chat"), KeystrokeParser.parseKeystroke("ctrl+g"));
        assertInstanceOf(KeybindingResolver.ResolveResult.Match.class, g);
        assertEquals("chat:externalEditor", ((KeybindingResolver.ResolveResult.Match) g).action());
    }

    // ── validation warnings ────────────────────────────────────────────────────

    @Test
    void gateOn_reportsInvalidContextWarning() throws IOException {
        Path file = tempFile(wrapper(List.of(
            block("NotAContext", "up", "history:next"))));
        store = UserKeybindingsStore.create(file, true);
        assertTrue(store.warnings().stream().anyMatch(w -> w.type() == WarningType.INVALID_CONTEXT));
    }

    @Test
    void missingBindingsArray_isParseError() throws IOException {
        Path file = tempFile("{}");
        store = UserKeybindingsStore.create(file, true);
        assertTrue(store.warnings().stream().anyMatch(w -> w.type() == WarningType.PARSE_ERROR));
    }

    @Test
    void fileAbsent_usesDefaults_noWarning() throws IOException {
        Path file = tempDir().resolve("absent-keybindings.json");
        assertFalse(Files.exists(file));
        store = UserKeybindingsStore.create(file, true);
        assertTrue(store.warnings().isEmpty());
        var enter = store.currentResolver().resolve(List.of("Chat"), KeystrokeParser.parseKeystroke("enter"));
        assertInstanceOf(KeybindingResolver.ResolveResult.Match.class, enter);
    }

    // ── hot reload ──────────────────────────────────────────────────────────────

    @Test
    void hotReload_onModify_notifiesAndSwapsResolver() throws Exception {
        Path file = tempFile(wrapper(List.of(block("Chat", "up", "history:next"))));
        store = UserKeybindingsStore.create(file, true);

        AtomicBoolean fired = new AtomicBoolean(false);
        AtomicReference<List<KeybindingResolver.ParsedBinding>> seen = new AtomicReference<>();
        store.subscribe(r -> {
            fired.set(true);
            seen.set(r.bindings());
        });

        // Change the user binding; the watcher should debounce and reload.
        Files.writeString(file, wrapper(List.of(block("Chat", "up", "foo:bar"))),
            StandardCharsets.UTF_8);

        waitFor(() -> {
            var up = store.currentResolver().resolve(List.of("Chat"), KeystrokeParser.parseKeystroke("up"));
            return fired.get() && up instanceof KeybindingResolver.ResolveResult.Match m
                && Strings.CS.equals("foo:bar", m.action());
        });
        assertTrue(fired.get(), "listener should have fired on reload");
    }

    @Test
    void hotReload_onDelete_resetsToDefaults() throws Exception {
        Path file = tempFile(wrapper(List.of(block("Chat", "up", "history:next"))));
        store = UserKeybindingsStore.create(file, true);

        AtomicBoolean fired = new AtomicBoolean(false);
        store.subscribe(_ -> fired.set(true));

        Files.delete(file);

        waitFor(() -> {
            if (!fired.get()) return false;
            var up = store.currentResolver().resolve(List.of("Chat"), KeystrokeParser.parseKeystroke("up"));
            return up instanceof KeybindingResolver.ResolveResult.Match m
                && Strings.CS.equals("history:previous", m.action()); // back to default
        });
        assertTrue(fired.get(), "listener should have fired on delete");
    }

    @Test
    void hotReload_unrelatedDirectoryEventsDoNotExtendTargetDebounce() throws Exception {
        Path file = tempFile(wrapper(List.of(block("Chat", "up", "history:next"))));
        store = UserKeybindingsStore.create(file, true);

        CountDownLatch reloaded = new CountDownLatch(1);
        store.subscribe(_ -> {
            var up = store.currentResolver().resolve(
                List.of("Chat"), KeystrokeParser.parseKeystroke("up"));
            if (up instanceof KeybindingResolver.ResolveResult.Match m
                    && Strings.CS.equals("foo:bar", m.action())) {
                reloaded.countDown();
            }
        });

        CountDownLatch stopNoise = new CountDownLatch(1);
        CountDownLatch noiseStarted = new CountDownLatch(1);
        Thread noise = Thread.ofVirtual().start(() -> {
            int sequence = 0;
            try {
                while (!stopNoise.await(40, TimeUnit.MILLISECONDS)) {
                    Path sibling = file.getParent().resolve("watch-noise-" + sequence++);
                    Files.writeString(sibling, "noise", StandardCharsets.UTF_8);
                    Files.deleteIfExists(sibling);
                    noiseStarted.countDown();
                }
            } catch (IOException | InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            assertTrue(noiseStarted.await(1, TimeUnit.SECONDS), "noise writer did not start");
            Files.writeString(file, wrapper(List.of(block("Chat", "up", "foo:bar"))),
                StandardCharsets.UTF_8);

            assertTrue(reloaded.await(3, TimeUnit.SECONDS),
                "unrelated sibling events must not postpone the target file's 500ms debounce");
        } finally {
            stopNoise.countDown();
            noise.join(1000);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("keybindings-test");
    }

    private static Path tempFile(String content) throws IOException {
        Path dir = tempDir();
        Path file = dir.resolve("keybindings.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String wrapper(List<String> blocks) {
        return "{\n  \"$schema\": \"x\",\n  \"bindings\": [\n" + String.join(",\n", blocks) + "\n  ]\n}\n";
    }

    private static String bareArray(List<String> blocks) {
        return "[\n" + String.join(",\n", blocks) + "\n]\n";
    }

    private static String block(String context, String key, String action) {
        return "    { \"context\": \"" + context + "\", \"bindings\": { \"" + key + "\": \"" + action + "\" } }";
    }

    private static void waitFor(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("condition not met within timeout");
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
