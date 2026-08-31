package com.claudecode.tools.worktree;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.ValidationResult;

class EnterExitWorktreeToolTest {

    private final EnterWorktreeTool enterTool = new EnterWorktreeTool();
    private final ExitWorktreeTool exitTool = new ExitWorktreeTool();
    private final ObjectMapper mapper = new ObjectMapper();

    private String savedUserDir;

    @BeforeEach
    void saveUserDir() {
        savedUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreState() {
        WorktreeService.clearCurrentSessionForTests();
        if (savedUserDir != null) System.setProperty("user.dir", savedUserDir);
    }

    private ToolExecutionContext ctx(Path cwd, String sessionId) {
        return ToolExecutionContext.builder(new AbortController(), sessionId).workingDirectory(cwd.toString()).build();
    }

    private ObjectNode enterInput(String name) {
        ObjectNode node = mapper.createObjectNode();
        if (name != null) node.put("name", name);
        return node;
    }

    private ObjectNode enterPathInput(Path path) {
        ObjectNode node = mapper.createObjectNode();
        node.put("path", path.toString());
        return node;
    }

    private ObjectNode exitInput(String action, Boolean discardChanges) {
        ObjectNode node = mapper.createObjectNode();
        node.put("action", action);
        if (discardChanges != null) node.put("discard_changes", discardChanges);
        return node;
    }

    @Test
    void enter_nonGitDirectory_returnsErrorWithoutMutatingState(@TempDir Path dir) {
        assumeTrue(gitAvailable(), "git executable not available");
        var invocation = enterTool.callWithResult(enterInput("feature"), ctx(dir, "sess-1"));
        String result = invocation.rawResult();
        ToolResult mapped = invocation.mappedResult();
        assertTrue(Strings.CS.startsWith(result, "Error:"), result);
        assertTrue(mapped.isError());
        assertEquals(result, mapped.toolUseResult());
        assertFalse(Strings.CS.startsWith(
            ((TextBlock) mapped.content().getFirst()).text(), "Error:"));
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void enter_realRepo_switchesCwdAndPersistsState(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);

        var invocation = enterTool.callWithResult(enterInput("feature-a"), ctx(repo, "sess-1"));
        String result = invocation.rawResult();
        ToolResult mapped = invocation.mappedResult();

        assertTrue(Strings.CS.contains(result, "Created worktree"), result);
        JsonNode uiPayload = mapper.valueToTree(mapped.toolUseResult());
        assertEquals("feature-a", Path.of(uiPayload.path("worktreePath").asText())
            .getFileName().toString());
        assertFalse(StringUtils.isBlank(uiPayload.path("message").asText()));
        assertNotNull(WorktreeService.getCurrentWorktreeSession());
        String newCwd = System.getProperty("user.dir");
        // macOS: @TempDir is /var/folders/... but chdir canonicalizes to /private/var/...,
        // so compare against the symlink-resolved (real) repo path.
        assertEquals(repo.toRealPath().resolve(".claude/worktrees/feature-a").toString(), newCwd);
        assertTrue(Files.isDirectory(Path.of(newCwd)));

        // The worktree-state entry must be persisted into the ORIGINAL project's
        // session file, keyed by the pre-switch cwd — not a path derived from the
        // new worktree cwd (that would silently orphan the transcript).
        SessionStorage storage = new SessionStorage(JsonUtils.getMapper());
        Path sessionFile = new SessionManager(repo.toRealPath().toString()).getSessionFile("sess-1");
        assertTrue(Files.exists(sessionFile), "worktree-state entry must land in the original session's JSONL");
        SessionStorage.WorktreeStateEntry entry = storage.scanWorktreeState(sessionFile);
        assertNotNull(entry);
        assertNotNull(entry.worktreeSessionJson());
        assertEquals(newCwd, entry.worktreeSessionJson().get("worktreePath").asText());
    }

    @Test
    void enter_twice_secondCallRefuses(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);

        enterTool.call(enterInput("feature-a"), ctx(repo, "sess-1"));
        String second = enterTool.call(enterInput("feature-b"), ctx(repo, "sess-1"));

        assertTrue(Strings.CS.contains(second, "Already in a worktree session"), second);
    }

    @Test
    void enter_schemaMatches197ExistingWorktreeContract() {
        JsonNode schema = enterTool.inputSchema();
        assertFalse(schema.path("required").isArray(), "both name and path are optional");
        assertTrue(schema.path("properties").has("name"));
        assertTrue(schema.path("properties").has("path"));
        assertTrue(Strings.CS.contains(schema.path("properties").path("name").path("description")
            .asText(), "Mutually exclusive with `path`"));
        assertTrue(Strings.CS.contains(schema.path("properties").path("path").path("description")
            .asText(), "Must appear in `git worktree list`"));
    }

    @Test
    void enter_nameAndPathTogetherAreRejectedBeforeMutation(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        ObjectNode input = enterPathInput(repo);
        input.put("name", "feature-a");

        ValidationResult validation = enterTool.validateInput(input, ctx(repo, "sess-1"));

        assertInstanceOf(ValidationResult.Invalid.class, validation);
        assertTrue(Strings.CS.contains(((ValidationResult.Invalid) validation).message(), "mutually exclusive"));
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void enter_existingRegisteredWorktreeSwitchesWithoutTakingOwnership(@TempDir Path repo)
        throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        Path existing = repo.resolve(".claude/worktrees/existing");
        Files.createDirectories(existing.getParent());
        run(repo, "git", "worktree", "add", "-q", "-b", "existing", existing.toString());

        String result = enterTool.call(enterPathInput(existing), ctx(repo, "sess-1"));

        assertTrue(Strings.CS.contains(result, "Entered existing worktree"), result);
        assertEquals(existing.toRealPath().toString(), System.getProperty("user.dir"));
        assertNotNull(WorktreeService.getCurrentWorktreeSession());
        assertTrue(WorktreeService.getCurrentWorktreeSession().enteredExisting());

        String exit = exitTool.call(exitInput("keep", null), ctx(existing, "sess-1"));
        assertTrue(Strings.CS.contains(exit, "Exited worktree"), exit);
        assertEquals(repo.toRealPath().toString(), System.getProperty("user.dir"));
        assertTrue(Files.isDirectory(existing), "an entered existing worktree remains on disk");
    }

    @Test
    void enter_existingPathMustBeRegisteredForSameRepository(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        Path ordinaryDirectory = repo.resolve("ordinary-directory");
        Files.createDirectories(ordinaryDirectory);

        String result = enterTool.call(enterPathInput(ordinaryDirectory), ctx(repo, "sess-1"));

        assertTrue(Strings.CS.startsWith(result, "Error:"), result);
        assertTrue(Strings.CS.contains(result, "registered worktree"), result);
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void enter_existingPathCanReplaceActiveWorktreeTracking(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        Path first = repo.resolve(".claude/worktrees/first");
        Path second = repo.resolve(".claude/worktrees/second");
        Files.createDirectories(first.getParent());
        run(repo, "git", "worktree", "add", "-q", "-b", "first", first.toString());
        run(repo, "git", "worktree", "add", "-q", "-b", "second", second.toString());
        enterTool.call(enterPathInput(first), ctx(repo, "sess-1"));

        String result = enterTool.call(enterPathInput(second), ctx(first, "sess-1"));

        assertTrue(Strings.CS.contains(result, "Entered existing worktree"), result);
        assertEquals(second.toRealPath().toString(), System.getProperty("user.dir"));
        assertEquals(repo.toRealPath().toString(),
            WorktreeService.getCurrentWorktreeSession().originalCwd());
        assertTrue(Files.isDirectory(first), "the previously visited worktree is untouched");
    }

    @Test
    void exit_removeRefusesForWorktreeEnteredByPath(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        Path existing = repo.resolve(".claude/worktrees/existing");
        Files.createDirectories(existing.getParent());
        run(repo, "git", "worktree", "add", "-q", "-b", "existing", existing.toString());
        enterTool.call(enterPathInput(existing), ctx(repo, "sess-1"));

        ValidationResult validation =
            exitTool.validateInput(exitInput("remove", true), ctx(existing, "sess-1"));

        assertInstanceOf(ValidationResult.Invalid.class, validation);
        assertTrue(Strings.CS.contains(((ValidationResult.Invalid) validation).message(), "entered with `path`"));
        assertTrue(Files.isDirectory(existing));
        assertNotNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void exit_withNoActiveSession_isNoOp(@TempDir Path dir) {
        // The no-op refusal now lives in validateInput (so the model sees
// is_error: true) — call itself is never reached by a real caller
        // (ToolRegistry) in this case, only its defensive re-check fallback.
        ValidationResult validation = exitTool.validateInput(exitInput("keep", null), ctx(dir, "sess-1"));
        assertInstanceOf(ValidationResult.Invalid.class, validation);
        assertTrue(Strings.CS.startsWith(((ValidationResult.Invalid) validation).message(), "No-op:"));

        String result = exitTool.call(exitInput("keep", null), ctx(dir, "sess-1"));
        assertTrue(Strings.CS.startsWith(result, "Error:"), result);
    }

    @Test
    void exit_keep_restoresCwdAndPreservesWorktree(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        enterTool.call(enterInput("feature-a"), ctx(repo, "sess-1"));
        String worktreePath = System.getProperty("user.dir");

        var invocation = exitTool.callWithResult(
            exitInput("keep", null), ctx(Path.of(worktreePath), "sess-1"));
        String result = invocation.rawResult();
        ToolResult mapped = invocation.mappedResult();

        assertTrue(Strings.CS.contains(result, "Exited worktree"), result);
        JsonNode uiPayload = mapper.valueToTree(mapped.toolUseResult());
        assertEquals("keep", uiPayload.path("action").asText());
        assertEquals(repo.toRealPath().toString(), uiPayload.path("originalCwd").asText());
        assertTrue(Strings.CS.contains(result, worktreePath), result);
        assertEquals(repo.toRealPath().toString(), System.getProperty("user.dir"));
        assertTrue(Files.isDirectory(Path.of(worktreePath)), "keep must leave the worktree on disk");
        assertNull(WorktreeService.getCurrentWorktreeSession());

        SessionStorage storage = new SessionStorage(JsonUtils.getMapper());
        Path sessionFile = new SessionManager(repo.toRealPath().toString()).getSessionFile("sess-1");
        SessionStorage.WorktreeStateEntry entry = storage.scanWorktreeState(sessionFile);
        assertNotNull(entry);
        assertNull(entry.worktreeSessionJson(), "exit must record worktreeSession:null");
    }

    @Test
    void exit_removeWithUncommittedChanges_refusesWithoutDiscard(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        enterTool.call(enterInput("feature-a"), ctx(repo, "sess-1"));
        String worktreePath = System.getProperty("user.dir");
        Files.writeString(Path.of(worktreePath, "dirty.txt"), "uncommitted\n");



        ValidationResult validation =
            exitTool.validateInput(exitInput("remove", null), ctx(Path.of(worktreePath), "sess-1"));
        assertInstanceOf(ValidationResult.Invalid.class, validation);
        String message = ((ValidationResult.Invalid) validation).message();
        assertTrue(Strings.CS.contains(message, "uncommitted"), message);
        assertTrue(Files.isDirectory(Path.of(worktreePath)), "refused removal must leave the worktree intact");
        assertNotNull(WorktreeService.getCurrentWorktreeSession(), "refused removal must not clear the session");
    }

    @Test
    void exit_removeWithUncommittedChanges_throughRegistry_isErrorTrue(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        enterTool.call(enterInput("feature-a"), ctx(repo, "sess-1"));
        String worktreePath = System.getProperty("user.dir");
        Files.writeString(Path.of(worktreePath, "dirty.txt"), "uncommitted\n");

        ToolRegistry registry = new ToolRegistry();
        registry.register(exitTool);
        ToolResult result = registry.execute(
            "ExitWorktree", exitInput("remove", null),
            ctx(Path.of(worktreePath), "sess-1"));

        assertTrue(result.isError(), "a refused destructive worktree removal must surface is_error: true to the model");
        assertTrue(Files.isDirectory(Path.of(worktreePath)), "refused removal must leave the worktree intact");
        assertNotNull(WorktreeService.getCurrentWorktreeSession(), "refused removal must not clear the session");
    }

    @Test
    void exit_removeWithDiscardChanges_deletesWorktreeAndBranch(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        enterTool.call(enterInput("feature-a"), ctx(repo, "sess-1"));
        String worktreePath = System.getProperty("user.dir");
        Files.writeString(Path.of(worktreePath, "dirty.txt"), "uncommitted\n");

        String result = exitTool.call(exitInput("remove", true), ctx(Path.of(worktreePath), "sess-1"));

        assertTrue(Strings.CS.contains(result, "Exited and removed worktree"), result);
        assertTrue(Strings.CS.contains(result, "Discarded"), result);
        assertEquals(repo.toRealPath().toString(), System.getProperty("user.dir"));
        assertFalse(Files.isDirectory(Path.of(worktreePath)), "remove must delete the worktree directory");
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void exit_removeCleanWorktree_succeedsWithoutDiscardFlag(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        enterTool.call(enterInput("feature-a"), ctx(repo, "sess-1"));
        String worktreePath = System.getProperty("user.dir");

        String result = exitTool.call(exitInput("remove", null), ctx(Path.of(worktreePath), "sess-1"));

        assertTrue(Strings.CS.contains(result, "Exited and removed worktree"), result);
        assertFalse(Files.isDirectory(Path.of(worktreePath)));
    }

    @Test
    void exit_keepReportsTmuxSessionForReattach(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        enterTool.call(enterInput("feature-a"), ctx(repo, "sess-1"));
        var current = WorktreeService.getCurrentWorktreeSession();
        WorktreeService.restoreWorktreeSession(new WorktreeSession(
            current.originalCwd(), current.worktreePath(), current.worktreeName(),
            current.worktreeBranch(), current.originalBranch(), current.originalHeadCommit(),
            current.sessionId(), "cc-feature-a", current.hookBased(),
            current.creationDurationMs(), current.usedSparsePaths(), current.projectRootMoved(),
            current.enteredExisting()));

        String result = exitTool.call(exitInput("keep", null),
            ctx(Path.of(current.worktreePath()), "sess-1"));

        assertTrue(Strings.CS.contains(result, "cc-feature-a"), result);
        assertTrue(Strings.CS.contains(result, "reattach"), result);
    }

    @Test
    void exit_removeKillsAttachedTmuxSession(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        enterTool.call(enterInput("feature-a"), ctx(repo, "sess-1"));
        var current = WorktreeService.getCurrentWorktreeSession();
        WorktreeService.restoreWorktreeSession(new WorktreeSession(
            current.originalCwd(), current.worktreePath(), current.worktreeName(),
            current.worktreeBranch(), current.originalBranch(), current.originalHeadCommit(),
            current.sessionId(), "cc-feature-a", current.hookBased(),
            current.creationDurationMs(), current.usedSparsePaths(), current.projectRootMoved(),
            current.enteredExisting()));
        AtomicReference<String> killed = new AtomicReference<>();
        WorktreeService.setTmuxSessionKillerForTests(killed::set);
        try {
            exitTool.call(exitInput("remove", true),
                ctx(Path.of(current.worktreePath()), "sess-1"));
        } finally {
            WorktreeService.resetTmuxSessionKillerForTests();
        }

        assertEquals("cc-feature-a", killed.get());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void initRepoWithOneCommit(Path dir) throws IOException, InterruptedException {
        run(dir, "git", "init", "-q");
        run(dir, "git", "config", "user.email", "test@example.com");
        run(dir, "git", "config", "user.name", "Test");
        Files.writeString(dir.resolve("a.txt"), "a\n");
        run(dir, "git", "add", ".");
        run(dir, "git", "commit", "-q", "-m", "init");
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception _) {
            return false;
        }
    }

    private static void run(Path dir, String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed (" + code + "): " + out);
        }
    }
}
