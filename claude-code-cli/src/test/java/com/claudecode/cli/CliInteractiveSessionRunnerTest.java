package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.claudecode.api.ApiConfig;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HooksSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

/** Ownership sentinel for the interactive terminal lifecycle. */
class CliInteractiveSessionRunnerTest {

    @Test
    void runnerOwnsScreenBoundLifecycleRatherThanThePicocliRoot() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliInteractiveSessionRunner.java"));

        assertTrue(Strings.CS.contains(source, "new LanternaReplScreen"));
        assertTrue(Strings.CS.contains(source, "progressSink.setScreen"));
        assertTrue(Strings.CS.contains(source, "attachRecommendationTrigger"));
        assertTrue(Strings.CS.contains(source, "finalizePendingAsyncHooks"));
        assertTrue(Strings.CS.contains(source, "finally {"),
            "REPL failures must use the same teardown path as normal returns");
        assertTrue(Strings.CS.contains(source, "finally {\n                finalizeInteractiveSession("),
            "the runner's try/finally must invoke the shared cleanup helper");
        String coordinator = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliInteractiveStartupCoordinator.java"));
        assertTrue(coordinator.indexOf("runSetupHook")
                < coordinator.indexOf("setHookDispatcherDeferred"),
            "Setup must complete before SessionStart is dispatched");
    }

    @Test
    void teardownForcesAndFinalizesAsyncHooks() {
        RecordingHookEngine hookEngine = new RecordingHookEngine();

        CliInteractiveSessionRunner.finalizeInteractiveSession(null, hookEngine);

        assertTrue(hookEngine.forceSync);
        assertTrue(hookEngine.finalized);
    }

    @Test
    void headlessSetupAlsoPrecedesSessionStart() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliHeadlessSessionRunner.java"));
        assertTrue(source.indexOf("runSetupHook") < source.indexOf("setHookDispatcher"));
    }

    @Test
    void optionalSettingsIoDoesNotRunInlineBeforeTheRepl() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliInteractiveSessionRunner.java"));

        assertTrue(Strings.CS.contains(source,
            "CliStartupTasks.supply(\"interactive-optional-settings\""));
        assertTrue(Strings.CS.contains(source, "sessionHostReady()"));
        assertFalse(Strings.CS.contains(source,
            "lanternaRepl.configureIdlePromptNotification(\n                    RuntimeSettings"));
    }

    @Test
    void builtInModelFamiliesRequireUsableAnthropicCredentialsForTheRoute() {
        assertFalse(CliInteractiveSessionRunner.showBuiltInModelFamilies(
            ApiConfig.ApiProvider.ANTHROPIC, null, new ConfigLoader.Credentials(null, null)));
        assertTrue(CliInteractiveSessionRunner.showBuiltInModelFamilies(
            ApiConfig.ApiProvider.ANTHROPIC, null, new ConfigLoader.Credentials("sk-ant-test", null)));
        assertTrue(CliInteractiveSessionRunner.showBuiltInModelFamilies(
            ApiConfig.ApiProvider.ANTHROPIC, "https://api.anthropic.com/v1",
            new ConfigLoader.Credentials("sk-ant-test", null)));
        assertFalse(CliInteractiveSessionRunner.showBuiltInModelFamilies(
            ApiConfig.ApiProvider.ANTHROPIC, "https://api.anthropic.com/v1",
            new ConfigLoader.Credentials(null, "gateway-token")));
        assertTrue(CliInteractiveSessionRunner.showBuiltInModelFamilies(
            ApiConfig.ApiProvider.ANTHROPIC, "https://gateway.example/v1",
            new ConfigLoader.Credentials("sk-ant-test", null)));
        assertTrue(CliInteractiveSessionRunner.showBuiltInModelFamilies(
            ApiConfig.ApiProvider.ANTHROPIC, "https://gateway.example/v1",
            new ConfigLoader.Credentials(null, "gateway-token")));
        assertFalse(CliInteractiveSessionRunner.showBuiltInModelFamilies(
            ApiConfig.ApiProvider.ANTHROPIC, "https://gateway.example/v1",
            new ConfigLoader.Credentials(null, null)));
        assertFalse(CliInteractiveSessionRunner.showBuiltInModelFamilies(
            ApiConfig.ApiProvider.BEDROCK, null,
            new ConfigLoader.Credentials("sk-ant-test", null)));
        assertFalse(CliInteractiveSessionRunner.showBuiltInModelFamilies(
            ApiConfig.ApiProvider.VERTEX, null,
            new ConfigLoader.Credentials("sk-ant-test", null)));
    }

    private static final class RecordingHookEngine extends HookEngine {
        private boolean forceSync;
        private boolean finalized;

        private RecordingHookEngine() {
            super(HooksSettings.EMPTY, "/tmp");
        }

        @Override
        public void setForceSyncExecution(boolean value) {
            forceSync = value;
        }

        @Override
        public void finalizePendingAsyncHooks() {
            finalized = true;
        }
    }
}
