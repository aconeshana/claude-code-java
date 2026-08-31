package com.claudecode.services.claudemd;

import org.apache.commons.lang3.Strings;

import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryFileScannerTest {

    /** Home directory in these tests is a subdir of the tmp root so User+Project scans don't clash. */
    private Path fakeHome(Path root) throws IOException {
        Path h = root.resolve("home");
        Files.createDirectories(h);
        return h;
    }

    // ── discovery walk ─────────────────────────────────────────────────────

    @Test
    void scan_findsUserProjectAndLocal(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude").resolve("CLAUDE.md"), "user body");
        Files.writeString(proj.resolve("CLAUDE.md"), "project body");
        Files.writeString(proj.resolve("CLAUDE.local.md"), "local body");

        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(3, out.size());
        assertEquals(MemoryType.USER,    out.getFirst().type());
        assertEquals("user body",    out.getFirst().content().trim());
        assertEquals(MemoryType.PROJECT, out.get(1).type());
        assertEquals(MemoryType.LOCAL,   out.get(2).type());
    }

    @Test
    void scan_returnsEmptyWhenNoMemoryFiles(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertTrue(out.isEmpty());
    }

    @Test
    void scan_picksUpDotClaudeSubdirAndRules(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Path dotClaude = Files.createDirectories(proj.resolve(".claude"));
        Files.writeString(dotClaude.resolve("CLAUDE.md"), "dot-claude body");
        Path rulesDir = Files.createDirectory(dotClaude.resolve("rules"));
        Files.writeString(rulesDir.resolve("a.md"), "rule a");
        Files.writeString(rulesDir.resolve("b.md"), "rule b");
        Files.writeString(rulesDir.resolve("skip.txt"), "not a markdown");

        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(3, out.size(), "dot-claude + 2 rules (skip.txt ignored)");
        assertTrue(out.stream().anyMatch(f -> f.path().endsWith("a.md")));
        assertTrue(out.stream().anyMatch(f -> f.path().endsWith("b.md")));
    }

    @Test
    void rulesPreserveDirectoryEncounterOrderAndInterleavedRecursion(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Path rules = Files.createDirectories(proj.resolve(".claude/rules"));
        Files.writeString(rules.resolve("z.md"), "z");
        Path sub = Files.createDirectory(rules.resolve("sub"));
        Files.writeString(sub.resolve("k.md"), "k");
        Files.writeString(rules.resolve("a.md"), "a");

        List<String> encounter = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(rules)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    encounter.add("k.md");
                } else if (Strings.CS.endsWith(entry.getFileName().toString(), ".md")) {
                    encounter.add(entry.getFileName().toString());
                }
            }
        }

        List<String> actual = new MemoryFileScanner(home).scan(proj).stream()
            .filter(f -> f.path().startsWith(rules))
            .map(f -> f.path().getFileName().toString())
            .toList();
        assertEquals(encounter, actual,
            "TS readdir order and file/directory interleaving are cache-key significant");
    }

    // ── @include recursion ─────────────────────────────────────────────────

    @Test
    void scan_followsAtImports(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("child.md"), "child body");
        Files.writeString(proj.resolve("CLAUDE.md"), "root body\n\n@./child.md\n");

        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(2, out.size());
        assertNull(out.getFirst().parent(), "root has no parent");
        assertEquals(out.getFirst().path(), out.get(1).parent(),
            "child.md must record CLAUDE.md as its parent");
    }

    @Test
    void scan_breaksCyclesViaProcessedSet(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"), "root\n@./a.md\n");
        Files.writeString(proj.resolve("a.md"),      "a\n@./b.md\n");
        Files.writeString(proj.resolve("b.md"),      "b\n@./a.md\n"); // cycle back to a
        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(3, out.size(), "should visit root + a + b once, ignoring the a→b→a cycle");
    }

    @Test
    void extractIncludePaths_skipsFencedCodeBlocks(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("real.md"), "real content");
        Files.writeString(proj.resolve("CLAUDE.md"),
            """
                root body

                ```
                @./should-be-ignored.md
                ```

                @./real.md
                """);

        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(2, out.size(),
            "only root + real.md; the fenced @./should-be-ignored.md must not resolve");
        assertTrue(out.stream().anyMatch(f -> f.path().endsWith("real.md")));
    }

    @Test
    void extractIncludePaths_skipsInlineCodeSpans(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"),
            "See `@./no.md` for details.\n");
        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(1, out.size(), "inline `@./no.md` must be treated as code, not an import");
    }

    @Test
    void extractIncludePaths_expandsTildePath(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Path notes = home.resolve("notes.md");
        Files.writeString(notes, "notes body");
        Files.writeString(proj.resolve("CLAUDE.md"), "root body\n@~/notes.md\n");

        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(2, out.size());
        assertTrue(out.stream().anyMatch(f -> f.path().equals(notes.toAbsolutePath().normalize())));
    }

    // ── depth cap ──────────────────────────────────────────────────────────

    @Test
    void processMemoryFile_stopsAtMaxDepth(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        // Build a 7-deep chain — MAX_INCLUDE_DEPTH is 5 so only 5 files should surface.
        for (int i = 0; i < 7; i++) {
            String include = (i < 6) ? "\n@./f" + (i + 1) + ".md\n" : "";
            Files.writeString(proj.resolve("f" + i + ".md"), "level " + i + include);
        }
        Files.writeString(proj.resolve("CLAUDE.md"), "root\n@./f0.md\n");

        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        // Depth 0=root, 1=f0, 2=f1, 3=f2, 4=f3 → 5 total when MAX_DEPTH=5.
        assertEquals(5, out.size(), "must cap recursion at MAX_INCLUDE_DEPTH=5");
    }

    // ── frontmatter paths → globs ──────────────────────────────────────────

    @Test
    void scan_capturesFrontmatterGlobs(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"),
            """
                ---
                paths:
                  - src/**/*.ts
                  - lib/**/*.js
                ---

                body content here
                """);

        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(1, out.size());
        assertNotNull(out.getFirst().globs());
        assertEquals(2, out.getFirst().globs().size());
        assertEquals("src/**/*.ts", out.getFirst().globs().getFirst());
    }

    @Test
    void scan_normalizesMatchAllGlobsToNull(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"),
            "---\npaths:\n  - **\n---\n\nbody\n");

        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(1, out.size());
        assertNull(out.getFirst().globs(),
            "\"**\" alone means match-all → treated as no gate (TS parity)");
    }

    // ── silent skips ───────────────────────────────────────────────────────

    @Test
    void scan_ignoresBlankBodyAfterFrontmatterStrip(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"),
            "---\nname: only frontmatter\n---\n");
        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertTrue(out.isEmpty(), "files with no body after frontmatter strip must be skipped");
    }

    // ── html comment stripping ─────────────────────────────────────────────

    @Test
    void scan_stripsBlockLevelHtmlComments(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"),
            """
                # Rules

                <!-- private note: contact 张三 -->

                Real body here.
                <!-- another block note -->
                Trailing content.
                """);
        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(1, out.size());
        String body = out.getFirst().content();
        assertFalse(Strings.CS.contains(body, "private note"), "block HTML comment must be stripped: " + body);
        assertFalse(Strings.CS.contains(body, "another block"), "trailing block HTML comment must be stripped: " + body);
        assertTrue(Strings.CS.contains(body, "Real body here"));
        assertTrue(Strings.CS.contains(body, "Trailing content"));
    }

    @Test
    void scan_preservesInlineHtmlCommentInParagraph(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));

        Files.writeString(proj.resolve("CLAUDE.md"),
            "See docs <!-- inline aside --> for details.\n");
        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(1, out.size());
        assertTrue(Strings.CS.contains(out.getFirst().content(), "<!-- inline aside -->"),
            "inline comment inside a paragraph must be preserved");
    }

    // ── symlink dedup ──────────────────────────────────────────────────────

    @Test
    void scan_dedupsSymlinkAlias(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        // Real file lives outside project; project/rules.md is a symlink to it.
        Path real = tmp.resolve("real-rules.md");
        Files.writeString(real, "shared content");
        Path link = proj.resolve("rules.md");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | FileSystemException _) {
            // Filesystem doesn't support symlinks (Windows without dev mode) — skip test.
            return;
        }
        // Include both aliases from CLAUDE.md to try to double-load the same content.
        Files.writeString(proj.resolve("CLAUDE.md"),
            "root\n\n@./rules.md\n\n@../real-rules.md\n");

        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        // Expect only 2 entries: root CLAUDE.md + shared content (via first alias).
        // Second @include of the same underlying file must be dedup'd via realpath.
        assertEquals(2, out.size(),
            "symlink alias must not double-load the underlying file; got: " + out);
    }

    // ── claudeMdExcludes gate ──────────────────────────────────────────────

    @Test
    void scan_respectsClaudeMdExcludesGlob(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"), "keep me");
        Path rulesDir = Files.createDirectories(proj.resolve(".claude").resolve("rules"));
        Files.writeString(rulesDir.resolve("keep.md"), "keep body");
        Files.writeString(rulesDir.resolve("skip-me.md"), "skip body");

        List<String> excludes = List.of("**/skip-*.md");
        List<MemoryFileInfo> out = new MemoryFileScanner(home, excludes).scan(proj);
        assertTrue(out.stream().anyMatch(f -> f.path().endsWith("CLAUDE.md")));
        assertTrue(out.stream().anyMatch(f -> f.path().endsWith("keep.md")));
        assertFalse(out.stream().anyMatch(f -> f.path().endsWith("skip-me.md")),
            "excluded glob must skip the file; got: " + out);
    }

    @Test
    void scan_defaultConstructorEmptyExcludes(@TempDir Path tmp) throws Exception {
        // The bare (Path home) constructor keeps existing callers/tests passing
        // — no excludes means every file that matched before still matches.
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"), "body");
        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj);
        assertEquals(1, out.size());
    }

    @Test
    void scan_excludesAbsolutePathGlob(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"), "body");
        // Exclude by absolute path — must match after Path realpath resolution
        // (macOS /tmp → /private/tmp handled by tryResolveStaticPrefix).
        String absPattern = proj.toAbsolutePath().normalize().resolve("CLAUDE.md").toString();
        List<MemoryFileInfo> out =
            new MemoryFileScanner(home, List.of(absPattern)).scan(proj);
        assertTrue(out.isEmpty(),
            "absolute-path exclude must skip the exact file; got: " + out);
    }

    // ── additional dirs ────────────────────────────────────────────────────

    @Test
    void scan_ignoresAdditionalDirsWhenEnvOff(@TempDir Path tmp) throws Exception {
        // No env set → additional dirs must NOT be loaded even when passed.
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"), "primary");
        Path extra = Files.createDirectory(tmp.resolve("extra"));
        Files.writeString(extra.resolve("CLAUDE.md"), "extra content");

        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj, List.of(extra));
        assertEquals(1, out.size(),
            "additional dir must be silently ignored when env is off; got: " + out);
        assertTrue(Strings.CS.contains(out.getFirst().content(), "primary"));
    }

    @Test
    void scan_ignoresNullAndEmptyAdditionalDirs(@TempDir Path tmp) throws Exception {
        // Defensive: null/empty additional dirs list should behave same as
        // single-arg scan overload — no crash, no missing files.
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"), "body");
        MemoryFileScanner s = new MemoryFileScanner(home);
        assertEquals(1, s.scan(proj, null).size());
        assertEquals(1, s.scan(proj, List.of()).size());
    }

    // ── setting source gates ───────────────────────────────────────────────

    @Test
    void scan_gatesByEnabledScopes_projectOnly(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude").resolve("CLAUDE.md"), "user body");
        Files.writeString(proj.resolve("CLAUDE.md"), "project body");
        Files.writeString(proj.resolve("CLAUDE.local.md"), "local body");

        Set<MemoryType> only = Set.of(MemoryType.PROJECT);
        List<MemoryFileInfo> out = new MemoryFileScanner(home).scan(proj, List.of(), only);
        assertEquals(1, out.size(), "only project scope must load; got: " + out);
        assertEquals(MemoryType.PROJECT, out.getFirst().type());
    }

    @Test
    void scan_gatesByEnabledScopes_emptyLoadsNothing(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude").resolve("CLAUDE.md"), "user body");
        Files.writeString(proj.resolve("CLAUDE.md"), "project body");

        List<MemoryFileInfo> out = new MemoryFileScanner(home)
            .scan(proj, List.of(), Set.of());
        assertTrue(out.isEmpty(),
            "empty enabledScopes must produce empty output (TS SDK settingSources:[] contract)");
    }
}



