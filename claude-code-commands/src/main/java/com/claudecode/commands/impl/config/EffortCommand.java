package com.claudecode.commands.impl.config;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.commands.policy.ImmediateCommandStrategy;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.Locale;
import java.util.Set;

/**
 * /effort — set or display the reasoning-effort level used on the next API request.
 */
@SlashCommand(
    name = "effort",
    description = "Set effort level for model usage"
)
public class EffortCommand implements AnnotatedCommand {

    private static final String ENV_OVERRIDE = "CLAUDE_CODE_EFFORT_LEVEL";
    private static final Set<String> HELP_ARGS = Set.of("help", "-h", "--help");

    public EffortCommand() {}

    @Override public String argumentHint() { return "[none|minimal|low|medium|high|xhigh|max|auto]"; }

    @Override
    public boolean isImmediate() {
        return ImmediateCommandStrategy.inferenceConfigCommandImmediate();
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {

        // (`Invalid argument: ${args}` not `${normalized}`). Keep the raw form.
        String rawArgs = args == null ? "" : args.trim();
        String normalized = rawArgs.toLowerCase(Locale.ROOT);

        if (HELP_ARGS.contains(normalized)) {
            return CommandResult.of(helpText());
        }
        if (normalized.isEmpty()) {
// No arg → prefer the interactive slider dialog.
            if (context.presentation().effortDialogLauncher() != null) {
                context.presentation().effortDialogLauncher().run();
                return CommandResult.skip();
            }
            return CommandResult.of(showCurrent(context));
        }
        if (Strings.CS.equals("current", normalized) || Strings.CS.equals("status", normalized)) {

            // these subcommands exist precisely to bypass the picker.
            return CommandResult.of(showCurrent(context));
        }

        // 1) auto/unset → unsetEffortLevel
        // 2) !isEffortLevel → invalid
        // 3) setEffortValue(normalized)
        if (Strings.CS.equals("auto", normalized) || Strings.CS.equals("unset", normalized)) {
            return CommandResult.of(clearLevel(context));
        }
        if (!EffortHelpers.isEffortLevel(normalized)) {

            return CommandResult.of("Invalid argument: " + rawArgs
                + ". Valid options are: none, minimal, low, medium, high, xhigh, max, auto");
        }
        return CommandResult.of(setLevel(context, normalized));
    }


    private String showCurrent(CommandContext context) {
        String envOverride = getEffortEnvOverride();
// Prefer the live AppState getter.
        String appStateEffort = context.session().effortValueSupplier() != null
            ? context.session().effortValueSupplier().get()
            : context.application().settings().preferences().effortLevel();
        String effective;
        if (Strings.CS.equals("__UNSET__", envOverride)) {
// env=auto/unset → suppress everything.
            effective = null;
        } else if (envOverride != null) {
            effective = envOverride;
        } else {
            effective = appStateEffort;
        }
        if (effective == null) {

            // getDisplayedEffortLevel returns 'high' fallback when nothing resolves.
            String modelDefault = EffortHelpers.getDisplayedEffortLevel(context.session().model(), null);
            return "Effort level: auto (currently " + modelDefault + ")";
        }

        return "Current effort level: " + effective
            + " (" + getEffortValueDescription(effective) + ")";
    }


    private String setLevel(CommandContext context, String level) {
        String persistable = toPersistableEffort(level);
        if (persistable != null) {
            try {
                context.application().settings().preferences()
                    .saveEffortLevel(persistable);
            } catch (RuntimeException e) {
                return "Failed to set effort level: " + e.getMessage();
            }
        }
        if (context.session().effortValueSetter() != null) {
            context.session().effortValueSetter().accept(level);
        }

        // system not wired (whole-app gap).

        String envOverride = getEffortEnvOverride();
        String envRaw = SubprocessEnvironment.get(ENV_OVERRIDE);
        if (envOverride != null && !Strings.CS.equals("__UNSET__", envOverride) && !envOverride.equals(level)) {
            if (persistable == null) {
                return "Not applied: CLAUDE_CODE_EFFORT_LEVEL=" + envRaw
                    + " overrides effort this session, and " + level
                    + " is session-only (nothing saved)";
            }
            return "CLAUDE_CODE_EFFORT_LEVEL=" + envRaw
                + " overrides this session — clear it and " + level + " takes over";
        }



        String suffix = persistable == null
            ? " (this session only)"
            : " (saved as your default for new sessions)";
        return effortLevelToSymbol(level) + " Set effort level to " + level + suffix + ": " + getEffortValueDescription(level);
    }


    private String clearLevel(CommandContext context) {
        try {
            context.application().settings().preferences().saveEffortLevel(null);
        } catch (RuntimeException e) {
            return "Failed to clear effort level: " + e.getMessage();
        }
        if (context.session().effortValueSetter() != null) {
            context.session().effortValueSetter().accept(null);
        }
        String envOverride = getEffortEnvOverride();
        if (envOverride != null && !Strings.CS.equals("__UNSET__", envOverride)) {
            String envRaw = SubprocessEnvironment.get(ENV_OVERRIDE);
            return "Cleared effort from settings, but CLAUDE_CODE_EFFORT_LEVEL=" + envRaw
                + " still controls this session";
        }
        return "Effort level set to auto";
    }



    private static String getEffortEnvOverride() {
        return EffortHelpers.getEffortEnvOverride();
    }


    private static String toPersistableEffort(String value) {
        return EffortHelpers.toPersistableEffort(value);
    }


    static String getEffortValueDescription(String level) {
        return EffortHelpers.getEffortValueDescription(level);
    }


    static String effortLevelToSymbol(String level) {
        return EffortHelpers.effortLevelToSymbol(level);
    }

    private static String helpText() {
        return """
            Usage: /effort [none|minimal|low|medium|high|xhigh|max|auto]

            Effort levels:
            - none: Disable reasoning effort on supported GPT models
            - minimal: Minimum reasoning on supported GPT models
            - low: Quick, straightforward implementation
            - medium: Balanced approach with standard testing
            - high: Comprehensive implementation with extensive testing
            - xhigh: Extended high effort with deeper reasoning for harder tasks
            - max: Maximum capability on models that support it
            - auto: Use the default effort level for your model

            Run /effort with no argument to open the interactive slider.""";
    }
}
