package com.claudecode.tools.mcp;



import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.McpClientRuntime;
import com.claudecode.mcp.McpClientManager;
import com.claudecode.mcp.McpFailures;
import com.claudecode.mcp.McpTimeouts;
import com.claudecode.mcp.McpConfig;
import com.claudecode.mcp.McpConfigLoader;
import com.claudecode.mcp.McpConfigWriter;
import com.claudecode.mcp.McpConnectionView;
import com.claudecode.mcp.McpServerScope;
import com.claudecode.mcp.oauth.McpOAuthProvider;
import com.claudecode.mcp.McpPromptInfo;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpToolInfo;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.skills.McpSkillDiscovery;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers and registers MCP tools into the tool registry.
 */
public class McpToolProvider implements ManagedMcpRuntime, WaitForMcpServersTool.Controller {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolProvider.class);


    private static final long DEFAULT_WAIT_TIMEOUT_MS = 5_000;
    private static final Set<String> RESOURCE_HELPER_TOOL_NAMES = Set.of(
        "ListMcpResourcesTool", "ReadMcpResourceDirTool", "ReadMcpResourceTool");

    private final McpClientManager clientManager;
    private final long waitTimeoutMs;
    private final Map<String, CompletableFuture<ServerState>> attempts = new ConcurrentHashMap<>();
    private final Set<String> configuredNames = ConcurrentHashMap.newKeySet();
    private final List<String> configuredOrder = new CopyOnWriteArrayList<>();
    private final Set<String> sdkServerNames = ConcurrentHashMap.newKeySet();
    private final Set<String> disabledNames = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Skill>> mcpSkillsByServer = new ConcurrentHashMap<>();
    private final Map<String, List<McpPromptInfo>> promptsByServer = new ConcurrentHashMap<>();
    private final Map<String, List<ToolDisplaySnapshot>> toolDisplaysByServer =
        new ConcurrentHashMap<>();
    private final Map<String, McpServerConfig> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, McpServerScope> serverScopes = new ConcurrentHashMap<>();
    private final Map<String, String> serverErrors = new ConcurrentHashMap<>();
    /**
     * OAuth is cold on ordinary launches.
     */
    private volatile McpOAuthProvider oauthProvider;
    private final Map<String, OAuthFlow> oauthFlows = new ConcurrentHashMap<>();
    private volatile boolean mcpSkillsEnabled;
    private volatile SkillLoader mcpSkillLoader;
    private volatile Path mcpSkillCacheHome;
    private boolean initialized = false;
    private volatile Path projectDir;
    private volatile ToolRegistry toolRegistry;

    private enum ServerState { CONNECTED, FAILED, NEEDS_AUTH }

    /**
     * Completes once every configured server has either connected or
     * conclusively failed/timed out. Callers that need the full tool/prompt
     * set (e.g. the CLI's prompt→slash-command sync) chain on this instead
     * of assuming connections exist when {@link #initialize} returns.
     */
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    /** See {@link #ready}. Already-completed future when no servers are configured. */
    @Override public CompletableFuture<Void> whenReady() {
        return ready;
    }

    /** Non-blocking status snapshot for SDK {@code mcp_status}. */
    @Override public Map<String, String> snapshotServerStatuses() {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(configuredOrder);
        configuredNames.stream().filter(name -> !names.contains(name)).sorted().forEach(names::add);
        for (String name : names) {
            if (disabledNames.contains(name)) {
                result.put(name, "disabled");
                continue;
            }
            CompletableFuture<ServerState> attempt = attempts.get(name);
            if (attempt == null || !attempt.isDone()) {
                result.put(name, "pending");
                continue;
            }
            result.put(name, switch (attempt.getNow(ServerState.FAILED)) {
                case CONNECTED -> "connected";
                case FAILED -> "failed";
                case NEEDS_AUTH -> "needs-auth";
            });
        }
        // Map.copyOf does not preserve the source map's encounter order. SDK

        // merged MCP config's first-insertion order (including overridden
        // entries), so retain the LinkedHashMap iteration contract.
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the names of servers whose initial connection is still pending.
     */
    @Override public List<String> pendingServerNames() {
        return snapshotServerStatuses().entrySet().stream()
            .filter(entry -> Strings.CS.equals("pending", entry.getValue()))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }


    @Override public List<ServerStatusSnapshot> snapshotServerDetails() {
        List<ServerStatusSnapshot> result = new ArrayList<>();
        for (String name : configuredNames.stream().sorted().toList()) {
            String status = snapshotStatus(name);
            McpServerConfig serverConfig = serverConfigs.get(name);
            boolean sdkHosted = sdkServerNames.contains(name);
            JsonNode serverInfo = null;
            List<ServerToolStatus> tools = null;
            JsonNode capabilities = null;
            if (Strings.CS.equals("connected", status)) {
                var connection = clientManager.borrowConnection(name);
                if (connection.isPresent()) {
                    var connected = connection.get();
                    if (!sdkHosted) serverInfo = connected.getServerInfo();
                    capabilities = connected.getServerCapabilities();
                    tools = clientManager.listToolsForServer(name).stream()
                        .map(McpToolProvider::statusTool)
                        .toList();
                }
            }
            String error = Strings.CS.equals("failed", status) ? serverErrors.get(name) : null;
            McpServerScope scope = serverScopes.getOrDefault(name, McpServerScope.DYNAMIC);
            result.add(new ServerStatusSnapshot(
                name,
                status,
                serverInfo,
                sdkHosted ? null : statusConfig(serverConfig),
                scope.name().toLowerCase(Locale.ROOT),
                tools,
                capabilities,
                error));
        }
        return List.copyOf(result);
    }

    /** Metadata attached to SDK assistant events for MCP tool-use rendering. */
    @Override public List<ToolDisplaySnapshot> snapshotToolDisplays() {
        List<ToolDisplaySnapshot> result = new ArrayList<>();
        for (String serverName : configuredNames.stream().sorted().toList()) {
            if (!Strings.CS.equals("connected", snapshotStatus(serverName))) continue;
            result.addAll(toolDisplaysByServer.getOrDefault(serverName, List.of()));
        }
        return List.copyOf(result);
    }

    /** Refreshes the stable SDK display catalogue after tools/list_changed. */
    @Override public void refreshToolDisplays(String serverName, List<McpToolInfo> tools) {
        cacheToolDisplays(serverName, tools != null ? tools : List.of());
    }

    private void cacheToolDisplays(String serverName, List<McpToolInfo> tools) {
        JsonNode serverInfo = clientManager.borrowConnection(serverName)
            .map(McpConnectionView::getServerInfo)
            .orElse(null);
        String fallback = toolDisplaysByServer.getOrDefault(serverName, List.of()).stream()
            .findFirst().map(ToolDisplaySnapshot::serverDisplayName).orElse(serverName);
        String serverDisplayName = sdkServerNames.contains(serverName) ? serverName : serverInfo != null
            ? serverInfo.path("name").asText(fallback) : fallback;
        List<ToolDisplaySnapshot> displays = tools.stream().map(tool -> {
            String title = tool.annotations().path("title").asText(null);
            String displayName = StringUtils.isNotBlank(title)
                ? title : humanizeToolName(tool.name());
            return new ToolDisplaySnapshot(
                "mcp__" + serverName + "__" + tool.name(),
                displayName,
                serverDisplayName);
        }).toList();
        toolDisplaysByServer.put(serverName, List.copyOf(displays));
    }

    private static String humanizeToolName(String name) {
        String[] words = name.replace('-', ' ').replace('_', ' ').trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) out.append(word.substring(1));
        }
        return out.toString();
    }

    private String snapshotStatus(String name) {
        if (disabledNames.contains(name)) return "disabled";
        CompletableFuture<ServerState> attempt = attempts.get(name);
        if (attempt == null || !attempt.isDone()) return "pending";
        return switch (attempt.getNow(ServerState.FAILED)) {
            case CONNECTED -> "connected";
            case FAILED -> "failed";
            case NEEDS_AUTH -> "needs-auth";
        };
    }

    private static ObjectNode statusConfig(McpServerConfig config) {
        if (config == null) return null;
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        switch (config.transportType()) {
            case "sse", "http" -> {
                out.put("type", config.transportType());
                if (config.url() != null) out.put("url", config.url());
                if (!config.headers().isEmpty()) {
                    out.set("headers", JsonUtils.getMapper().valueToTree(config.headers()));
                }
            }
            case "sdk" -> {
                out.put("type", "sdk");
                out.put("name", config.name());
            }
            default -> {
                out.put("type", "stdio");
                out.put("command", config.command());
                ArrayNode args = out.putArray("args");
                config.args().forEach(args::add);
            }
        }
        return out;
    }

    private static ServerToolStatus statusTool(McpToolInfo tool) {
        ObjectNode annotations = JsonUtils.getMapper().createObjectNode();
        JsonNode raw = tool.annotations();
        if (raw.path("readOnlyHint").asBoolean(false)) annotations.put("readOnly", true);
        if (raw.path("destructiveHint").asBoolean(false)) annotations.put("destructive", true);
        if (raw.path("openWorldHint").asBoolean(false)) annotations.put("openWorld", true);
        return new ServerToolStatus(tool.name(), annotations);
    }

    public record ServerStatusSnapshot(
        String name,
        String status,
        JsonNode serverInfo,
        JsonNode config,
        String scope,
        List<ServerToolStatus> tools,
        JsonNode capabilities,
        String error
    ) {}

    public record ServerToolStatus(String name, JsonNode annotations) {}

    public record ToolDisplaySnapshot(
        String toolName,
        String displayName,
        String serverDisplayName
    ) {}

    public McpToolProvider() {
        this(new McpClientManager(), DEFAULT_WAIT_TIMEOUT_MS);
    }

    public McpToolProvider(McpClientManager clientManager) {
        this(clientManager, DEFAULT_WAIT_TIMEOUT_MS);
    }

    McpToolProvider(McpClientManager clientManager, long waitTimeoutMs) {
        this.clientManager = clientManager;
        this.waitTimeoutMs = waitTimeoutMs;
    }


    @Override public void configureMcpSkills(boolean enabled, SkillLoader loader, Path claudeHome) {
        if (initialized) throw new IllegalStateException("MCP provider already initialized");
        this.mcpSkillsEnabled = enabled;
        this.mcpSkillLoader = loader;
        this.mcpSkillCacheHome = claudeHome;
    }

    /**
     * Returns the underlying MCP client manager so UI components (e.g. the
     * {@code /mcp} dialog) can drive reconnect + tool discovery without
     * spinning up a second instance.
     */
    McpClientManager clientManager() {
        return clientManager;
    }

    /**
     * Returns the non-owning client operations view for session consumers.
     * Connection-manager shutdown remains this provider's responsibility.
     */
    @Override
    public McpClientRuntime clientRuntime() {
        return clientManager;
    }

    public void initialize(Path projectDir, ToolRegistry registry) {
        try {
            initialize(McpConfigLoader.loadConfig(projectDir), projectDir, registry);
        } catch (Exception e) {
            LOG.error("Failed to initialize MCP tools", e);
            ready.complete(null);
        }
    }

    /**
     * Initializes from an already-resolved configuration, used by CLI flags
     * such as {@code --mcp-config} that must override the normal file cascade.
     */
    @Override public void initialize(McpConfig config, Path projectDir, ToolRegistry registry) {
        initializeResolved(config, projectDir, registry);
    }

    /** Package-visible configuration entry point for deterministic lifecycle tests. */
    void initialize(McpConfig config, ToolRegistry registry) {
        initializeResolved(config, null, registry);
    }

    private void initializeResolved(McpConfig config, Path projectDir, ToolRegistry registry) {
        if (initialized) {
            LOG.warn("McpToolProvider already initialized");
            return;
        }
        initialized = true;
        this.projectDir = projectDir;
        this.toolRegistry = registry;

        try {
            configuredNames.addAll(config.servers().keySet());
            configuredOrder.addAll(config.servers().keySet());
            serverConfigs.putAll(config.servers());
            config.servers().keySet().forEach(name ->
                serverScopes.put(name, config.scopeOf(name)));
            config.servers().values().stream()
                .filter(McpServerConfig::disabled)
                .map(McpServerConfig::name)
                .forEach(disabledNames::add);
            registry.register(new WaitForMcpServersTool(this));

            List<McpServerConfig> servers = config.servers().values().stream()
                .filter(c -> !c.disabled())
                .toList();
            if (servers.isEmpty()) {
                LOG.info("MCP tools initialized: no servers configured");
                ready.complete(null);
                return;
            }

            long timeoutMs = McpTimeouts.connectionTimeoutMillis();
            List<CompletableFuture<ServerState>> connects = new ArrayList<>(servers.size());
            for (McpServerConfig serverConfig : servers) {
                CompletableFuture<ServerState> attempt = CompletableFuture
                    .supplyAsync(() -> {
                        connectAndRegister(serverConfig, registry);
                        return ServerState.CONNECTED;
                    },
                        r -> Thread.ofVirtual()
                            .name("mcp-connect-" + serverConfig.name()).start(r))
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .handle((state, error) -> error == null
                        ? state
                        : onConnectFailure(serverConfig, error, timeoutMs));
                attempts.put(serverConfig.name(), attempt);
                connects.add(attempt);
            }
            CompletableFuture.allOf(connects.toArray(CompletableFuture[]::new))
                .whenComplete((_, _) -> {
                    LOG.info("MCP tools initialized: {} servers connected",
                        clientManager.getConnectedServerIds().size());
                    ready.complete(null);
                });
        } catch (Exception e) {
            LOG.error("Failed to initialize MCP tools", e);
            ready.complete(null);
        }
    }

    /** Reconnects one configured server and atomically refreshes its tool set. */
    @Override public synchronized void reconnectServer(String serverName) {
        McpServerConfig config = serverConfigs.get(serverName);
        if (config == null) throw new IllegalArgumentException("Server not found: " + serverName);
        if (disabledNames.contains(serverName) || config.disabled()) {
            throw new IllegalStateException("Server status: disabled");
        }
        disconnectAndUnregister(serverName);
        try {
            connectAndRegister(config, requireRegistry());
            serverErrors.remove(serverName);
            attempts.put(serverName, CompletableFuture.completedFuture(ServerState.CONNECTED));
        } catch (RuntimeException error) {
            serverErrors.put(serverName, errorMessage(error));
            attempts.put(serverName, CompletableFuture.completedFuture(
                McpFailures.isAuthenticationFailure(error) ? ServerState.NEEDS_AUTH : ServerState.FAILED));
            if (McpFailures.isAuthenticationFailure(error)) {
                registerAuthenticationTool(config);
            }
            throw error;
        }
    }

    /** Enables/disables a server, persisting non-dynamic state like the TUI. */
    @Override public synchronized void toggleServer(String serverName, boolean enabled) {
        McpServerConfig current = serverConfigs.get(serverName);
        if (current == null) throw new IllegalArgumentException("Server not found: " + serverName);
        McpServerScope scope = serverScopes.getOrDefault(serverName, McpServerScope.DYNAMIC);
        if (scope != McpServerScope.DYNAMIC) {
            try {
                McpConfigWriter.setDisabled(scope, projectDir, serverName, !enabled);
            } catch (Exception error) {
                throw new IllegalStateException(error.getMessage(), error);
            }
        }
        McpServerConfig updated = withDisabled(current, !enabled);
        serverConfigs.put(serverName, updated);
        if (!enabled) {
            disabledNames.add(serverName);
            attempts.remove(serverName);
            disconnectAndUnregister(serverName);
        } else {
            disabledNames.remove(serverName);
            reconnectServer(serverName);
        }
    }

    /** Clears stored OAuth/DCR credentials and reconnects in the background. */
    @Override public synchronized void clearServerAuth(String serverName) {
        McpServerConfig config = serverConfigs.get(serverName);
        if (config == null) throw new IllegalArgumentException("Server not found: " + serverName);
        if (!Strings.CS.equals("sse", config.transportType()) && !Strings.CS.equals("http", config.transportType())) {
            throw new IllegalArgumentException(
                "Cannot clear auth for server type \"" + config.transportType() + "\"");
        }
        oauthProvider().clearAuth(config);
        disconnectAndUnregister(serverName);
        startDynamicConnect(config);
    }

    /** Starts headless SDK OAuth without opening a browser in the CLI process. */
    @Override public synchronized AuthStart authenticateServer(String serverName) {
        McpServerConfig config = serverConfigs.get(serverName);
        if (config == null) throw new IllegalArgumentException("Server not found: " + serverName);
        if (!Strings.CS.equals("sse", config.transportType()) && !Strings.CS.equals("http", config.transportType())) {
            throw new IllegalArgumentException("Server type \"" + config.transportType()
                + "\" does not support OAuth authentication");
        }
        OAuthFlow previous = oauthFlows.remove(serverName);
        if (previous != null) previous.pending().cancel();
        McpOAuthProvider.PendingAuth pending = oauthProvider().startAuthentication(config, false);
        OAuthFlow flow = new OAuthFlow(pending);
        oauthFlows.put(serverName, flow);
        pending.completion().whenComplete((_, error) -> {
            // Both the browser loopback callback and the SDK/pasted callback
            // complete the same PendingAuth future.  Once either path has
            // exchanged the code, clear the pseudo-tool and reconnect so the
            // discovered MCP tools replace it on the next turn.  The old
            // `manual` guard skipped this branch for pasted callbacks, leaving
            // a successfully authenticated server stuck behind its auth tool.
            if (oauthFlows.remove(serverName, flow) && error == null
                    && !disabledNames.contains(serverName)) {
                startDynamicConnect(config);
            }
        });
        return new AuthStart(
            pending.authUrl(),
            true,
            true,
            "localhost",
            pending.state(),
            pending.callbackPort());
    }

    /** Supplies the full pasted redirect URL and waits for token exchange. */
    @Override public void submitServerAuthCallback(String serverName, String callbackUrl) {
        OAuthFlow flow = oauthFlows.get(serverName);
        if (flow == null) throw new IllegalStateException(
            "No active OAuth flow for server: " + serverName);
        flow.pending().submitCallbackUrl(callbackUrl);
        try {
            flow.pending().completion().join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw error;
        }
    }

    public record AuthStart(String authUrl, boolean requiresUserAction,
                            boolean callbackExpected, String redirectScheme,
                            String state, int callbackPort) {}
    private record OAuthFlow(McpOAuthProvider.PendingAuth pending) {}

    private McpOAuthProvider oauthProvider() {
        McpOAuthProvider current = oauthProvider;
        if (current != null) return current;
        synchronized (this) {
            current = oauthProvider;
            if (current == null) {
                current = new McpOAuthProvider();
                oauthProvider = current;
            }
            return current;
        }
    }

    /**
     * Replaces initialize-time SDK-hosted servers without touching process
     * dynamic servers. Runs on the turn thread so the stdin reader remains
     * available to resolve the bidirectional MCP control requests.
     */
    @Override public synchronized void setSdkServers(List<String> requestedNames) {
        LinkedHashSet<String> desired = new LinkedHashSet<>(
            requestedNames != null ? requestedNames : List.of());

        for (String name : List.copyOf(sdkServerNames)) {
            if (desired.contains(name)) continue;
            disconnectAndUnregister(name);
            attempts.remove(name);
            configuredNames.remove(name);
            configuredOrder.remove(name);
            disabledNames.remove(name);
            serverConfigs.remove(name);
            serverScopes.remove(name);
            serverErrors.remove(name);
            sdkServerNames.remove(name);
        }

        for (String name : desired) {
            CompletableFuture<ServerState> existing = attempts.get(name);
            if (sdkServerNames.contains(name) && existing != null && existing.isDone()
                    && existing.getNow(ServerState.FAILED) == ServerState.CONNECTED
                    && clientManager.borrowConnection(name).isPresent()) {
                continue;
            }
            if (sdkServerNames.contains(name)) {
                disconnectAndUnregister(name);
            }
            McpServerConfig config = new McpServerConfig(
                name, null, List.of(), Map.of(), false, "sdk", null, Map.of());
            sdkServerNames.add(name);
            configuredNames.add(name);
            if (!configuredOrder.contains(name)) configuredOrder.add(name);
            serverConfigs.put(name, config);
            serverScopes.put(name, McpServerScope.DYNAMIC);
            disabledNames.remove(name);

            CompletableFuture<ServerState> attempt = new CompletableFuture<>();
            attempts.put(name, attempt);
            try {
                connectAndRegister(config, requireRegistry());
                attempt.complete(ServerState.CONNECTED);
            } catch (RuntimeException error) {
                serverErrors.put(name, errorMessage(error));
                attempt.complete(McpFailures.isAuthenticationFailure(error)
                    ? ServerState.NEEDS_AUTH : ServerState.FAILED);
            }
        }
    }

    /**
     * Replaces only process-transport dynamic servers, preserving SDK/file/plugin configuration.
     */
    @Override public DynamicServerUpdate setDynamicServers(Map<String, McpServerConfig> requested) {
        Map<String, McpServerConfig> desired = requested != null ? requested : Map.of();
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<PendingDynamicConnect> pending = new ArrayList<>();

        synchronized (this) {
            List<String> currentDynamic = serverScopes.entrySet().stream()
                .filter(entry -> entry.getValue() == McpServerScope.DYNAMIC)
                .filter(entry -> !sdkServerNames.contains(entry.getKey()))
                .map(Map.Entry::getKey).toList();
            for (String name : currentDynamic) {
                McpServerConfig next = desired.get(name);
                if (next != null && next.equals(serverConfigs.get(name))) continue;
                disconnectAndUnregister(name);
                attempts.remove(name);
                configuredNames.remove(name);
                configuredOrder.remove(name);
                disabledNames.remove(name);
                serverConfigs.remove(name);
                serverScopes.remove(name);
                removed.add(name);
            }
            for (McpServerConfig config : desired.values()) {
                if (config.equals(serverConfigs.get(config.name()))) continue;
                configuredNames.add(config.name());
                if (!configuredOrder.contains(config.name())) configuredOrder.add(config.name());
                serverConfigs.put(config.name(), config);
                serverScopes.put(config.name(), McpServerScope.DYNAMIC);
                if (config.disabled()) {
                    disabledNames.add(config.name());
                    added.add(config.name());
                    continue;
                }
                disabledNames.remove(config.name());
                pending.add(new PendingDynamicConnect(config.name(), startDynamicConnect(config)));
                added.add(config.name());
            }
        }

        Map<String, String> errors = new LinkedHashMap<>();
        for (PendingDynamicConnect connection : pending) {
            ServerState state = connection.attempt().join();

            if (state == ServerState.FAILED) {
                errors.put(connection.serverName(), serverErrors.getOrDefault(
                    connection.serverName(), "Connection failed"));
            }
        }
        return new DynamicServerUpdate(List.copyOf(added), List.copyOf(removed), Map.copyOf(errors));
    }

    public record DynamicServerUpdate(List<String> added, List<String> removed,
                                      Map<String, String> errors) {}

    private record PendingDynamicConnect(String serverName,
                                         CompletableFuture<ServerState> attempt) {}

    private ToolRegistry requireRegistry() {
        if (toolRegistry == null) throw new IllegalStateException("MCP tool registry is unavailable");
        return toolRegistry;
    }

    private CompletableFuture<ServerState> startDynamicConnect(McpServerConfig config) {
        long timeoutMs = McpTimeouts.connectionTimeoutMillis();
        CompletableFuture<ServerState> attempt = CompletableFuture
            .supplyAsync(() -> {
                connectAndRegister(config, requireRegistry());
                return ServerState.CONNECTED;
            }, command -> Thread.ofVirtual()
                .name("mcp-dynamic-" + config.name()).start(command))
            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .handle((state, error) -> error == null ? state
                : onConnectFailure(config, error, timeoutMs));
        attempts.put(config.name(), attempt);
        return attempt;
    }

    private void disconnectAndUnregister(String serverName) {
        try {
            clientManager.disconnect(serverName);
        } catch (RuntimeException _) { }
        if (toolRegistry != null) {
            String prefix = "mcp__" + serverName + "__";
            toolRegistry.unregisterMatching(name -> Strings.CS.startsWith(name, prefix));
        }
        mcpSkillsByServer.remove(serverName);
        promptsByServer.remove(serverName);
        toolDisplaysByServer.remove(serverName);
        refreshResourceHelperTools();
    }

    private static McpServerConfig withDisabled(McpServerConfig config, boolean disabled) {
        return new McpServerConfig(config.name(), config.command(), config.args(), config.env(),
            disabled, config.transportType(), config.url(), config.headers());
    }

    /**
     * Failure tail of one async connect. On timeout the server is disconnected
     * so its transport closes — that unblocks the connect thread still parked
     * on the handshake read (stdio {@code readLine} has no timeout of its own).
     */
    private ServerState onConnectFailure(McpServerConfig config, Throwable e, long timeoutMs) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null
            ? e.getCause() : e;
        serverErrors.put(config.name(), errorMessage(cause));
        if (cause instanceof TimeoutException) {
            LOG.warn("MCP server '{}' did not connect within {}ms — disconnecting",
                config.name(), timeoutMs);
            try {
                clientManager.disconnect(config.name());
            } catch (Exception _) { }
        } else {
            LOG.warn("Failed to connect to MCP server '{}': {}",
                config.name(), cause.getMessage());
        }
        if (McpFailures.isAuthenticationFailure(cause)) {
            registerAuthenticationTool(config);
            return ServerState.NEEDS_AUTH;
        }
        return ServerState.FAILED;
    }


    private void registerAuthenticationTool(McpServerConfig config) {
        if (toolRegistry == null || config == null) return;
        if (!Strings.CS.equals("sse", config.transportType())
                && !Strings.CS.equals("http", config.transportType())) {
            return;
        }
        String prefix = "mcp__" + config.name() + "__";
        toolRegistry.unregisterMatching(name -> Strings.CS.startsWith(name, prefix));
        toolRegistry.register(new McpAuthTool(config.name(), config, this));
        LOG.debug("Registered MCP authentication pseudo-tool for server '{}'", config.name());
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return StringUtils.isNotBlank(message)
            ? message : error.getClass().getSimpleName();
    }

    /** True only during the interval in which at least one enabled server is connecting. */
    @Override
    public boolean hasPendingServers() {
        return attempts.values().stream().anyMatch(attempt -> !attempt.isDone());
    }

    /**
     * Waits up to five seconds for the selected servers, then snapshots every requested name into the
     * exact.
     */
    @Override
    public WaitForMcpServersTool.WaitResult waitForServers(List<String> servers) {
        List<String> targets;
        if (servers == null || servers.isEmpty()) {
            targets = attempts.entrySet().stream()
                .filter(entry -> !entry.getValue().isDone())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        } else {
            targets = List.copyOf(new LinkedHashSet<>(servers));
        }

        List<CompletableFuture<ServerState>> pending = targets.stream()
            .map(attempts::get)
            .filter(Objects::nonNull)
            .filter(attempt -> !attempt.isDone())
            .toList();
        if (!pending.isEmpty()) {
            try {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(waitTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException _) {
                // Expected: unresolved names are reported in stillPending below.
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException _) {
                // Attempts normalize failures into ServerState, so this is defensive only.
            }
        }

        List<String> connected = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> stillPending = new ArrayList<>();
        List<String> needsAuth = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String target : targets) {
            if (!configuredNames.contains(target)) {
                unknown.add(target);
                continue;
            }
            if (disabledNames.contains(target)) {
                disabled.add(target);
                continue;
            }
            CompletableFuture<ServerState> attempt = attempts.get(target);
            if (attempt == null || !attempt.isDone()) {
                stillPending.add(target);
                continue;
            }
            switch (attempt.getNow(ServerState.FAILED)) {
                case CONNECTED -> connected.add(target);
                case FAILED -> failed.add(target);
                case NEEDS_AUTH -> needsAuth.add(target);
            }
        }

        boolean resultReady = failed.isEmpty() && stillPending.isEmpty()
            && needsAuth.isEmpty() && disabled.isEmpty() && unknown.isEmpty();
        return new WaitForMcpServersTool.WaitResult(
            resultReady, connected, failed, stillPending, needsAuth, disabled, unknown);
    }

    private void connectAndRegister(McpServerConfig config, ToolRegistry registry) {
        if (config.disabled()) {
            LOG.debug("MCP server '{}' is disabled", config.name());
            return;
        }

        clientManager.connect(config);
// listTools connects and discovers tools
        List<McpToolInfo> tools = clientManager.listToolsForServer(config.name());
        cacheToolDisplays(config.name(), tools);

        for (McpToolInfo toolInfo : tools) {
            MCPTool tool = new MCPTool(toolInfo, clientManager);
            registry.register(tool);
            LOG.debug("Registered MCP tool: {}", tool.name());
        }

        promptsByServer.put(config.name(), List.copyOf(
            clientManager.listPromptsForServer(config.name())));

        clientManager.borrowConnection(config.name()).ifPresent(connection -> {
            boolean supportsResources = connection.hasCapability("resources");
            Runnable resourcePrefetch = supportsResources
                ? () -> prefetchResources(config.name())
                : () -> { };
            if (mcpSkillsEnabled && mcpSkillLoader != null && mcpSkillCacheHome != null) {
                List<Skill> discovered = new McpSkillDiscovery(mcpSkillCacheHome)
                    .fetch(connection, resourcePrefetch);
                mcpSkillsByServer.put(config.name(), discovered);
                List<Skill> all = mcpSkillsByServer.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .flatMap(entry -> entry.getValue().stream())
                    .toList();
                mcpSkillLoader.setMcpSkills(all);
            } else {
                resourcePrefetch.run();
            }
        });

        refreshResourceHelperTools();

        LOG.info("Connected to MCP server '{}' with {} tools",
            config.name(), tools.size());
        serverErrors.remove(config.name());
    }

    private void prefetchResources(String serverId) {
        try {
            clientManager.listResourcesForServer(serverId);
        } catch (RuntimeException e) {

            LOG.warn("Failed to prefetch MCP resources from '{}'", serverId, e);
        }
    }

    /**
     * Keeps the process-wide MCP resource helpers in lockstep with live server capabilities.
     */
    private synchronized void refreshResourceHelperTools() {
        if (toolRegistry == null) return;
        boolean supportsResources = clientManager.getConnectedServerIds().stream()
            .map(clientManager::borrowConnection)
            .flatMap(Optional::stream)
            .anyMatch(connection -> connection.hasCapability("resources"));
        if (supportsResources) {
            toolRegistry.register(new ListMcpResourcesTool(clientManager));
            toolRegistry.register(new ReadMcpResourceDirTool(
                clientManager, mcpSkillsEnabled));
            toolRegistry.register(new ReadMcpResourceTool(clientManager));
            return;
        }
        toolRegistry.unregisterMatching(RESOURCE_HELPER_TOOL_NAMES::contains);
    }

    /**
     * Fetches every connected server's {@code prompts/list} and passes each {@link McpPromptInfo} to
     * {@code sink}.
     */
    @Override public void syncPromptsToRegistry(Consumer<McpPromptInfo> sink) {
        if (sink == null) return;
        int count = 0;
        Set<String> connected = clientManager.getConnectedServerIds();
        promptsByServer.keySet().removeIf(serverId -> !connected.contains(serverId));
        for (String serverId : connected.stream().sorted().toList()) {
            List<McpPromptInfo> prompts = promptsByServer.getOrDefault(serverId, List.of());
            for (McpPromptInfo p : prompts) {
                sink.accept(p);
                count++;
            }
        }
        LOG.debug("Synced {} MCP prompts to command registry", count);
    }

    /** Stable live snapshot used by SDK {@code system/init} and command refreshes. */
    @Override public List<String> promptCommandNames() {
        return promptsByServer.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .flatMap(entry -> entry.getValue().stream())
            .map(McpPromptInfo::commandName)
            .toList();
    }

    /**
     * Returns the client manager for direct access if needed.
     */
    McpClientManager getClientManager() {
        return clientManager;
    }

    /**
     * Shuts down all MCP connections.
     */
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        clientManager.close();
        if (toolRegistry != null) {
            toolRegistry.unregisterMatching(RESOURCE_HELPER_TOOL_NAMES::contains);
        }
        attempts.clear();
        configuredNames.clear();
        configuredOrder.clear();
        sdkServerNames.clear();
        disabledNames.clear();
        mcpSkillsByServer.clear();
        promptsByServer.clear();
        toolDisplaysByServer.clear();
        serverConfigs.clear();
        serverScopes.clear();
        serverErrors.clear();
        oauthFlows.values().forEach(flow -> flow.pending().cancel());
        oauthFlows.clear();
        if (mcpSkillLoader != null) mcpSkillLoader.setMcpSkills(List.of());
        initialized = false;
    }
}
