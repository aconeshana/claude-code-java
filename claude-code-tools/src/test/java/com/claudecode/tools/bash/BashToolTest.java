package com.claudecode.tools.bash;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SandboxDecision;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.WorkingDirectoryController;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.monitor.MonitorFeatureGate;
import com.claudecode.tools.sandbox.NoopSandboxBackend;
import com.claudecode.tools.FakeSandboxBackend;

class BashToolTest {

    private final BashTool tool = new BashTool();
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    @Test
    void nameIsBash() {
        assertEquals("Bash", tool.name());
    }

    @Test
    void defaultAttributionTracksCurrentSonnetModel() {
        String description = tool.description();

        assertTrue(Strings.CS.contains(description,
            "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"));
        assertFalse(Strings.CS.contains(description,
            "Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"));
    }

    @Test
    void unknownModelAttributionFallsBackToCurrentOpusModel() {
        tool.setModelSupplier(() -> "private-gateway-model");

        assertTrue(Strings.CS.contains(tool.description(),
            "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"));
    }

    @Test
    void maxResultSizeChars_matchesReleased197() {
        assertEquals(30_000, tool.maxResultSizeChars());
    }

    @Test
    void schemaRequiresCommand() {
        assertTrue(tool.inputSchema().has("required"));
        assertEquals("command", tool.inputSchema().get("required").get(0).asText());
    }

    @Test
    void schemaUsesConfiguredMaximumTimeout() {
        BashTool configured = new BashTool(name -> switch (name) {
            case "BASH_DEFAULT_TIMEOUT_MS" -> "700000";
            case "BASH_MAX_TIMEOUT_MS" -> "300000";
            default -> null;
        });

        assertEquals("Optional timeout in milliseconds (max 700000)",
            configured.inputSchema().path("properties").path("timeout")
                .path("description").asText());
    }

    @Test
    void executeSimpleEcho() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "echo hello");

        String result = (String) tool.call(input, ctx());
        assertTrue(Strings.CS.contains(result, "hello"));
    }

    @Test
    void approvedSimulatedSedWritesExactPreviewWithoutExecutingCommand(@TempDir Path project)
            throws Exception {
        Path file = project.resolve("config.txt");
        Files.writeString(file, "old\r\n");
        FileStateCache cache = new FileStateCache();
        ToolExecutionContext context = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .workingDirectory(project.toString())
            .fileStateCache(cache)
            .build();
        ObjectNode input = mapper.createObjectNode().put("command", "false");
        input.putObject("_simulatedSedEdit")
            .put("filePath", file.toString())
            .put("newContent", "new\n");

        String result = (String) tool.call(input, context);

        assertEquals("", result);
        assertEquals("new\r\n", Files.readString(file));
        assertEquals("new\n", cache.get(file.toString()).content());
    }

    @Test
    void successfulGrepCachesExplicitFileForPostCompactRestore(@TempDir Path project)
            throws Exception {
        Path fixture = project.resolve("probe.txt");
        Files.writeString(fixture, "WIRE197_GREP_LINE\n");
        FileStateCache cache = new FileStateCache();
        ToolExecutionContext context = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .workingDirectory(project.toString())
            .fileStateCache(cache)
            .build();

        String result = (String) tool.call(mapper.createObjectNode().put(
            "command", "grep WIRE197_GREP_LINE '" + fixture + "'"), context);

        assertTrue(Strings.CS.contains(result, "WIRE197_GREP_LINE"), result);
        assertEquals("WIRE197_GREP_LINE\n", cache.get(fixture.toString()).content());
    }

    @Test
    void commandCompletingBeforeReleasedProgressThresholdEmitsNoProgress() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskRegistry.setGlobalForTest(registry);
        List<ToolExecutionContext.ProgressUpdate> updates = new ArrayList<>();
        ToolExecutionContext context = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .workingDirectory(System.getProperty("user.dir"))
            .progressSink(updates::add)
            .build();
        try {
            String result = (String) tool.call(
                mapper.createObjectNode().put("command", "true"), context);

            assertFalse(Strings.CS.startsWith(result, "Error:"), result);
            assertTrue(updates.isEmpty(),
                "released Bash progress is silent when the process finishes before 2 seconds");
            assertTrue(registry.listForegroundBackgroundable().isEmpty(),
                "a short command must never flash a foreground shell task");
            assertTrue(registry.store().list().isEmpty(),
                "a short command must leave no transient task state behind");
        } finally {
            TaskRegistry.resetGlobalForTest();
        }
    }

    @Test
    void foregroundCommandCtrlBContinuesSameProcessAsBackground(@TempDir Path project)
            throws Exception {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskRegistry.setGlobalForTest(registry);
        try {
            ToolExecutionContext context = ToolExecutionContext
                .builder(new AbortController(), "test-session")
                .workingDirectory(project.toString())
                .progressSink(_ -> {})
                .build();
            ObjectNode input = mapper.createObjectNode();
            input.put("command", "printf started; sleep 3; echo detached-done");
            AtomicReference<Object> returned = new AtomicReference<>();
            CountDownLatch callerDone = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                returned.set(tool.call(input, context));
                callerDone.countDown();
            });

            Thread.sleep(500);
            assertTrue(registry.listForegroundBackgroundable().isEmpty(),
                "197 only exposes Ctrl+B after the two-second progress threshold");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (registry.listForegroundBackgroundable().isEmpty()
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, registry.listForegroundBackgroundable().size());
            String taskId = registry.listForegroundBackgroundable().getFirst().id();

            assertEquals(1, registry.backgroundAllForegroundTasks());
            assertTrue(callerDone.await(2, TimeUnit.SECONDS));
            assertTrue(Strings.CS.startsWith(returned.get().toString(),
                "Command was manually backgrounded by user with ID: " + taskId),
                returned.get().toString());
            assertEquals(taskId, registry.listBackground().getFirst().id());

            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (registry.get(taskId).orElseThrow().status()
                    == TaskStatus.RUNNING
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(TaskStatus.COMPLETED,
                registry.get(taskId).orElseThrow().status());
            assertTrue(Strings.CS.contains(Files.readString(
                registry.getForegroundShellHandle(taskId).orElseThrow().getOutputPath()),
                "detached-done"));
        } finally {
            TaskRegistry.resetGlobalForTest();
        }
    }

    @Test
    void foregroundCdPublishesPhysicalCwd(@TempDir Path project) throws Exception {
        Path child = Files.createDirectory(project.resolve("child"));
        RecordingCwdController cwd = new RecordingCwdController(project, List.of(project));
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory(project.toString()).build()
            .withWorkingDirectoryController(cwd);

        String result = (String) tool.call(
            mapper.createObjectNode().put("command", "cd child"), context);

        assertFalse(Strings.CS.startsWith(result, "Error:"), result);
        assertEquals(child.toRealPath(), cwd.current);
        assertEquals(List.of(child.toRealPath()), cwd.updates);
    }

    @Test
    void maintainProjectWorkingDirectoryResetsWithoutWarning(@TempDir Path project) throws Exception {
        Path child = Files.createDirectory(project.resolve("child"));
        RecordingCwdController cwd = new RecordingCwdController(project, List.of(project));
        BashTool maintaining = new BashTool(name ->
            Strings.CS.equals("CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR", name) ? "true" : null);
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory(project.toString()).build()
            .withWorkingDirectoryController(cwd);

        String result = (String) maintaining.call(
            mapper.createObjectNode().put("command", "cd child"), context);

        assertEquals(project.toRealPath(), cwd.current);
        assertEquals(List.of(project.toRealPath()), cwd.updates,
            "maintain mode publishes only the final restored cwd");
        assertFalse(Strings.CS.contains(result, "Shell cwd was reset to"), result);
        assertNotEquals(child.toRealPath(), cwd.current);
    }

    @Test
    void cwdOutsideAllowedDirectoriesResetsAndWarns(@TempDir Path root) throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        RecordingCwdController cwd = new RecordingCwdController(project, List.of(project));
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory(project.toString()).build()
            .withWorkingDirectoryController(cwd);

        String result = (String) tool.call(mapper.createObjectNode().put(
            "command", "cd '" + outside + "'"), context);

        assertEquals(project.toRealPath(), cwd.current);
        assertEquals(List.of(project.toRealPath()), cwd.updates);
        assertTrue(Strings.CS.contains(result, "Shell cwd was reset to " + project.toRealPath()), result);
    }

    @Test
    void cwdInsideAdditionalWorkingDirectoryPersists(@TempDir Path root) throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        Path additional = Files.createDirectory(root.resolve("additional"));
        RecordingCwdController cwd =
            new RecordingCwdController(project, List.of(project, additional));
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory(project.toString()).build()
            .withWorkingDirectoryController(cwd);

        String result = (String) tool.call(mapper.createObjectNode().put(
            "command", "cd '" + additional + "'"), context);

        assertFalse(Strings.CS.contains(result, "Shell cwd was reset to"), result);
        assertEquals(additional.toRealPath(), cwd.current);
        assertEquals(List.of(additional.toRealPath()), cwd.updates);
    }

    @Test
    void failedCommandDoesNotMutateSessionCwd(@TempDir Path project) throws Exception {
        RecordingCwdController cwd = new RecordingCwdController(project, List.of(project));
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory(project.toString()).build()
            .withWorkingDirectoryController(cwd);

        tool.call(mapper.createObjectNode().put("command", "cd / && false"), context);

        assertEquals(project.toRealPath(), cwd.current);
        assertTrue(cwd.updates.isEmpty());
    }



    @Test
    void stdoutOverEnvLimit_isTruncatedWithTsMarker() {
        BashTool truncating = new BashTool(
            name -> Strings.CS.equals("BASH_MAX_OUTPUT_LENGTH", name) ? "500" : null);
        ObjectNode input = mapper.createObjectNode();
        // 100 lines × 11 chars = 1100 chars stdout, well over the 500 limit.
        input.put("command", "for i in $(seq 1 100); do echo 0123456789; done");

        String result = (String) truncating.call(input, ctx());
        assertTrue(Strings.CS.contains(result, "... ["), result);
        assertTrue(Strings.CS.contains(result, "lines truncated] ..."), result);

        assertTrue(Strings.CS.startsWith(result, "0123456789"), result);
    }

    @Test
    void envTruncationPersistsUnabridgedStdoutAndStderr(@TempDir Path tempDir)
            throws Exception {
        Path toolResultsDir = tempDir.resolve("tool-results");
        BashTool truncating = new BashTool(
            name -> Strings.CS.equals("BASH_MAX_OUTPUT_LENGTH", name) ? "500" : null,
            new NoopSandboxBackend(), () -> true, (_, _) -> toolResultsDir);
        ObjectNode input = mapper.createObjectNode();
        input.put("command", """
            i=1
            while [ "$i" -le 100 ]; do
              printf 'OUT-%03d-abcdefghij\\n' "$i"
              printf 'ERR-%03d-klmnopqrst\\n' "$i" >&2
              i=$((i + 1))
            done
            echo OUT-LAST-MARKER
            echo ERR-LAST-MARKER >&2
            """);

        String result = (String) truncating.call(input, ctx());

        assertTrue(Strings.CS.contains(result, "lines truncated] ..."), result);
        assertTrue(Strings.CS.contains(result, "Full output saved to:"), result);
        try (var files = Files.list(toolResultsDir)) {
            Path savedPath = files.findFirst().orElseThrow();
            String saved = Files.readString(savedPath);
            assertTrue(Strings.CS.contains(saved, "OUT-LAST-MARKER"), saved);
            assertTrue(Strings.CS.contains(saved, "ERR-LAST-MARKER"), saved);
        }
    }

    @Test
    void stdoutUnderEnvLimit_isNotTruncated() {
        BashTool truncating = new BashTool(
            name -> Strings.CS.equals("BASH_MAX_OUTPUT_LENGTH", name) ? "500" : null);
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "echo hello");

        String result = (String) truncating.call(input, ctx());
        assertTrue(Strings.CS.contains(result, "hello"));
        assertFalse(Strings.CS.contains(result, "lines truncated"), result);
    }

    @Test
    void invalidEnvValue_fallsBackToDefaultLimit() {
        // Garbage env value → EnvValidation falls back to the 30_000 default,
        // so a ~1100-char output must NOT be truncated.
        BashTool tolerant = new BashTool(
            name -> Strings.CS.equals("BASH_MAX_OUTPUT_LENGTH", name) ? "not-a-number" : null);
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "for i in $(seq 1 100); do echo 0123456789; done");

        String result = (String) tolerant.call(input, ctx());
        assertFalse(Strings.CS.contains(result, "lines truncated"), result);
    }

    @Test
    void unsetEnv_defaultLimitAppliesTo30000Chars() {
        // Default constructor reads the real env; BASH_MAX_OUTPUT_LENGTH is not
        // set in CI, so > 30_000 chars must hit the default truncation point.
        ObjectNode input = mapper.createObjectNode();
        // 4000 lines × 11 chars = 44_000 chars > 30_000 default.
        input.put("command", "for i in $(seq 1 4000); do echo 0123456789; done");

        String result = (String) new BashTool(_ -> null).call(input, ctx());
        assertTrue(Strings.CS.contains(result, "lines truncated"), () -> "expected default-limit truncation");
    }

    @Test
    void emptyCommandReturnsError() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "");

        String result = (String) tool.call(input, ctx());
        assertTrue(Strings.CS.contains(result, "Error"));
    }

    @Test
    void permissionDeniesEmptyCommand() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "");

        PermissionDecision decision = tool.checkPermissions(input,
                ToolPermissionContext.of(Path.of(".")));
        assertInstanceOf(PermissionDecision.Deny.class, decision);
    }

    @Test
    void permissionAllowsReadOnlyCommand() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "ls -la");

        PermissionDecision decision = tool.checkPermissions(input,
                ToolPermissionContext.of(Path.of(".")));
        assertInstanceOf(PermissionDecision.Allow.class, decision);
    }

    @Test
    void permissionAsksForWriteCommand() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "rm -rf /tmp/test");

        PermissionDecision decision = tool.checkPermissions(input,
                ToolPermissionContext.of(Path.of(".")));
        assertInstanceOf(PermissionDecision.Ask.class, decision);
    }

    @Test
    void permissionDeniesIncompleteCommand() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "echo hello |");

        PermissionDecision decision = tool.checkPermissions(input,
                ToolPermissionContext.of(Path.of(".")));
        assertInstanceOf(PermissionDecision.Deny.class, decision);
    }

    // isSearchOrReadCommand tests
    @Test
    void searchOrReadDetectsGrep() {
        assertTrue(BashTool.isSearchOrReadCommand("grep -r pattern ."));
    }

    @Test
    void searchOrReadDetectsFind() {
        assertTrue(BashTool.isSearchOrReadCommand("find . -name '*.java'"));
    }

    @Test
    void searchOrReadDetectsLs() {
        assertTrue(BashTool.isSearchOrReadCommand("ls -la"));
    }

    @Test
    void searchOrReadDetectsCat() {
        assertTrue(BashTool.isSearchOrReadCommand("cat file.txt"));
    }

    @Test
    void searchOrReadDetectsHead() {
        assertTrue(BashTool.isSearchOrReadCommand("head -n 10 file.txt"));
    }

    @Test
    void searchOrReadDetectsTail() {
        assertTrue(BashTool.isSearchOrReadCommand("tail -f log.txt"));
    }

    @Test
    void searchOrReadDetectsWc() {
        assertTrue(BashTool.isSearchOrReadCommand("wc -l file.txt"));
    }

    @Test
    void searchOrReadDetectsGitLog() {
        assertTrue(BashTool.isSearchOrReadCommand("git log --oneline"));
    }

    @Test
    void searchOrReadDetectsGitStatus() {
        assertTrue(BashTool.isSearchOrReadCommand("git status"));
    }

    @Test
    void searchOrReadRejectsRm() {
        assertFalse(BashTool.isSearchOrReadCommand("rm file.txt"));
    }

    @Test
    void searchOrReadRejectsMv() {
        assertFalse(BashTool.isSearchOrReadCommand("mv a b"));
    }

    @Test
    void searchOrReadRejectsNull() {
        assertFalse(BashTool.isSearchOrReadCommand(null));
    }

    @Test
    void searchOrReadRejectsBlank() {
        assertFalse(BashTool.isSearchOrReadCommand(""));
    }

    @Test
    void searchOrReadDetectsPipedReadCommands() {
        assertTrue(BashTool.isSearchOrReadCommand("cat file.txt | grep pattern"));
    }

    @Test
    void searchOrReadRejectsPipedWriteCommand() {
        assertFalse(BashTool.isSearchOrReadCommand("cat file.txt | tee output.txt"));
    }

    @Test
    void taskActivityClassificationMatchesReleased197CommandSets() {
        var mixed = BashTool.classifySearchOrReadCommand("cat file.txt | grep pattern");
        assertTrue(mixed.isSearch());
        assertTrue(mixed.isRead());
        assertFalse(mixed.isList());

        var neutralPrefix = BashTool.classifySearchOrReadCommand("echo ready | rg Task");
        assertTrue(neutralPrefix.isSearch());
        assertFalse(neutralPrefix.isRead());

        var list = BashTool.classifySearchOrReadCommand("ls -la");
        assertFalse(list.isSearch());
        assertFalse(list.isRead());
        assertTrue(list.isList());

        var unsupported = BashTool.classifySearchOrReadCommand("git status | rg Task");
        assertFalse(unsupported.isSearch());
        assertFalse(unsupported.isRead());
        assertFalse(unsupported.isList());
    }

    // Incomplete command tests
    @Test
    void incompleteCommandTrailingPipe() {
        assertTrue(BashTool.isIncompleteCommand("echo hello |"));
    }

    @Test
    void incompleteCommandTrailingAnd() {
        assertTrue(BashTool.isIncompleteCommand("echo hello &&"));
    }

    @Test
    void incompleteCommandTrailingOr() {
        assertTrue(BashTool.isIncompleteCommand("echo hello ||"));
    }

    @Test
    void terminalSemicolonIsAValidCommandListTerminator() {
        assertFalse(BashTool.isIncompleteCommand("find . -exec printf '%s' {} \\;"));
    }

    @Test
    void completeCommandIsNotIncomplete() {
        assertFalse(BashTool.isIncompleteCommand("echo hello"));
    }

    @Test
    void nullIsNotIncomplete() {
        assertFalse(BashTool.isIncompleteCommand(null));
    }

    // ── M-3: background spawn failure must not leave a zombie task ─────────

    @Test
    void backgroundSpawnFailure_evictsTask_noZombieLeftBehind() {
// A working directory that doesn't exist makes ProcessBuilder.start
        // throw IOException after the task was already created in the store.
        var registry = new TaskRegistry(TaskStore.inMemory());
        TaskRegistry.setGlobalForTest(registry);
        try {
            ObjectNode input = mapper.createObjectNode();
            input.put("command", "echo hi");
            input.put("run_in_background", true);
            ToolExecutionContext badDirCtx = ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory("/nonexistent/dir/that/does/not/exist").build();

            String result = (String) tool.call(input, badDirCtx);

            assertTrue(Strings.CS.startsWith(result, "Error:"), result);
            assertTrue(registry.store().list().isEmpty(),
                "failed spawn must evict the task — no phantom (running) row in /tasks");
            assertTrue(registry.listBackground().isEmpty());
        } finally {
            TaskRegistry.resetGlobalForTest();
        }
    }

    // ── background bash stamps the owning agent id (agentId routing) ──────

    @Test
    void backgroundCommand_tagsAgentId_forSubAgentContext() {
// (agentId routing): a sub-agent's background bash must stamp the task with the
// sub-agent id so its completion notification routes back to that agent, not the
// coordinator.
        var registry = new TaskRegistry(TaskStore.inMemory());
        TaskRegistry.setGlobalForTest(registry);
        try {
            ObjectNode input = mapper.createObjectNode();
            input.put("command", "echo done");
            input.put("run_in_background", true);
            ToolExecutionContext subCtx = ToolExecutionContext
                .builder(new AbortController(), "test-session")
                .fileStateCache(new FileStateCache())
                .agentId("sub-agent-9")
                .nestedMemoryAttachmentTriggers(ConcurrentHashMap.newKeySet())
                .loadedNestedMemoryPaths(ConcurrentHashMap.newKeySet())
                .build();

            tool.call(input, subCtx);

            var tasks = registry.store().list();
            assertEquals(1, tasks.size(), "exactly one background task created");
            assertEquals("sub-agent-9", tasks.getFirst().agentId().orElse(null),
                "background task must carry the sub-agent id");
        } finally {
            TaskRegistry.resetGlobalForTest();
        }
    }

    @Test
    void backgroundCommand_tagsNullAgentId_forMainThreadContext() {
        // Regression guard: a main-thread (agentId == null) background bash keeps
        // the task's agentId null, so its notification reaches the main session.
        var registry = new TaskRegistry(TaskStore.inMemory());
        TaskRegistry.setGlobalForTest(registry);
        try {
            ObjectNode input = mapper.createObjectNode();
            input.put("command", "echo done");
            input.put("run_in_background", true);
            ToolExecutionContext mainCtx = ToolExecutionContext.of(new AbortController(), "test-session");

            tool.call(input, mainCtx);

            var tasks = registry.store().list();
            assertEquals(1, tasks.size(), "exactly one background task created");
            assertNull(tasks.getFirst().agentId().orElse(null),
                "main-thread background task keeps agentId null");
        } finally {
            TaskRegistry.resetGlobalForTest();
        }
    }

    // ── sandbox: resolveCommandLine pure method ──────────────────────────────

    @Test
    void resolveCommandLine_unsandboxed_returnsPlainBash() {
        BashTool t = new BashTool(System::getenv, new NoopSandboxBackend());
        List<String> argv = t.resolveCommandLine("echo hi", Path.of("/work"),
            SandboxDecision.unsandboxed(), SandboxConfig.disabled());
        assertEquals(List.of(ExecutableFinder.bashExecutable(),
            "-c", "echo hi"), argv);
    }

    @Test
    void resolveCommandLine_sandboxed_usesManagerWrap() {
        BashTool t = new BashTool(System::getenv, new FakeSandboxBackend(true));
        List<String> argv = t.resolveCommandLine("echo hi", Path.of("/work"),
            SandboxDecision.sandbox(), SandboxConfig.disabled());
        assertEquals(List.of("fake-sandbox", "echo hi"), argv);
    }

    @Test
    void call_withSandboxEnabledButBackendUnavailable_rejectsWhenFailIfUnavailable() {
        // enabled + failIfUnavailable(explicit true) + Noop backend (unavailable)
        // must surface the reject error instead of silently running unsandboxed.

        ObjectNode sandbox = mapper.createObjectNode();
        sandbox.put("enabled", true);
        sandbox.put("failIfUnavailable", true);
        SandboxConfig cfg = SandboxConfig.fromJson(sandbox);
        BashTool t = new BashTool(System::getenv, new NoopSandboxBackend());
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "echo hi");
        // Build a context carrying the enabled sandbox config (of() defaults to disabled).
        ToolExecutionContext sandboxedCtx = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .fileStateCache(new FileStateCache())
            .nestedMemoryAttachmentTriggers(ConcurrentHashMap.newKeySet())
            .loadedNestedMemoryPaths(ConcurrentHashMap.newKeySet())
            .sandboxConfig(cfg)
            .build();
        String result = (String) t.call(input, sandboxedCtx);
        assertTrue(Strings.CS.startsWith(result, "Error:"), "expected reject error, got: " + result);
        assertTrue(Strings.CS.contains(result, "not supported"), "expected platform-unsupported reason");
    }

    // ── F1: shell-aware tokenization (quoted operators/pipes must not split) ──

    @Test
    void searchOrRead_quotedPipeNotSplit() {
        // The `&& rm -rf /` lives inside double quotes, so it must NOT be treated
        // as a separate destructive segment. F1 regression: a naive split would
        // have flagged this as non-read and denied auto-allow.
        assertTrue(BashTool.isSearchOrReadCommand("echo \"x && rm -rf /\""));
    }

    @Test
    void searchOrRead_quotedRedirectTargetNotCommand() {
        // `> out.txt` is a redirect (stripped); the command is still read-only.
        assertTrue(BashTool.isSearchOrReadCommand("echo hi > out.txt"));
    }

    @Test
    void concurrencySafe_rejectsOutputRedirect() {
        // A command that writes to disk is NOT concurrency-safe (isReadOnly).
        assertFalse(tool.isConcurrencySafe(mapper.createObjectNode().put("command", "cat a.txt > b.txt")));
    }

    @Test
    void concurrencySafe_allowsReadOnly() {
        assertTrue(tool.isConcurrencySafe(mapper.createObjectNode().put("command", "ls -la")));
        assertTrue(tool.isConcurrencySafe(mapper.createObjectNode().put("command", "cat a.txt | grep x")));
    }

    @Test
    void concurrencySafe_rejectsCd() {
        // `cd` changes cwd → not read-only → not concurrency-safe.
        assertFalse(tool.isConcurrencySafe(mapper.createObjectNode().put("command", "cd /tmp && ls")));
    }

    // ── F2: leading sleep guard ─────────────────────────────────────────────

    @Test
    void sleepGuard_blocksLeadingSleep() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "sleep 5");
        String result = MonitorFeatureGate.withSystemEnabled(true,
            () -> (String) tool.call(input, ctx()));
        assertTrue(Strings.CS.startsWith(result, "Blocked:"), "expected sleep block, got: " + result);
        assertTrue(Strings.CS.contains(result, "run_in_background"), result);
    }

    @Test
    void sleepGuard_blocksStartSleepVariant() {

        // `^sleep\s+(\d+)\s*$`); `start-sleep` is a separate command.
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "sleep 10");
        String result = MonitorFeatureGate.withSystemEnabled(true,
            () -> (String) tool.call(input, ctx()));
        assertTrue(Strings.CS.startsWith(result, "Blocked:"), "expected sleep block, got: " + result);
    }

    @Test
    void sleepGuard_under2sNotBlocked() {
        // Sub-2s sleeps are legitimate pacing; the guard must NOT block them.
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "sleep 1 && echo done");
        String result = (String) tool.call(input, ctx());
        assertFalse(Strings.CS.startsWith(result, "Blocked:"), "sub-2s sleep must not be blocked");
        assertTrue(Strings.CS.contains(result, "done"), "command should have run: " + result);
    }

    @Test
    void sleepGuard_skippedWhenBackgrounded() {
        // When the model asked to background it, the leading sleep is allowed.
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "sleep 30");
        input.put("run_in_background", true);
        String result = (String) tool.call(input, ctx());
        assertFalse(Strings.CS.startsWith(result, "Blocked:"), "backgrounded sleep must not be blocked");
    }

    @Test
    void imageDataUriReturnsImageBlock() {

        // data URI must yield a real image content block (so the model sees the
        // image), NOT a "[Image output detected]" text note.
        String pngDataUri = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
        ObjectNode input = mapper.createObjectNode();
        // Single-quote the URI so the ';' isn't parsed as a shell separator.
        input.put("command", "echo '" + pngDataUri + "'");

        Object result = tool.call(input, ctx());
        assertInstanceOf(ToolResult.class, result);
        ToolResult tr = (ToolResult) result;
        assertEquals(1, tr.content().size(), "image tool_result should carry exactly one block");
        assertInstanceOf(ImageBlock.class, tr.content().getFirst());
        ImageBlock ib = (ImageBlock) tr.content().getFirst();
        assertTrue(ib.source().has("media_type"), "image block must carry a media_type");
        assertTrue(Strings.CS.startsWith(ib.source().get("media_type").asText(), "image/"),
            "media_type should be an image MIME type");
    }

    private static final class RecordingCwdController implements WorkingDirectoryController {
        private final Path original;
        private final List<Path> allowed;
        private final List<Path> updates = new ArrayList<>();
        private Path current;

        private RecordingCwdController(Path original, List<Path> allowed) throws Exception {
            this.original = original.toRealPath();
            this.allowed = allowed.stream().map(path -> {
                try { return path.toRealPath(); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }).toList();
            this.current = this.original;
        }

        @Override public boolean mutable() { return true; }
        @Override public Path originalDirectory() { return original; }
        @Override public List<Path> allowedDirectories() { return allowed; }
        @Override public void update(Path previous, Path next) {
            current = next;
            updates.add(next);
        }
    }

}
