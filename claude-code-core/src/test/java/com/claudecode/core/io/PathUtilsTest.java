package com.claudecode.core.io;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Locale;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PathUtilsTest {

    private static boolean isWindowsHost() {
        return Strings.CS.contains(System.getProperty("os.name", "").toLowerCase(Locale.ROOT), "win");
    }

    @Test
    void pathUtilitiesMirrorRelativeAndTraversalSemantics() {
        // Build paths from real filesystem pieces: Path.of("/project") is only
        // absolute on POSIX hosts — on Windows it is drive-relative and its
        // relativize output uses the platform separator.
        Path cwd = Path.of("project").toAbsolutePath().normalize();
        assertEquals(Path.of("src", "App.java").toString(),
            PathUtils.toRelativePath(cwd, cwd.resolve("src").resolve("App.java")));
        Path outside = cwd.resolveSibling("outside").resolve("App.java");
        assertEquals(outside.toString(), PathUtils.toRelativePath(cwd, outside));
        assertTrue(PathUtils.containsPathTraversal("../secret"));
        assertTrue(PathUtils.containsPathTraversal("a\\..\\secret"));
        assertTrue(PathUtils.containsPathTraversal("src/../main"));
    }

    @Test
    void matchesNodePathExtname() {
        assertEquals(".html", PathUtils.extensionOf(Path.of("index.html")));
        assertEquals(".md", PathUtils.extensionOf(Path.of("index.coffee.md")));
        assertEquals("", PathUtils.extensionOf(Path.of("index")));
        assertEquals(".", PathUtils.extensionOf(Path.of("index.")));
        assertEquals("", PathUtils.extensionOf(Path.of("")));
        assertEquals("", PathUtils.extensionOf(Path.of(".")));
        assertEquals("", PathUtils.extensionOf(Path.of("..")));
        assertEquals("", PathUtils.extensionOf(Path.of(".index")));
        assertEquals(".md", PathUtils.extensionOf(Path.of(".index.md")));
        assertEquals(".", PathUtils.extensionOf(Path.of("foo.bar.")));
        assertEquals(".md", PathUtils.extensionOf(Path.of("a/b/c.md")));
        assertEquals("", PathUtils.extensionOf(Path.of("/abs/path.to/x")));
        assertEquals(".md", PathUtils.extensionOf(Path.of("README.MD")));
        assertEquals("", PathUtils.extensionOf(Path.of(".gitignore")));
        assertEquals("", PathUtils.extensionOf(Path.of(".claude")));
        assertEquals("", PathUtils.extensionOf(Path.of("Makefile")));
        assertEquals(".gz", PathUtils.extensionOf(Path.of("x.tar.gz")));
        assertEquals(".", PathUtils.extensionOf(Path.of("file.")));
        assertEquals(".b", PathUtils.extensionOf(Path.of("a..b")));
    }

    @Test
    void nullSafe() {
        assertEquals("", PathUtils.extensionOf(null));
        assertEquals("", PathUtils.extensionOf(Path.of("/")));
    }

    @Test
    void expandTildePrefixPreservesNonTildePaths() {
        String home = System.getProperty("user.home");
        assertNull(PathUtils.expandTilde(null));
        assertEquals(home, PathUtils.expandTilde("~"));
        assertEquals(home + "/work", PathUtils.expandTilde("~/work"));
        assertEquals("~other/work", PathUtils.expandTilde("~other/work"));
        assertEquals("relative/work", PathUtils.expandTilde("relative/work"));
    }

    /**
     * The permission layer ({@code PathValidation.expandTilde}) accepts the backslash
     * separator form on Windows and resolves it to the user's home; the tool layer
     * ({@code PathUtils.expandTilde}, used by FileRead/FileEdit/FileWrite/Grep/Glob)
     * must resolve the same input to the same file or an approved edit lands elsewhere.
     */
    @Test
    void expandTildeExpandsBackslashHomeOnWindows() {
        Assumptions.assumeTrue(isWindowsHost());
        String home = System.getProperty("user.home");
        assertEquals(home + "\\work", PathUtils.expandTilde("~\\work"));
    }

    @Test
    void expandTildeKeepsBackslashHomeLiteralOnNonWindows() {
        Assumptions.assumeFalse(isWindowsHost());
        assertEquals("~\\work", PathUtils.expandTilde("~\\work"));
    }

    @Test
    void expandPathResolvesBackslashTildeUnderHomeOnWindows() {
        Assumptions.assumeTrue(isWindowsHost());
        Path resolved = PathUtils.expandPath("~\\x.txt", "D:\\somewhere\\else");
        assertEquals(Path.of(System.getProperty("user.home"), "x.txt"), resolved);
    }

    @Test
    void abbreviateTildeAbbreviatesBothSeparatorsOnWindows() {
        Assumptions.assumeTrue(isWindowsHost());
        String home = System.getProperty("user.home");
        assertEquals("~\\work", PathUtils.abbreviateTilde(home + "\\work"));
        assertEquals("~/work", PathUtils.abbreviateTilde(home + "/work"));
        assertEquals("~", PathUtils.abbreviateTilde(home));
        assertEquals(home + "-sibling\\work", PathUtils.abbreviateTilde(home + "-sibling\\work"));
    }
}
