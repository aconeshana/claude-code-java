package com.claudecode.cli;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.context.ContextData.AgentEntry;
import com.claudecode.commands.context.ContextData.Category;
import com.claudecode.commands.context.ContextData.ContextColor;
import com.claudecode.commands.context.ContextData.McpToolEntry;
import com.claudecode.commands.context.ContextData.MemoryFileEntry;
import com.claudecode.commands.context.ContextData.SkillEntry;
import com.claudecode.commands.context.ContextData.SkillInfo;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Usage;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HookEvent;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.services.plugins.marketplace.InstalledPluginsStore;
import com.claudecode.services.plugins.marketplace.PluginDirectories;
import com.claudecode.services.plugins.marketplace.PluginSettingsStore;
import com.claudecode.services.plugins.runtime.PluginRuntimeLoader;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.tools.mcp.McpToolProvider;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSdkControlRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void sdkMcpInitializationDefersBridgeHandshakeUntilTheFirstTurn() {
        DefaultQuerySession engine = testEngine(tempDir);
        AtomicReference<List<String>> prepared = new AtomicReference<>();
        try (McpToolProvider provider = new McpToolProvider() {
            @Override public synchronized void setSdkServers(List<String> serverNames) {
                prepared.set(List.copyOf(serverNames));
            }
        }) {
            DefaultSdkControlRuntime runtime = new DefaultSdkControlRuntime(
                engine, tempDir.toString(), null, Map::of, _ -> false,
                null, null, null, provider, null, null, null);
            var names = JsonUtils.getMapper().createArrayNode()
                .add("sdk-wire").add("second");

            runtime.configureSdkMcpServers(names);
            assertNull(prepared.get(),
                "initialize response must be writable before the bridge asks for MCP messages");

            runtime.prepareForTurn();
            assertEquals(List.of("sdk-wire", "second"), prepared.get());
        }
    }

    @Test
    void seedReadStateNormalizesBomAndCrlfAndUsesDiskMtime() throws Exception {
        Path file = tempDir.resolve("src/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "\uFEFFone\r\ntwo\r\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(12_345));
        DefaultQuerySession engine = testEngine(tempDir);
        DefaultSdkControlRuntime runtime = runtime(engine, null);

        runtime.seedReadState("src/A.java", 12_345);

        var state = engine.getFileStateCache().get(file.toString());
        assertNotNull(state);
        assertEquals("one\ntwo\n", state.content());
        assertEquals(Files.getLastModifiedTime(file).toMillis(), state.timestampMs());
        assertTrue(state.isFullRead());
        assertFalse(state.isPartialView());
    }

    @Test
    void seedReadStateSkipsFilesNewerThanObservedReadAndIgnoresMissingFiles() throws Exception {
        Path file = tempDir.resolve("newer.txt");
        Files.writeString(file, "new");
        Files.setLastModifiedTime(file, FileTime.fromMillis(20_000));
        DefaultQuerySession engine = testEngine(tempDir);
        DefaultSdkControlRuntime runtime = runtime(engine, null);

        runtime.seedReadState("newer.txt", 19_999);
        runtime.seedReadState("missing.txt", 99_999);

        assertNull(engine.getFileStateCache().get(file.toString()));
        assertEquals(0, engine.getFileStateCache().size());
    }

    @Test
    void contextPayloadMatchesSdkFieldNamesAndNormalWidthGridShape() {
        ContextData data = new ContextData(
            List.of(
                new Category("System prompt", 3_000, ContextColor.PROMPT_BORDER),
                new Category("Messages", 47_000, ContextColor.PURPLE),
                new Category(ContextData.AUTOCOMPACT_BUFFER, 13_000, ContextColor.INACTIVE),
                new Category(ContextData.FREE_SPACE, 137_000, ContextColor.PROMPT_BORDER)),
            50_000, 200_000, 25, "claude-sonnet-4-6",
            List.of(new MemoryFileEntry("/p/CLAUDE.md", "Project", 1_200)),
            List.of(new McpToolEntry("mcp__gh__issue", "gh", 800)),
            List.of(new AgentEntry("reviewer", "User", 40)),
            null,
            new SkillInfo(2, 30, List.of(new SkillEntry("deploy", "Project", 30))),
            167_000L, "model-default", true,
            new ContextData.MessageBreakdown(50_000, 100, 200, 300, 400,
                List.of(new ContextData.ToolIo("Read", 40, 60))),
            new Usage(10, 20, 30, 40));

        ObjectNode payload = DefaultSdkControlRuntime.contextPayload(data);

        assertEquals(200_000, payload.path("rawMaxTokens").asLong());
        assertEquals("promptBorder", payload.path("categories").get(0).path("color").asText());
        assertEquals(10, payload.path("gridRows").size());
        payload.path("gridRows").forEach(row -> assertEquals(10, row.size()));
        assertEquals("gh", payload.path("mcpTools").get(0).path("serverName").asText());
        assertEquals(2, payload.path("skills").path("totalSkills").asInt());
        assertEquals(1, payload.path("skills").path("includedSkills").asInt());
        assertEquals("projectSettings",
            payload.path("skills").path("skillFrontmatter").get(0).path("source").asText());
        assertEquals("model-default", payload.path("autocompactSource").asText());
        assertEquals(0, payload.path("messageBreakdown").path("attachmentTokens").asInt());
        assertEquals(0, payload.path("messageBreakdown").path("redirectedContextTokens").asInt());
        assertEquals(0, payload.path("messageBreakdown").path("unattributedTokens").asInt());
        assertTrue(payload.path("messageBreakdown").path("attachmentsByType").isArray());
        assertEquals(30, payload.path("apiUsage").path("cache_creation_input_tokens").asInt());
    }

    @Test
    void mcpStatusIsStableAndSorted() {
        DefaultQuerySession engine = testEngine(tempDir);
        DefaultSdkControlRuntime runtime = new DefaultSdkControlRuntime(
            engine, tempDir.toString(), null,
            () -> Map.of("zeta", "pending", "alpha", "connected"), _ -> false);

        assertEquals(List.of("alpha", "zeta"),
            runtime.mcpStatus().stream().map(SdkControlRuntime.McpServerStatus::name).toList());
    }

    @Test
    void rewindReportsDisabledBackendWithOriginalControlErrorText() {
        DefaultQuerySession engine = testEngine(tempDir);

        var result = runtime(engine, null).rewindFiles("missing", false);

        assertFalse(result.canRewind());
        assertEquals("File rewinding is not enabled.", result.error());
        assertEquals(List.of(), result.filesChanged());
    }

    @Test
    void stopTaskIsIdempotentForAnUnknownTaskLike197ControlProtocol() {
        DefaultQuerySession engine = testEngine(tempDir);

        assertDoesNotThrow(() -> runtime(engine, null)
            .stopTask("missing-sdk-control-task"));
    }

    @Test
    void cancelAsyncMessageOnlyRemovesMatchingPendingSdkInput() {
        var queue = new LinkedBlockingQueue<CliHeadlessOutput.SdkUserInput>();
        var first = new CliHeadlessOutput.SdkUserInput(null, "first", null);
        var second = new CliHeadlessOutput.SdkUserInput(null, "second", null);
        queue.add(first);
        queue.add(second);

        assertTrue(CliHeadlessOutput.cancelQueuedSdkMessage(queue, "second"));
        assertEquals(List.of(first), List.copyOf(queue));
        assertFalse(CliHeadlessOutput.cancelQueuedSdkMessage(queue, "already-dequeued"));
    }

    @Test
    void flagSettingsUpdateLiveModelAndAppearInGetSettings() {
        DefaultQuerySession engine = testEngine(tempDir);
        DefaultSdkControlRuntime runtime = runtime(engine, null);
        ObjectNode flags = JsonUtils.getMapper().createObjectNode();
        flags.put("model", "claude-opus-4-1");
        flags.put("effortLevel", "high");
        try {
            runtime.applyFlagSettings(flags);

            assertEquals("claude-opus-4-1", engine.getConfig().model());
            assertEquals("high", engine.getConfig().effortValue());
            ObjectNode settings = (ObjectNode) runtime.settings();
            assertEquals("claude-opus-4-1", settings.path("effective").path("model").asText());
            assertFalse(settings.path("applied").path("ultracode").asBoolean(true));
            assertTrue(StreamSupport.stream(
                settings.path("sources").spliterator(), false)
                .anyMatch(source -> Strings.CS.equals("flagSettings", source.path("source").asText())));
        } finally {
            SettingsSources.clearFlagSettings();
        }
    }

    @Test
    void clearingFlagEffortPreservesTheLiveSessionEffort() {
        DefaultQuerySession engine = testEngine(tempDir);
        engine.getConfig().setEffortValue("medium");
        DefaultSdkControlRuntime runtime = runtime(engine, null);
        ObjectNode flags = JsonUtils.getMapper().createObjectNode();
        flags.put("effortLevel", "high");
        try {
            runtime.applyFlagSettings(flags);
            assertEquals("high", engine.getConfig().effortValue());

            flags.putNull("effortLevel");
            runtime.applyFlagSettings(flags);

            assertEquals("high", engine.getConfig().effortValue(),
                "clearing the flag tier must not wipe the session effort");
        } finally {
            SettingsSources.clearFlagSettings();
        }
    }

    @Test
    void sideQuestionUsesTheSharedBtwWrapperAndEnvironmentUpdatesReachChildren() {
        DefaultQuerySession engine = testEngine(tempDir);
        AtomicReference<String> wrapped = new AtomicReference<>();
        DefaultSdkControlRuntime runtime = new DefaultSdkControlRuntime(
            engine, tempDir.toString(), null, Map::of, _ -> false,
            null, question -> { wrapped.set(question); return "answer"; },
            null, null, null, null, null);

        assertEquals("answer", runtime.sideQuestion("What changed?").join());
        assertTrue(Strings.CS.contains(wrapped.get(), "What changed?"));
        ObjectNode variables = JsonUtils.getMapper().createObjectNode();
        variables.put("SDK_CONTROL_TEST_TOKEN", "fresh");
        runtime.updateEnvironmentVariables(variables);
        assertEquals("fresh", SubprocessEnvironment.get(
            "SDK_CONTROL_TEST_TOKEN"));
        Map<String, String> child = new HashMap<>();
        SubprocessEnvironment.applyTo(child);
        assertEquals("fresh", child.get("SDK_CONTROL_TEST_TOKEN"));
    }

    @Test
    void sdkInitializeHooksInstallAnIndependentCallbackChannel() {
        DefaultQuerySession engine = testEngine(tempDir);
        HookEngine hooks = new HookEngine(HooksSettings.EMPTY, tempDir.toString());
        SdkControlBroker broker = new SdkControlBroker(new PrintWriter(new StringWriter()),
            engine, new PermissionGate(), tempDir.toString());
        DefaultSdkControlRuntime runtime = new DefaultSdkControlRuntime(
            engine, tempDir.toString(), null, Map::of, _ -> false,
            null, null, null, null, null, hooks, broker);
        ObjectNode config = JsonUtils.getMapper().createObjectNode();
        ObjectNode matcher = config.putArray("PreToolUse").addObject();
        matcher.put("matcher", "Bash");
        matcher.putArray("hookCallbackIds").add("callback-1");

        runtime.configureHooks(config);

        assertEquals(1, hooks.currentSdkHooks().get(HookEvent.PRE_TOOL_USE).size());
        assertEquals("Bash", hooks.currentSdkHooks().get(HookEvent.PRE_TOOL_USE)
            .getFirst().matcher().orElseThrow());
    }

    @Test
    void reloadPluginsPreservesCommandAliasesAndUses197RefreshAgentOrder() {
        DefaultQuerySession engine = testEngine(tempDir);
        PluginDirectories directories = new PluginDirectories(tempDir.resolve("plugins"));
        CliPluginRuntime pluginRuntime = new CliPluginRuntime(
            new PluginRuntimeLoader(directories,
                new PluginSettingsStore(
                    tempDir.resolve("settings/user.json"),
                    tempDir.resolve("settings/project.json"),
                    tempDir.resolve("settings/local.json"),
                    tempDir.resolve("settings/policy.json")),
                new InstalledPluginsStore(directories.installedPluginsFile()),
                () -> "session-1"),
            tempDir.toString(), null, null, null, null);
        pluginRuntime.loadAndInject();

        var metadata = new StdoutMessageWriter.SdkOutputMetadata(
            "session-1", tempDir.toString(), "claude-sonnet-4-6", "default",
            List.of(), List.of(), List.of("loop"), "ANTHROPIC_API_KEY", "2.1.197",
            "default", List.of("claude", "Explore", "general-purpose", "Plan",
                "statusline-setup"), List.of("loop"), List.of());
        var catalog = new SdkInboundControlHandler.ControlCatalog(
            List.of(new SdkInboundControlHandler.CommandInfo(
                "loop", "Recurring prompt", "[interval] <prompt>",
                List.of("proactive"))),
            List.of(
                new SdkInboundControlHandler.AgentInfo("claude", "Claude", null),
                new SdkInboundControlHandler.AgentInfo("Explore", "Explore", "haiku"),
                new SdkInboundControlHandler.AgentInfo(
                    "general-purpose", "General", null),
                new SdkInboundControlHandler.AgentInfo("Plan", "Plan", null),
                new SdkInboundControlHandler.AgentInfo(
                    "statusline-setup", "Status", "sonnet")),
            List.of());
        DefaultSdkControlRuntime runtime = new DefaultSdkControlRuntime(
            engine, tempDir.toString(), null, Map::of, _ -> false,
            null, null, pluginRuntime, null,
            () -> new StdoutMessageWriter.SdkOutputState(metadata, catalog), null, null);

        ObjectNode response = (ObjectNode) runtime.reloadPlugins();

        assertEquals("proactive",
            response.path("commands").get(0).path("aliases").get(0).asText());
        assertEquals(List.of("general-purpose", "statusline-setup", "claude", "Explore", "Plan"),
            StreamSupport.stream(
                response.path("agents").spliterator(), false)
                .map(agent -> agent.path("name").asText()).toList());
    }

    private DefaultSdkControlRuntime runtime(DefaultQuerySession engine, ContextData contextData) {
        return new DefaultSdkControlRuntime(engine, tempDir.toString(),
            contextData == null ? null : () -> contextData, Map::of, _ -> false);
    }

    private static DefaultQuerySession testEngine(Path cwd) {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return List.<StreamingEvent>of().iterator();
                }
                @Override public String getModel() { return "claude-sonnet-4-6"; }
            })
            .model("claude-sonnet-4-6")
            .workingDirectory(cwd.toString())
            .build());
    }
}
