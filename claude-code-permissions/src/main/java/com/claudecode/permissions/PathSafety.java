package com.claudecode.permissions;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;


public final class PathSafety {

    private static final Pattern SHORT_NAME_PATTERN = Pattern.compile("~\\d");
    private static final Pattern TRAILING_DOT_OR_SPACE_PATTERN = Pattern.compile("[.\\s]+$");
    private static final Pattern DOS_DEVICE_PATTERN = Pattern.compile(
        "\\.(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_DOT_SEGMENT_PATTERN =
        Pattern.compile("(^|/|\\\\)\\.{3,}(/|\\\\|$)");

    private PathSafety() {}

    /** Sensitive files that must never be auto-edited without explicit permission. */
    public static final List<String> DANGEROUS_FILES = List.of(
        ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile", ".zshrc",
        ".zprofile", ".profile", ".ripgreprc", ".mcp.json", ".claude.json");

    /** Sensitive directories (case-insensitive) that must never be auto-edited. */
    public static final List<String> DANGEROUS_DIRECTORIES = List.of(
        ".git", ".vscode", ".idea", ".claude");

    /** Result of {@link #checkPathSafetyForAutoEdit}: safe flag, message, classifier-approvability. */
    public record SafetyResult(boolean safe, String message, boolean classifierApprovable) {
        private static final SafetyResult OK = new SafetyResult(true, null, false);

        public static SafetyResult ok() {
            return OK;
        }

        public static SafetyResult fail(String message, boolean classifierApprovable) {
            return new SafetyResult(false, message, classifierApprovable);
        }
    }

    static String normalizeCase(String p) {
        return p.toLowerCase(Locale.ROOT);
    }

    static String getPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (Strings.CS.contains( os, "win")) return "windows";
        String wsl = System.getenv("WSL_DISTRO_NAME");
        String release = System.getProperty("os.version", "").toLowerCase(Locale.ROOT);
        if ((StringUtils.isNotBlank(wsl)) ||Strings.CS.contains( release, "microsoft")) return "wsl";
        return "unix";
    }

    private static boolean windowsKernelPathSyntax() {
        String platform = getPlatform();
        return Strings.CS.equals(platform, "windows") || Strings.CS.equals(platform, "wsl");
    }

    /**
     * Detects suspicious Windows/NTFS path patterns (alternate data streams, 8.3 short names, long-path
     * prefixes, trailing dots/spaces, DOS device names, three-or-more consecutive dots, UNC).
     */
    public static boolean hasSuspiciousWindowsPathPattern(String path) {
        if (windowsKernelPathSyntax()) {
            int ci = path.indexOf(':', 2);
            if (ci != -1) return true;
        }
        if (SHORT_NAME_PATTERN.matcher(path).find()) return true;
        if (Strings.CS.startsWith(path, "\\\\?\\") || Strings.CS.startsWith(path, "\\\\.\\")
            || Strings.CS.startsWith(path, "//?/") || Strings.CS.startsWith(path, "//./")) return true;
        if (TRAILING_DOT_OR_SPACE_PATTERN.matcher(path).find()) return true;
        if (DOS_DEVICE_PATTERN.matcher(path).find()) return true;
        if (MULTI_DOT_SEGMENT_PATTERN.matcher(path).find()) return true;
        return containsVulnerableUncPath(path);
    }

    /**
     * Returns true if the path is a sensitive file/directory that must not be auto-edited.
     */
    public static boolean isDangerousFilePathToAutoEdit(Path path) {
        String abs = path.toAbsolutePath().normalize().toString();
        if (Strings.CS.startsWith(abs, "\\\\") || Strings.CS.startsWith(abs, "//")) {
            return true;
        }
        String[] segments = abs.split("[\\\\/]");
        String fileName = segments.length == 0 ? "" : segments[segments.length - 1];
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            String ns = normalizeCase(seg);
            for (String dir : DANGEROUS_DIRECTORIES) {
                if (!ns.equals(normalizeCase(dir))) {
                    continue;
                }
                if (Strings.CS.equals(dir, ".claude")) {
                    // Skip structural .claude/worktrees/ (git worktree storage).
                    if (i + 1 < segments.length
                        && Strings.CS.equals(normalizeCase(segments[i + 1]), "worktrees")) {
                        break;
                    }
                }
                return true;
            }
        }
        if (!fileName.isEmpty()) {
            String nfn = normalizeCase(fileName);
            for (String f : DANGEROUS_FILES) {
                if (nfn.equals(normalizeCase(f))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True if the resolved path is a Claude settings file (defense-in-depth against redundant {@code./}
     * / mixed-case bypasses).
     */
    public static boolean isClaudeSettingsPath(Path path) {
        String n = normalizeCase(path.toAbsolutePath().normalize().toString());
        String sep = File.separator;
        return Strings.CS.endsWith(n, sep + ".claude" + sep + "settings.json")
            || Strings.CS.endsWith(n, sep + ".claude" + sep + "settings.local.json");
    }

    /**
     * Full safety evaluation for an auto-edit target.
     */
    public static SafetyResult checkPathSafetyForAutoEdit(Path path) {
// Check the raw path string: Path.normalize collapses a leading "//"

// resolved path string directly, so match that here.
        String raw = path.toString();
        if (hasSuspiciousWindowsPathPattern(raw)) {
            return SafetyResult.fail(
                "Claude requested permissions to write to " + path
                    + ", which contains a suspicious Windows path pattern that requires manual approval.",
                false);
        }
        if (isClaudeSettingsPath(path)) {
            return SafetyResult.fail(
                "Claude requested permissions to write to " + path + ", but you haven't granted it yet.",
                true);
        }
        if (isDangerousFilePathToAutoEdit(path)) {
            return SafetyResult.fail(
                "Claude requested permissions to edit " + path + " which is a sensitive file.",
                true);
        }
        return SafetyResult.ok();
    }


    public static SafetyResult checkPathSafetyForRead(String rawPath, Path parsedPath,
                                                      PermissionPathContext context) {
        String raw = rawPath == null ? "" : rawPath;
        if (isNetworkPath(raw)) {
            boolean trusted = context != null && context.isTrustedNetworkDirectory(parsedPath);
            if (!trusted) {
                return SafetyResult.fail(
                    "Claude requested permissions to read from " + raw
                        + ", which appears to be a UNC path that could access network resources.",
                    false);
            }
        }
        if (hasSuspiciousWindowsPathPatternWithoutNetwork(raw)) {
            return SafetyResult.fail(
                "Claude requested permissions to read from " + raw
                    + ", which contains a suspicious Windows path pattern that requires manual approval.",
                false);
        }
        return SafetyResult.ok();
    }

    static boolean isNetworkPath(String raw) {
        return Strings.CS.startsWith(raw, "\\\\")
            || Strings.CS.startsWith(raw, "//")
            || Strings.CS.equals(raw, "/net")
            || Strings.CS.startsWith(raw, "/net/");
    }

    private static boolean hasSuspiciousWindowsPathPatternWithoutNetwork(String path) {
        if (windowsKernelPathSyntax()) {
            int ci = path.indexOf(':', 2);
            if (ci != -1) return true;
        }
        if (SHORT_NAME_PATTERN.matcher(path).find()) return true;
        if (Strings.CS.startsWith(path, "\\\\?\\") || Strings.CS.startsWith(path, "\\\\.\\")
            || Strings.CS.startsWith(path, "//?/") || Strings.CS.startsWith(path, "//./")) return true;
        if (TRAILING_DOT_OR_SPACE_PATTERN.matcher(path).find()) return true;
        if (DOS_DEVICE_PATTERN.matcher(path).find()) return true;
        return MULTI_DOT_SEGMENT_PATTERN.matcher(path).find();
    }

    private static boolean containsVulnerableUncPath(String p) {
        if (Strings.CS.startsWith(p, "\\\\") || Strings.CS.startsWith(p, "//")) {
            return true;
        }
        return Strings.CS.startsWith(p, "\\\\?\\") || Strings.CS.startsWith(p, "\\\\.\\")
            || Strings.CS.startsWith(p, "//?/") || Strings.CS.startsWith(p, "//./");
    }
}
