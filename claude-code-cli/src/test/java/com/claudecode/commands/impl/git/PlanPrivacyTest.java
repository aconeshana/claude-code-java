package com.claudecode.commands.impl.git;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.Usage;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * /plan + /privacy-settings command tests.
 */
class PlanPrivacyTest {

    // ── /plan ───────────────────────────────────────────────────────────────

    @Test
    void plan_enablesPlanModeWhenNotActive(@TempDir Path dir) {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        CommandContext ctx = ctxWith(gate, "s1", dir);

        CommandResult r = new PlanCommand().execute(ctx, "");
        assertEquals(PermissionMode.PLAN, gate.currentMode());
        assertTrue(Strings.CS.startsWith(r.output(), "Enabled plan mode"));
    }

    @Test
    void plan_inModeWithoutFile_announces(@TempDir Path dir) {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        CommandResult r = new PlanCommand().execute(ctxWith(gate, "s1", dir), "");
        assertTrue(Strings.CS.contains(r.output(), "No plan written yet"), r.output());
    }

    @Test
    void plan_inModeWithFile_printsContent(@TempDir Path dir) throws Exception {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        Files.writeString(dir.resolve("s1.md"), "1. step one\n2. step two\n");
        CommandResult r = new PlanCommand().execute(ctxWith(gate, "s1", dir), "");
        assertTrue(Strings.CS.contains(r.output(), "step one"));
        assertTrue(Strings.CS.contains(r.output(), "Current Plan"));
    }

    @Test
    void plan_openWithoutPlanFile_reportsNoPlanYet(@TempDir Path dir) {
        // no-plan message even for /plan open.
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        CommandResult r = new PlanCommand().execute(ctxWith(gate, "s1", dir), "open");
        assertEquals("Already in plan mode. No plan written yet.", r.output());
    }

    @Test
    void plan_openWithPlanFile_routesToEditorChannel(@TempDir Path dir) throws Exception {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        Files.writeString(dir.resolve("s1.md"), "the plan");
        AtomicReference<Path> opened = new AtomicReference<>();
        CommandContext ctx = CommandContext.builder(
            "m", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, ".", false)
            .currentSessionId(() -> "s1")
            .permissionCommands(ProviderTestCommandPorts.permissions(gate))
            .toolingCommands(ProviderTestCommandPorts.plans(dir))
            .openEditor(opened::set)
            .build();
        CommandResult r = new PlanCommand().execute(ctx, "open");
        assertEquals(dir.resolve("s1.md"), opened.get());
        assertEquals("Opened plan in editor: " + dir.resolve("s1.md"), r.output());
    }

    @Test
    void plan_inModeWithDescriptionShowsMissingPlanWithoutQuerying(@TempDir Path dir) {
        // A missing plan takes precedence over the optional description once plan mode is active.
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        CommandResult r = new PlanCommand().execute(
            ctxWith(gate, "s1", dir), "如何接入东方财富");
        assertFalse(r.shouldQuery());
        assertEquals("Already in plan mode. No plan written yet.", r.output());
    }

    @Test
    void plan_withDescription_enablesModeAndQueriesIt(@TempDir Path dir) {
        // The description is forwarded while the local-command output remains fixed.
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        CommandResult r = new PlanCommand().execute(ctxWith(gate, "s1", dir), "build a game");
        assertEquals(PermissionMode.PLAN, gate.currentMode());
        assertTrue(r.shouldQuery());
        assertEquals("Enabled plan mode", r.output());
        assertEquals(
            "<local-command-stdout>Enabled plan mode</local-command-stdout>",
            r.promptInvocation().textContent());
        assertEquals(List.of(MessageContent.ofText("""
            <command-name>/plan</command-name>
                        <command-message>plan</command-message>
                        <command-args>build a game</command-args>""")),
            r.promptInvocation().precedingUserMessages());
    }

    @Test
    void plan_commandTurnSuppressesAttachmentsAndCommandPermissions(@TempDir Path dir) {
        // The reentry command turn is a bare [CMD]+[STDOUT] envelope matching the
        // official 2.1.197 baseline: no plan-mode reentry/active reminder, no
        // command-permission system-reminder, no agent/skill listing on this turn.
        // Reentry + plan-active are deferred to the next real user turn.
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        CommandResult r = new PlanCommand().execute(ctxWith(gate, "s1", dir), "build a game");
        assertTrue(r.shouldQuery());
        assertTrue(r.promptInvocation().suppressInitialAttachments());
        assertTrue(r.promptInvocation().suppressCommandPermissions());
    }

    private static CommandContext ctxWith(PermissionGate gate, String sessionId, Path plansDir) {
        return CommandContext.builder(
            "m", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, ".", false)
            .currentSessionId(() -> sessionId)
            .permissionCommands(ProviderTestCommandPorts.permissions(gate))
            .toolingCommands(ProviderTestCommandPorts.plans(plansDir))
            .build();
    }
}
