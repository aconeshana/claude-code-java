package com.claudecode.commands.impl.config;

import com.claudecode.commands.impl.agents.AgentsCommand;
import com.claudecode.commands.impl.info.UsageCommand;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NewCommandsTest {

    // ── /add-dir ────────────────────────────────────────────────────────────

    @Test
    void addDir_noArgsReturnsUsage() {
        CommandResult r = new AddDirCommand().execute(CommandContext.minimal(), "");
        assertTrue(Strings.CS.startsWith(r.output(), "Usage: /add-dir"));
    }

    @Test
    void addDir_rejectsNonDirectory(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notdir.txt");
        Files.writeString(file, "x");
        PermissionGate gate = new PermissionGate(ToolPermissionContext.of(dir));
        CommandContext ctx = CommandContext.builder(
            "m", List::of, () -> {}, _ -> {},
            null, _ -> 0.0, dir.toString(), false)
            .permissionCommands(ProviderTestCommandPorts.permissions(gate))
            .build();
        CommandResult r = new AddDirCommand().execute(ctx, file.toString());
        assertTrue(Strings.CS.contains(r.output(), "is not a directory"), r.output());
    }

    @Test
    void addDir_addsDirectoryToGate(@TempDir Path dir) {
        Path target = dir.resolve("workspace");
        try {
            Files.createDirectory(target);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        PermissionGate gate = new PermissionGate();
        CommandContext ctx = CommandContext.builder(
            "m", List::of, () -> {}, _ -> {},
            null, _ -> 0.0, dir.toString(), false)
            .permissionCommands(ProviderTestCommandPorts.permissions(gate))
            .build();

        CommandResult r = new AddDirCommand().execute(ctx, target.toString());

        assertTrue(Strings.CS.startsWith(r.output(), "Added "), r.output());
        assertTrue(gate.currentContext().additionalDirs().keySet().stream()
            .anyMatch(p -> p.toAbsolutePath().normalize()
                .equals(target.toAbsolutePath().normalize())),
            "directory should be present on the permission gate");
    }

    @Test
    void addDir_expandsTilde() {
        Path resolved = AddDirCommand.resolveAndExpand("~/some/sub", "/ignored");
        assertTrue(resolved.startsWith(Path.of(System.getProperty("user.home"))),
            "got " + resolved);
    }

    // ── /agents ─────────────────────────────────────────────────────────────

    @Test
    void agents_handlesMissingDirectory(@TempDir Path dir) {
        CommandResult r = new AgentsCommand(dir.resolve("agents")).execute(CommandContext.minimal(), "");
        assertTrue(Strings.CS.contains(r.output(), "No agent definitions found"));
    }

    @Test
    void agents_listsDefinitions(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("reviewer.md"), "x");
        Files.writeString(dir.resolve("planner.md"), "x");
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        CommandResult r = new AgentsCommand(dir).execute(
            ProviderTestCommandPorts.withTooling(CommandContext.minimal(),
                ProviderTestCommandPorts.markdownResources()), "");
        assertTrue(Strings.CS.contains(r.output(), "planner"));
        assertTrue(Strings.CS.contains(r.output(), "reviewer"));
        assertFalse(Strings.CS.contains(r.output(), "notes"), "should not list non-md files");
    }

    // ── /usage ──────────────────────────────────────────────────────────────

    @Test
    void usage_returnsSessionAccounting() {
        CommandResult r = new UsageCommand().execute(CommandContext.minimal(), "");
        assertTrue(Strings.CS.contains(r.output(), "Total cost:"), r.output());
        assertTrue(Strings.CS.contains(r.output(), "Total duration (wall):"), r.output());
    }

    // ── /upgrade ────────────────────────────────────────────────────────────

}
