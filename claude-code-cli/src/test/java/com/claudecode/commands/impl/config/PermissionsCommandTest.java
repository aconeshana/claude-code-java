package com.claudecode.commands.impl.config;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies {@link PermissionsCommand}'s dialog-launcher hand-off and the
 * plain-text {@code show} fallback now backed by real {@link PermissionGate}
 * data (replacing the previous hardcoded placeholder text).
 */
class PermissionsCommandTest {

    @Test
    void execute_withDialogLauncher_invokesItAndSkips() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        CommandContext ctx = ctxWithPermissionsDialogLauncher(() -> invoked.set(true), null);
        CommandResult r = new PermissionsCommand().execute(ctx, "");
        assertTrue(invoked.get());
        assertTrue(r.silent(), "dialog path must not also print the text listing");
    }

    @Test
    void show_withoutGate_reportsNotWired() {
        CommandContext ctx = ctxWithPermissionsDialogLauncher(null, null);
        CommandResult r = new PermissionsCommand().execute(ctx, "show");
        assertTrue(Strings.CS.contains(r.output(), "No permission gate wired"));
    }

    @Test
    void show_reflectsRealRulesFromGate() {
        PermissionGate gate = new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .rules(List.of(
                PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS),
                PermissionRule.withPattern("Bash", PermissionBehavior.DENY, RuleSource.LOCAL_SETTINGS, "rm *")))
            .build());

        CommandContext ctx = ctxWithPermissionsDialogLauncher(null, gate);
        CommandResult r = new PermissionsCommand().execute(ctx, "show");

        assertTrue(Strings.CS.contains(r.output(), "Bash"), r.output());
        assertTrue(Strings.CS.contains(r.output(), "Bash(rm *)"), r.output());
        assertTrue(Strings.CS.contains(r.output(), "Allow (1)"), r.output());
        assertTrue(Strings.CS.contains(r.output(), "Deny (1)"), r.output());
        assertTrue(Strings.CS.contains(r.output(), "Ask (0)"), r.output());
    }

    @Test
    void show_withNoRules_showsNoneForEachSection() {
        PermissionGate gate = new PermissionGate(ToolPermissionContext.of(Path.of(".")));
        CommandContext ctx = ctxWithPermissionsDialogLauncher(null, gate);
        CommandResult r = new PermissionsCommand().execute(ctx, "show");
        assertTrue(Strings.CS.contains(r.output(), "Allow (0)"));
        assertFalse(Strings.CS.contains(r.output(), "Workspace directories"), "empty additionalDirs must not print a section");
    }

    @Test
    void execute_defaultsToShow() {
        PermissionGate gate = new PermissionGate(ToolPermissionContext.of(Path.of(".")));
        CommandContext ctx = ctxWithPermissionsDialogLauncher(null, gate);
        CommandResult r = new PermissionsCommand().execute(ctx, null);
        assertTrue(Strings.CS.contains(r.output(), "Tool Permissions"));
    }

    @Test
    void aliases_includesAllowedTools() {
        assertTrue(new PermissionsCommand().aliases().contains("allowed-tools"));
    }









    @Test
    void legacyFakeSubcommands_fallThroughToUsage() {
        CommandContext ctx = ctxWithPermissionsDialogLauncher(null, null);
        for (String action : List.of("set bypass", "allow-all", "deny-all", "reset")) {
            CommandResult r = new PermissionsCommand().execute(ctx, action);
            assertTrue(Strings.CS.contains(r.output(), "Usage:"), action + " -> " + r.output());
            assertFalse(Strings.CS.contains(r.output(), "Permission mode set to"), action);
        }
    }

    private static CommandContext ctxWithPermissionsDialogLauncher(Runnable permissionsDialogLauncher, PermissionGate gate) {
        return CommandContext.builder(
            "m", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, ".", false)
            .currentSessionId(() -> null)
            .permissionCommands(ProviderTestCommandPorts.permissions(gate))
            .permissionsDialogLauncher(permissionsDialogLauncher)
            .build();
    }
}
