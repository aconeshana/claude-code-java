package com.claudecode.commands.impl.config;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.XmlConstants;
import com.claudecode.core.model.ModelNames;
import com.claudecode.commands.policy.ImmediateCommandStrategy;

import java.util.function.Supplier;

/**
 * {@code /model} — show or change the current model, or open the interactive picker.
 */
public class ModelCommand implements Command {

    private final Supplier<String> currentModelSupplier;

    /** No-arg registration path ({@code CommandFactory}) — static description fallback. */
    public ModelCommand() {
        this(null);
    }

    /**
     * @param currentModelSupplier live current-model accessor for the dynamic
     *        description {@code "(currently X)"}; {@code null} → static fallback.
     */
    public ModelCommand(Supplier<String> currentModelSupplier) {
        this.currentModelSupplier = currentModelSupplier;
    }


    @Override
    public CommandMetadata metadata() {
        if (currentModelSupplier != null) {
            String m = currentModelSupplier.get();
            if (StringUtils.isNotBlank(m)) {
                return new CommandMetadata("model",
                    "Set the AI model for Claude Code (currently " + ModelNames.displayName(m) + ")");
            }
        }
        return new CommandMetadata("model", "Set the AI model for Claude Code");
    }

    @Override
    public String argumentHint() { return "[model]"; }

    @Override
    public boolean isImmediate() {
        return ImmediateCommandStrategy.inferenceConfigCommandImmediate();
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String trimmed = args == null ? "" : args.trim();

        // /model list|show|current|... → show current model (+ effort).
        if (XmlConstants.COMMON_INFO_ARGS.contains(trimmed)) {
            return CommandResult.of(showCurrent(context));
        }
        // /model help|-h|--help → usage line.
        if (XmlConstants.COMMON_HELP_ARGS.contains(trimmed)) {
            return CommandResult.of(
                "Run /model to open the model selection menu, or /model [modelName] to set the model.");
        }
        // /model  (no arg) → open interactive picker if wired, else text fallback.
        if (trimmed.isEmpty()) {
            if (context.presentation().modelDialogLauncher() != null) {
                context.presentation().modelDialogLauncher().run();
                return CommandResult.skip();
            }
            return CommandResult.of(showCurrent(context));
        }
        // /model default → reset to the default main-loop model.
        if (Strings.CS.equals("default", trimmed)) {
            context.session().setModel().accept(null);
            return CommandResult.of("Set model to " + ModelNames.renderModelLabel(null));
        }

        String selectionError = validateSelection(context, trimmed);
        if (selectionError != null) return CommandResult.of(selectionError);

        // /model <alias|custom> → set without a live API probe here (the
        // validator already enforced the settings-backed organization allowlist,


        // same setModel.
        context.session().setModel().accept(trimmed);
        return CommandResult.of("Set model to " + ModelNames.displayName(trimmed));
    }

    /**
     * Bound to {@link CommandContext.ModelApplyFromDialog} — the {@code /model} picker's confirm path.
     */
    public CommandResult applyFromDialog(CommandContext context, String model, String effort) {
        // model == null → the picker's "Default (recommended)" option.
        context.session().setModel().accept(model);
        String label = model != null ? ModelNames.displayName(model) : ModelNames.renderModelLabel(null);
        String message = "Set model to " + label;
        if (StringUtils.isNotBlank(effort)) {
            message += " with " + effort + " effort";
        }
        return CommandResult.of(message);
    }


    private static String showCurrent(CommandContext context) {
        String label = ModelNames.displayName(context.session().model());
        String effort = context.session().effortValueSupplier() != null
            ? context.session().effortValueSupplier().get() : null;
        String effortInfo = (StringUtils.isNotBlank(effort))
            ? " (effort: " + effort + ")" : "";
        return "Current model: " + label + effortInfo;
    }

    private static String validateSelection(CommandContext context, String model) {
        if (context.session().modelAllowed() != null && !context.session().modelAllowed().test(model)) {
            return "Model '" + model
                + "' is not available. Your organization restricts model selection.";
        }
        if (context.session().modelValidator() == null) return null;
        return context.session().modelValidator().apply(model);
    }
}
