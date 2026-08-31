package com.claudecode.cli;

import com.claudecode.commands.CommandRegistry;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.services.hooks.FileChangedHookWatcher;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.tools.skills.Skill;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the interactive startup dependency graph between the shared plugin/
 * Skill inventory and the first usable input frame.
 *
 * <p>The scene is intentionally not built here: the caller builds it in
 * parallel, then awaits {@link Result#inputSemanticReady} before publishing
 * the first frame. MCP discovery and LSP process startup never join this graph.
 */
final class CliInteractiveStartupCoordinator {

    private static final Logger log =
        LoggerFactory.getLogger(CliInteractiveStartupCoordinator.class);

    record Result(CompletionStage<Void> inputSemanticReady,
                  CompletionStage<List<Skill>> skills) {
    }

    private CliInteractiveStartupCoordinator() {}

    static Result start(
            QuerySession engine,
            HookEngine hooks,
            FileChangedHookWatcher watcher,
            CliHookEffectSink hookEffects,
            String setupTrigger,
            CliOutput errorOutput,
            CommandRegistry registry,
            Path cwd,
            CliPluginRuntimeView pluginRuntime,
            CliSessionLifecycleBootstrap.PromptInventory inventory) {
        CompletableFuture<Void> commandsReady = inventory.ready().thenCompose(_ ->
            CliStartupTasks.run("interactive-command-inventory", () -> {
                List<Skill> skills = inventory.skills().getNow(List.of());
                try {
                    hookEffects.syncSkillSnapshot(registry, skills, cwd);
                } catch (RuntimeException failure) {
                    log.warn("Skill command projection failed: {}", failure.getMessage());
                }
                try {
                    // Plugin markdown commands intentionally win name collisions
                    // with their model-facing Skill projections, matching the
                    // previous startup order.
                    if (pluginRuntime != null) {
                        pluginRuntime.attachCommandRegistry(
                            registry, hookEffects.skillCommandSync());
                    }
                } catch (RuntimeException failure) {
                    log.warn("Plugin command projection failed: {}", failure.getMessage());
                }
                try {
                    if (pluginRuntime != null) pluginRuntime.syncWorkflowCommands(cwd);
                    else CliHeadlessSessionRunner.syncWorkflowCommands(registry, cwd, null);
                } catch (RuntimeException failure) {
                    log.warn("Workflow command projection failed: {}", failure.getMessage());
                }
                inventory.timeline().mark("commands");
            }));
        engine.execution().addStartupBarrier(commandsReady);

        CompletableFuture<Void> hooksReady = inventory.pluginSnapshot().thenCompose(_ ->
            CliStartupTasks.run("interactive-hook-startup", () -> {
// Plugin hooks are already installed by loadAndInject at this
                // boundary. Setup and FileChanged matcher capture therefore see
                // the complete generation.
                hooks.setHookEffectSink(hookEffects);
                try {
                    CliSessionAssembler.runSetupHook(
                        setupTrigger, hooks, engine, errorOutput);
                } catch (RuntimeException failure) {
                    log.warn("Setup hook failed during interactive startup: {}",
                        failure.getMessage());
                }
                inventory.timeline().mark("setup");
                try {
                    watcher.initialize(cwd, hooks.configuredFileChangedMatchers());
                } catch (RuntimeException failure) {
                    log.warn("FileChanged watcher degraded during startup: {}",
                        failure.getMessage());
                }
                inventory.timeline().mark("watcher");
                engine.execution().setHookDispatcherDeferred(hooks);
            }));

        CompletionStage<Void> inputReady = CompletableFuture.allOf(commandsReady, hooksReady)
            .thenCompose(_ -> engine.execution().sealStartupReadiness())
            .thenRun(() -> inventory.timeline().mark("session-start"));
        return new Result(inputReady, inventory.skills());
    }
}
