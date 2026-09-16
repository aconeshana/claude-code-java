package com.claudecode.cli;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Executable ownership rules for the CLI composition root. */
class CliArchitectureTest {

    private static final Path CLI = Path.of(
        "src/main/java/com/claudecode/cli/ClaudeCodeCli.java");

    @Test
    void cliDelegatesRuntimePortAdapters() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliInteractiveSessionRunner.java"));

        assertTrue(Strings.CS.contains(source, "CliRuntimeAdapters."));
        for (String method : List.of(
                "newMemoryCatalog", "newDoctorPort", "newStatusLinePort",
                "newCompactWarningProvider", "newStartupTrustPort",
                "newShutdownPort", "newTurnAwakeGuard",
                "configureUiSettingsBackend", "newHookConfigurationPort")) {
            assertFalse(Strings.CS.contains(source, "private static " + method + "("),
                () -> "runtime adapter belongs outside ClaudeCodeCli: " + method);
        }
    }

    @Test
    void cliDelegatesHeadlessOutputAndSdkProtocol() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliSessionAssembler.java"));
        String headlessRunner = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliHeadlessSessionRunner.java"));
        String root = Files.readString(CLI);

        assertTrue(Strings.CS.contains(source, "CliHeadlessOutput.")
            || Strings.CS.contains(headlessRunner, "CliHeadlessOutput."));
        for (String method : List.of(
                "processPrompt", "runSdkControlMode", "parseSdkUserInput",
                "buildSdkOutputMetadata", "buildSdkControlCatalog",
                "extractToolInputSummary", "printAssistantMessage")) {
            assertFalse(Strings.CS.contains(root, "private static " + method + "("),
                () -> "headless/SDK protocol belongs outside ClaudeCodeCli: " + method);
        }
    }

    @Test
    void picocliRootDelegatesSessionAssemblyAndModeRunners() throws Exception {
        String source = Files.readString(CLI);
        String assembler = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliSessionAssembler.java"));

        assertTrue(Strings.CS.contains(source, "snapshotLaunchRequest()"));
        assertTrue(Strings.CS.contains(source, "CliSessionAssembler."));
        assertTrue(Strings.CS.contains(assembler, "CliExecutionRouter."));
        for (String construction : List.of(
                "new DefaultQuerySession(", "new ToolRegistry(", "new HookEngine(",
                "new LanternaReplScreen", "new ReplWiring(")) {
            assertFalse(Strings.CS.contains(source, construction),
                () -> "session construction belongs outside ClaudeCodeCli: " + construction);
        }
    }

    @Test
    void picocliRootRetainsTheOuterMcpResourceBoundary() throws Exception {
        String source = Files.readString(CLI);

        assertTrue(Strings.CS.contains(source,
            "try (ManagedMcpRuntime mcpRuntime = new McpToolProvider())"));
        assertTrue(Strings.CS.contains(source,
            "CliSessionAssembler.assembleAndRun(request, mcpRuntime)"));
    }

    @Test
    void sessionAssemblerOwnsTheTaskToolProviderResource() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliSessionAssembler.java"));

        assertTrue(Strings.CS.contains(source,
            "try (CliResourceScope sessionResources = new CliResourceScope())"));
        assertTrue(Strings.CS.contains(source,
            "sessionResources, errorOutput)"));
        assertTrue(Strings.CS.contains(source,
            "pluginRuntimeRef,\n                        sessionResources)"));
        String toolchain = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliToolchainAssembler.java"));
        assertTrue(Strings.CS.contains(toolchain,
            "TaskToolProvider taskToolProvider = resources.own(new TaskToolProvider("));
    }

    @Test
    void splitOwnersKeepWorktreeHooksAndStructuredOutputOrdering() throws Exception {
        String toolchain = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliToolchainAssembler.java"));
        String engine = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliEngineAssembler.java"));

        assertTrue(Strings.CS.contains(toolchain, "Path.of(workspace.cwd())"));
        assertInOrder(toolchain,
            "applyCliToolFiltering(workspace, toolRegistry, permissionGate);",
            """
            JsonNode structuredOutputSchema = registerStructuredOutput(
                        workspace, toolRegistry, errorOutput);\
            """);
        assertTrue(Strings.CS.contains(engine,
            "hookEngine.context().bindMessages(() -> engine.conversation().getMessages());"));
        assertTrue(Strings.CS.contains(engine, "Path cwdPath = Path.of(workspace.cwd());"));
    }

    @Test
    void assemblerPreservesTheRequiredStartupOrder() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliSessionAssembler.java"));

        assertInOrder(source,
            "CliWorkspaceBootstrap.bootstrap",
            "CliToolchainAssembler.assemble",
            "CliEngineAssembler.assemble",
            "CliSessionLifecycleBootstrap.bootstrap",
            "CliSessionRestoreCoordinator.restore",
            "CliExecutionRouter.route");
    }

    @Test
    void interactiveMcpInitializationDoesNotUseNullableFuture() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliSessionAssembler.java"));

        assertFalse(Strings.CS.contains(source,
            "interactiveStartup(request)\n                    ? prepareInteractiveMcpConfig"));
        assertInOrder(source,
            "if (interactiveStartup(request)) {",
            "CompletableFuture<McpConfig> interactiveMcpConfig =",
            "initializeInteractiveMcp = () -> interactiveMcpConfig.thenAccept");
    }

    @Test
    void slashCommandDialogLaunchersBindToTheCommandUiBridge() throws Exception {
        String runner = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliInteractiveSessionRunner.java"));
        String assembler = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliInteractiveReplAssembler.java"));

        // Dialog launchers must not deref the late-bound screen; the bridge is null-safe before
        // the scene exists and is the only command → UI path for feature dialogs.
        for (String launcher : List.of(
                "s.open", "s.show", "s.handleCompactProgress", "s.resumeSession",
                "s.startWebGateway", "screen.open", "screen.show", "screen.setWelcomePokemon")) {
            assertFalse(Strings.CS.contains(runner, "if (s != null) " + launcher)
                    || Strings.CS.contains(assembler, "if (screen != null) " + launcher),
                () -> "feature dialogs are reached through ReplCommandUiBridge, not the screen: "
                    + launcher);
        }
        assertTrue(Strings.CS.contains(runner, "commandUi::openMcp"));
        assertTrue(Strings.CS.contains(runner, ".tasksDialogLauncher(commandUi::openTasks)"));
        assertTrue(Strings.CS.contains(runner, ".gatewayLauncher(_ -> commandUi.startWebGateway())"));
    }

    private static void assertInOrder(String source, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = source.indexOf(fragment);
            assertTrue(current > previous, () -> "startup phase out of order: " + fragment);
            previous = current;
        }
    }
}
