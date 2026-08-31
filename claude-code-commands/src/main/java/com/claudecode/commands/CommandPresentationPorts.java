package com.claudecode.commands;

import com.claudecode.core.pokemon.PokemonProfile;

import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;









public record CommandPresentationPorts(
    Consumer<String> btwDialogLauncher,
    Consumer<PokemonProfile> pokemonStatusPresenter,
    Consumer<CommandContext.PokemonHatchRequest> pokemonHatchLauncher,
    Runnable effortDialogLauncher,
    Consumer<String> exportDialogLauncher,
    Runnable hooksDialogLauncher,
    Runnable sandboxDialogLauncher,
    Runnable openMessageSelector,
    Runnable goalDialogLauncher,
    Runnable memoryDialogLauncher,
    Consumer<Path> openEditor,
    Runnable doctorDialogLauncher,
    Consumer<String> themeDialogLauncher,
    Runnable configDialogLauncher,
    BiFunction<CommandContext, String, CommandResult> themeApplyFromDialog,
    Runnable statusDialogLauncher,
    Runnable usageDialogLauncher,
    Runnable modelDialogLauncher,
    CommandContext.ModelApplyFromDialog modelApplyFromDialog,
    Consumer<String> addDirDialogLauncher,
    Function<String, CommandContext.AddDirValidationOutcome> addDirValidator,
    CommandContext.AddDirApply addDirApply,
    Runnable permissionsDialogLauncher,
    Runnable agentsDialogLauncher,
    Runnable contextVisualizerLauncher,
    CommandContext.CopyPickerLauncher copyPickerLauncher,
    CommandContext.CopyApplyFromDialog copyApplyFromDialog,
    Runnable diffDialogLauncher,
    Runnable helpDialogLauncher,
    Runnable skillsDialogLauncher,
    Consumer<String> pluginDialogLauncher,
    Runnable tasksDialogLauncher,
    Runnable workflowsDialogLauncher,
    Runnable statsDialogLauncher,
    Consumer<CommandContext.TagRemovalRequest> tagRemovalLauncher
) { }
