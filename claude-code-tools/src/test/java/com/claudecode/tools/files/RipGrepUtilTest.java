package com.claudecode.tools.files;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;


class RipGrepUtilTest {

    @Test
    void stateDirUsesClaudeConfigHome() {
        assertEquals(ClaudePaths.CACHE_DIR.resolve("ripgrep"), RipGrepUtil.stateDir());
    }

    @Test
    void defaultCommandResolvesToBuiltinCachePathOnSupportedPlatforms() {
        String cmd = RipGrepUtil.ripgrepCommand();
        assertFalse(StringUtils.isBlank(cmd), "ripgrep command must not be blank");
        String configured = System.getenv("USE_BUILTIN_RIPGREP");
        if (configured != null && Set.of("0", "false", "no", "off")
                .contains(configured.trim().toLowerCase(Locale.ROOT))) {
            assertEquals("rg", cmd, "explicit builtin opt-out must use the system command");
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean supported = Strings.CS.contains(os, "win") || Strings.CS.contains(os, "mac")
            || Strings.CS.contains(os, "darwin") || Strings.CS.contains(os, "linux");
        if (!supported) {
            assertEquals("rg", cmd, "unknown platforms use the last-resort system command");
            return;
        }
        Path path = Path.of(cmd);
        assertTrue(path.isAbsolute(), "builtin command must be an absolute extracted path");
        assertTrue(path.normalize().startsWith(RipGrepUtil.stateDir().toAbsolutePath().normalize()),
            "builtin command must live under the ripgrep state directory: " + path);
        assertTrue(Strings.CS.equals(path.getFileName().toString(), "rg")
                || Strings.CS.equals(path.getFileName().toString(), "rg.exe"),
            "builtin command must resolve to the platform ripgrep executable");
    }

    @Test
    void statusReportsTheSelectedRuntimeMode() {
        String command = RipGrepUtil.ripgrepCommand();
        RipGrepUtil.RipgrepStatus status = RipGrepUtil.status();
        if (Strings.CS.equals("rg", command)) {
            assertEquals(RipGrepUtil.RipgrepMode.SYSTEM, status.mode());
            assertEquals("rg", status.systemPath());
        } else {
            assertEquals(RipGrepUtil.RipgrepMode.BUILTIN, status.mode());
            assertNull(status.systemPath());
        }
        assertEquals(RipGrepUtil.isAvailable(), status.working());
    }


    @Test
    void exitCode2BadRegexReturnsEmptyNotThrows(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        List<String> lines = RipGrepUtil.run(List.of("rg", "-e", "["), dir);
        assertEquals(List.of(), lines, "bad regex (exit 2, no output) must resolve to empty, not throw");
    }

    @Test
    void noMatchReturnsEmpty(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Files.createDirectories(dir); // ensure dir exists
        List<String> lines = RipGrepUtil.run(List.of("rg", "-e", "zzz-no-such-pattern-zzz"), dir);
        assertTrue(lines.isEmpty());
    }

/** Verifies recursive {@code *.md} discovery through ripgrep. */
    @Test
    void listMarkdownFiles_returnsMarkdownRecursively(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Files.createDirectories(dir.resolve("docs/sub"));
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve("a.md"), "# a");
        Files.writeString(dir.resolve("docs/sub/b.md"), "# b");
        Files.writeString(dir.resolve("notes.txt"), "not md");
        Files.writeString(dir.resolve(".git/ignored.md"), "# ignored");

        List<Path> md = RipGrepUtil.listMarkdownFiles(dir);
        Set<String> names = md.stream().map(p -> p.getFileName().toString())
            .collect(Collectors.toSet());
        assertTrue(names.contains("a.md"), "top-level .md must be found");
        assertTrue(names.contains("b.md"), "nested .md must be found (recursive)");
        assertFalse(names.contains("notes.txt"), "non-.md must be excluded");
        assertTrue(names.contains("ignored.md"), ".git .md is included (TS rg --no-ignore returns it)");
    }

    /**
     * When ripgrep is unavailable, {@link RipGrepUtil#listMarkdownFiles} falls back to a
     * native Java walk and returns the same markdown set.
     */
    @Test
    void listMarkdownFiles_fallsBackToJavaWhenRgUnavailable(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("docs/sub"));
        Files.writeString(dir.resolve("a.md"), "# a");
        Files.writeString(dir.resolve("docs/sub/b.md"), "# b");
        Files.writeString(dir.resolve("notes.txt"), "x");

        forceRipgrepUnavailable();
        try {
            List<Path> md = RipGrepUtil.listMarkdownFiles(dir);
            Set<String> names = md.stream().map(p -> p.getFileName().toString())
                .collect(Collectors.toSet());
            assertTrue(names.contains("a.md"), "fallback must find top-level .md");
            assertTrue(names.contains("b.md"), "fallback must find nested .md");
            assertFalse(names.contains("notes.txt"), "fallback must exclude non-.md");
        } finally {
            RipGrepUtil.clearAvailabilityCache();
        }
    }

/** Forces {@link RipGrepUtil#isAvailable} to return false (matches GrepToolTest helper). */
    private static void forceRipgrepUnavailable() throws Exception {
        var field = RipGrepUtil.class.getDeclaredField("availableCache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Boolean> ref = (AtomicReference<Boolean>) field.get(null);
        ref.set(Boolean.FALSE);
    }
}
