package com.claudecode.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LspBootstrapTest {

    @TempDir Path workspace;

    /** A disabled "java" server config. Java is config-driven in this port
     * (no built-in jdt.ls probe), so this simply exercises the disabled-entry
     * path without depending on whether jdt.ls is installed on the machine. */
    private static final LspServerSettings JAVA_DISABLED =
        new LspServerSettings(null, List.of(), Map.of(), Map.of(), false);

    @Test
    void wire_badCommand_doesNotThrowAndSkipsThatServer() {
        Map<String, LspServerSettings> configured = Map.of(
            "java", JAVA_DISABLED,
            "bogus", new LspServerSettings("/no/such/binary-xyz", List.of(), Map.of(), Map.of(), true)
        );

        LspBootstrap bootstrap = assertDoesNotThrow(() -> LspBootstrap.wire(workspace, configured));
        assertTrue(bootstrap.lspService().getRegisteredLanguages().isEmpty());
    }

    @Test
    void wire_disabledEntry_isNeverAttempted() {
        Map<String, LspServerSettings> configured = Map.of(
            "java", JAVA_DISABLED,
            "typescript", new LspServerSettings("should-not-run", List.of(), Map.of(), Map.of(), false)
        );

        LspBootstrap bootstrap = LspBootstrap.wire(workspace, configured);
        assertFalse(bootstrap.lspService().getRegisteredLanguages().contains("typescript"));
    }

    @Test
    void wire_exposesAssembledComponents() {
        LspBootstrap bootstrap = LspBootstrap.wire(workspace, Map.of("java", JAVA_DISABLED));
        assertNotNull(bootstrap.registry());
        assertNotNull(bootstrap.passiveFeedback());
        assertNotNull(bootstrap.lspService());
    }

    @Test
    void wire_emptyConfig_neverThrows() {
        // No servers configured — nothing spawns, but wiring must not throw.
        assertDoesNotThrow(() -> LspBootstrap.wire(workspace, Map.of()));
    }

    @Test
    void fileChangeListener_firstCallSendsDidOpen_subsequentSendDidChange() throws IOException {
        Map<String, LspServerSettings> configured = Map.of(
            "java", JAVA_DISABLED,
            "typescript", new LspServerSettings(null, List.of(),
                Map.of(".ts", "typescript"), Map.of(), false) // no command — never actually spawned
        );
        LspBootstrap bootstrap = LspBootstrap.wire(workspace, configured);

        RecordingServer stub = new RecordingServer("typescript");
        bootstrap.lspService().registerServer(stub);

        Path file = Files.createFile(workspace.resolve("Foo.ts"));
        Files.writeString(file, "const x = 1;");

        bootstrap.fileChangeListener().onFileChanged(file, "Edit");
        bootstrap.fileChangeListener().onFileChanged(file, "Edit");

        assertEquals(1, stub.didOpenCount.get());
        assertEquals(1, stub.didChangeCount.get());
    }

    @Test
    void fileChangeListener_unmappedExtension_isIgnored() throws IOException {
        LspBootstrap bootstrap = LspBootstrap.wire(workspace, Map.of("java", JAVA_DISABLED));

        RecordingServer stub = new RecordingServer("python");
        bootstrap.lspService().registerServer(stub);

        Path file = Files.createFile(workspace.resolve("Foo.py"));
        assertDoesNotThrow(() -> bootstrap.fileChangeListener().onFileChanged(file, "Write"));
        assertEquals(0, stub.didOpenCount.get());
    }

    @Test
    void fileChangeListener_editClearsDeliveredDiagnostics() throws IOException {

        LspBootstrap bootstrap = LspBootstrap.wire(workspace, Map.of("java", JAVA_DISABLED));
        LspDiagnosticRegistry registry = bootstrap.registry();

        AtomicInteger notifications = new AtomicInteger();
// match PassiveFeedback: empty diagnostic lists (e.g. the one fired by
        // clearDiagnostics) are not counted as a real delivery.
        registry.addListener((_, diags) -> {
            if (!diags.isEmpty()) notifications.incrementAndGet();
        });

        Path file = Files.createFile(workspace.resolve("Foo.txt")); // unmapped ext -> clears then returns
        String uri = file.toUri().toString();
        Diagnostic diag = new Diagnostic(file.toString(), 1, 1, 1, 2,
            Diagnostic.Severity.ERROR, "boom", "x", null);

        registry.registerDiagnostics(uri, List.of(diag));
        registry.registerDiagnostics(uri, List.of(diag)); // dedup suppresses
        assertEquals(1, notifications.get());

        bootstrap.fileChangeListener().onFileChanged(file, "Edit"); // triggers clearDiagnostics

        registry.registerDiagnostics(uri, List.of(diag)); // re-delivers after clear
        assertEquals(2, notifications.get());
    }

    @Test
    void rewire_tearsDownOldServersAndRebuildsRouting() throws IOException {
        LspBootstrap bootstrap = LspBootstrap.wire(workspace, Map.of("java", JAVA_DISABLED));
        RecordingServer stale = new RecordingServer("typescript");
        bootstrap.lspService().registerServer(stale);

        Map<String, LspServerSettings> reloaded = Map.of(
            "java", JAVA_DISABLED,
            "python", new LspServerSettings(null, List.of(), Map.of(".py", "python"), Map.of(), false)
        );
        bootstrap.rewire(workspace, reloaded);

        assertTrue(stale.shutdownCount.get() >= 1, "stale server must be shut down on rewire");

        // The rebuilt routing should map .py -> python. Register a python server
        // and confirm the file listener routes to it (no server when unmapped).
        RecordingServer py = new RecordingServer("python");
        bootstrap.lspService().registerServer(py);
        Path pyFile = Files.createFile(workspace.resolve("foo.py"));
        Files.writeString(pyFile, "x = 1");
        bootstrap.fileChangeListener().onFileChanged(pyFile, "Edit");
        assertEquals(1, py.didOpenCount.get(), "rewired routing should map .py -> python server");
    }

    /** Records didOpen/didChange/shutdown calls without doing any real LSP work. */
    private static class RecordingServer implements LspServerInstance {
        private final String languageId;
        final AtomicInteger didOpenCount = new AtomicInteger();
        final AtomicInteger didChangeCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();
        final AtomicReference<String> lastContent = new AtomicReference<>();

        RecordingServer(String languageId) { this.languageId = languageId; }

        @Override public String languageId() { return languageId; }
        @Override public void initialize(Path workspaceRoot) {}
        @Override public void shutdown() { shutdownCount.incrementAndGet(); }
        @Override public boolean isRunning() { return true; }
        @Override public List<Diagnostic> getDiagnostics(Path filePath) { return List.of(); }
        @Override public void didOpen(Path filePath, String content) { didOpenCount.incrementAndGet(); lastContent.set(content); }
        @Override public void didChange(Path filePath, String content) { didChangeCount.incrementAndGet(); lastContent.set(content); }
        @Override public void didClose(Path filePath) {}
        @Override public void didSave(Path filePath) {}
    }
}
