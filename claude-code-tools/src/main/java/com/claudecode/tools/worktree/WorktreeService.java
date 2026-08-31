package com.claudecode.tools.worktree;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.ThinkingClearLatch;
import com.claudecode.core.prompt.SystemPromptSectionResolver;
import com.claudecode.session.SessionStorage;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.git.GitUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class WorktreeService {

    private static final Logger log = LoggerFactory.getLogger(WorktreeService.class);

    private static final Pattern VALID_SLUG_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final int MAX_SLUG_LENGTH = 64;

    /** Exact shapes emitted by temporary Agent/Workflow worktrees. */
    private static final List<Pattern> EPHEMERAL_WORKTREE_PATTERNS = List.of(
        Pattern.compile("^agent-a[0-9a-f]{7}$"),
        Pattern.compile("^wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+$"),
        Pattern.compile("^wf-\\d+$"),
        Pattern.compile("^bridge-[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*$"),
        Pattern.compile("^job-[a-zA-Z0-9._-]{1,55}-[0-9a-f]{8}$"));


    private static final AtomicReference<WorktreeSession> CURRENT = new AtomicReference<>();

    /**
     * Optional user-configured {@code WorktreeCreate}/{@code WorktreeRemove} hook seam.
     * Null when unwired (tests, or a build without hook wiring) → git-only behavior.
     * Injected by the composition root ({@code ClaudeCodeCli}). See {@link WorktreeHooks}.
     */
    private static volatile WorktreeHooks worktreeHooks;

    private WorktreeService() {}

    /** Wires the {@code WorktreeCreate}/{@code WorktreeRemove} hook seam (composition root only). */
    public static void setWorktreeHooks(WorktreeHooks hooks) {
        worktreeHooks = hooks;
    }

    /**
     * Optional supplier of {@code worktree.symlinkDirectories} — directories to symlink
     * from the main repo into each newly created worktree (e.g. {@code node_modules}).
     * Null/empty → no symlinks. Injected by the composition root ({@code ClaudeCodeCli})
     * from {@code WorkspaceSettings}; the tools module can't read services' settings directly.
     * Takes the repo root as input so the supplier can resolve per-project settings tiers.
     */
    private static volatile Function<String, List<String>> symlinkDirectoriesSupplier;
    private static volatile Function<String, String> baseRefSupplier;
    private static volatile Function<String, List<String>> sparsePathsSupplier;
    /** CWD-sensitive memory/plans cache hooks wired by the CLI composition root. */
    private static volatile Runnable memoryFileCacheClearer = () -> {};
    private static volatile Runnable plansDirectoryCacheClearer = () -> {};

    /** Process seam for tmux teardown; replaceable only by tests. */
    private static final Consumer<String> DEFAULT_TMUX_SESSION_KILLER =
        WorktreeService::killTmuxSessionProcess;
    private static volatile Consumer<String> tmuxSessionKiller = DEFAULT_TMUX_SESSION_KILLER;

    /** Wires the {@code worktree.symlinkDirectories} settings seam (composition root only). */
    public static void setSymlinkDirectoriesSupplier(Function<String, List<String>> supplier) {
        symlinkDirectoriesSupplier = supplier;
    }

    /** Wires {@code worktree.baseRef}: {@code fresh} (default) or {@code head}. */
    public static void setBaseRefSupplier(Function<String, String> supplier) {
        baseRefSupplier = supplier;
    }

    /** Wires {@code worktree.sparsePaths} from the composition root. */
    public static void setSparsePathsSupplier(Function<String, List<String>> supplier) {
        sparsePathsSupplier = supplier;
    }


    public static void setMemoryFileCacheClearer(Runnable clearer) {
        memoryFileCacheClearer = clearer == null ? () -> {} : clearer;
    }

    /** Wires the plans-directory memoization reset. */
    public static void setPlansDirectoryCacheClearer(Runnable clearer) {
        plansDirectoryCacheClearer = clearer == null ? () -> {} : clearer;
    }


    public static WorktreeSession getCurrentWorktreeSession() {
        return CURRENT.get();
    }


    public static void restoreWorktreeSession(WorktreeSession session) {
        CURRENT.set(session);
    }

    /** Replaces active tracking when EnterWorktree(path=...) switches worktrees. */
    public static void replaceCurrentWorktreeSession(WorktreeSession session) {
        CURRENT.set(session);
    }

    /**
     * Atomically claims the "in a worktree" slot — succeeds (returns {@code true})
     * only if no worktree session is currently active. Used by {@code
     * EnterWorktreeTool} as the actual double-entry guard instead of a separate
     * {@code get} check followed by a later {@code set}, which would race if
     * two tool calls ever ran concurrently.
     */
    public static boolean tryClaim(WorktreeSession session) {
        return CURRENT.compareAndSet(null, session);
    }

    /** Test-only reset of the module-level singleton — public so tests in sibling
     *  packages ({@code com.claudecode.tools}'s tool tests) can reach it too. */
    public static void clearCurrentSessionForTests() {
        CURRENT.set(null);
    }

    public static void setTmuxSessionKillerForTests(Consumer<String> killer) {
        tmuxSessionKiller = killer == null ? DEFAULT_TMUX_SESSION_KILLER : killer;
    }

    public static void resetTmuxSessionKillerForTests() {
        tmuxSessionKiller = DEFAULT_TMUX_SESSION_KILLER;
    }

    /**
     * Resets {@link ThinkingClearLatch} and clears {@link SystemPromptSectionResolver}'s cache.
     */
    public static void resetLatches() {
        ThinkingClearLatch.reset();
        SystemPromptSectionResolver.clearAll();
        try {
            memoryFileCacheClearer.run();
        } catch (RuntimeException _) {
            // Cache invalidation is best-effort and must not turn a successful
            // worktree transition into a tool failure.
        }
        try {
            plansDirectoryCacheClearer.run();
        } catch (RuntimeException _) {

        }
    }




    public static void validateWorktreeSlug(String slug) {
        if (slug.length() > MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException(
                "Invalid worktree name: must be " + MAX_SLUG_LENGTH + " characters or fewer (got " + slug.length() + ")");
        }
        for (String segment : slug.split("/", -1)) {
            if (Strings.CS.equals(segment, ".") || Strings.CS.equals(segment, "..")) {
                throw new IllegalArgumentException(
                    "Invalid worktree name \"" + slug + "\": must not contain \".\" or \"..\" path segments");
            }
            if (!VALID_SLUG_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(
                    "Invalid worktree name \"" + slug + "\": each \"/\"-separated segment must be non-empty "
                        + "and contain only letters, digits, dots, underscores, and dashes");
            }
        }
    }


    private static String flattenSlug(String slug) {
        return slug.replace("/", "+");
    }


    public static String worktreeBranchName(String slug) {
        return "worktree-" + flattenSlug(slug);
    }

    private static String worktreesDir(String repoRoot) {
        return Path.of(repoRoot, ".claude", "worktrees").toString();
    }


    public static String worktreePathFor(String repoRoot, String slug) {
        return Path.of(worktreesDir(repoRoot), flattenSlug(slug)).toString();
    }



    public record WorktreeCreateResult(
        String worktreePath,
        String worktreeBranch,
        String originalBranch,
        String originalHeadCommit,
        boolean existed,
        boolean hookBased
    ) {
        /** Git-based result convenience constructor (hookBased=false). */
        public WorktreeCreateResult(String worktreePath, String worktreeBranch,
                                    String originalBranch, String originalHeadCommit, boolean existed) {
            this(worktreePath, worktreeBranch, originalBranch, originalHeadCommit, existed, false);
        }
    }


    private static Optional<String> tryCreateViaHook(String slug) {
        WorktreeHooks h = worktreeHooks;
        if (h != null && h.hasCreateHook()) {
            validateWorktreeSlug(slug);
            return h.create(slug).filter(p -> !StringUtils.isBlank(p));
        }
        return Optional.empty();
    }

    /**
     * Creates a SESSION worktree via the {@code WorktreeCreate} hook if one is configured (non-git VCS
     * fallback), else via git off {@code fromCwd}'s canonical root.
     */
    public static WorktreeCreateResult createSessionWorktree(String slug, String fromCwd) {
        Optional<String> hookPath = tryCreateViaHook(slug);
        if (hookPath.isPresent()) {
            return new WorktreeCreateResult(hookPath.get(), null, null, null, false, /* hookBased */ true);
        }
        String gitRoot = findCanonicalGitRoot(fromCwd);
        if (gitRoot == null) {
            throw new WorktreeException("Cannot create a worktree: not in a git repository "
                + "and no WorktreeCreate hooks are configured. "
                + "Configure WorktreeCreate/WorktreeRemove hooks in settings.json to use "
                + "worktree isolation with other VCS systems.");
        }
        return getOrCreateWorktree(gitRoot, slug);
    }

    /** One entry from {@code git worktree list --porcelain}. */
    public record RegisteredWorktree(String path, String branch, String headCommit) {}

    /**
     * Resolves {@code requestedPath} to a worktree registered for the same canonical repository as
     * {@code fromCwd}.
     */
    public static Optional<RegisteredWorktree> findRegisteredWorktree(
            String fromCwd, String requestedPath) {
        if (fromCwd == null || requestedPath == null || StringUtils.isBlank(requestedPath)) {
            return Optional.empty();
        }
        String repoRoot = findCanonicalGitRoot(fromCwd);
        if (repoRoot == null) return Optional.empty();

        Path requested = Path.of(requestedPath);
        if (!requested.isAbsolute()) requested = Path.of(fromCwd).resolve(requested);
        try {
            requested = requested.toRealPath();
        } catch (IOException | SecurityException _) {
            return Optional.empty();
        }

        GitResult result = execGit(repoRoot, "worktree", "list", "--porcelain");
        if (result.exitCode() != 0) return Optional.empty();

        String path = null;
        String branch = null;
        String head = null;
        for (String line : result.stdout()) {
            if (StringUtils.isBlank(line)) {
                Optional<RegisteredWorktree> match = registeredMatch(requested, path, branch, head);
                if (match.isPresent()) return match;
                path = branch = head = null;
            } else if (Strings.CS.startsWith(line, "worktree ")) {
                path = line.substring("worktree ".length());
            } else if (Strings.CS.startsWith(line, "branch ")) {
                String ref = line.substring("branch ".length());
                branch = Strings.CS.startsWith(ref, "refs/heads/")
                    ? ref.substring("refs/heads/".length()) : ref;
            } else if (Strings.CS.startsWith(line, "HEAD ")) {
                head = line.substring("HEAD ".length());
            }
        }
        return registeredMatch(requested, path, branch, head);
    }

    private static Optional<RegisteredWorktree> registeredMatch(
            Path requested, String path, String branch, String head) {
        if (path == null) return Optional.empty();
        try {
            Path registered = Path.of(path).toRealPath();
            if (registered.equals(requested)) {
                return Optional.of(new RegisteredWorktree(
                    registered.toString(), branch, head));
            }
        } catch (IOException | SecurityException _) {
            // A stale worktree entry is not a valid switch target.
        }
        return Optional.empty();
    }

    /**
     * Env vars that prevent git/SSH from prompting for credentials during {@code fetch} (which would
     * otherwise hang the CLI waiting on a tty).
     */
    private static final Map<String, String> GIT_NO_PROMPT_ENV =
        Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_ASKPASS", "");

    /**
     * Creates a new git worktree for {@code slug} off {@code repoRoot}'s default branch, or resumes it
     * if a worktree at that path already exists.
     */
    public static WorktreeCreateResult getOrCreateWorktree(String repoRoot, String slug) {
        validateWorktreeSlug(slug);
        String worktreePath = worktreePathFor(repoRoot, slug);
        String worktreeBranch = worktreeBranchName(slug);
        String originalBranch = currentBranch(repoRoot);

        // Fast resume path: a worktree already registered at this path.
        if (Files.isDirectory(Path.of(worktreePath))) {
            List<String> head = runGit(worktreePath, "rev-parse", "HEAD");
            if (!head.isEmpty()) {
                touchWorktreeForResume(worktreePath);
                return new WorktreeCreateResult(worktreePath, worktreeBranch, originalBranch, head.getFirst().trim(), true);
            }
        }

        try {
            Files.createDirectories(Path.of(worktreesDir(repoRoot)));
        } catch (IOException e) {
            throw new WorktreeException("Failed to create worktrees directory: " + e.getMessage());
        }

        WorktreeBase base = resolveWorktreeBase(repoRoot);

        List<String> sparsePaths = configuredSparsePaths(repoRoot);
        List<String> addArgs = new ArrayList<>(List.of("worktree", "add"));
        if (!sparsePaths.isEmpty()) addArgs.add("--no-checkout");
        // -B (not -b): resets any orphaned branch left behind by a removed worktree dir.
        addArgs.addAll(List.of("-B", worktreeBranch, worktreePath, base.branch()));
        GitResult created = execGit(repoRoot, addArgs.toArray(String[]::new));
        if (created.exitCode() != 0) {
            throw new WorktreeException("Failed to create worktree: " + created.stderrJoined());
        }

        if (!sparsePaths.isEmpty()) {
            configureSparseCheckout(repoRoot, worktreePath, sparsePaths);
        }

        copySettingsLocal(repoRoot, worktreePath);
        configureHooksPath(repoRoot, worktreePath);
        symlinkConfiguredDirectories(repoRoot, worktreePath);
        copyWorktreeIncludeFiles(repoRoot, worktreePath);

        return new WorktreeCreateResult(worktreePath, worktreeBranch, originalBranch, base.sha(), false);
    }

    private record WorktreeBase(String branch, String sha) {}


    private static WorktreeBase resolveWorktreeBase(String repoRoot) {
        Function<String, String> supplier = baseRefSupplier;
        String configured = supplier == null ? "fresh" : supplier.apply(repoRoot);
        if (Strings.CI.equals("head", configured)) {
            List<String> sha = runGit(repoRoot, "rev-parse", "HEAD");
            if (sha.isEmpty() || StringUtils.isBlank(sha.getFirst())) {
                throw new WorktreeException("Failed to resolve worktree.baseRef=head: git rev-parse HEAD failed");
            }
            return new WorktreeBase("HEAD", sha.getFirst().trim());
        }
        String defaultBranch = defaultBranch(repoRoot);
        String originRef = "origin/" + defaultBranch;

        List<String> localSha = runGit(repoRoot, "rev-parse", "--verify", "-q", "refs/remotes/" + originRef);
        String baseBranch;
        String baseSha = null;
        if (!localSha.isEmpty() && !StringUtils.isBlank(localSha.getFirst())) {
            baseBranch = originRef;
            baseSha = localSha.getFirst().trim();
        } else {
            GitResult fetch = execGitWithEnv(repoRoot, GIT_NO_PROMPT_ENV, "fetch", "origin", defaultBranch);
            baseBranch = fetch.exitCode() == 0 ? originRef : "HEAD";
        }

        if (baseSha == null) {
            List<String> sha = runGit(repoRoot, "rev-parse", baseBranch);
            if (sha.isEmpty()) {
                throw new WorktreeException("Failed to resolve base branch \"" + baseBranch + "\": git rev-parse failed");
            }
            baseSha = sha.getFirst().trim();
        }
        return new WorktreeBase(baseBranch, baseSha);
    }


    private static String defaultBranch(String repoRoot) {
        List<String> symref = runGit(repoRoot, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
        if (!symref.isEmpty() && !StringUtils.isBlank(symref.getFirst())) {
            String ref = symref.getFirst().trim();
            int slash = ref.indexOf('/');
            return slash >= 0 ? ref.substring(slash + 1) : ref;
        }
        for (String candidate : List.of("main", "master")) {
            List<String> sha = runGit(repoRoot, "rev-parse", "--verify", "-q", "refs/remotes/origin/" + candidate);
            if (!sha.isEmpty() && !StringUtils.isBlank(sha.getFirst())) return candidate;
        }
        return "main";
    }

    private static String currentBranch(String repoRoot) {
        List<String> out = runGit(repoRoot, "branch", "--show-current");
        if (!out.isEmpty() && !StringUtils.isBlank(out.getFirst())) return out.getFirst().trim();
        List<String> fallback = runGit(repoRoot, "rev-parse", "--abbrev-ref", "HEAD");
        return fallback.isEmpty() ? null : fallback.getFirst().trim();
    }

    private static List<String> configuredSparsePaths(String repoRoot) {
        Function<String, List<String>> supplier = sparsePathsSupplier;
        if (supplier == null) return List.of();
        try {
            List<String> paths = supplier.apply(repoRoot);
            if (paths == null) return List.of();
            return paths.stream()
                .filter(p -> StringUtils.isNotBlank(p) && !containsPathTraversal(p))
                .map(String::trim)
                .distinct()
                .toList();
        } catch (Exception e) {
            log.debug("sparsePaths supplier failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static void configureSparseCheckout(String repoRoot, String worktreePath, List<String> paths) {
        List<String> sparseArgs = new ArrayList<>(List.of("sparse-checkout", "set", "--cone", "--"));
        sparseArgs.addAll(paths);
        GitResult sparse = execGit(worktreePath, sparseArgs.toArray(String[]::new));
        if (sparse.exitCode() != 0) {
            removePartiallyCreatedWorktree(repoRoot, worktreePath);
            throw new WorktreeException("Failed to configure sparse-checkout: " + sparse.stderrJoined());
        }
        GitResult checkout = execGit(worktreePath, "checkout", "HEAD");
        if (checkout.exitCode() != 0) {
            removePartiallyCreatedWorktree(repoRoot, worktreePath);
            throw new WorktreeException("Failed to checkout sparse worktree: " + checkout.stderrJoined());
        }
    }

    private static void removePartiallyCreatedWorktree(String repoRoot, String worktreePath) {
        GitResult removed = execGit(repoRoot, "worktree", "remove", "--force", worktreePath);
        if (removed.exitCode() != 0) {
            log.warn("Failed to remove partially-created worktree {}: {}", worktreePath, removed.stderrJoined());
        }
    }

    private static void touchWorktreeForResume(String worktreePath) {
        try {
            Files.setLastModifiedTime(Path.of(worktreePath), FileTime.from(Instant.now()));
        } catch (IOException | SecurityException e) {
            log.debug("Could not refresh worktree mtime {}: {}", worktreePath, e.getMessage());
        }
    }


    private static void copySettingsLocal(String repoRoot, String worktreePath) {
        Path source = Path.of(repoRoot, ".claude", "settings.local.json");
        Path destination = Path.of(worktreePath, ".claude", "settings.local.json");
        try {
            if (!Files.isRegularFile(source)) return;
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Copied settings.local.json to worktree: {}", destination);
        } catch (IOException | SecurityException e) {
            log.debug("Failed to copy settings.local.json: {}", e.getMessage());
        }
    }

    /**
     * Copies ignored files selected by the repository's {@code .worktreeinclude} file.
     */
    private static void copyWorktreeIncludeFiles(String repoRoot, String worktreePath) {
        Path includeFile = Path.of(repoRoot, ".worktreeinclude");
        if (!Files.isRegularFile(includeFile)) return;
        List<String> patterns;
        try {
            patterns = Files.readAllLines(includeFile).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !Strings.CS.startsWith(line, "#"))
                .toList();
        } catch (IOException | SecurityException e) {
            log.debug("Failed to read .worktreeinclude: {}", e.getMessage());
            return;
        }
        if (patterns.isEmpty()) return;
        GitResult ignored = execGit(repoRoot, "ls-files", "--others", "--ignored", "--exclude-standard", "--directory");
        if (ignored.exitCode() != 0) return;
        for (String entry : ignored.stdout()) {
            if (StringUtils.isBlank(entry)) continue;
            String relative = entry.trim();
            if (Strings.CS.endsWith(relative, "/")) {
                // Expand collapsed ignored directories only when an include pattern can reach them.
                if (patterns.stream().noneMatch(p -> patternCouldReachDirectory(p, relative))) continue;
                GitResult expanded = execGit(repoRoot, "ls-files", "--others", "--ignored", "--exclude-standard", "--", relative);
                if (expanded.exitCode() != 0) continue;
                for (String child : expanded.stdout()) copyIncludedFile(repoRoot, worktreePath, patterns, child);
            } else {
                copyIncludedFile(repoRoot, worktreePath, patterns, relative);
            }
        }
    }

    private static boolean patternCouldReachDirectory(String pattern, String directory) {
        String normalized =Strings.CS.startsWith( pattern, "/") ? pattern.substring(1) : pattern;
        String dir =Strings.CS.endsWith( directory, "/") ? directory : directory + "/";
        if (Strings.CS.startsWith(normalized, dir)) return true;
        int glob = firstGlobIndex(normalized);
        return glob > 0 &&Strings.CS.startsWith( dir, normalized.substring(0, glob));
    }

    private static int firstGlobIndex(String value) {
        int star = value.indexOf('*');
        int question = value.indexOf('?');
        int bracket = value.indexOf('[');
        int result = star >= 0 ? star : value.length();
        if (question >= 0) result = Math.min(result, question);
        if (bracket >= 0) result = Math.min(result, bracket);
        return result == value.length() ? -1 : result;
    }

    private static void copyIncludedFile(String repoRoot, String worktreePath,
                                         List<String> patterns, String rawRelative) {
        if (StringUtils.isBlank(rawRelative)) return;
        String relative = rawRelative.trim();
        if (Strings.CS.endsWith(relative, "/") || containsPathTraversal(relative) || !matchesAnyPattern(patterns, relative)) return;
        Path source = Path.of(repoRoot).resolve(relative).normalize();
        Path destination = Path.of(worktreePath).resolve(relative).normalize();
        if (!source.startsWith(Path.of(repoRoot).toAbsolutePath().normalize())
                || !destination.startsWith(Path.of(worktreePath).toAbsolutePath().normalize())) return;
        try {
            if (!Files.isRegularFile(source)) return;
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Copied {} from .worktreeinclude", relative);
        } catch (IOException | SecurityException e) {
            log.debug("Failed to copy {} from .worktreeinclude: {}", relative, e.getMessage());
        }
    }

    private static boolean matchesAnyPattern(List<String> patterns, String relative) {
        return patterns.stream().anyMatch(pattern -> globMatches(pattern, relative));
    }

    private static boolean globMatches(String rawPattern, String relative) {
        String pattern = rawPattern.trim();
        if (pattern.isEmpty() ||Strings.CS.startsWith( pattern, "#") ||Strings.CS.startsWith( pattern, "!")) return false;
        boolean directoryPattern =Strings.CS.endsWith( pattern, "/");
        if (directoryPattern) pattern = pattern.substring(0, pattern.length() - 1);
        if (Strings.CS.startsWith(pattern, "/")) pattern = pattern.substring(1);
        String regex = globToRegex(pattern,Strings.CS.contains( pattern, "/"));
        boolean matched = Pattern.compile(regex).matcher(relative).matches();
        if (!matched && !Strings.CS.contains(pattern, "/")) {
            int slash = relative.lastIndexOf('/');
            matched = Pattern.compile(regex).matcher(slash < 0 ? relative : relative.substring(slash + 1)).matches();
        }
        return matched || (directoryPattern &&Strings.CS.startsWith( relative, pattern + "/"));
    }

    private static String globToRegex(String glob, boolean slashAware) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    out.append(".*");
                } else {
                    out.append(slashAware ? "[^/]*" : ".*");
                }
            } else if (c == '?') {
                out.append(slashAware ? "[^/]" : ".");
            } else {
                out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return out.append('$').toString();
    }

    /**
     * Symlinks {@code worktree.symlinkDirectories} (e.g.
     */
    private static void symlinkConfiguredDirectories(String repoRoot, String worktreePath) {
        Function<String, List<String>> supplier = symlinkDirectoriesSupplier;
        if (supplier == null) return;
        List<String> dirs;
        try {
            dirs = supplier.apply(repoRoot);
        } catch (Exception e) {
            log.debug("symlinkDirectories supplier failed: {}", e.getMessage());
            return;
        }
        if (dirs == null || dirs.isEmpty()) return;
        for (String dir : dirs) {
            if (StringUtils.isBlank(dir) || containsPathTraversal(dir)) {
                log.debug("Skipping worktree symlink for unsafe/blank entry: {}", dir);
                continue;
            }
            Path source = Path.of(repoRoot, dir);
            Path dest = Path.of(worktreePath, dir);
            try {
                Files.createSymbolicLink(dest, source);
                log.debug("Symlinked {} from main repo into worktree", dir);
            } catch (FileAlreadyExistsException | NoSuchFileException _) {
// Dest exists (git checked it out) or source not present yet — skip.
            } catch (IOException | UnsupportedOperationException e) {
                log.debug("Failed to symlink {} (skipped): {}", dir, e.getMessage());
            }
        }
    }


    private static boolean containsPathTraversal(String relative) {
        return Path.of(relative).isAbsolute() || PathUtils.containsPathTraversal(relative);
    }



    /**
     * A worktree created for a single subagent's {@code isolation: "worktree"} run.
     */
    public record AgentWorktree(String worktreePath, String worktreeBranch, String headCommit,
                                String gitRoot, boolean hookBased) {
        /** Git-based convenience constructor (hookBased=false). */
        public AgentWorktree(String worktreePath, String worktreeBranch, String headCommit, String gitRoot) {
            this(worktreePath, worktreeBranch, headCommit, gitRoot, false);
        }
    }

    /**
     * Creates (or resumes) an isolated worktree for a subagent, off {@code fromCwd}'s CANONICAL git
     * root.
     */
    public static AgentWorktree createAgentWorktree(String slug, String fromCwd) {
// Hook-first (non-git VCS fallback).
        Optional<String> hookPath = tryCreateViaHook(slug);
        if (hookPath.isPresent()) {
            return new AgentWorktree(hookPath.get(), null, null, null, /* hookBased */ true);
        }
        String gitRoot = findCanonicalGitRoot(fromCwd);
        if (gitRoot == null) {
            throw new WorktreeException("Cannot create agent worktree: not in a git repository "
                + "and no WorktreeCreate hook is configured.");
        }
        WorktreeCreateResult r = getOrCreateWorktree(gitRoot, slug);
        return new AgentWorktree(r.worktreePath(), r.worktreeBranch(), r.originalHeadCommit(), gitRoot);
    }


    public static boolean removeAgentWorktree(String worktreePath, String worktreeBranch, String gitRoot) {
        if (gitRoot == null) {
            log.error("Cannot remove agent worktree: no git root provided");
            return false;
        }
        GitResult removed = execGit(gitRoot, "worktree", "remove", "--force", worktreePath);
        if (removed.exitCode() != 0) {
            log.error("Failed to remove agent worktree: {}", removed.stderrJoined());
            return false;
        }
        if (StringUtils.isNotBlank(worktreeBranch)) {
            GitResult deleted = execGit(gitRoot, "branch", "-D", worktreeBranch);
            if (deleted.exitCode() != 0) {
                log.debug("Could not delete agent worktree branch {}: {}", worktreeBranch, deleted.stderrJoined());
            }
        }
        return true;
    }

    /**
     * Whether a subagent worktree has uncommitted changes OR commits ahead of {@code headCommit}.
     */
    public static boolean hasWorktreeChanges(String worktreePath, String headCommit) {
        GitResult status = execGit(worktreePath, "status", "--porcelain");
        if (status.exitCode() != 0) return true;
        if (status.stdout().stream().anyMatch(l -> !StringUtils.isBlank(l))) return true;
        if (StringUtils.isBlank(headCommit)) return true;
        GitResult revList = execGit(worktreePath, "rev-list", "--count", headCommit + "..HEAD");
        if (revList.exitCode() != 0) return true;
        try {
            return !revList.stdout().isEmpty() && Integer.parseInt(revList.stdout().getFirst().trim()) > 0;
        } catch (NumberFormatException _) {
            return true;
        }
    }

    /**
     * Cleanup after a subagent's isolated run.
     */
    public static Optional<String> cleanupAgentWorktree(AgentWorktree wt) {
        if (wt == null) return Optional.empty();
        try {
            // Hook-based agent worktrees can't be change-detected (VCS-agnostic), so

            if (wt.hookBased()) {
                log.debug("Hook-based agent worktree kept at: {}", wt.worktreePath());
                return Optional.of(wt.worktreePath());
            }
            if (!hasWorktreeChanges(wt.worktreePath(), wt.headCommit())) {
                removeAgentWorktree(wt.worktreePath(), wt.worktreeBranch(), wt.gitRoot());
                return Optional.empty();
            }
            log.debug("Agent worktree has changes, keeping: {}", wt.worktreePath());
            return Optional.of(wt.worktreePath());
        } catch (Exception e) {
            log.debug("Agent worktree cleanup failed, keeping {}: {}", wt.worktreePath(), e.getMessage());
            return Optional.of(wt.worktreePath());
        }
    }

    /**
     * Removes stale, clean temporary agent/workflow worktrees.
     */
    public static int cleanupStaleAgentWorktrees(Instant cutoff) {
        return cleanupStaleAgentWorktrees(System.getProperty("user.dir"), cutoff);
    }

    /** Package/test seam allowing the canonical repository root to be selected explicitly. */
    public static int cleanupStaleAgentWorktrees(String cwd, Instant cutoff) {
        if (cwd == null || cutoff == null) return 0;
        String gitRoot = findCanonicalGitRoot(cwd);
        if (gitRoot == null) return 0;
        Path directory = Path.of(worktreesDir(gitRoot));
        if (!Files.isDirectory(directory)) return 0;

        String currentPath = CURRENT.get() == null ? null : CURRENT.get().worktreePath();
        int removed = 0;
        try (var entries = Files.list(directory)) {
            for (Path worktree : entries.toList()) {
                String slug = worktree.getFileName().toString();
                if (EPHEMERAL_WORKTREE_PATTERNS.stream().noneMatch(p -> p.matcher(slug).matches())) continue;
                if (currentPath != null && Path.of(currentPath).equals(worktree)) continue;
                try {
                    if (Files.getLastModifiedTime(worktree).toInstant().compareTo(cutoff) >= 0) continue;
                } catch (IOException | SecurityException _) {
                    continue;
                }

                GitResult status = execGit(worktree.toString(), "--no-optional-locks", "status", "--porcelain", "-uno");
                if (status.exitCode() != 0 || status.stdout().stream().anyMatch(line -> !StringUtils.isBlank(line))) continue;
                GitResult unpushed = execGit(worktree.toString(), "rev-list", "--max-count=1", "HEAD", "--not", "--remotes");
                if (unpushed.exitCode() != 0 || unpushed.stdout().stream().anyMatch(line -> !StringUtils.isBlank(line))) continue;

                if (removeAgentWorktree(worktree.toString(), worktreeBranchName(slug), gitRoot)) removed++;
            }
        } catch (IOException | SecurityException _) {
            return removed;
        }
        if (removed > 0) {
            execGit(gitRoot, "worktree", "prune");
            log.debug("cleanupStaleAgentWorktrees: removed {} stale worktree(s)", removed);
        }
        return removed;
    }


    private static void configureHooksPath(String repoRoot, String worktreePath) {
        Path husky = Path.of(repoRoot, ".husky");
        Path gitHooks = Path.of(repoRoot, ".git", "hooks");
        Path hooksPath = Files.isDirectory(husky) ? husky : Files.isDirectory(gitHooks) ? gitHooks : null;
        if (hooksPath == null) return;
        GitResult result = execGit(worktreePath, "config", "core.hooksPath", hooksPath.toString());
        if (result.exitCode() != 0) {
            log.debug("Failed to configure core.hooksPath for {}: {}", worktreePath, result.stderrJoined());
        }
    }




    public static String keepWorktree() {
        WorktreeSession session = CURRENT.get();
        if (session == null) return "No active worktree session found";
        try {
            System.setProperty("user.dir", session.originalCwd());

            // setOriginalCwd(originalCwd)) so trust re-anchors to the repo root.
            CwdState.setOriginalCwd(Path.of(session.originalCwd()));
            log.debug("Linked worktree preserved at: {}{}", session.worktreePath(),
                session.worktreeBranch() != null ? " on branch: " + session.worktreeBranch() : "");
        } catch (Exception e) {
            log.error("Error keeping worktree: {}", e.getMessage());
        } finally {
            CURRENT.set(null);
            resetLatches();
        }
        return String.format(
            "Worktree kept. Your work is saved at %s on branch %s",
            session.worktreePath(),
            session.worktreeBranch() != null ? session.worktreeBranch() : "(unknown)");
    }


    public static String cleanupWorktree() {
        WorktreeSession session = CURRENT.get();
        if (session == null) return "No active worktree session found";
        try {
            System.setProperty("user.dir", session.originalCwd());

            // setOriginalCwd(originalCwd)).
            CwdState.setOriginalCwd(Path.of(session.originalCwd()));
            if (session.hookBased()) {
// VCS-agnostic worktree: delegate removal to the WorktreeRemove hook.
                WorktreeHooks h = worktreeHooks;
                if (h == null || !h.remove(session.worktreePath())) {
                    log.warn("No WorktreeRemove hook ran; hook-based worktree left at: {}", session.worktreePath());
                }
            } else {
                GitResult removed = execGit(session.originalCwd(), "worktree", "remove", "--force", session.worktreePath());
                if (removed.exitCode() != 0) {
                    log.error("Failed to remove linked worktree: {}", removed.stderrJoined());
                } else {
                    log.debug("Removed linked worktree at: {}", session.worktreePath());
                }
                if (session.worktreeBranch() != null) {

                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                    GitResult deleted = execGit(session.originalCwd(), "branch", "-D", session.worktreeBranch());
                    if (deleted.exitCode() != 0) {
                        log.error("Could not delete worktree branch: {}", deleted.stderrJoined());
                    } else {
                        log.debug("Deleted worktree branch: {}", session.worktreeBranch());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error cleaning up worktree: {}", e.getMessage());
        } finally {
            CURRENT.set(null);
            resetLatches();
        }
        return "Worktree removed.";
    }

    /**
     * Change summary for {@code ExitWorktreeTool}'s removal safety gate.
     */
    public record ChangeSummary(int changedFiles, int commits) {}

    public static ChangeSummary changeSummaryOrNull(String worktreePath, String originalHeadCommit) {
        GitResult status = execGit(worktreePath, "status", "--porcelain");
        if (status.exitCode() != 0) return null;
        int changedFiles = (int) status.stdout().stream().filter(l -> !StringUtils.isBlank(l)).count();

        if (StringUtils.isBlank(originalHeadCommit)) return null;
        GitResult revList = execGit(worktreePath, "rev-list", "--count", originalHeadCommit + "..HEAD");
        if (revList.exitCode() != 0) return null;
        int commits;
        try {
            commits = revList.stdout().isEmpty() ? 0 : Integer.parseInt(revList.stdout().getFirst().trim());
        } catch (NumberFormatException _) {
            commits = 0;
        }
        return new ChangeSummary(changedFiles, commits);
    }


    public static void killTmuxSession(String name) {
        if (StringUtils.isBlank(name)) return;
        tmuxSessionKiller.accept(name);
    }

    private static void killTmuxSessionProcess(String name) {
        try {
            Process process = new ProcessBuilder("tmux", "kill-session", "-t", name)
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                log.debug("tmux kill-session -t {} exited {}: {}", name, exit, output.trim());
            }
        } catch (IOException e) {
            log.debug("Unable to kill tmux session {}: {}", name, e.getMessage());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while killing tmux session {}", name);
        }
    }




    private static ObjectNode toJson(WorktreeSession session) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("originalCwd", session.originalCwd());
        node.put("worktreePath", session.worktreePath());
        node.put("worktreeName", session.worktreeName());
        if (session.worktreeBranch() != null) node.put("worktreeBranch", session.worktreeBranch());
        if (session.originalBranch() != null) node.put("originalBranch", session.originalBranch());
        if (session.originalHeadCommit() != null) node.put("originalHeadCommit", session.originalHeadCommit());
        node.put("sessionId", session.sessionId());
        if (session.tmuxSessionName() != null) node.put("tmuxSessionName", session.tmuxSessionName());
        node.put("hookBased", session.hookBased());
        node.put("projectRootMoved", session.projectRootMoved());
        node.put("enteredExisting", session.enteredExisting());
        return node;
    }

    /** Inverse of {@link #toJson} — reconstructs a {@link WorktreeSession} from a JSONL-read node. */
    private static WorktreeSession fromJson(JsonNode node) {
        return new WorktreeSession(
            textOrNull(node, "originalCwd"),
            textOrNull(node, "worktreePath"),
            textOrNull(node, "worktreeName"),
            textOrNull(node, "worktreeBranch"),
            textOrNull(node, "originalBranch"),
            textOrNull(node, "originalHeadCommit"),
            textOrNull(node, "sessionId"),
            textOrNull(node, "tmuxSessionName"),
            node.path("hookBased").asBoolean(false),
            0L,
            false,
            node.path("projectRootMoved").asBoolean(false),
            node.path("enteredExisting").asBoolean(false));
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }


    public static void persistWorktreeState(SessionStorage storage, Path sessionFile, String sessionId, WorktreeSession session) {
        if (storage == null || sessionFile == null) return;
        try {
            storage.appendWorktreeState(sessionFile, sessionId, session == null ? null : toJson(session));
        } catch (Exception e) {
            log.warn("Failed to persist worktree-state for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Reads the last {@code worktree-state} entry from {@code sessionFile} and reconstructs the {@link
     * WorktreeSession} it recorded, or {@code null} if the session never touched a worktree or last
     * recorded exiting one.
     */
    public static WorktreeSession readPersistedWorktreeState(SessionStorage storage, Path sessionFile) {
        if (storage == null || sessionFile == null) return null;
        SessionStorage.WorktreeStateEntry entry = storage.scanWorktreeState(sessionFile);
        if (entry == null || entry.worktreeSessionJson() == null) return null;
        return fromJson(entry.worktreeSessionJson());
    }

    /**
     * Resolves to the MAIN repository root, even when {@code cwd} is already inside.
     */
    public static String findCanonicalGitRoot(String cwd) {
        Path root = GitUtils.findCanonicalGitRoot(Path.of(cwd));
        return root != null ? root.toString() : null;
    }

    // ── Read-only helpers used by the exit dialog / exit tool ───────────────

    /**
     * Lines from {@code git status --porcelain} inside {@code cwd} (one line per changed file).
     */
    public static List<String> gitStatusPorcelain(String cwd) {
        List<String> out = runGit(cwd, "status", "--porcelain");
        List<String> lines = new ArrayList<>();
        for (String s : out) {
            if (s != null && !s.trim().isEmpty()) lines.add(s);
        }
        return lines;
    }

    /**
     * Count of commits in {@code cwd} that are not in {@code baseCommit}.
     */
    public static int commitCountAhead(String cwd, String baseCommit) {
        if (StringUtils.isBlank(baseCommit)) return 0;
        List<String> out = runGit(cwd, "rev-list", "--count", baseCommit + "..HEAD");
        if (out.isEmpty()) return 0;
        try {
            return Integer.parseInt(out.getFirst().trim());
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    // ── git process helpers ─────────────────────────────────────────────────

    /** Result of a git invocation: exit code, stdout lines, stderr lines. */
    private record GitResult(int exitCode, List<String> stdout, List<String> stderr) {
        String stderrJoined() {
            return String.join("\n", stderr).trim();
        }
    }

    private static GitResult execGit(String cwd, String... args) {
        return execGitWithEnv(cwd, Map.of(), args);
    }

    private static GitResult execGitWithEnv(String cwd, Map<String, String> env, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        Collections.addAll(cmd, args);
        ProcessResult result = ProcessRunner.run(
            cmd, cwd == null ? null : Path.of(cwd), Duration.ofSeconds(30), env);
        if (result.timedOut()) {
            return new GitResult(-1, List.of(), List.of("git command timed out: " + String.join(" ", args)));
        }
        if (result.exitCode() < 0) {
            return new GitResult(-1, List.of(), List.of("git spawn failed"));
        }
        return new GitResult(result.exitCode(), result.stdoutLines(), result.stderrLines());
    }

    /** Minimal git runner. Returns stdout lines; empty list on any error or non-zero exit. */
    private static List<String> runGit(String cwd, String... args) {
        GitResult result = execGit(cwd, args);
        return result.exitCode() == 0 ? result.stdout() : List.of();
    }
}
