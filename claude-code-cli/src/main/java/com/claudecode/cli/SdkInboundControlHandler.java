package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.services.model.ModelAllowlist;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Handles controller-to-CLI {@code control_request} messages in SDK stream-json mode.
 */
final class SdkInboundControlHandler {

    enum Action { CONTINUE, END_SESSION }

    record CommandInfo(String name, String description, String argumentHint,
                       List<String> aliases) {
        CommandInfo {
            description = description != null ? description : "";
            argumentHint = argumentHint != null ? argumentHint : "";
            aliases = aliases != null ? List.copyOf(aliases) : List.of();
        }
    }

    record AgentInfo(String name, String description, String model) {
        AgentInfo {
            description = description != null ? description : "";
        }
    }

    record ControlCatalog(List<CommandInfo> commands, List<AgentInfo> agents,
                          List<String> outputStyles) {
        ControlCatalog {
            commands = commands != null ? List.copyOf(commands) : List.of();
            agents = agents != null ? List.copyOf(agents) : List.of();
            outputStyles = outputStyles != null ? List.copyOf(outputStyles) : List.of();
        }

        static ControlCatalog empty() {
            return new ControlCatalog(List.of(), List.of(), List.of());
        }
    }

    private static final ObjectMapper MAPPER = JsonUtils.getMapper();

    private final CliOutput out;
    private final QuerySession engine;
    private final PermissionGate permissionGate;
    private final StdoutMessageWriter.SdkOutputMetadata metadata;
    private final ControlCatalog catalog;
    private final SdkControlRuntime runtime;
    private final boolean showBuiltInModelFamilies;
    private final List<String> customModelNames;
    private final List<CompletableFuture<?>> pendingResponses =
        Collections.synchronizedList(new ArrayList<>());
    private final List<UserMessage> pendingTranscriptBreadcrumbs =
        Collections.synchronizedList(new ArrayList<>());
    private boolean initialized;

    SdkInboundControlHandler(PrintWriter out, QuerySession engine,
                             PermissionGate permissionGate,
                             StdoutMessageWriter.SdkOutputMetadata metadata) {
        this(CliOutput.borrowed(out), engine, permissionGate, metadata, ControlCatalog.empty());
    }

    SdkInboundControlHandler(PrintWriter out, QuerySession engine,
                             PermissionGate permissionGate,
                             StdoutMessageWriter.SdkOutputMetadata metadata,
                             ControlCatalog catalog) {
        this(CliOutput.borrowed(out), engine, permissionGate, metadata, catalog);
    }

    SdkInboundControlHandler(CliOutput out, QuerySession engine,
                             PermissionGate permissionGate,
                             StdoutMessageWriter.SdkOutputMetadata metadata,
                             ControlCatalog catalog) {
        this(out, engine, permissionGate, metadata, catalog,
            SdkControlRuntime.unavailable(), true);
    }

    SdkInboundControlHandler(PrintWriter out, QuerySession engine,
                             PermissionGate permissionGate,
                             StdoutMessageWriter.SdkOutputMetadata metadata,
                             ControlCatalog catalog,
                             boolean showBuiltInModelFamilies) {
        this(CliOutput.borrowed(out), engine, permissionGate, metadata, catalog,
            SdkControlRuntime.unavailable(), showBuiltInModelFamilies);
    }

    SdkInboundControlHandler(PrintWriter out, QuerySession engine,
                             PermissionGate permissionGate,
                             StdoutMessageWriter.SdkOutputMetadata metadata,
                             ControlCatalog catalog,
                             boolean showBuiltInModelFamilies,
                             List<String> customModelNames) {
        this(CliOutput.borrowed(out), engine, permissionGate, metadata, catalog,
            SdkControlRuntime.unavailable(), showBuiltInModelFamilies, customModelNames);
    }

    SdkInboundControlHandler(PrintWriter out, QuerySession engine,
                             PermissionGate permissionGate,
                             StdoutMessageWriter.SdkOutputMetadata metadata,
                             ControlCatalog catalog,
                             SdkControlRuntime runtime) {
        this(CliOutput.borrowed(out), engine, permissionGate, metadata, catalog, runtime);
    }

    SdkInboundControlHandler(CliOutput out, QuerySession engine,
                             PermissionGate permissionGate,
                             StdoutMessageWriter.SdkOutputMetadata metadata,
                             ControlCatalog catalog,
                             SdkControlRuntime runtime) {
        this(out, engine, permissionGate, metadata, catalog, runtime, true);
    }

    SdkInboundControlHandler(CliOutput out, QuerySession engine,
                             PermissionGate permissionGate,
                             StdoutMessageWriter.SdkOutputMetadata metadata,
                             ControlCatalog catalog,
                             SdkControlRuntime runtime,
                             boolean showBuiltInModelFamilies) {
        this(out, engine, permissionGate, metadata, catalog, runtime,
            showBuiltInModelFamilies, List.of());
    }

    SdkInboundControlHandler(CliOutput out, QuerySession engine,
                             PermissionGate permissionGate,
                             StdoutMessageWriter.SdkOutputMetadata metadata,
                             ControlCatalog catalog,
                             SdkControlRuntime runtime,
                             boolean showBuiltInModelFamilies,
                             List<String> customModelNames) {
        this.out = out;
        this.engine = engine;
        this.permissionGate = permissionGate;
        this.metadata = metadata;
        this.catalog = catalog != null ? catalog : ControlCatalog.empty();
        this.runtime = runtime != null ? runtime : SdkControlRuntime.unavailable();
        this.showBuiltInModelFamilies = showBuiltInModelFamilies;
        this.customModelNames = customModelNames != null ? List.copyOf(customModelNames) : List.of();
    }

    Action handle(JsonNode message) {
        String requestId = message != null ? message.path("request_id").asText(null) : null;
        JsonNode request = message != null ? message.path("request") : MAPPER.missingNode();
        String subtype = request.path("subtype").asText(null);
        if (requestId == null || subtype == null) {
            writeError(requestId, "Missing request_id or request subtype");
            return Action.CONTINUE;
        }

        return switch (subtype) {
            case "initialize" -> initialize(requestId, request);
            case "set_permission_mode" -> setPermissionMode(requestId, request);
            case "set_model" -> setModel(requestId, request);
            case "set_max_thinking_tokens" -> setMaxThinkingTokens(requestId, request);
            case "mcp_status" -> mcpStatus(requestId);
            case "get_context_usage" -> getContextUsage(requestId);
            case "rewind_files" -> rewindFiles(requestId, request);
            case "cancel_async_message" -> cancelAsyncMessage(requestId, request);
            case "seed_read_state" -> seedReadState(requestId, request);
            case "stop_task" -> stopTask(requestId, request);
            case "background_tasks" -> backgroundTasks(requestId, request);
            case "get_settings" -> getSettings(requestId);
            case "mcp_set_servers" -> mcpSetServers(requestId, request);
            case "mcp_message" -> mcpMessage(requestId, request);
            case "reload_plugins" -> reloadPlugins(requestId);
            case "reload_skills" -> reloadSkills(requestId);
            case "read_file" -> readFile(requestId, request);
            case "mcp_reconnect" -> mcpReconnect(requestId, request);
            case "mcp_toggle" -> mcpToggle(requestId, request);
            case "mcp_clear_auth" -> mcpClearAuth(requestId, request);
            case "mcp_authenticate" -> mcpAuthenticate(requestId, request);
            case "mcp_oauth_callback_url" -> mcpOAuthCallback(requestId, request);
            case "channel_enable" -> {
                writeError(requestId, "channels feature not available in this build");
                yield Action.CONTINUE;
            }
            case "apply_flag_settings" -> applyFlagSettings(requestId, request);
            case "update_environment_variables" -> updateEnvironmentVariables(requestId, request);
            case "generate_session_title" -> generateSessionTitle(requestId, request);
            case "side_question" -> sideQuestion(requestId, request);
            case "interrupt" -> interrupt(requestId, false);
            case "end_session" -> interrupt(requestId, true);
            default -> {
                writeError(requestId, "Unsupported control request subtype: " + subtype);
                yield Action.CONTINUE;
            }
        };
    }

    private Action initialize(String requestId, JsonNode request) {
        if (initialized) {
            ObjectNode extra = MAPPER.createObjectNode();
            extra.set("pending_permission_requests", MAPPER.createArrayNode());
            writeError(requestId, "Already initialized", extra);
            return Action.CONTINUE;
        }
        initialized = true;
        try {
            runtime.configureSupportedDialogKinds(request.get("supportedDialogKinds"));
            if (request.has("sdkMcpServers")) {
                runtime.configureSdkMcpServers(request.get("sdkMcpServers"));
            }
            if (request.has("hooks")) {
                runtime.configureHooks(request.get("hooks"));
            }
        } catch (RuntimeException error) {
            writeError(requestId, errorMessage(error));
            return Action.CONTINUE;
        }

        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode commands = response.putArray("commands");
        List<CommandInfo> commandInfos = new ArrayList<>(catalog.commands());
        if (commandInfos.isEmpty()) {
            metadata.slashCommands().stream()
                .map(name -> new CommandInfo(name, "", "", List.of()))
                .forEach(commandInfos::add);
        }
        for (CommandInfo info : commandInfos) {
            ObjectNode command = commands.addObject();
            command.put("name", info.name());
            command.put("description", info.description());
            command.put("argumentHint", info.argumentHint());
            if (!info.aliases().isEmpty()) {
                command.set("aliases", MAPPER.valueToTree(info.aliases()));
            }
        }
        ArrayNode agents = response.putArray("agents");
        List<AgentInfo> agentInfos = new ArrayList<>(catalog.agents());
        if (agentInfos.isEmpty()) {
            metadata.agents().stream()
                .map(name -> new AgentInfo(name, "", null))
                .forEach(agentInfos::add);
        }
        for (AgentInfo info : agentInfos) {
            ObjectNode agent = agents.addObject();
            agent.put("name", info.name());
            agent.put("description", info.description());
            if (StringUtils.isNotBlank(info.model())
                    && !Strings.CS.equals("inherit", info.model())) {
                agent.put("model", info.model());
            }
        }
        response.put("output_style", metadata.outputStyle());
        List<String> outputStyles = catalog.outputStyles().isEmpty()
            ? List.of(metadata.outputStyle()) : catalog.outputStyles();
        response.set("available_output_styles", MAPPER.valueToTree(outputStyles));

        ArrayNode models = response.putArray("models");
        if (showBuiltInModelFamilies) {
            addModel(models, "default", ModelCatalog.LATEST_OPUS, "Default (recommended)",
                "Use the default model (currently Opus 5 with 1M context) · $5/$25 per Mtok",
                EffortHelpers.supportedEffortLevels(ModelCatalog.LATEST_OPUS), true, true, true);
            addAllowedModel(models, "opus[1m]", ModelCatalog.LATEST_OPUS, "Opus",
                "Opus 5 with 1M context · Best for everyday, complex tasks · $5/$25 per Mtok",
                EffortHelpers.supportedEffortLevels(ModelCatalog.LATEST_OPUS), true, true, true);
            addAllowedModel(models, "sonnet", ModelCatalog.LATEST_SONNET, "Sonnet",
                "Sonnet 5 · Efficient for routine tasks · $3/$15 per Mtok",
                EffortHelpers.supportedEffortLevels(ModelCatalog.LATEST_SONNET), true, false, true);
            addAllowedModel(models, "sonnet[1m]", ModelCatalog.LATEST_SONNET + "[1m]",
                "Sonnet 5 (1M context)",
                "Sonnet 5 for long sessions · $3/$15 per Mtok",
                EffortHelpers.supportedEffortLevels(ModelCatalog.LATEST_SONNET), true, false, true);
            addAllowedModel(models, "haiku", ModelCatalog.LATEST_HAIKU, "Haiku",
                "Haiku 4.5 · Fastest for quick answers · $1/$5 per Mtok",
                false, false, false, false);
        }
        for (String customModel : customModelNames) {
            if (!containsModel(models, customModel)) {
                addAllowedModel(models, customModel, customModel, customModel,
                    "Custom model", EffortHelpers.supportedEffortLevels(customModel),
                    true, false, true);
            }
        }
        if (showBuiltInModelFamilies
                && Strings.CS.equals("claude-sonnet-4-6", metadata.model())) {
            addAllowedModel(models, metadata.model(), metadata.model(), "Sonnet 4.6",
                "Newer version available · select Sonnet for Sonnet 5",
                List.of("low", "medium", "high", "max"), true, false, true);
        } else if (!ModelCatalog.isBuiltInSelection(metadata.model())
                && !containsModel(models, metadata.model())) {
            addAllowedModel(models, metadata.model(), metadata.model(), metadata.model(),
                "Custom model", EffortHelpers.supportedEffortLevels(metadata.model()),
                true, false, true);
        }

        ObjectNode account = response.putObject("account");
        account.put("tokenSource", "none");
        if (metadata.apiKeySource() != null && !Strings.CS.equals("none", metadata.apiKeySource())) {
            account.put("apiKeySource", metadata.apiKeySource());
        }
        account.put("apiProvider", "firstParty");
        response.put("pid", ProcessHandle.current().pid());
        response.put("fast_mode_state", metadata.fastModeState());
        writeSuccess(requestId, response);
        return Action.CONTINUE;
    }

    private static boolean containsModel(ArrayNode models, String value) {
        for (JsonNode model : models) {
            if (Strings.CS.equals(value, model.path("value").asText())) return true;
        }
        return false;
    }

    private static void addModel(ArrayNode models, String value, String resolvedModel,
                                 String displayName, String description,
                                 boolean effort, boolean adaptiveThinking,
                                 boolean fastMode, boolean autoMode) {
        addModel(models, value, resolvedModel, displayName, description,
            effort ? List.of("low", "medium", "high", "xhigh", "max") : List.of(),
            adaptiveThinking, fastMode, autoMode);
    }

    private static void addModel(ArrayNode models, String value, String resolvedModel,
                                 String displayName, String description,
                                 List<String> effortLevels, boolean adaptiveThinking,
                                 boolean fastMode, boolean autoMode) {
        ObjectNode model = models.addObject();
        model.put("value", value);
        model.put("resolvedModel", resolvedModel);
        model.put("displayName", displayName);
        model.put("description", description);
        if (!effortLevels.isEmpty()) {
            model.put("supportsEffort", true);
            model.set("supportedEffortLevels", MAPPER.valueToTree(effortLevels));
        }
        if (adaptiveThinking) model.put("supportsAdaptiveThinking", true);
        if (fastMode) model.put("supportsFastMode", true);
        if (autoMode) model.put("supportsAutoMode", true);
    }

    private static void addAllowedModel(ArrayNode models, String value, String resolvedModel,
                                        String displayName, String description,
                                        boolean effort, boolean adaptiveThinking,
                                        boolean fastMode, boolean autoMode) {
        if (ModelAllowlist.isAllowed(value)) {
            addModel(models, value, resolvedModel, displayName, description,
                effort, adaptiveThinking, fastMode, autoMode);
        }
    }

    private static void addAllowedModel(ArrayNode models, String value, String resolvedModel,
                                        String displayName, String description,
                                        List<String> effortLevels, boolean adaptiveThinking,
                                        boolean fastMode, boolean autoMode) {
        if (ModelAllowlist.isAllowed(value)) {
            addModel(models, value, resolvedModel, displayName, description,
                effortLevels, adaptiveThinking, fastMode, autoMode);
        }
    }

    private Action setPermissionMode(String requestId, JsonNode request) {
        String mode = request.path("mode").asText(null);
        if (mode == null) {
            writeError(requestId, "Missing permission mode");
            return Action.CONTINUE;
        }
        PermissionMode parsed = PermissionGate.parseMode(mode);
        if (!permissionGate.trySetMode(parsed)) {
            String reason = permissionGate.isBypassPermissionsModeDisabledByPolicy()
                ? "because it is disabled by settings or configuration"
                : "because the session was not launched with --dangerously-skip-permissions";
            writeError(requestId, "Cannot set permission mode to bypassPermissions " + reason);
            return Action.CONTINUE;
        }
        ObjectNode response = MAPPER.createObjectNode();
        response.put("mode", externalPermissionMode(mode));
        writeSuccess(requestId, response);

        ObjectNode status = MAPPER.createObjectNode();
        status.put("type", "system");
        status.put("subtype", "status");
        status.putNull("status");
        status.put("permissionMode", externalPermissionMode(mode));
        status.put("uuid", UUID.randomUUID().toString());
        status.put("session_id", metadata.sessionId());
        StdoutMessageWriter.writeControlMessage(status, out);
        return Action.CONTINUE;
    }

    private Action setModel(String requestId, JsonNode request) {
        String requested = request.path("model").asText(null);
        String resolved = StringUtils.isBlank(requested)
            || Strings.CS.equals("default", requested) ? ModelNames.defaultMainLoopModel() : requested;
        if (!ModelAllowlist.isAllowed(resolved)) {

            // ignores it at resolution time. QuerySession stores the effective
            // model directly, so use the equivalent default here.
            resolved = ModelNames.defaultMainLoopModel();
        }
        engine.configuration().setModel(resolved);
        injectModelSwitchBreadcrumbs(
            StringUtils.isBlank(requested) ? "default" : requested, resolved);
        writeSuccess(requestId, null);
        return Action.CONTINUE;
    }

    private Action setMaxThinkingTokens(String requestId, JsonNode request) {
        JsonNode value = request.get("max_thinking_tokens");
        if (value == null) {
            writeError(requestId, "Missing max_thinking_tokens");
            return Action.CONTINUE;
        }
        engine.configuration().getConfig().setThinkingBudgetTokens(
            value.isNull() ? null : value.asInt());
        writeSuccess(requestId, null);
        return Action.CONTINUE;
    }

    private Action interrupt(String requestId, boolean endSession) {
        engine.submission().interrupt();
        writeSuccess(requestId, null);
        return endSession ? Action.END_SESSION : Action.CONTINUE;
    }

    private Action mcpStatus(String requestId) {
        try {
            ObjectNode response = MAPPER.createObjectNode();
            ArrayNode servers = response.putArray("mcpServers");
            for (SdkControlRuntime.McpServerStatus status : runtime.mcpStatus()) {
                ObjectNode server = servers.addObject();
                server.put("name", status.name());
                server.put("status", status.status());
                if (status.serverInfo() != null) server.set("serverInfo", status.serverInfo());
                if (status.error() != null) server.put("error", status.error());
                if (status.config() != null) server.set("config", status.config());
                if (status.scope() != null) server.put("scope", status.scope());
                if (status.tools() != null) server.set("tools", status.tools());
                if (status.capabilities() != null) {
                    server.set("capabilities", status.capabilities());
                }
            }
            writeSuccess(requestId, response);
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private Action getContextUsage(String requestId) {
        try {
            writeSuccess(requestId, runtime.contextUsage());
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private Action rewindFiles(String requestId, JsonNode request) {
        String messageId = request.path("user_message_id").asText(null);
        if (messageId == null) {
            writeError(requestId, "Missing user_message_id");
            return Action.CONTINUE;
        }
        boolean dryRun = request.path("dry_run").asBoolean(false);
        try {
            SdkControlRuntime.RewindFilesResult result = runtime.rewindFiles(messageId, dryRun);
            if (!result.canRewind() && !dryRun) {
                writeError(requestId, result.error() != null ? result.error() : "Unexpected error");
                return Action.CONTINUE;
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("canRewind", result.canRewind());
            if (result.error() != null) response.put("error", result.error());
            if (result.filesChanged() != null && !result.filesChanged().isEmpty()) {
                response.set("filesChanged", MAPPER.valueToTree(result.filesChanged()));
            }
            if (result.insertions() != null) response.put("insertions", result.insertions());
            if (result.deletions() != null) response.put("deletions", result.deletions());
            writeSuccess(requestId, response);
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private Action cancelAsyncMessage(String requestId, JsonNode request) {
        String uuid = request.path("message_uuid").asText(null);
        if (uuid == null) {
            writeError(requestId, "Missing message_uuid");
            return Action.CONTINUE;
        }
        try {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("cancelled", runtime.cancelAsyncMessage(uuid));
            writeSuccess(requestId, response);
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private Action seedReadState(String requestId, JsonNode request) {
        String path = request.path("path").asText(null);
        JsonNode mtime = request.get("mtime");
        if (path == null || mtime == null || !mtime.isNumber()) {
            writeError(requestId, "Missing path or mtime");
            return Action.CONTINUE;
        }
        try {
            runtime.seedReadState(path, mtime.asLong());
            writeSuccess(requestId, null);
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private Action stopTask(String requestId, JsonNode request) {
        String taskId = request.path("task_id").asText(null);
        if (taskId == null) {
            writeError(requestId, "Missing task_id");
            return Action.CONTINUE;
        }
        try {
            runtime.stopTask(taskId);
            writeSuccess(requestId, MAPPER.createObjectNode());
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private Action getSettings(String requestId) {
        try {
            writeSuccess(requestId, runtime.settings());
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private Action backgroundTasks(String requestId, JsonNode request) {
        String toolUseId = request.path("tool_use_id").asText(null);
        try {
            writeSuccess(requestId, MAPPER.createObjectNode().put(
                "backgrounded", runtime.backgroundTasks(toolUseId)));
        } catch (RuntimeException error) {
            writeError(requestId, errorMessage(error));
        }
        return Action.CONTINUE;
    }

    private Action reloadSkills(String requestId) {
        try {
            writeSuccess(requestId, runtime.reloadSkills());
        } catch (RuntimeException error) {
            writeError(requestId, errorMessage(error));
        }
        return Action.CONTINUE;
    }

    private Action readFile(String requestId, JsonNode request) {
        String path = request.path("path").asText(null);
        Long maxBytes = request.hasNonNull("max_bytes")
            ? request.path("max_bytes").longValue() : null;
        String encoding = request.path("encoding").asText(null);
        try {
            writeSuccess(requestId, runtime.readFile(path, maxBytes, encoding));
        } catch (RuntimeException error) {
            writeError(requestId, errorMessage(error));
        }
        return Action.CONTINUE;
    }

    private Action mcpSetServers(String requestId, JsonNode request) {
        JsonNode servers = request.get("servers");
        if (servers == null || !servers.isObject()) {
            writeError(requestId, "Missing servers");
            return Action.CONTINUE;
        }
        try {
            if (request.has("sdkMcpServers")) {
                runtime.configureSdkMcpServers(request.get("sdkMcpServers"));
                runtime.prepareForTurn();
            }
            writeSuccess(requestId, runtime.setMcpServers(servers));
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private Action updateEnvironmentVariables(String requestId, JsonNode request) {
        JsonNode variables = request.get("variables");
        if (variables == null || !variables.isObject()) {
            writeError(requestId, "Missing variables");
            return Action.CONTINUE;
        }
        try {
            runtime.updateEnvironmentVariables(variables);
            writeSuccess(requestId, MAPPER.createObjectNode());
        } catch (RuntimeException error) {
            writeError(requestId, errorMessage(error));
        }
        return Action.CONTINUE;
    }

    private Action mcpMessage(String requestId, JsonNode request) {
        String serverName = request.path("server_name").asText(null);
        JsonNode message = request.get("message");
        if (serverName == null || message == null || !message.isObject()) {
            writeError(requestId, "Missing server_name or message");
            return Action.CONTINUE;
        }
        try {
            runtime.deliverMcpMessage(serverName, message);
            writeSuccess(requestId, null);
        } catch (RuntimeException error) {
            writeError(requestId, errorMessage(error));
        }
        return Action.CONTINUE;
    }

    private Action reloadPlugins(String requestId) {
        try {
            JsonNode response = runtime.reloadPlugins();
            writeCommandsChanged(response != null ? response.get("commands") : null);
            writeSuccess(requestId, response);
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private void writeCommandsChanged(JsonNode commands) {
        if (commands == null || !commands.isArray()) return;
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", "system");
        event.put("subtype", "commands_changed");
        event.set("commands", commands.deepCopy());
        event.put("uuid", UUID.randomUUID().toString());
        event.put("session_id", metadata.sessionId());
        synchronized (out) {
            StdoutMessageWriter.writeControlMessage(event, out);
        }
    }

    private Action mcpReconnect(String requestId, JsonNode request) {
        String serverName = request.path("serverName").asText(null);
        if (serverName == null) {
            writeError(requestId, "Missing serverName");
            return Action.CONTINUE;
        }
        try {
            runtime.reconnectMcp(serverName);
            writeSuccess(requestId, null);
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private Action mcpToggle(String requestId, JsonNode request) {
        String serverName = request.path("serverName").asText(null);
        JsonNode enabled = request.get("enabled");
        if (serverName == null || enabled == null || !enabled.isBoolean()) {
            writeError(requestId, "Missing serverName or enabled");
            return Action.CONTINUE;
        }
        try {
            runtime.toggleMcp(serverName, enabled.asBoolean());
            writeSuccess(requestId, null);
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private Action mcpClearAuth(String requestId, JsonNode request) {
        String serverName = request.path("serverName").asText(null);
        if (serverName == null) {
            writeError(requestId, "Missing serverName");
            return Action.CONTINUE;
        }
        try {
            runtime.clearMcpAuth(serverName);
            writeSuccess(requestId, MAPPER.createObjectNode());
        } catch (RuntimeException error) {
            writeError(requestId, errorMessage(error));
        }
        return Action.CONTINUE;
    }

    private Action mcpAuthenticate(String requestId, JsonNode request) {
        String serverName = request.path("serverName").asText(null);
        if (serverName == null) {
            writeError(requestId, "Missing serverName");
            return Action.CONTINUE;
        }
        try {
            writeSuccess(requestId, runtime.authenticateMcp(serverName));
        } catch (RuntimeException error) {
            writeError(requestId, errorMessage(error));
        }
        return Action.CONTINUE;
    }

    private Action mcpOAuthCallback(String requestId, JsonNode request) {
        String serverName = request.path("serverName").asText(null);
        String callbackUrl = request.path("callbackUrl").asText(null);
        if (serverName == null || callbackUrl == null) {
            writeError(requestId, "Missing serverName or callbackUrl");
            return Action.CONTINUE;
        }
        try {
            runtime.submitMcpOAuthCallback(serverName, callbackUrl);
            writeSuccess(requestId, null);
        } catch (RuntimeException error) {
            writeError(requestId, errorMessage(error));
        }
        return Action.CONTINUE;
    }

    private Action applyFlagSettings(String requestId, JsonNode request) {
        JsonNode settings = request.get("settings");
        if (settings == null || !settings.isObject()) {
            writeError(requestId, "Missing settings");
            return Action.CONTINUE;
        }
        try {
            String previousModel = ModelNames.parseUserSpecifiedModel(engine.configuration().getConfig().model());
            runtime.applyFlagSettings(settings);
            String currentModel = ModelNames.parseUserSpecifiedModel(engine.configuration().getConfig().model());
            if (!Objects.equals(previousModel, currentModel)) {
                JsonNode incomingModel = settings.get("model");
                String modelArg = incomingModel == null || incomingModel.isNull()
                    ? "default" : incomingModel.asText();
                injectModelSwitchBreadcrumbs(modelArg, currentModel);
            }
            writeSuccess(requestId, null);
        } catch (RuntimeException e) {
            writeError(requestId, errorMessage(e));
        }
        return Action.CONTINUE;
    }

    private void injectModelSwitchBreadcrumbs(String modelArg, String resolvedModel) {
        String parsed = ModelNames.parseUserSpecifiedModel(resolvedModel);
        String display = Objects.equals(resolvedModel, parsed)
            ? parsed : resolvedModel + " (" + parsed + ")";
        List<UserMessage> breadcrumbs = MessageFactory.createModelSwitchBreadcrumbs(
            modelArg, display);
        breadcrumbs.forEach(engine.conversation()::appendInMemoryMessage);
        pendingTranscriptBreadcrumbs.addAll(breadcrumbs);

        UserMessage stdout = breadcrumbs.getLast();
        ObjectNode replay = StdoutMessageWriter.toJson(
            new SDKMessage.User(stdout, true), metadata, true);
        StdoutMessageWriter.writeControlMessage(replay, out);
    }

    /**
     * Persists control-generated breadcrumbs at the next SDK turn boundary.
     */
    void flushPendingTranscriptBreadcrumbs() {
        List<UserMessage> pending;
        synchronized (pendingTranscriptBreadcrumbs) {
            if (pendingTranscriptBreadcrumbs.isEmpty()) return;
            pending = List.copyOf(pendingTranscriptBreadcrumbs);
            pendingTranscriptBreadcrumbs.clear();
        }
        TranscriptSink sink = engine.execution().getTranscriptSink();
        if (sink == null) return;
        String sessionId = engine.conversation().getSessionId();
        pending.forEach(message -> sink.record(sessionId, message));
    }

    private Action generateSessionTitle(String requestId, JsonNode request) {
        String description = request.path("description").asText(null);
        if (description == null) {
            writeError(requestId, "Missing description");
            return Action.CONTINUE;
        }
        trackAsync(requestId, runtime.generateSessionTitle(
            description, request.path("persist").asBoolean(false)), "title");
        return Action.CONTINUE;
    }

    private Action sideQuestion(String requestId, JsonNode request) {
        String question = request.path("question").asText(null);
        if (question == null) {
            writeError(requestId, "Missing question");
            return Action.CONTINUE;
        }
        trackAsync(requestId, runtime.sideQuestion(question), "response", true);
        return Action.CONTINUE;
    }

    private void trackAsync(String requestId, CompletableFuture<String> future, String field) {
        trackAsync(requestId, future, field, false);
    }

    private void trackAsync(String requestId, CompletableFuture<String> future, String field,
                            boolean includeSyntheticFlag) {
        CompletableFuture<?> response = future.whenComplete((value, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException
                    && error.getCause() != null ? error.getCause() : error;
                writeError(requestId, cause.getMessage() != null
                    ? cause.getMessage() : cause.getClass().getSimpleName());
                return;
            }
            ObjectNode body = MAPPER.createObjectNode();
            if (value != null) body.put(field, value);
            else body.putNull(field);
            if (includeSyntheticFlag) body.put("synthetic", false);
            writeSuccess(requestId, body);
        });
        pendingResponses.add(response);
    }

    void awaitPendingResponses(long timeoutMillis) {
        CompletableFuture<?>[] futures;
        synchronized (pendingResponses) {
            futures = pendingResponses.toArray(CompletableFuture[]::new);
        }
        if (futures.length == 0) return;
        try {
            CompletableFuture.allOf(futures).get(
                Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (Exception _) {
            // Best effort during shutdown; individual completions own their response/error.
        }
    }

    private static String errorMessage(RuntimeException error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return StringUtils.isNotBlank(current.getMessage())
            ? current.getMessage() : current.getClass().getSimpleName();
    }

    private static String externalPermissionMode(String requested) {
        return switch (requested) {
            case "plan", "acceptEdits", "bypassPermissions", "dontAsk", "auto" -> requested;
            default -> "default";
        };
    }

    void writeSuccess(String requestId, JsonNode responseBody) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "control_response");
        ObjectNode response = root.putObject("response");
        response.put("subtype", "success");
        response.put("request_id", requestId);
        if (responseBody != null) response.set("response", responseBody);
        synchronized (out) {
            StdoutMessageWriter.writeControlMessage(root, out);
        }
    }

    private void writeError(String requestId, String error) {
        writeError(requestId, error, null);
    }

    private void writeError(String requestId, String error, ObjectNode extra) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "control_response");
        ObjectNode response = root.putObject("response");
        response.put("subtype", "error");
        if (requestId != null) response.put("request_id", requestId);
        response.put("error", error);
        if (extra != null) response.setAll(extra);
        synchronized (out) {
            StdoutMessageWriter.writeControlMessage(root, out);
        }
    }
}
