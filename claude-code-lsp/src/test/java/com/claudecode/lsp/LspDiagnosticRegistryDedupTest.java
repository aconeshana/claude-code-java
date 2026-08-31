package com.claudecode.lsp;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers the delivered-fingerprint dedup added to suppress repeat notifications
 * when {@code didChange} churn keeps re-publishing an identical diagnostic set. */
class LspDiagnosticRegistryDedupTest {

    private static Diagnostic diag(String message) {
        return new Diagnostic("Test.java", 1, 2, 1, 10, Diagnostic.Severity.ERROR, message, "javac", null);
    }

    @Test
    void identicalDiagnostics_notifyOnlyOnce() {
        var registry = new LspDiagnosticRegistry();
        AtomicInteger notifications = new AtomicInteger();
        registry.addListener((_, _) -> notifications.incrementAndGet());

        registry.registerDiagnostics("file:///Test.java", List.of(diag("same error")));
        registry.registerDiagnostics("file:///Test.java", List.of(diag("same error")));
        registry.registerDiagnostics("file:///Test.java", List.of(diag("same error")));

        assertEquals(1, notifications.get());
    }

    @Test
    void changedDiagnostics_notifyEveryTime() {
        var registry = new LspDiagnosticRegistry();
        AtomicInteger notifications = new AtomicInteger();
        registry.addListener((_, _) -> notifications.incrementAndGet());

        registry.registerDiagnostics("file:///Test.java", List.of(diag("error A")));
        registry.registerDiagnostics("file:///Test.java", List.of(diag("error B")));

        assertEquals(2, notifications.get());
    }

    @Test
    void differentFiles_areTrackedIndependently() {
        var registry = new LspDiagnosticRegistry();
        AtomicInteger notifications = new AtomicInteger();
        registry.addListener((_, _) -> notifications.incrementAndGet());

        registry.registerDiagnostics("file:///A.java", List.of(diag("same error")));
        registry.registerDiagnostics("file:///B.java", List.of(diag("same error")));

        assertEquals(2, notifications.get());
    }

    @Test
    void clearDiagnostics_resetsDedupState() {
        var registry = new LspDiagnosticRegistry();
        AtomicInteger notifications = new AtomicInteger();
        registry.addListener((_, _) -> notifications.incrementAndGet());

        registry.registerDiagnostics("file:///Test.java", List.of(diag("same error")));
        registry.clearDiagnostics("file:///Test.java");
        registry.registerDiagnostics("file:///Test.java", List.of(diag("same error")));

        // register + clear (always notifies) + register again = 3
        assertEquals(3, notifications.get());
    }

    @Test
    void perFileDiagnostics_cappedAtTen() {
        var registry = new LspDiagnosticRegistry();
        List<Diagnostic> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(diag("error " + i));
        }
        registry.registerDiagnostics("file:///Test.java", many);

        assertEquals(10, registry.getDiagnostics("file:///Test.java").size());
    }

    @Test
    void totalDiagnostics_cappedAtThirty_keepingMostSevere() {
        var registry = new LspDiagnosticRegistry();
        // 4 files × 10 errors = 40 total; cap should drop the least severe 10.
        for (int f = 0; f < 4; f++) {
            List<Diagnostic> diags = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                diags.add(diag("file" + f + " error " + i));
            }
            registry.registerDiagnostics("file:///F" + f + ".java", diags);
        }

        int total = registry.getAllDiagnostics().values().stream().mapToInt(List::size).sum();
        assertEquals(30, total);
        // Every retained diagnostic is an ERROR (all are equally severe here, so 30 of 40 kept).
        assertTrue(registry.getAllDiagnostics().values().stream()
            .flatMap(List::stream)
            .allMatch(d -> d.severity() == Diagnostic.Severity.ERROR));
    }

    @Test
    void severityOrdering_keepsErrorsOverHints() {
        var registry = new LspDiagnosticRegistry();
        List<Diagnostic> mixed = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            mixed.add(new Diagnostic("M.java", 1, 1, 1, 1, Diagnostic.Severity.HINT, "hint", "x", null));
        }
        for (int i = 0; i < 8; i++) {
            mixed.add(new Diagnostic("M.java", 2, 1, 2, 1, Diagnostic.Severity.ERROR, "err", "x", null));
        }
        registry.registerDiagnostics("file:///M.java", mixed);

        // 10 cap: 8 errors + 2 hints (errors preferred).
        List<Diagnostic> kept = registry.getDiagnostics("file:///M.java");
        assertEquals(10, kept.size());
        assertEquals(8, kept.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count());
    }
}
