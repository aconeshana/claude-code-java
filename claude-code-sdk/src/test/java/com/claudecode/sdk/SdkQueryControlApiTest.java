package com.claudecode.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class SdkQueryControlApiTest {
    @Test
    void streamingPromptIsWrittenOnceAndCannotBeRestarted() throws Exception {
        ControlProcess process = new ControlProcess();
        SDKUserMessage message = new SDKUserMessage(
            JsonUtils.getMapper().getNodeFactory().textNode("streamed"), null, Instant.now());
        QueryOptions options = QueryOptions.builder().processSpawner(_ -> process).build();

        try (ExtendedSdkQuery query = ClaudeAgentSdk.queryExtended(List.of(message), options)) {
            query.initializationResult().join();
            assertEquals("streamed", process.initialInput().get(2, TimeUnit.SECONDS)
                .path("message").path("content").asText());
            assertThrows(CompletionException.class, () -> query.streamInput(List.of()).join());
        }
    }

    @Test
    void closeRejectsPendingControlRequests() throws Exception {
        ControlProcess process = new ControlProcess(null, "interrupt");
        SdkQuery query = ClaudeAgentSdk.query("hello",
            QueryOptions.builder().processSpawner(_ -> process).build());
        query.initializationResult().join();
        CompletableFuture<Void> pending = query.interrupt();

        query.close();

        CompletionException failure = assertThrows(CompletionException.class, pending::join);
        assertInstanceOf(SdkControlException.class, failure.getCause());
    }

    @Test
    void rejectedControlRequestExposesOperationAndRequestId() throws Exception {
        ControlProcess process = new ControlProcess("interrupt");
        QueryOptions options = QueryOptions.builder().processSpawner(_ -> process).build();

        try (SdkQuery query = ClaudeAgentSdk.query("hello", options)) {
            query.initializationResult().join();
            CompletionException failure = assertThrows(CompletionException.class,
                () -> query.interrupt().join());
            SdkControlException control = assertInstanceOf(
                SdkControlException.class, failure.getCause());
            assertEquals("interrupt", control.operation());
            assertNotNull(control.requestId());
        }
    }

    @Test
    void mcpAndBackgroundControlsUseThePublishedRequestFields() throws Exception {
        ControlProcess process = new ControlProcess();
        QueryOptions options = QueryOptions.builder()
            .processSpawner(_ -> process)
            .loadTimeout(Duration.ofSeconds(2))
            .build();

        try (ExtendedSdkQuery query = ClaudeAgentSdk.queryExtended("hello", options)) {
            assertEquals("verify", query.supportedCommands().join().getFirst().name());
            assertEquals("sonnet", query.supportedModels().join().getFirst().value());
            assertEquals("Explore", query.supportedAgents().join().getFirst().name());
            assertEquals("firstParty", query.accountInfo().join().apiProvider());
            query.reconnectMcpServer("docs").join();
            query.toggleMcpServer("docs", false).join();
            query.mcpClearAuth("docs").join();
            query.clearMcpAuth("docs").join();
            query.mcpAuthenticate("docs").join();
            query.authenticateMcp("docs").join();
            query.mcpSubmitOAuthCallbackUrl("docs", "http://127.0.0.1/callback?code=x").join();
            query.submitMcpOAuthCallback(
                "docs", "http://127.0.0.1/callback?code=x").join();
            assertTrue(query.backgroundTasks().join());
            query.updateEnvironmentVariables(Map.of("CLAUDE_CODE_OAUTH_TOKEN", "fresh")).join();
            query.interrupt().join();
            query.stopTask("task-1").join();
            query.setPermissionMode(PermissionMode.PLAN).join();
            query.setModel(null).join();
            query.setMaxThinkingTokens(null).join();
            query.getSettings().join();
            query.applyFlagSettings(Settings.of(Map.of("model", "sonnet"))).join();
            assertEquals(12, query.getContextUsage().join().totalTokens());
            assertEquals(List.of("README.md"), query.rewindFiles(
                "message-1", new RewindFilesOptions(true)).join().filesChanged());
            query.cancelAsyncMessage("message-2").join();
            query.seedReadState(Path.of("README.md"), 123L).join();
            query.generateSessionTitle("SDK controls", false).join();
            query.askSideQuestion("What changed?").join();
            assertEquals("docs", query.mcpServerStatus().join().getFirst().name());
            assertEquals(List.of("remote"), query.setMcpServers(Map.of("remote",
                new McpHttpServerConfig("https://mcp.example.test",
                    Map.of("X-Test", "yes")))).join().added());
            assertEquals("verify-plugin", query.reloadPlugins().join().plugins().getFirst().name());
            query.reloadSkills().join();
            query.readFile(Path.of("README.md"), 1024L, "utf-8").join();
        }

        assertServerName(process.request("mcp_reconnect"));
        assertServerName(process.request("mcp_toggle"));
        assertServerName(process.request("mcp_clear_auth"));
        assertServerName(process.request("mcp_authenticate"));
        JsonNode callback = process.request("mcp_oauth_callback_url");
        assertServerName(callback);
        assertEquals("http://127.0.0.1/callback?code=x",
            callback.path("callbackUrl").asText());
        assertFalse(callback.has("callback_url"));
        assertFalse(process.request("background_tasks").has("tool_use_id"));
        assertFalse(process.request("set_model").has("model"));
        assertTrue(process.request("set_max_thinking_tokens").path("max_thinking_tokens").isNull());
        assertEquals("http", process.request("mcp_set_servers")
            .path("servers").path("remote").path("type").asText());
        JsonNode environment = process.message("update_environment_variables");
        assertEquals("fresh", environment.path("variables")
            .path("CLAUDE_CODE_OAUTH_TOKEN").asText());
        assertTrue(environment.hasNonNull("request_id"));
        assertEquals(Set.of(
            "mcp_reconnect", "mcp_toggle", "mcp_clear_auth", "mcp_authenticate",
            "mcp_oauth_callback_url", "background_tasks", "interrupt", "stop_task",
            "set_permission_mode", "set_model", "set_max_thinking_tokens", "get_settings",
            "apply_flag_settings", "get_context_usage", "rewind_files",
            "cancel_async_message", "seed_read_state", "generate_session_title",
            "side_question", "mcp_status", "mcp_set_servers", "reload_plugins",
            "reload_skills", "read_file"), process.requestSubtypes());
    }

    private static void assertServerName(JsonNode request) {
        assertEquals("docs", request.path("serverName").asText());
        assertFalse(request.has("server_name"));
    }

    private static final class ControlProcess extends Process {
        private final PipedInputStream sdkStdout = new PipedInputStream();
        private final PipedOutputStream childStdout;
        private final PipedInputStream childStdin = new PipedInputStream();
        private final PipedOutputStream sdkStdin;
        private final ByteArrayInputStream stderr = new ByteArrayInputStream(new byte[0]);
        private final Map<String, JsonNode> requests = new ConcurrentHashMap<>();
        private final Map<String, JsonNode> messages = new ConcurrentHashMap<>();
        private final CompletableFuture<JsonNode> initialInput = new CompletableFuture<>();
        private final String errorSubtype;
        private final String ignoredSubtype;
        private volatile boolean alive = true;

        ControlProcess() throws IOException {
            this(null, null);
        }

        ControlProcess(String errorSubtype) throws IOException {
            this(errorSubtype, null);
        }

        ControlProcess(String errorSubtype, String ignoredSubtype) throws IOException {
            this.errorSubtype = errorSubtype;
            this.ignoredSubtype = ignoredSubtype;
            childStdout = new PipedOutputStream(sdkStdout);
            sdkStdin = new PipedOutputStream(childStdin);
            Thread.startVirtualThread(this::runScript);
        }

        JsonNode request(String subtype) {
            return requests.get(subtype);
        }

        JsonNode message(String type) {
            return messages.get(type);
        }

        Set<String> requestSubtypes() {
            return Set.copyOf(requests.keySet());
        }

        CompletableFuture<JsonNode> initialInput() { return initialInput; }

        private void runScript() {
            try (var reader = new BufferedReader(new InputStreamReader(
                    childStdin, StandardCharsets.UTF_8));
                 var writer = new BufferedWriter(new OutputStreamWriter(
                    childStdout, StandardCharsets.UTF_8))) {
                JsonNode initialize = JsonUtils.getMapper().readTree(reader.readLine());
                ObjectNode initialization = JsonUtils.getMapper().createObjectNode();
                initialization.putArray("commands").addObject().put("name", "verify")
                    .put("description", "Verify changes").put("argumentHint", "<target>");
                initialization.putArray("models").addObject().put("value", "sonnet")
                    .put("displayName", "Sonnet").put("description", "Balanced model")
                    .put("supportsFastMode", true);
                initialization.putArray("agents").addObject().put("name", "Explore")
                    .put("description", "Explore the codebase");
                initialization.put("output_style", "default");
                initialization.putArray("available_output_styles").add("default");
                initialization.putObject("account").put("apiProvider", "firstParty");
                initialization.put("future_field", "ignored");
                respond(writer, initialize, initialization);
                initialInput.complete(JsonUtils.getMapper().readTree(reader.readLine()));

                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode envelope = JsonUtils.getMapper().readTree(line);
                    String type = envelope.path("type").asText();
                    if (Strings.CS.equals("update_environment_variables", type)) {
                        messages.put(type, envelope.deepCopy());
                        respond(writer, envelope, JsonUtils.getMapper().createObjectNode());
                        continue;
                    }
                    if (!Strings.CS.equals("control_request", type)) continue;
                    JsonNode request = envelope.path("request");
                    String subtype = request.path("subtype").asText();
                    requests.put(subtype, request.deepCopy());
                    if (Strings.CS.equals(ignoredSubtype, subtype)) continue;
                    if (Strings.CS.equals(errorSubtype, subtype)) {
                        respondError(writer, envelope, "rejected for test");
                    } else respond(writer, envelope, responseFor(subtype));
                }
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                alive = false;
                try { childStdout.close(); } catch (IOException _) { }
            }
        }

        private static ObjectNode responseFor(String subtype) {
            ObjectNode response = JsonUtils.getMapper().createObjectNode();
            switch (subtype) {
                case "background_tasks" -> response.put("backgrounded", true);
                case "cancel_async_message" -> response.put("cancelled", true);
                case "generate_session_title" -> response.put("title", "SDK controls");
                case "side_question" -> response.put("response", "Nothing risky");
                case "mcp_status" -> response.putArray("mcpServers").addObject()
                    .put("name", "docs").put("status", "connected")
                    .putObject("serverInfo").put("name", "docs").put("version", "1.0");
                case "mcp_set_servers" -> {
                    response.putArray("added").add("remote");
                    response.putArray("removed");
                    response.putObject("errors");
                }
                case "rewind_files" -> {
                    response.put("canRewind", true);
                    response.putArray("filesChanged").add("README.md");
                    response.put("insertions", 2).put("deletions", 1);
                }
                case "reload_plugins" -> {
                    response.putArray("commands");
                    response.putArray("agents");
                    response.putArray("plugins").addObject()
                        .put("name", "verify-plugin").put("path", "/plugins/verify");
                    response.putArray("mcpServers");
                    response.put("error_count", 0);
                }
                case "get_context_usage" -> contextUsage(response);
                default -> { }
            }
            return response;
        }

        private static void contextUsage(ObjectNode response) {
            response.putArray("categories");
            response.put("totalTokens", 12).put("maxTokens", 100).put("rawMaxTokens", 100)
                .put("percentage", 12).putArray("gridRows");
            response.put("model", "sonnet");
            response.putArray("memoryFiles");
            response.putArray("mcpTools");
            response.putArray("agents");
            response.put("isAutoCompactEnabled", true);
            response.putNull("apiUsage");
        }

        private static void respond(BufferedWriter writer, JsonNode request,
                                    JsonNode response) throws IOException {
            ObjectNode envelope = JsonUtils.getMapper().createObjectNode();
            envelope.put("type", "control_response");
            envelope.putObject("response").put("subtype", "success")
                .put("request_id", request.path("request_id").asText())
                .set("response", response);
            writer.write(envelope.toString());
            writer.newLine();
            writer.flush();
        }

        private static void respondError(BufferedWriter writer, JsonNode request,
                                         String error) throws IOException {
            ObjectNode envelope = JsonUtils.getMapper().createObjectNode();
            envelope.put("type", "control_response");
            envelope.putObject("response").put("subtype", "error")
                .put("request_id", request.path("request_id").asText()).put("error", error);
            writer.write(envelope.toString());
            writer.newLine();
            writer.flush();
        }

        @Override public OutputStream getOutputStream() { return sdkStdin; }
        @Override public InputStream getInputStream() { return sdkStdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() throws InterruptedException {
            while (alive) Thread.sleep(1);
            return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long end = System.nanoTime() + unit.toNanos(timeout);
            while (alive && System.nanoTime() < end) Thread.sleep(1);
            return !alive;
        }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
