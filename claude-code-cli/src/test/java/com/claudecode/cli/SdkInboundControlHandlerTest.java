package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.services.config.SettingsSources;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SdkInboundControlHandlerTest {

    @Test
    void initializeRegistersSupportedDialogKinds() throws Exception {
        AtomicReference<JsonNode> configured = new AtomicReference<>();
        SdkControlRuntime runtime = new EmptyRuntime() {
            @Override public void configureSupportedDialogKinds(JsonNode dialogKinds) {
                configured.set(dialogKinds.deepCopy());
            }
        };
        StringWriter buffer = new StringWriter();
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), new PermissionGate(), metadata(),
            SdkInboundControlHandler.ControlCatalog.empty(), runtime);

        handler.handle(request("initialize-dialogs", "{\"subtype\":\"initialize\","
            + "\"supportedDialogKinds\":[\"refusal_fallback_prompt\"]}"));

        assertEquals(List.of("refusal_fallback_prompt"),
            StreamSupport.stream(configured.get().spliterator(), false)
                .map(JsonNode::asText)
                .toList());
        ObjectNode response = outputLines(buffer).getFirst();
        assertControlSuccess(response, "initialize-dialogs");
        assertEquals("off", response.path("response").path("response")
            .path("fast_mode_state").asText());
    }

    @Test
    void initializeRegistersSdkMcpNamesWithoutStartingTheBridgeInline() throws Exception {
        AtomicReference<List<String>> configured = new AtomicReference<>();
        SdkControlRuntime runtime = new EmptyRuntime() {
            @Override public void configureSdkMcpServers(
                    JsonNode serverNames) {
                configured.set(StreamSupport.stream(
                    serverNames.spliterator(), false)
                    .map(JsonNode::asText)
                    .toList());
            }
        };
        StringWriter buffer = new StringWriter();
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), new PermissionGate(), metadata(),
            SdkInboundControlHandler.ControlCatalog.empty(), runtime);

        handler.handle(request("initialize-sdk", "{\"subtype\":\"initialize\","
            + "\"sdkMcpServers\":[\"sdk-wire\",\"second\"]}"));

        assertEquals(List.of("sdk-wire", "second"), configured.get());
        List<ObjectNode> lines = outputLines(buffer);
        assertEquals(1, lines.size());
        assertControlSuccess(lines.getFirst(), "initialize-sdk");
    }

    @Test
    void initializeFiltersModelCatalogueByAvailableModelsButKeepsDefault() throws Exception {
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"availableModels\":[\"sonnet\"]}"));
        try {
            StringWriter buffer = new StringWriter();
            var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
                testEngine(), new PermissionGate(), metadata());
            handler.handle(request("initialize-allowlist", "{\"subtype\":\"initialize\"}"));

            List<String> values = StreamSupport.stream(
                    body(outputLines(buffer).getFirst()).path("models").spliterator(), false)
                .map(node -> node.path("value").asText())
                .toList();
            assertTrue(values.contains("default"));
            assertTrue(values.contains("sonnet"));
            assertTrue(values.contains("sonnet[1m]"));
            assertFalse(values.contains("opus[1m]"));
            assertFalse(values.contains("haiku"));
        } finally {
            SettingsSources.clearFlagSettings();
        }
    }

    @Test
    void initializeOmitsUnusableBuiltInModelsWhenDirectAnthropicAuthIsMissing()
            throws Exception {
        StringWriter buffer = new StringWriter();
        var customMetadata = new StdoutMessageWriter.SdkOutputMetadata(
            "session-1", "/tmp/project", "gateway-model", "default",
            List.of(), List.of(), List.of(), "none", "2.1.197",
            "default", List.of(), List.of(), List.of());
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), new PermissionGate(), customMetadata,
            SdkInboundControlHandler.ControlCatalog.empty(), false);

        handler.handle(request("initialize-no-auth", "{\"subtype\":\"initialize\"}"));

        List<String> values = StreamSupport.stream(
                body(outputLines(buffer).getFirst()).path("models").spliterator(), false)
            .map(node -> node.path("value").asText())
            .toList();
        assertEquals(List.of("gateway-model"), values);
    }

    @Test
    void initializeCustomOnlyCatalogueContainsEveryConfiguredCustomModel() throws Exception {
        StringWriter buffer = new StringWriter();
        var customMetadata = new StdoutMessageWriter.SdkOutputMetadata(
            "session-1", "/tmp/project", "alpha", "default",
            List.of(), List.of(), List.of(), "none", "2.1.197",
            "default", List.of(), List.of(), List.of());
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), new PermissionGate(), customMetadata,
            SdkInboundControlHandler.ControlCatalog.empty(), false,
            List.of("alpha", "zeta"));

        handler.handle(request("initialize-custom-only", "{\"subtype\":\"initialize\"}"));

        assertEquals(List.of("alpha", "zeta"), StreamSupport.stream(
                body(outputLines(buffer).getFirst()).path("models").spliterator(), false)
            .map(node -> node.path("value").asText()).toList());
    }

    @Test
    void initializeWithoutCredentialsOrCustomModelsHasNoModelRows() throws Exception {
        StringWriter buffer = new StringWriter();
        var builtInMetadata = new StdoutMessageWriter.SdkOutputMetadata(
            "session-1", "/tmp/project", ModelCatalog.LATEST_SONNET, "default",
            List.of(), List.of(), List.of(), "none", "2.1.197",
            "default", List.of(), List.of(), List.of());
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), new PermissionGate(), builtInMetadata,
            SdkInboundControlHandler.ControlCatalog.empty(), false, List.of());

        handler.handle(request("initialize-empty-models", "{\"subtype\":\"initialize\"}"));

        assertTrue(body(outputLines(buffer).getFirst()).path("models").isEmpty());
    }

    @Test
    void reloadPluginsEmitsCommandsChangedBeforeItsControlResponse() throws Exception {
        StringWriter buffer = new StringWriter();
        SdkControlRuntime runtime = new EmptyRuntime() {
            @Override public JsonNode reloadPlugins() {
                ObjectNode response = JsonUtils.getMapper().createObjectNode();
                ObjectNode command = response.putArray("commands").addObject();
                command.put("name", "loop");
                command.put("description", "Recurring prompt");
                command.put("argumentHint", "[interval] <prompt>");
                command.putArray("aliases").add("proactive");
                response.putArray("agents");
                response.putArray("plugins");
                response.putArray("mcpServers");
                response.put("error_count", 0);
                return response;
            }
        };
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), new PermissionGate(), metadata(),
            SdkInboundControlHandler.ControlCatalog.empty(), runtime);

        handler.handle(request("reload-1", "{\"subtype\":\"reload_plugins\"}"));

        List<ObjectNode> lines = outputLines(buffer);
        assertEquals(2, lines.size());
        assertEquals("system", lines.getFirst().path("type").asText());
        assertEquals("commands_changed", lines.getFirst().path("subtype").asText());
        assertEquals("session-1", lines.getFirst().path("session_id").asText());
        assertTrue(lines.getFirst().path("uuid").isTextual());
        assertEquals("proactive",
            lines.getFirst().path("commands").get(0).path("aliases").get(0).asText());
        assertControlSuccess(lines.get(1), "reload-1");
        assertEquals("loop", body(lines.get(1)).path("commands").get(0).path("name").asText());
    }

    @Test
    void setModelInjects197BreadcrumbsReplaysStdoutAndRefreshesModelTokenDefault()
            throws Exception {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return List.<StreamingEvent>of().iterator();
                }
                @Override public String getModel() { return "claude-sonnet-4-6"; }
            })
            .model("claude-sonnet-4-6")
            .maxTokens(32_000)
            .maxTokensResolver(model -> Strings.CS.contains(model, "opus-4-6") ? 64_000 : 32_000)
            .workingDirectory("/tmp/project")
            .build());
        StringWriter buffer = new StringWriter();
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            engine, new PermissionGate(), metadata());

        handler.handle(request("model-1",
            "{\"subtype\":\"set_model\",\"model\":\"claude-opus-4-6\"}"));

        assertEquals("claude-opus-4-6", engine.getConfig().model());
        assertEquals(64_000, engine.getConfig().maxTokens());
        List<UserMessage> breadcrumbs = engine.getMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .toList();
        assertEquals(3, breadcrumbs.size());
        assertEquals("""
            <command-name>/model</command-name>
                        <command-message>model</command-message>
                        <command-args>claude-opus-4-6</command-args>""",
            breadcrumbs.get(1).message().text());

        List<ObjectNode> lines = outputLines(buffer);
        assertEquals(2, lines.size());
        assertEquals("user", lines.getFirst().path("type").asText());
        assertEquals("<local-command-stdout>Set model to claude-opus-4-6</local-command-stdout>",
            lines.getFirst().path("message").path("content").asText());
        assertTrue(lines.getFirst().path("isReplay").asBoolean());
        assertControlSuccess(lines.get(1), "model-1");
    }

    @Test
    void modelBreadcrumbsPersistOnlyAfterNextPromptIdentityStarts() throws Exception {
        DefaultQuerySession engine = testEngine();
        List<String> writes = new ArrayList<>();
        engine.setTranscriptSink(new TranscriptSink() {
            @Override public void record(String sessionId, Message message) {
                writes.add("message:" + ((UserMessage) message).message().text());
            }
        });
        var handler = new SdkInboundControlHandler(new PrintWriter(new StringWriter(), true),
            engine, new PermissionGate(), metadata());

        handler.handle(request("model-1",
            "{\"subtype\":\"set_model\",\"model\":\"claude-opus-4-6\"}"));

        assertTrue(writes.isEmpty());
        assertEquals(3, engine.getMessages().size());
        handler.flushPendingTranscriptBreadcrumbs();
        assertEquals(3, writes.size());
    }

    @Test
    void forwardCompatibleControlRequestsDispatchAndPreserveResponseShapes() throws Exception {
        DefaultQuerySession engine = testEngine();
        StringWriter buffer = new StringWriter();
        AtomicReference<String> cancelled = new AtomicReference<>();
        AtomicReference<String> seeded = new AtomicReference<>();
        AtomicReference<String> stopped = new AtomicReference<>();
        ObjectNode context = JsonUtils.getMapper().createObjectNode()
            .put("totalTokens", 42).put("maxTokens", 200_000);
        ObjectNode settings = JsonUtils.getMapper().createObjectNode();
        settings.putObject("effective").put("model", "claude-sonnet-4-6");
        settings.putArray("sources");

        SdkControlRuntime runtime = new SdkControlRuntime() {
            @Override public List<McpServerStatus> mcpStatus() {
                return List.of(new McpServerStatus("github", "connected"));
            }
            @Override public JsonNode contextUsage() { return context; }
            @Override public RewindFilesResult rewindFiles(String id, boolean dryRun) {
                return new RewindFilesResult(true, null, List.of("/tmp/project/A.java"), 3, 1);
            }
            @Override public boolean cancelAsyncMessage(String uuid) {
                cancelled.set(uuid);
                return true;
            }
            @Override public void seedReadState(String path, long mtime) {
                seeded.set(path + ":" + mtime);
            }
            @Override public void stopTask(String taskId) { stopped.set(taskId); }
            @Override public JsonNode settings() { return settings; }
        };
        var handler = new SdkInboundControlHandler(
            new PrintWriter(buffer, true), engine, new PermissionGate(), metadata(),
            SdkInboundControlHandler.ControlCatalog.empty(), runtime);

        handler.handle(request("mcp", "{\"subtype\":\"mcp_status\"}"));
        handler.handle(request("context", "{\"subtype\":\"get_context_usage\"}"));
        handler.handle(request("rewind", "{\"subtype\":\"rewind_files\",\"user_message_id\":\"u-1\",\"dry_run\":true}"));
        handler.handle(request("cancel", "{\"subtype\":\"cancel_async_message\",\"message_uuid\":\"msg-1\"}"));
        handler.handle(request("seed", "{\"subtype\":\"seed_read_state\",\"path\":\"A.java\",\"mtime\":123}"));
        handler.handle(request("stop", "{\"subtype\":\"stop_task\",\"task_id\":\"task-1\"}"));
        handler.handle(request("settings", "{\"subtype\":\"get_settings\"}"));

        List<ObjectNode> lines = outputLines(buffer);
        assertEquals(7, lines.size());
        assertEquals("github", body(lines.getFirst()).path("mcpServers").get(0).path("name").asText());
        assertEquals(42, body(lines.get(1)).path("totalTokens").asInt());
        assertTrue(body(lines.get(2)).path("canRewind").asBoolean());
        assertEquals(3, body(lines.get(2)).path("insertions").asInt());
        assertTrue(body(lines.get(3)).path("cancelled").asBoolean());
        assertTrue(body(lines.get(4)).isMissingNode() || body(lines.get(4)).isEmpty());
        assertTrue(body(lines.get(5)).isMissingNode() || body(lines.get(5)).isEmpty());
        assertEquals("claude-sonnet-4-6", body(lines.get(6)).path("effective").path("model").asText());
        assertEquals("msg-1", cancelled.get());
        assertEquals("A.java:123", seeded.get());
        assertEquals("task-1", stopped.get());
    }

    @Test
    void mcpStatusSerializesTheFull197ConnectedShape() throws Exception {
        ObjectNode serverInfo = JsonUtils.getMapper().createObjectNode()
            .put("name", "wire-fake-mcp").put("version", "1.0.0");
        ObjectNode config = JsonUtils.getMapper().createObjectNode()
            .put("type", "stdio").put("command", "/usr/bin/python3");
        config.putArray("args").add("fake.py");
        var tools = JsonUtils.getMapper().createArrayNode();
        tools.addObject().put("name", "echo_marker")
            .putObject("annotations").put("readOnly", true);
        SdkControlRuntime runtime = new EmptyRuntime() {
            @Override public List<McpServerStatus> mcpStatus() {
                return List.of(new McpServerStatus(
                    "wire", "connected", serverInfo, null, config,
                    "project", tools, null));
            }
        };
        StringWriter buffer = new StringWriter();
        var handler = new SdkInboundControlHandler(
            new PrintWriter(buffer, true), testEngine(), new PermissionGate(), metadata(),
            SdkInboundControlHandler.ControlCatalog.empty(), runtime);

        handler.handle(request("status", "{\"subtype\":\"mcp_status\"}"));

        ObjectNode server = (ObjectNode) body(outputLines(buffer).getFirst())
            .path("mcpServers").get(0);
        assertEquals(serverInfo, server.path("serverInfo"));
        assertEquals(config, server.path("config"));
        assertEquals("project", server.path("scope").asText());
        assertEquals(tools, server.path("tools"));
        assertFalse(server.has("error"));
        assertFalse(server.has("capabilities"));
    }

    @Test
    void rewindFailureUsesControlErrorUnlessDryRun() throws Exception {
        SdkControlRuntime runtime = runtimeWithRewind(
            new SdkControlRuntime.RewindFilesResult(
                false, "snapshot missing", List.of(), null, null));
        StringWriter buffer = new StringWriter();
        var handler = new SdkInboundControlHandler(
            new PrintWriter(buffer, true), testEngine(), new PermissionGate(), metadata(),
            SdkInboundControlHandler.ControlCatalog.empty(), runtime);

        handler.handle(request("real", "{\"subtype\":\"rewind_files\",\"user_message_id\":\"missing\"}"));
        handler.handle(request("dry", "{\"subtype\":\"rewind_files\",\"user_message_id\":\"missing\",\"dry_run\":true}"));

        List<ObjectNode> lines = outputLines(buffer);
        assertEquals("error", lines.getFirst().path("response").path("subtype").asText());
        assertEquals("snapshot missing", lines.getFirst().path("response").path("error").asText());
        assertControlSuccess(lines.get(1), "dry");
        assertFalse(body(lines.get(1)).path("canRewind").asBoolean());
        assertFalse(body(lines.get(1)).has("filesChanged"));
    }

    @Test
    void asynchronousTitleAndSideQuestionControlsDoNotBlockDispatchAndKeepResponseShapes()
            throws Exception {
        StringWriter buffer = new StringWriter();
        CompletableFuture<String> title = new CompletableFuture<>();
        CompletableFuture<String> side = new CompletableFuture<>();
        SdkControlRuntime runtime = new SdkControlRuntime() {
            @Override public List<McpServerStatus> mcpStatus() { return List.of(); }
            @Override public JsonNode contextUsage() { return null; }
            @Override public RewindFilesResult rewindFiles(String id, boolean dryRun) { return null; }
            @Override public boolean cancelAsyncMessage(String uuid) { return false; }
            @Override public void seedReadState(String path, long mtime) {}
            @Override public void stopTask(String taskId) {}
            @Override public JsonNode settings() { return null; }
            @Override public CompletableFuture<String> generateSessionTitle(
                    String description, boolean persist) {
                assertEquals("Fix the SDK", description);
                assertTrue(persist);
                return title;
            }
            @Override public CompletableFuture<String> sideQuestion(String question) {
                assertEquals("What changed?", question);
                return side;
            }
        };
        var handler = new SdkInboundControlHandler(
            new PrintWriter(buffer, true), testEngine(), new PermissionGate(), metadata(),
            SdkInboundControlHandler.ControlCatalog.empty(), runtime);

        handler.handle(request("title", "{\"subtype\":\"generate_session_title\",\"description\":\"Fix the SDK\",\"persist\":true}"));
        handler.handle(request("side", "{\"subtype\":\"side_question\",\"question\":\"What changed?\"}"));
        assertEquals("", buffer.toString());

        side.complete("The control protocol changed.");
        title.complete("Fix SDK controls");
        handler.awaitPendingResponses(2_000);

        List<ObjectNode> lines = outputLines(buffer);
        assertEquals(2, lines.size());
        assertEquals("The control protocol changed.", body(lines.getFirst()).path("response").asText());
        assertFalse(body(lines.getFirst()).path("synthetic").asBoolean(true));
        assertEquals("Fix SDK controls", body(lines.get(1)).path("title").asText());
    }

    @Test
    void extended197ControlsDispatchToLiveRuntimeAndPreserveEnvelopeShapes() throws Exception {
        StringWriter buffer = new StringWriter();
        List<String> calls = new ArrayList<>();
        SdkControlRuntime runtime = new EmptyRuntime() {
            @Override public JsonNode setMcpServers(
                JsonNode servers) {
                calls.add("set:" + servers.fieldNames().next());
                ObjectNode response = JsonUtils.getMapper().createObjectNode();
                response.putArray("added").add("sdk");
                return response;
            }
            @Override public JsonNode reloadPlugins() {
                calls.add("reload");
                return JsonUtils.getMapper().createObjectNode().put("error_count", 0);
            }
            @Override public void reconnectMcp(String name) { calls.add("reconnect:" + name); }
            @Override public void deliverMcpMessage(String name,
                    JsonNode message) {
                calls.add("message:" + name + ":" + message.path("method").asText());
            }
            @Override public void toggleMcp(String name, boolean enabled) {
                calls.add("toggle:" + name + ":" + enabled);
            }
            @Override public void applyFlagSettings(JsonNode settings) {
                calls.add("flags:" + settings.path("model").asText());
            }
            @Override public JsonNode authenticateMcp(String name) {
                calls.add("auth:" + name);
                return JsonUtils.getMapper().createObjectNode().put("authUrl", "https://auth");
            }
            @Override public void submitMcpOAuthCallback(String name, String url) {
                calls.add("callback:" + name + ":" + url);
            }
            @Override public void clearMcpAuth(String name) { calls.add("clear:" + name); }
        };
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), new PermissionGate(), metadata(),
            SdkInboundControlHandler.ControlCatalog.empty(), runtime);

        handler.handle(request("set", "{\"subtype\":\"mcp_set_servers\",\"servers\":{\"sdk\":{\"command\":\"x\"}}}"));
        handler.handle(request("reload", "{\"subtype\":\"reload_plugins\"}"));
        handler.handle(request("message", "{\"subtype\":\"mcp_message\",\"server_name\":\"sdk\",\"message\":{\"jsonrpc\":\"2.0\",\"method\":\"notice\"}}"));
        handler.handle(request("reconnect", "{\"subtype\":\"mcp_reconnect\",\"serverName\":\"sdk\"}"));
        handler.handle(request("toggle", "{\"subtype\":\"mcp_toggle\",\"serverName\":\"sdk\",\"enabled\":false}"));
        handler.handle(request("flags", "{\"subtype\":\"apply_flag_settings\",\"settings\":{\"model\":\"sonnet\"}}"));
        handler.handle(request("auth", "{\"subtype\":\"mcp_authenticate\",\"serverName\":\"sdk\"}"));
        handler.handle(request("callback", "{\"subtype\":\"mcp_oauth_callback_url\",\"serverName\":\"sdk\",\"callbackUrl\":\"http://127.0.0.1/callback?code=x\"}"));
        handler.handle(request("clear", "{\"subtype\":\"mcp_clear_auth\",\"serverName\":\"sdk\"}"));
        handler.handle(request("channel", "{\"subtype\":\"channel_enable\",\"serverName\":\"sdk\"}"));

        assertEquals(List.of("set:sdk", "reload", "message:sdk:notice", "reconnect:sdk",
            "toggle:sdk:false", "flags:sonnet", "auth:sdk",
            "callback:sdk:http://127.0.0.1/callback?code=x", "clear:sdk"), calls);
        List<ObjectNode> lines = outputLines(buffer);
        assertEquals(10, lines.size());
        lines.subList(0, 9).forEach(line ->
            assertEquals("success", line.path("response").path("subtype").asText()));
        assertEquals("error", lines.get(9).path("response").path("subtype").asText());
        assertEquals("channels feature not available in this build",
            lines.get(9).path("response").path("error").asText());
        assertEquals("sdk", body(lines.getFirst()).path("added").get(0).asText());
        assertEquals(0, body(lines.get(1)).path("error_count").asInt());
    }

    @Test
    void backgroundTasksWithoutToolUseIdUsesCtrlBAllTasksSemantics() throws Exception {
        AtomicReference<String> selectedToolUseId = new AtomicReference<>("unset");
        SdkControlRuntime runtime = new EmptyRuntime() {
            @Override public boolean backgroundTasks(String toolUseId) {
                selectedToolUseId.set(toolUseId);
                return true;
            }
        };
        StringWriter buffer = new StringWriter();
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), new PermissionGate(), metadata(),
            SdkInboundControlHandler.ControlCatalog.empty(), runtime);

        handler.handle(request("background", "{\"subtype\":\"background_tasks\"}"));

        assertNull(selectedToolUseId.get());
        ObjectNode response = outputLines(buffer).getFirst();
        assertControlSuccess(response, "background");
        assertTrue(body(response).path("backgrounded").asBoolean());
    }

    private abstract static class EmptyRuntime implements SdkControlRuntime {
        @Override public List<McpServerStatus> mcpStatus() { return List.of(); }
        @Override public JsonNode contextUsage() { return null; }
        @Override public RewindFilesResult rewindFiles(String id, boolean dryRun) { return null; }
        @Override public boolean cancelAsyncMessage(String uuid) { return false; }
        @Override public void seedReadState(String path, long mtime) {}
        @Override public void stopTask(String taskId) {}
        @Override public JsonNode settings() { return null; }
    }

    @Test
    void basicControlSequenceMatches197EnvelopeOrderAndMutatesSessionState() throws Exception {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return List.<StreamingEvent>of().iterator();
                }

                @Override
                public String getModel() {
                    return "glm-5.2";
                }
            })
            .model("glm-5.2")
            .workingDirectory("/tmp/project")
            .build());
        PermissionGate gate = new PermissionGate();
        StringWriter buffer = new StringWriter();
        var metadata = new StdoutMessageWriter.SdkOutputMetadata(
            "session-1", "/tmp/project", "glm-5.2", "default",
            List.of("Task", "Read"), List.of(), List.of("verify", "compact"),
            "ANTHROPIC_API_KEY", "2.1.197", "default",
            List.of("claude", "Explore"), List.of("verify"), List.of());
        var catalog = new SdkInboundControlHandler.ControlCatalog(
            List.of(new SdkInboundControlHandler.CommandInfo(
                "verify", "Verify changes", "[target]", List.of("check"))),
            List.of(new SdkInboundControlHandler.AgentInfo(
                "Explore", "Explore the codebase", "haiku")),
            List.of("default", "Proactive", "Explanatory", "Learning"));
        SdkInboundControlHandler handler = new SdkInboundControlHandler(
            new PrintWriter(buffer, true), engine, gate, metadata, catalog);

        assertEquals(SdkInboundControlHandler.Action.CONTINUE,
            handler.handle(request("init-1", "{\"subtype\":\"initialize\"}")));
        assertEquals(SdkInboundControlHandler.Action.CONTINUE,
            handler.handle(request("mode-1", "{\"subtype\":\"set_permission_mode\",\"mode\":\"plan\"}")));
        assertEquals(SdkInboundControlHandler.Action.CONTINUE,
            handler.handle(request("model-1", "{\"subtype\":\"set_model\",\"model\":\"claude-sonnet-4-6\"}")));
        assertEquals(SdkInboundControlHandler.Action.CONTINUE,
            handler.handle(request("thinking-budget", "{\"subtype\":\"set_max_thinking_tokens\",\"max_thinking_tokens\":5000}")));
        assertTrue(engine.getConfig().isThinkingEnabled());
        assertEquals(5000, engine.getConfig().thinkingBudgetTokens());
        assertEquals(SdkInboundControlHandler.Action.CONTINUE,
            handler.handle(request("thinking-1", "{\"subtype\":\"set_max_thinking_tokens\",\"max_thinking_tokens\":0}")));
        assertEquals(SdkInboundControlHandler.Action.END_SESSION,
            handler.handle(request("end-1", "{\"subtype\":\"end_session\",\"reason\":\"wire-test\"}")));

        List<ObjectNode> lines = Arrays.stream(buffer.toString().strip().split("\\R"))
            .map(line -> {
                try {
                    return (ObjectNode) JsonUtils.getMapper().readTree(line);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList();

        assertEquals(8, lines.size());
        assertControlSuccess(lines.getFirst(), "init-1");
        ObjectNode init = (ObjectNode) lines.getFirst().path("response").path("response");
        assertTrue(init.path("commands").isArray());
        assertEquals("Verify changes", init.path("commands").get(0).path("description").asText());
        assertEquals("[target]", init.path("commands").get(0).path("argumentHint").asText());
        assertEquals("check", init.path("commands").get(0).path("aliases").get(0).asText());
        assertTrue(init.path("agents").isArray());
        assertEquals("Explore the codebase", init.path("agents").get(0).path("description").asText());
        assertEquals("haiku", init.path("agents").get(0).path("model").asText());
        assertEquals(4, init.path("available_output_styles").size());
        assertTrue(init.path("models").isArray());
        assertEquals(List.of("default", "opus[1m]", "sonnet", "sonnet[1m]", "haiku", "glm-5.2"),
            StreamSupport.stream(init.path("models").spliterator(), false)
                .map(model -> model.path("value").asText()).toList());
        assertEquals("claude-opus-5", init.path("models").get(0).path("resolvedModel").asText());
        assertEquals("claude-opus-5", init.path("models").get(1).path("resolvedModel").asText());
        assertTrue(Strings.CS.contains(
            init.path("models").get(0).path("description").asText(), "Opus 5"));
        assertTrue(init.path("account").isObject());
        assertEquals("none", init.path("account").path("tokenSource").asText());
        assertTrue(init.path("pid").isIntegralNumber());

        assertControlSuccess(lines.get(1), "mode-1");
        assertEquals("plan", lines.get(1).path("response").path("response").path("mode").asText());
        assertEquals("system", lines.get(2).path("type").asText());
        assertEquals("status", lines.get(2).path("subtype").asText());
        assertTrue(lines.get(2).path("status").isNull());
        assertEquals("plan", lines.get(2).path("permissionMode").asText());
        assertEquals("session-1", lines.get(2).path("session_id").asText());

        assertEquals("user", lines.get(3).path("type").asText());
        assertEquals("<local-command-stdout>Set model to claude-sonnet-4-6</local-command-stdout>",
            lines.get(3).path("message").path("content").asText());
        assertTrue(lines.get(3).path("isReplay").asBoolean());
        assertControlSuccess(lines.get(4), "model-1");
        assertControlSuccess(lines.get(5), "thinking-budget");
        assertControlSuccess(lines.get(6), "thinking-1");
        assertControlSuccess(lines.get(7), "end-1");
        assertEquals(PermissionMode.PLAN, gate.currentMode());
        assertEquals("claude-sonnet-4-6", engine.getConfig().model());
        assertFalse(engine.getConfig().isThinkingEnabled());
        assertNull(engine.getConfig().thinkingBudgetTokens());
    }

    @Test
    void setPermissionModeRejectsUnavailableBypass() throws Exception {
        PermissionGate gate = new PermissionGate();
        gate.setBypassPermissionsModeAvailable(false);
        StringWriter buffer = new StringWriter();
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), gate, metadata());

        handler.handle(request("blocked", "{\"subtype\":\"set_permission_mode\","
            + "\"mode\":\"bypassPermissions\"}"));

        List<ObjectNode> lines = outputLines(buffer);
        assertEquals(1, lines.size());
        assertEquals("error", lines.getFirst().path("response").path("subtype").asText());
        assertEquals("blocked", lines.getFirst().path("response").path("request_id").asText());
        assertEquals(PermissionMode.DEFAULT, gate.currentMode());
    }

    @Test
    void setPermissionModeReportsPolicyBeforeMissingLaunchIntent() throws Exception {
        PermissionGate gate = new PermissionGate();
        gate.configureBypassPermissionsMode(false, true);
        StringWriter buffer = new StringWriter();
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), gate, metadata());

        handler.handle(request("policy-blocked", "{\"subtype\":\"set_permission_mode\","
            + "\"mode\":\"bypassPermissions\"}"));

        List<ObjectNode> lines = outputLines(buffer);
        assertEquals(1, lines.size());
        assertEquals("error", lines.getFirst().path("response").path("subtype").asText());
        assertEquals("policy-blocked", lines.getFirst().path("response").path("request_id").asText());
        assertTrue(Strings.CS.contains(lines.getFirst().path("response").path("error").asText(), "disabled by settings or configuration"));
        assertEquals(PermissionMode.DEFAULT, gate.currentMode());
    }

    @Test
    void initializeDescribesAnExplicitSonnet46AsThe197LegacyModelOption()
            throws Exception {
        StringWriter buffer = new StringWriter();
        var explicitModelMetadata = new StdoutMessageWriter.SdkOutputMetadata(
            "session-1", "/tmp/project", "claude-sonnet-4-6", "default",
            List.of(), List.of(), List.of(), "ANTHROPIC_API_KEY", "2.1.197",
            "default", List.of(), List.of(), List.of());
        var handler = new SdkInboundControlHandler(new PrintWriter(buffer, true),
            testEngine(), new PermissionGate(), explicitModelMetadata);

        handler.handle(request("init-legacy", "{\"subtype\":\"initialize\"}"));

        ObjectNode init = (ObjectNode) body(outputLines(buffer).getFirst());
        assertEquals(List.of("default", "opus[1m]", "sonnet", "sonnet[1m]",
                "haiku", "claude-sonnet-4-6"),
            StreamSupport.stream(init.path("models").spliterator(), false)
                .map(model -> model.path("value").asText()).toList());
        ObjectNode legacy = (ObjectNode) init.path("models").get(5);
        assertEquals("claude-sonnet-4-6", legacy.path("resolvedModel").asText());
        assertEquals("Sonnet 4.6", legacy.path("displayName").asText());
        assertEquals("Newer version available · select Sonnet for Sonnet 5",
            legacy.path("description").asText());
        assertEquals(List.of("low", "medium", "high", "max"),
            StreamSupport.stream(
                    legacy.path("supportedEffortLevels").spliterator(), false)
                .map(JsonNode::asText).toList());
        assertTrue(legacy.path("supportsAdaptiveThinking").asBoolean());
        assertTrue(legacy.path("supportsAutoMode").asBoolean());
        assertFalse(legacy.has("supportsFastMode"));
    }

    private static ObjectNode request(String requestId, String requestJson) throws Exception {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("type", "control_request");
        root.put("request_id", requestId);
        root.set("request", JsonUtils.getMapper().readTree(requestJson));
        return root;
    }

    private static DefaultQuerySession testEngine() {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return List.<StreamingEvent>of().iterator();
                }
                @Override public String getModel() { return "glm-5.2"; }
            })
            .model("glm-5.2").workingDirectory("/tmp/project").build());
    }

    private static StdoutMessageWriter.SdkOutputMetadata metadata() {
        return new StdoutMessageWriter.SdkOutputMetadata(
            "session-1", "/tmp/project", "glm-5.2", "default",
            List.of(), List.of(), List.of(), "none", "2.1.197", "default",
            List.of(), List.of(), List.of());
    }

    private static List<ObjectNode> outputLines(StringWriter buffer) {
        return Arrays.stream(buffer.toString().strip().split("\\R"))
            .filter(line -> !StringUtils.isBlank(line))
            .map(line -> {
                try { return (ObjectNode) JsonUtils.getMapper().readTree(line); }
                catch (Exception e) { throw new AssertionError(e); }
            }).toList();
    }

    private static JsonNode body(ObjectNode line) {
        return line.path("response").path("response");
    }

    private static SdkControlRuntime runtimeWithRewind(SdkControlRuntime.RewindFilesResult result) {
        return new SdkControlRuntime() {
            @Override public List<McpServerStatus> mcpStatus() { return List.of(); }
            @Override public JsonNode contextUsage() { return null; }
            @Override public RewindFilesResult rewindFiles(String id, boolean dryRun) { return result; }
            @Override public boolean cancelAsyncMessage(String uuid) { return false; }
            @Override public void seedReadState(String path, long mtime) {}
            @Override public void stopTask(String taskId) {}
            @Override public JsonNode settings() { return null; }
        };
    }

    private static void assertControlSuccess(ObjectNode line, String requestId) {
        assertEquals("control_response", line.path("type").asText());
        assertEquals("success", line.path("response").path("subtype").asText());
        assertEquals(requestId, line.path("response").path("request_id").asText());
    }
}
