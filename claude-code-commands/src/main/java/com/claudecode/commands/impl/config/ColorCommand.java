package com.claudecode.commands.impl.config;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import org.apache.commons.lang3.StringUtils;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /color — set the session's prompt-bar color.
 */
@SlashCommand(
    name = "color",
    description = "Set the prompt bar color for this session"
)
public class ColorCommand implements AnnotatedCommand {

    /** The 8 named agent colors. Also reused by {@code AgentColorPicker} for the {@code /agents} color step. */
    public static final List<String> AGENT_COLORS = List.of(
        "red", "blue", "green", "yellow", "purple", "orange", "pink", "cyan");
    private static final Set<String> RESET_ALIASES = Set.of(
        "default", "reset", "none", "gray", "grey");

    public ColorCommand() { }

    @Override public boolean isImmediate() { return true; }

    @Override public String argumentHint() { return "<color|default>"; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.application().tooling().collaboration().isTeammateSession()) {
            return CommandResult.of(
                "Cannot set color: This session is a swarm teammate. "
                    + "Teammate colors are assigned by the team leader.");
        }

        String raw = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return CommandResult.of(
                "Please provide a color. Available colors: "
              + String.join(", ", AGENT_COLORS) + ", default");
        }

        String sessionId = context.session().currentSessionId() != null
            ? context.session().currentSessionId().get() : null;

        if (RESET_ALIASES.contains(raw)) {
// Use "default" sentinel (not empty/null) so truthiness guards in sessionStorage
// persist the reset across session restarts.
            if (StringUtils.isNotBlank(sessionId)) {
                try { context.application().sessions().saveAgentColor(sessionId, "default"); }
                catch (RuntimeException e) { return CommandResult.of("Failed to save color: " + e.getMessage()); }
            }
            if (context.session().sessionColorSetter() != null) {
                context.session().sessionColorSetter().accept("default");
            }
            // TODO(analytics): logEvent('tengu_agent_color_set', {})
            return CommandResult.of("Session color reset to default");
        }

        if (!AGENT_COLORS.contains(raw)) {
            return CommandResult.of(
                "Invalid color \"" + raw + "\". Available colors: "
              + String.join(", ", AGENT_COLORS) + ", default");
        }

        if (StringUtils.isNotBlank(sessionId)) {
            try { context.application().sessions().saveAgentColor(sessionId, raw); }
            catch (RuntimeException e) { return CommandResult.of("Failed to save color: " + e.getMessage()); }
        }
        if (context.session().sessionColorSetter() != null) {
            context.session().sessionColorSetter().accept(raw);
        }
        // TODO(analytics): logEvent('tengu_agent_color_set', {})
        return CommandResult.of("Session color set to: " + raw);
    }

    /**
     * Appends {@code {type:"agent-color", agentColor, sessionId}} to the session JSONL.
     */
}
