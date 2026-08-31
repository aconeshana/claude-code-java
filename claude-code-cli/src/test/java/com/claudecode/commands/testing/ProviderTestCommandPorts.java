package com.claudecode.commands.testing;

import com.claudecode.commands.permissions.PermissionCommandPort;

import com.claudecode.commands.CommandApplicationPorts;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.session.SessionCommandPort;
import com.claudecode.commands.tooling.ToolingCommandPorts;
import com.claudecode.core.message.Message;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.WorkingDirectoryPaths;
import com.claudecode.session.SessionForkService;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionSearch;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.stats.ClaudeCodeStats;
import com.claudecode.session.stats.StatsAggregator;
import com.claudecode.tools.tasks.TaskRegistry;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


public final class ProviderTestCommandPorts {
    private ProviderTestCommandPorts() { }

    public static PermissionCommandPort permissions(PermissionGate gate) {
        if (gate == null) return PermissionCommandPort.none();
        return new PermissionCommandPort() {
            @Override public Snapshot snapshot() {
                var context = gate.currentContext();
                return new Snapshot(true, context.mode().name(),
                    rules(context.rules(), PermissionBehavior.ALLOW),
                    rules(context.rules(), PermissionBehavior.ASK),
                    rules(context.rules(), PermissionBehavior.DENY),
                    List.copyOf(WorkingDirectoryPaths.allWorkingDirectories(context)),
                    List.copyOf(context.additionalDirs().keySet()));
            }
            @Override public boolean isPlanMode() { return gate.currentMode() == PermissionMode.PLAN; }
            @Override public void enterPlanMode() { gate.setMode(PermissionMode.PLAN); }
            @Override public void addDirectory(Path directory) { gate.addDirectories(List.of(directory)); }
        };
    }

    public static SessionCommandPort sessions(SessionManager manager, SessionStorage storage) {
        return sessions(manager, storage, new StatsAggregator());
    }

    public static SessionCommandPort sessions(SessionManager manager, SessionStorage storage,
                                               StatsAggregator stats) {
        return sessions(manager, storage, stats, new SessionSearch(manager));
    }

    public static SessionCommandPort sessions(SessionManager manager, SessionStorage storage,
                                               SessionSearch search) {
        return sessions(manager, storage, new StatsAggregator(), search);
    }

    private static SessionCommandPort sessions(SessionManager manager, SessionStorage storage,
                                                StatsAggregator stats, SessionSearch search) {
        SessionForkService forks = new SessionForkService();
        return new SessionCommandPort() {
            @Override public List<LocatedSession> listSessions() {
                return search.listSessions().stream().map(ProviderTestCommandPorts::located).toList();
            }
            @Override public Optional<LocatedSession> findExactSessionId(String id) {
                return search.findExactSessionId(id).map(ProviderTestCommandPorts::located);
            }
            @Override public List<LocatedSession> searchExactCustomTitle(String title) {
                return search.searchExactCustomTitle(title).stream().map(ProviderTestCommandPorts::located).toList();
            }
            @Override public List<Message> readMessages(Path transcript) { return storage.readMessages(transcript); }
            @Override public String createSession() { return manager.createSession(); }
            @Override public Path transcriptPath(String id) { return manager.getSessionFile(id); }
            @Override public boolean hasTranscript(String id) {
                try { return Files.isRegularFile(manager.getSessionFile(id))
                    && Files.size(manager.getSessionFile(id)) > 0; }
                catch (Exception _) { return false; }
            }
            @Override public ForkResult fork(String sourceId, String targetId) {
                try {
                    var result = forks.fork(manager.getSessionFile(sourceId), manager.getSessionFile(targetId),
                        sourceId, targetId);
                    return new ForkResult(ForkResult.Status.SUCCESS, result.messageCount(),
                        storage.readMessages(manager.getSessionFile(targetId)));
                } catch (SessionForkService.NoMessagesToForkException _) {
                    return new ForkResult(ForkResult.Status.NO_MESSAGES, 0, List.of());
                } catch (SessionForkService.NoConversationToForkException _) {
                    return new ForkResult(ForkResult.Status.NO_CONVERSATION, 0, List.of());
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
            @Override public void appendMessages(String sessionId, List<Message> messages) {
                for (Message message : messages) {
                    storage.appendMessage(manager.getSessionFile(sessionId), message);
                }
            }
            @Override public void saveCustomTitle(String id, String value) { append(id, "custom-title", "customTitle", value); }
            @Override public void saveAgentName(String id, String value) { append(id, "agent-name", "agentName", value); }
            @Override public void saveAgentColor(String id, String value) { append(id, "agent-color", "agentColor", value); }
            @Override public void saveTag(String id, String value) { append(id, "tag", "tag", value); }
            @Override public String readTag(String id) {
                return storage.scanMetadata(manager.getSessionFile(id)).tag().orElse(null);
            }
            @Override public String readCustomTitle(String id) { return manager.readCustomTitle(id); }
            @Override public Path toolResultsDirectory(String id) { return manager.getToolResultsDir(id); }
            @Override public void recordSessionAlias(Path targetDirectory, String activeSessionId) {
                manager.recordSessionAlias(targetDirectory, manager.getSessionFile(activeSessionId).getParent());
            }
            @Override public StatsSnapshot stats() { return ProviderTestCommandPorts.stats(stats); }
            private void append(String id, String type, String key, String value) {
                ObjectNode entry = JsonUtils.getMapper().createObjectNode();
                entry.put("type", type); entry.put(key, value); entry.put("sessionId", id);
                storage.appendCustomEntry(manager.getSessionFile(id), entry);
            }
        };
    }

    public static CommandContext withSessions(CommandContext context, SessionManager manager,
                                              SessionStorage storage) {
        return withSessions(context, sessions(manager, storage));
    }

    public static CommandContext withSessions(CommandContext context, SessionCommandPort sessions) {
        CommandApplicationPorts app = context.application();
        return new CommandContext(context.session(), new CommandApplicationPorts(
            app.doctor(), app.dream(), app.plugins(), app.insights(), app.settings(), app.mcp(),
            app.permissions(), sessions, app.tooling()), context.presentation());
    }

    public static CommandContext withTooling(CommandContext context, ToolingCommandPorts tooling) {
        CommandApplicationPorts app = context.application();
        return new CommandContext(context.session(), new CommandApplicationPorts(
            app.doctor(), app.dream(), app.plugins(), app.insights(), app.settings(), app.mcp(),
            app.permissions(), app.sessions(), tooling), context.presentation());
    }

    public static ToolingCommandPorts tasks(TaskRegistry registry) {
        ToolingCommandPorts none = ToolingCommandPorts.none();
        return new ToolingCommandPorts(none.resources(), none.plans(), () -> registry.store().list().stream()
            .map(task -> new ToolingCommandPorts.Tasks.Snapshot(task.id(), task.type().name(),
                task.description(), ToolingCommandPorts.Tasks.Status.valueOf(task.status().name()),
                task.startTime())).toList(), none.skillAttribution(),
            none.collaboration(), none.sandbox());
    }

    public static ToolingCommandPorts markdownResources() {
        ToolingCommandPorts none = ToolingCommandPorts.none();
        return new ToolingCommandPorts(directory -> {
            if (!Files.isDirectory(directory)) return List.of();
            try (var paths = Files.walk(directory)) {
                return paths.filter(Files::isRegularFile)
                    .filter(path -> Strings.CS.endsWith(
                        path.getFileName().toString(), ".md"))
                    .sorted().toList();
            }
        }, none.plans(), none.tasks(), none.skillAttribution(),
            none.collaboration(), none.sandbox());
    }

    public static ToolingCommandPorts plans(Path directory) {
        ToolingCommandPorts none = ToolingCommandPorts.none();
        return new ToolingCommandPorts(none.resources(), new ToolingCommandPorts.Plans() {
            @Override public Path planFile(String sessionId) { return directory.resolve(sessionId + ".md"); }
            @Override public void copy(String sourceSessionId, String targetSessionId) {
                Path source = planFile(sourceSessionId);
                if (!Files.isRegularFile(source)) return;
                try {
                    Files.createDirectories(directory);
                    Files.copy(source, planFile(targetSessionId));
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
        }, none.tasks(), none.skillAttribution(), none.collaboration(), none.sandbox());
    }

    public static ToolingCommandPorts collaboration(boolean teammate) {
        ToolingCommandPorts none = ToolingCommandPorts.none();
        return new ToolingCommandPorts(none.resources(), none.plans(), none.tasks(),
            none.skillAttribution(), () -> teammate, none.sandbox());
    }

    private static List<String> rules(List<PermissionRule> rules, PermissionBehavior behavior) {
        return rules.stream().filter(rule -> rule.behavior() == behavior)
            .map(PermissionEngine::permissionRuleToString).toList();
    }
    private static SessionCommandPort.LocatedSession located(SessionSearch.LocatedSession session) {
        return new SessionCommandPort.LocatedSession(session.id(), session.sessionFile(),
            session.cwd(), session.customTitle());
    }
    private static SessionCommandPort.StatsSnapshot stats(StatsAggregator aggregator) {
        ClaudeCodeStats stats = aggregator.aggregateAll();
        Map<String, SessionCommandPort.ModelUsage> models = stats.modelUsage().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> new SessionCommandPort.ModelUsage(
                entry.getValue().inputTokens(), entry.getValue().outputTokens())));
        LocalDate peak = stats.peakActivityDay() == null ? null : LocalDate.parse(stats.peakActivityDay());
        return new SessionCommandPort.StatsSnapshot(stats.totalSessions(), models,
            stats.longestSession() == null ? null
                : new SessionCommandPort.SessionDuration(stats.longestSession().duration()),
            Math.toIntExact(stats.activeDays()), Math.toIntExact(stats.totalDays()),
            new SessionCommandPort.Streaks(Math.toIntExact(stats.streaks().longestStreak()),
                Math.toIntExact(stats.streaks().currentStreak())), peak);
    }
}
