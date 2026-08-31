package com.claudecode.commands.context;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.File;
import java.nio.file.Path;

/**
 * Human-friendly path shortening for {@code /context} displays.
 */
final class DisplayPath {

    private DisplayPath() {}

    static String shorten(String filePath) {
        return shorten(filePath,
            System.getProperty("user.dir"), System.getProperty("user.home"));
    }

    static String shorten(String filePath, String cwd, String home) {
        if (StringUtils.isEmpty(filePath)) return "";
        try {
            Path path = Path.of(filePath);
            if (cwd != null) {
                Path cwdPath = Path.of(cwd);
                if (path.isAbsolute() && path.startsWith(cwdPath)) {
                    Path relative = cwdPath.relativize(path);
                    if (!relative.toString().isEmpty()) {
                        return relative.toString();
                    }
                }
            }
            if (home != null && Strings.CS.startsWith(filePath, home + File.separator)) {
                return "~" + filePath.substring(home.length());
            }
        } catch (Exception _) {
            // Fall through to the raw path.
        }
        return filePath;
    }
}
