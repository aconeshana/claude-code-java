package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import com.claudecode.core.memdir.AutoMemoryPrompt;

/**
 * Local resolution of the team-memory directory and containment check.
 */
public final class TeamMemPaths {

    /**
     * Cache of team-memory dir per working directory.
     */
    private static final ConcurrentHashMap<String, String> TEAM_MEM_PATH_CACHE =
        new ConcurrentHashMap<>();

    private TeamMemPaths() {}

    /**
     * Returns the team-memory directory for the given working directory, always
     * with a single trailing separator. Never null; returns an absolute path.
     */
    public static String getTeamMemPath(String workingDirectory) {
        String wd = (StringUtils.isBlank(workingDirectory))
            ? System.getProperty("user.dir") : workingDirectory;
        return TEAM_MEM_PATH_CACHE.computeIfAbsent(wd, k ->
            AutoMemoryPrompt.resolveAutoMemPath(Path.of(k)).resolve("team").toString()
                + File.separator);
    }

    /**
     * Whether {@code filePath} resolves inside the team-memory directory for
     * {@code workingDirectory}. {@code false} for any path that fails to
     * normalize (defensive — a malformed path is never "within team memory").
     */
    public static boolean isTeamMemPath(String filePath, String workingDirectory) {
        if (StringUtils.isBlank(filePath)) {
            return false;
        }
        try {
            String resolved = Path.of(filePath).toAbsolutePath().normalize().toString();
            return Strings.CS.startsWith(resolved, getTeamMemPath(workingDirectory));
        } catch (RuntimeException _) {
            return false;
        }
    }
}
