package com.claudecode.tools.files;

import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Workspace file enumeration and {@code @}-completion ranking, shared by the
 * TUI's typeahead and the gateway's {@code GET /api/file-suggestions} endpoint.
 *
 * <p>The scoring and enumeration live here rather than in either consumer so
 * the two surfaces cannot drift: the TUI's {@code FileSuggestionService}
 * delegates its ranking and file collection here, and the CLI composition
 * root implements {@code GatewayFileSuggestionsPort} over {@link #suggest}.
 * The fallback chain matches the TUI's established behaviour
 * (git ls-files → nested-repo aggregation → {@code rg --files} →
 * {@code Files.walk}).
 *
 * <p>Candidates carry paths only; nothing here reads file contents. Selecting
 * a candidate inserts an {@code @path} token whose submission-time parsing
 * ({@code AtMentionedFilesProvider}) owns the actual read.
 */
public final class WorkspaceFileSearch {

    private static final int MAX_INLINE_FILE_WALK = 500;
    private static final int GIT_LS_TIMEOUT_SECS = 2;
    private static final int MAX_SCANNED_ENTRIES = 200;

    /** One completion candidate: a path (rendered form) and whether it is a directory. */
    public record Candidate(String path, boolean directory) {}

    /** Depth-1 entries of {@code cwd} when the query is blank; else the ranked rows. */
    public List<Candidate> suggest(String cwd, String query, int limit) {
        List<Candidate> rows = queryRows(cwd, query);
        return limit > 0 ? rows.stream().limit(limit).toList() : rows;
    }

    private List<Candidate> queryRows(String cwd, String query) {
        Path path = Path.of(cwd);
        if (StringUtils.isBlank(query)) {
            return listDirectory(path, "");
        }
        if (isPathLike(query)) {
            return directoryEntries(query);
        }
        String lowered = query.toLowerCase(Locale.ROOT);
        List<Map.Entry<String, Integer>> matches = new ArrayList<>();
        for (String file : collectFiles(path, true)) {
            int score = subsequenceScore(lowered, file);
            if (score >= 0) matches.add(Map.entry(file, score));
        }
        matches.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
            .reversed());
        return matches.stream().map(e -> new Candidate(e.getKey(), false)).toList();
    }

    /** True for a query rooted at home, the filesystem root, or a relative path. */
    public static boolean isPathLike(String query) {
        return Strings.CS.equals(query, "~")
            || Strings.CS.startsWith(query, "~/")
            || Strings.CS.startsWith(query, "/")
            || Strings.CS.startsWith(query, "./")
            || Strings.CS.startsWith(query, "../")
            || Strings.CS.contains(query, "/");
    }

    /**
     * Entries of the directory named by a path-like query, prefix-filtered.
     *
     * <p>Each row carries the <em>full</em> path, not the bare entry name: the
     * client inserts the row verbatim as an {@code @path} mention, so a bare
     * {@code brand/} would resolve against the workspace root instead of the
     * walked directory and the model would never see the file. The prefix is
     * taken from the query as typed, which keeps {@code ~/}, {@code ./} and
     * absolute forms round-tripping through the backend's own path handling.
     */
    private List<Candidate> directoryEntries(String query) {
        String home = System.getProperty("user.home", "");
        String resolved = Strings.CS.equals(query, "~")
            ? home
            : Strings.CS.startsWith(query, "~/") ? home + query.substring(1) : query;
        int lastSep = resolved.lastIndexOf('/');
        String basePart = lastSep >= 0 ? resolved.substring(0, lastSep + 1) : resolved;
        String prefix = lastSep >= 0
            ? resolved.substring(lastSep + 1).toLowerCase(Locale.ROOT) : "";
        // The query's own directory part, still spelled the way the user typed
        // it (`~/x/`, `./x/`, `/abs/x/` or `rel/x/`).
        int querySep = query.lastIndexOf('/');
        String displayPrefix = querySep >= 0 ? query.substring(0, querySep + 1) : "";
        Path base;
        try {
            base = Path.of(basePart);
        } catch (RuntimeException _) {
            return List.of();
        }
        return listDirectory(base, prefix).stream()
            .map(row -> new Candidate(displayPrefix + row.path(), row.directory()))
            .toList();
    }

    /** Depth-1 entries of {@code base} whose rendered name starts with {@code prefixLower}. */
    private static List<Candidate> listDirectory(Path base, String prefixLower) {
        try (Stream<Path> stream = Files.list(base)) {
            return stream
                .map(path -> {
                    String name = path.getFileName().toString();
                    boolean directory = Files.isDirectory(path);
                    return new Candidate(directory ? name + "/" : name, directory);
                })
                .filter(row -> Strings.CI.startsWith(
                    row.path().toLowerCase(Locale.ROOT), prefixLower))
                .sorted((left, right) -> left.directory() != right.directory()
                    ? (left.directory() ? -1 : 1)
                    : left.path().compareTo(right.path()))
                .limit(MAX_SCANNED_ENTRIES)
                .toList();
        } catch (Exception _) {
            return List.of();
        }
    }

    /**
     * Subsequence fuzzy match score — pure-JVM equivalent of nucleo's
     * Smith-Waterman variant, shared by the TUI typeahead and the gateway's
     * {@code @}-completion endpoint. Returns -1 if {@code query} is not a
     * subsequence of {@code text}.
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
        String queryBare = Strings.CS.endsWith(query, "/")
            ? query.substring(0, query.length() - 1) : query;
        int firstSegBonus = firstSeg.equalsIgnoreCase(queryBare) ? 20 : 0;
        int slashIdx = text.lastIndexOf('/');
        String baseName = slashIdx >= 0 ? text.substring(slashIdx + 1) : text;
        int filenameBonus = Character.toLowerCase(baseName.charAt(0)) == query.charAt(0)
            && Strings.CI.startsWith(baseName, query) ? 5 : 0;
        int hiddenPenalty = Strings.CS.contains(text, "/.") ? 1 : 0;
        return (score + firstSegBonus + filenameBonus - hiddenPenalty) * 1000 - firstMatchPos;
    }

    /**
     * Collect raw workspace file paths. Tries three fallbacks in order:
     * git ls-files → nested-repo aggregation → rg --files → Files.walk.
     */
    public static List<String> collectFiles(Path cwd, boolean respectIgnoredFiles) {
        if (respectIgnoredFiles) {
            ProcessResult gitFiles = ProcessRunner.run(
                List.of("git", "-c", "core.quotepath=false", "ls-files",
                    "--recurse-submodules"),
                cwd, Duration.ofSeconds(GIT_LS_TIMEOUT_SECS));
            if (gitFiles.succeeded() && !StringUtils.isBlank(gitFiles.stdout())) {
                return gitFiles.stdoutLines();
            }
            List<String> aggregated = collectFromNestedGitRepos(cwd);
            if (!aggregated.isEmpty()) return aggregated;
        }

        // RipGrepUtil handles stdin-close, the closed-pipe no-PATH search, symlink cwd via
        // toRealPath(), EAGAIN retry, and timeout kill — none of which an inline launcher would.
        try {
            List<String> rgArgs = new ArrayList<>(List.of(
                "rg", "--files", "--follow", "--hidden",
                "--glob", "!.git/", "--glob", "!.svn/",
                "--glob", "!.hg/", "--glob", "!.bzr/",
                "--glob", "!.jj/", "--glob", "!.sl/",
                "--glob", "!node_modules/", "--glob", "!target/"));
            if (!respectIgnoredFiles) rgArgs.add("--no-ignore");
            List<String> lines = RipGrepUtil.run(rgArgs, cwd);
            if (!lines.isEmpty()) {
                return lines.stream().limit(MAX_INLINE_FILE_WALK).collect(Collectors.toList());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // fall through to Files.walk
        }

        try {
            try (Stream<Path> walk = Files.walk(cwd, 2)) {
                return walk
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> !isExcluded(cwd.relativize(p).toString()))
                    .map(p -> cwd.relativize(p).toString())
                    .limit(MAX_INLINE_FILE_WALK)
                    .collect(Collectors.toList());
            }
        } catch (Exception _) {
            return List.of();
        }
    }

    private static boolean isExcluded(String rel) {
        for (String dir : List.of(".git", ".hg", ".svn", ".bzr", ".jj", ".sl",
                "node_modules", "target")) {
            if (Strings.CS.startsWith(rel, dir) || Strings.CS.contains(rel, "/" + dir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * When {@code base} is not itself a git repo, scan depth-1 and depth-2
     * subdirectories for {@code .git} roots and aggregate tracked files.
     * Each path is prefixed with the relative path from {@code base}.
     * Parallelised via virtual threads with a 3s wall-clock cap.
     */
    private static List<String> collectFromNestedGitRepos(Path base) {
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
}