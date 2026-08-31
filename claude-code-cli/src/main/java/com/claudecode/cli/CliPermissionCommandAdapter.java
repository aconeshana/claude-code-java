package com.claudecode.cli;

import com.claudecode.commands.permissions.PermissionCommandPort;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.permissions.WorkingDirectoryPaths;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI leaf adapter from command permission use cases to the live permission gate.
 */
final class CliPermissionCommandAdapter implements PermissionCommandPort {
    private final PermissionGate gate;

    CliPermissionCommandAdapter(PermissionGate gate) {
        this.gate = gate;
    }

    @Override
    public Snapshot snapshot() {
        if (gate == null) return PermissionCommandPort.none().snapshot();
        ToolPermissionContext context = gate.currentContext();
        return new Snapshot(true, context.mode().name(),
            rules(context.rules(), PermissionBehavior.ALLOW),
            rules(context.rules(), PermissionBehavior.ASK),
            rules(context.rules(), PermissionBehavior.DENY),
            List.copyOf(WorkingDirectoryPaths.allWorkingDirectories(context)),
            List.copyOf(context.additionalDirs().keySet()));
    }

    @Override
    public boolean isPlanMode() {
        return gate != null && gate.currentMode() == PermissionMode.PLAN;
    }

    @Override
    public void enterPlanMode() {
        if (gate != null) gate.setMode(PermissionMode.PLAN);
    }

    @Override
    public void addDirectory(Path directory) {
        if (gate == null) throw new IllegalStateException("Permission gate is not wired");
        gate.addDirectories(List.of(directory));
    }

    private static List<String> rules(List<PermissionRule> rules, PermissionBehavior behavior) {
        return rules.stream().filter(rule -> rule.behavior() == behavior)
            .map(PermissionEngine::permissionRuleToString).toList();
    }
}
