package com.claudecode.lsp;

import com.claudecode.core.io.PathUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages LSP server instances and maintains a diagnostic registry.
 */
public class LspService {

    private static final Logger LOG = LoggerFactory.getLogger(LspService.class);

    private final Map<String, LspServerInstance> servers;
    private final Map<String, List<Diagnostic>> diagnosticRegistry;
    private final Map<String, String> extensionToLanguageId;

    public LspService() {
        this(Map.of());
    }


    public LspService(Map<String, String> extensionToLanguageId) {
        this.servers = new ConcurrentHashMap<>();
        this.diagnosticRegistry = new ConcurrentHashMap<>();
        this.extensionToLanguageId = extensionToLanguageId;
    }

    /**
     * Register an LSP server instance for a language.
     *
     * @param server the server instance
     */
    public void registerServer(LspServerInstance server) {
        servers.put(server.languageId(), server);
        LOG.info("Registered LSP server for language: {}", server.languageId());
    }

    /**
     * Register a single server instance under an explicit language id.
     */
    public void registerServer(LspServerInstance server, String languageId) {
        servers.put(languageId, server);
        LOG.info("Registered LSP server (native language: {}) under language id: {}",
                server.languageId(), languageId);
    }

    /**
     * Remove an LSP server instance.
     *
     * @param languageId the language ID
     */
    public void unregisterServer(String languageId) {
        LspServerInstance server = servers.remove(languageId);
        if (server != null && server.isRunning()) {
            server.shutdown();
        }
    }

    /** Get the server registered for a language, if any. */
    public Optional<LspServerInstance> getServer(String languageId) {
        return Optional.ofNullable(servers.get(languageId));
    }

    /**
     * Whether at least one registered server is still usable (not in a terminal failure state).
     */
    public boolean hasHealthyServer() {
        if (servers.isEmpty()) {
            return false;
        }
        return servers.values().stream().anyMatch(LspServerInstance::isHealthy);
    }

    /**
     * Get diagnostics for a file from all applicable servers.
     *
     * @param filePath the file path
     * @return list of diagnostics from all servers
     */
    public List<Diagnostic> getDiagnostics(Path filePath) {
        // Check registry first
        String key = filePath.toAbsolutePath().toString();
        List<Diagnostic> cached = diagnosticRegistry.get(key);
        if (cached != null) {
            return cached;
        }

        // Query all servers
        List<Diagnostic> allDiagnostics = new ArrayList<>();
        for (LspServerInstance server : servers.values()) {
            if (server.isRunning()) {
                try {
                    allDiagnostics.addAll(server.getDiagnostics(filePath));
                } catch (Exception e) {
                    LOG.warn("Failed to get diagnostics from {} server: {}",
                            server.languageId(), e.getMessage());
                }
            }
        }

        // Cache results
        diagnosticRegistry.put(key, allDiagnostics);
        return allDiagnostics;
    }

    /**
     * Update diagnostics in the registry (called by server push notifications).
     */
    public void updateDiagnostics(String filePath, List<Diagnostic> diagnostics) {
        diagnosticRegistry.put(filePath, List.copyOf(diagnostics));
    }

    /** Clear cached diagnostics for a file. */
    public void clearDiagnostics(String filePath) {
        diagnosticRegistry.remove(filePath);
    }

// ── 9 navigation operations — route by file extension ──────────────────.

/**
     * How long a navigation call waits for a server that is still initializing before giving up with a
     * "no server" message.
     */
    private static final Duration INIT_WAIT = Duration.ofSeconds(5);

    /**
     * Resolve the LSP server that handles {@code filePath}, based on its extension.
     * Returns {@link Optional#empty} when no registered server covers that type.
     */
    public Optional<LspServerInstance> getServerForFile(Path filePath) {
        String ext = PathUtils.extensionOf(filePath);
        String languageId = extensionToLanguageId.get(ext);
        if (languageId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(servers.get(languageId));
    }

    /**
     * Awaits the server's initialization completion signal for up to {@link #INIT_WAIT}.
     */
    private static boolean awaitReady(LspServerInstance server) {
// Give a crashed/slow server a chance to (re)start lazily before we decide it's
// unavailable.
        server.ensureStarted();
        try {
            server.readiness().toCompletableFuture()
                .get(INIT_WAIT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException _) {
            // Initialization failure/timeout is represented to callers as an
            // unavailable server, matching the existing navigation contract.
        }
        return server.isRunning();
    }

    private static String noServer(Path filePath) {
        return "No LSP server available for " + filePath.getFileName();
    }

    private static List<String> noServerList(Path filePath) {
        return List.of(noServer(filePath));
    }

    public List<String> goToDefinition(Path filePath, int line, int character) {
        return getServerForFile(filePath).filter(LspService::awaitReady)
            .map(s -> s.goToDefinition(filePath, line, character))
            .orElseGet(() -> noServerList(filePath));
    }

    public List<String> findReferences(Path filePath, int line, int character) {
        return getServerForFile(filePath).filter(LspService::awaitReady)
            .map(s -> s.findReferences(filePath, line, character))
            .orElseGet(() -> noServerList(filePath));
    }

    public String hover(Path filePath, int line, int character) {
        return getServerForFile(filePath).filter(LspService::awaitReady)
            .map(s -> s.hover(filePath, line, character)).orElseGet(() -> noServer(filePath));
    }

    public List<String> documentSymbol(Path filePath, int line, int character) {
        return getServerForFile(filePath).filter(LspService::awaitReady)
            .map(s -> s.documentSymbol(filePath, line, character))
            .orElseGet(() -> noServerList(filePath));
    }

    public List<String> workspaceSymbol(Path filePath, int line, int character) {
        return getServerForFile(filePath).filter(LspService::awaitReady)
            .map(s -> s.workspaceSymbol(filePath, line, character))
            .orElseGet(() -> noServerList(filePath));
    }

    public List<String> goToImplementation(Path filePath, int line, int character) {
        return getServerForFile(filePath).filter(LspService::awaitReady)
            .map(s -> s.goToImplementation(filePath, line, character))
            .orElseGet(() -> noServerList(filePath));
    }

    public List<String> prepareCallHierarchy(Path filePath, int line, int character) {
        return getServerForFile(filePath).filter(LspService::awaitReady)
            .map(s -> s.prepareCallHierarchy(filePath, line, character))
            .orElseGet(() -> noServerList(filePath));
    }

    public List<String> incomingCalls(Path filePath, int line, int character) {
        return getServerForFile(filePath).filter(LspService::awaitReady)
            .map(s -> s.incomingCalls(filePath, line, character))
            .orElseGet(() -> noServerList(filePath));
    }

    public List<String> outgoingCalls(Path filePath, int line, int character) {
        return getServerForFile(filePath).filter(LspService::awaitReady)
            .map(s -> s.outgoingCalls(filePath, line, character))
            .orElseGet(() -> noServerList(filePath));
    }

    /**
     * Clear all cached diagnostics.
     */
    public void clearAllDiagnostics() {
        diagnosticRegistry.clear();
    }

    /**
     * Get all registered server language IDs.
     */
    public Set<String> getRegisteredLanguages() {
        return Set.copyOf(servers.keySet());
    }

    /**
     * Shut down all servers.
     */
    public void shutdownAll() {
        for (LspServerInstance server : servers.values()) {
            try {
                if (server.isRunning()) {
                    server.shutdown();
                }
            } catch (Exception e) {
                LOG.warn("Error shutting down {} server: {}",
                        server.languageId(), e.getMessage());
            }
        }
        servers.clear();
        diagnosticRegistry.clear();
    }
}
