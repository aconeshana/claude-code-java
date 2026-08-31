package com.claudecode.cli;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.ConfigLiveSetters;
import com.claudecode.commands.impl.config.AddDirCommand;
import com.claudecode.commands.impl.config.ThemeCommand;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.services.compact.CompactService;
import com.claudecode.ui.lanterna.repl.LanternaReplScreen;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.components.SpinnerFrames;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Assembles the screen-bound command capabilities for one interactive REPL session.
 */
final class CliInteractiveReplAssembler {
    private CliInteractiveReplAssembler() {}

    record Bindings(
        AtomicReference<LanternaReplScreen> screenRef,
        ReplCommandUiBridge commandUi,
        Consumer<String> btwLauncher,
        Consumer<String> colorSetter,
        Consumer<PokemonProfile> pokemonSetter,
        Consumer<String> effortSetter,
        Supplier<String> effortGetter,
        Runnable effortLauncher,
        Consumer<String> exportLauncher,
        Consumer<String> themeLauncher,
        BiFunction<CommandContext, String, CommandResult> themeApplyFromDialog,
        Runnable configLauncher,
        Runnable statusLauncher,
        Runnable usageLauncher,
        Runnable permissionsLauncher,
        Runnable agentsLauncher,
        Consumer<String> addDirLauncher,
        CommandContext.AddDirApply addDirApply,
        ConfigLiveSetters configLiveSetters,
        Runnable rewindLauncher
    ) {}

    static Bindings create(
            QuerySession engine,
            CompactService compactService,
            Function<String, String> sideQuestionRunner) {
        AtomicReference<LanternaReplScreen> screenRef = new AtomicReference<>();
        ReplCommandUiBridge commandUi = new ReplCommandUiBridge();
        Consumer<String> btwLauncher = question -> {
            LanternaReplScreen screen = screenRef.get();
            if (screen != null) screen.openBtwDialog(question, sideQuestionRunner);
        };
        Consumer<String> colorSetter = name -> {
            LanternaReplScreen screen = screenRef.get();
            if (screen != null) screen.setSessionColor(name);
        };
        Consumer<PokemonProfile> pokemonSetter = pokemon -> {
            LanternaReplScreen screen = screenRef.get();
            if (screen != null) screen.setWelcomePokemon(pokemon);
        };
        Consumer<String> effortSetter = level -> {
            engine.configuration().getConfig().setEffortValue(level);
            commandUi.showEffortNotification(level);
        };
        Supplier<String> effortGetter = () -> engine.configuration().getConfig().effortValue();
        Runnable effortLauncher = commandUi::openEffort;
        Consumer<String> exportLauncher = content -> {
            LanternaReplScreen screen = screenRef.get();
            if (screen != null) screen.openExportDialog(content);
        };
        Consumer<String> themeLauncher = commandUi::openTheme;
        BiFunction<CommandContext, String, CommandResult> themeApplyFromDialog =
            new ThemeCommand()::applyFromDialog;
        Runnable configLauncher = commandUi::openConfig;
        Runnable statusLauncher = commandUi::openStatus;
        Runnable usageLauncher = commandUi::openUsage;
        Runnable permissionsLauncher = commandUi::openPermissions;
        Runnable agentsLauncher = commandUi::openAgents;
        Consumer<String> addDirLauncher = commandUi::openAddDirectory;
        CommandContext.AddDirApply addDirApply = new AddDirCommand()::applyAddDirectory;
        ConfigLiveSetters configLiveSetters = new ConfigLiveSetters(
            verbose -> {
                LanternaReplScreen screen = screenRef.get();
                if (screen != null) screen.setVerbose(verbose);
            },
            theme -> {
                LanternaReplScreen screen = screenRef.get();
                if (screen != null) screen.setThemeScheme(theme);
            },
            compactService::setAutoCompactEnabled,
            enabled -> {
                LanternaReplScreen screen = screenRef.get();
                if (screen != null) screen.setThinkingEnabled(enabled);
            },
            SpinnerFrames::setReducedMotion,
            _ -> {
                LanternaReplScreen screen = screenRef.get();
                if (screen != null) screen.refreshClaudeHud();
            });
        Runnable rewindLauncher = () -> {
            LanternaReplScreen screen = screenRef.get();
            if (screen != null) screen.openMessageSelector();
        };
        return new Bindings(screenRef, commandUi, btwLauncher, colorSetter, pokemonSetter, effortSetter,
            effortGetter, effortLauncher, exportLauncher, themeLauncher, themeApplyFromDialog,
            configLauncher, statusLauncher, usageLauncher, permissionsLauncher, agentsLauncher,
            addDirLauncher, addDirApply, configLiveSetters, rewindLauncher);
    }
}
