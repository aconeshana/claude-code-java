package com.claudecode.commands.impl.integration;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.runtime.capabilities.SessionCapabilityPort;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.Strings;

/**
 * {@code /tools} — inspect and progressively enable/disable built-in tools in the current
 * session (preserving the Turn-1 prefix cache) or persist project defaults ({@code --default}).
 */
@Explanation("Provides TUI slash-command parity with WebUI DSH Context capability switches for built-in tools")
@SlashCommand(
    name = "tools",
    description = "Manage built-in tool switches (session or --default)"
)
public class ToolsCommand implements AnnotatedCommand {

    @Override
    public boolean isImmediate() {
        return true;
    }

    @Override
    public String argumentHint() {
        return "[enable|disable <tool-name|all> [--default]]";
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        SessionCapabilityPort capabilities = context != null && context.application() != null
            ? context.application().capabilities() : null;
        if (capabilities == null) {
            return CommandResult.of("Tool capability controller is not available.");
        }
        String sessionId = context.session().currentSessionId() != null
            ? context.session().currentSessionId().get() : "";
        String trimmed = args != null ? args.trim() : "";
        if (trimmed.isEmpty() || Strings.CI.equals("list", trimmed)) {
            return renderListing(capabilities.snapshot(sessionId, context.session().model()));
        }

        String[] parts = trimmed.split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        if (!Strings.CS.equals("enable", action) && !Strings.CS.equals("disable", action)) {
            return CommandResult.of("Usage: /tools [enable|disable <tool-name|all> [--default]]");
        }
        boolean enabling = Strings.CS.equals("enable", action);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        SessionCapabilityPort.Scope scope = SessionCapabilityPort.Scope.SESSION;
        String target = "";
        for (String tok : (rest.isEmpty() ? new String[0] : rest.split("\\s+"))) {
            if (Strings.CS.equals("--default", tok) || Strings.CS.equals("--persist", tok)) {
                scope = SessionCapabilityPort.Scope.DEFAULT;
            } else if (Strings.CS.equals("--session", tok)) {
                scope = SessionCapabilityPort.Scope.SESSION;
            } else if (target.isEmpty()) {
                target = tok;
            }
        }
        if (target.isEmpty()) {
            return CommandResult.of("Usage: /tools " + action + " <tool-name|all> [--default]");
        }

        var snap = capabilities.snapshot(sessionId, context.session().model());
        if (Strings.CS.equals("all", target)) {
            int count = 0;
            for (var t : snap.tools()) {
                if (t.kernel()) continue;
                boolean current = scope == SessionCapabilityPort.Scope.DEFAULT
                    ? t.defaultEnabled() : t.sessionEnabled();
                if (current != enabling) {
                    capabilities.toggle(new SessionCapabilityPort.ToggleRequest(
                        sessionId, SessionCapabilityPort.Kind.TOOL, t.name(), null, enabling, scope));
                    count++;
                }
            }
            return CommandResult.of((enabling ? "Enabled " : "Disabled ")
                + count + " built-in tool(s) (" + scope.wire() + ").");
        }

        final String toolName = target;
        var match = snap.tools().stream().filter(t -> t.name().equals(toolName)).findFirst().orElse(null);
        if (match == null) {
            return CommandResult.of("Built-in tool \"" + toolName + "\" not found.");
        }
        if (match.kernel() && !enabling) {
            return CommandResult.of("Kernel tool \"" + toolName
                + "\" is required for progressive discovery and cannot be disabled.");
        }
        var outcome = capabilities.toggle(new SessionCapabilityPort.ToggleRequest(
            sessionId, SessionCapabilityPort.Kind.TOOL, toolName, null, enabling, scope));
        return CommandResult.of(outcome.message());
    }

    private static CommandResult renderListing(SessionCapabilityPort.CapabilitiesSnapshot snap) {
        List<SessionCapabilityPort.BuiltInToolItem> tools = snap.tools();
        long enabledCount = tools.stream().filter(SessionCapabilityPort.BuiltInToolItem::sessionEnabled).count();
        StringBuilder sb = new StringBuilder();
        sb.append("Built-in Tools (protocol: ").append(snap.protocol())
            .append(" · active: ").append(enabledCount).append("/").append(tools.size())
            .append(" · saved: ~").append(snap.savedTokens()).append(" tokens)\n")
            .append("================================================================\n");
        for (var t : tools) {
            String badge = t.kernel() ? "[KERNEL]  "
                : t.sessionEnabled() ? "[ENABLED] " : "[DISABLED]";
            String defNote = t.defaultEnabled() != t.sessionEnabled()
                ? (t.defaultEnabled() ? " (default: on)" : " (default: off)")
                : "";
            sb.append(String.format("  %s %-22s %-22s ~%dt%s%n",
                badge, t.name(), "(" + t.deliveryMode() + ")", t.tokens(), defNote));
        }
        sb.append("\nTip: /tools enable|disable <ToolName|all> [--default]");
        return CommandResult.of(sb.toString());
    }
}
