package com.claudecode.core.io;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PathUtilsTest {

    @Test
    void pathUtilitiesMirrorRelativeAndTraversalSemantics() {
        Path cwd = Path.of("/project");
        assertEquals("src/App.java", PathUtils.toRelativePath(cwd, Path.of("/project/src/App.java")));
        assertEquals("/outside/App.java", PathUtils.toRelativePath(cwd, Path.of("/outside/App.java")));
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
}
