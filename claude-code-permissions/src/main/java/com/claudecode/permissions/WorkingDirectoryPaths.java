package com.claudecode.permissions;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.io.FileUtils;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Directory-containment checks used to decide whether a tool's target path falls inside the
 * session's working directory or one of the directories added via {@code /add-dir}.
 */
public final class WorkingDirectoryPaths {

    private WorkingDirectoryPaths() {}

    /**
     * The working directory plus every {@code /add-dir}-added directory, normalized to absolute paths.
     */
    public static Set<Path> allWorkingDirectories(ToolPermissionContext context) {
        Set<Path> dirs = new LinkedHashSet<>();
        dirs.add(normalize(context.workingDirectory()));
        for (Path dir : context.additionalDirs().keySet()) {
            dirs.add(normalize(dir));
        }
        return dirs;
    }

    /**
     * Whether {@code candidate} falls inside the working directory or any {@code /add-dir}-added
     * directory.
     */
    public static boolean isWithinWorkingDirectories(Path candidate, ToolPermissionContext context) {
        for (Path checkedPath : FileUtils.pathsForPermissionCheck(candidate)) {
            boolean allowed = false;
            for (Path workingDir : allWorkingDirectories(context)) {
                if (isWithin(checkedPath, workingDir)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) return false;
        }
        return true;
    }

    /**
     * Whether {@code path} is equal to or nested inside {@code workingPath}.
     */
    public static boolean isWithin(Path path, Path workingPath) {
        String candidate = forComparison(normalize(path));
        String working = forComparison(normalize(workingPath));
        return Path.of(candidate).equals(Path.of(working))
            || Path.of(candidate).startsWith(Path.of(working));
    }

    private static Path normalize(Path path) {
        Path expanded = expandHome(path);
        return expanded.toAbsolutePath().normalize();
    }

    private static Path expandHome(Path path) {
        String s = path.toString();
        if (Strings.CS.equals(s, "~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (Strings.CS.startsWith(s, "~/")) {
            return Path.of(System.getProperty("user.home"), s.substring(2));
        }
        return path;
    }

/**
     * Applies macOS mount aliasing, then unconditional lowercasing.
     */
    private static String forComparison(Path normalized) {
        String s = normalized.toString();
        s = s.replaceFirst("^/private/var/", "/var/");
        s = s.replaceFirst("^/private/tmp(/|$)", "/tmp$1");
        return s.toLowerCase(Locale.ROOT);
    }
}
