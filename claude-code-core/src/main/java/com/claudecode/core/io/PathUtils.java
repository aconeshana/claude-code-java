package com.claudecode.core.io;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Path helpers shared across modules (the LSP, services, and tools layers all need the same
 * extension-extraction semantics, which previously lived as three divergent {@code extensionOf}
 * copies).
 */
public final class PathUtils {

    private PathUtils() {}

    public static String extensionOf(Path path) {
        String name = path != null && path.getFileName() != null
                ? path.getFileName().toString()
                : "";
        return posixExtname(name).toLowerCase(Locale.ROOT);
    }

    /** Expands bare {@code ~} and a leading {@code ~/}; leaves other strings unchanged. */
    public static String expandTilde(String path) {
        if (StringUtils.isEmpty(path)) return path;
        if (Strings.CS.equals(path, "~")) return System.getProperty("user.home");
        if (Strings.CS.startsWith(path, "~/")) return System.getProperty("user.home") + path.substring(1);
        return path;
    }

    /**
     * Inverse of {@link #expandTilde}: rewrites the home prefix back to {@code ~} so absolute
     * paths stay legible in narrow terminal chrome. Only a whole leading path segment matches,
     * so a sibling directory such as {@code /Users/xmly-backup} is left alone.
     */
    public static String abbreviateTilde(String path) {
        if (StringUtils.isEmpty(path)) return path;
        String home = System.getProperty("user.home");
        if (StringUtils.isEmpty(home)) return path;
        if (Strings.CS.equals(path, home)) return "~";
        return Strings.CS.startsWith(path, home + "/") ? "~" + path.substring(home.length()) : path;
    }

    /** Implements {@code path.posix.extname} semantics consistently on every host platform. */
    private static String posixExtname(String name) {
        int startDot = -1;
        int startPart = 0;
        int end = -1;
        boolean matchedSlash = true;
        int preDotState = 0;
        for (int i = name.length() - 1; i >= 0; --i) {
            char c = name.charAt(i);
            if (c == '/') {
                if (!matchedSlash) {
                    startPart = i + 1;
                    break;
                }
                continue;
            }
            if (end == -1) {
                matchedSlash = false;
                end = i + 1;
            }
            if (c == '.') {
                if (startDot == -1) {
                    startDot = i;
                } else if (preDotState != 1) {
                    preDotState = 1;
                }
            } else if (startDot != -1) {
                preDotState = -1;
            }
        }

        if (startDot == -1
                || end == -1
                || preDotState == 0
                || (preDotState == 1 && startDot == end - 1 && startDot == startPart + 1)) {
            return "";
        }
        return name.substring(startDot, end);
    }


    public static Path expandPath(String path, String cwd) {
        String trimmed = path == null ? "" : path.trim();
        if (trimmed.isEmpty()) {
            return normalizeNfc(Path.of(cwd).toAbsolutePath().normalize());
        }
        String expanded = expandTilde(trimmed);
        // Windows POSIX-style drive path (/c/Users/...) → native Windows path
// runs this only under its ^/[a-z]/ guard on Windows).
        if (isWindows() && expanded.matches("/[a-zA-Z]/.*")) {
            expanded = posixPathToWindowsPath(expanded);
        }
        Path resolved = Path.of(expanded);
        resolved = resolved.isAbsolute() ? resolved : Path.of(cwd).resolve(resolved);
        return normalizeNfc(resolved.normalize());
    }

/** Pure conversion matching. */
    public static String windowsPathToPosixPath(String path) {
        if (Strings.CS.startsWith(path, "\\\\")) return path.replace('\\', '/');
        if (path.matches("^[A-Za-z]:[/\\\\].*")) {
            return "/" + Character.toLowerCase(path.charAt(0))
                + path.substring(2).replace('\\', '/');
        }
        return path.replace('\\', '/');
    }

/** Pure conversion matching. */
    public static String posixPathToWindowsPath(String path) {
        if (Strings.CS.startsWith(path, "//")) return path.replace('/', '\\');
        Matcher cygdrive = Pattern
            .compile("^/cygdrive/([A-Za-z])(/|$)").matcher(path);
        if (cygdrive.find()) {
            String rest = path.substring(("/cygdrive/" + cygdrive.group(1)).length());
            return cygdrive.group(1).toUpperCase(Locale.ROOT) + ":"
                + (rest.isEmpty() ? "\\" : rest).replace('/', '\\');
        }
        Matcher drive = Pattern
            .compile("^/([A-Za-z])(/|$)").matcher(path);
        if (drive.find()) {
            String rest = path.substring(2);
            return drive.group(1).toUpperCase(Locale.ROOT) + ":"
                + (rest.isEmpty() ? "\\" : rest).replace('/', '\\');
        }
        return path.replace('/', '\\');
    }

    /**
     * Relativizes {@code absolutePath} only when it is inside {@code cwd}; otherwise returns
     * the original path, so paths outside the project remain unambiguous in tool output.
     */
    public static String toRelativePath(Path cwd, Path absolutePath) {
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        Path normalizedPath = absolutePath.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedCwd)
            ? normalizedCwd.relativize(normalizedPath).toString()
            : absolutePath.toString();
    }

    /**
     * Returns a directory itself when it exists as one, otherwise its parent. UNC paths are
     * never probed because probing them on Windows may trigger an NTLM credential request.
     */
    public static Path directoryForPath(String path, String cwd) {
        Path absolutePath = expandPath(path, cwd);
        String normalized = absolutePath.toString();
        if (Strings.CS.startsWith(normalized, "\\\\") || Strings.CS.startsWith(normalized, "//")) {
            return parentOrSelf(absolutePath);
        }
        return Files.isDirectory(absolutePath) ? absolutePath : parentOrSelf(absolutePath);
    }

    /** Returns whether a path contains a lexical {@code ..} traversal segment. */
    public static boolean containsPathTraversal(String path) {
        return path != null && path.matches(".*(?:^|[\\\\/])\\.\\.(?:[\\\\/]|$).*");
    }

    private static Path parentOrSelf(Path path) {
        return path.getParent() == null ? path : path.getParent();
    }

    private static Path normalizeNfc(Path p) {
        return Path.of(Normalizer.normalize(p.toString(), Normalizer.Form.NFC));
    }

    private static boolean isWindows() {
        return Strings.CI.contains(System.getProperty("os.name", ""), "windows");
    }
}
