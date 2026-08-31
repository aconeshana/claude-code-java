package com.claudecode.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Interface for LSP server communication.
 */
public interface LspServerInstance {


    String languageId();

    /** Initialize the LSP server for the given workspace. */
    void initialize(Path workspaceRoot);


    default void ensureStarted() {}

    /**
     * Completion signal for the current initialization attempt. Implementations
     * with asynchronous startup override this with a future completed on handshake
     * success or failure. Already-running simple/test implementations use the
     * immediate default.
     */
    default CompletionStage<Void> readiness() {
        return isRunning()
            ? CompletableFuture.completedFuture(null)
            : CompletableFuture.failedFuture(
                new IllegalStateException("LSP server is not running: " + languageId()));
    }

    /** Shut down the LSP server. */
    void shutdown();

    /** Check if the server is running. */
    boolean isRunning();

    /**
     * Whether this server is still usable — i.e.
     */
    default boolean isHealthy() { return true; }

    /** Request diagnostics for a file. */
    List<Diagnostic> getDiagnostics(Path filePath);

    // ── Navigation operations ───────────────────────────────────────────────

    /** Find where a symbol is defined. Returns list of location strings. */
    default List<String> goToDefinition(Path filePath, int line, int character) {
        return List.of("Operation not supported by this LSP server: goToDefinition");
    }

    /** Find all references to a symbol. */
    default List<String> findReferences(Path filePath, int line, int character) {
        return List.of("Operation not supported by this LSP server: findReferences");
    }

    /** Get hover information (documentation, type info) for a symbol. */
    default String hover(Path filePath, int line, int character) {
        return "Operation not supported by this LSP server: hover";
    }

    /** Get all symbols in a document. */
    default List<String> documentSymbol(Path filePath, int line, int character) {
        return List.of("Operation not supported by this LSP server: documentSymbol");
    }

    /** Search for symbols across the entire workspace. */
    default List<String> workspaceSymbol(Path filePath, int line, int character) {
        return List.of("Operation not supported by this LSP server: workspaceSymbol");
    }

    /** Find implementations of an interface or abstract method. */
    default List<String> goToImplementation(Path filePath, int line, int character) {
        return List.of("Operation not supported by this LSP server: goToImplementation");
    }

    /** Get call hierarchy item at a position. */
    default List<String> prepareCallHierarchy(Path filePath, int line, int character) {
        return List.of("Operation not supported by this LSP server: prepareCallHierarchy");
    }

    /** Find all callers of the function at the position. */
    default List<String> incomingCalls(Path filePath, int line, int character) {
        return List.of("Operation not supported by this LSP server: incomingCalls");
    }

    /** Find all functions called by the function at the position. */
    default List<String> outgoingCalls(Path filePath, int line, int character) {
        return List.of("Operation not supported by this LSP server: outgoingCalls");
    }

    /** Notify the server that a file was opened. */
    void didOpen(Path filePath, String content);

    /** Notify the server that a file was changed. */
    void didChange(Path filePath, String content);

    /**
     * Notify the server that a file was closed (textDocument/didClose).
     */
    void didClose(Path filePath);

    /**
     * Notify the server that a file was saved (textDocument/didSave).
     */
    void didSave(Path filePath);
}
