package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.doctor.DoctorReport;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * State-machine tests for {@link DoctorDialog}, driven directly (no real GUI
 * thread) — same pattern as {@code HooksConfigMenuDialogRefreshTest}.
 */
class DoctorDialogTest {

    private DoctorDialog newDialog() {
        return new DoctorDialog(DoctorDialogTest::emptyReport);
    }

    private static DoctorReport emptyReport() {
        return new DoctorReport(
            new DoctorReport.RuntimeInfo("test"),
            new DoctorReport.RipgrepStatus(true, DoctorReport.RipgrepMode.BUILTIN, null),
            List.of(), List.of(), List.of(),
            new DoctorReport.ContextUsage(null, null, null), List.of(), List.of(),
            List.of(), List.of());
    }

    @Test
    void ripgrepStatusDistinguishesVendorAndSystemModes() {
        assertEquals("OK (vendor)", DoctorDialog.formatRipgrepStatus(
            new DoctorReport.RipgrepStatus(true, DoctorReport.RipgrepMode.BUILTIN, null)));
        assertEquals("OK (rg)", DoctorDialog.formatRipgrepStatus(
            new DoctorReport.RipgrepStatus(true, DoctorReport.RipgrepMode.SYSTEM, "rg")));
        assertEquals("Not working (Java regex fallback)", DoctorDialog.formatRipgrepStatus(
            new DoctorReport.RipgrepStatus(false, DoctorReport.RipgrepMode.BUILTIN, null)));
    }

    /** Blocks until the dialog's background scan finishes (state != LOADING). */
    private void awaitReport(DoctorDialog d) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (d.visibleState() == DoctorDialog.PublicState.LOADING_S) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for REPORT state");
            Thread.sleep(10);
        }
    }

    @Test
    void initialState_isHidden() {
        DoctorDialog d = newDialog();
        assertEquals(DoctorDialog.PublicState.HIDDEN_S, d.visibleState());
        assertFalse(d.isActive());
    }

    /** A collector that parks until released, so LOADING can be observed deterministically. */
    private static DoctorDialog gatedDialog(CountDownLatch release) {
        return new DoctorDialog(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return emptyReport();
        });
    }

    @Test
    void show_synchronouslyTransitionsToLoading() {
        // Gate the collector rather than racing it: with the default collector the scan
        // can reach REPORT before the assertion runs, which made this test fail only
        // under full-suite load.
        CountDownLatch release = new CountDownLatch(1);
        DoctorDialog d = gatedDialog(release);
        try {
            d.show(() -> {});
            assertEquals(DoctorDialog.PublicState.LOADING_S, d.visibleState());
            assertTrue(d.isActive());
        } finally {
            release.countDown();
        }
    }

    @Test
    void dismissDuringScan_isNotUndoneWhenTheScanFinishes() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        DoctorDialog d = gatedDialog(release);
        d.show(() -> {});

        d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertEquals(DoctorDialog.PublicState.HIDDEN_S, d.visibleState());

        release.countDown();
        assertFalse(becomesReport(d), "a scan that finishes after Esc must not reopen the dialog");
    }

    @Test
    void reopeningDuringScan_ignoresTheAbandonedScan() throws InterruptedException {
        CountDownLatch firstScan = new CountDownLatch(1);
        CountDownLatch secondScan = new CountDownLatch(1);
        AtomicInteger scans = new AtomicInteger();
        DoctorDialog d = new DoctorDialog(() -> {
            CountDownLatch gate = scans.incrementAndGet() == 1 ? firstScan : secondScan;
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return emptyReport();
        });

        d.show(() -> {});
        d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        d.show(() -> {});

        // The abandoned first scan completes last; the dialog must still be waiting on
        // the second one rather than showing a report nobody asked for.
        firstScan.countDown();
        assertFalse(becomesReport(d), "the superseded scan must not publish");

        secondScan.countDown();
        awaitReport(d);
        assertEquals(DoctorDialog.PublicState.REPORT_S, d.visibleState());
    }

    /** True if the dialog reaches REPORT within a short grace period. */
    private boolean becomesReport(DoctorDialog d) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < deadline) {
            if (d.visibleState() == DoctorDialog.PublicState.REPORT_S) return true;
            Thread.sleep(10);
        }
        return false;
    }

    @Test
    void show_backgroundScanCompletesToReportWithLines(@TempDir Path tmp) throws InterruptedException {
        DoctorDialog d = newDialog();
        d.show(() -> {});
        awaitReport(d);

        assertEquals(DoctorDialog.PublicState.REPORT_S, d.visibleState());
        assertTrue(d.lineCount() > 0);
    }

    @Test
    void loadingState_swallowsKeysWithoutChangingState() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        DoctorDialog d = gatedDialog(release);
        try {
            d.show(() -> {});
            AtomicBoolean deliver = new AtomicBoolean(true);

            d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), deliver);

            assertFalse(deliver.get());
            assertEquals(DoctorDialog.PublicState.LOADING_S, d.visibleState());
        } finally {
            release.countDown();
        }
    }

    @Test
    void confirmationBindingsDismissEvenWhileLoading(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Confirmation","bindings":{
              "x":"confirm:yes",
              "z":"confirm:no",
              "escape":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        CountDownLatch release = new CountDownLatch(1);
        try {
            AtomicInteger dismissed = new AtomicInteger();
            DoctorDialog d = gatedDialog(release);
            d.setKeybindingsStore(store);
            d.show(dismissed::incrementAndGet);

            d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
            assertTrue(d.isActive(), "null-unbound Escape must not use the hard-coded fallback");

            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            assertFalse(d.isActive(), "confirm:yes is registered by the TS Doctor screen");
            assertEquals(1, dismissed.get());
        } finally {
            release.countDown();
            store.dispose();
        }
    }

    @Test
    void reportState_arrowKeysAdjustScrollWithinBounds(@TempDir Path tmp) throws InterruptedException {
        DoctorDialog d = newDialog();
        d.show(() -> {});
        awaitReport(d);

        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(new KeyStroke(KeyType.ARROW_UP), deliver);
        assertEquals(0, d.scrollOffset(), "cannot scroll above 0");

        d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), deliver);
        assertTrue(d.scrollOffset() >= 0);

        d.handleKey(new KeyStroke(KeyType.END), deliver);
        int maxScroll = Math.max(0, d.lineCount() - 18);
        assertEquals(maxScroll, d.scrollOffset());

        d.handleKey(new KeyStroke(KeyType.HOME), deliver);
        assertEquals(0, d.scrollOffset());
    }

    @Test
    void reportState_pageUpPageDownAdjustScroll(@TempDir Path tmp) throws InterruptedException {
        DoctorDialog d = newDialog();
        d.show(() -> {});
        awaitReport(d);

        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(new KeyStroke(KeyType.PAGE_DOWN), deliver);
        int afterPageDown = d.scrollOffset();
        assertTrue(afterPageDown >= 0);

        d.handleKey(new KeyStroke(KeyType.PAGE_UP), deliver);
        assertEquals(0, d.scrollOffset());
    }

    @Test
    void escapeAndEnterBothCloseTheDialog(@TempDir Path tmp) throws InterruptedException {
        DoctorDialog d1 = newDialog();
        AtomicInteger dismissed1 = new AtomicInteger();
        d1.show(dismissed1::incrementAndGet);
        awaitReport(d1);
        d1.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertEquals(DoctorDialog.PublicState.HIDDEN_S, d1.visibleState());
        assertFalse(d1.isActive());
        assertEquals(1, dismissed1.get());

        DoctorDialog d2 = newDialog();
        AtomicInteger dismissed2 = new AtomicInteger();
        d2.show(dismissed2::incrementAndGet);
        awaitReport(d2);
        d2.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals(DoctorDialog.PublicState.HIDDEN_S, d2.visibleState());
        assertEquals(1, dismissed2.get());
    }

    @Test
    void collectorFailure_stillReachesReportWithFallbackLine() throws InterruptedException {
        DoctorDialog d = new DoctorDialog(
            () -> { throw new RuntimeException("boom"); });

        CountDownLatch latch = new CountDownLatch(1);
        d.show(latch::countDown);

        // Spinning without yielding pegged a core for as long as the scan took, which
        // starved the very thread this test waits on when the suite runs in parallel.
        awaitReport(d);

        assertEquals(DoctorDialog.PublicState.REPORT_S, d.visibleState());
        assertEquals(1, d.lineCount());
    }

    @Test
    void hiddenState_preferredSizeIsZero() {
        DoctorDialog d = newDialog();
        var size = d.calculatePreferredSize();
        assertEquals(0, size.getColumns());
        assertEquals(0, size.getRows());
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}
