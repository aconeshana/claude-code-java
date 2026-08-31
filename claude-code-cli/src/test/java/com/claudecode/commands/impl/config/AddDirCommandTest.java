package com.claudecode.commands.impl.config;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.testing.FakeSettingsManagementPort;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link AddDirCommand}'s arg routing (empty vs path, launcher present vs absent) and
 * {@link AddDirCommand#applyAddDirectory} (session add optional local-settings persistence).
 */
class AddDirCommandTest {

    @TempDir Path tempDir;

    private PermissionGate gate;
    private Path cwd;

    @BeforeEach
    void setUp() {
        // Gate's working directory deliberately outside tempDir's project-under-test
        // subtree, so directories created directly under tempDir count as new
        // ("Success") rather than "already accessible" — the latter is exercised
        // explicitly below with a directory placed inside cwd.
        cwd = tempDir.resolve("cwd");
        gate = new PermissionGate(ToolPermissionContext.of(cwd));
    }

    private static final AddDirCommand CMD = new AddDirCommand();

    // ── metadata ─────────────────────────────────────────────────────────────

    @Test
    void argumentHint_isPath() {
        assertEquals("<path>", CMD.argumentHint());
    }

    @Test
    void description_matchesTs() {
        assertEquals("Add a new working directory", CMD.description());
    }

    @Test
    void aliases_isEmpty() {
        assertTrue(CMD.aliases().isEmpty());
    }

    // ── no args ──────────────────────────────────────────────────────────────

    @Test
    void noArgs_launcherPresent_invokesWithNullAndSkips() {
        AtomicReference<String> received = new AtomicReference<>("unset");
        CommandContext ctx = ctxWith(gate, received::set);

        CommandResult r = CMD.execute(ctx, "");

        assertNull(received.get());
        assertTrue(r.silent());
    }

    @Test
    void noArgs_launcherNull_fallsBackToUsageText() {
        CommandResult r = CMD.execute(ctxWith(gate, null), "");
        assertTrue(Strings.CS.contains(r.output(), "Usage: /add-dir <path>"));
    }

    // ── path argument ────────────────────────────────────────────────────────

    @Test
    void validPath_launcherPresent_invokesWithResolvedPathAndSkips() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("some-project"));
        AtomicReference<String> received = new AtomicReference<>();
        CommandContext ctx = ctxWith(gate, received::set);

        CommandResult r = CMD.execute(ctx, dir.toString());

        assertEquals(dir.toString(), received.get());
        assertTrue(r.silent());
    }

    @Test
    void validPath_launcherNull_addsForSessionDirectly() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("some-project"));
        CommandResult r = CMD.execute(ctxWith(gate, null), dir.toString());

        assertTrue(Strings.CS.contains(r.output(), "Added " + dir + " as a working directory for this session"));
        assertTrue(Strings.CS.contains(r.output(), "/permissions to manage"));
        assertTrue(gate.currentContext().additionalDirs().containsKey(dir));
    }

    @Test
    void invalidPath_reportsHelpMessage() {
        Path missing = tempDir.resolve("does-not-exist");
        CommandResult r = CMD.execute(ctxWith(gate, _ -> fail("launcher must not open for an invalid path")),
            missing.toString());

        assertTrue(Strings.CS.contains(r.output(), "was not found"), r.output());
    }

    @Test
    void alreadyInWorkingDirectory_reportsHelpMessage() throws IOException {
        Files.createDirectories(cwd);
        Path child = Files.createDirectory(cwd.resolve("child"));
        CommandResult r = CMD.execute(ctxWith(gate, _ -> fail("launcher must not open when already covered")),
            child.toString());

        assertTrue(Strings.CS.contains(r.output(), "already accessible"), r.output());
    }

    @Test
    void noPermissionGate_reportsFallbackMessage() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("some-project"));
        CommandContext ctx = ctxWithoutGate();

        CommandResult r = CMD.execute(ctx, dir.toString());
        assertTrue(Strings.CS.contains(r.output(), "Permission gate not wired"));
    }

    // ── applyAddDirectory (dialog confirm entry point) ──────────────────────

    @Test
    void applyAddDirectory_sessionOnly_addsToGateWithoutPersisting() {
        Path dir = tempDir.resolve("session-only-dir");
        CommandContext ctx = ctxWith(gate, null);

        CommandResult r = CMD.applyAddDirectory(ctx, dir.toString(), false);

        assertTrue(Strings.CS.contains(r.output(), "for this session"));
        assertFalse(Strings.CS.contains(r.output(), "saved to local settings"));
        assertTrue(gate.currentContext().additionalDirs().containsKey(dir));
        assertFalse(Files.isReadable(tempDir.resolve(".claude/settings.local.json")));
    }

    @Test
    void applyAddDirectory_remember_addsToGateAndPersists() throws IOException {
        Path dir = tempDir.resolve("remembered-dir");
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        CommandContext ctx = CommandContext.builder(
                "m", List::of, () -> {}, _ -> {}, null, _ -> 0.0,
                tempDir.toString(), false)
            .permissionCommands(ProviderTestCommandPorts.permissions(gate))
            .settingsManagement(settings)
            .build();

        CommandResult r = CMD.applyAddDirectory(ctx, dir.toString(), true);

        assertTrue(Strings.CS.contains(r.output(), "and saved to local settings"), r.output());
        assertTrue(gate.currentContext().additionalDirs().containsKey(dir));
        assertEquals(List.of(dir.toString()), settings.additionalDirectories);
    }

    @Test
    void applyAddDirectory_recordsActiveSessionAlias(@TempDir Path home) throws IOException {
        Path activeCwd = Files.createDirectories(home.resolve("active"));
        Path added = Files.createDirectories(home.resolve("added"));
        SessionManager manager = new SessionManager(home, activeCwd.toString());
        String sessionId = manager.createSession();
        Files.createDirectories(manager.getSessionFile(sessionId).getParent());
        Files.writeString(manager.getSessionFile(sessionId), "{}\n");
        CommandContext ctx = CommandContext.builder(
                "m", List::of, () -> {}, _ -> {}, null, _ -> 0.0,
                activeCwd.toString(), false)
            .currentSessionId(() -> sessionId)
            .permissionCommands(ProviderTestCommandPorts.permissions(gate))
            .sessionCommands(ProviderTestCommandPorts.sessions(manager, new SessionStorage()))
            .build();

        CMD.applyAddDirectory(ctx, added.toString(), false);

        Path aliasFile = home.resolve("projects")
            .resolve(SessionManager.sanitizePath(added.toRealPath().toString()))
            .resolve(".session-aliases");
        assertEquals(List.of(manager.getSessionFile(sessionId).getParent().toRealPath().toString()),
            Files.readAllLines(aliasFile));
    }

    // ── test fixtures ────────────────────────────────────────────────────────

    private CommandContext ctxWith(PermissionGate gate, Consumer<String> addDirDialogLauncher) {
        Supplier<PermissionGate> gateSupplier = () -> gate;
        return CommandContext.builder(
                "m", List::of, () -> {}, _ -> {},
                null, _ -> 0.0, tempDir.toString(), false)
            .currentSessionId(() -> null)
            .permissionCommands(ProviderTestCommandPorts.permissions(gateSupplier.get()))
            .addDirDialogLauncher(addDirDialogLauncher)
            .addDirApply(CMD::applyAddDirectory)
            .build();
    }

    private CommandContext ctxWithoutGate() {
        return CommandContext.builder(
                "m", List::of, () -> {}, _ -> {},
                null, _ -> 0.0, tempDir.toString(), false)
            .currentSessionId(() -> null)
            .addDirApply(CMD::applyAddDirectory)
            .build();
    }
}
