package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.context.ContextData.Category;
import com.claudecode.commands.impl.terminal.BtwCommand;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.model.ModelNames;
import com.claudecode.services.config.SettingsSnapshots;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.services.model.ModelAllowlist;
import com.claudecode.services.titles.TerminalSessionTitleGenerator;
import com.claudecode.services.hooks.*;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.mcp.McpRuntime;
import com.claudecode.tools.mcp.McpToolProvider;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.io.PathUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

/**
 * Default adapter from SDK control operations to the live CLI session.
 */
final class DefaultSdkControlRuntime implements SdkControlRuntime {

    private final QuerySession engine;
    private final String cwd;
    private final Supplier<ContextData> contextUsageSupplier;
    private final Supplier<Map<String, String>> mcpStatusSupplier;
    private final Predicate<String> asyncMessageCanceller;
    private final TerminalSessionTitleGenerator titleGenerator;
    private final Function<String, String> sideQuestionRunner;
    private final CliPluginRuntimeView pluginRuntime;
    private final McpRuntime mcpRuntime;
    private final Supplier<StdoutMessageWriter.SdkOutputState> sdkStateSupplier;
    private final HookEngine hookEngine;
    private final SdkControlBroker controlBroker;
    private volatile List<String> sdkMcpServerNames = List.of();

    DefaultSdkControlRuntime(QuerySession engine, String cwd,
                             Supplier<ContextData> contextUsageSupplier,
                             Supplier<Map<String, String>> mcpStatusSupplier,
                             Predicate<String> asyncMessageCanceller) {
        this.engine = engine;
        this.cwd = cwd;
        this.contextUsageSupplier = contextUsageSupplier;
        this.mcpStatusSupplier = mcpStatusSupplier;
        this.asyncMessageCanceller = asyncMessageCanceller;
        this.titleGenerator = null;
        this.sideQuestionRunner = null;
        this.pluginRuntime = null;
        this.mcpRuntime = null;
        this.sdkStateSupplier = null;
        this.hookEngine = null;
        this.controlBroker = null;
    }

    DefaultSdkControlRuntime(QuerySession engine, String cwd,
                             Supplier<ContextData> contextUsageSupplier,
                             Supplier<Map<String, String>> mcpStatusSupplier,
                             Predicate<String> asyncMessageCanceller,
                             TerminalSessionTitleGenerator titleGenerator,
                             Function<String, String> sideQuestionRunner,
                             CliPluginRuntimeView pluginRuntime,
                             McpRuntime mcpRuntime,
                             Supplier<StdoutMessageWriter.SdkOutputState> sdkStateSupplier,
                             HookEngine hookEngine,
                             SdkControlBroker controlBroker) {
        this.engine = engine;
        this.cwd = cwd;
        this.contextUsageSupplier = contextUsageSupplier;
        this.mcpStatusSupplier = mcpStatusSupplier;
        this.asyncMessageCanceller = asyncMessageCanceller;
        this.titleGenerator = titleGenerator;
        this.sideQuestionRunner = sideQuestionRunner;
        this.pluginRuntime = pluginRuntime;
        this.mcpRuntime = mcpRuntime;
        this.sdkStateSupplier = sdkStateSupplier;
        this.hookEngine = hookEngine;
        this.controlBroker = controlBroker;
    }

    @Override
    public List<McpServerStatus> mcpStatus() {
        if (mcpRuntime != null) {
            return mcpRuntime.snapshotServerDetails().stream()
                .map(status -> new McpServerStatus(
                    status.name(),
                    status.status(),
                    status.serverInfo(),
                    status.error(),
                    status.config(),
                    status.scope(),
                    status.tools() != null
                        ? JsonUtils.getMapper().valueToTree(status.tools()) : null,
                    null))
                .toList();
        }
        Map<String, String> statuses = mcpStatusSupplier != null
            ? mcpStatusSupplier.get() : Map.of();
        return statuses.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new McpServerStatus(entry.getKey(), entry.getValue()))
            .toList();
    }

    @Override
    public void configureSdkMcpServers(JsonNode serverNames) {
        if (serverNames == null || !serverNames.isArray()) {
            throw new IllegalArgumentException("sdkMcpServers must be an array");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        serverNames.forEach(value -> {
            if (!value.isTextual() || StringUtils.isBlank(value.asText())) {
                throw new IllegalArgumentException(
                    "sdkMcpServers entries must be non-empty strings");
            }
            unique.add(value.asText());
        });
        sdkMcpServerNames = List.copyOf(unique);
    }

    @Override
    public void configureSupportedDialogKinds(JsonNode dialogKinds) {
        if (controlBroker != null) {
            controlBroker.configureSupportedDialogKinds(dialogKinds);
        }
    }

    @Override
    public void prepareForTurn() {
        if (mcpRuntime == null) return;
        mcpRuntime.setSdkServers(sdkMcpServerNames);
    }

    @Override
    public JsonNode contextUsage() {
        if (contextUsageSupplier == null) {
            throw new IllegalStateException("Context usage is unavailable");
        }
        ContextData data = contextUsageSupplier.get();
        if (data == null) throw new IllegalStateException("Context usage is unavailable");
        return contextPayload(data);
    }

    @Override
    public RewindFilesResult rewindFiles(String userMessageId, boolean dryRun) {
        FileHistoryManager history = engine.conversation().getFileHistoryManager();
        if (history == null) {
            return new RewindFilesResult(false, "File rewinding is not enabled.",
                List.of(), null, null);
        }
        if (!history.canRestore(userMessageId)) {
            return new RewindFilesResult(false, "No file checkpoint found for this message.",
                List.of(), null, null);
        }
        if (dryRun) {
            FileHistoryManager.DiffStats stats = history.getDiffStats(userMessageId);
            return new RewindFilesResult(true, null, stats.filesChanged(),
                stats.insertions(), stats.deletions());
        }
        try {
            history.rewind(userMessageId);
            return new RewindFilesResult(true, null, List.of(), null, null);
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new RewindFilesResult(false, "Failed to rewind: " + message,
                List.of(), null, null);
        }
    }

    @Override
    public boolean cancelAsyncMessage(String messageUuid) {
        return asyncMessageCanceller != null && asyncMessageCanceller.test(messageUuid);
    }

    @Override
    public void seedReadState(String path, long observedMtime) {
        try {
            Path normalized = PathUtils.expandPath(path, cwd).normalize();
            long diskMtime = FileUtils.modificationTimeMillis(normalized);
            if (diskMtime > observedMtime) return;
            String content = Files.readString(normalized, StandardCharsets.UTF_8);
            if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                content = content.substring(1);
            }
            content = content.replace("\r\n", "\n");
            engine.forks().getFileStateCache().set(normalized.toString(),
                new FileStateCache.FileState(content, diskMtime, null, null, false));
        } catch (IOException | RuntimeException _) {
            // Missing/unreadable/stale files are deliberately skipped; the
            // control request still succeeds and Edit will require a fresh Read.
        }
    }

    @Override
    public void stopTask(String taskId) {

        TaskRegistry.global().killTask(taskId);
    }

    @Override
    public boolean backgroundTasks(String toolUseId) {
        if (StringUtils.isBlank(toolUseId)) {
            return TaskRegistry.global().backgroundAllForegroundTasks() > 0;
        }
        return TaskRegistry.global().backgroundByToolUseId(toolUseId);
    }

    @Override
    public JsonNode reloadSkills() {
        if (pluginRuntime == null) throw new IllegalStateException("Skill reload is unavailable");
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("skillCount", pluginRuntime.reloadSkills());
        return response;
    }

    @Override
    public JsonNode readFile(String requestedPath, Long requestedMaxBytes, String encoding) {
        if (StringUtils.isBlank(requestedPath)) throw new IllegalArgumentException("Missing path");
        long maxBytes = requestedMaxBytes == null ? 10L * 1024 * 1024 : requestedMaxBytes;
        if (maxBytes < 0 || maxBytes > 100L * 1024 * 1024) {
            throw new IllegalArgumentException("max_bytes must be between 0 and 104857600");
        }
        Path path = Path.of(requestedPath);
        if (!path.isAbsolute()) path = Path.of(cwd).resolve(path);
        path = path.normalize();
        try {
            long size = Files.size(path);
            if (size > maxBytes) throw new IllegalArgumentException("File exceeds max_bytes");
            byte[] bytes = Files.readAllBytes(path);
            ObjectNode response = JsonUtils.getMapper().createObjectNode();
            response.put("path", path.toString()).put("size", bytes.length);
            if (Strings.CI.equals("base64", encoding)) {
                response.put("encoding", "base64");
                response.put("content", Base64.getEncoder().encodeToString(bytes));
            } else {
                response.put("encoding", "utf-8");
                response.put("content", new String(bytes, StandardCharsets.UTF_8));
            }
            return response;
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read file: " + error.getMessage(), error);
        }
    }

    @Override
    public JsonNode settings() {
        ObjectNode response = SettingsSnapshots.withSources(cwd);
        ObjectNode applied = response.putObject("applied");
        String model = ModelNames.parseUserSpecifiedModel(engine.configuration().getConfig().model());
        applied.put("model", model);
        String effort = EffortHelpers.resolveAppliedEffort(
            model, engine.configuration().getConfig().effortValue());
        if (effort != null) applied.put("effort", effort);
        else applied.putNull("effort");

        // control schema still reports the truthful disabled state explicitly.
        applied.put("ultracode", false);
        return response;
    }

    @Override
    public void configureHooks(JsonNode hooks) {
        if (hookEngine == null || controlBroker == null) {
            throw new IllegalStateException("SDK hook callbacks are unavailable");
        }
        if (hooks == null || !hooks.isObject()) {
            throw new IllegalArgumentException("hooks must be an object");
        }
        Map<HookEvent, List<HookMatcher>> configured = new EnumMap<>(HookEvent.class);
        hooks.fields().forEachRemaining(eventEntry -> {
            HookEvent event = HookEvent.fromConfigKey(eventEntry.getKey());
            if (event == null || !eventEntry.getValue().isArray()) return;
            List<HookMatcher> matchers = new ArrayList<>();
            eventEntry.getValue().forEach(rawMatcher -> {
                List<HookCommand> callbacks = new ArrayList<>();
                JsonNode ids = rawMatcher.path("hookCallbackIds");
                Optional<Integer> timeout = rawMatcher.has("timeout")
                    ? Optional.of(Math.max(1, rawMatcher.path("timeout").asInt()))
                    : Optional.empty();
                if (ids.isArray()) ids.forEach(id -> callbacks.add(new CallbackHook(
                    id.asText(),
                    (input, toolUseId) -> {
                        try {
                            return controlBroker.askHookCallback(id.asText(),
                                JsonUtils.getMapper().readTree(input.toJson()), toolUseId,
                                timeout.orElse(0));
                        } catch (Exception _) {
                            return JsonUtils.getMapper().createObjectNode();
                        }
                    },
                    timeout)));
                if (!callbacks.isEmpty()) {
                    Optional<String> matcher = rawMatcher.hasNonNull("matcher")
                        ? Optional.of(rawMatcher.path("matcher").asText()) : Optional.empty();
                    matchers.add(new HookMatcher(matcher, callbacks));
                }
            });
            if (!matchers.isEmpty()) configured.put(event, List.copyOf(matchers));
        });
        hookEngine.setSdkHooks(configured);
    }

    @Override
    public JsonNode setMcpServers(JsonNode servers) {
        if (mcpRuntime == null) throw new IllegalStateException("MCP runtime is unavailable");
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        ObjectNode parseErrors = JsonUtils.getMapper().createObjectNode();
        servers.fields().forEachRemaining(entry -> {
            try {
                configs.put(entry.getKey(), parseMcpServer(entry.getKey(), entry.getValue()));
            } catch (RuntimeException error) {
                parseErrors.put(entry.getKey(), error.getMessage());
            }
        });
        McpToolProvider.DynamicServerUpdate update = mcpRuntime.setDynamicServers(configs);
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.set("added", JsonUtils.getMapper().valueToTree(update.added()));
        response.set("removed", JsonUtils.getMapper().valueToTree(update.removed()));
        ObjectNode errors = response.putObject("errors");
        errors.setAll(parseErrors);
        update.errors().forEach(errors::put);
        return response;
    }

    @Override
    public void deliverMcpMessage(String serverName, JsonNode message) {
        if (mcpRuntime == null) throw new IllegalStateException("MCP runtime is unavailable");
        mcpRuntime.clientRuntime().deliverSdkMessage(serverName, message);
    }

    @Override
    public JsonNode reloadPlugins() {
        if (pluginRuntime == null || sdkStateSupplier == null) {
            throw new IllegalStateException("Plugin reload is unavailable");
        }
        var refresh = pluginRuntime.refresh();
        StdoutMessageWriter.SdkOutputState state = sdkStateSupplier.get();
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        ArrayNode commands = response.putArray("commands");
        for (SdkInboundControlHandler.CommandInfo info : state.controlCatalog().commands()) {
            ObjectNode command = commands.addObject().put("name", info.name())
                .put("description", info.description()).put("argumentHint", info.argumentHint());
            if (!info.aliases().isEmpty()) {
                command.set("aliases", JsonUtils.getMapper().valueToTree(info.aliases()));
            }
        }
        ArrayNode agents = response.putArray("agents");
        for (SdkInboundControlHandler.AgentInfo info
                : reloadAgentOrder(state.controlCatalog().agents())) {
            ObjectNode agent = agents.addObject().put("name", info.name())
                .put("description", info.description());
            if (info.model() != null && !Strings.CS.equals("inherit", info.model())) agent.put("model", info.model());
        }
        response.set("plugins", JsonUtils.getMapper().valueToTree(state.metadata().plugins()));
        ArrayNode mcp = response.putArray("mcpServers");
        for (McpServerStatus status : mcpStatus()) {
            appendMcpServerStatus(mcp.addObject(), status);
        }
        response.put("error_count", refresh.errorCount());
        return response;
    }


    static List<SdkInboundControlHandler.AgentInfo> reloadAgentOrder(
            List<SdkInboundControlHandler.AgentInfo> agents) {
        List<SdkInboundControlHandler.AgentInfo> remaining =
            new ArrayList<>(agents != null ? agents : List.of());
        List<SdkInboundControlHandler.AgentInfo> ordered = new ArrayList<>();
        moveAgent("general-purpose", remaining, ordered);
        moveAgent("statusline-setup", remaining, ordered);
        ordered.addAll(remaining);
        return List.copyOf(ordered);
    }

    private static void moveAgent(String name,
                                  List<SdkInboundControlHandler.AgentInfo> remaining,
                                  List<SdkInboundControlHandler.AgentInfo> ordered) {
        for (int index = 0; index < remaining.size(); index++) {
            if (name.equals(remaining.get(index).name())) {
                ordered.add(remaining.remove(index));
                return;
            }
        }
    }

    private static void appendMcpServerStatus(ObjectNode server, McpServerStatus status) {
        server.put("name", status.name());
        server.put("status", status.status());
        if (status.serverInfo() != null) server.set("serverInfo", status.serverInfo());
        if (status.error() != null) server.put("error", status.error());
        if (status.config() != null) server.set("config", status.config());
        if (status.scope() != null) server.put("scope", status.scope());
        if (status.tools() != null) server.set("tools", status.tools());
        if (status.capabilities() != null) server.set("capabilities", status.capabilities());
    }

    @Override
    public void reconnectMcp(String serverName) {
        if (mcpRuntime == null) throw new IllegalStateException("MCP runtime is unavailable");
        mcpRuntime.reconnectServer(serverName);
    }

    @Override
    public void toggleMcp(String serverName, boolean enabled) {
        if (mcpRuntime == null) throw new IllegalStateException("MCP runtime is unavailable");
        mcpRuntime.toggleServer(serverName, enabled);
    }

    @Override
    public void clearMcpAuth(String serverName) {
        if (mcpRuntime == null) throw new IllegalStateException("MCP runtime is unavailable");
        mcpRuntime.clearServerAuth(serverName);
    }

    @Override
    public JsonNode authenticateMcp(String serverName) {
        if (mcpRuntime == null) throw new IllegalStateException("MCP runtime is unavailable");
        McpToolProvider.AuthStart auth = mcpRuntime.authenticateServer(serverName);
        return JsonUtils.getMapper().createObjectNode()
            .put("authUrl", auth.authUrl())
            .put("requiresUserAction", auth.requiresUserAction())
            .put("callbackExpected", auth.callbackExpected())
            .put("redirectScheme", auth.redirectScheme())
            .put("state", auth.state())
            .put("callbackPort", auth.callbackPort());
    }

    @Override
    public void submitMcpOAuthCallback(String serverName, String callbackUrl) {
        if (mcpRuntime == null) throw new IllegalStateException("MCP runtime is unavailable");
        mcpRuntime.submitServerAuthCallback(serverName, callbackUrl);
    }

    @Override
    public void applyFlagSettings(JsonNode incoming) {
        SettingsSources.applyFlagSettings(incoming);
        JsonNode effective = SettingsSnapshots.withSources(cwd).path("effective");
        if (incoming.has("model")) {
            JsonNode requestedModel = incoming.get("model");
            if (requestedModel != null && !requestedModel.isNull()) {

                // even when a policy tier would otherwise win the merged
                // settings object.  Preserve aliases such as "opusplan" too.
                String requested = requestedModel.asText();

                // filters it before every turn and falls back to the built-in
                // default. Java's config model is the already-effective value,
                // so materialize that same fallback instead of exposing the
                // rejected id to the request builder.
                engine.configuration().setModel(ModelAllowlist.isAllowed(requested)
                    ? requested : ModelNames.defaultMainLoopModel());
            } else {
                // Clearing the flag tier removes the override; it must resume
                // the live env/settings/default resolver rather than pinning
                // the value that happened to be effective before the clear.
                engine.configuration().getConfig().clearUserSpecifiedModelOverride();
            }
        }
        if (incoming.has("fastMode")) {
            JsonNode fastMode = incoming.get("fastMode");
            boolean enabled = fastMode != null && fastMode.isBoolean() && fastMode.asBoolean();
            if (!engine.configuration().getFastModeController().setEnabledFromRuntime(enabled)) {
                throw new IllegalStateException("Fast Mode is unavailable for this session");
            }
        }
        if (incoming.has("effortLevel")) {
            JsonNode effort = effective.get("effortLevel");

            // session/CLI effort when the layered setting disappears. A null
            // flag value therefore clears only the flag tier, not the live
            // effortValue held by the current session.
            if (effort != null && !effort.isNull()) {
                engine.configuration().getConfig().setEffortValue(effort.asText());
            }
        }
    }

    @Override
    public void updateEnvironmentVariables(JsonNode variables) {
        if (variables == null || !variables.isObject()) {
            throw new IllegalArgumentException("Environment variables must be an object");
        }
        Map<String, String> updates = new LinkedHashMap<>();
        variables.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) updates.put(entry.getKey(), entry.getValue().asText());
        });
        SubprocessEnvironment.updateRuntime(updates);
    }

    private static McpServerConfig parseMcpServer(String name, JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Server config must be an object");
        String type = node.path("type").asText(null);
        if (StringUtils.isBlank(type)) type = node.hasNonNull("url") ? "http" : "stdio";
        String command = node.path("command").asText("");
        String url = node.path("url").asText(null);
        if (Strings.CS.equals("stdio", type) && StringUtils.isBlank(command)) throw new IllegalArgumentException("Missing command");
        if (!Strings.CS.equals("stdio", type) && !Strings.CS.equals("sdk", type)
                && (StringUtils.isBlank(url))) throw new IllegalArgumentException("Missing url");
        List<String> args = new ArrayList<>();
        JsonNode argsNode = node.path("args");
        if (argsNode.isArray()) argsNode.forEach(value -> args.add(value.asText()));
        Map<String, String> env = stringMap(node.path("env"));
        Map<String, String> headers = stringMap(node.path("headers"));
        return new McpServerConfig(name, command, args, env,
            node.path("disabled").asBoolean(false), type, url, headers);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        }
        return result;
    }

    @Override
    public CompletableFuture<String> generateSessionTitle(String description, boolean persist) {
        if (titleGenerator == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Session title generation is unavailable"));
        }
        String sessionId = engine.conversation().getSessionId();
        String model = engine.configuration().getConfig().model();
        String effort = EffortHelpers.resolveAppliedEffort(
            model, engine.configuration().getConfig().effortValue());
        return titleGenerator.generateAsync(description, sessionId,
                LlmClientAdapter.requestMetadata(sessionId), effort, true)
            .thenApply(title -> {
                if (title != null && persist && engine.execution().getTranscriptSink() != null) {
                    engine.execution().getTranscriptSink().recordAiTitle(sessionId, title);
                }
                return title;
            });
    }

    @Override
    public CompletableFuture<String> sideQuestion(String question) {
        if (sideQuestionRunner == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Side questions are unavailable"));
        }
        return CompletableFuture.supplyAsync(
            () -> sideQuestionRunner.apply(BtwCommand.wrapQuestion(question)),
            Thread::startVirtualThread);
    }

    static ObjectNode contextPayload(ContextData data) {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        ArrayNode categories = root.putArray("categories");
        for (Category category : data.categories()) {
            ObjectNode item = categories.addObject();
            item.put("name", category.name());
            item.put("tokens", category.tokens());
            item.put("color", colorName(category.color()));
        }
        root.put("totalTokens", data.totalTokens());
        root.put("maxTokens", data.maxTokens());
        root.put("rawMaxTokens", data.maxTokens());
        root.put("autocompactSource", data.autoCompactSource());
        root.put("percentage", data.percentage());
        root.set("gridRows", contextGrid(data));
        root.put("model", data.model());

        ArrayNode memory = root.putArray("memoryFiles");
        data.memoryFiles().forEach(entry -> memory.addObject()
            .put("path", entry.path()).put("type", entry.type()).put("tokens", entry.tokens()));
        ArrayNode mcp = root.putArray("mcpTools");
        data.mcpTools().forEach(entry -> mcp.addObject()
            .put("name", entry.name()).put("serverName", entry.serverName())
            .put("tokens", entry.tokens()));
        ArrayNode agents = root.putArray("agents");
        data.agents().forEach(entry -> agents.addObject()
            .put("agentType", entry.agentType()).put("source", entry.sourceDisplay())
            .put("tokens", entry.tokens()));
        if (data.slashCommands() != null) {
            ObjectNode commands = root.putObject("slashCommands");
            commands.put("totalCommands", data.slashCommands().totalCommands());
            commands.put("includedCommands", data.slashCommands().includedCommands());
            commands.put("tokens", data.slashCommands().tokens());
        }
        if (data.skills() != null) {
            ObjectNode skills = root.putObject("skills");
            skills.put("totalSkills", data.skills().totalSkills());
            skills.put("includedSkills", data.skills().skillFrontmatter().size());
            skills.put("tokens", data.skills().tokens());
            ArrayNode frontmatter = skills.putArray("skillFrontmatter");
            data.skills().skillFrontmatter().forEach(entry -> frontmatter.addObject()
                .put("name", entry.name()).put("source", settingSource(entry.sourceDisplay()))
                .put("tokens", entry.tokens()));
        }
        if (data.autoCompactThreshold() != null) {
            root.put("autoCompactThreshold", data.autoCompactThreshold());
        }
        root.put("isAutoCompactEnabled", data.autoCompactEnabled());
        if (data.messageBreakdown() != null) {
            ObjectNode breakdown = root.putObject("messageBreakdown");
            breakdown.put("toolCallTokens", data.messageBreakdown().toolCallTokens());
            breakdown.put("toolResultTokens", data.messageBreakdown().toolResultTokens());
            breakdown.put("attachmentTokens", 0);
            breakdown.put("assistantMessageTokens", data.messageBreakdown().assistantMessageTokens());
            breakdown.put("userMessageTokens", data.messageBreakdown().userMessageTokens());
            breakdown.put("redirectedContextTokens", data.messageBreakdown().redirectedContextTokens());
            breakdown.put("unattributedTokens", data.messageBreakdown().unattributedTokens());
            ArrayNode tools = breakdown.putArray("toolCallsByType");
            data.messageBreakdown().toolCallsByType().forEach(entry -> tools.addObject()
                .put("name", entry.name()).put("callTokens", entry.callTokens())
                .put("resultTokens", entry.resultTokens()));
            breakdown.putArray("attachmentsByType");
        }
        if (data.apiUsage() == null) {
            root.putNull("apiUsage");
        } else {
            ObjectNode usage = root.putObject("apiUsage");
            usage.put("input_tokens", data.apiUsage().inputTokens());
            usage.put("output_tokens", data.apiUsage().outputTokens());
            usage.put("cache_creation_input_tokens", data.apiUsage().cacheCreationInputTokens());
            usage.put("cache_read_input_tokens", data.apiUsage().cacheReadInputTokens());
        }
        return root;
    }

    private static String settingSource(String display) {
        if (display == null) return "projectSettings";
        return switch (display) {
            case "Managed" -> "policySettings";
            case "User" -> "userSettings";
            case "Project", "Local" -> "projectSettings";
            case "Built-in" -> "built-in";
            case "Plugin" -> "plugin";
            default -> display;
        };
    }

    private static ArrayNode contextGrid(ContextData data) {
        long window = Math.max(1, data.maxTokens());
        int width = window >= 1_000_000 ? 20 : 10;
        int height = 10;
        int total = width * height;
        List<ObjectNode> squares = new ArrayList<>(total);
        Category reserved = null;
        Category free = null;
        for (Category category : data.categories()) {
            if (category.isReserved()) reserved = category;
            else if (ContextData.FREE_SPACE.equals(category.name())) free = category;
            else appendSquares(squares, category, window, total, total);
        }
        int reservedCount = reserved != null
            ? Math.max(1, (int) Math.round((double) reserved.tokens() / window * total)) : 0;
        while (squares.size() < total - reservedCount) {
            squares.add(square(free, window, 1.0));
        }
        if (reserved != null) appendSquares(squares, reserved, window, total, total);

        ArrayNode rows = JsonUtils.getMapper().createArrayNode();
        for (int row = 0; row < height; row++) {
            ArrayNode cells = rows.addArray();
            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                if (index < squares.size()) cells.add(squares.get(index));
            }
        }
        return rows;
    }

    private static void appendSquares(List<ObjectNode> out, Category category,
                                      long window, int total, int cap) {
        double exact = (double) category.tokens() / window * total;
        int count = Math.max(1, (int) Math.round(exact));
        int whole = (int) Math.floor(exact);
        double fraction = exact - whole;
        for (int i = 0; i < count && out.size() < cap; i++) {
            out.add(square(category, window,
                i == whole && fraction > 0 ? fraction : 1.0));
        }
    }

    private static ObjectNode square(Category category, long window, double fullness) {
        ObjectNode square = JsonUtils.getMapper().createObjectNode();
        square.put("color", colorName(category != null
            ? category.color() : ContextData.ContextColor.PROMPT_BORDER));
        square.put("isFilled", true);
        square.put("categoryName", category != null ? category.name() : ContextData.FREE_SPACE);
        long tokens = category != null ? category.tokens() : 0;
        square.put("tokens", tokens);
        square.put("percentage", Math.round((double) tokens / window * 100));
        square.put("squareFullness", fullness);
        return square;
    }

    private static String colorName(ContextData.ContextColor color) {
        return switch (color) {
            case PROMPT_BORDER -> "promptBorder";
            case INACTIVE -> "inactive";
            case CYAN -> "cyan_FOR_SUBAGENTS_ONLY";
            case PERMISSION -> "permission";
            case CLAUDE -> "claude";
            case WARNING -> "warning";
            case PURPLE -> "purple_FOR_SUBAGENTS_ONLY";
        };
    }
}
