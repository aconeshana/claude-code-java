package com.claudecode.tools.lsp;

import org.apache.commons.lang3.Strings;

import com.claudecode.lsp.Diagnostic;
import com.claudecode.lsp.LspServerInstance;
import com.claudecode.lsp.LspService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@link LSPTool#call} operation dispatch against a stub
 * {@link LspServerInstance} registered on a real {@link LspService}.
 */
class LSPToolOperationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LspService lspService;
    private LSPTool tool;
    private Path testFile;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        lspService = new LspService(Map.of(".java", "java"));
        lspService.registerServer(new RecordingLspServer("java"));
        tool = new LSPTool(lspService);
        // GAP-7 validation requires the file to actually exist on disk.
        testFile = tempDir.resolve("Test.java");
        Files.writeString(testFile, "class Test {}");
    }

    private ObjectNode input(String operation, String filePath, int line, int character) {
        ObjectNode node = MAPPER.createObjectNode();
        if (operation != null) node.put("operation", operation);
        if (filePath != null) node.put("filePath", filePath);
        node.put("line", line);
        node.put("character", character);
        return node;
    }

    @Test
    void name_isLSP() {
        assertEquals("LSP", tool.name());
    }

    @Test
    void isReadOnly_true() {
        assertTrue(tool.isReadOnly());
    }

    @Test
    void goToDefinition_dispatchesToServer() {
        String result = tool.call(input("goToDefinition", testFile.toString(), 1, 1), null);
        assertEquals("goToDefinition:Test.java:1:1", result);
    }

    @Test
    void findReferences_dispatchesToServer() {
        String result = tool.call(input("findReferences", testFile.toString(), 2, 3), null);
        assertEquals("findReferences:Test.java:2:3", result);
    }

    @Test
    void hover_dispatchesToServer() {
        String result = tool.call(input("hover", testFile.toString(), 4, 5), null);
        assertEquals("hover:Test.java:4:5", result);
    }

    @Test
    void documentSymbol_dispatchesToServer() {
        String result = tool.call(input("documentSymbol", testFile.toString(), 1, 1), null);
        assertEquals("documentSymbol:Test.java:1:1", result);
    }

    @Test
    void workspaceSymbol_dispatchesToServer() {
        String result = tool.call(input("workspaceSymbol", testFile.toString(), 1, 1), null);
        assertEquals("workspaceSymbol:Test.java:1:1", result);
    }

    @Test
    void goToImplementation_dispatchesToServer() {
        String result = tool.call(input("goToImplementation", testFile.toString(), 1, 1), null);
        assertEquals("goToImplementation:Test.java:1:1", result);
    }

    @Test
    void prepareCallHierarchy_dispatchesToServer() {
        String result = tool.call(input("prepareCallHierarchy", testFile.toString(), 1, 1), null);
        assertEquals("prepareCallHierarchy:Test.java:1:1", result);
    }

    @Test
    void incomingCalls_dispatchesToServer() {
        String result = tool.call(input("incomingCalls", testFile.toString(), 1, 1), null);
        assertEquals("incomingCalls:Test.java:1:1", result);
    }

    @Test
    void outgoingCalls_dispatchesToServer() {
        String result = tool.call(input("outgoingCalls", testFile.toString(), 1, 1), null);
        assertEquals("outgoingCalls:Test.java:1:1", result);
    }

    @Test
    void missingOperation_returnsError() {
        String result = tool.call(input(null, testFile.toString(), 1, 1), null);
        assertTrue(Strings.CS.startsWith(result, "Error: 'operation' is required"));
    }

    @Test
    void missingFilePath_returnsError() {
        String result = tool.call(input("hover", null, 1, 1), null);
        assertEquals("Error: 'filePath' is required", result);
    }

    @Test
    void unknownOperation_returnsError() {
        String result = tool.call(input("frobnicate", testFile.toString(), 1, 1), null);
        assertEquals("Unknown operation: frobnicate", result);
    }

    @Test
    void noServerRegistered_returnsNoServerMessage() {
        LSPTool bare = new LSPTool(new LspService());
        String result = bare.call(input("goToDefinition", testFile.toString(), 1, 1), null);
        assertTrue(Strings.CS.contains(result, "No LSP server available"));
    }

    @Test
    void call_missingFile_returnsError() {
        // GAP-7 regression: validateInput — a non-existent file fails cleanly
        // instead of producing a cryptic downstream error.
        String result = tool.call(input("hover", "DoesNotExist-xyz.java", 1, 1), null);
        assertTrue(Strings.CS.contains(result, "does not exist"), result);
    }

    @Test
    void call_directoryPath_returnsError() {
        String result = tool.call(input("hover", System.getProperty("java.io.tmpdir"), 1, 1), null);
        assertTrue(Strings.CS.contains(result, "not a file"), result);
    }

    /** Stub server that echoes the operation name and position so dispatch can be asserted. */
    private static class RecordingLspServer implements LspServerInstance {
        private final String languageId;

        RecordingLspServer(String languageId) {
            this.languageId = languageId;
        }

        private static String tag(String op, Path filePath, int line, int character) {
            return op + ":" + filePath.getFileName() + ":" + line + ":" + character;
        }

        @Override public String languageId() { return languageId; }
        @Override public void initialize(Path workspaceRoot) {}
        @Override public void shutdown() {}
        @Override public boolean isRunning() { return true; }

        @Override
        public List<Diagnostic> getDiagnostics(Path filePath) {
            return List.of(new Diagnostic(filePath.toString(), 0, 0, 0, 5,
                Diagnostic.Severity.WARNING, "stub diagnostic", "stub", null));
        }

        @Override public List<String> goToDefinition(Path filePath, int line, int character) {
            return List.of(tag("goToDefinition", filePath, line, character));
        }
        @Override public List<String> findReferences(Path filePath, int line, int character) {
            return List.of(tag("findReferences", filePath, line, character));
        }
        @Override public String hover(Path filePath, int line, int character) {
            return tag("hover", filePath, line, character);
        }
        @Override public List<String> documentSymbol(Path filePath, int line, int character) {
            return List.of(tag("documentSymbol", filePath, line, character));
        }
        @Override public List<String> workspaceSymbol(Path filePath, int line, int character) {
            return List.of(tag("workspaceSymbol", filePath, line, character));
        }
        @Override public List<String> goToImplementation(Path filePath, int line, int character) {
            return List.of(tag("goToImplementation", filePath, line, character));
        }
        @Override public List<String> prepareCallHierarchy(Path filePath, int line, int character) {
            return List.of(tag("prepareCallHierarchy", filePath, line, character));
        }
        @Override public List<String> incomingCalls(Path filePath, int line, int character) {
            return List.of(tag("incomingCalls", filePath, line, character));
        }
        @Override public List<String> outgoingCalls(Path filePath, int line, int character) {
            return List.of(tag("outgoingCalls", filePath, line, character));
        }

        @Override public void didOpen(Path filePath, String content) {}
        @Override public void didChange(Path filePath, String content) {}
        @Override public void didClose(Path filePath) {}
        @Override public void didSave(Path filePath) {}
    }
}
