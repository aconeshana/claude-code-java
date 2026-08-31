package com.claudecode.commands.impl.integration;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;


class SkillsCommandTest {

    private final SkillsCommand command = new SkillsCommand();

    private static CommandContext.Builder builder(Path cwd) {
        return CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0,
            cwd.toString(), false);
    }

    @Test
    void metadata_matchesTs() {
        assertEquals("skills", command.name());
        assertEquals("List available skills", command.description());
        assertTrue(command.aliases().isEmpty(), "TS /skills has no aliases");
    }

    @Test
    void launcherWired_opensDialogAndSkips(@TempDir Path cwd) {
        AtomicInteger opened = new AtomicInteger();
        CommandContext ctx = builder(cwd)
            .skillsDialogLauncher(opened::incrementAndGet)
            .build();

        CommandResult r = command.execute(ctx, "");

        assertEquals(1, opened.get(), "must open the interactive dialog");
        assertTrue(r.silent(), "dialog owns the UI — result must be skip()/silent");
    }

    @Test
    void noLauncher_fallsBackToTextListing_emptyState(@TempDir Path cwd) {
        CommandContext ctx = builder(cwd).build();

        CommandResult r = command.execute(ctx, "");

        assertFalse(r.silent());
        assertTrue(Strings.CS.contains(r.output(), "No skills found"),
            "headless fallback shows the empty-state message; got: " + r.output());
    }

    @Test
    void noLauncher_listsSkillFilesAndDirs(@TempDir Path cwd) throws IOException {
        Path skillsDir = cwd.resolve(".claude/skills");
        Files.createDirectories(skillsDir.resolve("alpha"));
        Files.writeString(skillsDir.resolve("alpha/SKILL.md"), "---\nname: alpha\n---\nbody");
        Files.writeString(skillsDir.resolve("beta.md"), "---\nname: beta\n---\nbody");

        CommandContext ctx = builder(cwd).build();
        CommandResult r = command.execute(ctx, "");

        assertTrue(Strings.CS.contains(r.output(), "alpha"), "must list <name>/SKILL.md dir skills");
        assertTrue(Strings.CS.contains(r.output(), "beta"), "must list bare <name>.md skills");
        assertTrue(Strings.CS.contains(r.output(), "2 skills"), "must show the count; got: " + r.output());
    }

    @Test
    void removedSubcommands_areNotSpecialCased(@TempDir Path cwd) {
        // "add my-skill" used to create a file; now it's just args to the
        // viewer, which lists (headless) — it must NOT write anything.
        CommandContext ctx = builder(cwd).build();
        command.execute(ctx, "add my-skill");
        assertFalse(Files.exists(cwd.resolve(".claude/skills/my-skill.md")),
            "the removed 'add' subcommand must not create files anymore");
    }
}
