package com.claudecode.tools.worktree;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.SessionStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeServiceTest {

    private String savedUserDir;

    @BeforeEach
    void saveUserDir() {
        savedUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void clear() {
        WorktreeService.clearCurrentSessionForTests();
        WorktreeService.setWorktreeHooks(null); // reset the hook seam between tests
        WorktreeService.setSymlinkDirectoriesSupplier(null); // reset the symlink-dirs seam
        WorktreeService.setBaseRefSupplier(null); // reset worktree.baseRef between tests
        WorktreeService.setSparsePathsSupplier(null); // reset sparse-checkout seam
        WorktreeService.setMemoryFileCacheClearer(null);
        WorktreeService.setPlansDirectoryCacheClearer(null);
        WorktreeService.resetTmuxSessionKillerForTests();
        // getOrCreateWorktree/keepWorktree/cleanupWorktree mutate the JVM-wide
        // user.dir property — restore it so later tests (in this class or run in
        // the same fork) don't silently inherit a worktree cwd.
        if (savedUserDir != null) System.setProperty("user.dir", savedUserDir);
    }

    @Test
    void currentSession_startsNull() {
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void resetLatches_clearsCwdSensitiveCaches_andFailsOpen() {
        AtomicInteger memoryClears = new AtomicInteger();
        AtomicInteger plansClears = new AtomicInteger();
        WorktreeService.setMemoryFileCacheClearer(memoryClears::incrementAndGet);
        WorktreeService.setPlansDirectoryCacheClearer(plansClears::incrementAndGet);

        assertDoesNotThrow(WorktreeService::resetLatches);
        assertEquals(1, memoryClears.get());
        assertEquals(1, plansClears.get());

        WorktreeService.setMemoryFileCacheClearer(() -> { throw new IllegalStateException("test"); });
        WorktreeService.setPlansDirectoryCacheClearer(() -> { throw new IllegalStateException("test"); });
        assertDoesNotThrow(WorktreeService::resetLatches,
            "cache invalidation must not make a worktree transition fail");
    }

    @Test
    void restoreSession_thenGetReturnsIt() {
        WorktreeSession s = sampleSession();
        WorktreeService.restoreWorktreeSession(s);
        assertSame(s, WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void keepWorktree_clearsCurrentSession() {
        WorktreeService.restoreWorktreeSession(sampleSession());
        String msg = WorktreeService.keepWorktree();
        assertTrue(Strings.CS.contains(msg, "Worktree kept"));
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void cleanupWorktree_clearsCurrentSession() {
        WorktreeService.restoreWorktreeSession(sampleSession());
        String msg = WorktreeService.cleanupWorktree();
        assertTrue(Strings.CS.startsWith(msg, "Worktree removed"));
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void keepWorktree_withNoSession_returnsNotice() {
        assertEquals("No active worktree session found", WorktreeService.keepWorktree());
    }

    @Test
    void cleanupWorktree_withNoSession_returnsNotice() {
        assertEquals("No active worktree session found", WorktreeService.cleanupWorktree());
    }

    @Test
    void gitStatusPorcelain_nonGitDir_returnsEmpty() {
        // /tmp is unlikely to be a git repo. Runner tolerates git failure.
        assertNotNull(WorktreeService.gitStatusPorcelain("/tmp"));
    }

    @Test
    void commitCountAhead_blankBase_returnsZero() {
        assertEquals(0, WorktreeService.commitCountAhead("/tmp", ""));
        assertEquals(0, WorktreeService.commitCountAhead("/tmp", null));
    }

    @Test
    void session_hasTmuxSession_reflectsField() {
        assertFalse(sampleSession().hasTmuxSession());
        WorktreeSession withTmux = new WorktreeSession(
            "/tmp/a", "/tmp/wt", "wt", "branch", "main", "abc",
            "sid", "my-tmux", false, 0, false);
        assertTrue(withTmux.hasTmuxSession());
    }

    private static WorktreeSession sampleSession() {
        return new WorktreeSession(
            "/tmp/original", "/tmp/worktree", "worktree-slug",
            "wt-branch", "main", "HEAD_SHA", "session-id",
            null, false, 0L, false);
    }

    // ── validateWorktreeSlug ─────────────────────────────────────────────────

    @Test
    void validateWorktreeSlug_acceptsSimpleName() {
        assertDoesNotThrow(() -> WorktreeService.validateWorktreeSlug("feature-1"));
    }

    @Test
    void validateWorktreeSlug_rejectsDotDotSegment() {
        assertThrows(IllegalArgumentException.class,
            () -> WorktreeService.validateWorktreeSlug("../escape"));
    }

    @Test
    void validateWorktreeSlug_rejectsTooLong() {
        assertThrows(IllegalArgumentException.class,
            () -> WorktreeService.validateWorktreeSlug("a".repeat(65)));
    }

    @Test
    void validateWorktreeSlug_rejectsInvalidCharacters() {
        assertThrows(IllegalArgumentException.class,
            () -> WorktreeService.validateWorktreeSlug("bad slug!"));
    }

    @Test
    void worktreeBranchName_prefixesAndFlattensNesting() {
        assertEquals("worktree-feature-1", WorktreeService.worktreeBranchName("feature-1"));
        assertEquals("worktree-user+feature", WorktreeService.worktreeBranchName("user/feature"));
    }

    // ── getOrCreateWorktree / keepWorktree / cleanupWorktree against a real repo ──

    @Test
    void getOrCreateWorktree_createsNewWorktreeOffHead(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        String headSha = runCapture(repo, "git", "rev-parse", "HEAD").trim();

        WorktreeService.WorktreeCreateResult result =
            WorktreeService.getOrCreateWorktree(repo.toString(), "feature-x");

        assertFalse(result.existed());
        assertEquals("worktree-feature-x", result.worktreeBranch());
        assertEquals(headSha, result.originalHeadCommit());
        assertEquals(repo.resolve(".claude/worktrees/feature-x").toString(), result.worktreePath());
        assertTrue(Files.isDirectory(Path.of(result.worktreePath())));
    }

    @Test
    void getOrCreateWorktree_resumesExistingWorktree(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);

        WorktreeService.WorktreeCreateResult first =
            WorktreeService.getOrCreateWorktree(repo.toString(), "feature-y");
        assertFalse(first.existed());

        WorktreeService.WorktreeCreateResult second =
            WorktreeService.getOrCreateWorktree(repo.toString(), "feature-y");
        assertTrue(second.existed());
        assertEquals(first.worktreePath(), second.worktreePath());
    }

    @Test
    void getOrCreateWorktree_copiesLocalSettingsAndIncludedIgnoredFiles(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        Files.createDirectories(repo.resolve(".claude"));
        Files.writeString(repo.resolve(".claude/settings.local.json"), "{\"local\":true}\n");
        Files.writeString(repo.resolve(".gitignore"), "secrets/\n");
        run(repo, "git", "add", ".gitignore");
        run(repo, "git", "commit", "-q", "-m", "ignore secrets");
        Files.createDirectories(repo.resolve("secrets"));
        Files.writeString(repo.resolve("secrets/api.key"), "secret\n");
        Files.writeString(repo.resolve(".worktreeinclude"), "secrets/api.key\n");

        WorktreeService.WorktreeCreateResult result =
            WorktreeService.getOrCreateWorktree(repo.toString(), "feature-local");

        Path worktree = Path.of(result.worktreePath());
        assertEquals("{\"local\":true}\n",
            Files.readString(worktree.resolve(".claude/settings.local.json")));
        assertEquals("secret\n", Files.readString(worktree.resolve("secrets/api.key")));
    }

    @Test
    void getOrCreateWorktree_expandsCollapsedDirectoryForAnchoredGlob(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        Files.writeString(repo.resolve(".gitignore"), "secrets/\n");
        run(repo, "git", "add", ".gitignore");
        run(repo, "git", "commit", "-q", "-m", "ignore secrets");
        Files.createDirectories(repo.resolve("secrets/nested"));
        Files.writeString(repo.resolve("secrets/nested/api.key"), "secret\n");
        Files.writeString(repo.resolve(".worktreeinclude"), "secrets/**/*.key\n");

        WorktreeService.WorktreeCreateResult result =
            WorktreeService.getOrCreateWorktree(repo.toString(), "feature-glob-include");

        assertEquals("secret\n", Files.readString(
            Path.of(result.worktreePath()).resolve("secrets/nested/api.key")));
    }

    @Test
    void getOrCreateWorktree_appliesConfiguredSparseCheckout(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/included.txt"), "included\n");
        Files.createDirectories(repo.resolve("other"));
        Files.writeString(repo.resolve("other/excluded.txt"), "other\n");
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-q", "-m", "sparse fixture");
        WorktreeService.setSparsePathsSupplier(_ -> List.of("src"));

        WorktreeService.WorktreeCreateResult result =
            WorktreeService.getOrCreateWorktree(repo.toString(), "feature-sparse");

        Path worktree = Path.of(result.worktreePath());
        assertTrue(Files.isRegularFile(worktree.resolve("src/included.txt")));
        assertFalse(Files.exists(worktree.resolve("other/excluded.txt")),
            "cone sparse-checkout must omit files outside configured paths");
    }

    @Test
    void getOrCreateWorktree_invalidSlugThrowsBeforeAnyGitCommand(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        assertThrows(IllegalArgumentException.class,
            () -> WorktreeService.getOrCreateWorktree(repo.toString(), "../escape"));
    }



    @Test
    void getOrCreateWorktree_fetchesOriginDefaultBranch_whenNotYetResolvableLocally(@TempDir Path root) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        Path remote = root.resolve("remote.git");
        Files.createDirectories(remote);
        run(remote, "git", "init", "-q", "--bare");

        // Seed the bare remote with one commit on "main" via a scratch clone —
        // never fetched into `repo` below, so `repo` starts with zero knowledge
        // of origin/main and must actually run `git fetch` to find it.
        Path seed = root.resolve("seed");
        Files.createDirectories(seed);
        initRepoWithOneCommit(seed);
        run(seed, "git", "branch", "-M", "main");
        run(seed, "git", "remote", "add", "origin", remote.toString());
        run(seed, "git", "push", "-q", "origin", "main");
        String remoteMainSha = runCapture(seed, "git", "rev-parse", "main").trim();

        // `repo` has its own unrelated history and only knows the remote's URL —
        // refs/remotes/origin/main does not exist here until fetched.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        run(repo, "git", "init", "-q");
        run(repo, "git", "config", "user.email", "test@example.com");
        run(repo, "git", "config", "user.name", "Test");
        Files.writeString(repo.resolve("unrelated.txt"), "unrelated content\n");
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-q", "-m", "unrelated init");
        run(repo, "git", "remote", "add", "origin", remote.toString());
        String localHeadSha = runCapture(repo, "git", "rev-parse", "HEAD").trim();
        assertNotEquals(remoteMainSha, localHeadSha, "fixture must diverge repo's HEAD from origin/main");

        WorktreeService.WorktreeCreateResult result =
            WorktreeService.getOrCreateWorktree(repo.toString(), "feature-fetch");

        assertEquals(remoteMainSha, result.originalHeadCommit(),
            "must fetch and base the worktree off origin/main, not repo's own local HEAD");
    }

    @Test
    void getOrCreateWorktree_skipsFetch_whenOriginRefAlreadyResolvesLocally(@TempDir Path root) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        Path remote = root.resolve("remote.git");
        Files.createDirectories(remote);
        run(remote, "git", "init", "-q", "--bare");

        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        initRepoWithOneCommit(repo);
        run(repo, "git", "branch", "-M", "main");
        run(repo, "git", "remote", "add", "origin", remote.toString());
        run(repo, "git", "push", "-q", "origin", "main");
        String originMainSha = runCapture(repo, "git", "rev-parse", "origin/main").trim();

        // Diverge local HEAD from origin/main with an unpushed commit — if the
        // fetch-skip path is broken and this fell back to HEAD, the assertion below
        // would catch it (origin/main's SHA would no longer match).
        Files.writeString(repo.resolve("b.txt"), "b\n");
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-q", "-m", "unpushed");

        WorktreeService.WorktreeCreateResult result =
            WorktreeService.getOrCreateWorktree(repo.toString(), "feature-origin");

        assertEquals(originMainSha, result.originalHeadCommit(),
            "new worktree must base off origin/main (already resolvable locally), not the unpushed local HEAD");
    }

    @Test
    void getOrCreateWorktree_headBaseRefUsesCurrentLocalHead(@TempDir Path root) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        Path remote = root.resolve("remote.git");
        Files.createDirectories(remote);
        run(remote, "git", "init", "-q", "--bare");

        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        initRepoWithOneCommit(repo);
        run(repo, "git", "branch", "-M", "main");
        run(repo, "git", "remote", "add", "origin", remote.toString());
        run(repo, "git", "push", "-q", "origin", "main");
        Files.writeString(repo.resolve("local-only.txt"), "local\n");
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-q", "-m", "local-only");
        String localHead = runCapture(repo, "git", "rev-parse", "HEAD").trim();
        String originHead = runCapture(repo, "git", "rev-parse", "origin/main").trim();
        assertNotEquals(originHead, localHead);
        WorktreeService.setBaseRefSupplier(_ -> "head");

        WorktreeService.WorktreeCreateResult result =
            WorktreeService.getOrCreateWorktree(repo.toString(), "feature-head");

        assertEquals(localHead, result.originalHeadCommit());
        assertEquals(localHead,
            runCapture(Path.of(result.worktreePath()), "git", "rev-parse", "HEAD").trim());
    }

    @Test
    void getOrCreateWorktree_fallsBackToHead_whenOriginFetchFails(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        run(repo, "git", "remote", "add", "origin", "/nonexistent/unreachable-remote.git");
        String headSha = runCapture(repo, "git", "rev-parse", "HEAD").trim();

        WorktreeService.WorktreeCreateResult result =
            WorktreeService.getOrCreateWorktree(repo.toString(), "feature-fallback");

        assertEquals(headSha, result.originalHeadCommit(),
            "an unreachable origin must fall back to HEAD instead of throwing");
    }

    @Test
    void keepWorktree_realGit_restoresCwdAndLeavesWorktreeOnDisk(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        WorktreeService.WorktreeCreateResult created =
            WorktreeService.getOrCreateWorktree(repo.toString(), "keep-me");

        WorktreeSession session = new WorktreeSession(
            repo.toString(), created.worktreePath(), "keep-me", created.worktreeBranch(),
            created.originalBranch(), created.originalHeadCommit(), "sid", null, false, 0L, false);
        WorktreeService.restoreWorktreeSession(session);

        String msg = WorktreeService.keepWorktree();

        assertTrue(Strings.CS.contains(msg, "Worktree kept"));
        assertEquals(repo.toString(), System.getProperty("user.dir"));
        assertTrue(Files.isDirectory(Path.of(created.worktreePath())), "keep must leave the worktree on disk");
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void cleanupWorktree_realGit_removesWorktreeAndBranch(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        WorktreeService.WorktreeCreateResult created =
            WorktreeService.getOrCreateWorktree(repo.toString(), "remove-me");

        WorktreeSession session = new WorktreeSession(
            repo.toString(), created.worktreePath(), "remove-me", created.worktreeBranch(),
            created.originalBranch(), created.originalHeadCommit(), "sid", null, false, 0L, false);
        WorktreeService.restoreWorktreeSession(session);

        String msg = WorktreeService.cleanupWorktree();

        assertTrue(Strings.CS.startsWith(msg, "Worktree removed"));
        assertEquals(repo.toString(), System.getProperty("user.dir"));
        assertFalse(Files.isDirectory(Path.of(created.worktreePath())), "cleanup must delete the worktree directory");
        String branches = runCapture(repo, "git", "branch", "--list", created.worktreeBranch());
        assertTrue(StringUtils.isBlank(branches), "cleanup must delete the worktree branch");
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    // ── agent worktree isolation (createAgentWorktree / hasWorktreeChanges / cleanup) ──

    @Test
    void createAgentWorktree_createsIsolatedWorktreeWithoutTouchingSessionState(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);

        WorktreeService.AgentWorktree wt = WorktreeService.createAgentWorktree("agent-abc123", repo.toString());

        assertTrue(Files.isDirectory(Path.of(wt.worktreePath())));
        assertEquals("worktree-agent-abc123", wt.worktreeBranch());
// macOS: gitRoot is canonical (/private/var/...), @TempDir repo is /var/...
        assertEquals(repo.toRealPath().toString(), wt.gitRoot());
        // Must NOT touch the session-level singleton or the process cwd.
        assertNull(WorktreeService.getCurrentWorktreeSession(),
            "agent worktree must not populate the session CURRENT singleton");
    }

    @Test
    void hasWorktreeChanges_falseForCleanWorktree_trueWhenDirty(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        WorktreeService.AgentWorktree wt = WorktreeService.createAgentWorktree("agent-x", repo.toString());

        assertFalse(WorktreeService.hasWorktreeChanges(wt.worktreePath(), wt.headCommit()),
            "fresh worktree off HEAD has no changes");

        Files.writeString(Path.of(wt.worktreePath(), "new.txt"), "dirty\n");
        assertTrue(WorktreeService.hasWorktreeChanges(wt.worktreePath(), wt.headCommit()),
            "uncommitted file must count as changed");
    }

    @Test
    void cleanupAgentWorktree_removesWhenClean_keepsWhenChanged(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);

        // Clean worktree → removed, empty kept-path.
        WorktreeService.AgentWorktree clean = WorktreeService.createAgentWorktree("agent-clean", repo.toString());
        assertTrue(WorktreeService.cleanupAgentWorktree(clean).isEmpty());
        assertFalse(Files.isDirectory(Path.of(clean.worktreePath())), "clean agent worktree must be removed");

        // Dirty worktree → kept, path surfaced.
        WorktreeService.AgentWorktree dirty = WorktreeService.createAgentWorktree("agent-dirty", repo.toString());
        Files.writeString(Path.of(dirty.worktreePath(), "keep.txt"), "work\n");
        assertEquals(dirty.worktreePath(), WorktreeService.cleanupAgentWorktree(dirty).orElse(null),
            "changed agent worktree must be kept and its path surfaced");
        assertTrue(Files.isDirectory(Path.of(dirty.worktreePath())));
    }

    @Test
    void cleanupStaleAgentWorktrees_removesOnlyOldCleanRemoteReachableTemporaryWorktrees(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        String head = runCapture(repo, "git", "rev-parse", "HEAD").trim();
        run(repo, "git", "update-ref", "refs/remotes/origin/main", head);

        WorktreeService.AgentWorktree stale =
            WorktreeService.createAgentWorktree("agent-a1234567", repo.toString());
        Files.setLastModifiedTime(Path.of(stale.worktreePath()),
            FileTime.from(Instant.now().minus(Duration.ofDays(31))));

        assertTrue(Files.exists(Path.of(stale.worktreePath())), stale.worktreePath());
        assertTrue(WorktreeService.findCanonicalGitRoot(repo.toString()) != null,
            "canonical root missing");
        assertTrue(Files.getLastModifiedTime(Path.of(stale.worktreePath())).toInstant()
            .isBefore(Instant.now().minus(Duration.ofDays(30))), "mtime was not made stale");
        assertEquals("agent-a1234567", Path.of(stale.worktreePath()).getFileName().toString());
        assertEquals("", runCapture(Path.of(stale.worktreePath()), "git", "status", "--porcelain", "-uno").trim());
        assertEquals("", runCapture(Path.of(stale.worktreePath()), "git", "rev-list", "--max-count=1", "HEAD", "--not", "--remotes").trim());

        int removed = WorktreeService.cleanupStaleAgentWorktrees(
            repo.toString(), Instant.now().minus(Duration.ofDays(30)));

        assertEquals(1, removed);
        assertFalse(Files.exists(Path.of(stale.worktreePath())));
    }

    @Test
    void cleanupStaleAgentWorktrees_keepsDirtyTemporaryWorktree(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        String head = runCapture(repo, "git", "rev-parse", "HEAD").trim();
        run(repo, "git", "update-ref", "refs/remotes/origin/main", head);

        WorktreeService.AgentWorktree stale =
            WorktreeService.createAgentWorktree("agent-a6543210", repo.toString());
        Files.writeString(Path.of(stale.worktreePath(), "a.txt"), "keep\n");
        Files.setLastModifiedTime(Path.of(stale.worktreePath()),
            FileTime.from(Instant.now().minus(Duration.ofDays(31))));

        assertEquals(0, WorktreeService.cleanupStaleAgentWorktrees(
            repo.toString(), Instant.now().minus(Duration.ofDays(30))));
        assertTrue(Files.exists(Path.of(stale.worktreePath())));
    }

    @Test
    void createAgentWorktree_nonGitDir_throws(@TempDir Path notARepo) {
        assumeTrue(gitAvailable(), "git executable not available");
        assertThrows(WorktreeException.class,
            () -> WorktreeService.createAgentWorktree("agent-x", notARepo.toString()));
    }

    // ── WorktreeCreate / WorktreeRemove hook fallback (non-git VCS) ────────────

    /** Fake hook seam: "creates" a worktree dir under {@code base}, records removals. */
    private static final class FakeHooks implements WorktreeHooks {
        final Path base;
        boolean hasHook = true;
        final List<String> removed = new ArrayList<>();
        FakeHooks(Path base) { this.base = base; }
        @Override public boolean hasCreateHook() { return hasHook; }
        @Override public Optional<String> create(String slug) {
            try {
                Path p = base.resolve("hookwt-" + slug);
                Files.createDirectories(p);
                return Optional.of(p.toString());
            } catch (IOException _) { return Optional.empty(); }
        }
        @Override public boolean remove(String worktreePath) { removed.add(worktreePath); return true; }
    }

    @Test
    void createSessionWorktree_prefersHookOverGit_whenHookConfigured(@TempDir Path repo, @TempDir Path hookBase) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo); // git IS available, but the hook must win
        FakeHooks hooks = new FakeHooks(hookBase);
        WorktreeService.setWorktreeHooks(hooks);

        WorktreeService.WorktreeCreateResult r = WorktreeService.createSessionWorktree("feat", repo.toString());

        assertTrue(r.hookBased(), "a configured WorktreeCreate hook must be preferred over git");
        assertTrue(Strings.CS.startsWith(r.worktreePath(), hookBase.toString()));
        assertNull(r.worktreeBranch(), "hook-based worktrees have no git branch");
    }

    @Test
    void createSessionWorktree_nonGitDir_usesHook(@TempDir Path notARepo, @TempDir Path hookBase) {
        WorktreeService.setWorktreeHooks(new FakeHooks(hookBase));
        // No git repo at all — only the hook can create the worktree.
        WorktreeService.WorktreeCreateResult r = WorktreeService.createSessionWorktree("feat", notARepo.toString());
        assertTrue(r.hookBased());
    }

    @Test
    void createSessionWorktree_noHookNoGit_throws(@TempDir Path notARepo) {
        assumeTrue(gitAvailable(), "git executable not available");
        assertThrows(WorktreeException.class,
            () -> WorktreeService.createSessionWorktree("feat", notARepo.toString()));
    }

    @Test
    void cleanupWorktree_hookBasedSession_delegatesToRemoveHook(@TempDir Path repo, @TempDir Path hookBase) {
        FakeHooks hooks = new FakeHooks(hookBase);
        WorktreeService.setWorktreeHooks(hooks);
        WorktreeSession session = new WorktreeSession(
            repo.toString(), hookBase.resolve("hookwt-x").toString(), "x", null,
            null, null, "sid", null, /* hookBased */ true, 0L, false);
        WorktreeService.restoreWorktreeSession(session);

        WorktreeService.cleanupWorktree();

        assertEquals(1, hooks.removed.size(), "hook-based session cleanup must call the WorktreeRemove hook");
        assertEquals(session.worktreePath(), hooks.removed.getFirst());
    }

    @Test
    void cleanupAgentWorktree_hookBased_alwaysKeeps(@TempDir Path hookBase) {
        FakeHooks hooks = new FakeHooks(hookBase);
        WorktreeService.setWorktreeHooks(hooks);
        WorktreeService.AgentWorktree wt = new WorktreeService.AgentWorktree(
            hookBase.resolve("hookwt-agent").toString(), null, null, null, /* hookBased */ true);

        Optional<String> kept = WorktreeService.cleanupAgentWorktree(wt);

        assertTrue(kept.isPresent(), "hook-based agent worktree is always kept (VCS-agnostic, no change detection)");
        assertTrue(hooks.removed.isEmpty(), "agent worktree cleanup must NOT call the remove hook");
    }

    @Test
    void createAgentWorktree_prefersHook_whenConfigured(@TempDir Path repo, @TempDir Path hookBase) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        WorktreeService.setWorktreeHooks(new FakeHooks(hookBase));

        WorktreeService.AgentWorktree wt = WorktreeService.createAgentWorktree("agent-h", repo.toString());

        assertTrue(wt.hookBased());
        assertTrue(Strings.CS.startsWith(wt.worktreePath(), hookBase.toString()));
    }

    // ── worktree.symlinkDirectories ──────────────────────────────────────────

    @Test
    void symlinkDirectories_symlinksConfiguredDirIntoNewWorktree(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        // A gitignored derived dir in the main repo (like node_modules).
        Files.createDirectory(repo.resolve("node_modules"));
        Files.writeString(repo.resolve("node_modules/marker.txt"), "installed\n");
        WorktreeService.setSymlinkDirectoriesSupplier(_ -> List.of("node_modules"));

        WorktreeService.WorktreeCreateResult r = WorktreeService.getOrCreateWorktree(repo.toString(), "feat-sym");

        Path link = Path.of(r.worktreePath(), "node_modules");
        assertTrue(Files.isSymbolicLink(link), "node_modules must be a symlink in the worktree");
        assertEquals(repo.resolve("node_modules"), Files.readSymbolicLink(link),
            "symlink must point at the main repo's directory");
        // Reading through the link reaches the main repo's content (shared, zero-copy).
        assertEquals("installed\n", Files.readString(link.resolve("marker.txt")));
    }

    @Test
    void symlinkDirectories_rejectsPathTraversalEntries(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        WorktreeService.setSymlinkDirectoriesSupplier(_ -> List.of("../escape", "/abs/evil"));

        WorktreeService.WorktreeCreateResult r = WorktreeService.getOrCreateWorktree(repo.toString(), "feat-safe");

        // Neither unsafe entry may create anything outside (or inside) the worktree.
        assertFalse(Files.exists(Path.of(r.worktreePath(), "..", "escape")));
        assertFalse(Files.exists(Path.of(r.worktreePath()).getParent().resolve("escape")));
    }

    @Test
    void symlinkDirectories_missingSourceOrExistingDest_skippedSilently(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        // "dist" source does NOT exist in the main repo → skipped, no crash.
        WorktreeService.setSymlinkDirectoriesSupplier(_ -> List.of("dist"));

        WorktreeService.WorktreeCreateResult r =
            assertDoesNotThrow(() -> WorktreeService.getOrCreateWorktree(repo.toString(), "feat-miss"));
        assertFalse(Files.exists(Path.of(r.worktreePath(), "dist")), "missing source must not create a dangling link");
    }

    @Test
    void symlinkDirectories_noSupplier_isNoOp(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");
        initRepoWithOneCommit(repo);
        // Default: no supplier wired → creation still succeeds, no symlinks.
        WorktreeService.WorktreeCreateResult r =
            assertDoesNotThrow(() -> WorktreeService.getOrCreateWorktree(repo.toString(), "feat-plain"));
        assertTrue(Files.isDirectory(Path.of(r.worktreePath())));
    }

    @Test
    void persistWorktreeState_roundtrip_throughSessionStorage(@TempDir Path tmp) {
        SessionStorage storage = new SessionStorage(JsonUtils.getMapper());
        Path sessionFile = tmp.resolve("session.jsonl");
        WorktreeSession session = new WorktreeSession(
            "/repo", "/repo/.claude/worktrees/feature-z", "feature-z",
            "worktree-feature-z", "main", "deadbeef", "sess-1", "tmux-1", false, 500L, false);

        WorktreeService.persistWorktreeState(storage, sessionFile, "sess-1", session);
        WorktreeSession restored = WorktreeService.readPersistedWorktreeState(storage, sessionFile);

        assertNotNull(restored);
        assertEquals(session.originalCwd(), restored.originalCwd());
        assertEquals(session.worktreePath(), restored.worktreePath());
        assertEquals(session.worktreeName(), restored.worktreeName());
        assertEquals(session.worktreeBranch(), restored.worktreeBranch());
        assertEquals(session.originalBranch(), restored.originalBranch());
        assertEquals(session.originalHeadCommit(), restored.originalHeadCommit());
        assertEquals(session.sessionId(), restored.sessionId());
        assertEquals(session.tmuxSessionName(), restored.tmuxSessionName());
// Ephemeral fields are intentionally stripped.
        assertEquals(0L, restored.creationDurationMs());
    }

    @Test
    void persistWorktreeState_nullSession_thenReadReturnsNull(@TempDir Path tmp) {
        SessionStorage storage = new SessionStorage(JsonUtils.getMapper());
        Path sessionFile = tmp.resolve("session.jsonl");
        WorktreeService.persistWorktreeState(storage, sessionFile, "sess-1", sampleSession());

        WorktreeService.persistWorktreeState(storage, sessionFile, "sess-1", null);

        assertNull(WorktreeService.readPersistedWorktreeState(storage, sessionFile));
    }

    @Test
    void readPersistedWorktreeState_noEntry_returnsNull(@TempDir Path tmp) {
        SessionStorage storage = new SessionStorage(JsonUtils.getMapper());
        Path sessionFile = tmp.resolve("session.jsonl");
        assertNull(WorktreeService.readPersistedWorktreeState(storage, sessionFile));
    }

    // ── test helpers ──────────────────────────────────────────────────────────

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

    private static String runCapture(Path dir, String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }
}
