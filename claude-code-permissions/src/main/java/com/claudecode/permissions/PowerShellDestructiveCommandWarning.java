package com.claudecode.permissions;

import org.apache.commons.lang3.StringUtils;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects potentially destructive PowerShell commands and returns a warning string for display in
 * the permission dialog.
 */
public final class PowerShellDestructiveCommandWarning {

    private record PatternEntry(Pattern pattern, String warning) {}

    private static PatternEntry entry(String regex, String warning) {
        return new PatternEntry(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), warning);
    }

    private static final List<PatternEntry> PATTERNS = List.of(
        // Remove-Item with -Recurse and/or -Force (and common aliases)
        entry("(?:^|[|;&\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\n}]*-Recurse\\b[^|;&\n}]*-Force\\b",
            "Note: may recursively force-remove files"),
        entry("(?:^|[|;&\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\n}]*-Force\\b[^|;&\n}]*-Recurse\\b",
            "Note: may recursively force-remove files"),
        entry("(?:^|[|;&\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\n}]*-Recurse\\b",
            "Note: may recursively remove files"),
        entry("(?:^|[|;&\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\n}]*-Force\\b",
            "Note: may force-remove files"),

        // Clear-Content on broad paths
        entry("\\bClear-Content\\b[^|;&\n]*\\*",
            "Note: may clear content of multiple files"),

        // Format-Volume and Clear-Disk
        entry("\\bFormat-Volume\\b",
            "Note: may format a disk volume"),
        entry("\\bClear-Disk\\b",
            "Note: may clear a disk"),

        // Git destructive operations (same as BashTool)
        entry("\\bgit\\s+reset\\s+--hard\\b",
            "Note: may discard uncommitted changes"),
        entry("\\bgit\\s+push\\b[^|;&\n]*\\s+(--force|--force-with-lease|-f)\\b",
            "Note: may overwrite remote history"),
        entry("\\bgit\\s+clean\\b(?![^|;&\n]*(?:-[a-zA-Z]*n|--dry-run))[^|;&\n]*-[a-zA-Z]*f",
            "Note: may permanently delete untracked files"),
        entry("\\bgit\\s+stash\\s+(drop|clear)\\b",
            "Note: may permanently remove stashed changes"),

        // Database operations
        entry("\\b(DROP|TRUNCATE)\\s+(TABLE|DATABASE|SCHEMA)\\b",
            "Note: may drop or truncate database objects"),

        // System operations
        entry("\\bStop-Computer\\b",
            "Note: will shut down the computer"),
        entry("\\bRestart-Computer\\b",
            "Note: will restart the computer"),
        entry("\\bClear-RecycleBin\\b",
            "Note: permanently deletes recycled files")
    );

    private PowerShellDestructiveCommandWarning() {}

    /**
     * Returns the first warning matching {@code command}, or empty if no pattern matched.
     */
    public static Optional<String> check(String command) {
        if (StringUtils.isBlank(command)) return Optional.empty();
        for (PatternEntry e : PATTERNS) {
            if (e.pattern().matcher(command).find()) return Optional.of(e.warning());
        }
        return Optional.empty();
    }
}
