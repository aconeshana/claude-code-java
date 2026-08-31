package com.claudecode.session;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;

/** Public SDK-facing session listing service. */
public final class SessionListingService {
    private static final int READ_BATCH_SIZE = 32;
    private final Path configHome;
    private final Predicate<String> builtInCommand;
    private final Function<String, List<String>> worktreePaths;

    public SessionListingService(Path configHome) { this(configHome, _ -> false); }
    public SessionListingService(Path configHome, Predicate<String> builtInCommand) {
        this(configHome, builtInCommand, SessionSearch::detectWorktreePaths);
    }
    SessionListingService(Path configHome, Predicate<String> builtInCommand,
                          Function<String, List<String>> worktreePaths) {
        this.configHome = configHome;
        this.builtInCommand = builtInCommand == null ? _ -> false : builtInCommand;
        this.worktreePaths = worktreePaths == null ? _ -> List.of() : worktreePaths;
    }

    public List<SessionInfo> listSessions(ListSessionsOptions requested) {
        ListSessionsOptions options = requested == null ? ListSessionsOptions.defaults() : requested;
        String rootDir = StringUtils.isBlank(options.dir())
            ? System.getProperty("user.dir") : options.dir().trim();
        SessionManager root = new SessionManager(configHome, rootDir);
        List<String> detected = options.effectiveIncludeWorktrees() && StringUtils.isNotBlank(options.dir())
            ? worktreePaths.apply(rootDir) : List.of();
        List<SessionCatalog.Candidate> candidates = SessionCatalog.sdkCandidates(root, options.dir(),
            options.effectiveIncludeWorktrees(), options.paginated(), detected);

        Map<String, SessionCatalog.Entry> unique = new LinkedHashMap<>();
        if (options.paginated()) {
            long required = options.effectiveLimit() == Integer.MAX_VALUE ? Long.MAX_VALUE
                : (long) options.effectiveOffset() + options.effectiveLimit();
            for (int start = 0; start < candidates.size() && unique.size() < required;
                    start += READ_BATCH_SIZE) {
                int end = Math.min(candidates.size(), start + READ_BATCH_SIZE);
                for (SessionCatalog.Entry entry : SessionCatalog.enrichSdkBatch(
                        candidates.subList(start, end), builtInCommand,
                        options.effectiveIncludeProgrammatic())) {
                    unique.putIfAbsent(entry.info().id(), entry);
                }
            }
        } else {
            List<SessionCatalog.Entry> enriched = new ArrayList<>();
            for (int start = 0; start < candidates.size(); start += READ_BATCH_SIZE) {
                int end = Math.min(candidates.size(), start + READ_BATCH_SIZE);
                enriched.addAll(SessionCatalog.enrichSdkBatch(candidates.subList(start, end),
                    builtInCommand, options.effectiveIncludeProgrammatic()));
            }
            enriched.sort(Comparator.comparingLong((SessionCatalog.Entry e) -> e.info().lastModified())
                .reversed().thenComparing(e -> e.info().id(), Comparator.reverseOrder()));
            for (SessionCatalog.Entry entry : enriched) unique.putIfAbsent(entry.info().id(), entry);
        }
        int offset = Math.min(options.effectiveOffset(), unique.size());
        int end = options.effectiveLimit() == Integer.MAX_VALUE ? unique.size()
            : Math.min(unique.size(), offset + options.effectiveLimit());
        return new ArrayList<>(unique.values()).subList(offset, end).stream()
            .map(SessionListingService::sdkProjection).toList();
    }

    private static SessionInfo sdkProjection(SessionCatalog.Entry entry) {
        SessionInfo info = entry.info();
        String title = StringUtils.isNotBlank(info.customTitle()) ? info.customTitle() : entry.aiTitle();
        return new SessionInfo(info.id(), info.lastModified(), info.createdAt(), info.messageCount(),
            info.summary(), info.gitBranch(), info.cwd(), info.tag(), info.fileSize(), title,
            info.firstPrompt());
    }
}
