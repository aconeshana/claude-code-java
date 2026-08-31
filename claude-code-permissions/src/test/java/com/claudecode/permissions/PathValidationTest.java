package com.claudecode.permissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import org.apache.commons.lang3.Strings;

/**
 * Unit tests for PathValidation (matches ).
 */
class PathValidationTest {

    @Test
    void expandTildeResolvesHome() {
        String home = System.getProperty("user.home");
        assertEquals(home, PathValidation.expandTilde("~"));
        assertEquals(home + "/foo", PathValidation.expandTilde("~/foo"));
        // Unsupported variants are left untouched.
        assertEquals("~root", PathValidation.expandTilde("~root"));
        assertEquals("~+/x", PathValidation.expandTilde("~+/x"));
    }

    @Test
    void windowsTildeSeparatorIsLiteralOnNonWindows() {
        Assumptions.assumeFalse(Strings.CS.contains(System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT), "win"));
        assertEquals("~\\foo", PathValidation.expandTilde("~\\foo"));
    }

    @Test
    void getGlobBaseDirectoryExtractsPrefix() {
        assertEquals("/path/to", PathValidation.getGlobBaseDirectory("/path/to/*.txt"));
        assertEquals("/path/to", PathValidation.getGlobBaseDirectory("/path/to/**"));
        assertEquals(".", PathValidation.getGlobBaseDirectory("*.txt"));
        assertEquals("/", PathValidation.getGlobBaseDirectory("/*.txt"));
        assertEquals("src", PathValidation.getGlobBaseDirectory("src/a?.ts"));
    }

    @Test
    void isDangerousRemovalPathFlagsUnsafeTargets() {
        assertTrue(PathValidation.isDangerousRemovalPath("*"));
        assertTrue(PathValidation.isDangerousRemovalPath("/foo/*"));
        assertTrue(PathValidation.isDangerousRemovalPath("/"));
        assertTrue(PathValidation.isDangerousRemovalPath("/usr"));
        assertTrue(PathValidation.isDangerousRemovalPath("/etc"));
        assertTrue(PathValidation.isDangerousRemovalPath("C:"));
        assertTrue(PathValidation.isDangerousRemovalPath("C:/Windows"));
        // Safe-ish targets.
        assertFalse(PathValidation.isDangerousRemovalPath("/usr/local"));
        assertFalse(PathValidation.isDangerousRemovalPath("/home/user/file"));
        assertFalse(PathValidation.isDangerousRemovalPath("foo/bar"));
    }

    @Test
    void formatDirectoryListTruncates() {
        assertEquals("'a'", PathValidation.formatDirectoryList(List.of("a")));
        assertEquals("'a', 'b'", PathValidation.formatDirectoryList(List.of("a", "b")));
        assertEquals("'a', 'b', 'c', 'd', 'e', and 1 more",
            PathValidation.formatDirectoryList(List.of("a", "b", "c", "d", "e", "f")));
    }

    @Test
    void validatePathRejectsUncAndShellExpansion() {
        // UNC
        assertFalse(PathValidation.validatePath("\\\\server\\share\\x", "/cwd", true).allowed());
        assertFalse(PathValidation.validatePath("//server/share/x", "/cwd", true).allowed());
        // Tilde variants
        assertFalse(PathValidation.validatePath("~root/.ssh/id_rsa", "/cwd", true).allowed());
        // Shell expansion
        assertFalse(PathValidation.validatePath("$HOME/x", "/cwd", true).allowed());
        assertFalse(PathValidation.validatePath("%TEMP%/x", "/cwd", true).allowed());
        assertFalse(PathValidation.validatePath("=rg", "/cwd", true).allowed());
    }

    @Test
    void validatePathRejectsGlobInWrite() {
        assertFalse(PathValidation.validatePath("/allowed/dir/*.txt", "/cwd", false).allowed());
        // Read globs are permitted (caller does rule matching on the base dir).
        PathValidation.PathValidationResult r =
            PathValidation.validatePath("/allowed/dir/*.txt", "/cwd", true);
        assertTrue(r.allowed());
        assertEquals("/allowed/dir", r.resolvedPath());
    }

    @Test
    void validatePathResolvesAbsoluteAndRelative() {
        PathValidation.PathValidationResult abs =
            PathValidation.validatePath("/tmp/foo.txt", "/cwd", true);
        assertTrue(abs.allowed());
        assertEquals("/tmp/foo.txt", abs.resolvedPath());

        PathValidation.PathValidationResult rel =
            PathValidation.validatePath("bar.txt", "/cwd", true);
        assertTrue(rel.allowed());
        assertEquals("/cwd/bar.txt", rel.resolvedPath());

        // Expands tilde.
        String home = System.getProperty("user.home");
        PathValidation.PathValidationResult tilde =
            PathValidation.validatePath("~/f.txt", "/cwd", true);
        assertTrue(tilde.allowed());
        assertEquals(home + "/f.txt", tilde.resolvedPath());

        // Strips surrounding quotes.
        PathValidation.PathValidationResult quoted =
            PathValidation.validatePath("'/tmp/f.txt'", "/cwd", true);
        assertTrue(quoted.allowed());
        assertEquals("/tmp/f.txt", quoted.resolvedPath());
    }
}
