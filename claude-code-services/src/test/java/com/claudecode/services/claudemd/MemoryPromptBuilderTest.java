package com.claudecode.services.claudemd;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.FileStateCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPromptBuilderTest {

    private Path fakeHome(Path root) throws IOException {
        Path h = root.resolve("home");
        Files.createDirectories(h);
        return h;
    }

    /**
     * Builder with the auto-memory dir pinned inside {@code tmp} — the default
     * resolver would create directories under the developer's real
     * {@code ~/.claude/projects} (test-pollution lesson).
     */
    private MemoryPromptBuilder testBuilder(MemoryFileScanner scanner, Path tmp) {
        return new MemoryPromptBuilder(scanner, _ -> {
            try {
                Path d = tmp.resolve("auto-mem").resolve("memory");
                Files.createDirectories(d);
                return d;
            } catch (IOException _) {
                return null;
            }
        });
    }

    // ── unconditional files ───────────────────────────────────────────────

    @Test
    void build_returnsMergedContentWhenNoGlobs(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"), "root instructions");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude").resolve("CLAUDE.md"), "user instructions");

        MemoryFileScanner scanner = new MemoryFileScanner(home);
        String out = testBuilder(scanner, tmp).build(proj);
        assertTrue(Strings.CS.contains(out, "user instructions"), out);
        assertTrue(Strings.CS.contains(out, "root instructions"), out);

        // the model knows which scope an instruction block came from.
        assertTrue(Strings.CS.contains(out, "Contents of "), out);
        assertTrue(Strings.CS.contains(out, "(user's private global instructions for all projects)"), out);
        assertTrue(Strings.CS.contains(out, "(project instructions, checked into the codebase)"), out);
    }

    @Test
    void build_returnsEmptyWhenNothingFound(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        MemoryFileScanner scanner = new MemoryFileScanner(home);
        String out = testBuilder(scanner, tmp).build(proj);
        assertEquals("", out);
    }

    // ── frontmatter globs gating ──────────────────────────────────────────

    @Test
    void build_includesGlobFileWhenCwdMatches(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Path srcDir = Files.createDirectory(proj.resolve("src"));
        // Put a gated CLAUDE.md in proj root, gate on src/**
        Files.writeString(proj.resolve("CLAUDE.md"),
            "---\npaths:\n  - src/**\n---\n\ntypescript rules\n");

        MemoryFileScanner scanner = new MemoryFileScanner(home);
        MemoryPromptBuilder builder = testBuilder(scanner, tmp);
        // cwd = src/  → glob "src/**" relative to proj matches → include
        String matched = builder.build(srcDir);
        assertTrue(Strings.CS.contains(matched, "typescript rules"),
            "glob src/** must match cwd inside src/: " + matched);
    }

    @Test
    void build_excludesGlobFileWhenCwdMisses(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Path libDir = Files.createDirectory(proj.resolve("lib"));
        Files.writeString(proj.resolve("CLAUDE.md"),
            "---\npaths:\n  - src/**\n---\n\ntypescript rules\n");

        MemoryFileScanner scanner = new MemoryFileScanner(home);
        MemoryPromptBuilder builder = testBuilder(scanner, tmp);
        // cwd = lib/  → glob "src/**" doesn't match → skip
        String missed = builder.build(libDir);
        assertFalse(Strings.CS.contains(missed, "typescript rules"),
            "cwd=lib/ must not activate globs targeting src/**: got: " + missed);
    }

    @Test
    void build_mixesUnconditionalAndGatedFiles(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Path srcDir = Files.createDirectory(proj.resolve("src"));

        // Unconditional root
        Files.writeString(proj.resolve("CLAUDE.md"), "always-on rules");
        Path rulesDir = Files.createDirectories(proj.resolve(".claude").resolve("rules"));
        Files.writeString(rulesDir.resolve("ts.md"),
            "---\npaths:\n  - src/**\n---\n\ngated ts rules\n");

        MemoryFileScanner scanner = new MemoryFileScanner(home);
        MemoryPromptBuilder builder = testBuilder(scanner, tmp);

        // cwd = src/  → both applied
        String hit = builder.build(srcDir);
        assertTrue(Strings.CS.contains(hit, "always-on rules"));
        assertTrue(Strings.CS.contains(hit, "gated ts rules"));

        // cwd = proj root → only always-on
        String miss = builder.build(proj);
        assertTrue(Strings.CS.contains(miss, "always-on rules"));
        assertFalse(Strings.CS.contains(miss, "gated ts rules"),
            "gated file must not activate at proj root; got: " + miss);
    }

    @Test
    void buildSeedsOnlyMemoryFilesActuallyShownToTheModel(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Path rulesDir = Files.createDirectories(home.resolve(".claude").resolve("rules"));
        Path shown = rulesDir.resolve("shown.md");
        Path shownSecond = rulesDir.resolve("shown-second.md");
        Path hidden = rulesDir.resolve("hidden.md");
        Files.writeString(shown, "always shown");
        Files.writeString(shownSecond, "also shown");
        Files.writeString(hidden, "---\npaths:\n  - src/**\n---\n\nhidden at project root\n");

        FileStateCache cache = new FileStateCache();
        String out = testBuilder(new MemoryFileScanner(home), tmp).build(
            proj, List.of(),
            Set.of(MemoryType.USER, MemoryType.PROJECT, MemoryType.LOCAL), cache);

        assertTrue(Strings.CS.contains(out, "always shown"));
        assertNotNull(cache.get(shown.toAbsolutePath().toString()),
            "eager rules shown in claudeMd must be eligible for post-compact restoration");
        assertNotNull(cache.get(shownSecond.toAbsolutePath().toString()));
        assertEquals(
            cache.get(shown.toAbsolutePath().toString()).timestampMs(),
            cache.get(shownSecond.toAbsolutePath().toString()).timestampMs(),
            "2.1.197 records one eager-load observation time, so equal-recency files retain scanner order");
        assertNull(cache.get(hidden.toAbsolutePath().toString()),
            "glob-missed rules were never shown and must not enter readFileState");
    }

    // ── auto-memory MEMORY.md index (the # Memory feature's read side) ─────

    @Test
    void build_appendsAutoMemoryIndexWhenPresent(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("CLAUDE.md"), "root instructions");
        Path memDir = Files.createDirectories(tmp.resolve("auto-mem").resolve("memory"));
        Files.writeString(memDir.resolve("MEMORY.md"),
            "- [Maven quirk](maven.md) — clean install required\n");

        String out = testBuilder(new MemoryFileScanner(home), tmp).build(proj);
        assertTrue(Strings.CS.contains(out, "(user's auto-memory, persists across conversations):"),
            "auto-memory scope header missing: " + out);
        assertTrue(Strings.CS.contains(out, "[Maven quirk](maven.md)"), out);
        assertTrue(out.indexOf("root instructions") < out.indexOf("auto-memory"),
            "memory index must come after regular claudeMd blocks");
    }

    @Test
    void build_returnsMemoryIndexEvenWithoutClaudeMdFiles(@TempDir Path tmp) throws Exception {
        // Regression guard: the old early-return on empty scan would have
        // dropped the memory index for projects with no CLAUDE.md at all.
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Path memDir = Files.createDirectories(tmp.resolve("auto-mem").resolve("memory"));
        Files.writeString(memDir.resolve("MEMORY.md"), "- [Fact](f.md) — hook\n");

        String out = testBuilder(new MemoryFileScanner(home), tmp).build(proj);
        assertTrue(Strings.CS.contains(out, "[Fact](f.md)"), out);
    }

    @Test
    void build_omitsMemoryIndexWhenAbsentOrBlank(@TempDir Path tmp) throws Exception {
        Path home = fakeHome(tmp);
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        // memory dir exists but MEMORY.md absent
        String out = testBuilder(new MemoryFileScanner(home), tmp).build(proj);
        assertFalse(Strings.CS.contains(out, "auto-memory"), out);

        // blank MEMORY.md is treated as absent
        Path memDir = Files.createDirectories(tmp.resolve("auto-mem").resolve("memory"));
        Files.writeString(memDir.resolve("MEMORY.md"), "   \n");
        String out2 = testBuilder(new MemoryFileScanner(home), tmp).build(proj);
        assertFalse(Strings.CS.contains(out2, "auto-memory"), out2);
    }
}
