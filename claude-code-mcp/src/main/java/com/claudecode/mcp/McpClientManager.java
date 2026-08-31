package com.claudecode.mcp;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.mcp.ChannelMessageWrapper;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.UnicodeSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages connections to MCP servers.
 */
public class McpClientManager implements McpClientRuntime, AutoCloseable {

    @FunctionalInterface
    public interface ElicitationHandler {
        JsonNode handle(String serverName, JsonNode params, Set<String> activeToolUseIds);
    }

    private static final Logger LOG = LoggerFactory.getLogger(McpClientManager.class);

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
    /** Cached resources/list snapshots, invalidated on reconnect/list_changed. */
    private final Map<String, List<JsonNode>> resourceCache = new ConcurrentHashMap<>();
    private final Map<String, Object> reconnectLocks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> activeToolUseIdsByServer =
        new ConcurrentHashMap<>();
    /** Active MCP progress listeners keyed by the progress token carried in tools/call._meta. */
    private final Map<String, Map<String, Consumer<JsonNode>>> progressListenersByServer =
        new ConcurrentHashMap<>();

    /**
     * Fires when a server sends {@code notifications/tools/list_changed}.
     * The receiver is expected to re-run {@link #listToolsForServer} for the
     * named server and refresh its {@code ToolRegistry} entries. Null in
     * headless / test contexts — {@link #registerNotificationHandlers}
     * silently no-ops when unset.
     */
    private volatile Consumer<String> toolsChangedListener;
    /**
     * Fires when a server sends {@code notifications/prompts/list_changed}.
     * Same shape as {@link #toolsChangedListener} — the receiver refreshes
     * the slash-command registry via {@link #listPromptsForServer}.
     */
    private volatile Consumer<String> promptsChangedListener;
    private volatile ElicitationHandler elicitationHandler;
    private volatile SdkControlTransport.MessageExchange sdkMessageExchange;

    @Override public void setSdkMessageExchange(SdkControlTransport.MessageExchange exchange) {
        this.sdkMessageExchange = exchange;
    }

    /** Routes an SDK-server message into its live CLI-side transport. */
    @Override public void deliverSdkMessage(String serverName, JsonNode message) {
        McpConnection connection = connections.get(serverName);
        if (connection != null && connection.getTransport() instanceof SdkControlTransport sdk) {
            sdk.receive(message);
        }
    }

    /** Installs an MCP elicitation bridge and retrofits already-live transports. */
    @Override public void setElicitationHandler(ElicitationHandler handler) {
        this.elicitationHandler = handler;
        if (handler != null) {
            connections.forEach((name, connection) -> {
                if (!Strings.CS.equals("sdk", connection.getConfig().transportType())) {
                    registerElicitationHandler(connection.getTransport(), name);
                }
            });
        }
    }

    /**
     * Installs the {@code tools/list_changed} broadcast target. See
     * {@link #toolsChangedListener}. Overwrites any previously-set listener.
     */
    @Override public void setToolsChangedListener(Consumer<String> listener) {
        this.toolsChangedListener = listener;
    }

    /**
     * Installs the {@code prompts/list_changed} broadcast target. See
     * {@link #promptsChangedListener}.
     */
    @Override public void setPromptsChangedListener(Consumer<String> listener) {
        this.promptsChangedListener = listener;
    }

    /**
     * Session-scoped priority queue for injecting MCP channel / notification
     * messages between query turns. Shared with the owning {@code QueryEngine}.
     * Null until wired by the app layer via {@link #setMessageQueue}.
     */
    private volatile MessageQueueManager messageQueue;

    /**
     * Wires the session priority queue so channel notification handlers can
     * enqueue messages. Call immediately after constructing both the
     * {@code McpClientManager} and the {@code QueryEngine}.
     */
    @Override public void setMessageQueue(MessageQueueManager queue) {
        this.messageQueue = queue;
    }

    /**
     * Connects to an MCP server using the given configuration.
     * If a connection with the same server name already exists, it is replaced.
     *
     * @param config the server configuration
     */
    @Override public void connect(McpServerConfig config) {
        connect(config, null);
    }

    private void connect(McpServerConfig config, List<McpToolInfo> retainedTools) {
        if (config.disabled()) {
            throw new McpException("Cannot connect to disabled server '" + config.name() + "'");
        }

        // Disconnect existing connection if present
        disconnect(config.name());

        McpTransport transport = createTransport(config);
        McpConnection connection = new McpConnection(config, transport);
        if (retainedTools != null) {
            connection.setCachedTools(retainedTools);
        }
        try {
            connections.put(config.name(), connection);

            // Wire server → client message handlers — every transport keeps its
            // own read loop (stdio's reader thread / SSE's event stream), so
            // inbound requests and notifications are live on all of them.
            boolean sdkHosted = Strings.CS.equals("sdk", config.transportType());
            if (!sdkHosted) registerServerRequestHandlers(transport, config);
            registerNotificationHandlers(transport, config);

            // MCP handshake — must complete before any tools/prompts/resources
            // calls so the server knows what we support and vice-versa. See
            // spec: https://modelcontextprotocol.io/specification/basic/lifecycle
            performInitialize(transport, connection, sdkHosted);

            // Channel handler registration is post-initialize because the server's
            // experimental capabilities (needed for the gate) are only known after
            // the initialize handshake completes.
            if (connection.hasExperimentalCapability("claude/channel")) {
                registerChannelNotificationHandler(transport, config);
            }
        } catch (RuntimeException | Error failure) {
            connections.remove(config.name(), connection);
            closeAfterFailure(connection, failure);
            throw failure;
        }

        LOG.info("Connected to MCP server '{}'", config.name());
    }

    /**
     * MCP protocol version this client claims to support.
     */
    private static final String CLIENT_PROTOCOL_VERSION = "2025-11-25";

    /**
     * Sends the {@code initialize} JSON-RPC request, caches the server's capabilities on the {@link
     * McpConnection}, then fires {@code notifications/initialized} to complete the handshake.
     */
    private void performInitialize(McpTransport transport, McpConnection connection,
                                   boolean sdkHosted) {
        var mapper = JsonUtils.getMapper();
        var params = mapper.createObjectNode();
        params.put("protocolVersion", CLIENT_PROTOCOL_VERSION);
        // Only advertise what we actually implement — declaring a
        // capability we can't service invites the server to send us
        // requests we'll respond to with MethodNotFound.
        var caps = params.putObject("capabilities");
        if (!sdkHosted) {
            caps.putObject("roots");
            caps.putObject("elicitation");
        }
        var clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "claude-code");
        clientInfo.put("title", "Claude Code");
        clientInfo.put("version", "2.1.197");
        clientInfo.put("description", "Anthropic's agentic coding tool");
        clientInfo.put("websiteUrl", "https://claude.com/claude-code");

        JsonNode result = transport.sendRequest("initialize", params);
        connection.setInitializeResult(result);
        LOG.debug("MCP initialize handshake done: server={} protocol={}",
            connection.getServerInfo(), connection.getProtocolVersion());

        // Spec: client MUST send this notification after initialize returns
        // and before any other request. Servers may buffer requests until
        // they see it; skipping it hangs the connection on strict servers.
        transport.sendNotification("notifications/initialized", null);
    }

    /**
     * Registers handlers for server-initiated JSON-RPC <em>requests</em> ({@code roots/list} etc.).
     */
    private void registerServerRequestHandlers(McpTransport transport, McpServerConfig config) {
        transport.onServerRequest("roots/list", _ -> {
            var mapper = JsonUtils.getMapper();
            var result = mapper.createObjectNode();
            var roots = result.putArray("roots");
            var entry = mapper.createObjectNode();
            entry.put("uri", "file://" + System.getProperty("user.dir"));
            entry.put("name", "workspace");
            roots.add(entry);
            LOG.debug("Server {} requested roots/list — replying with cwd", config.name());
            return result;
        });
        registerElicitationHandler(transport, config.name());
    }

    private void registerElicitationHandler(McpTransport transport, String serverName) {
        ElicitationHandler handler = elicitationHandler;
        if (handler == null) return;
        transport.onServerRequest("elicitation/create", params ->
            handler.handle(serverName, params, activeToolUseIds(serverName)));
        transport.onServerRequest("elicit/create", params ->
            handler.handle(serverName, params, activeToolUseIds(serverName)));
    }

    private Set<String> activeToolUseIds(String serverName) {
        Set<String> active = activeToolUseIdsByServer.get(serverName);
        return active == null || active.isEmpty() ? Set.of() : Set.copyOf(active);
    }

    /**
     * Registers handlers for server-initiated <em>notifications</em> ({@code
     * notifications/tools/list_changed} etc).
     */
    private void registerNotificationHandlers(McpTransport transport, McpServerConfig config) {
        transport.onNotification("notifications/tools/list_changed", _ -> {
            LOG.info("MCP server '{}' announced tools/list_changed", config.name());
            // Invalidate the per-connection tool cache so the next listTools*

            // fetchToolsForClient.cache before re-fetching on list_changed.
            McpConnection conn = connections.get(config.name());
            if (conn != null) {
                conn.setCachedTools(null);
            }
            Consumer<String> l = toolsChangedListener;
            if (l != null) l.accept(config.name());
        });
        transport.onNotification("notifications/prompts/list_changed", _ -> {
            LOG.info("MCP server '{}' announced prompts/list_changed", config.name());
            Consumer<String> l = promptsChangedListener;
            if (l != null) l.accept(config.name());
        });
        transport.onNotification("notifications/resources/list_changed", _ -> {
            LOG.info("MCP server '{}' announced resources/list_changed", config.name());
            resourceCache.remove(config.name());
        });
        transport.onNotification("notifications/progress", params -> {
            if (params == null || !params.hasNonNull("progressToken")) return;
            String token = params.path("progressToken").asText("");
            Map<String, Consumer<JsonNode>> listeners = progressListenersByServer.get(config.name());
            Consumer<JsonNode> listener = listeners == null ? null : listeners.get(token);
            if (listener != null) {
                try {
                    listener.accept(params);
                } catch (RuntimeException callbackFailure) {
                    LOG.debug("MCP progress listener for '{}' failed: {}", config.name(),
                        callbackFailure.getMessage());
                }
            }
        });
// Channel handler registration is deferred to after performInitialize
        // because the capability gate requires the initialize result.
    }

    /**
     * Registers the {@code notifications/claude/channel} handler for servers that declared the {@code
     * claude/channel} experimental capability.
     */
    private void registerChannelNotificationHandler(McpTransport transport, McpServerConfig config) {
        String serverName = config.name();
        transport.onNotification("notifications/claude/channel", params -> {
            if (params == null) return;
            JsonNode contentNode = params.get("content");
            if (contentNode == null || !contentNode.isTextual()) {
                LOG.warn("MCP channel notification from '{}' missing string 'content'", serverName);
                return;
            }
            String content = contentNode.textValue();
            // Extract optional meta attributes (record<string,string>)
            Map<String, String> meta = null;
            JsonNode metaNode = params.get("meta");
            if (metaNode != null && metaNode.isObject()) {
                meta = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = metaNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    if (e.getValue().isTextual()) {
                        meta.put(e.getKey(), e.getValue().textValue());
                    }
                }
            }
            String xml = ChannelMessageWrapper.wrapChannelMessage(serverName, content, meta);
            LOG.debug("MCP channel notification from '{}': {}", serverName,
                content.substring(0, Math.min(80, content.length())));
            MessageQueueManager mq = messageQueue;
            if (mq != null) {
                mq.enqueue(QueuedCommand.channel(xml));
            } else {
                LOG.warn("No MessageQueueManager wired — dropping channel message from '{}'", serverName);
            }
        });
        LOG.info("Registered claude/channel notification handler for MCP server '{}'", serverName);
    }

    /**
     * Disconnects from the specified MCP server.
     *
     * @param serverId the server identifier
     */
    @Override public void disconnect(String serverId) {
        resourceCache.remove(serverId);
        McpConnection conn = connections.remove(serverId);
        if (conn != null) {
            try {
                conn.close();
                LOG.info("Disconnected from MCP server '{}'", serverId);
            } catch (Exception e) {
                LOG.warn("Error disconnecting from MCP server '{}'", serverId, e);
            }
        }
    }

    /**
     * Returns the connection for the specified server, if it exists and is connected.
     */
    public Optional<McpConnection> getConnection(String serverId) {
        McpConnection conn = connections.get(serverId);
        if (conn != null && conn.isConnected()) {
            return Optional.of(conn);
        }
        return Optional.empty();
    }

    /**
     * Returns a non-owning view of a connected server.
     *
     * <p>The returned view must not be closed. Connection lifecycle remains
     * owned by this manager through {@link #disconnect(String)} and
     * {@link #close}.
     */
    @Override public Optional<McpConnectionView> borrowConnection(String serverId) {
        return getConnection(serverId).map(McpConnectionView::of);
    }

    /**
     * Returns a healthy connection for an operation, reconnecting a known dropped transport when
     * possible.
     */
    @Override public McpConnectionView ensureConnected(String serverId) {
        Optional<McpConnection> healthy = getConnection(serverId);
        return healthy.map(McpConnectionView::of).orElseGet(() -> ensureConnectedForOperation(serverId));
    }

    /** Returns known server IDs, including a connection whose transport dropped. */
    @Override public Set<String> getKnownServerIds() {
        Set<String> result = new HashSet<>(connections.keySet());
        result.addAll(getConnectedServerIds());
        return Set.copyOf(result);
    }

    /**
     * Cached resources/list discovery with reconnect-on-session-expiry. Raw
     * resource objects are retained so extension fields are not discarded.
     */
    @Override public List<JsonNode> listResourcesForServer(String serverId) {
        McpConnectionView connection = ensureConnected(serverId);
        if (!connection.hasCapability("resources")) return List.of();
        List<JsonNode> cached = resourceCache.get(serverId);
        if (cached != null) return cached;
        JsonNode result = sendRequestWithRecovery(serverId, "resources/list", null);
        JsonNode resources = result == null ? null : result.get("resources");
        if (resources == null || !resources.isArray()) {
            resourceCache.put(serverId, List.of());
            return List.of();
        }
        List<JsonNode> snapshot = new ArrayList<>();
        resources.forEach(resource -> snapshot.add(resource.deepCopy()));
        List<JsonNode> immutable = List.copyOf(snapshot);
        resourceCache.put(serverId, immutable);
        return immutable;
    }

    /** Reads a resource through the reconnecting request path. */
    @Override public JsonNode readResource(String serverId, String uri) {
        McpConnectionView connection = ensureConnected(serverId);
        if (!connection.hasCapability("resources")) {
            throw new McpException("Server \"" + serverId + "\" does not support resources");
        }
        ObjectNode params = JsonUtils.getMapper().createObjectNode();
        params.put("uri", uri == null ? "" : uri);
        return sendRequestWithRecovery(serverId, "resources/read", params);
    }

    /** Sends a non-tool MCP request and retries one recoverable session drop. */
    @Override public JsonNode sendRequestWithRecovery(String serverId, String method, JsonNode params) {
        try {
            return ensureConnected(serverId).sendRequest(method, params);
        } catch (McpException failure) {
            if (!isRecoverableSessionFailure(failure)) throw failure;
            resourceCache.remove(serverId);
            reconnectForRetry(serverId);
            return ensureConnected(serverId).sendRequest(method, params);
        }
    }

    /**
     * Discovers tools from all connected MCP servers.
     * Sends a "tools/list" JSON-RPC request to each server.
     *
     * @return aggregated list of tools from all servers
     */
    @Override public List<McpToolInfo> listTools() {
        List<McpToolInfo> allTools = new ArrayList<>();
        for (McpConnection conn : connections.values()) {
            if (!conn.isConnected()) continue;
            try {
                allTools.addAll(getToolsForConnection(conn));
            } catch (McpException e) {
                LOG.warn("Failed to list tools from server '{}'", conn.getServerId(), e);
            }
        }
        return Collections.unmodifiableList(allTools);
    }

    /**
     * Discovers tools from a specific MCP server.
     *
     * @param serverId the server identifier
     * @return list of tools from that server
     */
    @Override public List<McpToolInfo> listToolsForServer(String serverId) {
        McpConnection conn = connections.get(serverId);
        if (conn == null || !conn.isConnected()) {
            return List.of();
        }
        try {
            return getToolsForConnection(conn);
        } catch (McpException e) {
            LOG.warn("Failed to list tools from server '{}'", serverId, e);
            return List.of();
        }
    }

    /** Whether the initialized server advertised {@code capabilities.tools}. */
    boolean serverSupportsTools(String serverId) {
        return ensureConnected(serverId).hasCapability("tools");
    }

    /**
     * Executes the health check's real {@code tools/list} probe and preserves
     * failures for authentication/status classification instead of logging and
     * collapsing them to an empty list like {@link #listToolsForServer}.
     */
    void verifyToolsForServer(String serverId) {
        McpConnection connection = connections.get(serverId);
        if (connection == null || !connection.isConnected()) {
            throw new McpException("Server \"" + serverId + "\" is not connected");
        }
        getToolsForConnection(connection);
    }

    @Override
    public List<McpToolInfo> cachedToolsForServer(String serverId) {
        McpConnection connection = connections.get(serverId);
        if (connection == null || !connection.isConnected()) return List.of();
        List<McpToolInfo> cached = connection.getCachedTools();
        return cached == null ? List.of() : cached;
    }

    /**
     * Returns the cached tool list for a connection, discovering and caching it on first access.
     */
    private List<McpToolInfo> getToolsForConnection(McpConnection conn) throws McpException {
        List<McpToolInfo> cached = conn.getCachedTools();
        if (cached != null) {
            return cached;
        }
        List<McpToolInfo> tools = List.copyOf(discoverTools(conn));
        conn.setCachedTools(tools);
        return tools;
    }

    /**
     * Discovers prompts from a specific MCP server via {@code prompts/list}.
     */
    @Override public List<McpPromptInfo> listPromptsForServer(String serverId) {
        McpConnectionView conn;
        try {
            conn = ensureConnected(serverId);
        } catch (McpException _) {
            return List.of();
        }
        if (!conn.hasCapability("prompts")) {
            return List.of();
        }
        try {
            JsonNode result = sendRequestWithRecovery(serverId, "prompts/list", null);
            JsonNode promptsNode = result != null ? result.get("prompts") : null;
            if (promptsNode == null || !promptsNode.isArray()) return List.of();
            List<McpPromptInfo> out = new ArrayList<>(promptsNode.size());
            for (JsonNode rawPrompt : promptsNode) {
                JsonNode p = UnicodeSanitizer.sanitize(rawPrompt);
                String name = p.path("name").asText();
                if (name.isEmpty()) continue;
                String desc = p.path("description").asText(null);
                List<McpPromptInfo.PromptArgument> args = new ArrayList<>();
                JsonNode argsNode = p.get("arguments");
                if (argsNode != null && argsNode.isArray()) {
                    // Spec-compliant array form: [{name, description?, required?}, ...]
                    for (JsonNode a : argsNode) {
                        String argName = a.path("name").asText();
                        if (argName.isEmpty()) continue;
                        args.add(new McpPromptInfo.PromptArgument(
                            argName,
                            a.path("description").asText(null),
                            a.path("required").asBoolean(false)));
                    }
                } else if (argsNode != null && argsNode.isObject()) {
                    // Some servers use the map form: {name: {description, required}}.
                    // Preserve declaration order via JsonNode field iteration.
                    Iterator<Map.Entry<String, JsonNode>> it = argsNode.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> e = it.next();
                        args.add(new McpPromptInfo.PromptArgument(
                            e.getKey(),
                            e.getValue().path("description").asText(null),
                            e.getValue().path("required").asBoolean(false)));
                    }
                }
                out.add(new McpPromptInfo(serverId, name, desc, List.copyOf(args)));
            }
            return List.copyOf(out);
        } catch (McpException e) {
            // MethodNotFound is the expected reply for servers without prompts capability.
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (Strings.CS.contains(msg, "-32601") || Strings.CI.contains(msg, "method not found")) {
                LOG.debug("Server '{}' does not expose prompts (no prompts capability)", serverId);
            } else {
                LOG.warn("Failed to list prompts from server '{}': {}", serverId, msg);
            }
            return List.of();
        }
    }

    /**
     * Renders a prompt via {@code prompts/get}.
     */
    @Override public McpPromptResult getPrompt(String serverId, String promptName,
                                      Map<String, String> arguments) {
        McpConnectionView conn = ensureConnected(serverId);
        if (!conn.hasCapability("prompts")) {
            throw new McpException("Server \"" + serverId + "\" does not support prompts");
        }
        var params = JsonUtils.getMapper().createObjectNode();
        params.put("name", promptName);
        if (arguments != null && !arguments.isEmpty()) {
            var argsNode = params.putObject("arguments");
            arguments.forEach(argsNode::put);
        }
        JsonNode result = sendRequestWithRecovery(serverId, "prompts/get", params);
        return McpPromptResult.fromJson(result);
    }

    /**
     * Invokes a tool on the specified MCP server.
     *
     * @param serverId the server to call
     * @param toolName the tool to invoke
     * @param args     the tool arguments
     * @return the tool result as JSON
     */
    @Override public JsonNode callTool(String serverId, String toolName, JsonNode args) {
        return callTool(serverId, toolName, args, null);
    }

    /**
     * Invokes a tool and carries the originating Anthropic tool-use id through MCP {@code
     * params._meta}.
     */
    @Override public JsonNode callTool(String serverId, String toolName, JsonNode args,
                             String toolUseId) {
        return callTool(serverId, toolName, args, toolUseId, null);
    }

    /**
     * Invokes a tool with the query abort signal used by the MCP SDK. SDK-hosted
     * transports use it to emit {@code notifications/cancelled}; process
     * transports retain their existing request path.
     */
    @Override public JsonNode callTool(String serverId, String toolName, JsonNode args,
                             String toolUseId, AbortController abortController) {
        return callTool(serverId, toolName, args, toolUseId, abortController, null);
    }

    /**
     * Invokes a tool and forwards server {@code notifications/progress} to the caller.
     */
    @Override public JsonNode callTool(String serverId, String toolName, JsonNode args,
                             String toolUseId, AbortController abortController,
                             Consumer<JsonNode> progressListener) {
        var params = JsonUtils.getMapper().createObjectNode();
        params.put("name", toolName);
        if (args != null) {
            params.set("arguments", args);
        }
        if (StringUtils.isNotBlank(toolUseId)) {
            ObjectNode meta = params.putObject("_meta");
            meta.put("claudecode/toolUseId", toolUseId);
        }

        Set<String> activeIds = null;
        if (StringUtils.isNotBlank(toolUseId)) {
            activeIds = activeToolUseIdsByServer.computeIfAbsent(
                serverId, _ -> ConcurrentHashMap.newKeySet());
            activeIds.add(toolUseId);
        }
        Map<String, Consumer<JsonNode>> progressListeners = null;
        if (progressListener != null && toolUseId != null && !StringUtils.isBlank(toolUseId)) {
            progressListeners = progressListenersByServer.computeIfAbsent(
                serverId, _ -> new ConcurrentHashMap<>());
            progressListeners.put(toolUseId, progressListener);
        }
        try {
            return sendToolCallWithRecovery(serverId, params, abortController, activeIds);
        } finally {
            if (activeIds != null) {
                activeIds.remove(toolUseId);
                if (activeIds.isEmpty()) {
                    activeToolUseIdsByServer.remove(serverId, activeIds);
                }
            }
            if (progressListeners != null) {
                progressListeners.remove(toolUseId, progressListener);
                if (progressListeners.isEmpty()) {
                    progressListenersByServer.remove(serverId, progressListeners);
                }
            }
        }
    }


    private JsonNode sendToolCallWithRecovery(String serverId, JsonNode params,
                                              AbortController abortController,
                                              Set<String> activeIds) {
        boolean reconnected = false;
        int elicitationAttempts = 0;
        while (true) {
            try {
                McpConnectionView connection = ensureConnectedForOperation(serverId);
                return connection.sendToolRequest(params, abortController);
            } catch (McpException failure) {
                if (!reconnected && isRecoverableSessionFailure(failure)) {
                    reconnected = true;
                    reconnectForRetry(serverId);
                    continue;
                }
                if (elicitationAttempts < 3) {
                    ElicitationHandling handling = handleUrlElicitation(
                        serverId, params.path("name").asText("unknown"), failure, activeIds);
                    if (handling.terminalResult() != null) {
                        return handling.terminalResult();
                    }
                    if (handling.handled()) {
                        elicitationAttempts++;
                        continue;
                    }
                }
                throw failure;
            }
        }
    }

    private boolean isRecoverableSessionFailure(McpException failure) {
        String message = failure.getMessage() == null
            ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        return Strings.CS.contains( message, "session") &&Strings.CS.contains( message, "expir")
            ||Strings.CS.contains( message, "transport closed")
            ||Strings.CS.contains( message, "connection closed")
            ||Strings.CS.contains( message, "-32001");
    }

    private void reconnectForRetry(String serverId) {
        resourceCache.remove(serverId);
        McpConnection current = connections.get(serverId);
        if (current == null) throw new McpException("No active connection to server '" + serverId + "'");
        McpServerConfig config = current.getConfig();
        List<McpToolInfo> retainedTools = current.getCachedTools();
        try {
            current.close();
        } catch (Exception _) {
            // The replacement connection is still authoritative.
        }
        connections.remove(serverId, current);
        connect(config, retainedTools);
    }

    private ElicitationHandling handleUrlElicitation(String serverId, String toolName,
                                                     McpException failure,
                                                     Set<String> activeIds) {
        ElicitationHandler handler = elicitationHandler;
        if (handler == null) return ElicitationHandling.NOT_HANDLED;
        String message = failure.getMessage();
        if (message == null || !Strings.CS.contains(message, "-32042")) return ElicitationHandling.NOT_HANDLED;
        JsonNode error = parseJson(message);
        JsonNode raw = error == null ? null : error.path("data").path("elicitations");
        if (raw == null || !raw.isArray() || raw.isEmpty()) return ElicitationHandling.NOT_HANDLED;
        boolean handled = false;
        for (JsonNode elicitation : raw) {

            // A malformed -32042 payload must surface as the original MCP
            // error instead of causing an unbounded retry with incomplete data.
            if (!isValidUrlElicitation(elicitation)) {
                return ElicitationHandling.NOT_HANDLED;
            }
            JsonNode response = handler.handle(serverId, elicitation,
                activeIds == null ? Set.of() : Set.copyOf(activeIds));
            handled = true;
            String action = response == null ? "cancel" : response.path("action").asText("cancel");
            if (!Strings.CS.equals("accept", action)) {
                return new ElicitationHandling(true,
                    McpElicitationResult.terminal(toolName, action));
            }
        }
        return handled ? new ElicitationHandling(true, null) : ElicitationHandling.NOT_HANDLED;
    }

    private static boolean isValidUrlElicitation(JsonNode value) {
        return value != null && value.isObject()
            &&Strings.CS.equals( "url", value.path("mode").asText())
            && value.path("url").isTextual()
            && value.path("elicitationId").isTextual()
            && value.path("message").isTextual();
    }

    private record ElicitationHandling(boolean handled, JsonNode terminalResult) {
        private static final ElicitationHandling NOT_HANDLED = new ElicitationHandling(false, null);
    }

    private static JsonNode parseJson(String message) {
        try {
            return JsonUtils.getMapper().readTree(message);
        } catch (Exception _) {
            return null;
        }
    }

    private McpConnectionView ensureConnectedForOperation(String serverId) {
        McpConnection existing = connections.get(serverId);
        if (existing == null) {
            throw new McpException("No active connection to server '" + serverId + "'");
        }
        if (existing.isConnected()) return McpConnectionView.of(existing);

        Object lock = reconnectLocks.computeIfAbsent(serverId, _ -> new Object());
        synchronized (lock) {
            McpConnection current = connections.get(serverId);
            if (current == null) {
                throw new McpException("No active connection to server '" + serverId + "'");
            }
            if (current.isConnected()) return McpConnectionView.of(current);

            McpServerConfig config = current.getConfig();
            List<McpToolInfo> retainedTools = current.getCachedTools();
            LOG.info("MCP server '{}' connection closed; reconnecting for the next operation",
                serverId);
            connect(config, retainedTools);
            McpConnection reconnected = connections.get(serverId);
            if (reconnected == null || !reconnected.isConnected()) {
                throw new McpException("No active connection to server '" + serverId + "'");
            }
            return McpConnectionView.of(reconnected);
        }
    }

    /**
     * Returns all active server IDs.
     */
    @Override public Set<String> getConnectedServerIds() {
        Set<String> ids = new HashSet<>();
        for (var entry : connections.entrySet()) {
            if (entry.getValue().isConnected()) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    /**
     * Returns server-declared instructions for configured connection entries, including an entry whose
     * raw transport closed unexpectedly and will be re-established by {@link
     * #ensureConnectedForOperation(String)}.
     */
    @Override public Map<String, String> getServerInstructions() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        connections.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                String instructions = entry.getValue().getInstructions();
                if (StringUtils.isNotBlank(instructions)) {
                    snapshot.put(entry.getKey(), instructions);
                }
            });
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Summarizes connection state for {@code /status}'s "MCP servers" row.
     */
    @Override public String connectionSummary() {
        int total = connections.size();
        if (total == 0) return "";
        long connected = connections.values().stream().filter(McpConnection::isConnected).count();
        return connected == total
            ? connected + " connected"
            : connected + " connected, " + total + " total";
    }

    @Override
    public void close() {
        for (String serverId : new ArrayList<>(connections.keySet())) {
            disconnect(serverId);
        }
    }

    // -- internal --

    /**
     * Creates a started transport and transfers ownership to {@link #connect}.
     */
    McpTransport createTransport(McpServerConfig config) {
        return switch (config.transportType()) {
            case "stdio" -> startTransport(new StdioTransport(config), StdioTransport::start);
            case "sse" -> startTransport(new SseTransport(config), SseTransport::connect);
            case "http" -> startTransport(new HttpTransport(config), HttpTransport::connect);
            case "sdk" -> {
                if (sdkMessageExchange == null) {
                    throw new McpException("SDK MCP control transport is unavailable");
                }
                yield new SdkControlTransport(config.name(), sdkMessageExchange);
            }
            default -> throw new McpException("Unknown transport type: " + config.transportType());
        };
    }

    /**
     * Starts a newly-created transport and transfers ownership to the caller only after startup
     * succeeds.
     */
    private static <T extends McpTransport> T startTransport(T transport, Consumer<T> starter) {
        try {
            starter.accept(transport);
            return transport;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(transport, failure);
            throw failure;
        }
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private List<McpToolInfo> discoverTools(McpConnection conn) {
        JsonNode result = conn.getTransport().sendRequest("tools/list", null);
        List<McpToolInfo> tools = new ArrayList<>();

        JsonNode toolsNode = result.get("tools");
        if (toolsNode != null && toolsNode.isArray()) {
            for (JsonNode rawToolNode : toolsNode) {
                JsonNode toolNode = UnicodeSanitizer.sanitize(rawToolNode);
                String name = toolNode.has("name") ? toolNode.get("name").asText() : "";
                String desc = toolNode.has("description") ? toolNode.get("description").asText() : "";
                desc = McpUtils.truncateDescription(desc);
                JsonNode schema = toolNode.has("inputSchema") ? toolNode.get("inputSchema") : JsonUtils.getMapper().createObjectNode();
                JsonNode annotations = toolNode.has("annotations")
                    ? toolNode.get("annotations") : JsonUtils.getMapper().createObjectNode();
                JsonNode meta = toolNode.has("_meta")
                    ? toolNode.get("_meta") : JsonUtils.getMapper().createObjectNode();
                tools.add(new McpToolInfo(
                    conn.getServerId(), name, desc, schema, annotations, meta));
            }
        }
        return tools;
    }
}
