package com.claudecode.tools.files;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileReadIgnorePattern;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;


class GrepToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolExecutionContext ctx(Path cwd) {
        return ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory(cwd.toString()).build();
    }

    @Test
    void maxResultSizeChars_matchesReleased197() {
        assertEquals(20_000, new GrepTool().maxResultSizeChars());
    }

    @Test
    void pathParameter_searchesGivenDirectoryInsteadOfCwd(@TempDir Path cwd, @TempDir Path other) throws IOException {
        Files.writeString(other.resolve("readme.txt"), "hello from other-project\n", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "hello");
        input.put("path", other.toString());

        String result = tool.call(input, ctx(cwd));

        // Default output_mode is files_with_matches → result is the file PATH,
        // not the matching content line.
        assertTrue(Strings.CS.contains(result, "readme.txt"), result);
    }

    @Test
    void pathParameter_searchesSingleFileWithoutUsingItAsProcessCwd(@TempDir Path cwd) throws IOException {
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Path file = cwd.resolve("single-file.txt");
        Files.writeString(file, "needle in one file\n", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "needle");
        input.put("path", file.toString());
        input.put("output_mode", "content");

        String result = tool.call(input, ctx(cwd));

        assertFalse(Strings.CS.startsWith(result, "Error running ripgrep:"), result);
        assertTrue(Strings.CS.contains(result, "single-file.txt"), result);
        assertTrue(Strings.CS.contains(result, "needle in one file"), result);
    }

    @Test
    void singleFileSearch_honorsReadDenyMask(@TempDir Path cwd) throws IOException {
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Path file = cwd.resolve("denied-single-file.txt");
        Files.writeString(file, "private needle\n", StandardCharsets.UTF_8);
        ToolExecutionContext deniedCtx = ctx(cwd).withReadDenyIgnorePatterns(
            List.of(new FileReadIgnorePattern("denied-single-file.txt", cwd.toString())));

        GrepTool tool = new GrepTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "needle");
        input.put("path", file.toString());
        input.put("output_mode", "content");

        String result = tool.call(input, deniedCtx);

        assertEquals("No matches found", result);
        assertFalse(Strings.CS.contains(result, "private needle"), result);
    }

    @Test
    void noPathParameter_searchesCwd(@TempDir Path cwd) throws IOException {
        Files.writeString(cwd.resolve("in-cwd.txt"), "hello from cwd\n", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "hello");

        String result = tool.call(input, ctx(cwd));

        assertTrue(Strings.CS.contains(result, "in-cwd.txt"), result);
    }

    @Test
    void defaultOutputMode_isFilesWithMatches_returnsPathsNotContent(@TempDir Path cwd) throws IOException {
        Files.writeString(cwd.resolve("notes.txt"), "hello world from notes\n", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "hello");

        String result = tool.call(input, ctx(cwd));

        assertTrue(Strings.CS.contains(result, "notes.txt"), result);
        // In files_with_matches mode the matching content line must NOT be echoed.
        assertFalse(Strings.CS.contains(result, "hello world from notes"), result);
    }

    @Test
    void outputModeContent_returnsMatchingLines(@TempDir Path cwd) throws IOException {
        Files.writeString(cwd.resolve("notes.txt"), "hello world from notes\n", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "hello");
        input.put("output_mode", "content");

        String result = tool.call(input, ctx(cwd));

        assertTrue(Strings.CS.contains(result, "hello world from notes"), result);
    }

    @Test
    void pathParameter_missingDirectoryOutsideCwd_doesNotFalsePositiveOnCwd(@TempDir Path cwd, @TempDir Path other) throws IOException {
        Files.writeString(cwd.resolve("in-cwd.txt"), "hello from cwd\n", StandardCharsets.UTF_8);

        // from `other`, not silently fall back to matching cwd's file.
        Files.writeString(other.resolve("unrelated.txt"), "nothing interesting here\n", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "hello");
        input.put("path", other.toString());

        String result = tool.call(input, ctx(cwd));



        // found". The key invariant is that cwd's file is NOT returned (no false-positive on cwd).
        assertEquals("No files found", result);
        assertFalse(Strings.CS.contains(result, "hello from cwd"), "path outside cwd must not fall back to cwd");
    }

    /**
     * The read/deny mask must actually exclude denied files from Grep results. ripgrep anchors a
     * leading-slash --glob to the filesystem root whenever a positional PATH is present, so
     * GrepTool cannot inject the mask as a --glob; instead it searches with PATH=searchRoot
     * (reliable under the JVM) and post-filters denied paths in Java. Regression for the
     * GrepTool mask fix.
     */
    @Test
    void readDenyMask_excludesDeniedFile(@TempDir Path cwd) throws IOException {
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Files.writeString(cwd.resolve("public.txt"), "needle in public\n", StandardCharsets.UTF_8);
        Files.createDirectories(cwd.resolve("secret"));
        Files.writeString(cwd.resolve("secret").resolve("secret.txt"), "needle in secret\n", StandardCharsets.UTF_8);

        ToolExecutionContext deniedCtx = ctx(cwd).withReadDenyIgnorePatterns(
            List.of(new FileReadIgnorePattern("secret/**", cwd.toString())));

        GrepTool tool = new GrepTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "needle");
        input.put("output_mode", "content");

        String result = tool.call(input, deniedCtx);

        assertTrue(Strings.CS.contains(result, "public.txt"), "public file should be returned: " + result);
        assertFalse(Strings.CS.contains(result, "secret.txt"), "denied file must NOT appear: " + result);
    }

    /**
     * Fix A regression: when ripgrep is unavailable and GrepTool falls back to the Java regex
     * walker, the read/deny exclusion mask must STILL be applied (a denied file must not be
     * leaked). This exercises the denyMatchers wiring inside executeJavaGrep, which the rg-path
     * readDenyMask_excludesDeniedFile test above does not cover.
     */
    @Test
    void readDenyMask_excludedInJavaFallbackWhenRgUnavailable(@TempDir Path cwd) throws Exception {
        forceRipgrepUnavailable();
        try {
            Files.writeString(cwd.resolve("public.txt"), "needle in public\n", StandardCharsets.UTF_8);
            Files.createDirectories(cwd.resolve("secret"));
            Files.writeString(cwd.resolve("secret").resolve("secret.txt"), "needle in secret\n", StandardCharsets.UTF_8);

            ToolExecutionContext deniedCtx = ctx(cwd).withReadDenyIgnorePatterns(
                List.of(new FileReadIgnorePattern("secret/**", cwd.toString())));

            GrepTool tool = new GrepTool();
            ObjectNode input = mapper.createObjectNode();
            input.put("pattern", "needle");
            input.put("output_mode", "content");

            String result = tool.call(input, deniedCtx);

            assertTrue(Strings.CS.contains(result, "public.txt"), "public file should be returned in fallback: " + result);
            assertFalse(Strings.CS.contains(result, "secret.txt"), "denied file must NOT appear in the java fallback path: " + result);
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
    void rebaseToSearchRoot_makesRelativeOutputAbsolute() {
        Path searchRoot = Path.of("/tmp/search-root");
        // bare (files_with_matches) line
        assertEquals("/tmp/search-root/foo.txt", GrepTool.rebaseToSearchRoot("foo.txt", searchRoot));
        // content line: path:line:col:content
        assertEquals("/tmp/search-root/foo.txt:1:2:match",
            GrepTool.rebaseToSearchRoot("foo.txt:1:2:match", searchRoot));
        // absolute input is left untouched
        assertEquals("/abs/bar.txt", GrepTool.rebaseToSearchRoot("/abs/bar.txt", searchRoot));
        // A Windows drive colon is part of the absolute path, not rg metadata.
        assertEquals("D:\\project\\file.txt", GrepTool.rebaseToSearchRoot("D:\\project\\file.txt", searchRoot));
        assertEquals("D:\\project\\file.txt:1:match",
            GrepTool.rebaseToSearchRoot("D:\\project\\file.txt:1:match", searchRoot));
    }
}
