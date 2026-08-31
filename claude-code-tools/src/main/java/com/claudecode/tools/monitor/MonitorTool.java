package com.claudecode.tools.monitor;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SandboxDecision;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.tasks.MonitorCommandTask;
import com.claudecode.tools.tasks.MonitorTaskHandle;
import com.claudecode.tools.tasks.MonitorWebSocketTask;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.platform.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import com.claudecode.tools.bash.BashPermissions;
import com.claudecode.tools.sandbox.PlatformSandboxManager;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolHttpClient;
import com.claudecode.tools.ValidationResult;

/**
 * Starts a background event monitor over a shell command or WebSocket.
 */
@BuiltInTool(
    name = "Monitor",
    shouldDefer = true,
    concurrencySafe = true,
    maxResultSizeChars = 10_000
)
public final class MonitorTool extends AnnotatedTool<JsonNode, StructuredToolOutput> {

    private static final long DEFAULT_TIMEOUT_MS = 300_000;
    private static final long MAX_TIMEOUT_MS = 3_600_000;
    private static final long REMOTE_TIMEOUT_MS = 1_800_000;
    private static final JsonNode SCHEMA = buildSchema();

    private final BooleanSupplier enabled;
    private final SandboxManager sandboxManager;
    private final TaskRegistry registry;
    private final OkHttpClient httpClient;

    public MonitorTool() {
        this(() -> MonitorFeatureGate.systemEnabled()
                && (!Platform.IS_WINDOWS || ExecutableFinder.find("bash").isPresent()),
            PlatformSandboxManager.create(), TaskRegistry.global());
    }

    MonitorTool(BooleanSupplier enabled, SandboxManager sandboxManager,
                TaskRegistry registry) {
        this(enabled, sandboxManager, registry, ToolHttpClient.standard().newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build());
    }

    MonitorTool(BooleanSupplier enabled, SandboxManager sandboxManager,
                TaskRegistry registry, OkHttpClient httpClient) {
        this.enabled = enabled;
        this.sandboxManager = sandboxManager;
        this.registry = registry;
        this.httpClient = httpClient;
    }

    @Override
    public String description() {
        return ToolTexts.description("Monitor");
    }

    @Override public JsonNode inputSchema() { return SCHEMA; }

    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        boolean command = input.has("command") && input.get("command").isTextual()
            && !StringUtils.isBlank(input.get("command").asText());
        boolean websocket = input.has("ws") && input.get("ws").isObject();
        if (command == websocket) {
            return ValidationResult.invalid(
                "Provide exactly one Monitor source: command or ws.");
        }
        boolean persistent = input.path("persistent").asBoolean(false);
        double timeout = input.path("timeout_ms").asDouble(DEFAULT_TIMEOUT_MS);
        if (!Double.isFinite(timeout) || timeout < 1_000) {
            return ValidationResult.invalid("timeout_ms must be at least 1000ms.");
        }
        if (!persistent && timeout > MAX_TIMEOUT_MS) {
            return ValidationResult.invalid(
                "timeout_ms must not exceed 3600000ms unless persistent is true.");
        }
        if (websocket) {
            JsonNode ws = input.get("ws");
            String rawUrl = ws.path("url").asText("");
            if (!isApprovalSafe(rawUrl) || !isAscii(rawUrl)
                    || rawUrl.indexOf('\t') >= 0 || rawUrl.indexOf('\n') >= 0
                    || rawUrl.indexOf('\r') >= 0) {
                return ValidationResult.invalid(
                    "url must be a valid ASCII ws:// or wss:// URL with no userinfo or whitespace");
            }
            try {
                URI uri = URI.create(rawUrl);
                String scheme = uri.getScheme();
                if (scheme == null || uri.getHost() == null
                        || !(Strings.CI.equals(scheme, "ws") || Strings.CI.equals(scheme, "wss"))
                        || uri.getRawUserInfo() != null) {
                    return ValidationResult.invalid(
                        "ws.url must be an absolute ws:// or wss:// URL without credentials.");
                }
            } catch (IllegalArgumentException _) {
                return ValidationResult.invalid("ws.url is not a valid WebSocket URL.");
            }
            if (ws.has("protocols")) {
                if (!ws.get("protocols").isArray()) {
                    return ValidationResult.invalid("ws.protocols must be an array.");
                }
                HashSet<String> unique = new HashSet<>();
                for (JsonNode protocol : ws.get("protocols")) {
                    String value = protocol.asText("");
                    if (!value.matches("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")) {
                        return ValidationResult.invalid(
                            "protocol must be an RFC 6455 token");
                    }
                    if (!unique.add(value)) {
                        return ValidationResult.invalid("protocols must be unique");
                    }
                }
            }
        } else if (!isApprovalSafe(input.path("command").asText(""))) {
            return ValidationResult.invalid(
                "command contains control characters that would be hidden in the approval dialog");
        }
        return ValidationResult.valid();
    }

    @Override
    public StructuredToolOutput call(JsonNode input, ToolExecutionContext context) {
        boolean requestedPersistent = input.path("persistent").asBoolean(false);
        long requestedTimeoutMs = Math.max(1_000, Math.round(input.path("timeout_ms")
            .asDouble(DEFAULT_TIMEOUT_MS)));
        boolean remote = EnvUtils.isEnvTruthy(
            SubprocessEnvironment.get("CLAUDE_CODE_REMOTE"));
        boolean persistent = requestedPersistent && !remote;
        long timeoutMs = remote
            ? (requestedPersistent ? REMOTE_TIMEOUT_MS
                : Math.min(requestedTimeoutMs, REMOTE_TIMEOUT_MS))
            : requestedTimeoutMs;
        String description = input.path("description").asText("");
        MessageQueueManager queue = context.messageQueueManager() != null
            ? context.messageQueueManager() : registry.messageQueue();
        if (queue == null) {
            throw new IllegalStateException("Monitor requires the session message queue");
        }

        boolean commandSource = input.has("command");
        TaskState task = commandSource
            ? registry.store().create(
                TaskType.LOCAL_BASH, description, context.agentId())
            : registry.store().create(TaskType.MONITOR_WS, description, context.agentId());
        if (context.toolUseId() != null) {
            task = registry.store().updateToolUseId(task.id(), context.toolUseId());
        }
        Path outputPath = TaskOutputPaths.outputPath(task.id(), context);
        try {
            MonitorTaskHandle handle;
            if (commandSource) {
                String command = input.get("command").asText();
                SandboxConfig config = context.sandboxConfig() != null
                    ? context.sandboxConfig() : SandboxConfig.disabled();
                SandboxDecision decision = sandboxManager.decide(command, false, config);
                if (decision.isReject()) {
                    throw new IllegalArgumentException(decision.rejectReason());
                }
                handle = new MonitorCommandTask(task, command,
                    registry.store(), outputPath, Path.of(context.workingDirectory()),
                    sandboxManager, decision, config, queue, timeoutMs, persistent);
            } else {
                JsonNode ws = input.get("ws");
                URI uri = URI.create(ws.path("url").asText());
                MonitorUrlSafety.Result safety = MonitorUrlSafety.validate(uri);
                if (!safety.valid()) throw new IllegalArgumentException(safety.message());
                SandboxConfig config = context.sandboxConfig() != null
                    ? context.sandboxConfig() : SandboxConfig.disabled();
                MonitorUrlSafety.Result policy = MonitorUrlSafety.validateDomainPolicy(
                    uri, config.network());
                if (!policy.valid()) throw new IllegalArgumentException(policy.message());
                WebSocketDestination destination = websocketDestination(uri, safety.addresses());
                List<String> protocols = new ArrayList<>();
                if (ws.path("protocols").isArray()) {
                    ws.path("protocols").forEach(value -> protocols.add(value.asText()));
                }
                handle = new MonitorWebSocketTask(task,
                    registry.store(), outputPath, uri, destination.url(),
                    destination.hostHeader(), protocols, destination.client(),
                    queue, timeoutMs, persistent);
            }
            registry.registerMonitor(handle);
            queue.enqueueSdkEvent(new SDKMessage.TaskStarted(
                task.id(), task.toolUseId().orElse(null), task.description(),
                task.type().name().toLowerCase(Locale.ROOT), null, null));
            if (handle instanceof MonitorCommandTask commandTask) commandTask.start();
            else ((MonitorWebSocketTask) handle).start();
        } catch (Exception e) {
            registry.unregisterMonitor(task.id());
            registry.store().remove(task.id());
            throw new IllegalStateException("Failed to start Monitor: " + e.getMessage(), e);
        }

        String lifetime = persistent
            ? "persistent — runs until TaskStop or session end"
            : "timeout " + timeoutMs + "ms";
        String text = "Monitor started (task " + task.id() + ", " + lifetime
            + "). You will be notified on each event. Keep working — do not poll or sleep. "
            + "Events may arrive while you are waiting for the user — an event is not their reply.";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("timeoutMs", persistent ? 0L : timeoutMs);
        payload.put("persistent", persistent);
        return new StructuredToolOutput(text, payload);
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        if (input != null && input.has("command")) {
            return BashPermissions.check(input.path("command").asText(""), permCtx);
        }
        if (input != null && input.path("ws").isObject()) {
            try {
                URI uri = URI.create(input.path("ws").path("url").asText(""));
                if (MonitorUrlSafety.isBlockedLiteral(uri.getHost())) {
                    return PermissionDecision.deny();
                }
                return new PermissionDecision.Ask(null, null,
                    "Monitor will open a WebSocket to " + uri, null, null);
            } catch (IllegalArgumentException _) {
                return PermissionDecision.deny();
            }
        }
        return PermissionDecision.deny();
    }

    @Override public boolean isEnabled() { return enabled.getAsBoolean(); }

    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("description")
            .put("description", "Short human-readable description of what you are monitoring (shown in notifications).")
            .put("type", "string");
        properties.putObject("timeout_ms")
            .put("description", "Kill the monitor after this deadline. Default 300000ms, max 3600000ms. Ignored when persistent is true.")
            .put("default", DEFAULT_TIMEOUT_MS)
            .put("type", "number")
            .put("minimum", 1000);
        properties.putObject("persistent")
            .put("description", "Run for the lifetime of the session (no timeout). Use for session-length watches like PR monitoring or log tails. Stop with TaskStop.")
            .put("default", false)
            .put("type", "boolean");
        properties.putObject("command")
            .put("description", "Shell command or script. Each stdout line is an event; exit ends the watch.")
            .put("type", "string");
        ObjectNode ws = properties.putObject("ws");
        ws.put("description", "WebSocket to open. Each text frame is an event; binary frames are reported as a placeholder line. Socket close ends the watch. Cannot be combined with command.");
        ws.put("type", "object");
        ObjectNode wsProperties = ws.putObject("properties");
        wsProperties.putObject("url").put("type", "string");
        ObjectNode protocols = wsProperties.putObject("protocols");
        protocols.put("type", "array");
        protocols.putObject("items")
            .put("type", "string")
            .put("pattern", "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
        ws.putArray("required").add("url");
        schema.putArray("required").add("description").add("timeout_ms").add("persistent");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static boolean isApprovalSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\n') continue;
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) return false;
        }
        return true;
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(ch -> ch <= 0x7f);
    }

    /**
     * Pins the safety-checked DNS answer to the actual WebSocket connection.
     */
    private WebSocketDestination websocketDestination(URI uri,
                                                        List<InetAddress> addresses) {
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("WebSocket host did not resolve");
        }
        String transportUrl = (Strings.CI.equals(uri.getScheme(), "ws") ? "http" : "https")
            + uri.toASCIIString().substring(uri.getScheme().length());
        HttpUrl original = HttpUrl.get(transportUrl);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (Strings.CI.equals(uri.getScheme(), "ws")) {
            HttpUrl pinned = original.newBuilder()
                .host(addresses.getFirst().getHostAddress())
                .build();
            return new WebSocketDestination(pinned, hostHeader(original), httpClient);
        }
        Dns system = httpClient.dns();
        OkHttpClient pinnedClient = httpClient.newBuilder()
            .dns(requestedHost -> requestedHost.equalsIgnoreCase(host)
                ? addresses : system.lookup(requestedHost))
            .build();
        return new WebSocketDestination(original, null, pinnedClient);
    }

    private static String hostHeader(HttpUrl url) {
        String host = url.host().indexOf(':') >= 0 ? "[" + url.host() + "]" : url.host();
        return url.port() == HttpUrl.defaultPort(url.scheme())
            ? host : host + ":" + url.port();
    }

    private record WebSocketDestination(HttpUrl url, String hostHeader,
                                        OkHttpClient client) {}
}
