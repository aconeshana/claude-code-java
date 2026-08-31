package com.claudecode.cli;

import com.claudecode.commands.session.SessionCommandPort;
import com.claudecode.core.message.Message;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.SessionForkService;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionSearch;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TranscriptMessageCleaner;
import com.claudecode.session.stats.ClaudeCodeStats;
import com.claudecode.session.stats.StatsAggregator;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * CLI leaf adapter for command-facing session persistence use cases.
 */
final class CliSessionCommandAdapter implements SessionCommandPort {
    private final String cwd;
    private final SessionManager manager;
    private final SessionStorage storage;
    private final SessionSearch search;
    private final SessionForkService forks;

    CliSessionCommandAdapter(String cwd) {
        this(new SessionManager(cwd), new SessionStorage(), cwd);
    }

    private CliSessionCommandAdapter(SessionManager manager, SessionStorage storage, String cwd) {
        this.manager = manager;
        this.storage = storage;
        this.cwd = cwd;
        this.search = new SessionSearch(manager);
        this.forks = new SessionForkService();
    }

    @Override public List<LocatedSession> listSessions() {
        return search.listSessions().stream().map(CliSessionCommandAdapter::map).toList();
    }
    @Override public Optional<LocatedSession> findExactSessionId(String id) {
        return search.findExactSessionId(id).map(CliSessionCommandAdapter::map);
    }
    @Override public List<LocatedSession> searchExactCustomTitle(String title) {
        return search.searchExactCustomTitle(title).stream().map(CliSessionCommandAdapter::map).toList();
    }
    @Override public List<Message> readMessages(Path transcript) {
        return storage.loadTranscriptFromFile(transcript).messages();
    }
    @Override public String createSession() { return manager.createSession(); }
    @Override public Path transcriptPath(String sessionId) { return manager.getSessionFile(sessionId); }
    @Override public boolean hasTranscript(String sessionId) {
        Path transcript = manager.getSessionFile(sessionId);
        try { return Files.isRegularFile(transcript) && Files.size(transcript) > 0; }
        catch (Exception _) { return false; }
    }
    @Override public ForkResult fork(String sourceSessionId, String forkSessionId) {
        Path source = manager.getSessionFile(sourceSessionId);
        Path target = manager.getSessionFile(forkSessionId);
        try {
            var result = forks.fork(source, target, sourceSessionId, forkSessionId);
            return new ForkResult(ForkResult.Status.SUCCESS, result.messageCount(),
                storage.loadTranscriptFromFile(target).messages());
        } catch (SessionForkService.NoMessagesToForkException _) {
            return new ForkResult(ForkResult.Status.NO_MESSAGES, 0, List.of());
        } catch (SessionForkService.NoConversationToForkException _) {
            return new ForkResult(ForkResult.Status.NO_CONVERSATION, 0, List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
    @Override public void appendMessages(String sessionId, List<Message> messages) {
        Path target = manager.getSessionFile(sessionId);
        for (Message message : TranscriptMessageCleaner.cleanMessagesForLogging(messages)) {
            storage.appendMessage(target, message, sessionId, cwd, false, null);
        }
    }
    @Override public void saveCustomTitle(String sessionId, String title) {
        append(sessionId, "custom-title", "customTitle", title);
    }
    @Override public void saveAgentName(String sessionId, String name) {
        append(sessionId, "agent-name", "agentName", name);
    }
    @Override public void saveAgentColor(String sessionId, String color) {
        append(sessionId, "agent-color", "agentColor", color);
    }
    @Override public void saveTag(String sessionId, String tag) { append(sessionId, "tag", "tag", tag); }
    @Override public String readTag(String sessionId) {
        return storage.scanMetadata(manager.getSessionFile(sessionId)).tag().orElse(null);
    }
    @Override public String readCustomTitle(String sessionId) { return manager.readCustomTitle(sessionId); }
    @Override public Path toolResultsDirectory(String sessionId) { return manager.getToolResultsDir(sessionId); }
    @Override public void recordSessionAlias(Path targetDirectory, String activeSessionId) {
        Path source = activeSessionId == null ? manager.getSessionFile("placeholder").getParent()
            : search.findExactSessionId(activeSessionId).map(SessionSearch.LocatedSession::sessionFile)
                .map(Path::getParent).orElse(manager.getSessionFile(activeSessionId).getParent());
        manager.recordSessionAlias(targetDirectory, source);
    }
    @Override public StatsSnapshot stats() {
        ClaudeCodeStats stats = new StatsAggregator().aggregateAll();
        Map<String, ModelUsage> models = stats.modelUsage().entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> new ModelUsage(entry.getValue().inputTokens(), entry.getValue().outputTokens())));
        LocalDate peak = stats.peakActivityDay() == null ? null : LocalDate.parse(stats.peakActivityDay());
        return new StatsSnapshot(stats.totalSessions(), models,
            stats.longestSession() == null ? null : new SessionDuration(stats.longestSession().duration()),
            Math.toIntExact(stats.activeDays()), Math.toIntExact(stats.totalDays()),
            new Streaks(Math.toIntExact(stats.streaks().longestStreak()),
                Math.toIntExact(stats.streaks().currentStreak())), peak);
    }

    private void append(String sessionId, String type, String key, String value) {
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", type);
        entry.put(key, value);
        entry.put("sessionId", sessionId);
        storage.appendCustomEntry(manager.getSessionFile(sessionId), entry);
    }
    private static LocatedSession map(SessionSearch.LocatedSession located) {
        return new LocatedSession(located.id(), located.sessionFile(), located.cwd(), located.customTitle());
    }
}
