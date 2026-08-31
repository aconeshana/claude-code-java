package com.claudecode.tools.tasks;

import com.claudecode.core.state.AgentColorStore;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.claudecode.core.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks teams created during the current JVM session and removes orphaned team state during
 * graceful shutdown.
 */
public final class TeamSessionCleanup {

    private static final Set<String> CREATED_TEAMS = ConcurrentHashMap.newKeySet();

    private TeamSessionCleanup() {}

    /** Registers a team immediately after TeamCreate provisions it. */
    public static void register(String teamName) {
        if (StringUtils.isNotBlank(teamName)) CREATED_TEAMS.add(teamName);
    }

    /** Removes a team from shutdown cleanup after explicit TeamDelete. */
    public static void unregister(String teamName) {
        if (teamName != null) CREATED_TEAMS.remove(teamName);
    }

    /**
     * Best-effort cleanup of every team still owned by this session. Each team
     * is isolated so a corrupt directory cannot prevent the remaining teams
     * from being removed.
     */
    public static void cleanupRegisteredTeams() {
        if (CREATED_TEAMS.isEmpty()) return;
        var teams = new ArrayList<>(CREATED_TEAMS);
        for (String teamName : teams) {
            try {
                TeamRegistry.instance().get(teamName).ifPresent(state -> {
                    TeammateMailbox.instance().clearTeam(state.members());
                });
                TeamRegistry.instance().remove(teamName);
            } catch (RuntimeException _) {
                // Continue with on-disk cleanup even if in-memory state is stale.
            }
            try {
                TeamTaskListRegistry.instance().removeAndDelete(teamName);
            } catch (RuntimeException _) {

            }
            try {
                FileUtils.deleteRecursively(TeamPaths.teamDirectory(teamName));
            } catch (RuntimeException _) {
                // Best effort during shutdown.
            }
            CREATED_TEAMS.remove(teamName);
        }

        AgentColorStore.resetAll();
    }

    /** Test seam; package-private to keep production ownership explicit. */
    static void clearForTest() {
        CREATED_TEAMS.clear();
    }
}
