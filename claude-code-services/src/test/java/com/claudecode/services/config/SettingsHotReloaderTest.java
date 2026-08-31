package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeEvent.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link SettingsHotReloader#handleEvent} directly with fabricated {@link
 * DirectoryChangeEvent}s so the state machine (debounce / delete grace / internal-write
 * suppression) is exercised without relying on the OS file watcher — which is inherently flaky on
 * CI (macOS FSEvents batching, inotify race with tmp-dir setup, etc.).
 */
class SettingsHotReloaderTest {

    // Aggressive timings so the tests finish fast.
    private static final long DEBOUNCE_MS = 30;
    private static final long DELETE_GRACE_MS = 60;
    private static final long INTERNAL_WRITE_WINDOW_MS = 5000;

    // Give scheduled tasks generous headroom to run — CI shared runners are noisy.
    private static final long WAIT_MULTIPLIER = 6;

    @TempDir Path tmp;

    private Path userPath;
    private Path projectPath;
    private Path localPath;
    private Path policyPath;
    private List<RuleSource> notifications;
    private SettingsHotReloader reloader;

    @BeforeEach
    void setup() {
        InternalWrites.clearInternalWrites();
        userPath    = tmp.resolve("user/settings.json");
        projectPath = tmp.resolve("project/settings.json");
        localPath   = tmp.resolve("project/settings.local.json");
        policyPath  = tmp.resolve("policy/managed-settings.json");
        notifications = new ArrayList<>();
        reloader = new SettingsHotReloader(
            userPath, projectPath, localPath,
            src -> notifications.add(src),
            DEBOUNCE_MS, DELETE_GRACE_MS, INTERNAL_WRITE_WINDOW_MS);
    }

    // ── Debounce ─────────────────────────────────────────────────────────

    @Test
    void singleModify_firesOnceAfterDebounce() throws Exception {
        reloader.handleEvent(event(EventType.MODIFY, userPath));
        awaitFirst();
        assertEquals(List.of(RuleSource.USER_SETTINGS), notifications);
    }

    @Test
    void rapidWrites_coalesceIntoOneFire() throws Exception {
        reloader.handleEvent(event(EventType.MODIFY, userPath));
        reloader.handleEvent(event(EventType.MODIFY, userPath));
        reloader.handleEvent(event(EventType.MODIFY, userPath));
        awaitFirst();
        // Give any late-scheduled duplicates a chance to fire.
        Thread.sleep(DEBOUNCE_MS * 2);
        assertEquals(1, notifications.size(),
            "burst of writes within debounce window must coalesce");
    }

    @Test
    void writesToDifferentFiles_fireIndependently() throws Exception {
        reloader.handleEvent(event(EventType.MODIFY, userPath));
        reloader.handleEvent(event(EventType.MODIFY, projectPath));
        Thread.sleep(DEBOUNCE_MS * WAIT_MULTIPLIER);
        assertEquals(2, notifications.size());
        assertTrue(notifications.contains(RuleSource.USER_SETTINGS));
        assertTrue(notifications.contains(RuleSource.PROJECT_SETTINGS));
    }

    // ── Internal-write suppression ───────────────────────────────────────

    @Test
    void internalWriteMark_suppressesNextEvent() throws Exception {
        InternalWrites.markInternalWrite(userPath);
        reloader.handleEvent(event(EventType.MODIFY, userPath));
        Thread.sleep(DEBOUNCE_MS * WAIT_MULTIPLIER);
        assertTrue(notifications.isEmpty(),
            "marked path must be silently skipped");
    }

    @Test
    void internalWriteMark_isSingleShot_secondEventFires() throws Exception {
        InternalWrites.markInternalWrite(userPath);
        reloader.handleEvent(event(EventType.MODIFY, userPath));   // suppressed
        Thread.sleep(DEBOUNCE_MS);
        reloader.handleEvent(event(EventType.MODIFY, userPath));   // real change
        Thread.sleep(DEBOUNCE_MS * WAIT_MULTIPLIER);
        assertEquals(1, notifications.size(),
            "the second event (external) must fire — mark is single-shot");
    }

    @Test
    void internalWriteMark_doesNotSuppressDeleteGracePath() throws Exception {
        InternalWrites.markInternalWrite(userPath);
        reloader.handleEvent(event(EventType.DELETE, userPath));
        long deadline = System.currentTimeMillis() + DELETE_GRACE_MS * WAIT_MULTIPLIER;
        while (notifications.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(List.of(RuleSource.USER_SETTINGS), notifications,
            "TS consumes internal-write markers only for change/add, not unlink");
    }

    // ── Delete grace period ──────────────────────────────────────────────

    @Test
    void deleteThenRecreate_withinGrace_treatedAsChange() throws Exception {
        reloader.handleEvent(event(EventType.DELETE, userPath));
        // Immediately re-created (IDE atomic-save pattern).
        reloader.handleEvent(event(EventType.CREATE, userPath));
        awaitFirst();
        Thread.sleep(DELETE_GRACE_MS * 2);
        assertEquals(1, notifications.size(),
            "delete + recreate inside grace should collapse to a single change");
    }

    @Test
    void deleteWithoutRecreate_firesAfterGrace() throws Exception {
        reloader.handleEvent(event(EventType.DELETE, userPath));
        // Wait past grace but be patient about scheduler latency.
        long deadline = System.currentTimeMillis() + DELETE_GRACE_MS * WAIT_MULTIPLIER;
        while (notifications.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(List.of(RuleSource.USER_SETTINGS), notifications);
    }

    @Test
    void doubleDelete_coalesces() throws Exception {
        reloader.handleEvent(event(EventType.DELETE, userPath));
        reloader.handleEvent(event(EventType.DELETE, userPath));
        Thread.sleep(DELETE_GRACE_MS * WAIT_MULTIPLIER);
        assertEquals(1, notifications.size(),
            "back-to-back deletes should not fire twice");
    }

    // ── Unrelated files ──────────────────────────────────────────────────

    @Test
    void eventOnUnrelatedFile_ignored() throws Exception {
        Path stranger = tmp.resolve("user/random.txt");
        reloader.handleEvent(event(EventType.MODIFY, stranger));
        Thread.sleep(DEBOUNCE_MS * WAIT_MULTIPLIER);
        assertTrue(notifications.isEmpty(),
            "only the three known settings paths trigger the listener");
    }

    @Test
    void managedPolicyDropIn_mapsToPolicySettings() throws Exception {
        SettingsHotReloader r = new SettingsHotReloader(
            userPath, projectPath, localPath, policyPath,
            src -> notifications.add(src),
            DEBOUNCE_MS, DELETE_GRACE_MS, INTERNAL_WRITE_WINDOW_MS);
        try {
            Path dropIn = policyPath.getParent().resolve("managed-settings.d/20-security.json");
            r.handleEvent(event(EventType.CREATE, dropIn));
            awaitFirst();
            assertEquals(List.of(RuleSource.POLICY_SETTINGS), notifications);
        } finally {
            r.close();
        }
    }

    @Test
    void freshInstall_stillStartsMdmPollingWithoutFilesystemDirectories() throws Exception {
        reloader.start();
        Field field = SettingsHotReloader.class.getDeclaredField("mdmRefresh");
        field.setAccessible(true);
        assertNotNull(field.get(reloader),
            "MDM polling must not depend on settings directories existing");
    }

    // ── Path routing ─────────────────────────────────────────────────────

    @Test
    void projectPath_mapsToProjectSettings() throws Exception {
        reloader.handleEvent(event(EventType.MODIFY, projectPath));
        awaitFirst();
        assertEquals(List.of(RuleSource.PROJECT_SETTINGS), notifications);
    }

    @Test
    void localPath_mapsToLocalSettings() throws Exception {
        reloader.handleEvent(event(EventType.MODIFY, localPath));
        awaitFirst();
        assertEquals(List.of(RuleSource.LOCAL_SETTINGS), notifications);
    }

    // ── Listener resilience ──────────────────────────────────────────────

    @Test
    void listenerException_doesNotKillFuturNotifications() throws Exception {
        CountDownLatch secondCall = new CountDownLatch(1);
        List<RuleSource> observed = new ArrayList<>();
        SettingsHotReloader r = new SettingsHotReloader(
            userPath, projectPath, localPath,
            src -> {
                observed.add(src);
                if (observed.size() == 1) throw new RuntimeException("boom");
                secondCall.countDown();
            },
            DEBOUNCE_MS, DELETE_GRACE_MS, INTERNAL_WRITE_WINDOW_MS);
        try {
            r.handleEvent(event(EventType.MODIFY, userPath));   // throws
            Thread.sleep(DEBOUNCE_MS * 2);
            r.handleEvent(event(EventType.MODIFY, projectPath)); // must still fire
            assertTrue(secondCall.await(DEBOUNCE_MS * WAIT_MULTIPLIER, TimeUnit.MILLISECONDS),
                "listener exception must be swallowed — next event still fires");
            assertEquals(2, observed.size());
        } finally {
            r.close();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private DirectoryChangeEvent event(EventType type, Path path) {
        // count=1, hash=null, rootPath=parent — matches what directory-watcher
        // produces for a real MODIFY inside a watched dir.
        return new DirectoryChangeEvent(type, /*isDirectory*/ false,
            path.toAbsolutePath().normalize(), /*hash*/ null, /*count*/ 1,
            path.toAbsolutePath().normalize().getParent());
    }

    private void awaitFirst() throws InterruptedException {
        long deadline = System.currentTimeMillis() + DEBOUNCE_MS * WAIT_MULTIPLIER;
        while (notifications.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(notifications.isEmpty(),
            "listener never fired within " + (DEBOUNCE_MS * WAIT_MULTIPLIER) + "ms");
    }
}
