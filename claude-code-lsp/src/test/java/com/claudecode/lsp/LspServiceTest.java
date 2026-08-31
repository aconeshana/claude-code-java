package com.claudecode.lsp;

import org.apache.commons.lang3.Strings;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LspServiceTest {

    private LspService lspService;

    @BeforeEach
    void setUp() {
        lspService = new LspService();
    }

    @Test
    void registerAndQueryServer() {
        StubLspServer server = new StubLspServer("java");
        server.addDiagnostic(new Diagnostic(
                "Test.java", 0, 0, 0, 10,
                Diagnostic.Severity.ERROR, "Syntax error", "javac", "E001"));

        lspService.registerServer(server);

        List<Diagnostic> diagnostics = lspService.getDiagnostics(Path.of("Test.java"));
        assertEquals(1, diagnostics.size());
        assertEquals("Syntax error", diagnostics.getFirst().message());
    }

    @Test
    void getRegisteredLanguages() {
        lspService.registerServer(new StubLspServer("java"));
        lspService.registerServer(new StubLspServer("typescript"));

        Set<String> languages = lspService.getRegisteredLanguages();
        assertEquals(2, languages.size());
        assertTrue(languages.contains("java"));
        assertTrue(languages.contains("typescript"));
    }

    @Test
    void updateDiagnosticsCache() {
        Diagnostic diag = new Diagnostic(
                "App.java", 5, 0, 5, 20,
                Diagnostic.Severity.WARNING, "Unused variable", "javac", "W001");

        lspService.updateDiagnostics("/path/App.java", List.of(diag));

        // Direct registry query won't find it via getDiagnostics(Path) since
        // the path key differs, but the registry is updated
        lspService.clearDiagnostics("/path/App.java");
    }

    @Test
    void clearAllDiagnostics() {
        lspService.updateDiagnostics("file1.java", List.of());
        lspService.updateDiagnostics("file2.java", List.of());

        lspService.clearAllDiagnostics();
        // No exception means success
    }

    @Test
    void shutdownAll() {
        StubLspServer server = new StubLspServer("java");
        lspService.registerServer(server);

        lspService.shutdownAll();
        assertFalse(server.isRunning());
        assertTrue(lspService.getRegisteredLanguages().isEmpty());
    }

    @Test
    void unregisterServer() {
        StubLspServer server = new StubLspServer("java");
        lspService.registerServer(server);

        lspService.unregisterServer("java");
        assertFalse(server.isRunning());
        assertFalse(lspService.getRegisteredLanguages().contains("java"));
    }

    @Test
    void diagnosticFormat() {
        Diagnostic diag = new Diagnostic(
                "Test.java", 4, 2, 4, 10,
                Diagnostic.Severity.ERROR, "Missing semicolon", "javac", "E001");

        String formatted = diag.format();
        assertTrue(Strings.CS.contains(formatted, "Test.java:5:3"));
        assertTrue(Strings.CS.contains(formatted, "error"));
        assertTrue(Strings.CS.contains(formatted, "Missing semicolon"));
    }

    @Test
    void diagnosticSeverityFromValue() {
        assertEquals(Diagnostic.Severity.ERROR, Diagnostic.Severity.fromValue(1));
        assertEquals(Diagnostic.Severity.WARNING, Diagnostic.Severity.fromValue(2));
        assertEquals(Diagnostic.Severity.INFORMATION, Diagnostic.Severity.fromValue(3));
        assertEquals(Diagnostic.Severity.HINT, Diagnostic.Severity.fromValue(4));
        assertEquals(Diagnostic.Severity.INFORMATION, Diagnostic.Severity.fromValue(99));
    }

    @Test
    void navigation_waitsForServerInitialization() {
        var service = new LspService(Map.of(".java", "java"));
        var server = new SlowStartingServer("java");
        service.registerServer(server);

        var result = service.goToDefinition(Path.of("Test.java"), 1, 1);
        assertTrue(server.becameReady(), "server should have become ready during the wait");
        assertEquals(0, server.prematureRunningProbes(),
            "navigation should await the readiness signal instead of polling isRunning()");
        assertTrue(result.stream().anyMatch(s -> Strings.CS.contains(s, "def:Test.java")));
    }

    @Test
    void navigation_failedReadinessReturnsPromptly() {
        var service = new LspService(Map.of(".java", "java"));
        service.registerServer(new FailedStartingServer("java"));

        assertTimeout(Duration.ofSeconds(1), () -> {
            var result = service.goToDefinition(Path.of("Test.java"), 1, 1);
            assertTrue(result.stream().anyMatch(s -> Strings.CS.contains(s, "No LSP server available")));
        });
    }

    @Test
    void navigation_noServerForUnmappedExtension() {
        var service = new LspService(Map.of(".java", "java"));
        service.registerServer(new StubLspServer("java"));

        var result = service.goToDefinition(Path.of("main.py"), 1, 1);
        assertTrue(result.stream().anyMatch(s -> Strings.CS.contains(s, "No LSP server available")));
    }

    /**
     * Stub LSP server for testing.
     */
    static class StubLspServer implements LspServerInstance {
        private final String languageId;
        protected boolean running = true;
        private final List<Diagnostic> diagnostics = new ArrayList<>();

        StubLspServer(String languageId) {
            this.languageId = languageId;
        }

        void addDiagnostic(Diagnostic d) {
            diagnostics.add(d);
        }

        @Override
        public String languageId() { return languageId; }

        @Override
        public void initialize(Path workspaceRoot) { running = true; }

        @Override
        public void shutdown() { running = false; }

        @Override
        public boolean isRunning() { return running; }

        @Override
        public List<Diagnostic> getDiagnostics(Path filePath) { return diagnostics; }

        @Override
        public void didOpen(Path filePath, String content) {}

        @Override
        public void didChange(Path filePath, String content) {}

        @Override
        public void didClose(Path filePath) {}

        @Override public void didSave(Path filePath) {}
    }

    /** Server that starts not-running and flips to ready shortly after construction. */
    static class SlowStartingServer implements LspServerInstance {
        private final String languageId;
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicInteger prematureRunningProbes = new AtomicInteger();
        private final CompletableFuture<Void> readiness = new CompletableFuture<>();

        SlowStartingServer(String languageId) {
            this.languageId = languageId;
            Thread.ofVirtual().start(() -> {
                try { Thread.sleep(200); } catch (InterruptedException _) {}
                ready.set(true);
                readiness.complete(null);
            });
        }

        boolean becameReady() { return ready.get(); }
        int prematureRunningProbes() { return prematureRunningProbes.get(); }

        @Override public String languageId() { return languageId; }
        @Override public void initialize(Path workspaceRoot) {
            ready.set(true);
            readiness.complete(null);
        }
        @Override public CompletionStage<Void> readiness() { return readiness; }
        @Override public void shutdown() { ready.set(false); }
        @Override public boolean isRunning() {
            if (!readiness.isDone()) prematureRunningProbes.incrementAndGet();
            return ready.get();
        }
        @Override public List<Diagnostic> getDiagnostics(Path filePath) { return List.of(); }

        @Override public List<String> goToDefinition(Path filePath, int line, int character) {
            return List.of("def:" + filePath.getFileName() + ":" + line + ":" + character);
        }
        @Override public void didOpen(Path filePath, String content) {}
        @Override public void didChange(Path filePath, String content) {}
        @Override public void didClose(Path filePath) {}
        @Override public void didSave(Path filePath) {}
    }

    static class FailedStartingServer extends StubLspServer {
        private final CompletableFuture<Void> readiness =
            CompletableFuture.failedFuture(new IllegalStateException("startup failed"));

        FailedStartingServer(String languageId) {
            super(languageId);
            running = false;
        }

        @Override public CompletionStage<Void> readiness() { return readiness; }
    }
}
