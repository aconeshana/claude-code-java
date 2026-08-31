package com.claudecode.tools.files;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileReadIgnorePattern;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.platform.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class GlobToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolExecutionContext ctx(Path cwd, List<FileReadIgnorePattern> deny) {
        return ToolExecutionContext.builder(new AbortController(), "test-session")
            .workingDirectory(cwd.toString())
            .fileStateCache(new FileStateCache())
            .nestedMemoryAttachmentTriggers(ConcurrentHashMap.newKeySet())
            .loadedNestedMemoryPaths(ConcurrentHashMap.newKeySet())
            .readDenyIgnorePatterns(deny)
            .build();
    }

    private ObjectNode input(String pattern) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("pattern", pattern);
        return n;
    }

    private String glob(GlobTool tool, ObjectNode in, Path cwd, List<FileReadIgnorePattern> deny) {
        return tool.call(in, ctx(cwd, deny));
    }

    @Test
    void basicGlobReturnsMatchingFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("b.txt"), "b");
        Files.writeString(dir.resolve("notes.md"), "md");

        String out = glob(new GlobTool(), input("*.txt"), dir, List.of());
        assertTrue(Strings.CS.contains(out, "a.txt"), out);
        assertTrue(Strings.CS.contains(out, "b.txt"), out);
        assertFalse(Strings.CS.contains(out, "notes.md"), out);
    }

    @Test
    void hiddenFilesIncludedByDefault(@TempDir Path dir) throws Exception {

        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Files.writeString(dir.resolve("visible.txt"), "v");
        Files.writeString(dir.resolve(".hidden.txt"), "h");

        String out = glob(new GlobTool(), input("*.txt"), dir, List.of());
        assertTrue(Strings.CS.contains(out, "visible.txt"), out);
        assertTrue(Strings.CS.contains(out, ".hidden.txt"), "hidden files must be included by default");
    }

    @Test
    void hiddenFilesExcludedWhenIncludeHiddenFalse(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(RipGrepUtil.isAvailable(),
            "this assertion exercises ripgrep's native hidden-file default");
        Files.writeString(dir.resolve("visible.txt"), "v");
        Files.writeString(dir.resolve(".hidden.txt"), "h");

        // rg's default hidden handling is environment-dependent (global config / rg version):
        // some rg builds include hidden files even without --hidden, in which case

        // them. Only assert exclusion when rg actually skips hidden without --hidden.
        List<String> baseline = RipGrepUtil.run(
            List.of("rg", "--files", "--no-ignore", "--glob", "*.txt"), dir);
        boolean rgSkipsHiddenByDefault = !baseline.contains(".hidden.txt");
        Assumptions.assumeTrue(rgSkipsHiddenByDefault,
            "rg includes hidden by default in this environment; cannot assert exclusion");

        ObjectNode in = input("*.txt");
        in.put("include_hidden", false);
        String out = glob(new GlobTool(), in, dir, List.of());
        assertTrue(Strings.CS.contains(out, "visible.txt"), out);
        assertFalse(Strings.CS.contains(out, ".hidden.txt"), "include_hidden=false must exclude hidden files");
    }

    @Test
    void excludeFiltersMatches(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("secret.txt"), "s");

        ObjectNode in = input("*.txt");
        in.put("exclude", "secret.txt");
        String out = glob(new GlobTool(), in, dir, List.of());
        assertTrue(Strings.CS.contains(out, "a.txt"), out);
        assertFalse(Strings.CS.contains(out, "secret.txt"), "exclude must drop secret.txt");
    }

    @Test
    void maxResultsTruncates(@TempDir Path dir) throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.writeString(dir.resolve("f" + i + ".txt"), "x");
        }
        ObjectNode in = input("*.txt");
        in.put("max_results", 2);
        String out = glob(new GlobTool(), in, dir, List.of());
        long count = out.lines().filter(l -> Strings.CS.endsWith(l, ".txt")).count();
        assertEquals(2, count, "must cap at max_results");
        assertTrue(Strings.CS.contains(out, "truncated"), "must report truncation");
    }

    @Test
    void denyReadRuleExcludesFile(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("secret.txt"), "s");

        // A file-read deny rule (bare pattern, matches anywhere under search dir).
        List<FileReadIgnorePattern> deny = List.of(FileReadIgnorePattern.anywhere("secret.txt"));
        String out = glob(new GlobTool(), input("*.txt"), dir, deny);
        assertTrue(Strings.CS.contains(out, "a.txt"), out);
        assertFalse(Strings.CS.contains(out, "secret.txt"), "read/deny rule must exclude secret.txt");
    }

    @Test
    void denyReadRuleExcludedInJavaFallbackWhenRgUnavailable(@TempDir Path dir) throws Exception {
        // Fix C regression: when ripgrep is unavailable and GlobTool.call falls back to the
        // java.nio Files.walkFileTree path, the read/deny exclusion mask must STILL be applied
        // (a denied file must not be leaked). This exercises the denyMatchers wiring inside
        // walkFileTreeGlob, which the rg-path deny tests above do not cover.
        forceRipgrepUnavailable();
        try {
            Files.writeString(dir.resolve("a.txt"), "a");
            Files.writeString(dir.resolve("secret.txt"), "s");
            List<FileReadIgnorePattern> deny = List.of(FileReadIgnorePattern.anywhere("secret.txt"));
            String out = glob(new GlobTool(), input("*.txt"), dir, deny);
            assertTrue(Strings.CS.contains(out, "a.txt"), "public file must be returned in fallback: " + out);
            assertFalse(Strings.CS.contains(out, "secret.txt"),
                "read/deny rule must exclude secret.txt in the java fallback path");
        } finally {
            RipGrepUtil.clearAvailabilityCache();
        }
    }

/** Forces {@link RipGrepUtil#isAvailable} to return false so {@code call} uses the Java fallback. */
    private static void forceRipgrepUnavailable() throws Exception {
        var field = RipGrepUtil.class.getDeclaredField("availableCache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Boolean> ref =
            (AtomicReference<Boolean>) field.get(null);
        ref.set(Boolean.FALSE);
    }

    @Test
    void absolutePatternRerootsToBaseDir(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("b.txt"), "b");

        // An absolute pattern is rerooted: the dir becomes the search dir, the relative
// remainder is passed to rg.
        ObjectNode in = input(dir.resolve("*.txt").toString());
        String out = glob(new GlobTool(), in, dir, List.of());
        assertTrue(Strings.CS.contains(out, "a.txt"), out);
        assertTrue(Strings.CS.contains(out, "b.txt"), out);
    }

    @Test
    void pathArgumentSearchesDifferentDir(@TempDir Path dir) throws Exception {
        // cwd != searchDir triggers rg with a non-cwd search dir; the runRipgrep fix sets rg's
        // process cwd to searchDir (not the real cwd) so output stays searchDir-relative and the
        // read/deny --glob mask anchors to the right directory.
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Path sub = dir.resolve("sub");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("x.txt"), "x");
        Files.writeString(sub.resolve("y.txt"), "y");

        ObjectNode in = input("*.txt");
        in.put("path", sub.toString());
        String out = glob(new GlobTool(), in, dir, List.of());
        assertTrue(Strings.CS.contains(out, "sub/x.txt"), out);
        assertTrue(Strings.CS.contains(out, "sub/y.txt"), out);
        // Regression guard: must NOT double-prefix the search dir.
        assertFalse(Strings.CS.contains(out, sub.toString() + sub.toString()),
            "search dir must not be concatenated twice: " + out);
    }

    @Test
    void windowsWildcardPatternsResolveTheirAbsoluteBaseLexically() {
        assertEquals("D:\\project\\src",
            GlobTool.absoluteBaseDirectory("D:\\project\\src\\*.java", Platform.WIN32));
        assertEquals("D:\\project\\src",
            GlobTool.absoluteBaseDirectory("/d/project/src/*.java", Platform.WIN32));
        assertEquals("\\\\server\\share\\src",
            GlobTool.absoluteBaseDirectory(
                "\\\\server\\share\\src\\*.java", Platform.WIN32));
        assertNull(GlobTool.absoluteBaseDirectory("src\\*.java", Platform.WIN32));
    }

    @Test
    void windowsSearchPatternsUseRipgrepCompatibleSeparators() {
        assertEquals("win-tools/*.txt",
            GlobTool.searchPatternForPlatform("win-tools\\*.txt", Platform.WIN32));
        assertEquals("win-tools\\*.txt",
            GlobTool.searchPatternForPlatform("win-tools\\*.txt", Platform.LINUX));
    }

    @Test
    void denyReadRuleAnchorsToPathDir(@TempDir Path dir) throws Exception {
        // Regression for the cwd-anchoring bug: a read/deny rule under a `path` argument (so
        // searchDir != cwd) must exclude the file inside that dir, not a same-named file under
        // cwd. Before the fix rg anchored the leading-slash mask to cwd and missed it.
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Path sub = dir.resolve("sub");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("a.txt"), "a");
        Files.writeString(sub.resolve("secret.txt"), "s");
        Files.writeString(dir.resolve("secret.txt"), "top-level, must NOT be affected by mask");

        List<FileReadIgnorePattern> deny = List.of(FileReadIgnorePattern.anywhere("secret.txt"));
        ObjectNode in = input("*.txt");
        in.put("path", sub.toString());
        String out = glob(new GlobTool(), in, dir, deny);
        assertTrue(Strings.CS.contains(out, "sub/a.txt"), out);
        assertFalse(Strings.CS.contains(out, "sub/secret.txt"), "deny mask must exclude secret.txt under path dir");
    }

    @Test
    void missingPatternReturnsError() {
        ObjectNode in = MAPPER.createObjectNode();
        String out = new GlobTool().call(in, ctx(Path.of("."), List.of()));
        assertNotNull(out);
        assertTrue(Strings.CS.startsWith(out, "Error:"), out);
    }

    @Test
    void safeRelativizeUsesPathSegmentBoundary() {
        // Regression for the /a/foo vs /a/foobar prefix bug: a plain startsWith would
        // wrongly relativize /a/foobar under /a/foo. It must be returned absolute.
        assertEquals("/a/foobar", GlobTool.safeRelativize(Path.of("/a/foo"), Path.of("/a/foobar")));
        // True descendant still relativizes.
        assertEquals("x.txt", GlobTool.safeRelativize(Path.of("/a/foo"), Path.of("/a/foo/x.txt")));
        // Sibling/unrelated directory returns absolute.
        assertEquals("/b/bar.txt", GlobTool.safeRelativize(Path.of("/a/foo"), Path.of("/b/bar.txt")));
    }
}
