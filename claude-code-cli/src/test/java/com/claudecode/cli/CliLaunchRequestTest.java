package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** Tests the immutable boundary between Picocli parsing and CLI startup. */
class CliLaunchRequestTest {

    @Test
    void snapshotFreezesParsedOptionsOverridesAndDerivedModes() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        ClaudeCodeCliTest.MockStreamingClient client =
            new ClaudeCodeCliTest.MockStreamingClient("unused");
        PrintWriter writer = new PrintWriter(new StringWriter(), true);
        cli.setStreamingClientOverride(client);
        cli.setOutputWriter(writer);

        new CommandLine(cli).parseArgs(
            "--print", "--verbose", "--output-format", "stream-json",
            "--input-format", "stream-json", "--include-partial-messages",
            "--replay-user-messages", "--mcp-config", "first.json",
            "--mcp-config", "second.json", "--plugin-dir", "plugin-a",
            "--plugin-dir", "plugin-b", "--allowed-tools", "Read,Bash",
            "--disallowed-tools", "Write", "--tools", "default,Read",
            "--cwd", "/tmp", "hello");

        CliLaunchRequest request = cli.snapshotLaunchRequest();

        assertEquals("hello", request.session().initialPrompt());
        assertEquals("stream-json", request.output().outputFormat());
        assertEquals(List.of("first.json", "second.json"), request.workspace().mcpConfig());
        assertEquals(List.of("plugin-a", "plugin-b"), request.workspace().pluginDirectories());
        assertEquals(List.of("Read,Bash"), request.permissions().allowedTools());
        assertEquals(List.of("Write"), request.permissions().disallowedTools());
        assertEquals(List.of("default,Read"), request.permissions().baseTools());
        assertSame(client, request.testOverrides().streamingClient());
        assertNotNull(request.testOverrides().output());
        assertTrue(request.mode().sdkStreamJson());
        assertTrue(request.mode().headless());
        assertFalse(request.mode().interactive());
        assertTrue(request.mode().formattedOutput());
        assertThrows(UnsupportedOperationException.class,
            () -> request.workspace().mcpConfig().add("later.json"));
    }

    @Test
    void snapshotPreservesAnOmittedToolsOptionAsNull() {
        CliLaunchRequest request = new ClaudeCodeCli().snapshotLaunchRequest();

        assertNull(request.permissions().baseTools());
    }

    @Test
    void sessionNameSupportsLongAndShortOptionsWithStartupNormalization() {
        CliLaunchRequest longForm = snapshot("--name", "  Named session  ");
        CliLaunchRequest shortForm = snapshot("-n", "Short name");
        CliLaunchRequest blank = snapshot("--name", "   ");

        assertEquals("Named session", longForm.session().name());
        assertEquals("Short name", shortForm.session().name());
        assertNull(blank.session().name());
    }

    @Test
    void repeatedSessionNameKeepsTheLastValueAndMissingValueIsRejected() {
        CliLaunchRequest request = snapshot(
            "--name", "first", "-n", "second", "hello");

        assertEquals("second", request.session().name());
        assertThrows(CommandLine.ParameterException.class,
            () -> new CommandLine(new ClaudeCodeCli()).parseArgs("--name"));
    }

    @Test
    void sessionIdIsCapturedAndRepeatedArgumentsKeepTheLastValue() {
        String first = "11111111-2222-4333-8444-555555555555";
        String second = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

        CliLaunchRequest request = snapshot(
            "--session-id", first, "--session-id", second);

        assertEquals(second, request.session().sessionId());
        assertThrows(CommandLine.ParameterException.class,
            () -> new CommandLine(new ClaudeCodeCli()).parseArgs("--session-id"));
    }

    @Test
    void hiddenSetupFlagsMapToReleasedTriggers() {
        CliLaunchRequest init = snapshot("--init");
        CliLaunchRequest initOnly = snapshot("--init-only");
        CliLaunchRequest maintenance = snapshot("--maintenance");

        assertEquals("init", init.session().setupTrigger());
        assertEquals("init", initOnly.session().setupTrigger());
        assertTrue(initOnly.session().initOnly());
        assertEquals("maintenance", maintenance.session().setupTrigger());
    }

    @Test
    void hiddenPlanModeInstructionsFlagReachesImmutableLaunchRequest() {
        CliLaunchRequest request = snapshot(
            "--print", "--plan-mode-instructions", "CUSTOM PLAN WORKFLOW", "hello");

        assertEquals("CUSTOM PLAN WORKFLOW", request.model().planModeInstructions());
    }

    @Test
    void hiddenRewindFilesFlagCreatesAStandaloneHeadlessLaunch() {
        CliLaunchRequest request = snapshot(
            "--rewind-files", "user-message-id", "--resume", "session-id");

        assertEquals("user-message-id", request.session().rewindFiles());
        assertEquals("session-id", request.session().resumeSession());
        assertTrue(request.mode().headless());
        assertFalse(request.mode().interactive());
        assertTrue(new CommandLine(new ClaudeCodeCli()).getCommandSpec()
            .findOption("--rewind-files").hidden());
    }

    @Test
    void snapshotDerivesEachNormalLaunchModeWithoutExecutionReadingPicocliFields() {
        CliLaunchRequest interactive = snapshot("hello");
        CliLaunchRequest print = snapshot("--print", "hello");
        CliLaunchRequest noInteractive = snapshot("--no-interactive", "hello");
        CliLaunchRequest sdk = snapshot(
            "--input-format", "stream-json", "--output-format", "stream-json");

        assertTrue(interactive.mode().interactive());
        assertFalse(interactive.mode().headless());

        assertTrue(print.mode().headless());
        assertFalse(print.mode().sdkStreamJson());

        assertTrue(noInteractive.mode().headless());
        assertFalse(noInteractive.mode().interactive());

        assertTrue(sdk.mode().sdkStreamJson());
        assertTrue(sdk.mode().headless());
        assertTrue(sdk.mode().formattedOutput());
    }

    private static CliLaunchRequest snapshot(String... args) {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        ClaudeCodeCli.commandLine(cli).parseArgs(args);
        return cli.snapshotLaunchRequest();
    }
}
