package com.claudecode.session;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Worktree-aware session lookup shared by commands and the interactive picker.
 */
public final class SessionSearch {

    private final Supplier<List<SessionManager>> managersSupplier;
    private final SessionManager rootManager;
    private final Predicate<String> builtInCommand;

    /** Production search rooted at the standard Claude home. */
    public SessionSearch(String cwd) {
        this(ClaudePaths.CLAUDE_HOME, cwd, () -> detectWorktreePaths(cwd), _ -> false);
    }

    public SessionSearch(String cwd, Predicate<String> builtInCommand) {
        this(ClaudePaths.CLAUDE_HOME, cwd, () -> detectWorktreePaths(cwd), builtInCommand);
    }

    /** Search with an explicit Claude home, useful for isolated callers/tests. */
    public SessionSearch(Path baseDir, String cwd) {
        this(baseDir, cwd, () -> detectWorktreePaths(cwd), _ -> false);
    }

    /** Current-project-only compatibility seam. */
    public SessionSearch(SessionManager manager) {
        this.managersSupplier = () -> List.of(manager);
        this.rootManager = manager;
        this.builtInCommand = _ -> false;
    }

    public SessionSearch(Path baseDir, String cwd, Supplier<List<String>> worktreePathsSupplier) {
        this(baseDir, cwd, worktreePathsSupplier, _ -> false);
    }

    public SessionSearch(Path baseDir, String cwd, Supplier<List<String>> worktreePathsSupplier,
                         Predicate<String> builtInCommand) {
        this.rootManager = new SessionManager(baseDir, cwd);
        this.builtInCommand = builtInCommand == null ? _ -> false : builtInCommand;
        this.managersSupplier = () -> {
            List<String> detected;
            try {
                detected = worktreePathsSupplier == null ? List.of() : worktreePathsSupplier.get();
            } catch (Exception _) {
                detected = List.of();
            }
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            if (StringUtils.isNotBlank(cwd)) paths.add(cwd);
            if (detected != null) {
                detected.stream().filter(StringUtils::isNotBlank).forEach(paths::add);
            }
            return paths.stream().map(path -> new SessionManager(baseDir, path)).toList();
        };
    }

    /** All resumable same-repo sessions, newest first and deduplicated by id. */
    public List<LocatedSession> listSessions() {
        return progressiveSessions().loadMore(Integer.MAX_VALUE);
    }

    /** Stat-only same-repository listing enriched incrementally by the picker. */
    public ProgressiveListing progressiveSessions() {
        return new ProgressiveListing(SessionCatalog.forManagers(managers(), builtInCommand));
    }

    /** Stat-only all-project listing with an independent cursor. */
    public ProgressiveListing progressiveAllProjects() {
        return new ProgressiveListing(SessionCatalog.forAllProjects(rootManager, builtInCommand));
    }

    /** Exact UUID lookup, including a direct-file fallback for non-enriched logs. */
    public Optional<LocatedSession> findExactSessionId(String sessionId) {
        Optional<LocatedSession> listed = listSessions().stream()
            .filter(s -> s.id().equalsIgnoreCase(sessionId))
            .findFirst();
        if (listed.isPresent()) return listed;

        LocatedSession newest = null;
        for (SessionManager manager : managers()) {
            Path file = manager.getSessionFile(sessionId);
            if (!Files.isRegularFile(file)) continue;
            try {
                long modified = FileUtils.modificationTimeMillis(file);
                SessionInfo synthetic = new SessionInfo(
                    sessionId, modified, Instant.ofEpochMilli(modified), 0,
                    manager.readCustomTitle(sessionId), null,
                    manager.readSessionCwd(sessionId).orElse(manager.projectPath()), null);
                LocatedSession candidate = located(manager, synthetic,
                    manager.readCustomTitle(sessionId));
                newest = newest == null ? candidate : newer(newest, candidate);
            } catch (IOException _) {
                // Unreadable candidate: keep searching other worktrees.
            }
        }
        return Optional.ofNullable(newest);
    }

    /** Exact, case-insensitive custom-title search, newest first. */
    public List<LocatedSession> searchExactCustomTitle(String query) {
        return searchCustomTitle(query, true, 0);
    }

    /** Case-insensitive exact/contains title search across worktrees, aliases and fallback dirs. */
    public List<LocatedSession> searchCustomTitle(String query, boolean exact, int limit) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) return List.of();
        List<SessionCatalog.Entry> matches = SessionCatalog.searchEntries(managers(), builtInCommand)
            .stream().filter(entry -> {
                String title = StringUtils.isNotBlank(entry.info().customTitle())
                    ? entry.info().customTitle() : entry.aiTitle();
                if (title == null) return false;
                String candidate = title.toLowerCase(Locale.ROOT).trim();
                return exact ? candidate.equals(normalized) : Strings.CS.contains(candidate, normalized);
            }).sorted(Comparator.comparingLong((SessionCatalog.Entry entry) ->
                entry.info().lastModified()).reversed()
                .thenComparing(entry -> entry.info().id(), Comparator.reverseOrder())).toList();
        LinkedHashMap<String, LocatedSession> unique = new LinkedHashMap<>();
        for (SessionCatalog.Entry entry : matches) unique.putIfAbsent(entry.info().id(),
            new LocatedSession(entry.info(), entry.transcript(), entry.projectPath(),
                entry.info().customTitle(), entry.aiTitle(), entry.alias()));
        return unique.values().stream().limit(limit <= 0 ? Long.MAX_VALUE : limit).toList();
    }

    private List<SessionManager> managers() {
        List<SessionManager> managers = managersSupplier.get();
        return managers == null ? List.of() : managers;
    }

    private static LocatedSession located(SessionManager manager, SessionInfo info,
                                           String customTitle) {
        return new LocatedSession(info, manager.getSessionFile(info.id()),
            manager.projectPath(), customTitle, null, false);
    }

    private static LocatedSession newer(LocatedSession left, LocatedSession right) {
        return right.lastModified() > left.lastModified() ? right : left;
    }

    static List<String> detectWorktreePaths(String cwd) {
        if (StringUtils.isBlank(cwd)) return List.of();
        Process process = null;
        try {
            process = new ProcessBuilder("git", "worktree", "list", "--porcelain")
                .directory(new File(cwd))
                .redirectErrorStream(true)
                .start();
            try { process.getOutputStream().close(); } catch (IOException _) {}
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0) return List.of();
            String output = new String(process.getInputStream().readAllBytes());
            List<String> paths = new ArrayList<>();
            output.lines()
                .filter(line -> Strings.CS.startsWith(line, "worktree "))
                .map(line -> line.substring("worktree ".length()).strip())
                .filter(path -> !StringUtils.isBlank(path))
                .forEach(paths::add);
            paths.sort((a, b) -> {
                boolean aCurrent = cwd.equals(a) || Strings.CS.startsWith(cwd, a + File.separator);
                boolean bCurrent = cwd.equals(b) || Strings.CS.startsWith(cwd, b + File.separator);
                if (aCurrent != bCurrent) return aCurrent ? -1 : 1;
                return a.compareTo(b);
            });
            return List.copyOf(paths);
        } catch (Exception _) {
            if (process != null) process.destroyForcibly();
            return List.of();
        }
    }

    public record LocatedSession(
        SessionInfo info,
        Path sessionFile,
        String cwd,
        String customTitle,
        String aiTitle,
        boolean isAlias
    ) {
        public String id() { return info.id(); }
        public long lastModified() { return info.lastModified(); }
    }

    public static final class ProgressiveListing {
        private final SessionCatalog.Listing delegate;

        private ProgressiveListing(SessionCatalog.Listing delegate) {
            this.delegate = delegate;
        }

        public synchronized List<LocatedSession> loadMore(int count) {
            return delegate.loadMore(count).stream()
                .map(entry -> new LocatedSession(entry.info(), entry.transcript(),
                    entry.projectPath(), entry.info().customTitle(), entry.aiTitle(), entry.alias()))
                .toList();
        }

        public synchronized boolean hasMore() {
            return delegate.hasMore();
        }
    }
}
