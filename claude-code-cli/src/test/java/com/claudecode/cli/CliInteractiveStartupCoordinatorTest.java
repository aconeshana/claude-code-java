package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.commands.CommandRegistry;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.services.hooks.FileChangedHookWatcher;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.services.plugins.runtime.PluginRuntimeSnapshot;
import com.claudecode.session.SessionManager;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliInteractiveStartupCoordinatorTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override public String getModel() { return "test-model"; }
    };

    @Test
    void delayedInventoryDoesNotBlockCoordinatorStartAndGatesInput(
            @TempDir Path cwd) throws Exception {
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder().llmClient(NOOP_CLIENT).build());
        HookEngine hooks = new HookEngine(HooksSettings.EMPTY, cwd.toString());
        FileChangedHookWatcher watcher = new FileChangedHookWatcher(hooks);
        TranscriptRecorder transcript = new TranscriptRecorder(new SessionManager(cwd.toString()));
        CliOutput output = CliOutput.borrowed(new PrintWriter(new StringWriter(), true));
        CliHookEffectSink effects = new CliHookEffectSink(
            engine, transcript, new SkillLoader(), watcher, output, output, true, false);
        CommandRegistry registry = new CommandRegistry();
        CompletableFuture<PluginRuntimeSnapshot> plugins = new CompletableFuture<>();
        CompletableFuture<List<Skill>> skills = new CompletableFuture<>();
        CliSessionLifecycleBootstrap.PromptInventory inventory =
            new CliSessionLifecycleBootstrap.PromptInventory(
                plugins, skills, CompletableFuture.allOf(plugins, skills),
                new CliStartupTimeline());

        CliInteractiveStartupCoordinator.Result result =
            CliInteractiveStartupCoordinator.start(
                engine, hooks, watcher, effects, null, output, registry,
                cwd, null, inventory);

        assertFalse(result.inputSemanticReady().toCompletableFuture().isDone());
        plugins.complete(PluginRuntimeSnapshot.empty());
        assertFalse(result.inputSemanticReady().toCompletableFuture().isDone());
        skills.complete(List.of(new Skill(
            "delayed-skill", "Delayed", List.of(), "body", null,
            Skill.SkillSource.USER, null, null, null,
            Map.of("commandProjection", true))));

        result.inputSemanticReady().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(registry.find("delayed-skill").isPresent());
        effects.close();
    }
}
