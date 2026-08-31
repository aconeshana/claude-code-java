package com.claudecode.commands.impl.agents;

import java.util.Locale;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;

/**
 * /advisor — configure the advisor model.
 */
@SlashCommand(
    name = "advisor",
    description = "Configure the advisor model"
)
public class AdvisorCommand implements AnnotatedCommand {

    public AdvisorCommand() {}

    @Override public String argumentHint() { return "[<model>|off]"; }

    @Override public boolean supportsNonInteractive() { return true; }

    @Override
    public boolean isAvailable(CommandContext context) {
        return false;
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String arg = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);

        // No arg → show current setting
        if (arg.isEmpty()) {
            String current = context.application().settings().preferences()
                .advisorModel().orElse(null);
            if (current == null) {
                return CommandResult.of(
                    """
                        No advisor model configured.
                        Usage: /advisor <model>   — set advisor model
                               /advisor off       — disable advisor model
                        Note: The advisor feature is gated by tengu_advisor. \
                        Setting this now will take effect when the gate opens.""");
            }
            return CommandResult.of("Current advisor model: " + current);
        }

        // 'off' / 'none' → clear
        if (Strings.CS.equals("off", arg) || Strings.CS.equals("none", arg)) {
            try {
                context.application().settings().preferences().saveAdvisorModel(null);
                return CommandResult.of("Advisor model cleared.");
            } catch (RuntimeException e) {
                return CommandResult.of("Failed to clear advisor model: " + e.getMessage());
            }
        }

        // Set a model
        try {
            context.application().settings().preferences().saveAdvisorModel(arg);
            return CommandResult.of("Advisor set to " + arg + ".\n"
                + "Note: The advisor feature is gated by the tengu_advisor feature flag. "
                + "The setting is saved and will take effect when enabled.");
        } catch (RuntimeException e) {
            return CommandResult.of("Failed to save advisor model: " + e.getMessage());
        }
    }

    @Override
    public boolean isHidden() {
        return true;
    }
}
