package com.claudecode.tools.lsp;

import com.claudecode.lsp.Diagnostic;
import com.claudecode.lsp.LspServerInstance;
import com.claudecode.lsp.LspService;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;


class LSPToolTest {

    // ── isEnabled (connectivity gate) ─────────────────────────────────────

    @Test
    void isEnabledTrueWhenNoServiceInjected() {
        // No-arg constructor (standalone/test use) has no health signal → stays enabled.
        LSPTool tool = new LSPTool();
        assertTrue(tool.isEnabled());
    }

    @Test
    void isEnabledFalseWhenNoHealthyServer() {
        LspService service = new LspService();
        // Empty registry → no healthy server → tool not offered to the model.
        LSPTool tool = new LSPTool(service);
        assertFalse(tool.isEnabled());
    }

    @Test
    void isEnabledTrueWhenAHealthyServerRegistered() {
        LspService service = new LspService();
        service.registerServer(new StubServer("java", true));
        LSPTool tool = new LSPTool(service);
        assertTrue(tool.isEnabled());
    }

    @Test
    void isEnabledFalseWhenAllServersExhaustedRestarts() {
        LspService service = new LspService();
        service.registerServer(new StubServer("java", false));
        LSPTool tool = new LSPTool(service);
        assertFalse(tool.isEnabled());
    }

    /** Minimal {@link LspServerInstance} stub whose health is injected. */
    private static final class StubServer implements LspServerInstance {
        private final String languageId;
        private final boolean healthy;

        StubServer(String languageId, boolean healthy) {
            this.languageId = languageId;
            this.healthy = healthy;
        }

        @Override public String languageId() { return languageId; }
        @Override public void initialize(Path workspaceRoot) {}
        @Override public void shutdown() {}
        @Override public boolean isRunning() { return healthy; }
        @Override public List<Diagnostic> getDiagnostics(Path filePath) { return List.of(); }
        @Override public void didOpen(Path filePath, String content) {}
        @Override public void didChange(Path filePath, String content) {}
        @Override public void didClose(Path filePath) {}
        @Override public void didSave(Path filePath) {}
        @Override public boolean isHealthy() { return healthy; }
    }
}
