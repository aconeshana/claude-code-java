package com.claudecode.lsp;

import org.apache.commons.lang3.Strings;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.claudecode.core.serialization.JsonUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generic {@link ProcessLspServerInstance} behavior that doesn't require a
 * real language server subprocess: diagnostic conversion/URI-to-path
 * handling and registry forwarding. Real subprocess lifecycle is exercised
 * manually (see {@link JdtLsPresetTest} for the jdt.ls-gated equivalent).
 */
class ProcessLspServerInstanceTest {

    private static org.eclipse.lsp4j.Diagnostic lsp4jDiagnostic(int line, int character, String message) {
        org.eclipse.lsp4j.Diagnostic diag = new org.eclipse.lsp4j.Diagnostic();
        diag.setRange(new Range(new Position(line, character), new Position(line, character + 5)));
        diag.setMessage(message);
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setSource("typescript");
        return diag;
    }

    private static PublishDiagnosticsParams publish(String uri, org.eclipse.lsp4j.Diagnostic... diags) {
        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri(uri);
        params.setDiagnostics(List.of(diags));
        return params;
    }

    @Test
    void languageId_returnsConfiguredValue() {
        var server = new ProcessLspServerInstance("typescript",
            List.of("typescript-language-server", "--stdio"), null, null);
        assertEquals("typescript", server.languageId());
    }

    @Test
    void isRunning_falseBeforeInitialize() {
        var server = new ProcessLspServerInstance("java", List.of("echo"), null, null);
        assertFalse(server.isRunning());
    }

    @Test
    void onPublishDiagnostics_convertsFileUriToPlainPath() {
        var server = new ProcessLspServerInstance("typescript", List.of("echo"), null, null);
        Path file = Path.of("/tmp/Test.ts");
        String uri = file.toUri().toString();

        server.onPublishDiagnostics(publish(uri, lsp4jDiagnostic(1, 2, "Type error")));

// getDiagnostics(Path) gates on isRunning, which requires a live
        // subprocess — use the raw cache accessor instead since we never
// call initialize here.
        List<Diagnostic> diags = server.getAllDiagnostics().get(uri);
        assertNotNull(diags);
        assertEquals(1, diags.size());
        // filePath must be a real filesystem path, not the raw file:// URI
        assertEquals(file.toString(), diags.getFirst().filePath());
        assertNotEquals(uri, diags.getFirst().filePath());
    }

    @Test
    void onPublishDiagnostics_nonFileUri_passesThroughUnchanged() {
        var server = new ProcessLspServerInstance("typescript", List.of("echo"), null, null);
        String opaqueUri = "untitled:Untitled-1";

        server.onPublishDiagnostics(publish(opaqueUri, lsp4jDiagnostic(0, 0, "msg")));

        List<Diagnostic> diags = server.getAllDiagnostics().get(opaqueUri);
        assertNotNull(diags);
        assertEquals(1, diags.size());
        // Non-file:// scheme can't be converted to a real path — falls back to the raw URI.
        assertEquals(opaqueUri, diags.getFirst().filePath());
    }

    @Test
    void onPublishDiagnostics_nullSeverityDefaultsToError() {

        // 'Error'; a null LSP severity must NOT be downgraded to INFORMATION.
        var server = new ProcessLspServerInstance("typescript", List.of("echo"), null, null);
        String uri = Path.of("/tmp/NoSev.ts").toUri().toString();

        org.eclipse.lsp4j.Diagnostic diag = new org.eclipse.lsp4j.Diagnostic();
        diag.setRange(new Range(new Position(1, 2), new Position(1, 7)));
        diag.setMessage("untyped error");
// deliberately no setSeverity -> null

        server.onPublishDiagnostics(publish(uri, diag));

        List<Diagnostic> diags = server.getAllDiagnostics().get(uri);
        assertNotNull(diags);
        assertEquals(1, diags.size());
        assertEquals(Diagnostic.Severity.ERROR, diags.getFirst().severity(),
            "null LSP severity must default to ERROR (GAP-4), not INFORMATION");
    }

    @Test
    void onPublishDiagnostics_forwardsToRegistry() {
        var registry = new LspDiagnosticRegistry();
        var received = new AtomicReference<List<Diagnostic>>();
        registry.addListener((_, diags) -> received.set(diags));

        var server = new ProcessLspServerInstance("java", List.of("echo"), null, registry);
        server.onPublishDiagnostics(publish("file:///tmp/Foo.java", lsp4jDiagnostic(0, 0, "boom")));

        assertNotNull(received.get());
        assertEquals(1, received.get().size());
        assertEquals("boom", received.get().getFirst().message());
    }

    @Test
    void onPublishDiagnostics_nullRegistry_doesNotThrow() {
        var server = new ProcessLspServerInstance("java", List.of("echo"), null, null);
        assertDoesNotThrow(() ->
            server.onPublishDiagnostics(publish("file:///tmp/Foo.java", lsp4jDiagnostic(0, 0, "boom"))));
    }

    @Test
    void didChange_sendsWholeDocumentContentLikeTs() throws Exception {
        AtomicReference<DidChangeTextDocumentParams> received = new AtomicReference<>();
        TextDocumentService textDocumentService = proxy(TextDocumentService.class,
            (method, args) -> {
                if (Strings.CS.equals(method, "didChange")) {
                    received.set((DidChangeTextDocumentParams) args[0]);
                }
                return null;
            });
        LanguageServer languageServer = proxy(LanguageServer.class,
            (method, _) -> Strings.CS.equals(method, "getTextDocumentService")
                ? textDocumentService
                : null);
        var server = new ProcessLspServerInstance("typescript", List.of("echo"), null, null) {
            @Override
            public boolean isRunning() {
                return true;
            }
        };
        setField(server, "languageServer", languageServer);

        String content = "const answer = 42;\n";
        server.didChange(Path.of("/tmp/example.ts"), content);

        DidChangeTextDocumentParams params = received.get();
        assertNotNull(params, "didChange notification must be sent");
        assertEquals(1, params.getContentChanges().size());
        var change = params.getContentChanges().getFirst();
        assertEquals(content, change.getText());
        assertNull(change.getRange(),
            "TS sends contentChanges: [{ text }], so a full-document change has no range");
    }

    @Test
    void severityMapping_allLevels() {
        assertEquals(Diagnostic.Severity.ERROR, Diagnostic.Severity.fromValue(1));
        assertEquals(Diagnostic.Severity.WARNING, Diagnostic.Severity.fromValue(2));
        assertEquals(Diagnostic.Severity.INFORMATION, Diagnostic.Severity.fromValue(3));
        assertEquals(Diagnostic.Severity.HINT, Diagnostic.Severity.fromValue(4));
    }

    @Test
    void initialize_nonStdioTransport_skipsWithoutSpawning() throws Exception {
        LspServerSettings s = LspServerSettings.fromNode(JsonUtils.getMapper().readTree(
            "{\"command\":\"should-not-run\",\"transport\":\"socket\","
                + "\"extensionToLanguage\":{\".ts\":\"typescript\"}}"));
        var server = new ProcessLspServerInstance("typescript", s, null);
        CompletableFuture<Void> readiness = server.readiness().toCompletableFuture();
        Path ws = Path.of(System.getProperty("java.io.tmpdir"));
        // Non-stdio transports are unsupported; the server must skip startup
        // gracefully without spawning a subprocess or throwing.
        assertDoesNotThrow(() -> server.initialize(ws));
        assertFalse(server.isRunning(), "non-stdio transport must skip startup");
        assertThrows(ExecutionException.class, () -> readiness.get(1, TimeUnit.SECONDS),
            "unsupported transport must release readiness waiters with a failure");
    }

    @Test
    void crashedServer_stopsAfterMaxRestarts() throws Exception {
// A command that exits non-zero immediately fails initialize and leaves the
        // server down. With the lazy crash-recovery model (G2) there is no eager
        // respawn, so the server stays stopped — restart only happens later via
// ensureStarted and is bounded by MAX_RESTARTS.
        var server = new ProcessLspServerInstance("java",
            List.of("sh", "-c", "exit 3"), null, null);
        Path ws = Path.of(System.getProperty("java.io.tmpdir"));

        assertThrows(RuntimeException.class, () -> server.initialize(ws));

        // No eager restart: the server must remain down (give the exit-watch a beat).
        for (int i = 0; i < 20; i++) {
            Thread.sleep(50);
            assertFalse(server.isRunning(), "server must not respawn on its own after a crash");
        }
    }

    @Test
    void ignoredUris_filtersGitIgnoredResults(@TempDir Path repo) throws Exception {
        // G1 regression: ignoredUris must return the ORIGINAL file:// URIs (not the
        // decoded paths). The previous implementation returned decoded paths, which
        // the navigation filters compare against raw file:// URIs and so never matched
        // — meaning git-ignored results were never dropped.
        Assumptions.assumeTrue(gitAvailable(), "git is required for this test");

        git(repo, "init");
        Files.writeString(repo.resolve(".gitignore"), "*.ignored\n");
        Path kept = repo.resolve("kept.ts");
        Path ignored = repo.resolve("ignored.ignored");
        Files.writeString(kept, "export const kept = 1;\n");
        Files.writeString(ignored, "export const ignored = 2;\n");

        var server = new ProcessLspServerInstance("typescript", List.of("echo"), null, null);
        setField(server, "workspaceRoot", repo);

        String keptUri = kept.toUri().toString();
        String ignoredUri = ignored.toUri().toString();

        Set<String> result = server.ignoredUris(List.of(keptUri, ignoredUri));

        assertEquals(Set.of(ignoredUri), result,
            "only the git-ignored URI should be returned, in its original file:// form");
        assertFalse(result.contains(keptUri), "kept.ts must not be filtered out");
    }

    @Test
    void crash_doesNotEagerlyRestart() throws Exception {
        // G2 regression: onCrash must NOT eagerly respawn the process. A crashed
// server stays down until a future request calls ensureStarted (matching

        var server = new ProcessLspServerInstance("java",
            List.of("sh", "-c", "exit 3"), null, null);
        Path ws = Path.of(System.getProperty("java.io.tmpdir"));

        assertThrows(RuntimeException.class, () -> server.initialize(ws),
            "a process that exits non-zero must fail initialize()");
        assertFalse(server.isRunning(),
            "crashed server must not eagerly restart on its own");
    }

    @Test
    void ensureStarted_onFreshServerDoesNotAutoStart() {
// ensureStarted is the crash-recovery path, not an auto-start: a freshly
        // constructed server (crashCount == 0) must be left parked.
        var server = new ProcessLspServerInstance("java", List.of("echo"), null, null);
        server.ensureStarted();
        assertFalse(server.isRunning(),
            "ensureStarted must not auto-start a server that never started or crashed");
    }

    @Test
    void ensureStarted_pastMaxRestartsGivesUp() throws Exception {
// Once crashCount exceeds MAX_RESTARTS, ensureStarted must stop trying
        // rather than respawn indefinitely.
        var server = new ProcessLspServerInstance("java",
            List.of("sh", "-c", "exit 3"), null, null);
        Path ws = Path.of(System.getProperty("java.io.tmpdir"));
        setField(server, "workspaceRoot", ws);
        setField(server, "crashCount", 99);

        server.ensureStarted();
        assertFalse(server.isRunning(),
            "ensureStarted must give up once the restart budget is exhausted");
        assertThrows(ExecutionException.class,
            () -> server.readiness().toCompletableFuture().get(1, TimeUnit.SECONDS),
            "callers must be released immediately when the restart budget is exhausted");
    }

    @Test
    void sendRequest_allowsThreeContentModifiedRetries() throws Exception {

        // three retries. The fourth invocation must therefore still be reachable.
        var server = new ProcessLspServerInstance("rust", List.of("echo"), null, null);
        AtomicInteger invocations = new AtomicInteger();
        Supplier<CompletableFuture<String>> request = () -> {
            if (invocations.incrementAndGet() <= 3) {
                return CompletableFuture.failedFuture(contentModifiedError());
            }
            return CompletableFuture.completedFuture("ok");
        };

        assertEquals("ok", invokeSendRequest(server, request, "definition"));
        assertEquals(4, invocations.get(),
            "three retries must be in addition to the initial request");
    }

    @Test
    void sendRequest_interruptedBackoffRestoresInterruptFlag() throws Exception {
        var server = new ProcessLspServerInstance("rust", List.of("echo"), null, null);
        CountDownLatch firstAttempt = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedAtExit = new AtomicBoolean();
        Supplier<CompletableFuture<String>> request = () -> {
            firstAttempt.countDown();
            return CompletableFuture.failedFuture(contentModifiedError());
        };

        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                invokeSendRequest(server, request, "definition");
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                interruptedAtExit.set(Thread.currentThread().isInterrupted());
            }
        });

        assertTrue(firstAttempt.await(1, TimeUnit.SECONDS), "request did not enter backoff");
        Thread.sleep(50);
        worker.interrupt();
        worker.join(1000);

        assertFalse(worker.isAlive(), "interrupted backoff must return promptly");
        assertInstanceOf(InterruptedException.class, failure.get());
        assertTrue(interruptedAtExit.get(), "sendRequest must preserve the interrupt flag");
    }

    private static ResponseErrorException contentModifiedError() {
        return new ResponseErrorException(new ResponseError(
            ResponseErrorCode.ContentModified, "content modified", null));
    }

    private static <T> T invokeSendRequest(ProcessLspServerInstance server,
                                             Supplier<CompletableFuture<T>> request,
                                             String method) throws Exception {
        Method sendRequest = ProcessLspServerInstance.class.getDeclaredMethod(
            "sendRequest", Supplier.class, String.class);
        sendRequest.setAccessible(true);
        try {
            @SuppressWarnings("unchecked")
            T result = (T) sendRequest.invoke(server, request, method);
            return result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        Object value = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (_, method, args) -> invocation.invoke(method.getName(), args));
        return type.cast(value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException _) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception _) {
            return false;
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "git " + args[0] + " timed out");
        assertEquals(0, p.exitValue(), "git " + args[0] + " failed");
    }
}
