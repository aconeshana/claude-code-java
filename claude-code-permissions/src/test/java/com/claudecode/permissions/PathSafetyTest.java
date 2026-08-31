package com.claudecode.permissions;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for PathSafety (matches  safety guards).
 */
class PathSafetyTest {

    @Test
    void dangerousFilesAndDirsListed() {
        assertTrue(PathSafety.DANGEROUS_FILES.contains(".bashrc"));
        assertTrue(PathSafety.DANGEROUS_DIRECTORIES.contains(".git"));
        assertTrue(PathSafety.DANGEROUS_DIRECTORIES.contains(".claude"));
    }

    @Test
    void isDangerousFilePathToAutoEditDetectsSensitive() {
        assertTrue(PathSafety.isDangerousFilePathToAutoEdit(Path.of("/home/u/.bashrc")));
        assertTrue(PathSafety.isDangerousFilePathToAutoEdit(Path.of("/home/u/project/.git/config")));
        assertTrue(PathSafety.isDangerousFilePathToAutoEdit(Path.of("/home/u/.claude/settings.json")));
        assertTrue(PathSafety.isDangerousFilePathToAutoEdit(Path.of("/home/u/.vscode/launch.json")));
        // .claude/worktrees/ is a structural, non-dangerous segment.
        assertFalse(PathSafety.isDangerousFilePathToAutoEdit(Path.of("/home/u/.claude/worktrees/foo/x")));
        assertFalse(PathSafety.isDangerousFilePathToAutoEdit(Path.of("/home/u/project/src/main.java")));
        assertFalse(PathSafety.isDangerousFilePathToAutoEdit(Path.of("/home/u/.bash_profile.bak")));
    }

    @Test
    void hasSuspiciousWindowsPathPattern() {
        assertTrue(PathSafety.hasSuspiciousWindowsPathPattern("C:\\foo~1"));
        assertTrue(PathSafety.hasSuspiciousWindowsPathPattern("foo/.../bar"));
        assertTrue(PathSafety.hasSuspiciousWindowsPathPattern("file.CON"));
        assertTrue(PathSafety.hasSuspiciousWindowsPathPattern("trailingdots. "));
        assertTrue(PathSafety.hasSuspiciousWindowsPathPattern("//server/share/x"));
        assertFalse(PathSafety.hasSuspiciousWindowsPathPattern("normal/path.txt"));
        assertFalse(PathSafety.hasSuspiciousWindowsPathPattern("CON"));
    }

    @Test
    void isClaudeSettingsPath() {
        assertTrue(PathSafety.isClaudeSettingsPath(Path.of("/home/u/.claude/settings.json")));
        assertTrue(PathSafety.isClaudeSettingsPath(Path.of("/home/u/.claude/settings.local.json")));
        assertFalse(PathSafety.isClaudeSettingsPath(Path.of("/home/u/.claude/foo.json")));
        assertFalse(PathSafety.isClaudeSettingsPath(Path.of("/home/u/settings.json")));
    }

    @Test
    void checkPathSafetyForAutoEdit() {
        PathSafety.SafetyResult safe = PathSafety.checkPathSafetyForAutoEdit(Path.of("/home/u/project/src.java"));
        assertTrue(safe.safe());
        assertSame(safe,
            PathSafety.checkPathSafetyForAutoEdit(Path.of("/home/u/project/other.java")));

        PathSafety.SafetyResult dangerous = PathSafety.checkPathSafetyForAutoEdit(Path.of("/home/u/.bashrc"));
        assertFalse(dangerous.safe());
        assertTrue(dangerous.classifierApprovable());

        // On macOS/Linux a Java Path collapses a leading "//" (UNC) into "/", so
        // UNC detection is exercised via the raw-string hasSuspiciousWindowsPathPattern
// test above. Here we use a Windows pattern that survives Path.normalize.
        PathSafety.SafetyResult windows = PathSafety.checkPathSafetyForAutoEdit(Path.of("/foo/.../bar"));
        assertFalse(windows.safe());
        assertFalse(windows.classifierApprovable());
    }
}
