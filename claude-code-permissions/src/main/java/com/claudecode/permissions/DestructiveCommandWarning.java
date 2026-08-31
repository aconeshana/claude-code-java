package com.claudecode.permissions;

import org.apache.commons.lang3.StringUtils;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects potentially destructive bash commands and returns a warning string for display in the
 * permission dialog.
 */
public final class DestructiveCommandWarning {

    private record PatternEntry(Pattern pattern, String warning) {}


    private static PatternEntry entry(String regex, String warning) {
        return new PatternEntry(Pattern.compile(regex), warning);
    }


    private static PatternEntry entryCi(String regex, String warning) {
        return new PatternEntry(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), warning);
    }

    private static final List<PatternEntry> PATTERNS = List.of(
        // Git — data loss / hard to reverse
        entry("\\bgit\\s+reset\\s+--hard\\b",
            "Note: may discard uncommitted changes"),
        entry("\\bgit\\s+push\\b[^;&|\\n]*[ \\t](--force|--force-with-lease|-f)\\b",
            "Note: may overwrite remote history"),
        entry("\\bgit\\s+clean\\b(?![^;&|\\n]*(?:-[a-zA-Z]*n|--dry-run))[^;&|\\n]*-[a-zA-Z]*f",
            "Note: may permanently delete untracked files"),
        entry("\\bgit\\s+checkout\\s+(--\\s+)?\\.[ \\t]*($|[;&|\\n])",
            "Note: may discard all working tree changes"),
        entry("\\bgit\\s+restore\\s+(--\\s+)?\\.[ \\t]*($|[;&|\\n])",
            "Note: may discard all working tree changes"),
        entry("\\bgit\\s+stash[ \\t]+(drop|clear)\\b",
            "Note: may permanently remove stashed changes"),
        entry("\\bgit\\s+branch\\s+(-D[ \\t]|--delete\\s+--force|--force\\s+--delete)\\b",
            "Note: may force-delete a branch"),

        // Git — safety bypass
        entry("\\bgit\\s+(commit|push|merge)\\b[^;&|\\n]*--no-verify\\b",
            "Note: may skip safety hooks"),
        entry("\\bgit\\s+commit\\b[^;&|\\n]*--amend\\b",
            "Note: may rewrite the last commit"),

        // File deletion
        entry("(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*[rR][a-zA-Z]*f|(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*f[a-zA-Z]*[rR]",
            "Note: may recursively force-remove files"),
        entry("(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*[rR]",
            "Note: may recursively remove files"),
        entry("(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*f",
            "Note: may force-remove files"),


        entryCi("\\b(DROP|TRUNCATE)\\s+(TABLE|DATABASE|SCHEMA)\\b",
            "Note: may drop or truncate database objects"),
        entryCi("\\bDELETE\\s+FROM\\s+\\w+[ \\t]*(;|\"|'|\\n|$)",
            "Note: may delete all rows from a database table"),

        // Infrastructure
        entry("\\bkubectl\\s+delete\\b",
            "Note: may delete Kubernetes resources"),
        entry("\\bterraform\\s+destroy\\b",
            "Note: may destroy Terraform infrastructure")
    );

    private DestructiveCommandWarning() {}

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
