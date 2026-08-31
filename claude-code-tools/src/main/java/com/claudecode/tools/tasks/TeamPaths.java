package com.claudecode.tools.tasks;

import com.claudecode.core.config.ClaudePaths;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Canonical on-disk paths for agent-team state.
 */
final class TeamPaths {

    private TeamPaths() {}

    static String sanitizeName(String name) {
        return Objects.requireNonNull(name, "name")
            .replaceAll("[^a-zA-Z0-9]", "-")
            .toLowerCase(Locale.ROOT);
    }

    static Path teamDirectory(String teamName) {
        return ClaudePaths.TEAMS_DIR.resolve(sanitizeName(teamName));
    }

    static Path teamConfigFile(String teamName) {
        return teamDirectory(teamName).resolve("config.json");
    }

    static Path taskListDirectory(String teamName) {
        return ClaudePaths.TASKS_DIR.resolve(
            TaskPersistence.sanitizePathComponent(teamName));
    }
}
