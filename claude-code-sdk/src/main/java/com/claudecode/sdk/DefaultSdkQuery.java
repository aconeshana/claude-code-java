package com.claudecode.sdk;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Out-of-process stream-json query transport and control dispatcher.
 * <ul>
 *   <li>{@code node_modules/@anthropic-ai/claude-agent-sdk/sdk.mjs} —
 *       {@code Query} process transport, control methods, callbacks, and cleanup.</li>
 *   <li>public {@code query} entrypoint.</li>
 *   <li>control envelopes.</li>
 * </ul>
 */
final class DefaultSdkQuery implements ExtendedSdkQuery {
    private sealed interface QueueItem permits MessageItem, ErrorItem, EndItem {}
    private record MessageItem(SDKMessage message) implements QueueItem {}
    private record ErrorItem(RuntimeException error) implements QueueItem {}
    private record EndItem() implements QueueItem {}
    private record PendingControl(String operation, CompletableFuture<JsonNode> future) {}
    private record ControlCall(String requestId, String operation,
                               CompletableFuture<JsonNode> future) {}

    private static final EndItem END = new EndItem();
    private final QueryOptions options;
    private final Process process;
    private final BufferedWriter stdin;
    private final BlockingQueue<QueueItem> messages = new LinkedBlockingQueue<>(1024);
    private final Map<String, PendingControl> pending = new ConcurrentHashMap<>();
    private final Map<String, AbortController> activeCallbacks = new ConcurrentHashMap<>();
    private final Map<String, QueryCallbacks.JsonCallback> hookCallbacks = new ConcurrentHashMap<>();
    private final Map<String, SdkMcpServer> sdkServers = new ConcurrentHashMap<>();
    private final CompletableFuture<SDKControlInitializeResponse> initialization =
        new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean inputClaimed = new AtomicBoolean();
    private final boolean singleTurn;
    private final Path temporaryConfig;
    private final SessionMirrorBuffer mirrorBuffer;
    private volatile QueueItem next;
    private volatile String lastErrorResult;
    private final StringBuilder diagnostics = new StringBuilder();

    static DefaultSdkQuery start(String prompt, Iterable<SDKUserMessage> stream, QueryOptions raw) {
        QueryOptions options = raw == null ? QueryOptions.builder().build() : raw;
        try {
            Path temporaryConfig = SessionStoreMaterializer.prepare(options);
            ProcessCommand command = ProcessCommand.create(options);
            Process process = options.processSpawner != null
                ? options.processSpawner.spawn(command) : spawn(command);
            DefaultSdkQuery query = new DefaultSdkQuery(options, process, prompt != null, temporaryConfig);
            query.startReader();
            query.sendInitialize();
            if (prompt != null) {
                query.inputClaimed.set(true);
                query.write(singlePrompt(prompt));
            } else query.streamInput(stream == null ? List.of() : stream);
            return query;
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to start Claude Code SDK query", failure);
        }
    }

    private DefaultSdkQuery(QueryOptions options, Process process, boolean singleTurn,
                            Path temporaryConfig) throws IOException {
        this.options = options;
        this.process = process;
        this.singleTurn = singleTurn;
        this.temporaryConfig = temporaryConfig;
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        mirrorBuffer = new SessionMirrorBuffer(options.sessionStore, options.sessionStoreEager,
            this::reportMirrorError);
        options.sdkMcpServers.forEach((name, config) -> sdkServers.put(name, config.instance()));
        AbortController abort = options.abortController;
        if (abort != null) abort.onAbort(this::abort);
    }

    private static Process spawn(ProcessCommand command) throws IOException {
        List<String> complete = new ArrayList<>();
        complete.add(command.executable());
        complete.addAll(command.arguments());
        ProcessBuilder builder = new ProcessBuilder(complete);
        if (command.cwd() != null) builder.directory(command.cwd().toFile());
        builder.environment().clear();
        builder.environment().putAll(command.environment());
        return builder.start();
    }

    private void startReader() {
        Thread.startVirtualThread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (StringUtils.isBlank(line)) continue;
                    JsonNode node;
                    try {
                        node = JsonUtils.getMapper().readTree(line);
                    } catch (Exception _) {
                        continue;
                    }
                    handleInbound(node);
                }
                int exit = process.waitFor();
                if (exit != 0 && !closed.get()) {
                    RuntimeException error = lastErrorResult == null
                        ? new IllegalStateException("Claude Code process exited with code " + exit
                            + diagnosticSuffix())
                        : new IllegalStateException("Claude Code returned an error result: " + lastErrorResult);
                    fail(error);
                } else finish();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail(new AbortError("Operation aborted", interrupted));
            } catch (IOException error) {
                if (!closed.get()) fail(new IllegalStateException("Failed reading Claude Code output", error));
            } catch (RuntimeException error) {
                if (!closed.get()) fail(error);
            }
        });
        Thread.startVirtualThread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (diagnostics) {
                        if (diagnostics.length() < 16_384) diagnostics.append('\n').append(line);
                    }
                }
            } catch (IOException _) { }
        });
    }

    private String diagnosticSuffix() {
        synchronized (diagnostics) {
            return diagnostics.isEmpty() ? "" : ":" + diagnostics;
        }
    }

    private void handleInbound(JsonNode node) {
        switch (node.path("type").asText("")) {
            case "control_response" -> handleResponse(SDKControlResponse.fromJson(node));
            case "control_request" -> handleControlRequest(SDKControlRequest.fromJson(node));
            case "control_cancel_request" -> {
                AbortController abort = activeCallbacks.remove(node.path("request_id").asText());
                if (abort != null) abort.abort();
            }
            case "keep_alive" -> { }
            case "transcript_mirror" -> mirror(node);
            default -> {
                if (Strings.CS.equals("result", node.path("type").asText())) {
                    if (node.path("is_error").asBoolean(false)) {
                        lastErrorResult = Strings.CS.equals(
                            "success", node.path("subtype").asText())
                            ? node.path("result").asText() : joinErrors(node.path("errors"));
                    }
                    if (singleTurn) endInput();
                } else if (!(Strings.CS.equals("system", node.path("type").asText())
                        && Strings.CS.equals("session_state_changed",
                            node.path("subtype").asText()))) {
                    lastErrorResult = null;
                }
                put(new MessageItem(new SDKMessage(node)));
            }
        }
    }

    private void handleResponse(SDKControlResponse response) {
        PendingControl pendingControl = pending.remove(response.requestId());
        if (pendingControl == null) return;
        if (response.success()) {
            JsonNode value = response.response();
            pendingControl.future().complete(value == null
                ? JsonUtils.getMapper().createObjectNode() : value);
        } else {
            pendingControl.future().completeExceptionally(new SdkControlException(
                pendingControl.operation(), response.requestId(), response.error()));
            response.pendingPermissionRequests().forEach(this::handleControlRequest);
        }
    }

    private void handleControlRequest(SDKControlRequest envelope) {
        String requestId = envelope.requestId();
        if (activeCallbacks.putIfAbsent(requestId, new AbortController()) != null) return;
        AbortController abort = activeCallbacks.get(requestId);
        Thread.startVirtualThread(() -> {
            try {
                JsonNode result = processCallback(envelope.request(), abort);
                write(SDKControlResponse.success(requestId, result == null
                    ? JsonUtils.getMapper().createObjectNode() : result).toJson());
            } catch (Exception failure) {
                write(SDKControlResponse.error(requestId,
                    Objects.toString(failure.getMessage(), failure.getClass().getSimpleName())).toJson());
            } finally {
                activeCallbacks.remove(requestId);
            }
        });
    }

    private JsonNode processCallback(JsonNode request, AbortController abort) throws Exception {
        return switch (request.path("subtype").asText()) {
            case "can_use_tool" -> {
                if (options.canUseTool == null) throw new IllegalStateException("canUseTool callback is not provided");
                JsonNode decision = options.canUseTool.call(request.path("tool_name").asText(),
                    request.path("input"), abort);
                ObjectNode result = decision != null && decision.isObject()
                    ? ((ObjectNode) decision).deepCopy() : JsonUtils.getMapper().createObjectNode();
                result.put("toolUseID", request.path("tool_use_id").asText());
                yield result;
            }
            case "hook_callback" -> callback(hookCallbacks.get(request.path("callback_id").asText()), request, abort);
            case "elicitation" -> callback(options.onElicitation, request, abort,
                JsonUtils.getMapper().createObjectNode().put("action", "decline"));
            case "request_user_dialog" -> callback(options.onUserDialog, request, abort,
                JsonUtils.getMapper().createObjectNode().put("action", "cancel"));
            case "oauth_token_refresh" -> callback(options.getOAuthToken, request, abort);
            case "host_auth_token_refresh" -> callback(options.getHostAuthToken, request, abort);
            case "mcp_message" -> mcpMessage(request);
            default -> throw new IllegalArgumentException(
                "Unsupported control request subtype: " + request.path("subtype").asText());
        };
    }

    private JsonNode mcpMessage(JsonNode request) throws Exception {
        SdkMcpServer server = sdkServers.get(request.path("server_name").asText());
        if (server == null) throw new IllegalArgumentException("SDK MCP server not found");
        JsonNode response = server.handle(request.path("message")).get();
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.set("mcp_response", response == null
            ? JsonUtils.getMapper().createObjectNode().put("jsonrpc", "2.0") : response);
        return result;
    }

    private static JsonNode callback(QueryCallbacks.JsonCallback callback, JsonNode request,
                                     AbortController abort) throws Exception {
        if (callback == null) throw new IllegalStateException("Callback is not provided");
        return callback.call(request, abort);
    }

    private static JsonNode callback(QueryCallbacks.JsonCallback callback, JsonNode request,
                                     AbortController abort, JsonNode fallback) throws Exception {
        return callback == null ? fallback : callback.call(request, abort);
    }

    private void sendInitialize() {
        ObjectNode request = JsonUtils.getMapper().createObjectNode();
        request.put("subtype", "initialize");
        if (!sdkServers.isEmpty()) request.set("sdkMcpServers",
            JsonUtils.getMapper().valueToTree(sdkServers.keySet()));
        if (!options.supportedDialogKinds.isEmpty()) request.set("supportedDialogKinds",
            JsonUtils.getMapper().valueToTree(options.supportedDialogKinds));
        if (options.outputSchema != null) request.set("jsonSchema", options.outputSchema);
        if (options.systemPrompt != null) request.put("systemPrompt", options.systemPrompt);
        if (options.appendSystemPrompt != null) request.put("appendSystemPrompt", options.appendSystemPrompt);
        if (options.title != null) request.put("title", options.title);
        if (options.agents != null) request.set("agents", options.agents);
        if (!options.skills.isEmpty()) request.set("skills",
            JsonUtils.getMapper().valueToTree(options.skills));
        if (options.promptConfig != null) request.set("promptConfig", options.promptConfig);
        if (!options.hooks.isEmpty()) {
            ObjectNode hooks = request.putObject("hooks");
            options.hooks.forEach((event, callback) -> {
                String id = "hook_" + hookCallbacks.size();
                hookCallbacks.put(id, callback);
                ObjectNode matcher = hooks.putArray(event).addObject();
                matcher.putArray("hookCallbackIds").add(id);
            });
        }
        ControlCall call = request(request);
        call.future().whenComplete((value, failure) -> {
            if (failure != null) initialization.completeExceptionally(failure);
            else {
                try {
                    initialization.complete(SdkQueryJson.initialization(value));
                } catch (RuntimeException malformed) {
                    initialization.completeExceptionally(new SdkControlException(
                        call.operation(), call.requestId(), malformed));
                }
            }
        });
    }

    private static ObjectNode singlePrompt(String prompt) {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("type", "user");
        root.put("session_id", "");
        ObjectNode message = root.putObject("message");
        message.put("role", "user");
        message.putArray("content").addObject().put("type", "text").put("text", prompt);
        root.putNull("parent_tool_use_id");
        root.put("uuid", UUID.randomUUID().toString());
        root.put("timestamp", Instant.now().toString());
        return root;
    }

    private ControlCall request(ObjectNode body) {
        String id = UUID.randomUUID().toString();
        String operation = body.path("subtype").asText("control_request");
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        if (closed.get()) {
            future.completeExceptionally(new SdkControlException(operation, id, "Query is closed"));
            return new ControlCall(id, operation, future);
        }
        pending.put(id, new PendingControl(operation, future));
        write(new SDKControlRequest(id, body).toJson());
        return new ControlCall(id, operation, future);
    }

    private ControlCall request(String subtype) {
        return request(JsonUtils.getMapper().createObjectNode().put("subtype", subtype));
    }

    private ControlCall topLevelRequest(ObjectNode envelope) {
        String id = UUID.randomUUID().toString();
        String operation = envelope.path("type").asText("control_message");
        envelope.put("request_id", id);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        if (closed.get()) {
            future.completeExceptionally(new SdkControlException(operation, id, "Query is closed"));
            return new ControlCall(id, operation, future);
        }
        pending.put(id, new PendingControl(operation, future));
        write(envelope);
        return new ControlCall(id, operation, future);
    }

    private static CompletableFuture<Void> discard(ControlCall call) {
        return call.future().thenApply(_ -> null);
    }

    private static <T> CompletableFuture<T> decode(ControlCall call,
                                                    Function<JsonNode, T> decoder) {
        return call.future().thenApply(value -> {
            try {
                return decoder.apply(value);
            } catch (RuntimeException malformed) {
                throw new SdkControlException(call.operation(), call.requestId(), malformed);
            }
        });
    }

    private synchronized void write(JsonNode node) {
        if (closed.get()) return;
        try {
            stdin.write(node.toString());
            stdin.newLine();
            stdin.flush();
        } catch (IOException error) {
            fail(new IllegalStateException("Failed writing Claude Code input", error));
        }
    }

    private synchronized void writeInput(JsonNode node) {
        if (closed.get()) throw new IllegalStateException("Query is closed");
        try {
            stdin.write(node.toString());
            stdin.newLine();
            stdin.flush();
        } catch (IOException error) {
            throw new IllegalStateException("Failed writing Claude Code input", error);
        }
    }

    private synchronized void endInput() {
        try { stdin.close(); } catch (IOException _) { }
    }

    private void mirror(JsonNode frame) {
        if (options.sessionStore == null) return;
        SessionStoreKey key = SessionStoreMaterializer.keyForFrame(
            frame.path("filePath").asText(), options);
        if (key == null) return;
        List<JsonNode> entries = new ArrayList<>();
        frame.path("entries").forEach(entry -> entries.add(entry.deepCopy()));
        mirrorBuffer.append(key, entries);
    }

    private void reportMirrorError(Exception failure) {
        ObjectNode error = JsonUtils.getMapper().createObjectNode();
        error.put("type", "system").put("subtype", "mirror_error");
        error.put("error", Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
        put(new MessageItem(new SDKMessage(error)));
    }

    private static String joinErrors(JsonNode errors) {
        List<String> values = new ArrayList<>();
        if (errors.isArray()) errors.forEach(value -> values.add(value.asText()));
        return String.join("; ", values);
    }

    private void put(QueueItem item) {
        try { messages.put(item); }
        catch (InterruptedException _) { Thread.currentThread().interrupt(); }
    }

    private void finish() {
        mirrorBuffer.flush();
        if (!initialization.isDone()) initialization.completeExceptionally(
            new IllegalStateException("Claude Code exited before SDK initialization"));
        rejectPending("Claude Code exited before response was received");
        put(END);
    }

    private void fail(RuntimeException error) {
        mirrorBuffer.flush();
        pending.values().forEach(control -> control.future().completeExceptionally(error));
        pending.clear();
        if (!initialization.isDone()) initialization.completeExceptionally(error);
        put(new ErrorItem(error));
    }

    private void rejectPending(String message) {
        pending.forEach((requestId, control) -> control.future().completeExceptionally(
            new SdkControlException(control.operation(), requestId, message)));
        pending.clear();
    }

    private void abort() {
        if (closed.compareAndSet(false, true)) {
            mirrorBuffer.flush();
            activeCallbacks.values().forEach(AbortController::abort);
            process.destroy();
            fail(new AbortError("Claude Code process aborted by user"));
            cleanupTemporary();
        }
    }

    @Override public boolean hasNext() {
        if (next == END) return false;
        if (next == null) {
            try { next = messages.take(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AbortError("Operation aborted", interrupted);
            }
        }
        if (next instanceof ErrorItem(RuntimeException error1)) throw error1;
        return next != END;
    }

    @Override public SDKMessage next() {
        if (!hasNext()) throw new NoSuchElementException();
        MessageItem item = (MessageItem) next;
        next = null;
        return item.message();
    }

    @Override public CompletableFuture<SDKControlInitializeResponse> initializationResult() {
        return initialization;
    }

    @Override public CompletableFuture<Void> interrupt() { return discard(request("interrupt")); }

    @Override public CompletableFuture<Void> stopTask(String id) {
        return discard(request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "stop_task").put("task_id", id)));
    }

    @Override public CompletableFuture<Boolean> backgroundTasks(String id) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode().put("subtype", "background_tasks");
        if (id != null) body.put("tool_use_id", id);
        return decode(request(body), value -> value.path("backgrounded").asBoolean(true));
    }

    @Override public CompletableFuture<Void> setPermissionMode(PermissionMode mode) {
        Objects.requireNonNull(mode, "mode");
        return discard(request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "set_permission_mode").put("mode", mode.wireValue())));
    }

    @Override public CompletableFuture<Void> setModel(String model) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode().put("subtype", "set_model");
        if (model != null) body.put("model", model);
        return discard(request(body));
    }

    @Override public CompletableFuture<Void> setMaxThinkingTokens(Integer tokens) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode()
            .put("subtype", "set_max_thinking_tokens");
        if (tokens == null) body.putNull("max_thinking_tokens");
        else body.put("max_thinking_tokens", tokens);
        return discard(request(body));
    }

    @Override public CompletableFuture<JsonNode> getSettings() {
        return request("get_settings").future();
    }

    @Override public CompletableFuture<Void> applyFlagSettings(Settings settings) {
        Objects.requireNonNull(settings, "settings");
        ObjectNode body = JsonUtils.getMapper().createObjectNode().put("subtype", "apply_flag_settings");
        body.set("settings", settings.value());
        return discard(request(body));
    }

    @Override public CompletableFuture<SDKControlGetContextUsageResponse> getContextUsage() {
        return decode(request("get_context_usage"), SdkQueryJson::contextUsage);
    }

    @Override public CompletableFuture<RewindFilesResult> rewindFiles(
            String id, RewindFilesOptions options) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode().put("subtype", "rewind_files")
            .put("user_message_id", id);
        if (options != null && options.dryRun() != null) body.put("dry_run", options.dryRun());
        return decode(request(body), SdkQueryJson::rewind);
    }

    @Override public CompletableFuture<Boolean> cancelAsyncMessage(String id) {
        return decode(request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "cancel_async_message").put("message_uuid", id)),
            value -> value.path("cancelled").asBoolean());
    }

    @Override public CompletableFuture<Void> seedReadState(Path path, long mtime) {
        return discard(request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "seed_read_state").put("path", path.toString()).put("mtime", mtime)));
    }

    @Override public CompletableFuture<String> generateSessionTitle(String description,
                                                                     boolean persist) {
        return decode(request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "generate_session_title").put("description", description)
            .put("persist", persist)), value -> value.path("title").asText());
    }

    @Override public CompletableFuture<JsonNode> askSideQuestion(String question) {
        return decode(request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "side_question").put("question", question)),
            value -> value.has("response") ? value.get("response") : value);
    }

    @Override public CompletableFuture<List<McpServerStatus>> mcpServerStatus() {
        return decode(request("mcp_status"), SdkQueryJson::mcpStatuses);
    }

    @Override public CompletableFuture<McpSetServersResult> setMcpServers(
            Map<String, ? extends McpServerConfig> servers) {
        Map<String, ? extends McpServerConfig> requested = servers == null ? Map.of() : servers;
        ObjectNode body = JsonUtils.getMapper().createObjectNode().put("subtype", "mcp_set_servers");
        ObjectNode configs = body.putObject("servers");
        Map<String, SdkMcpServer> replacementSdk = new LinkedHashMap<>();
        requested.forEach((name, config) -> {
            configs.set(name, SdkQueryJson.mcpConfig(config));
            if (config instanceof McpSdkServerConfigWithInstance sdk) {
                replacementSdk.put(name, sdk.instance());
            }
        });
        body.set("sdkMcpServers", JsonUtils.getMapper().valueToTree(replacementSdk.keySet()));
        Map<String, SdkMcpServer> previous = new HashMap<>(sdkServers);
        sdkServers.clear();
        sdkServers.putAll(replacementSdk);
        CompletableFuture<McpSetServersResult> result = decode(request(body),
            SdkQueryJson::mcpSetServersResult);
        return result.whenComplete((_, failure) -> {
            if (failure != null) {
                sdkServers.clear();
                sdkServers.putAll(previous);
                replacementSdk.forEach((name, server) -> {
                    if (!previous.containsKey(name)) server.close();
                });
            } else previous.forEach((name, server) -> {
                if (replacementSdk.get(name) != server) server.close();
            });
        });
    }

    @Override public CompletableFuture<Void> reconnectMcpServer(String name) {
        return discard(request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "mcp_reconnect").put("serverName", name)));
    }

    @Override public CompletableFuture<Void> toggleMcpServer(String name, boolean enabled) {
        return discard(request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "mcp_toggle").put("serverName", name).put("enabled", enabled)));
    }

    @Override public CompletableFuture<JsonNode> mcpClearAuth(String name) {
        return request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "mcp_clear_auth").put("serverName", name)).future();
    }

    @Override public CompletableFuture<JsonNode> mcpAuthenticate(String name) {
        return request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "mcp_authenticate").put("serverName", name)).future();
    }

    @Override public CompletableFuture<JsonNode> mcpSubmitOAuthCallbackUrl(
            String name, String callbackUrl) {
        return request(JsonUtils.getMapper().createObjectNode()
            .put("subtype", "mcp_oauth_callback_url").put("serverName", name)
            .put("callbackUrl", callbackUrl)).future();
    }

    @Override public CompletableFuture<SDKControlReloadPluginsResponse> reloadPlugins() {
        return decode(request("reload_plugins"), SdkQueryJson::reloadPlugins);
    }

    @Override public CompletableFuture<JsonNode> reloadSkills() {
        return request("reload_skills").future();
    }

    @Override public CompletableFuture<JsonNode> readFile(Path path, Long maxBytes,
                                                           String encoding) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode().put("subtype", "read_file")
            .put("path", path.toString());
        if (maxBytes != null) body.put("max_bytes", maxBytes);
        if (encoding != null) body.put("encoding", encoding);
        return request(body).future();
    }

    @Override public CompletableFuture<Void> updateEnvironmentVariables(
            Map<String, String> variables) {
        ObjectNode message = JsonUtils.getMapper().createObjectNode()
            .put("type", "update_environment_variables");
        message.set("variables", JsonUtils.getMapper().valueToTree(
            variables == null ? Map.of() : variables));
        return discard(topLevelRequest(message));
    }

    @Override public CompletableFuture<Void> streamInput(Iterable<SDKUserMessage> stream) {
        if (stream == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("stream is required"));
        }
        if (!inputClaimed.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Query input stream has already been started"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                for (SDKUserMessage message : stream) {
                    if (closed.get()) throw new IllegalStateException("Query is closed");
                    writeInput(message.toJson());
                }
                endInput();
                result.complete(null);
            } catch (RuntimeException failure) {
                endInput();
                result.completeExceptionally(failure);
                fail(failure);
            }
        });
        return result;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        endInput();
        mirrorBuffer.flush();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        rejectPending("Query closed before response was received");
        activeCallbacks.values().forEach(AbortController::abort);
        sdkServers.values().forEach(SdkMcpServer::close);
        cleanupTemporary();
        put(END);
    }

    private void cleanupTemporary() {
        if (temporaryConfig == null) return;
        try (var paths = Files.walk(temporaryConfig)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException _) { } });
        } catch (IOException _) { }
    }
}
