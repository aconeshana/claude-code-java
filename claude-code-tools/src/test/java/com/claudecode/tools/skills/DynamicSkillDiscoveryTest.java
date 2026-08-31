package com.claudecode.tools.skills;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.files.FileEditTool;
import com.claudecode.tools.files.FileReadTool;
import com.claudecode.tools.files.FileWriteTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicSkillDiscoveryTest {

    @Test
    void readDiscoversNestedSkillDirectoryLoadsSkillAndEmitsOneTrigger(@TempDir Path root)
        throws Exception {
        Path nested = root.resolve("packages/app");
        Path skillDir = nested.resolve(".claude/skills/nested-probe");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\ndescription: nested description\n---\nnested body\n");
        Files.writeString(nested.resolve("probe.txt"), "probe\n");

        SkillLoader loader = new SkillLoader();
        DynamicSkillTriggerSet triggers = new DynamicSkillTriggerSet();
        DynamicSkillDiscovery discovery =
            new DynamicSkillDiscovery(loader, triggers, true);
        FileReadTool read = new FileReadTool(discovery);
        var input = new ObjectMapper().createObjectNode()
            .put("file_path", "packages/app/probe.txt");
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "session").workingDirectory(root.toString()).build();

        read.call(input, context);
        read.call(input, context);

        assertEquals(Set.of(nested.resolve(".claude/skills").toString()), triggers);
        assertTrue(loader.loadAll().stream()
            .noneMatch(skill -> Strings.CS.equals("nested-probe", skill.name())));
        triggers.clear();
        assertEquals(1, loader.loadAll().stream()
            .filter(skill -> Strings.CS.equals("nested-probe", skill.name())).count());
        assertTrue(loader.loadAll().stream()
            .anyMatch(skill -> ("nested description (from packages/app/.claude/skills"
                + " — applies when working on files under packages/app/)")
                .equals(skill.description())));
    }

    @Test
    void nestedSkillCollidingWithBaseSkillGetsReleasedScopedName(@TempDir Path root)
        throws Exception {
        Path nested = root.resolve("packages/app");
        Path skillDir = nested.resolve(".claude/skills/nested-probe");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\ndescription: nested description\n---\nnested body\n");
        Files.writeString(nested.resolve("probe.txt"), "probe\n");

        SkillLoader loader = new SkillLoader();
        loader.setBundledSkills(List.of(new Skill(
            "nested-probe", "base description", List.of(), "base body",
            root.resolve("bundled/nested-probe/SKILL.md"), Skill.SkillSource.BUNDLED,
            null, null, null, Map.of())));
        DynamicSkillTriggerSet triggers = new DynamicSkillTriggerSet();
        new DynamicSkillDiscovery(loader, triggers, true)
            .discover(nested.resolve("probe.txt"), root.toString());

        triggers.clear();

        Skill scoped = loader.loadAll().stream()
            .filter(skill -> Strings.CS.equals("packages/app:nested-probe", skill.name()))
            .findFirst().orElseThrow();
        assertEquals("nested-probe", scoped.unqualifiedName());
        assertEquals("nested description (scoped to packages/app/ — use this instead of the "
            + "unscoped \"nested-probe\" skill when the files being changed are under "
            + "packages/app/)", scoped.description());
    }

    @Test
    void disabledProjectSourceDoesNotDiscoverNestedSkills(@TempDir Path root)
        throws Exception {
        Path nested = root.resolve("pkg");
        Path skillDir = nested.resolve(".claude/skills/hidden");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\ndescription: hidden\n---\nbody\n");
        Files.writeString(nested.resolve("probe.txt"), "probe\n");

        SkillLoader loader = new SkillLoader();
        Set<String> triggers = new DynamicSkillTriggerSet();
        DynamicSkillDiscovery discovery =
            new DynamicSkillDiscovery(loader, triggers, false);

        discovery.discover(nested.resolve("probe.txt"), root.toString());

        assertTrue(triggers.isEmpty());
        assertTrue(loader.loadAll().isEmpty());
    }

    @Test
    void writeDiscoversNestedSkillDirectoryBeforeCreatingFile(@TempDir Path root)
        throws Exception {
        Path nested = root.resolve("packages/app");
        Path skillRoot = nested.resolve(".claude/skills/write-probe");
        Files.createDirectories(skillRoot);
        Files.writeString(skillRoot.resolve("SKILL.md"),
            "---\ndescription: write description\n---\nwrite body\n");

        SkillLoader loader = new SkillLoader();
        DynamicSkillTriggerSet triggers = new DynamicSkillTriggerSet();
        DynamicSkillDiscovery discovery = new DynamicSkillDiscovery(loader, triggers, true);
        FileWriteTool write = new FileWriteTool(discovery);
        var input = new ObjectMapper().createObjectNode()
            .put("file_path", "packages/app/new.txt")
            .put("content", "created\n");

        write.call(input, context(root));

        assertEquals(Set.of(nested.resolve(".claude/skills").toString()), triggers);
        triggers.clear();
        assertTrue(loader.loadAll().stream()
            .anyMatch(skill -> Strings.CS.equals("write-probe", skill.name())));
        assertEquals("created\n", Files.readString(nested.resolve("new.txt")));
    }

    @Test
    void editDiscoversNestedSkillDirectoryAfterReadBeforeWriteGate(@TempDir Path root)
        throws Exception {
        Path nested = root.resolve("packages/app");
        Path skillRoot = nested.resolve(".claude/skills/edit-probe");
        Files.createDirectories(skillRoot);
        Files.writeString(skillRoot.resolve("SKILL.md"),
            "---\ndescription: edit description\n---\nedit body\n");
        Files.writeString(nested.resolve("probe.txt"), "before\n");

        SkillLoader loader = new SkillLoader();
        DynamicSkillTriggerSet triggers = new DynamicSkillTriggerSet();
        DynamicSkillDiscovery discovery = new DynamicSkillDiscovery(loader, triggers, true);
        ToolExecutionContext context = context(root);
        new FileReadTool().call(new ObjectMapper().createObjectNode()
            .put("file_path", "packages/app/probe.txt"), context);
        var input = new ObjectMapper().createObjectNode()
            .put("file_path", "packages/app/probe.txt")
            .put("old_string", "before")
            .put("new_string", "after");

        new FileEditTool(discovery).call(input, context);

        assertEquals(Set.of(nested.resolve(".claude/skills").toString()), triggers);
        triggers.clear();
        assertTrue(loader.loadAll().stream()
            .anyMatch(skill -> Strings.CS.equals("edit-probe", skill.name())));
        assertEquals("after\n", Files.readString(nested.resolve("probe.txt")));
    }

    @Test
    void gitignoredContainingDirectoryIsNotDiscovered(@TempDir Path root)
        throws Exception {
        runGit(root, "init", "-q");
        Files.writeString(root.resolve(".gitignore"), "packages/\n");
        Path nested = root.resolve("packages/app");
        Path skillRoot = nested.resolve(".claude/skills/ignored-probe");
        Files.createDirectories(skillRoot);
        Files.writeString(skillRoot.resolve("SKILL.md"),
            "---\ndescription: ignored description\n---\nignored body\n");
        Files.writeString(nested.resolve("probe.txt"), "probe\n");

        SkillLoader loader = new SkillLoader();
        Set<String> triggers = new DynamicSkillTriggerSet();
        new DynamicSkillDiscovery(loader, triggers, true)
            .discover(nested.resolve("probe.txt"), root.toString());

        assertTrue(triggers.isEmpty());
        assertTrue(loader.loadAll().stream()
            .noneMatch(skill -> Strings.CS.equals("ignored-probe", skill.name())));
    }

    private static ToolExecutionContext context(Path root) {
        return ToolExecutionContext.builder(new AbortController(), "session").workingDirectory(root.toString()).build();
    }

    private static void runGit(Path directory, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        assertEquals(0, process.waitFor());
    }
}
