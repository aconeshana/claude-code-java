package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.commands.CommandResult;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.prompt.McpInstructionEntry;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.skills.Skill;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.io.TempFilePaths;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * End-to-end smoke tests for the CLI entry point.
 */
class ClaudeCodeCliTest {

    @Test
    void duplicateDangerousPermissionFlagIsAcceptedLikeOfficialCommander() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        CommandLine command = ClaudeCodeCli.commandLine(cli);

        assertDoesNotThrow(() -> command.parseArgs(
            "--dangerously-skip-permissions",
            "--dangerously-skip-permissions"));
    }

    private static int runSdkControlModeForTest(
            DefaultQuerySession engine, PrintWriter out, PermissionGate permissionGate,
            boolean replayUserMessages, StdoutMessageWriter.SdkOutputState state,
            String restoredSessionMode, String permissionPromptToolName) {
        return CliHeadlessOutput.runSdkControlMode(
            engine, out, permissionGate, "/tmp/project",
            false, replayUserMessages, null, () -> state,
            restoredSessionMode, permissionPromptToolName,
            null, Map::of, null, null, null, null, null, null);
    }

    @Test
    void headlessSessionsRetainSdkRelayableQuestionAndPlanTools() {
        assertFalse(CliExecutionRouter.shouldHideInteractiveTools(false, "stream-json"));
        assertFalse(CliExecutionRouter.shouldHideInteractiveTools(true, "text"));
        assertFalse(CliExecutionRouter.shouldHideInteractiveTools(false, "text"));
    }

    @Test
    void questionPreviewFormatDistinguishesSdkUndefinedFromCliMarkdown() {
        assertNull(CliToolchainAssembler.resolveQuestionPreviewFormat(true, null, null));
        assertEquals("markdown",
            CliToolchainAssembler.resolveQuestionPreviewFormat(false, null, null));
        assertEquals("html",
            CliToolchainAssembler.resolveQuestionPreviewFormat(true, "html", "markdown"));
        assertEquals("markdown",
            CliToolchainAssembler.resolveQuestionPreviewFormat(true, "invalid", "markdown"));
    }

    @Test
    void sdkStreamInputUsesSdkCliSystemPromptAndBillingProfile() {
        assertTrue(CliWorkspaceBootstrap.isSdkCliSession(false, "stream-json"));
        assertTrue(CliWorkspaceBootstrap.isSdkCliSession(true, "text"));
        assertFalse(CliWorkspaceBootstrap.isSdkCliSession(false, "text"));
    }

    @Test
    void restoredPrintTurnWritesQueueLifecycleBeforeRecoveredMessages() {
        List<String> writes = new ArrayList<>();
        TranscriptSink sink = new TranscriptSink() {
            @Override public void record(String sessionId, Message message) {
                writes.add("recovery:" + message.type());
            }

            @Override public void prepareSessionMaterialization(String sessionId) {
                writes.add("prepare");
            }

            @Override public void recordQueueOperation(
                    String sessionId, String operation, String content) {
                writes.add("queue:" + operation);
            }
        };

        CliHeadlessSessionRunner.recordPromptPreamble(
            sink, "session-1", "prompt",
            () -> writes.add("recovery:user"));

        assertEquals(List.of(
            "prepare", "queue:enqueue", "queue:dequeue", "recovery:user"), writes);
    }

    @Test
    void restoredPrintTurnCachesEffectiveModeWhenCoordinatorFeatureIsAvailable() {
        assertFalse(CliHeadlessSessionRunner.shouldRecordRestoredSessionMode(true, false));
        assertFalse(CliHeadlessSessionRunner.shouldRecordRestoredSessionMode(false, true));
        assertTrue(CliHeadlessSessionRunner.shouldRecordRestoredSessionMode(true, true));
    }

    @Test
    void restoredPrintModeIsMaterializedOnlyWhenTheTranscriptDoesNotAlreadyHaveOne() {
        TranscriptSink missing = new TranscriptSink() {
            @Override public void record(String sessionId, Message message) { }
        };
        TranscriptSink persisted = new TranscriptSink() {
            @Override public void record(String sessionId, Message message) { }
            @Override public boolean hasPersistedMode(String sessionId) { return true; }
        };

        assertTrue(CliHeadlessSessionRunner.shouldAppendRestoredMode(
            missing, "session-1", "normal"));
        assertFalse(CliHeadlessSessionRunner.shouldAppendRestoredMode(
            persisted, "session-1", "normal"));
    }

    @Test
    void parsesSdkUserIdentityAndPreservesEveryStructuredContentBlock() throws Exception {
        var node = JsonUtils.getMapper().readTree("""
            {
              "type":"user",
              "uuid":"source-user-uuid",
              "timestamp":"2026-07-29T17:00:00Z",
              "message":{
                "role":"user",
                "content":[
                  {"type":"text","text":"look"},
                  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGVsbG8="}}
                ]
              }
            }
            """);

        CliHeadlessOutput.SdkUserInput input =
            CliHeadlessOutput.parseSdkUserInput(node, Instant.EPOCH);

        assertEquals("source-user-uuid", input.uuid());
        assertEquals(Instant.EPOCH, input.timestamp(),
            "2.1.197 creates the replay timestamp when the prompt is accepted; it does not reuse inbound timestamp");
        assertEquals(2, input.content().blocks().size());
        assertInstanceOf(TextBlock.class, input.content().blocks().getFirst());
        assertInstanceOf(ImageBlock.class, input.content().blocks().get(1));
    }

    @Test
    void sdkStreamRejectsNonUserMessageRoleLikeReleasedStructuredIo() throws Exception {
        var assistant = JsonUtils.getMapper().readTree("""
            {"type":"user","message":{"role":"assistant","content":"do not send"}}
            """);

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> CliHeadlessOutput.parseSdkUserInput(assistant, Instant.EPOCH));

        assertEquals("Error: Expected message role 'user', got 'assistant'", error.getMessage());
    }

    @Test
    void sdkStreamRejectsMissingUserMessageEnvelope() throws Exception {
        var missing = JsonUtils.getMapper().readTree("""
            {"type":"user"}
            """);

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> CliHeadlessOutput.parseSdkUserInput(missing, Instant.EPOCH));

        assertEquals("__MISSING_USER_MESSAGE__", error.getMessage());
    }

    @Test
    void sdkEnvironmentUpdateAcknowledgesTopLevelRequestId() throws Exception {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(("""
                {"type":"update_environment_variables","request_id":"env-1","variables":{}}
                """).getBytes(StandardCharsets.UTF_8)));
            DefaultQuerySession engine = new DefaultQuerySession(
                QuerySessionSpec.builder()
                    .llmClient(new MockStreamingClient("OK"))
                    .workingDirectory("/tmp/project")
                    .model("claude-sonnet-4-6")
                    .build());
            StringWriter output = new StringWriter();
            var state = new StdoutMessageWriter.SdkOutputState(
                StdoutMessageWriter.SdkOutputMetadata.fromEngine(engine),
                SdkInboundControlHandler.ControlCatalog.empty());

            int exit = runSdkControlModeForTest(
                engine, new PrintWriter(output, true), new PermissionGate(),
                false, state, null, null);

            assertEquals(0, exit);
            JsonNode response = JsonUtils.getMapper().readTree(
                output.toString().lines()
                    .filter(line -> Strings.CS.contains(line, "\"request_id\":\"env-1\""))
                    .findFirst().orElseThrow());
            assertEquals("control_response", response.path("type").asText());
            assertEquals("success", response.path("response").path("subtype").asText());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void cliDefinedAgentInitialPromptIsNotInjectedIntoReleasedStreamJsonInput() {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(("""
                {"type":"user","uuid":"11111111-2222-4333-8444-555555555555","message":{"role":"user","content":"USER"}}
                """).getBytes(StandardCharsets.UTF_8)));
            MockStreamingClient mockClient = new MockStreamingClient("OK");
            ClaudeCodeCli cli = new ClaudeCodeCli();
            cli.setStreamingClientOverride(mockClient);
            cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

            int exitCode = new CommandLine(cli).execute(
                "--print", "--input-format", "stream-json",
                "--output-format", "stream-json", "--verbose",
                "--agents", "{\"boot\":{\"description\":\"boot test\","
                    + "\"prompt\":\"CUSTOM SYSTEM\",\"initialPrompt\":\"INITIAL\"}}",
                "--agent", "boot");

            assertEquals(0, exitCode);
            assertNotNull(mockClient.lastRequest);
            String messages = mockClient.lastRequest.messages().toString();
            assertTrue(Strings.CS.contains(messages, "USER"), messages);
            assertFalse(Strings.CS.contains(messages, "INITIAL"),
                "released 2.1.197 CLI --agents stream input does not prepend initialPrompt: " + messages);
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void sdkControlTurnRecordsQueueLifecycleAndFinalPromptMetadata() {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(("""
                {"type":"user","uuid":"41111111-1111-4111-8111-111111111111","message":{"role":"user","content":"stream resume prompt"}}
                """).getBytes(StandardCharsets.UTF_8)));

            DefaultQuerySession engine = new DefaultQuerySession(
                QuerySessionSpec.builder()
                    .llmClient(new MockStreamingClient("OK"))
                    .workingDirectory("/tmp/project")
                    .model("claude-sonnet-4-6")
                    .build());
            engine.switchToSession("session-resume");
            List<String> writes = new CopyOnWriteArrayList<>();
            engine.setTranscriptSink(new TranscriptSink() {
                @Override
                public void record(String sessionId, Message message) {
                    writes.add("message:" + message.type());
                }

                @Override
                public void recordQueueOperation(
                        String sessionId, String operation, String content) {
                    writes.add("queue:" + operation
                        + (content != null ? ":" + content : ""));
                }

                @Override
                public void recordPromptStart(String sessionId, String promptSource) {
                    writes.add("prompt-start:" + promptSource);
                }

                @Override
                public void recordLastPrompt(String sessionId, String prompt) {
                    writes.add("last-prompt:" + prompt);
                }

                @Override
                public void recordMode(String sessionId, String mode) {
                    writes.add("mode:" + mode);
                }

                @Override
                public boolean awaitPendingWrites(String sessionId, long timeoutMillis) {
                    writes.add("await");
                    return true;
                }
            });
            StdoutMessageWriter.SdkOutputMetadata metadata =
                StdoutMessageWriter.SdkOutputMetadata.fromEngine(engine);
            var state = new StdoutMessageWriter.SdkOutputState(
                metadata, SdkInboundControlHandler.ControlCatalog.empty());

            int exit = runSdkControlModeForTest(
                engine, new PrintWriter(new StringWriter(), true),
                new PermissionGate(), true, state,
                "normal", null);

            assertEquals(0, exit);
            assertEquals(List.of(
                "queue:enqueue:stream resume prompt",
                "prompt-start:sdk",
                "queue:dequeue",
                "message:user",
                "message:assistant",
                "last-prompt:stream resume prompt",
                "mode:normal",
                "await"), writes);
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void sdkControlModeReturnsNonZeroWhenFinalResultIsError() {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(("""
                {"type":"user","uuid":"41111111-1111-4111-8111-111111111111","message":{"role":"user","content":"fail"}}
                """).getBytes(StandardCharsets.UTF_8)));

            DefaultQuerySession engine = new DefaultQuerySession(
                    QuerySessionSpec.builder()
                        .llmClient(new MockStreamingClient("unused"))
                        .workingDirectory("/tmp/project")
                        .model("claude-sonnet-4-6")
                        .build()) {
                @Override
                public Iterator<SDKMessage> submitMessage(
                        Object prompt, SubmitOptions options) {
                    return List.<SDKMessage>of(new SDKMessage.Result(
                        SDKMessage.Result.ERROR_DURING_EXECUTION,
                        List.of(), Usage.EMPTY, getSessionId())).iterator();
                }
            };
            StdoutMessageWriter.SdkOutputMetadata metadata =
                StdoutMessageWriter.SdkOutputMetadata.fromEngine(engine);
            var state = new StdoutMessageWriter.SdkOutputState(
                metadata, SdkInboundControlHandler.ControlCatalog.empty());

            int exit = runSdkControlModeForTest(
                engine, new PrintWriter(new StringWriter(), true),
                new PermissionGate(), false, state, null, null);

            assertEquals(1, exit,
                "released 2.1.197 exits non-zero when the final SDK result is_error=true");
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void sdkControlStartsANewPromptIdentityWhenEachQueuedUserTurnIsDequeued() {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(("""
                {"type":"user","uuid":"41111111-1111-4111-8111-111111111111","message":{"role":"user","content":"first"}}
                {"type":"user","uuid":"42222222-2222-4222-8222-222222222222","message":{"role":"user","content":"second"}}
                """).getBytes(StandardCharsets.UTF_8)));

            DefaultQuerySession engine = new DefaultQuerySession(
                QuerySessionSpec.builder()
                    .llmClient(new MockStreamingClient("OK"))
                    .workingDirectory("/tmp/project")
                    .model("claude-sonnet-4-6")
                    .build());
            List<String> writes = new CopyOnWriteArrayList<>();
            engine.setTranscriptSink(new TranscriptSink() {
                @Override public void record(
                        String sessionId, Message message) {
                    writes.add("message:" + message.type());
                }

                @Override public void recordQueueOperation(
                        String sessionId, String operation, String content) {
                    writes.add("queue:" + operation);
                }

                @Override public void recordPromptStart(String sessionId, String promptSource) {
                    writes.add("prompt-start:" + promptSource);
                }
            });
            StdoutMessageWriter.SdkOutputMetadata metadata =
                StdoutMessageWriter.SdkOutputMetadata.fromEngine(engine);
            var state = new StdoutMessageWriter.SdkOutputState(
                metadata, SdkInboundControlHandler.ControlCatalog.empty());

            int exit = runSdkControlModeForTest(
                engine, new PrintWriter(new StringWriter(), true),
                new PermissionGate(), false, state, null, null);

            assertEquals(0, exit);
            assertEquals(List.of(
                "prompt-start:sdk", "queue:dequeue",
                "prompt-start:sdk", "queue:dequeue"),
                writes.stream()
                    .filter(value -> Strings.CS.startsWith(value, "prompt-start:")
                        || Strings.CS.equals(value, "queue:dequeue"))
                    .toList());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void sdkPermissionBrokerIsInstalledOnlyForStdioPermissionPromptTool() {
        InputStream originalIn = System.in;
        try {
            StdoutMessageWriter.SdkOutputState emptyState =
                new StdoutMessageWriter.SdkOutputState(
                    StdoutMessageWriter.SdkOutputMetadata.fromEngine(new DefaultQuerySession(
                        QuerySessionSpec.builder()
                            .llmClient(new MockStreamingClient("OK"))
                            .build())),
                    SdkInboundControlHandler.ControlCatalog.empty());

            DefaultQuerySession withoutFlag = new DefaultQuerySession(
                QuerySessionSpec.builder()
                    .llmClient(new MockStreamingClient("OK"))
                    .build());
            System.setIn(new ByteArrayInputStream(new byte[0]));
            runSdkControlModeForTest(withoutFlag,
                new PrintWriter(new StringWriter(), true), new PermissionGate(),
                false, emptyState, null, null);
            assertNull(withoutFlag.getPermissionAskCallback());

            DefaultQuerySession withStdio = new DefaultQuerySession(
                QuerySessionSpec.builder()
                    .llmClient(new MockStreamingClient("OK"))
                    .build());
            System.setIn(new ByteArrayInputStream(new byte[0]));
            runSdkControlModeForTest(withStdio,
                new PrintWriter(new StringWriter(), true), new PermissionGate(),
                false, emptyState, null, "stdio");
            assertNotNull(withStdio.getPermissionAskCallback());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void mcpInstructionsArePlacedBetweenAgentAndSkillListings() {
        String listing = """
            Available agent types for the Agent tool:
            - Alpha: agent

            When you launch multiple agents for independent work, send them in a single message \
            with multiple tool uses so they run concurrently.

            The following skills are available for use with the Skill tool:

            - verify: verify changes""";

        String combined = CliPromptInventoryAssembler.insertMcpInstructions(listing, List.of(
            new McpInstructionEntry("alpha", "ALPHA instructions"),
            new McpInstructionEntry("zeta", "ZETA instructions")));

        int concurrency = combined.indexOf("When you launch multiple agents");
        int mcp = combined.indexOf("# MCP Server Instructions");
        int skills = combined.indexOf("The following skills are available");
        assertTrue(concurrency < mcp && mcp < skills);
        assertTrue(Strings.CS.contains(combined, "## alpha\nALPHA instructions\n\n## zeta\nZETA instructions"));
    }

    @Test
    void skillListingLetsFilesystemSkillsShadowBundledButKeepsBuiltinCommands() {
        Skill userVerify = skill("verify", "user verify", Skill.SkillSource.USER);
        Skill bundledVerify = skill("verify", "bundled verify", Skill.SkillSource.BUNDLED);
        Skill builtinVerify = skill("verify", "builtin verify", Skill.SkillSource.BUILTIN);
        Skill other = skill("other", "other description", Skill.SkillSource.PROJECT);

        List<SkillListingEntry> entries = CliPromptInventoryAssembler.skillListingEntries(
            List.of(userVerify, bundledVerify, other, builtinVerify));

        assertEquals(List.of("verify", "other", "verify"),
            entries.stream().map(SkillListingEntry::name).toList());
        assertEquals("user verify", entries.getFirst().description(),
            "the filesystem definition must shadow the later bundled fallback");
        assertFalse(entries.getFirst().bundled(),
            "a shadowed bundled skill must not make the user definition look protected/bundled");
        assertEquals("builtin verify", entries.getLast().description(),
            "2.1.197 keeps same-named compiled commands in the model-visible inventory");
    }

    @Test
    void gptModelsHideOnlyTheBundledClaudeApiSkill() {
        Skill bundledClaudeApi = skill(
            "claude-api", "large bundled reference", Skill.SkillSource.BUNDLED);
        Skill bundledVerify = skill("verify", "verify", Skill.SkillSource.BUNDLED);
        Skill userClaudeApi = skill(
            "claude-api", "user override", Skill.SkillSource.USER);

        List<SkillListingEntry> bundledEntries = CliPromptInventoryAssembler.skillListingEntries(
            List.of(bundledClaudeApi, bundledVerify), "gpt-5.6-sol");
        List<SkillListingEntry> claudeEntries = CliPromptInventoryAssembler.skillListingEntries(
            List.of(bundledClaudeApi, bundledVerify), "claude-sonnet-4-6");
        List<SkillListingEntry> shadowedEntries = CliPromptInventoryAssembler.skillListingEntries(
            List.of(userClaudeApi, bundledClaudeApi), "gpt-5.6-sol");

        assertEquals(List.of("verify"),
            bundledEntries.stream().map(SkillListingEntry::name).toList());
        assertEquals(List.of("claude-api", "verify"),
            claudeEntries.stream().map(SkillListingEntry::name).toList());
        assertEquals(List.of("claude-api"),
            shadowedEntries.stream().map(SkillListingEntry::name).toList());
        assertFalse(shadowedEntries.getFirst().bundled());
    }

    private static Skill skill(String name, String description, Skill.SkillSource source) {
        return new Skill(
            name, description, List.of(), "body", Path.of("/skills", source.name(), name, "SKILL.md"),
            source, null, null, null, Map.of());
    }

    @Test
    void testVersionFlag() {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new ClaudeCodeCli());
        cmd.setOut(new PrintWriter(sw));
        int exitCode = cmd.execute("--version");
        assertEquals(0, exitCode);
        assertTrue(Strings.CS.contains(sw.toString(), "claude-code-java 0.1.0"),
            "Version output should contain version string, got: " + sw.toString());
    }

    @Test
    void testHelpFlag() {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new ClaudeCodeCli());
        cmd.setOut(new PrintWriter(sw));
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(Strings.CS.contains(output, "claude"), "Help should mention command name");
        assertTrue(Strings.CS.contains(output, "--model"), "Help should list --model option");
        assertTrue(Strings.CS.contains(output, "--api-key"), "Help should list --api-key option");
        assertTrue(Strings.CS.contains(output, "--max-tokens"), "Help should list --max-tokens option");
        assertTrue(Strings.CS.contains(output, "--output-format"), "Help should list --output-format option");
        assertTrue(Strings.CS.contains(output, "--no-interactive"), "Help should list --no-interactive option");
    }

    @Test
    void testCliCanBeInstantiatedWithPicocli() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        CommandLine cmd = new CommandLine(cli);
        assertNotNull(cmd);
        assertEquals("claude", cmd.getCommandName());
    }

    @Test
    void testOptionParsing() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        CommandLine cmd = new CommandLine(cli);
        cmd.parseArgs(
            "--model", "claude-opus-4-20250514",
            "--api-key", "sk-test-key",
            "--system-prompt", "Be helpful",
            "--append-system-prompt", "Keep answers concise",
            "--add-dir", "/tmp",
            "--max-tokens", "8192",
            "--max-turns", "50",
            "--max-budget-usd", "5.0",
            "--output-format", "json",
            "--no-interactive",
            "Hello Claude"
        );

        assertEquals("claude-opus-4-20250514", cli.getModel());
        assertEquals("sk-test-key", cli.getApiKey());
        assertEquals("Be helpful", cli.getSystemPrompt());
        assertEquals("Keep answers concise", cli.getAppendSystemPrompt());
        assertEquals(8192, cli.getMaxTokens());
        assertEquals(50, cli.getMaxTurns());
        assertEquals(5.0, cli.getMaxBudgetUsd(), 0.001);
        assertEquals("json", cli.getOutputFormat());
        assertTrue(cli.isNoInteractive());
        assertEquals("Hello Claude", cli.getInitialPrompt());
    }

    @Test
    void parses197EffortAndHiddenThinkingOptions() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        CommandLine cmd = new CommandLine(cli);

        assertDoesNotThrow(() -> cmd.parseArgs(
            "--effort", "low",
            "--thinking", "disabled",
            "--max-thinking-tokens", "5000"));

        assertEquals("low", cmd.getParseResult().matchedOptionValue("--effort", null));
        assertEquals("disabled", cmd.getParseResult().matchedOptionValue("--thinking", null));
        assertEquals(Integer.valueOf(5000),
            cmd.getParseResult().matchedOptionValue("--max-thinking-tokens", null));
    }

    @Test
    void parsesOfficialPermissionToolFlagsAndAliases() {
        CommandLine cmd = new CommandLine(new ClaudeCodeCli());

        assertDoesNotThrow(() -> cmd.parseArgs(
            "prompt",
            "--allowed-tools", "Read,Bash(git *)",
            "--disallowedTools", "Write",
            "--tools", "Read,Bash"));

        assertEquals(List.of("Read,Bash(git *)"),
            cmd.getParseResult().matchedOptionValue("--allowed-tools", null));
        assertEquals(List.of("Write"),
            cmd.getParseResult().matchedOptionValue("--disallowedTools", null));
        assertEquals(List.of("Read,Bash"),
            cmd.getParseResult().matchedOptionValue("--tools", null));
    }

    @Test
    void parsesOfficialDisableSlashCommandsFlagAndRejectsNonOfficialAlias() {
        ClaudeCodeCli official = new ClaudeCodeCli();
        new CommandLine(official).parseArgs("--disable-slash-commands");
        assertTrue(official.isDisableSlashCommands());

        ClaudeCodeCli alias = new ClaudeCodeCli();
        assertThrows(CommandLine.ParameterException.class,
            () -> new CommandLine(alias).parseArgs("--disable-skills"));
    }

    @Test
    void parsesOfficialSettingSourcesSeparatelyFromAdditionalSettings() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        new CommandLine(cli).parseArgs(
            "--setting-sources", "user,local",
            "--settings", "{\"model\":\"opus\"}");

        CliWorkspaceBootstrap.SettingSourceSelection selection =
            CliWorkspaceBootstrap.parseSettingSources(cli.getSettingSourcesRaw());
        assertTrue(selection.user());
        assertFalse(selection.project());
        assertTrue(selection.local());
        assertEquals(List.of(RuleSource.USER_SETTINGS, RuleSource.LOCAL_SETTINGS),
            selection.orderedSources());
        assertFalse(selection.flagBeforePolicy(),
            "an explicit --setting-sources list appends policy before flag, like TS Set insertion order");
        assertEquals("{\"model\":\"opus\"}", cli.getSettingsFileOrJson());

        ClaudeCodeCli empty = new ClaudeCodeCli();
        new CommandLine(empty).parseArgs("--setting-sources", "");
        CliWorkspaceBootstrap.SettingSourceSelection isolated =
            CliWorkspaceBootstrap.parseSettingSources(empty.getSettingSourcesRaw());
        assertFalse(isolated.user());
        assertFalse(isolated.project());
        assertFalse(isolated.local());
        assertTrue(isolated.orderedSources().isEmpty());
        assertFalse(isolated.flagBeforePolicy());

        CliWorkspaceBootstrap.SettingSourceSelection defaults =
            CliWorkspaceBootstrap.parseSettingSources(null);
        assertTrue(defaults.flagBeforePolicy());

        ClaudeCodeCli reordered = new ClaudeCodeCli();
        new CommandLine(reordered).parseArgs("--setting-sources", "local,user");
        assertEquals(List.of(RuleSource.LOCAL_SETTINGS, RuleSource.USER_SETTINGS),
            CliWorkspaceBootstrap.parseSettingSources(reordered.getSettingSourcesRaw()).orderedSources());

        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
            () -> CliWorkspaceBootstrap.parseSettingSources("user,workspace"));
        assertEquals(
            "Invalid setting source: workspace. Valid options are: user, project, local",
            invalid.getMessage());
    }

    @Test
    void resolvesLaunchModelWithCliEnvironmentSettingsAndDefaultPrecedence() {
        assertEquals("cli",
            CliWorkspaceBootstrap.resolveLaunchModel("cli", "env", "settings"));
        assertEquals("env",
            CliWorkspaceBootstrap.resolveLaunchModel(null, "env", "settings"));
        assertEquals("settings",
            CliWorkspaceBootstrap.resolveLaunchModel(null, null, "settings"));
        assertEquals(ModelNames.defaultMainLoopModel(),
            CliWorkspaceBootstrap.resolveLaunchModel(null, null, null));
        assertEquals(ModelNames.defaultMainLoopModel(),
            CliWorkspaceBootstrap.resolveLaunchModel("default", "env", "settings"));
        assertNull(CliWorkspaceBootstrap.resolveLaunchModelPreference(null, null, null));
        assertEquals("settings",
            CliWorkspaceBootstrap.resolveLaunchModelPreference(null, null, "settings"));
        assertEquals(ModelNames.defaultMainLoopModel(),
            CliWorkspaceBootstrap.resolveLaunchModelPreference("default", "env", "settings"));
    }

    @Test
    void settingsFlagLoadsInlineObjectIntoFlagSettingsBeforeStartup() throws Exception {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        String raw = "{\"language\":\"Chinese\",\"outputStyle\":\"explanatory\"}";
        Path stableSettingsPath = TempFilePaths.generate("claude-settings", ".json", raw);
        new CommandLine(cli).parseArgs(
            "--settings", raw);

        try {
            CliWorkspaceBootstrap.resolveSettingsOption(cli.getSettingsFileOrJson());
            var flags = SettingsSources.flagSettingsSnapshot();
            assertEquals("Chinese", flags.path("language").asText());
            assertEquals("explanatory", flags.path("outputStyle").asText());
            assertEquals(raw, Files.readString(stableSettingsPath, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(stableSettingsPath);
            SettingsSources.clearFlagSettings();
        }
    }

    @Test
    void settingsFlagTreatsAnEmptyFileAsAnEmptySettingsObject() throws Exception {
        Path settingsPath = Files.createTempFile("claude-empty-settings", ".json");
        try {
            ClaudeCodeCli cli = new ClaudeCodeCli();
            new CommandLine(cli).parseArgs("--settings", settingsPath.toString());
            CliWorkspaceBootstrap.resolveSettingsOption(cli.getSettingsFileOrJson());
            assertTrue(SettingsSources.flagSettingsSnapshot().isEmpty());
        } finally {
            Files.deleteIfExists(settingsPath);
            SettingsSources.clearFlagSettings();
        }
    }

    @Test
    void settingsFlagDefersMalformedFileHandlingUntilTheSourceIsRead() throws Exception {
        Path settingsPath = Files.createTempFile("claude-malformed-settings", ".json");
        Files.writeString(settingsPath, "{not-json", StandardCharsets.UTF_8);
        try {
            ClaudeCodeCli cli = new ClaudeCodeCli();
            new CommandLine(cli).parseArgs("--settings", settingsPath.toString());
            assertDoesNotThrow(() ->
                CliWorkspaceBootstrap.resolveSettingsOption(cli.getSettingsFileOrJson()));
            assertTrue(SettingsSources.flagSettingsSnapshot().isEmpty());
        } finally {
            Files.deleteIfExists(settingsPath);
            SettingsSources.clearFlagSettings();
        }
    }

    @Test
    void settingsOptionWithoutFlagClearsPreviousProcessLocalSource() throws Exception {
        Path settingsPath = Files.createTempFile("claude-previous-settings", ".json");
        try {
            Files.writeString(settingsPath, "{\"language\":\"Chinese\"}", StandardCharsets.UTF_8);
            SettingsSources.setFlagSettingsSource(
                settingsPath, JsonUtils.readJson(settingsPath));
            assertEquals("Chinese", SettingsSources.flagSettingsSnapshot().path("language").asText());

            ClaudeCodeCli cli = new ClaudeCodeCli();
            CliWorkspaceBootstrap.resolveSettingsOption(cli.getSettingsFileOrJson());

            assertTrue(SettingsSources.flagSettingsSnapshot().isEmpty());
            assertNull(SettingsSources.flagSettingsPath());
        } finally {
            Files.deleteIfExists(settingsPath);
            SettingsSources.clearFlagSettings();
        }
    }

    @Test
    void parsesRepeatableOfficialPluginDirOption() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        CommandLine command = new CommandLine(cli);

        assertDoesNotThrow(() -> command.parseArgs(
            "--plugin-dir", "/tmp/plugin-a",
            "--plugin-dir", "/tmp/plugin-b"));

        assertEquals(List.of("/tmp/plugin-a", "/tmp/plugin-b"), cli.pluginDirs);
    }

    @Test
    void testNonInteractiveModeWithMockClient() {
        // Create a mock streaming client that returns a simple response
        StreamingClient mockClient = new MockStreamingClient("Hello! I'm Claude.");

        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(mockClient);

        StringWriter sw = new StringWriter();
        cli.setOutputWriter(new PrintWriter(sw, true));

        CommandLine cmd = new CommandLine(cli);
        int exitCode = cmd.execute("--no-interactive", "Hi there");

        assertEquals(0, exitCode);
        assertTrue(Strings.CS.contains(sw.toString(), "Hello! I'm Claude."),
            "Output should contain mock response, got: " + sw.toString());
    }

    @Test
    void nonInteractivePromptCommandIsExpandedBeforeTheModelRequest() {
        MockStreamingClient mockClient = new MockStreamingClient("Initialized.");
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(mockClient);
        cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

        int exitCode = new CommandLine(cli).execute("--no-interactive", "/init");

        assertEquals(0, exitCode);
        assertNotNull(mockClient.lastRequest, "prompt command must still issue a model request");
        String messages = mockClient.lastRequest.messages().toString();
        assertTrue(Strings.CS.contains(messages, "Please analyze this codebase"),
            "the request must contain InitCommand's expanded prompt: " + messages);
        assertFalse(Strings.CS.contains(messages, "content=/init"),
            "the raw slash command must not be sent to the model: " + messages);
        InvokedSkillRegistry.Entry invocation = InvokedSkillRegistry.global()
            .entriesFor(null).stream()
            .filter(entry -> Strings.CS.equals(entry.name(), "init"))
            .findFirst().orElseThrow();
        assertEquals("builtin:init", invocation.path());
        assertTrue(Strings.CS.contains(invocation.content(), "Please analyze this codebase"));
    }

    @Test
    void nonInteractiveEligibleLocalCommandReturnsWithoutCallingTheModel() {
        MockStreamingClient mockClient = new MockStreamingClient("must not be used");
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(mockClient);
        StringWriter sw = new StringWriter();
        cli.setOutputWriter(new PrintWriter(sw, true));

        int exitCode = new CommandLine(cli).execute("--no-interactive", "/cost");

        assertEquals(0, exitCode);
        assertNull(mockClient.lastRequest, "local headless command must not query the model");
        assertTrue(Strings.CS.contains(sw.toString(), "Total cost:"), sw.toString());
    }

    @Test
    void headlessTextModeDoesNotPrintCompactDisplayOnlyText() {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw, true);

        CliHeadlessSessionRunner.writeCommandResult(null,
            CommandResult.displayOnly("Compacted (ctrl+o to see full summary)"),
            out, "text", false, 1L, () -> null);

        assertEquals("", sw.toString(),
            "2.1.197 returns an empty text result after successful headless /compact");
    }

    @Test
    void nonInteractiveUnsupportedCommandIsReportedAsUnknownWithoutCallingTheModel() {
        MockStreamingClient mockClient = new MockStreamingClient("must not be used");
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(mockClient);
        StringWriter sw = new StringWriter();
        cli.setOutputWriter(new PrintWriter(sw, true));

        int exitCode = new CommandLine(cli).execute("--no-interactive", "/clear");

        assertEquals(0, exitCode);
        assertNull(mockClient.lastRequest, "filtered local-jsx/local command must not query the model");
        assertTrue(Strings.CS.contains(sw.toString(), "Unknown skill: clear"), sw.toString());
    }

    @Test
    void mainQueryStopHookInvokesInjectedAutoDreamEngine() {
        AtomicInteger triggerCount = new AtomicInteger();
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(new MockStreamingClient("Dream wiring checked."));
        cli.setAutoDreamEngineOverride(_ -> triggerCount.incrementAndGet());
        cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

        int exitCode = new CommandLine(cli).execute("--no-interactive", "Hi there");

        assertEquals(0, exitCode);
        assertEquals(1, triggerCount.get(),
            "the CLI-composed main engine must invoke auto-dream from its stop hook");
    }

    @Test
    void testDefaultValues() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        new CommandLine(cli).parseArgs();

        // No --max-tokens passed: field stays null so the effective request
        // value falls back to the resolved model's own default instead of
        // one hardcoded constant (see maxTokensDefaultsToModelBoundsWhenFlagOmitted below).
        assertNull(cli.getMaxTokens());
        assertEquals(0, cli.getMaxTurns(),
            "released --max-turns has no default: an omitted flag means no turn ceiling");
        assertEquals(-1.0, cli.getMaxBudgetUsd(), 0.001);
        assertEquals("text", cli.getOutputFormat());
        assertFalse(cli.isNoInteractive());
        assertNull(cli.getInitialPrompt());
        assertNull(cli.getModel());
        assertNull(cli.getApiKey());
    }

    @Test
    void maxBudgetIsUnlimitedUntilExplicitlyConfigured() {
        assertEquals(-1.0, CliWorkspaceBootstrap.effectiveMaxBudgetUsd(-1.0), 0.000001);
        assertEquals(3.0, CliWorkspaceBootstrap.effectiveMaxBudgetUsd(3.0), 0.000001);
    }

    @Test
    void mcpInstructionsDeltaProfileHonorsExplicitEnvOverride() {
        assertTrue(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled(null));
        assertTrue(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled(""));
        assertTrue(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled("true"));
        assertTrue(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled("1"));
        assertTrue(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled("on"));
        assertFalse(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled("false"));
        assertFalse(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled("0"));
        assertFalse(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled("off"));
        assertFalse(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled(
            null, "1", null));
        assertTrue(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled(
            null, "1", "ant"));
        assertTrue(CliEngineAssembler.effectiveMcpInstructionsDeltaEnabled(
            "true", "1", null));
    }

    @Test
    void maxTokensDefaultsToResolvedModelBoundsWhenFlagOmitted() {
        // No --max-tokens: effective request value must come from
        // ModelOutputTokens.getModelMaxOutputTokens(resolvedModel), not a
        // single hardcoded constant. Pin the resolved model so this assertion

        MockStreamingClient mockClient = new MockStreamingClient("hi");
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(mockClient);
        cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

        new CommandLine(cli).execute("--no-interactive", "--model", "claude-sonnet-4-6", "Hello");

        assertNotNull(mockClient.lastRequest, "streaming client never received a request");
        assertEquals(32_000, mockClient.lastRequest.maxTokens());
    }

    @Test
    void outputFormatJsonWritesOnlyFinalResultMessage() throws Exception {
        MockStreamingClient mockClient = new MockStreamingClient("Hello! I'm Claude.");
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(mockClient);
        StringWriter sw = new StringWriter();
        cli.setOutputWriter(new PrintWriter(sw, true));

        int exitCode = new CommandLine(cli).execute(
            "--print", "--output-format", "json", "Hi there");

        assertEquals(0, exitCode);
        String output = sw.toString().strip();
        JsonNode json = JsonUtils.getMapper().readTree(output);
        assertEquals("result", json.get("type").asText());
        assertEquals("success", json.get("subtype").asText());
        assertTrue(Strings.CS.contains(json.get("result").asText(), "Hello! I'm Claude."),
            "result text should contain the assistant's final text, got: " + json);
        assertFalse(json.get("is_error").asBoolean());
        assertTrue(json.has("session_id"));
        assertTrue(json.has("uuid"));
        assertTrue(json.get("duration_ms").asLong() >= 0);
    }

    @Test
    void outputFormatStreamJsonRequiresVerbose() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(new MockStreamingClient("hi"));
        StringWriter sw = new StringWriter();
        StringWriter errSw = new StringWriter();
        cli.setOutputWriter(new PrintWriter(sw, true));

        CommandLine cmd = new CommandLine(cli);
        cmd.setErr(new PrintWriter(errSw, true));
        int exitCode = cmd.execute("--print", "--output-format", "stream-json", "Hi");

        assertEquals(1, exitCode, "stream-json without --verbose must error, not silently run");
    }

    @Test
    void includePartialMessagesRequiresPrintAndStreamJson() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(new MockStreamingClient("hi"));
        cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

        CommandLine cmd = new CommandLine(cli);
        // No --print, no --output-format=stream-json: must be rejected.
        int exitCode = cmd.execute("--no-interactive", "--include-partial-messages", "Hi");

        assertEquals(1, exitCode);
    }

    @Test
    void replayUserMessagesRequiresStreamJsonInputEvenWithStreamJsonOutput() {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(new MockStreamingClient("hi"));
        cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

        int exitCode = new CommandLine(cli).execute(
            "--print", "--output-format", "stream-json", "--verbose",
            "--replay-user-messages", "Hi");

        assertEquals(1, exitCode);
    }

    @Test
    void outputFormatStreamJsonWritesNdjsonPerMessage() throws Exception {
        MockStreamingClient mockClient = new MockStreamingClient("Hello! I'm Claude.");
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(mockClient);
        StringWriter sw = new StringWriter();
        cli.setOutputWriter(new PrintWriter(sw, true));

        int exitCode = new CommandLine(cli).execute(
            "--print", "--output-format", "stream-json", "--verbose", "Hi there");

        assertEquals(0, exitCode);
        List<String> lines = sw.toString().lines().filter(l -> !StringUtils.isBlank(l)).toList();
        assertFalse(lines.isEmpty(), "stream-json mode should write at least one NDJSON line");
        boolean sawResult = false;
        for (String line : lines) {
            JsonNode json = JsonUtils.getMapper().readTree(line);
            assertTrue(json.has("type"), "every NDJSON line must have a type field: " + line);
            if (Strings.CS.equals("result", json.get("type").asText())) {
                sawResult = true;
                assertEquals("success", json.get("subtype").asText());
            }
            // Without --include-partial-messages, raw stream_event lines must not appear.
            assertNotEquals("stream_event", json.get("type").asText());
        }
        assertTrue(sawResult, "stream-json output must include the final result message");
    }

    @Test
    void launchModePropertiesAreRestoredAfterSdkCliSession() {
        String previousEntrypoint = System.getProperty("claude.code.entrypoint");
        String previousNonInteractive = System.getProperty("claude.code.nonInteractive");
        try {
            System.setProperty("claude.code.entrypoint", "outer-entrypoint");
            System.setProperty("claude.code.nonInteractive", "outer-mode");
            ClaudeCodeCli cli = new ClaudeCodeCli();
            cli.setStreamingClientOverride(new MockStreamingClient("OK"));
            cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

            int exitCode = new CommandLine(cli).execute(
                "--print", "--output-format", "stream-json", "--verbose", "Hi");

            assertEquals(0, exitCode);
            assertEquals("outer-entrypoint", System.getProperty("claude.code.entrypoint"));
            assertEquals("outer-mode", System.getProperty("claude.code.nonInteractive"));
        } finally {
            restoreProperty("claude.code.entrypoint", previousEntrypoint);
            restoreProperty("claude.code.nonInteractive", previousNonInteractive);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    @Test
    void selectedAgentInitialPromptPrecedesTheUserProvidedPrompt() {
        MockStreamingClient mockClient = new MockStreamingClient("OK");
        ClaudeCodeCli cli = new ClaudeCodeCli();
        cli.setStreamingClientOverride(mockClient);
        cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

        int exitCode = new CommandLine(cli).execute(
            "--no-interactive",
            "--agents", "{\"boot\":{\"description\":\"boot test\","
                + "\"prompt\":\"CUSTOM SYSTEM\",\"initialPrompt\":\"INITIAL\"}}",
            "--agent", "boot",
            "USER");

        assertEquals(0, exitCode);
        assertNotNull(mockClient.lastRequest);
        assertTrue(Strings.CS.contains(mockClient.lastRequest.messages().toString(), "INITIAL\n\nUSER"),
            mockClient.lastRequest.messages().toString());
    }

    @Test
    void selectedAgentInitialPromptSatisfiesPrintInputWithoutArgvOrStdin() {
        InputStream originalIn = System.in;
        try {
            System.setIn(InputStream.nullInputStream());
            MockStreamingClient mockClient = new MockStreamingClient("OK");
            ClaudeCodeCli cli = new ClaudeCodeCli();
            cli.setStreamingClientOverride(mockClient);
            cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

            int exitCode = new CommandLine(cli).execute(
                "--print", "--input-format", "text",
                "--output-format", "stream-json", "--verbose",
                "--agents", "{\"boot\":{\"description\":\"boot test\","
                    + "\"prompt\":\"CUSTOM SYSTEM\",\"initialPrompt\":\"INITIAL\"}}",
                "--agent", "boot");

            assertEquals(0, exitCode);
            assertNotNull(mockClient.lastRequest);
            assertTrue(Strings.CS.contains(mockClient.lastRequest.messages().toString(), "INITIAL"),
                mockClient.lastRequest.messages().toString());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void selectedAgentInitialPromptUsesTheOfficialTwoNewlineBoundaryBeforeStdin() {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream("STDIN".getBytes(StandardCharsets.UTF_8)));
            MockStreamingClient mockClient = new MockStreamingClient("OK");
            ClaudeCodeCli cli = new ClaudeCodeCli();
            cli.setStreamingClientOverride(mockClient);
            cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

            int exitCode = new CommandLine(cli).execute(
                "--print", "--input-format", "text",
                "--output-format", "stream-json", "--verbose",
                "--agents", "{\"boot\":{\"description\":\"boot test\","
                    + "\"prompt\":\"CUSTOM SYSTEM\",\"initialPrompt\":\"INITIAL\"}}",
                "--agent", "boot");

            assertEquals(0, exitCode);
            assertNotNull(mockClient.lastRequest);
            assertTrue(Strings.CS.contains(mockClient.lastRequest.messages().toString(), "INITIAL\n\nSTDIN"),
                mockClient.lastRequest.messages().toString());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void printTextInputUsesPipedStdinAsThePrompt() throws Exception {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(
                "WIRE197_STDIN_TEXT_ONLY\nsecond line\n".getBytes(StandardCharsets.UTF_8)));
            MockStreamingClient mockClient = new MockStreamingClient("OK");
            ClaudeCodeCli cli = new ClaudeCodeCli();
            cli.setStreamingClientOverride(mockClient);
            StringWriter stdout = new StringWriter();
            cli.setOutputWriter(new PrintWriter(stdout, true));

            int exitCode = new CommandLine(cli).execute(
                "--print", "--input-format", "text",
                "--output-format", "stream-json", "--verbose",
                "--model", "claude-sonnet-4-20250514");

            assertEquals(0, exitCode);
            assertFalse(mockClient.requests.isEmpty(),
                "stdin-only print mode must issue a model request instead of entering the TUI");
            assertTrue(mockClient.requests.stream().anyMatch(request ->
                    Strings.CS.contains(request.messages().toString(),
                        "WIRE197_STDIN_TEXT_ONLY\nsecond line\n")),
                mockClient.requests.toString());
            JsonNode init = JsonUtils.getMapper().readTree(
                stdout.toString().lines().filter(line -> !StringUtils.isBlank(line)).findFirst().orElseThrow());
            assertTrue(Strings.CS.contains(init.path("skills").toString(), "verify"), init.toString());
            assertTrue(Strings.CS.contains(init.path("slash_commands").toString(), "compact"), init.toString());
            assertFalse(Strings.CS.contains(init.path("agents").toString(), "Explore"),
                "credential-pinned Explore must be hidden when this custom-model test has no "
                    + "usable Anthropic fallback: " + init);
            assertFalse(Strings.CS.contains(init.path("tools").toString(), "AskUserQuestion"), init.toString());
            assertFalse(Strings.CS.contains(init.path("tools").toString(), "EnterPlanMode"), init.toString());
            assertFalse(Strings.CS.contains(init.path("tools").toString(), "ExitPlanMode"), init.toString());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void sdkPermissionPromptHostKeepsInteractiveToolsVisible() throws Exception {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream("WIRE\n".getBytes(StandardCharsets.UTF_8)));
            MockStreamingClient mockClient = new MockStreamingClient("OK");
            ClaudeCodeCli cli = new ClaudeCodeCli();
            cli.setStreamingClientOverride(mockClient);
            StringWriter stdout = new StringWriter();
            cli.setOutputWriter(new PrintWriter(stdout, true));

            int exitCode = new CommandLine(cli).execute(
                "--print", "--input-format", "text",
                "--output-format", "stream-json", "--verbose",
                "--permission-prompt-tool", "stdio");

            assertEquals(0, exitCode);
            JsonNode init = JsonUtils.getMapper().readTree(
                stdout.toString().lines().filter(line -> !StringUtils.isBlank(line)).findFirst().orElseThrow());
            assertTrue(Strings.CS.contains(init.path("tools").toString(), "AskUserQuestion"), init.toString());
            assertTrue(Strings.CS.contains(init.path("tools").toString(), "EnterPlanMode"), init.toString());
            assertTrue(Strings.CS.contains(init.path("tools").toString(), "ExitPlanMode"), init.toString());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void printTextInputAppendsPipedStdinToTheArgvPromptWithOneSeparatorNewline() {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(
                "STDIN_MARKER\n".getBytes(StandardCharsets.UTF_8)));
            MockStreamingClient mockClient = new MockStreamingClient("OK");
            ClaudeCodeCli cli = new ClaudeCodeCli();
            cli.setStreamingClientOverride(mockClient);
            cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

            int exitCode = new CommandLine(cli).execute(
                "--print", "--input-format", "text",
                "--output-format", "stream-json", "--verbose", "ARGV_MARKER");

            assertEquals(0, exitCode);
            assertFalse(mockClient.requests.isEmpty());
            assertTrue(mockClient.requests.stream().anyMatch(request ->
                    Strings.CS.contains(request.messages().toString(),
                        "ARGV_MARKER\nSTDIN_MARKER\n")),
                mockClient.requests.toString());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void printTextInputRejectsEofWhenNeitherArgvNorStdinProvidesAPrompt() {
        InputStream originalIn = System.in;
        try {
            System.setIn(InputStream.nullInputStream());
            MockStreamingClient mockClient = new MockStreamingClient("must not run");
            ClaudeCodeCli cli = new ClaudeCodeCli();
            cli.setStreamingClientOverride(mockClient);
            cli.setOutputWriter(new PrintWriter(new StringWriter(), true));

            int exitCode = new CommandLine(cli).execute(
                "--print", "--input-format", "text",
                "--output-format", "stream-json", "--verbose");

            assertEquals(1, exitCode);
            assertNull(mockClient.lastRequest);
        } finally {
            System.setIn(originalIn);
        }
    }

    /**
     * A mock StreamingClient that returns a fixed text response.
     */
    static class MockStreamingClient implements StreamingClient {

        private final String responseText;
        volatile StreamRequest lastRequest;
        final List<StreamRequest> requests = new CopyOnWriteArrayList<>();

        MockStreamingClient(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            lastRequest = request;
            requests.add(request);
            return new Iterator<>() {
                private int step = 0;

                @Override
                public boolean hasNext() {
                    return step < 3;
                }

                @Override
                public StreamingEvent next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return switch (step++) {
                        case 0 -> new StreamingEvent.MessageStartEvent(
                            "msg_001", "claude-sonnet-4-20250514",
                            List.of(), new Usage(10, 0, 0, 0)
                        );
                        case 1 -> new StreamingEvent.ContentBlockDeltaEvent(
                            0, "text_delta", responseText
                        );
                        case 2 -> new StreamingEvent.MessageDeltaEvent(
                            "end_turn", new Usage(0, 20, 0, 0)
                        );
                        default -> throw new NoSuchElementException();
                    };
                }
            };
        }

        @Override
        public String getModel() {
            return "claude-sonnet-4-20250514";
        }
    }
}
