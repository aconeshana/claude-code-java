package com.claudecode.services.claudemd;

/**
 * Classification of a CLAUDE.md memory file by scope.
 */
public enum MemoryType {
    /** {@code ~/.claude/CLAUDE.md} and {@code ~/.claude/rules/*.md}. */
    USER,
    /** {@code <dir>/CLAUDE.md}, {@code <dir>/.claude/CLAUDE.md}, {@code <dir>/.claude/rules/*.md}. */
    PROJECT,
    /** {@code <dir>/CLAUDE.local.md} — gitignored, per-checkout. */
    LOCAL
}
