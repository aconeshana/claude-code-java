package com.claudecode.commands.impl.config;

import java.util.Locale;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandPresentationPorts;
import com.claudecode.commands.CommandResult;

import java.util.List;

/**
 * Switches the active color theme.
 */
@SlashCommand(
    name = "theme",
    description = "Change the theme"
)
public class ThemeCommand implements AnnotatedCommand {

    /**
     * matches {@code THEME_SETTINGS} in 1:1.
     */
    private static final List<String> THEMES = List.of(
        "auto",
        "dark",
        "light",
        "light-daltonized",
        "dark-daltonized",
        "light-ansi",
        "dark-ansi"
    );

    public ThemeCommand() {}

    @Override
    public CommandResult execute(CommandContext context, String args) {





        if (context.presentation().themeDialogLauncher() != null) {
            String current = context.application().settings().preferences().theme();
            context.presentation().themeDialogLauncher().accept(current);
            return CommandResult.skip();
        }

        String action = args != null ? args.trim() : "";
        String lower = action.toLowerCase(Locale.ROOT);

        if (action.isEmpty()) {
            return listThemes();
        }
        if (Strings.CS.equals(lower, "list") || Strings.CS.equals(lower, "ls")) {
            return listThemes();
        }
        if (THEMES.contains(lower)) {
            return setTheme(context, lower);
        }
        return invalidTheme(lower);
    }

    /**
     * Applies and persists {@code name}, bypassing the args-ignored/picker-reopens
     * branch in {@link #execute}. Bound to {@link CommandPresentationPorts#themeApplyFromDialog}
     * and called once by {@code LanternaReplScreen.handleThemeDialogResult} after the
     * interactive picker resolves with a chosen theme — see that field's Javadoc for
     * why a re-dispatched {@code "/theme <name>"} can't be used for this instead
     * (it would just hit the launcher branch above and reopen the picker).
     *
     * @param name a value already validated by the picker (one of {@link #THEMES});
     *             not re-validated here
     */
    public CommandResult applyFromDialog(CommandContext context, String name) {
        return setTheme(context, name);
    }

    /** Persists {@code theme} + live-applies via {@code configLiveSetters}. */
    private CommandResult setTheme(CommandContext context, String name) {
        context.application().settings().preferences().saveTheme(name);
        if (context.session().configLiveSetters() != null && context.session().configLiveSetters().themeSetter() != null) {
            context.session().configLiveSetters().themeSetter().accept(name);
        }
        return CommandResult.of("Theme set to " + name);
    }

    private CommandResult invalidTheme(String value) {
        return CommandResult.of(
            "Invalid theme \"" + value + "\". Available themes: " + String.join(", ", THEMES));
    }

    private CommandResult listThemes() {
        StringBuilder sb = new StringBuilder("Available Themes\n================\n\n");
        for (String theme : THEMES) {
            sb.append("  ").append(theme).append("\n");
        }
        sb.append("\nUse /theme <name> to switch.\n");
        return CommandResult.of(sb.toString());
    }
}
