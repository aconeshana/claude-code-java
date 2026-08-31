package com.claudecode.ui.lanterna.suggest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.tools.files.RipGrepUtil;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @-file completion service.
 */
public final class FileSuggestionService {


    private static final long REFRESH_THROTTLE_MS = 5_000;

    private static final int MAX_INLINE_FILE_WALK = 500;
    private static final int GIT_LS_TIMEOUT_SECS  = 2;
    private static final long COLD_REFRESH_WAIT_MS = 2_500;

    private final AtomicReference<List<String>> cache           = new AtomicReference<>(null);
    private final AtomicLong                    lastRefreshMs   = new AtomicLong(0);
    private final AtomicLong                    lastGitMtime    = new AtomicLong(-1);
    private final AtomicReference<CompletableFuture<List<String>>> refreshInFlight =
        new AtomicReference<>();
    private final AtomicLong                    queryGen        = new AtomicLong(0);
    private final AtomicReference<Boolean>      cachedRespectGitignore =
        new AtomicReference<>();

    private final WindowBasedTextGUI gui;
    private final InputPanel         inputPanel;
    private final BooleanSupplier    respectGitignore;

    public FileSuggestionService(WindowBasedTextGUI gui, InputPanel inputPanel) {
        this(gui, inputPanel,
            () -> UiSettings.readEffectiveBoolean("respectGitignore", true));
    }

    FileSuggestionService(WindowBasedTextGUI gui, InputPanel inputPanel,
                          BooleanSupplier respectGitignore) {
        this.gui        = gui;
        this.inputPanel = inputPanel;
        this.respectGitignore = respectGitignore;
    }


    void warmUp() {
        startBackgroundRefresh(findGitRootUpward(System.getProperty("user.dir")));
    }

    /** Increment and return the current query generation. */
    public long nextGen() { return queryGen.incrementAndGet(); }

    /** Read the current query generation without incrementing. */
    public long currentGen() { return queryGen.get(); }

    /**
     * Build file suggestions filtered by {@code filter}.
     */
    public List<SuggestionPanel.Suggestion> build(String filter) {
        return build(filter, () -> false);
    }

    /** Builds suggestions while allowing a superseding keystroke to stop scoring early. */
    List<SuggestionPanel.Suggestion> build(String filter, BooleanSupplier cancelled) {
        String cwd = findGitRootUpward(System.getProperty("user.dir"));
        boolean respectIgnoredFiles = currentRespectGitignore();

        if (StringUtils.isBlank(filter)) {
            return getTopLevelPaths(cwd);
        }

        List<String> files = cache.get();
        if (files == null) {
            CompletableFuture<List<String>> refresh = new CompletableFuture<>();
            if (refreshInFlight.compareAndSet(null, refresh)) {
                try {
                    files = collectFiles(cwd, respectIgnoredFiles);
                    cache.compareAndSet(null, files);
                    lastRefreshMs.set(System.currentTimeMillis());
                    refresh.complete(files);
                } catch (RuntimeException failure) {
                    refresh.completeExceptionally(failure);
                    throw failure;
                } finally {
                    refreshInFlight.compareAndSet(refresh, null);
                }
                files = cache.get();
            } else {
                // Another query already owns the cold refresh. This method only
                // runs on LatestTaskRunner's virtual thread, so it is safe to
                // wait cooperatively instead of publishing an empty list.
                files = awaitColdRefresh(refreshInFlight.get(), cancelled);
                if (files == null) return List.of();
            }
        } else {
            startBackgroundRefresh(cwd);
        }

        final String q = filter.toLowerCase(Locale.ROOT);
        List<Map.Entry<String, Integer>> matches = new ArrayList<>();
        for (String file : files) {
            if (cancelled.getAsBoolean()) return List.of();
            int score = subsequenceScore(q, file);
            if (score >= 0) matches.add(Map.entry(file, score));
        }
        if (cancelled.getAsBoolean()) return List.of();
        matches.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return matches.stream().limit(SuggestionPanel.MAX_VISIBLE)
            .map(e -> new SuggestionPanel.Suggestion(e.getKey(), "", "+"))
            .toList();
    }

    private List<String> awaitColdRefresh(CompletableFuture<List<String>> refresh,
                                          BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return null;
        if (refresh == null) return cache.get();
        try {
            return refresh.get(COLD_REFRESH_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException _) {
            return cache.get();
        }
    }


    private List<SuggestionPanel.Suggestion> getTopLevelPaths(String cwd) {
        try (Stream<Path> stream = Files.list(Path.of(cwd))) {
            return stream
                .map(p -> {
                    String name = p.getFileName().toString();
                    boolean isDir = Files.isDirectory(p);
                    String display = isDir ? name + "/" : name;
                    return new SuggestionPanel.Suggestion(display, "", "+");
                })
                .limit(SuggestionPanel.MAX_VISIBLE)
                .collect(Collectors.toList());
        } catch (Exception _) {
            return List.of();
        }
    }

    /**
     * Subsequence fuzzy match score — pure-JVM equivalent of nucleo's
     * Smith-Waterman variant. See {@link FileSuggestionService} class Javadoc.
     * Returns -1 if query is not a subsequence of text.
     */
    public static int subsequenceScore(String query, String text) {
        if (query.isEmpty()) return 0;
        int score = 0;
        int qi = 0;
        int firstMatchPos = 0;
        boolean prevMatch = false;
        for (int ti = 0; ti < text.length() && qi < query.length(); ti++) {
            char tc = Character.toLowerCase(text.charAt(ti));
            if (tc == query.charAt(qi)) {
                if (qi == 0) firstMatchPos = ti;
                score += 1;
                if (prevMatch) score += 2;
                if (ti == 0) {
                    score += 3;
                } else {
                    char prev = text.charAt(ti - 1);
                    if (prev == '/' || prev == '.' || prev == '-' || prev == '_' || prev == ' ') {
                        score += 3;
                    }
                }
                qi++;
                prevMatch = true;
            } else {
                prevMatch = false;
            }
        }
        if (qi < query.length()) return -1;
        int firstSlash = text.indexOf('/');
        String firstSeg = firstSlash > 0 ? text.substring(0, firstSlash) : text;
        String queryBare = Strings.CS.endsWith(query, "/") ? query.substring(0, query.length() - 1) : query;
        int firstSegBonus = firstSeg.equalsIgnoreCase(queryBare) ? 20 : 0;
        int slashIdx = text.lastIndexOf('/');
        String baseName = slashIdx >= 0 ? text.substring(slashIdx + 1) : text;
        int filenameBonus = Character.toLowerCase(baseName.charAt(0)) == query.charAt(0)
            && Strings.CI.startsWith(baseName, query) ? 5 : 0;
        int hiddenPenalty = Strings.CS.contains(text, "/.") ? 1 : 0;
        return (score + firstSegBonus + filenameBonus - hiddenPenalty) * 1000 - firstMatchPos;
    }

    private void startBackgroundRefresh(String cwd) {
        boolean respectIgnoredFiles = currentRespectGitignore();
        if (refreshInFlight.get() != null) return;

        long now = System.currentTimeMillis();
        long lastRefresh = lastRefreshMs.get();
        boolean cacheEmpty = cache.get() == null;

        long currentGitMtime = getGitIndexMtime(cwd);
        boolean gitIndexChanged = currentGitMtime >= 0
            && currentGitMtime != lastGitMtime.get();

        if (!cacheEmpty && !gitIndexChanged && (now - lastRefresh) < REFRESH_THROTTLE_MS) {
            return;
        }

        CompletableFuture<List<String>> refresh = new CompletableFuture<>();
        if (!refreshInFlight.compareAndSet(null, refresh)) return;

        Thread.ofVirtual().name("file-cache-refresh").start(() -> {
            try {
                List<String> fresh = collectFiles(cwd, respectIgnoredFiles);
                cache.set(fresh);
                lastRefreshMs.set(System.currentTimeMillis());
                if (currentGitMtime >= 0) {
                    lastGitMtime.set(currentGitMtime);
                }
// Re-fire query change on the GUI thread so the current live @-token is
// re-evaluated with fresh cache.
                if (gui != null && inputPanel != null) {
                    gui.getGUIThread().invokeLater(inputPanel::triggerQueryChange);
                }
                refresh.complete(fresh);
            } catch (RuntimeException failure) {
                refresh.completeExceptionally(failure);
            } finally {
                refreshInFlight.compareAndSet(refresh, null);
            }
        });
    }

    private boolean currentRespectGitignore() {
        boolean current = respectGitignore.getAsBoolean();
        Boolean previous = cachedRespectGitignore.getAndSet(current);
        if (previous != null && previous != current) {
            cache.set(null);
            lastRefreshMs.set(0);
        }
        return current;
    }

    /**
     * Return .git/index mtime in ms, or -1 if not in a git repo / on error.
     * Handles worktrees and submodules where .git is a pointer file
     * containing "gitdir: /path/to/real/gitdir".
     */
    private static long getGitIndexMtime(String cwd) {
        try {
            Path dotGit = Path.of(cwd, ".git");
            Path indexPath;
            if (Files.isDirectory(dotGit)) {
                indexPath = dotGit.resolve("index");
            } else if (Files.isRegularFile(dotGit)) {
                String contents = Files.readString(dotGit).trim();
                if (!Strings.CS.startsWith(contents, "gitdir:")) return -1;
                Path realGitDir = Path.of(contents.substring("gitdir:".length()).strip());
                if (!realGitDir.isAbsolute()) {
                    realGitDir = dotGit.getParent().resolve(realGitDir).normalize();
                }
                indexPath = realGitDir.resolve("index");
            } else {
                return -1;
            }
            if (!Files.exists(indexPath)) return -1;
            return FileUtils.modificationTimeMillis(indexPath);
        } catch (Exception _) {
            return -1;
        }
    }

    /**
     * Walk UP from {@code dir} to find the nearest ancestor containing a
     * {@code .git} entry (directory or pointer file). Returns {@code dir}
     * unchanged if none found.
     */
    private static String findGitRootUpward(String dir) {
        Path p = Path.of(dir).toAbsolutePath().normalize();
        while (p != null) {
            if (Files.exists(p.resolve(".git"))) return p.toString();
            p = p.getParent();
        }
        return dir;
    }

    /**
     * When {@code base} is not itself a git repo, scan depth-1 and depth-2
     * subdirectories for {@code .git} roots and aggregate tracked files.
     * Each path is prefixed with the relative path from {@code base}.
     * Parallelised via Virtual Threads with a 3s wall-clock cap.
     */
    private List<String> collectFromNestedGitRepos(Path base) {
        List<Path> repos = new ArrayList<>();
        try (Stream<Path> depth1 = Files.list(base)) {
            depth1.filter(Files::isDirectory)
                .filter(p -> !Strings.CS.startsWith(p.getFileName().toString(), "."))
                .forEach(d1 -> {
                    if (Files.exists(d1.resolve(".git"))) {
                        repos.add(d1);
                    } else {
                        try (Stream<Path> depth2 = Files.list(d1)) {
                            depth2.filter(Files::isDirectory)
                                .filter(d2 -> Files.exists(d2.resolve(".git")))
                                .forEach(repos::add);
                        } catch (Exception _) {}
                    }
                });
        } catch (Exception _) {}
        if (repos.isEmpty()) return List.of();

        List<String> result = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Path repo : repos) {
                String relPrefix = base.relativize(repo) + "/";
                pool.submit(() -> {
                    ProcessResult command = ProcessRunner.run(
                        List.of("git", "-c", "core.quotepath=false", "ls-files",
                            "--recurse-submodules"),
                        repo, Duration.ofSeconds(GIT_LS_TIMEOUT_SECS));
                    if (!command.succeeded()) return;
                    command.stdoutLines().stream()
                        .filter(line -> !StringUtils.isBlank(line))
                        .map(file -> relPrefix + file)
                        .forEach(result::add);
                });
            }
            pool.shutdown();
            try {
                if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException _) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        return result;
    }

    /**
     * Collect raw file paths from {@code cwd}. Tries three fallbacks in order:
     * git ls-files → nested-repo aggregation → rg --files → Files.walk.
     */
    private List<String> collectFiles(String cwd, boolean respectIgnoredFiles) {
        Path cwdPath = Path.of(cwd);

        // ── 1. git ls-files ─────────────────────────────────────────────
        if (respectIgnoredFiles) {
            ProcessResult gitFiles = ProcessRunner.run(
                List.of("git", "-c", "core.quotepath=false", "ls-files",
                    "--recurse-submodules"),
                cwdPath, Duration.ofSeconds(GIT_LS_TIMEOUT_SECS));
            if (gitFiles.succeeded() && !StringUtils.isBlank(gitFiles.stdout())) {
                return gitFiles.stdoutLines();
            }

            List<String> aggregated = collectFromNestedGitRepos(cwdPath);
            if (!aggregated.isEmpty()) return aggregated;
        }


        // RipGrepUtil handles stdin-close, the closed-pipe no-PATH search, symlink cwd via
        // toRealPath(), EAGAIN retry, and timeout kill — none of which the old inline launcher did.
        try {
            List<String> rgArgs = new ArrayList<>(List.of(
                "rg", "--files", "--follow", "--hidden",
                "--glob", "!.git/", "--glob", "!.svn/",
                "--glob", "!.hg/", "--glob", "!.bzr/",
                "--glob", "!.jj/", "--glob", "!.sl/",
                "--glob", "!node_modules/", "--glob", "!target/"));
            if (!respectIgnoredFiles) rgArgs.add("--no-ignore");
            List<String> lines = RipGrepUtil.run(rgArgs, cwdPath);
            if (!lines.isEmpty()) {
                return lines.stream().limit(MAX_INLINE_FILE_WALK).collect(Collectors.toList());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // fall through to Files.walk
        }

        // ── 3. Files.walk fallback (depth ≤ 2) ──────────────────────
        try {
            try (Stream<Path> walk = Files.walk(cwdPath, 2)) {
                return walk
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        String rel = cwdPath.relativize(p).toString();
                        return !Strings.CS.startsWith(rel, ".git") && !Strings.CS.contains(rel, "/.git")
                            && !Strings.CS.startsWith(rel, ".hg") && !Strings.CS.contains(rel, "/.hg")
                            && !Strings.CS.startsWith(rel, ".svn") && !Strings.CS.contains(rel, "/.svn")
                            && !Strings.CS.startsWith(rel, ".bzr") && !Strings.CS.contains(rel, "/.bzr")
                            && !Strings.CS.startsWith(rel, ".jj") && !Strings.CS.contains(rel, "/.jj")
                            && !Strings.CS.startsWith(rel, ".sl") && !Strings.CS.contains(rel, "/.sl")
                            && !Strings.CS.startsWith(rel, "node_modules") && !Strings.CS.contains(rel, "/node_modules")
                            && !Strings.CS.startsWith(rel, "target") && !Strings.CS.contains(rel, "/target");
                    })
                    .map(p -> cwdPath.relativize(p).toString())
                    .limit(MAX_INLINE_FILE_WALK)
                    .collect(Collectors.toList());
            }
        } catch (Exception _) {}

        return List.of();
    }
}
