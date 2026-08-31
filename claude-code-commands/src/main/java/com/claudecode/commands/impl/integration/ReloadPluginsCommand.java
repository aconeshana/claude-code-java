package com.claudecode.commands.impl.integration;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.plugins.PluginRuntimePort;
import com.claudecode.core.text.StringUtils;

import java.util.List;

/**
 * {@code /reload-plugins} — re-read installed plugins from disk and swap the active commands /
 * agents / skills / hooks / MCP servers in the running session, then report the load statistics.
 */
@SlashCommand(
    name = "reload-plugins",
    description = "Activate pending plugin changes in the current session"
)
public class ReloadPluginsCommand implements AnnotatedCommand {

    @Override
    public CommandResult execute(CommandContext context, String args) {
        PluginRuntimePort runtime = context.application().plugins();
        if (runtime == null) {
            return CommandResult.of(
                "Plugin runtime is not initialized in this session — restart to load plugins.");
        }

        PluginRuntimePort.RefreshResult r;
        try {
            r = runtime.refresh();
        } catch (Exception e) {
            return CommandResult.of("Failed to reload plugins: " + e.getMessage());
        }
// Plugin MCP/LSP counts are named explicitly to distinguish them from
// user-configured servers (gh-31321).
        List<String> parts = List.of(
            n(r.enabledCount(), "plugin"),
            n(r.commandCount(), "skill"),
            n(r.agentCount(), "agent"),
            n(r.hookCount(), "hook"),
            n(r.mcpCount(), "plugin MCP server"),
            n(r.lspCount(), "plugin LSP server"));
        StringBuilder msg = new StringBuilder("Reloaded: " + String.join(" · ", parts));

        if (r.errorCount() > 0) {
            msg.append('\n')
               .append(n(r.errorCount(), "error"))
               .append(" during load. Run /doctor for details.");
        }
        return CommandResult.of(msg.toString());
    }


    private static String n(int count, String noun) {
        return count + " " + StringUtils.plural(count, noun);
    }
}
