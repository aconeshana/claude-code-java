package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.io.FileUtils;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry mapping a team id to its shared task list, so that multiple in-process
 * teammates spawned under the same {@code team_id} can pull work from one list (auto-claiming via
 * {@link InProcessTeammateTask#tryClaimNextTask}).
 */
public final class TeamTaskListRegistry {

    private static final TeamTaskListRegistry INSTANCE = new TeamTaskListRegistry();

    private final Map<String, TodoStore> stores = new ConcurrentHashMap<>();

    private final Map<String, String> sessionTeams = new ConcurrentHashMap<>();

    private TeamTaskListRegistry() {}

    public static TeamTaskListRegistry instance() {
        return INSTANCE;
    }

    /**
     * Returns the shared todo list for {@code teamId}, creating (and
     * registering) a persistent one if it does not yet exist. Safe to call
     * concurrently; the store is created at most once per team id.
     */
    public TodoStore getOrCreate(String teamId) {
        return stores.computeIfAbsent(teamId,
            TodoStore::new);
    }

    /** Returns the shared todo list for {@code teamId}, or empty if none registered. */
    public Optional<TodoStore> get(String teamId) {
        return Optional.ofNullable(stores.get(teamId));
    }

    /** Registers a pre-built store (used by the TeamCreate tool). */
    public void register(String teamId, TodoStore store) {
        stores.put(teamId, store);
    }

/** Removes the shared list and icompatibility baseline-compatible on-disk directory. */
    public void removeAndDelete(String teamId) {
        stores.remove(teamId);
        FileUtils.deleteRecursively(TeamPaths.taskListDirectory(teamId));
        sessionTeams.values().removeIf(teamId::equals);
    }

    /** Binds a leader/session to the shared team task list for subsequent Task calls. */
    public void bindSession(String sessionId, String teamId) {
        if (StringUtils.isNotBlank(sessionId) && teamId != null && !StringUtils.isBlank(teamId)) {
            sessionTeams.put(sessionId, teamId);
        }
    }

    /** Removes the session's team task-list binding without deleting the store. */
    public void unbindSession(String sessionId) {
        if (sessionId != null) sessionTeams.remove(sessionId);
    }

    /** Resolves the store selected by the current session, or the injected fallback. */
    public TodoStore resolveForSession(String sessionId, TodoStore fallback) {
        String teamId = sessionId == null ? null : sessionTeams.get(sessionId);
        return teamId == null ? fallback : getOrCreate(teamId);
    }

    /** Returns the team currently bound to a leader/session, when one exists. */
    Optional<String> teamIdForSession(String sessionId) {
        return Optional.ofNullable(sessionId == null ? null : sessionTeams.get(sessionId));
    }

    /** Test seam: clears all registered lists. */
    void clearForTest() {
        stores.clear();
        sessionTeams.clear();
    }
}
