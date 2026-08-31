package com.claudecode.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

class SdkQueryTransportTest {
    @Test
    void malformedInitializationCompletesTheFutureExceptionally() throws Exception {
        ScriptedProcess process = new ScriptedProcess(true);
        QueryOptions options = QueryOptions.builder().processSpawner(_ -> process).build();

        try (SdkQuery query = ClaudeAgentSdk.query("hello", options)) {
            CompletionException failure = assertThrows(CompletionException.class,
                () -> query.initializationResult().join());
            assertInstanceOf(SdkControlException.class, failure.getCause());
        }
    }

    @Test
    void initializesBridgesSdkMcpAndOnlyYieldsPublicMessages() throws Exception {
        SdkMcpToolDefinition echo = ClaudeAgentSdk.tool("echo", "echoes",
            JsonUtils.getMapper().readTree("""
                {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}
                """),
            (input, _) -> SdkMcpToolResult.text(input.path("text").asText()), null);
        McpSdkServerConfigWithInstance server = ClaudeAgentSdk.createSdkMcpServer(
            new CreateSdkMcpServerOptions("local", null, null, List.of(echo), false));
        ScriptedProcess process = new ScriptedProcess();
        QueryOptions options = QueryOptions.builder()
            .sdkMcpServers(Map.of("local", server))
            .processSpawner(_ -> process)
            .loadTimeout(Duration.ofSeconds(2))
            .build();

        try (SdkQuery query = ClaudeAgentSdk.query("hello", options)) {
            SDKControlInitializeResponse initialized = query.initializationResult().get(2, TimeUnit.SECONDS);
            assertEquals("verify", initialized.commands().getFirst().name());
            assertEquals("sonnet", query.supportedModels().get(2, TimeUnit.SECONDS).getFirst().value());
            assertEquals("firstParty", query.accountInfo().get(2, TimeUnit.SECONDS).apiProvider());
            assertThrows(CompletionException.class, () -> query.streamInput(List.of()).join());
            assertTrue(query.hasNext());
            SDKMessage result = query.next();
            assertEquals("result", result.type());
            assertEquals("done", result.value().path("result").asText());
            assertFalse(query.hasNext());
        }
        assertEquals("echoed", process.mcpText);
        assertTrue(process.sawPrompt);
    }

    private static final class ScriptedProcess extends Process {
        private final PipedInputStream sdkStdout = new PipedInputStream();
        private final PipedOutputStream childStdout;
        private final PipedInputStream childStdin = new PipedInputStream();
        private final PipedOutputStream sdkStdin;
        private final ByteArrayInputStream stderr = new ByteArrayInputStream(new byte[0]);
        private volatile boolean alive = true;
        private volatile boolean sawPrompt;
        private volatile String mcpText;
        private final boolean malformedInitialization;

        ScriptedProcess() throws IOException {
            this(false);
        }

        ScriptedProcess(boolean malformedInitialization) throws IOException {
            this.malformedInitialization = malformedInitialization;
            childStdout = new PipedOutputStream(sdkStdout);
            sdkStdin = new PipedOutputStream(childStdin);
            Thread.startVirtualThread(this::runScript);
        }

        private void runScript() {
            try (var reader = new BufferedReader(new InputStreamReader(
                    childStdin, StandardCharsets.UTF_8));
                 var writer = new BufferedWriter(new OutputStreamWriter(
                    childStdout, StandardCharsets.UTF_8))) {
                JsonNode initialize = JsonUtils.getMapper().readTree(reader.readLine());
                assertEquals("initialize", initialize.path("request").path("subtype").asText());
                ObjectNode initialization = JsonUtils.getMapper().createObjectNode();
                initialization.putArray("commands").addObject()
                    .put("name", "verify").put("description", "Verify changes")
                    .put("argumentHint", "<target>");
                initialization.putArray("agents");
                initialization.put("output_style", "default");
                initialization.putArray("available_output_styles").add("default");
                initialization.putArray("models").addObject()
                    .put("value", "sonnet").put("displayName", "Sonnet")
                    .put("description", "Balanced model");
                initialization.putObject("account").put("apiProvider", "firstParty");
                if (malformedInitialization) initialization.remove("commands");
                ObjectNode initResponse = JsonUtils.getMapper().createObjectNode();
                initResponse.put("type", "control_response");
                initResponse.putObject("response").put("subtype", "success")
                    .put("request_id", initialize.path("request_id").asText())
                    .set("response", initialization);
                write(writer, initResponse);

                JsonNode prompt = JsonUtils.getMapper().readTree(reader.readLine());
                sawPrompt = Strings.CS.equals("hello",
                    prompt.path("message").path("content").path(0).path("text").asText());
                if (malformedInitialization) {
                    write(writer, JsonUtils.getMapper().createObjectNode()
                        .put("type", "result").put("subtype", "success")
                        .put("is_error", false).put("result", "done"));
                    return;
                }
                ObjectNode callback = JsonUtils.getMapper().createObjectNode();
                callback.put("type", "control_request").put("request_id", "mcp-1");
                ObjectNode request = callback.putObject("request");
                request.put("subtype", "mcp_message").put("server_name", "local");
                ObjectNode rpc = request.putObject("message");
                rpc.put("jsonrpc", "2.0").put("id", 7).put("method", "tools/call");
                rpc.putObject("params").put("name", "echo").putObject("arguments")
                    .put("text", "echoed");
                write(writer, callback);

                JsonNode callbackResponse = JsonUtils.getMapper().readTree(reader.readLine());
                mcpText = callbackResponse.path("response").path("response")
                    .path("mcp_response").path("result").path("content").path(0).path("text").asText();
                write(writer, JsonUtils.getMapper().createObjectNode()
                    .put("type", "keep_alive"));
                write(writer, JsonUtils.getMapper().createObjectNode()
                    .put("type", "result").put("subtype", "success")
                    .put("is_error", false).put("result", "done"));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                alive = false;
                try { childStdout.close(); } catch (IOException _) { }
            }
        }

        private static void write(BufferedWriter writer, JsonNode node) throws IOException {
            writer.write(node.toString());
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
