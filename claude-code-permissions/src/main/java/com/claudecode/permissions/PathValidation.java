package com.claudecode.permissions;

import org.apache.commons.lang3.Strings;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * String-level path validation used before permission-rule evaluation.
 *
 * <p>The checks cover tilde and shell-variable expansion, UNC paths, write globs, and dangerous
 * removal targets. Rule matching remains the responsibility of {@link PermissionEngine}.
 */
public final class PathValidation {

    private PathValidation() {}

    private static final Pattern GLOB_PATTERN_REGEX = Pattern.compile("[*?\\[\\]{}()]");
    private static final Pattern WINDOWS_DRIVE_ROOT_REGEX = Pattern.compile("^[A-Za-z]:/?$");
    private static final Pattern WINDOWS_DRIVE_CHILD_REGEX = Pattern.compile("^[A-Za-z]:/[^/]+$");
    private static final int MAX_DIRS_TO_LIST = 5;

    /** Result of {@link #validatePath}: allowed flag, resolved path, optional denial reason. */
    public record PathValidationResult(boolean allowed, String resolvedPath, String reason) {
        public static PathValidationResult allow(String resolvedPath) {
            return new PathValidationResult(true, resolvedPath, null);
        }

        public static PathValidationResult deny(String resolvedPath, String reason) {
            return new PathValidationResult(false, resolvedPath, reason);
        }
    }

    /**
     * Expands a leading tilde ({@code ~} or {@code ~/}) to the user's home directory.
     * The backslash separator form ({@code ~\foo}) is accepted only on Windows,
     * matching the shell path rules on that platform. {@code ~user} expansion is
     * intentionally unsupported (security: the shell resolves it differently,
     * creating a TOCTOU gap).
     */
    public static String expandTilde(String path) {
        if (path == null) {
            return null;
        }
        boolean windows =Strings.CS.contains( System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT), "win");
        if (Strings.CS.equals( path, "~") ||Strings.CS.startsWith( path, "~/")
            || (windows &&Strings.CS.startsWith( path, "~\\"))) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    /**
     * Returns the base directory of a glob pattern — everything up to the last
     * separator before the first glob metacharacter. E.g. {@code "/path/to/*.txt"}
     * → {@code "/path/to"}. A pattern with no separator → {@code "."}; a pattern
     * rooted at {@code /} → {@code "/"}.
     */
    public static String getGlobBaseDirectory(String path) {
        Matcher m = GLOB_PATTERN_REGEX.matcher(path);
        if (!m.find()) {
            return path;
        }
        String before = path.substring(0, m.start());
        int lastSep = before.lastIndexOf('/');
        if (lastSep == -1) {
            return ".";
        }
        String base = before.substring(0, lastSep);
        return base.isEmpty() ? "/" : base;
    }

    /**
     * Checks if a resolved path is dangerous for removal ({@code rm}/{@code rmdir}): wildcards, root,
     * home, direct children of root, and Windows drive root/children.
     */
    public static boolean isDangerousRemovalPath(String resolvedPath) {
        if (resolvedPath == null) {
            return false;
        }
        String fwd = resolvedPath.replaceAll("[\\\\/]+", "/");
        if (Strings.CS.equals(fwd, "*") || Strings.CS.endsWith(fwd, "/*")) {
            return true;
        }
        String normalized = Strings.CS.equals(fwd, "/") ? "/" : fwd.replaceAll("/$", "");
        if (Strings.CS.equals(normalized, "/")) {
            return true;
        }
        if (WINDOWS_DRIVE_ROOT_REGEX.matcher(normalized).matches()) {
            return true;
        }
        String home = System.getProperty("user.home").replaceAll("[\\\\/]+", "/");
        if (normalized.equals(home)) {
            return true;
        }
        int li = normalized.lastIndexOf('/');
        String parent = (li <= 0) ? "/" : normalized.substring(0, li);
        if (Strings.CS.equals(parent, "/")) {
            return true;
        }
        return WINDOWS_DRIVE_CHILD_REGEX.matcher(normalized).matches();
    }

    /** Formats a directory list for display, truncating after {@link #MAX_DIRS_TO_LIST}. */
    public static String formatDirectoryList(List<String> directories) {
        int n = directories.size();
        if (n <= MAX_DIRS_TO_LIST) {
            return directories.stream().map(d -> "'" + d + "'").collect(Collectors.joining(", "));
        }
        String first = directories.stream().limit(MAX_DIRS_TO_LIST)
            .map(d -> "'" + d + "'").collect(Collectors.joining(", "));
        return first + ", and " + (n - MAX_DIRS_TO_LIST) + " more";
    }

    /**
     * Validates a (Bash/file) target path at the string level, returning whether it is permitted and
     * the resolved path for error messages.
     */
    public static PathValidationResult validatePath(String path, String cwd, boolean readOnly) {
        if (path == null) {
            return PathValidationResult.allow(null);
        }
        String clean = expandTilde(stripQuotes(path));

        if (containsVulnerableUncPath(clean)) {
            return PathValidationResult.deny(clean, "UNC network paths require manual approval");
        }
        // expandTilde already converted ~ and ~/ to absolute paths; any remaining
        // leading ~ is an unsupported variant that the shell would expand differently.
        if (Strings.CS.startsWith(clean, "~")) {
            return PathValidationResult.deny(
                clean, "Tilde expansion variants (~user, ~+, ~-) in paths require manual approval");
        }
        if (Strings.CS.contains(clean, "$") || Strings.CS.contains(clean, "%") || Strings.CS.startsWith(clean, "=")) {
            return PathValidationResult.deny(
                clean, "Shell expansion syntax in paths requires manual approval");
        }
        if (GLOB_PATTERN_REGEX.matcher(clean).find()) {
            if (!readOnly) {
                return PathValidationResult.deny(
                    clean,
                    "Glob patterns are not allowed in write operations. Please specify an exact file path.");
            }
            // Read glob: resolve its base directory; rule matching deferred to caller.
            return PathValidationResult.allow(resolve(getGlobBaseDirectory(clean), cwd));
        }
        return PathValidationResult.allow(resolve(clean, cwd));
    }

    private static String stripQuotes(String path) {
        if (path.length() >= 2
            && (Strings.CS.startsWith(path, "'") && Strings.CS.endsWith(path, "'")
                || Strings.CS.startsWith(path, "\"") && Strings.CS.endsWith(path, "\""))) {
            return path.substring(1, path.length() - 1);
        }
        return path;
    }

    private static boolean containsVulnerableUncPath(String p) {
        if (Strings.CS.startsWith(p, "\\\\") || Strings.CS.startsWith(p, "//")) {
            return true;
        }
        return Strings.CS.startsWith(p, "\\\\?\\") || Strings.CS.startsWith(p, "\\\\.\\")
            || Strings.CS.startsWith(p, "//?/") || Strings.CS.startsWith(p, "//./");
    }

    private static String resolve(String p, String cwd) {
        Path path = Path.of(p);
        if (path.isAbsolute()) {
            return path.normalize().toString();
        }
        return Path.of(cwd == null ? "." : cwd, p).normalize().toString();
    }
}
