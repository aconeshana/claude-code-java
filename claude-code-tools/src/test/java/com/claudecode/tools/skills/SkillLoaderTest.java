package com.claudecode.tools.skills;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class SkillLoaderTest {

    @TempDir
    Path tempDir;

    private SkillLoader loader;

    @BeforeEach
    void setUp() {
        loader = new SkillLoader();
    }

    @Test
    void loadFromDirectoryWithSkillFiles() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir.resolve("coding-standards"));
        Files.createDirectories(skillsDir.resolve("code-review"));

        Files.writeString(skillsDir.resolve("coding-standards/SKILL.md"), """
                ---
                name: coding-standards
                description: Coding standards for the project
                ---
                Follow these coding standards...
                """);

        Files.writeString(skillsDir.resolve("code-review/SKILL.md"), """
                ---
                name: code-review
                description: Code review guidelines
                ---
                When reviewing code...
                """);

        List<Skill> skills = loader.loadFromDirectory(skillsDir, Skill.SkillSource.PROJECT);

        assertEquals(2, skills.size());
    }

    @Test
    void loadFromDirectory_scansSkillMdSubdirectoryLayout() throws IOException {

        // the discovery bug where only bare <name>.md files were scanned, so a
        // real ~/.claude/skills/ (all <name>/SKILL.md) surfaced zero skills.
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir.resolve("agent-reach"));
        Files.writeString(skillsDir.resolve("agent-reach/SKILL.md"), """
                ---
                name: agent-reach
                description: Use the internet across many platforms
                ---
                Body.
                """);
        // A directory without SKILL.md must be skipped, not errored.
        Files.createDirectories(skillsDir.resolve("not-a-skill"));

        List<Skill> skills = loader.loadFromDirectory(skillsDir, Skill.SkillSource.USER);

        assertEquals(1, skills.size());
        assertEquals("agent-reach", skills.getFirst().name());
        assertTrue(Strings.CS.endsWith(skills.getFirst().sourceFile().toString(), "agent-reach/SKILL.md"));
    }

    @Test
    void loadFromDirectory_skillMdNameFallsBackToDirName() throws IOException {

        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir.resolve("my-tool"));
        Files.writeString(skillsDir.resolve("my-tool/SKILL.md"), """
                ---
                description: no name field here
                ---
                Body.
                """);

        List<Skill> skills = loader.loadFromDirectory(skillsDir, Skill.SkillSource.USER);

        assertEquals(1, skills.size());
        assertEquals("my-tool", skills.getFirst().name());
    }

    @Test
    void loadFromDirectory_descriptionFallsBackToFirstMarkdownHeading() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir.resolve("eval-harness"));
        Files.writeString(skillsDir.resolve("eval-harness/SKILL.md"), """
                ---
                name: eval-harness
                ---
                # Eval Harness Skill

                Details.
                """);

        List<Skill> skills = loader.loadFromDirectory(skillsDir, Skill.SkillSource.USER);

        assertEquals("Eval Harness Skill", skills.getFirst().description());
    }

    @Test
    void loadFromDirectory_ignoresFlatMarkdownFiles() throws IOException {

        // deprecated /commands/ loader and must not leak into skill discovery.
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir.resolve("dir-skill"));
        Files.writeString(skillsDir.resolve("dir-skill/SKILL.md"),
            "---\nname: dir-skill\ndescription: d\n---\nbody");
        Files.writeString(skillsDir.resolve("flat-skill.md"),
            "---\nname: flat-skill\ndescription: d\n---\nbody");

        List<Skill> skills = loader.loadFromDirectory(skillsDir, Skill.SkillSource.USER);

        assertEquals(List.of("dir-skill"), skills.stream().map(Skill::name).toList());
    }

    @Test
    void loadFromDirectory_frontmatterNameIsDisplayOnly() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir.resolve("directory-name"));
        Files.writeString(skillsDir.resolve("directory-name/SKILL.md"), """
                ---
                name: Friendly Display Name
                description: Description
                ---
                Body.
                """);

        List<Skill> skills = loader.loadFromDirectory(skillsDir, Skill.SkillSource.USER);

        assertEquals(1, skills.size());
        assertEquals("directory-name", skills.getFirst().name(),
            "TS uses the directory entry name as the callable skill name");
    }

    @Test
    void loadFromDirectory_loadsSkillAtOneMillionByteLimit() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        writeSizedSkill(skillsDir, "at-limit", 1_000_000);

        List<Skill> skills = loader.loadFromDirectory(skillsDir, Skill.SkillSource.USER);

        assertEquals(List.of("at-limit"), skills.stream().map(Skill::name).toList());
    }

    @Test
    void loadFromDirectory_skipsSkillOverOneMillionByteLimit() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        writeSizedSkill(skillsDir, "over-limit", 1_000_001);

        List<Skill> skills = loader.loadFromDirectory(skillsDir, Skill.SkillSource.USER);

        assertTrue(skills.isEmpty());
    }

    @Test
    void loadFromNonExistentDirectory() {
        List<Skill> skills = loader.loadFromDirectory(
                tempDir.resolve("nonexistent"), Skill.SkillSource.PROJECT);
        assertTrue(skills.isEmpty());
    }

    @Test
    void loadAllFromMultipleSources() throws IOException {
        Path managedDir = tempDir.resolve("managed");
        Path userDir = tempDir.resolve("user");
        Files.createDirectories(managedDir.resolve("base-skill"));
        Files.createDirectories(userDir.resolve("custom-skill"));

        Files.writeString(managedDir.resolve("base-skill/SKILL.md"), """
                ---
                name: base-skill
                description: Base skill
                ---
                Base content.
                """);

        Files.writeString(userDir.resolve("custom-skill/SKILL.md"), """
                ---
                name: custom-skill
                description: Custom skill
                ---
                Custom content.
                """);

        loader.addSource(Skill.SkillSource.MANAGED, managedDir);
        loader.addSource(Skill.SkillSource.USER, userDir);

        List<Skill> skills = loader.loadAll();
        assertEquals(2, skills.size());
    }

    @Test
    void loadAll_reusesSnapshotUntilExplicitlyInvalidated() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Path skillFile = skillsDir.resolve("cached/SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, "---\ndescription: first\n---\nFirst body");
        loader.addSource(Skill.SkillSource.USER, skillsDir);

        List<Skill> first = loader.loadAll();
        Files.writeString(skillFile, "---\ndescription: second\n---\nSecond body");
        List<Skill> cached = loader.loadAll();

        assertSame(first, cached);
        assertEquals("first", cached.getFirst().description());

        loader.invalidateCache();
        List<Skill> refreshed = loader.loadAll();

        assertNotSame(first, refreshed);
        assertEquals("second", refreshed.getFirst().description());
    }

    @Test
    void skillWithoutNameUseFilename() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir.resolve("unnamed"));

        Files.writeString(skillsDir.resolve("unnamed/SKILL.md"), """
                ---
                description: No name field
                ---
                Content without name.
                """);

        List<Skill> skills = loader.loadFromDirectory(skillsDir, Skill.SkillSource.PROJECT);
        assertEquals(1, skills.size());
        assertEquals("unnamed", skills.getFirst().name());
    }

    @Test
    void loadAll_preservesSameNamedSkillsInSourcePriorityOrder() throws IOException {
        Path dir1 = tempDir.resolve("dir1");
        Path dir2 = tempDir.resolve("dir2");
        Files.createDirectories(dir1.resolve("shared-skill"));
        Files.createDirectories(dir2.resolve("shared-skill"));

        Files.writeString(dir1.resolve("shared-skill/SKILL.md"), """
                ---
                description: From dir1
                ---
                Content 1.
                """);

        Files.writeString(dir2.resolve("shared-skill/SKILL.md"), """
                ---
                description: From dir2
                ---
                Content 2.
                """);

        loader.addSource(Skill.SkillSource.MANAGED, dir1);
        loader.addSource(Skill.SkillSource.USER, dir2);

        List<Skill> skills = loader.loadAll();
        assertEquals(2, skills.size());
        assertEquals(List.of("From dir1", "From dir2"),
            skills.stream().map(Skill::description).toList());
    }

    @Test
    void loadLegacyCommands_recursesNamespacesAndSkipsModelDisabledEntries() throws IOException {
        Path commandsDir = tempDir.resolve("commands");
        Files.createDirectories(commandsDir.resolve("ccpanes"));
        Files.writeString(commandsDir.resolve("build-fix.md"), """
                ---
                description: Fix the build
                ---
                Body.
                """);
        Files.writeString(commandsDir.resolve("ccpanes/workspace.md"), """
                ---
                description: Open a workspace
                ---
                Body.
                """);
        Files.writeString(commandsDir.resolve("setup-pm.md"), """
                ---
                description: Configure package manager
                disable-model-invocation: true
                ---
                Body.
                """);

        loader.addLegacyCommandsSource(Skill.SkillSource.USER, commandsDir);

        List<Skill> skills = loader.loadAll();
        assertEquals(List.of("build-fix", "ccpanes:workspace"),
            skills.stream().map(Skill::name).toList());
    }

    @Test
    void loadLegacyCommands_usesLocaleOrderLikeJavaScriptLocaleCompare() throws IOException {
        Path commandsDir = tempDir.resolve("commands");
        Files.createDirectories(commandsDir.resolve("ccpanes"));
        Files.writeString(commandsDir.resolve("ccpanes/workspace-migrate.md"),
            "---\ndescription: migrate\n---\nbody");
        Files.writeString(commandsDir.resolve("ccpanes/workspace.md"),
            "---\ndescription: workspace\n---\nbody");

        List<Skill> skills = loader.loadLegacyCommandsFromDirectory(
            commandsDir, Skill.SkillSource.USER);

        assertEquals(List.of("ccpanes:workspace", "ccpanes:workspace-migrate"),
            skills.stream().map(Skill::name).toList());
    }

    @Test
    void loadAll_deduplicatesSamePhysicalSkillFile(@TempDir Path temp) throws IOException {
        Path realRoot = temp.resolve("real");
        Files.createDirectories(realRoot.resolve("shared"));
        Files.writeString(realRoot.resolve("shared/SKILL.md"), """
                ---
                name: shared-skill
                description: Shared physical file
                ---
                Body.
                """);
        Path linkedRoot = temp.resolve("linked");
        try {
            Files.createSymbolicLink(linkedRoot, realRoot);
        } catch (UnsupportedOperationException | IOException _) {

            return;
        }

        loader.addSource(Skill.SkillSource.MANAGED, realRoot);
        loader.addSource(Skill.SkillSource.USER, linkedRoot);

        assertEquals(1, loader.loadAll().size());
    }

    @Test
    void loadAll_doesNotDeduplicateDistinctHardLinkPaths(@TempDir Path temp) throws IOException {
        Path firstRoot = temp.resolve("first");
        Path secondRoot = temp.resolve("second");
        Files.createDirectories(firstRoot.resolve("one"));
        Files.createDirectories(secondRoot.resolve("two"));
        Path firstFile = firstRoot.resolve("one/SKILL.md");
        Files.writeString(firstFile, "---\ndescription: linked content\n---\nBody.");
        try {
            Files.createLink(secondRoot.resolve("two/SKILL.md"), firstFile);
        } catch (UnsupportedOperationException | IOException _) {
            // Some filesystems do not support hard links.
            return;
        }

        loader.addSource(Skill.SkillSource.MANAGED, firstRoot);
        loader.addSource(Skill.SkillSource.USER, secondRoot);

        assertEquals(List.of("one", "two"),
            loader.loadAll().stream().map(Skill::name).toList(),
            "TS deduplicates canonical paths, not files that merely share an inode");
    }

    private static void writeSizedSkill(Path root, String name, int byteCount)
            throws IOException {
        Path skillFile = root.resolve(name).resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        String prefix = "---\ndescription: Sized skill\n---\n";
        int paddingBytes = byteCount - prefix.getBytes(StandardCharsets.UTF_8).length;
        Files.writeString(skillFile, prefix + "x".repeat(paddingBytes),
            StandardCharsets.UTF_8);
        assertEquals(byteCount, Files.size(skillFile));
    }
}
