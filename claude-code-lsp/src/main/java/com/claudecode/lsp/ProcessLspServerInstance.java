package com.claudecode.lsp;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.process.SubprocessEnvironment;


import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Generic {@link LspServerInstance} that spawns any stdio-based language server subprocess and
 * speaks LSP over JSON-RPC via LSP4J.
 */
public class ProcessLspServerInstance implements LspServerInstance {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessLspServerInstance.class);
    private static final int TIMEOUT_SECONDS = 30;

    private final String languageId;
    private final List<String> command;
    private final Map<String, String> env;
    private final Map<String, String> extensionToLanguage;
    private final JsonNode initializationOptions;
    private final JsonNode settingsNode;
    private final String workspaceFolder;
    private final String transport;
    private final Long timeoutMs;
    private final boolean restart;
    private final LspDiagnosticRegistry registry;

    private Process process;
    private LanguageClient languageClient;
    private LanguageServer languageServer;
    private volatile boolean running = false;
    private volatile boolean initialized = false;
    private volatile Path workspaceRoot;
    private volatile boolean shuttingDown = false;
    private int crashCount = 0;
    private final AtomicReference<CompletableFuture<Void>> readiness =
        new AtomicReference<>(new CompletableFuture<>());


    private static final int MAX_RESTARTS = 3;


    private static final int MAX_TRANSIENT_RETRIES = 3;

    /** Base delay for exponential backoff on transient LSP errors. */
    private static final long RETRY_BASE_DELAY_MS = 500;

    // Diagnostic cache keyed by file URI
    private final Map<String, List<Diagnostic>> diagnosticsCache = new ConcurrentHashMap<>();

    // Files we have already sent didOpen for — guards against duplicate opens.
    private final Set<String> openedUris = ConcurrentHashMap.newKeySet();


    private static final long MAX_LSP_FILE_SIZE_BYTES = 10_000_000L;

    private ExecutorService executor;

    /**
     * @param languageId LSP language identifier (e.g.
     */
    public ProcessLspServerInstance(String languageId, List<String> command, Map<String, String> env,
                                     LspDiagnosticRegistry registry) {
        this(languageId, toSettings(command, env), registry);
    }

    private static LspServerSettings toSettings(List<String> command, Map<String, String> env) {
        List<String> cmd = command == null ? List.of() : command;
        String executable = cmd.isEmpty() ? null : cmd.getFirst();
        List<String> args = cmd.size() > 1 ? List.copyOf(cmd.subList(1, cmd.size())) : List.of();
        return new LspServerSettings(executable, args, Map.of(), env == null ? Map.of() : env, true);
    }

    /**
     * Full constructor: derives the launch command ({@code command} + {@code args}) and all
     * runtime options from a parsed {@link LspServerSettings}. Used for
     * plugin-provided LSP servers.
     */
    public ProcessLspServerInstance(String languageId, LspServerSettings settings,
                                     LspDiagnosticRegistry registry) {
        this.languageId = languageId;
        List<String> cmd = new ArrayList<>();
        if (StringUtils.isNotBlank(settings.command())) {
            cmd.add(settings.command());
        }
        cmd.addAll(settings.args());
        this.command = List.copyOf(cmd);
        this.env = settings.env() == null ? Map.of() : Map.copyOf(settings.env());
        this.extensionToLanguage = settings.extensionToLanguage() == null
            ? Map.of() : Map.copyOf(settings.extensionToLanguage());
        this.initializationOptions = settings.initializationOptions();
        this.settingsNode = settings.settings();
        this.workspaceFolder = settings.workspaceFolder();
        this.transport = StringUtils.isBlank(settings.transport())
            ? "stdio" : settings.transport();
        this.timeoutMs = settings.timeoutMs();
        this.restart = settings.restart();
        this.registry = registry;
    }

    @Override
    public String languageId() {
        return languageId;
    }

    @Override
    public CompletionStage<Void> readiness() {
        return readiness.get().minimalCompletionStage();
    }

    @Override
    @SuppressWarnings("deprecation")
    public synchronized void initialize(Path workspaceRoot) {
        CompletableFuture<Void> readinessSignal = prepareReadinessSignal();
        if (running) {
            LOG.warn("LSP server '{}' already initialized", languageId);
            if (initialized) readinessSignal.complete(null);
            return;
        }
        // A shutdown in progress (or already done) must suppress any restart that
        // was queued by the crash monitor — otherwise a restart can spawn a fresh
        // process that shutdown then destroys, or race shutdown's own teardown.
        if (shuttingDown) {
            LOG.debug("LSP server '{}' not initializing: shutdown in progress", languageId);
            readinessSignal.completeExceptionally(
                new CancellationException("LSP server is shutting down: " + languageId));
            return;
        }
        this.workspaceRoot = workspaceRoot;

// Only the stdio transport is spoken by this port; skip anything else before spawning a
// subprocess.
        if (!Strings.CI.equals("stdio", transport)) {
            LOG.warn("LSP server '{}' uses unsupported transport '{}' (only stdio is "
                + "supported); skipping", languageId, transport);
            running = false;
            readinessSignal.completeExceptionally(
                new IllegalStateException("Unsupported LSP transport: " + transport));
            return;
        }

        try {
            LOG.info("Starting LSP server '{}': {}", languageId, command);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workspaceRoot.toFile());
            SubprocessEnvironment.applyTo(pb.environment());
            if (!env.isEmpty()) {
                pb.environment().putAll(env);
            }
            process = pb.start();


            // (restart on the next request via ensureServerStarted, after setting
            // state='error'); this port restarts eagerly bounded by MAX_RESTARTS,

            // maxRestarts (default 3). A clean shutdown sets shuttingDown first,
            // so this never fires for an intentional stop.
            Thread.ofVirtual().name("lsp-exit-watch-" + languageId).start(() -> {
                try {
                    int code = process.waitFor();
                    if (!shuttingDown && code != 0) {
                        onCrash();
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });

            // Create our client implementation
            languageClient = new LspLanguageClient();

            // Create the launcher
            Launcher<LanguageServer> serverLauncher = Launcher.createLauncher(
                languageClient,
                LanguageServer.class,
                process.getInputStream(),
                process.getOutputStream()
            );

            languageServer = serverLauncher.getRemoteProxy();

            running = true;

            // Start stderr reader thread
            Thread.ofVirtual().start(this::readStderr);

            // Start listening for server messages
            executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> {
                try {
                    serverLauncher.startListening().get();
                } catch (Exception e) {
                    LOG.error("LSP listener error", e);
                }
                return null;
            });

            // Initialize the server
            InitializeParams params = new InitializeParams();
            // rootUri is @Deprecated in lsp4j (LSP 3.16) but kept for compatibility:

//  which deliberately retains the
            // deprecated rootUri/rootPath so some servers still resolve URIs.
            params.setRootUri(workspaceRoot.toUri().toString());
            // rootPath is @Deprecated in lsp4j (LSP 3.8) but kept for compatibility:


            params.setRootPath(workspaceRoot.toAbsolutePath().toString());
            params.setProcessId((int) ProcessHandle.current().pid());
            params.setCapabilities(createClientCapabilities());
            if (initializationOptions != null) {
                params.setInitializationOptions(initializationOptions);
            }
            if (settingsNode != null) {
                // LSP4J 0.24.0's InitializeParams has no `settings` field; servers
                // that need settings should request them via workspace/configuration
                // (not wired in this port). Log so the declared config isn't silent.
                LOG.debug("LSP server '{}' declares settings; LSP4J 0.24.0 does not "
                    + "forward them through initialize (ignored)", languageId);
            }
            if (StringUtils.isNotBlank(workspaceFolder)) {
                Path folder = Path.of(workspaceFolder);
                String folderUri = folder.toUri().toString();
                String folderName = folder.getFileName() != null
                    ? folder.getFileName().toString() : folderUri;
                params.setWorkspaceFolders(List.of(new WorkspaceFolder(folderUri, folderName)));
            }

            long initTimeout = timeoutMs != null ? timeoutMs : TIMEOUT_SECONDS;
            InitializeResult result = initializeServer(params, initTimeout);
// The LSP handshake requires the client to send the `initialized` notification after a
// successful initialize response; servers such as rust-analyzer reject requests that
// arrive before it.
            languageServer.initialized(new InitializedParams());
            initialized = true;
            readinessSignal.complete(null);

            LOG.info("LSP server '{}' initialized, server info: {}", languageId,
                result.getServerInfo() != null ? result.getServerInfo().getName() : "unknown");
            LOG.debug("Server capabilities: {} (restart configured: {})",
                result.getCapabilities(), restart);
            crashCount = 0; // a successful handshake resets the crash budget

        } catch (Exception e) {
            LOG.error("Failed to initialize LSP server '{}'", languageId, e);
            if (e instanceof TimeoutException
                    && process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            running = false;
            initialized = false;
            readinessSignal.completeExceptionally(e);
            CompletableFuture<Void> currentSignal = readiness.get();
            if (currentSignal != readinessSignal) {
                currentSignal.completeExceptionally(e);
            }
            throw new RuntimeException("Failed to start LSP server '" + languageId + "'", e);
        }
    }

    /** Starts a fresh readiness generation after a completed failed/stopped attempt. */
    private CompletableFuture<Void> prepareReadinessSignal() {
        while (true) {
            CompletableFuture<Void> current = readiness.get();
            if (!current.isDone()) {
                return current;
            }
            CompletableFuture<Void> next = new CompletableFuture<>();
            if (readiness.compareAndSet(current, next)) {
                return next;
            }
        }
    }


    private InitializeResult initializeServer(InitializeParams params, long initTimeout) throws Exception {
        return languageServer.initialize(params).get(initTimeout, TimeUnit.SECONDS);
    }


    private <T> T sendRequest(Supplier<CompletableFuture<T>> request, String method) throws Exception {
        for (int retries = 0; ; retries++) {
            try {
                return request.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                if (!isContentModified(e) || retries >= MAX_TRANSIENT_RETRIES) {
                    throw e;
                }

                long delayMillis = RETRY_BASE_DELAY_MS * (1L << retries);
                LOG.debug("LSP request '{}' to '{}' got ContentModified error, retrying in {}ms "
                    + "(attempt {}/{})", method, languageId, delayMillis, retries + 1,
                    MAX_TRANSIENT_RETRIES);
                try {
                    // Investigation (2026-07-29): this is the bounded exponential

                    // await sleep(delay), not polling for a state change. Replacing it
                    // with a scheduler would not make this synchronous request API
                    // complete sooner and would add an unnecessary async hand-off.
                    //noinspection BusyWait
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
    }

    private static boolean isContentModified(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return cause instanceof ResponseErrorException ree
            && ree.getResponseError() != null
            && ree.getResponseError().getCode() == ResponseErrorCode.ContentModified.getValue();
    }

    /**
     * Records an unexpected process exit: marks the server un-healthy and bumps the crash counter, but
     * does NOT restart here.
     */
    private void onCrash() {
        running = false;
        initialized = false;
        crashCount++;
        CompletableFuture<Void> crashedSignal = readiness.getAndSet(new CompletableFuture<>());
        crashedSignal.completeExceptionally(
            new IllegalStateException("LSP server crashed: " + languageId));
        if (crashCount <= MAX_RESTARTS && workspaceRoot != null && !shuttingDown) {
            LOG.warn("LSP server '{}' crashed (recovery {}/{}); will restart lazily on next request",
                languageId, crashCount, MAX_RESTARTS);
        } else {
            readiness.get().completeExceptionally(
                new IllegalStateException("LSP restart budget exhausted: " + languageId));
            LOG.error("LSP server '{}' exceeded max restarts ({}); giving up", languageId, MAX_RESTARTS);
        }
    }


    @Override
    public void ensureStarted() {
        if (running || shuttingDown || workspaceRoot == null || crashCount == 0) {
            return;
        }
        if (crashCount > MAX_RESTARTS) {
            readiness.get().completeExceptionally(
                new IllegalStateException("LSP restart budget exhausted: " + languageId));
            return;
        }
        try {
            initialize(workspaceRoot);
        } catch (Exception e) {
            LOG.warn("LSP server '{}' lazy restart failed", languageId, e);
        }
    }

    private ClientCapabilities createClientCapabilities() {

//  Declaring an unimplemented
        // capability is worse than omitting it: LSP4J's default LanguageClient
        // handlers throw UnsupportedOperationException, so a server acting on a
        // false declaration (workspace/applyEdit, window/workDoneProgress/create,
        // willSave[WaitUntil] notifications) would break the session.
        ClientCapabilities caps = new ClientCapabilities();

        // Workspace: applyEdit and configuration stay unset (falsy on the wire),

        // workspace/configuration request is answered unconditionally via the
        // @JsonRequest handler below, not gated on the capability bit.
        WorkspaceClientCapabilities wsCaps = new WorkspaceClientCapabilities();
        caps.setWorkspace(wsCaps);


        TextDocumentClientCapabilities tdCaps = new TextDocumentClientCapabilities();
        SynchronizationCapabilities syncCaps = new SynchronizationCapabilities();
        syncCaps.setWillSave(false);
        syncCaps.setWillSaveWaitUntil(false);
        syncCaps.setDidSave(true);
        tdCaps.setSynchronization(syncCaps);
        caps.setTextDocument(tdCaps);


        // declare it either).
        return caps;
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.debug("[LSP SERVER {}] {}", languageId, line);
            }
        } catch (IOException _) {
            LOG.debug("{} stderr closed", languageId);
        }
    }

    @Override
    public synchronized void shutdown() {
        shuttingDown = true; // suppress the crash monitor so a clean stop never triggers a restart
        if (!running) {
            readiness.get().completeExceptionally(
                new CancellationException("LSP server shut down: " + languageId));
            return;
        }

        try {
            if (languageServer != null && initialized) {
                languageServer.shutdown().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
// Send the mandatory `exit` notification so the server terminates gracefully.
                try {
                    languageServer.exit();
                } catch (Exception e) {
                    LOG.debug("LSP server '{}' exit notification failed (continuing)", languageId, e);
                }
            }
        } catch (Exception e) {
            LOG.warn("Error during LSP server '{}' shutdown", languageId, e);
        }

        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }

        if (executor != null) {
            executor.shutdownNow();
        }

        running = false;
        initialized = false;
        readiness.get().completeExceptionally(
            new CancellationException("LSP server shut down: " + languageId));
        languageServer = null;
        languageClient = null;
        diagnosticsCache.clear();
        LOG.info("LSP server '{}' shutdown complete", languageId);
    }

    @Override
    public boolean isRunning() {
        return running && initialized && process != null && process.isAlive();
    }

    /**
     * Usable only if we haven't given up on restarting it (crashCount past {@link #MAX_RESTARTS} means
     * a terminal failure) and it isn't mid-shutdown.
     */
    @Override
    public boolean isHealthy() {
        return crashCount <= MAX_RESTARTS && !shuttingDown;
    }

    @Override
    public List<Diagnostic> getDiagnostics(Path filePath) {
        if (!isRunning()) {
            return Collections.emptyList();
        }
        return diagnosticsCache.getOrDefault(filePath.toUri().toString(), Collections.emptyList());
    }

    @Override
    public void didOpen(Path filePath, String content) {
        if (!isRunning()) return;

        try {
            String uri = filePath.toUri().toString();
            TextDocumentItem textDocItem = new TextDocumentItem(uri, languageIdForFile(filePath), 1, content);

            languageServer.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(textDocItem));
            openedUris.add(uri);
            LOG.debug("Sent didOpen for {}", uri);
        } catch (Exception e) {
            LOG.warn("Failed to send didOpen for {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Resolve the LSP language id for a file: a per-file mapping from {@code extensionToLanguage} wins.
     */
    private String languageIdForFile(Path filePath) {
        if (!extensionToLanguage.isEmpty()) {
            String name = filePath.getFileName() != null ? filePath.getFileName().toString() : "";
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
            String mapped = extensionToLanguage.get(ext);
            if (mapped != null) {
                return mapped;
            }
        }
        return languageId;
    }

    @Override
    public void didChange(Path filePath, String content) {
        if (!isRunning()) return;

        try {
            String uri = filePath.toUri().toString();
            TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent(content);
            VersionedTextDocumentIdentifier textDocId = new VersionedTextDocumentIdentifier(uri, 1);

            languageServer.getTextDocumentService().didChange(
                new DidChangeTextDocumentParams(textDocId, List.of(change))
            );
            LOG.debug("Sent didChange for {}", uri);
        } catch (Exception e) {
            LOG.warn("Failed to send didChange for {}: {}", filePath, e.getMessage());
        }
    }

    @Override
    public void didClose(Path filePath) {
        if (!isRunning()) return;

        try {
            String uri = filePath.toUri().toString();
            VersionedTextDocumentIdentifier textDocId = new VersionedTextDocumentIdentifier(uri, 1);
            languageServer.getTextDocumentService().didClose(
                new DidCloseTextDocumentParams(textDocId)
            );
            diagnosticsCache.remove(uri);
            LOG.debug("Sent didClose for {}", uri);
        } catch (Exception e) {
            LOG.warn("Failed to send didClose for {}: {}", filePath, e.getMessage());
        }
    }

    @Override
    public void didSave(Path filePath) {
        if (!isRunning()) return;

        try {
            String uri = filePath.toUri().toString();
            VersionedTextDocumentIdentifier textDocId = new VersionedTextDocumentIdentifier(uri, 1);
            languageServer.getTextDocumentService().didSave(
                new DidSaveTextDocumentParams(textDocId)
            );
            LOG.debug("Sent didSave for {}", uri);
        } catch (Exception e) {
            LOG.warn("Failed to send didSave for {}: {}", filePath, e.getMessage());
        }
    }

    // ========== Navigation operations ==========


    // params (1-based UI coords → 0-based protocol), the file is opened via
    // didOpen before the request if it isn't already, and the raw LSP4J result


    @Override
    public List<String> goToDefinition(Path filePath, int line, int character) {
        try {
            ensureOpen(filePath);
            var result = sendRequest(() -> textDocument().definition(
                new DefinitionParams(textDocId(filePath), position(line, character))), "definition");
            result = filterDefinitionEither(result, ignoredUris(urisOfDefinition(result)));
            return LspResultFormatter.formatDefinition(result, cwd());
        } catch (Exception e) {
            return List.of("Error performing goToDefinition: " + errorMessage(e));
        }
    }

    @Override
    public List<String> findReferences(Path filePath, int line, int character) {
        try {
            ensureOpen(filePath);
            List<? extends Location> result = sendRequest(() -> textDocument().references(
                new ReferenceParams(textDocId(filePath), position(line, character),
                    new ReferenceContext(true))), "references");
            result = filterLocations(result, ignoredUris(urisOfLocations(result)));
            return LspResultFormatter.formatReferences(result, cwd());
        } catch (Exception e) {
            return List.of("Error performing findReferences: " + errorMessage(e));
        }
    }

    @Override
    public String hover(Path filePath, int line, int character) {
        try {
            ensureOpen(filePath);
            Hover result = sendRequest(() -> textDocument().hover(
                new HoverParams(textDocId(filePath), position(line, character))), "hover");
            return LspResultFormatter.formatHover(result);
        } catch (Exception e) {
            return "Error performing hover: " + errorMessage(e);
        }
    }

    @Override
    public List<String> documentSymbol(Path filePath, int line, int character) {
        try {
            ensureOpen(filePath);
            var result = sendRequest(() -> textDocument().documentSymbol(
                new DocumentSymbolParams(textDocId(filePath))), "documentSymbol");
            return LspResultFormatter.formatDocumentSymbol(result, cwd());
        } catch (Exception e) {
            return List.of("Error performing documentSymbol: " + errorMessage(e));
        }
    }

    @Override
    public List<String> workspaceSymbol(Path filePath, int line, int character) {
        try {
            var result = sendRequest(() -> languageServer.getWorkspaceService()
                .symbol(new WorkspaceSymbolParams("")), "workspaceSymbol");
            result = filterWorkspaceSymbolsEither(result, ignoredUris(urisOfWorkspace(result)));
            return LspResultFormatter.formatWorkspaceSymbol(result, cwd());
        } catch (Exception e) {
            return List.of("Error performing workspaceSymbol: " + errorMessage(e));
        }
    }

    @Override
    public List<String> goToImplementation(Path filePath, int line, int character) {
        try {
            ensureOpen(filePath);
            var result = sendRequest(() -> textDocument().implementation(
                new ImplementationParams(textDocId(filePath), position(line, character))), "implementation");
            result = filterDefinitionEither(result, ignoredUris(urisOfDefinition(result)));
            return LspResultFormatter.formatDefinition(result, cwd());
        } catch (Exception e) {
            return List.of("Error performing goToImplementation: " + errorMessage(e));
        }
    }

    @Override
    public List<String> prepareCallHierarchy(Path filePath, int line, int character) {
        List<? extends CallHierarchyItem> items = prepareCallHierarchyRaw(filePath, line, character);
        return LspResultFormatter.formatPrepareCallHierarchy(items, cwd());
    }

    @Override
    public List<String> incomingCalls(Path filePath, int line, int character) {
        List<? extends CallHierarchyItem> items = prepareCallHierarchyRaw(filePath, line, character);
        if (items.isEmpty()) {
            return LspResultFormatter.formatPrepareCallHierarchy(items, cwd());
        }
        try {
            ensureOpen(filePath);
            List<? extends CallHierarchyIncomingCall> calls = sendRequest(() -> textDocument()
                .callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(items.getFirst())),
                "callHierarchyIncomingCalls");
            return LspResultFormatter.formatIncomingCalls(calls, cwd());
        } catch (Exception e) {
            return List.of("Error performing incomingCalls: " + errorMessage(e));
        }
    }

    @Override
    public List<String> outgoingCalls(Path filePath, int line, int character) {
        List<? extends CallHierarchyItem> items = prepareCallHierarchyRaw(filePath, line, character);
        if (items.isEmpty()) {
            return LspResultFormatter.formatPrepareCallHierarchy(items, cwd());
        }
        try {
            ensureOpen(filePath);
            List<? extends CallHierarchyOutgoingCall> calls = sendRequest(() -> textDocument()
                .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(items.getFirst())),
                "callHierarchyOutgoingCalls");
            return LspResultFormatter.formatOutgoingCalls(calls, cwd());
        } catch (Exception e) {
            return List.of("Error performing outgoingCalls: " + errorMessage(e));
        }
    }

    private List<? extends CallHierarchyItem> prepareCallHierarchyRaw(Path filePath, int line, int character) {
        try {
            ensureOpen(filePath);
            return sendRequest(() -> textDocument().prepareCallHierarchy(
                new CallHierarchyPrepareParams(textDocId(filePath), position(line, character))),
                "prepareCallHierarchy");
        } catch (Exception e) {
            LOG.debug("prepareCallHierarchy failed for {}: {}", filePath, errorMessage(e));
            return List.of();
        }
    }

    /** Open the file on the server (if not already) before a navigation request. */
    private void ensureOpen(Path filePath) {
        if (!isRunning()) return;
        String uri = filePath.toUri().toString();
        if (openedUris.contains(uri)) return;
        try {
            long size = Files.size(filePath);
            if (size > MAX_LSP_FILE_SIZE_BYTES) {
                LOG.debug("Skipping LSP didOpen for {}: file too large ({} bytes)", filePath, size);
                return;
            }
            String content = Files.readString(filePath);
            didOpen(filePath, content);
        } catch (IOException e) {
            LOG.debug("Failed to read {} for LSP didOpen: {}", filePath, e.getMessage());
        }
    }

    private TextDocumentService textDocument() {
        return languageServer.getTextDocumentService();
    }

    private static TextDocumentIdentifier textDocId(Path filePath) {
        return new TextDocumentIdentifier(filePath.toUri().toString());
    }

    private static Position position(int line, int character) {
        // UI exposes 1-based coords; the LSP protocol is 0-based.
        return new Position(line - 1, character - 1);
    }

    private static String cwd() {
        return System.getProperty("user.dir");
    }

    private static String errorMessage(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    // ========== gitignore filtering (G1) ==========


    // result whose file is git-ignored (build output, vendored trees, etc).

/**
     * Resolve the set of git-ignored URIs among {@code uris}.
     */
    Set<String> ignoredUris(List<String> uris) {
        if (workspaceRoot == null || uris.isEmpty()) {
            return Set.of();
        }
        List<String> paths = new ArrayList<>(uris.size());
        for (String uri : uris) {
            paths.add(uriToPath(uri));
        }
        Set<String> ignoredPaths = GitignoreFilter.ignoredPaths(workspaceRoot, paths);
        Set<String> ignored = new HashSet<>(ignoredPaths.size());
        for (int i = 0; i < uris.size(); i++) {
            if (ignoredPaths.contains(paths.get(i))) {
                ignored.add(uris.get(i));
            }
        }
        return ignored;
    }

    private static List<String> urisOfDefinition(
            Either<List<? extends Location>, List<? extends LocationLink>> result) {
        List<String> uris = new ArrayList<>();
        if (result.isLeft()) {
            for (Location l : result.getLeft()) {
                if (l != null && l.getUri() != null) uris.add(l.getUri());
            }
        } else {
            for (LocationLink l : result.getRight()) {
                if (l != null && l.getTargetUri() != null) uris.add(l.getTargetUri());
            }
        }
        return uris;
    }

    private static List<String> urisOfLocations(List<? extends Location> result) {
        List<String> uris = new ArrayList<>();
        for (Location l : result) {
            if (l != null && l.getUri() != null) uris.add(l.getUri());
        }
        return uris;
    }


    // is scoped to this method.
    @SuppressWarnings("deprecation")
    private static List<String> urisOfWorkspace(
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result) {
        List<String> uris = new ArrayList<>();
        if (result.isLeft()) {
            for (SymbolInformation s : result.getLeft()) {
                if (s != null && s.getLocation() != null && s.getLocation().getUri() != null) {
                    uris.add(s.getLocation().getUri());
                }
            }
        } else {
            for (WorkspaceSymbol s : result.getRight()) {
                if (s != null) {
                    String uri = workspaceSymbolUri(s.getLocation());
                    if (uri != null) uris.add(uri);
                }
            }
        }
        return uris;
    }

    private static String workspaceSymbolUri(Either<Location, WorkspaceSymbolLocation> loc) {
        if (loc == null) return null;
        return loc.isLeft() ? loc.getLeft().getUri() : loc.getRight().getUri();
    }

    @SuppressWarnings("unchecked")
    private static Either<List<? extends Location>, List<? extends LocationLink>> filterDefinitionEither(
            Either<List<? extends Location>, List<? extends LocationLink>> result, Set<String> ignored) {
        if (result.isLeft()) {
            List<Location> kept = new ArrayList<>();
            for (Location l : result.getLeft()) {
                if (l == null || !ignored.contains(l.getUri())) kept.add(l);
            }
            return (Either<List<? extends Location>, List<? extends LocationLink>>) (Either<?, ?>) Either.forLeft(kept);
        }
        List<LocationLink> kept = new ArrayList<>();
        for (LocationLink l : result.getRight()) {
            if (l == null || !ignored.contains(l.getTargetUri())) kept.add(l);
        }
        return (Either<List<? extends Location>, List<? extends LocationLink>>) (Either<?, ?>) Either.forRight(kept);
    }

    private static <L extends Location> List<L> filterLocations(List<L> result, Set<String> ignored) {
        return result.stream()
            .filter(l -> l == null || !ignored.contains(l.getUri())).toList();
    }


    // is scoped to this method.
    @SuppressWarnings({"unchecked", "deprecation"})
    private static Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> filterWorkspaceSymbolsEither(
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result, Set<String> ignored) {
        if (result.isLeft()) {
            List<SymbolInformation> kept = new ArrayList<>();
            for (SymbolInformation s : result.getLeft()) {
                if (s == null || s.getLocation() == null
                    || !ignored.contains(s.getLocation().getUri())) kept.add(s);
            }
            return (Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>)
                (Either<?, ?>) Either.forLeft(kept);
        }
        List<WorkspaceSymbol> kept = new ArrayList<>();
        for (WorkspaceSymbol s : result.getRight()) {
            if (s == null || !ignored.contains(workspaceSymbolUri(s.getLocation()))) kept.add(s);
        }
        return (Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>)
            (Either<?, ?>) Either.forRight(kept);
    }

    // ========== Diagnostic handling ==========

    /**
     * Handle publishDiagnostics notification from the language server.
     */
    public void onPublishDiagnostics(PublishDiagnosticsParams params) {
        String uri = params.getUri();
        List<Diagnostic> diagnostics = new ArrayList<>();

        for (org.eclipse.lsp4j.Diagnostic lspDiag : params.getDiagnostics()) {
            diagnostics.add(convertDiagnostic(uri, lspDiag));
        }

        diagnosticsCache.put(uri, diagnostics);
        LOG.debug("Cached {} diagnostics for {}", diagnostics.size(), uri);

        if (registry != null) {
            registry.registerDiagnostics(uri, diagnostics);
        }
    }

    private Diagnostic convertDiagnostic(String uri, org.eclipse.lsp4j.Diagnostic lspDiag) {
        Diagnostic.Severity severity = mapSeverity(lspDiag.getSeverity());
        String message = lspDiag.getMessage();
        String source = lspDiag.getSource();
        String code = lspDiag.getCode() != null ? lspDiag.getCode().toString() : null;

        Range range = lspDiag.getRange();
        return new Diagnostic(
            uriToPath(uri),
            range.getStart().getLine(),
            range.getStart().getCharacter(),
            range.getEnd().getLine(),
            range.getEnd().getCharacter(),
            severity, message, source, code
        );
    }

    /** Converts a {@code file://} URI to a plain filesystem path; non-file URIs pass through unchanged. */
    private static String uriToPath(String uri) {
        try {
            return Path.of(URI.create(uri)).toString();
        } catch (Exception _) {
            return uri;
        }
    }

    private Diagnostic.Severity mapSeverity(DiagnosticSeverity severity) {

        // 'Information') — align so an unlabeled diagnostic is never silently
        // downgraded and hidden (GAP-4).
        if (severity == null) return Diagnostic.Severity.ERROR;
        return switch (severity) {
            case Error -> Diagnostic.Severity.ERROR;
            case Warning -> Diagnostic.Severity.WARNING;
            case Information -> Diagnostic.Severity.INFORMATION;
            case Hint -> Diagnostic.Severity.HINT;
            default -> Diagnostic.Severity.ERROR;
        };
    }

    // ========== Language client implementation ==========

    /**
     * Language client implementation for receiving server notifications.
     */
    private class LspLanguageClient implements LanguageClient {

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            ProcessLspServerInstance.this.onPublishDiagnostics(params);
        }

        @Override
        public void logMessage(MessageParams message) {
            LOG.debug("{} window/logMessage: {}", languageId, message.getMessage());
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            LOG.info("{} message [{}]: {}", languageId, messageParams.getType(), messageParams.getMessage());
        }

        @Override
        public void telemetryEvent(Object object) {
            LOG.debug("{} telemetry/event: {}", languageId, object);
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            LOG.debug("{} window/showMessageRequest: {}", languageId, requestParams.getMessage());
            return CompletableFuture.completedFuture(null);
        }

        /**
         * Handles server→client {@code workspace/configuration} requests (GAP-3).
         */
        @JsonRequest("workspace/configuration") @Override
        public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
            List<Object> items = new ArrayList<>();
            int n = params != null && params.getItems() != null ? params.getItems().size() : 0;
            for (int i = 0; i < n; i++) {
                items.add(null);
            }
            return CompletableFuture.completedFuture(items);
        }
    }

    // ========== Utility methods ==========

    /**
     * Get all cached diagnostics for the workspace.
     */
    public Map<String, List<Diagnostic>> getAllDiagnostics() {
        return Collections.unmodifiableMap(diagnosticsCache);
    }
}
