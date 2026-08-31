package com.claudecode.commands.impl.config;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.permissions.PermissionCommandPort;

import java.util.List;

/**
 * {@code /permissions} — opens the interactive rule-management panel ({@code PermissionsPanel} in
 * claude-code-ui: Allow / Ask / Deny / Workspace tabs) when a dialog launcher is wired; otherwise
 * falls back to a plain-text listing read from the live {@link PermissionGate} (bridge / headless /
 * tests).
 */
@SlashCommand(
    name = "permissions",
    description = "Manage tool permissions",
    aliases = "allowed-tools"
)
public class PermissionsCommand implements AnnotatedCommand {

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().permissionsDialogLauncher() != null) {
            context.presentation().permissionsDialogLauncher().run();
            return CommandResult.skip();
        }

        String action = StringUtils.isNotBlank(args) ? args.trim().toLowerCase(Locale.ROOT) : "show";
        if (Strings.CS.equals(action, "show") || Strings.CS.equals(action, "list")) {
            return showPermissions(context);
        }
        return CommandResult.of("""
            Usage:
              /permissions show      - Show current permissions

            Use /config set defaultPermissionMode <mode> to change the permission mode.""");
    }

    /**
     * Plain-text rendering of the live {@link PermissionGate} state — the
     * bridge/headless counterpart to {@code PermissionsPanel}'s Allow/Ask/Deny/
     * Workspace tabs. Replaces the previous hardcoded placeholder text (which
     * never reflected real rules) now that {@link PermissionGate#currentContext()}
     * is the actual source of truth wired throughout this command context.
     */
    private CommandResult showPermissions(CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool Permissions\n");
        sb.append("================\n\n");

        PermissionCommandPort.Snapshot snapshot = context.application().permissions().snapshot();
        if (!snapshot.wired()) {
            sb.append("No permission gate wired in this context.");
            return CommandResult.of(sb.toString());
        }

        sb.append("Mode: ").append(snapshot.mode()).append("\n\n");
        appendRuleSection(sb, "Allow", snapshot.allowRules());
        appendRuleSection(sb, "Ask", snapshot.askRules());
        appendRuleSection(sb, "Deny", snapshot.denyRules());

        if (!snapshot.additionalDirectories().isEmpty()) {
            sb.append("\nWorkspace directories:\n");
            for (var dir : snapshot.additionalDirectories()) {
                sb.append("  ").append(dir).append("\n");
            }
        }
        return CommandResult.of(sb.toString().stripTrailing());
    }

    private void appendRuleSection(StringBuilder sb, String label, List<String> rules) {
        sb.append(label).append(" (").append(rules.size()).append("):\n");
        if (rules.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (String rule : rules) sb.append("  ").append(rule).append("\n");
        }
    }
}
