package com.claudecode.cli;

import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.Message;
import com.claudecode.session.*;
import com.claudecode.session.stats.ClaudeCodeStats;
import com.claudecode.session.stats.StatsAggregator;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.tools.worktree.WorktreeSession;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import com.claudecode.core.serialization.JsonUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * CLI leaf adapter from the UI-owned session boundary to JSONL infrastructure.
 */
public final class CliInteractiveSessionAdapter implements InteractiveSessionPort {
    private final StatsAggregator stats;
    private final Predicate<String> builtInCommand;

    public CliInteractiveSessionAdapter() {
        this(new StatsAggregator(), _ -> false);
    }

    public CliInteractiveSessionAdapter(StatsAggregator stats) {
        this(stats, _ -> false);
    }

    public CliInteractiveSessionAdapter(StatsAggregator stats, Predicate<String> builtInCommand) {
        this.stats = stats;
        this.builtInCommand = builtInCommand == null ? _ -> false : builtInCommand;
    }

    @Override
    public List<SessionEntry> recentSessions(String cwd, int limit) {
        SessionManager manager = new SessionManager(cwd);
        return manager.listSessions(limit).stream()
            .map(info -> entry(manager, info, info.customTitle()))
            .toList();
    }

    @Override
    public List<SessionEntry> sameRepositorySessions(String cwd) {
        SessionListing listing = sameRepositorySessionListing(cwd);
        return listing.loadMore(Integer.MAX_VALUE);
    }

    @Override
    public List<SessionEntry> allProjectSessions(String cwd, int limit) {
        return allProjectSessionListing(cwd).loadMore(limit);
    }

    @Override
    public SessionListing sameRepositorySessionListing(String cwd) {
        return listing(new SessionSearch(cwd, builtInCommand).progressiveSessions());
    }

    @Override
    public SessionListing allProjectSessionListing(String cwd) {
        return listing(new SessionSearch(cwd, builtInCommand).progressiveAllProjects());
    }

    @Override public Optional<SessionEntry> findExactSession(String cwd, String sessionId) {
        return new SessionSearch(cwd).findExactSessionId(sessionId).map(this::entry);
    }

    @Override public List<Message> readMessages(Path transcript) {
        // Session preview is a tolerant file-order projection, not a resume
        // operation. It must still display older/partial transcripts that do
        // not carry a complete parentUuid conversation graph; the dialog runs
        // this read on its preview worker and applies retract filtering itself.
        return new SessionStorage().readMessages(transcript);
    }

    @Override public List<Message> readAgentMessages(Path transcript, String agentId) {
        return new SessionStorage().getAgentTranscript(transcript, agentId)
            .map(TranscriptLoader.AgentTranscript::messages)
            .orElseGet(List::of);
    }

    @Override public List<Message> readAgentSidechainMessages(Path transcript, String agentId) {
        return new SessionStorage().getAgentTranscript(transcript, agentId)
            .map(TranscriptLoader.AgentTranscript::sidechainMessages)
            .orElseGet(List::of);
    }

    @Override public MetadataSnapshot scanMetadata(Path transcript) {
        var metadata = transcript == null
            ? SessionStorage.MetadataSnapshot.empty() : new SessionStorage().scanMetadata(transcript);
        return new MetadataSnapshot(metadata.customTitle().orElse(null),
            metadata.agentName().orElse(null), metadata.agentColor().orElse(null),
            metadata.tag().orElse(null));
    }

    @Override public boolean deleteSession(SessionEntry session, String fallbackCwd) {
        String cwd = StringUtils.isBlank(session.cwd()) ? fallbackCwd : session.cwd();
        return new SessionManager(cwd).deleteSessionPermanently(session.id());
    }

    @Override public void saveCustomTitle(SessionEntry session, String title) {
        var node = JsonUtils.getMapper().createObjectNode();
        node.put("type", "custom-title");
        node.put("customTitle", title);
        node.put("sessionId", session.id());
        new SessionStorage().appendCustomEntry(session.transcriptPath(), node);
    }

    @Override public Path sessionFile(String cwd, String sessionId) {
        return new SessionManager(cwd).getSessionFile(sessionId);
    }

    @Override public Path agentTranscriptPath(String cwd, String sessionId, String agentId) {
        return new SessionManager(cwd).getAgentTranscriptPath(sessionId, agentId);
    }

    @Override public Path toolResultsDirectory(String cwd, String sessionId) {
        return new SessionManager(cwd).getToolResultsDir(sessionId);
    }

    @Override public Path workflowRunPath(String cwd, String sessionId, String runId) {
        return new SessionManager(cwd).getWorkflowRunPath(sessionId, runId);
    }

    @Override public String readCustomTitle(String cwd, String sessionId) {
        return new SessionManager(cwd).readCustomTitle(sessionId);
    }

    @Override public String parentSessionId(String cwd, String sessionId) {
        return new SessionManager(cwd).readParentSessionId(sessionId);
    }

    @Override public void appendParentSession(String cwd, String sessionId,
                                               String parentSessionId, String reason) {
        new SessionManager(cwd).appendParentSession(sessionId, parentSessionId, reason);
    }

    @Override public void reAppendSessionMetadata(String cwd, String sessionId) {
        new SessionManager(cwd).reAppendSessionMetadata(sessionId);
    }

    @Override public void releaseTranscriptState(TranscriptSink sink, String sessionId,
                                                  long timeoutMillis) {
        if (sink instanceof TranscriptRecorder recorder) {
            recorder.flushCachedLastPrompt(sessionId);
            recorder.releaseSessionState(sessionId, timeoutMillis);
        }
    }

    @Override public StatsSnapshot aggregateStats(StatsDateRange range) {
        StatsAggregator.StatsDateRange sessionRange = switch (range) {
            case SEVEN_DAYS -> StatsAggregator.StatsDateRange.SEVEN_DAYS;
            case THIRTY_DAYS -> StatsAggregator.StatsDateRange.THIRTY_DAYS;
            case ALL -> StatsAggregator.StatsDateRange.ALL;
        };
        return stats(stats.aggregateForRange(sessionRange));
    }

    @Override public void persistWorktreeExit(WorktreeSession session) {
        if (session == null || session.sessionId() == null || session.originalCwd() == null) return;
        WorktreeService.persistWorktreeState(new SessionStorage(),
            new SessionManager(session.originalCwd()).getSessionFile(session.sessionId()),
            session.sessionId(), null);
    }

    private SessionEntry entry(SessionSearch.LocatedSession located) {
        SessionInfo info = located.info();
        return entry(info, located.sessionFile(), located.cwd(), located.customTitle(), located.isAlias());
    }

    private static SessionEntry entry(SessionManager manager, SessionInfo info, String customTitle) {
        return entry(info, manager.getSessionFile(info.id()), manager.projectPath(), customTitle, false);
    }

    private static SessionEntry entry(SessionInfo info, Path file, String projectPath,
                                      String customTitle, boolean alias) {
        return new SessionEntry(info.id(), info.lastModified(), info.createdAt(), info.messageCount(),
            info.summary(), info.gitBranch(), info.cwd(), info.tag(), file, projectPath,
            customTitle, info.fileSize(), alias);
    }

    private SessionListing listing(SessionSearch.ProgressiveListing source) {
        return new SessionListing() {
            @Override public synchronized List<SessionEntry> loadMore(int count) {
                return source.loadMore(count).stream()
                    .map(CliInteractiveSessionAdapter.this::entry).toList();
            }

            @Override public synchronized boolean hasMore() {
                return source.hasMore();
            }
        };
    }

    private static StatsSnapshot stats(ClaudeCodeStats source) {
        List<DailyActivity> daily = source.dailyActivity().stream()
            .map(v -> new DailyActivity(v.date(), v.messageCount(), v.sessionCount(), v.toolCallCount()))
            .toList();
        List<DailyModelTokens> tokens = source.dailyModelTokens().stream()
            .map(v -> new DailyModelTokens(v.date(), Map.copyOf(v.tokensByModel()))).toList();
        Map<String, ModelUsage> models = new LinkedHashMap<>();
        source.modelUsage().forEach((name, v) -> models.put(name,
            new ModelUsage(v.inputTokens(), v.outputTokens(), v.cacheReadInputTokens(),
                v.cacheCreationInputTokens(), v.webSearchRequests(), v.costUSD(),
                v.contextWindow(), v.maxOutputTokens())));
        var streak = source.streaks();
        StreakInfo streakInfo = new StreakInfo(streak.currentStreak(), streak.longestStreak(),
            streak.currentStreakStart(), streak.longestStreakStart(), streak.longestStreakEnd());
        var longest = source.longestSession();
        SessionStats longestSession = longest == null ? null
            : new SessionStats(longest.sessionId(), longest.duration(), longest.messageCount(), longest.timestamp());
        return new StatsSnapshot(source.totalSessions(), source.totalMessages(), source.totalDays(),
            source.activeDays(), streakInfo, daily, tokens, longestSession, Map.copyOf(models),
            source.firstSessionDate(), source.lastSessionDate(), source.peakActivityDay(),
            source.peakActivityHour(), source.totalSpeculationTimeSavedMs());
    }
}
