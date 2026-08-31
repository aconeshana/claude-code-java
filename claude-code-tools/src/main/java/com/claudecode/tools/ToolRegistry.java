package com.claudecode.tools;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.engine.RawBlocksOutput;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolContextModifier;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolResultContentForm;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.engine.PostToolUseOutputResult;
import com.claudecode.core.validation.JsonSchemaValidator;
import com.claudecode.core.engine.ToolSchemaGate;
import com.claudecode.core.engine.ToolSearchGate;
import com.claudecode.core.message.*;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.permissions.AutoModeClassifier;
import com.claudecode.permissions.CommandSuggestion;
import com.claudecode.permissions.DecisionReason;
import com.claudecode.permissions.DestructiveCommandWarning;
import com.claudecode.permissions.PowerShellDestructiveCommandWarning;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.PermissionDecisionResult;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.RuleSource;
import com.claudecode.tools.mcp.MCPTool;
import com.claudecode.tools.agent.AgentTool;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.tools.bash.BashTool;
import com.claudecode.tools.output.ToolResultStorage;

/**
 * Registry for managing tool instances.
 * Implements ToolExecutor to bridge with QuerySession.
 *
 * <ul>
 *   <li>{@code runToolUse} unknown-tool
 *       error ({@code <tool_use_error>Error: No such tool available: X</tool_use_error>},
 *       line 401); {@code checkPermissionsAndCallTool} input-validation gate
 *       ({@code <tool_use_error>InputValidationError: …</tool_use_error>}, lines 614-682,
 *       via {@link ToolInputValidation#validateInput}); the {@code tool.validateInput?}
 *       semantic gate (lines 683-733, same {@code <tool_use_error>} wrap, via {@link
 *       Tool#validateInput}); permission-denied tool_result content
 *       (uses {@code permissionDecision.message} verbatim, no tag wrap, plus
 *       {@code toolUseResult: "Error: …"} and SDK denial recording, lines 995-1069);
 *       plain interactive rejection uses model-facing {@code REJECT_MESSAGE},
 *       aborts the query, and persists the stable {@code "User rejected tool use"}
 *       marker produced by {@code StreamingToolExecutor#createSyntheticErrorMessage};
 *       an SDK {@code can_use_tool} deny with {@code interrupt:true} follows the
 *       same stable aborted-tool result instead of leaking the controller's custom
 *       denial text;
 *       tool execution failure content ({@code formatError(error)}, no tag wrap,
 *       lines 1691-1724)</li>
 *   <li> plus
 *        deny-rule message
 *       {@code "Permission to use X has been denied."} (line 1178),
 *       {@code dontAsk} mode's distinct {@code DONT_ASK_REJECT_MESSAGE}, and headless
 *       ask→deny {@code AUTO_REJECT_MESSAGE} when no permission prompt is available
 *       (lines 945-951)</li>
 *   <li> —
 *       {@code mapToolResultToToolResultBlockParam}: splitting a
 *       {@link com.claudecode.core.engine.StructuredToolOutput} into the
 *       model-facing text and the {@code toolUseResult} structured payload</li>
 *   <li>
 *        Bash's structured
 *       {@code stdout/stderr/interrupted/isImage/noOutputExpected} payload and
 *       the non-empty {@code (Bash completed with no output)} model marker.</li>
 *   <li>stable
 *       model-visible tool catalogue ordering; API definitions are emitted by
 *       tool name so identical registries serialize to the same prompt-cache
 *       prefix, including dynamically registered MCP tools; input-only tool
 *       aliases resolve to the canonical instance without creating duplicate
 *       wire definitions.</li>
 *   <li>
 *       {@code components/permissions/BashPermissionRequest} — ordered typed
 *       permission suggestions survive tool checking into the interactive UI;
 *       path suggestions are not replaced by an invented command-prefix rule.</li>
 * </ul>
 */
public class ToolRegistry implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private static void logRegistryEvent(String event, String toolName, Object detail) {
        log.debug("[REGISTRY] event={} tool={} detail={}", event, toolName, detail);
    }

    private final Map<String, Tool<?, ?>> tools = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new ConcurrentHashMap<>();
    private volatile List<String> modelVisibleToolOrder = List.of();

    private final boolean eagerInputStreaming = ToolSchemaGate.eagerInputStreamingEnabled();
    private record ToolResultBudgetKey(String sessionId, String workingDirectory, String agentId) {}
    private final Map<ToolResultBudgetKey, ToolResultBudget.State> toolResultBudgetStates =
        new ConcurrentHashMap<>();
    private volatile PermissionGate permissionGate;
    private volatile Supplier<List<Message>> messagesSupplier;
    private volatile AutoModeClassifier autoModeClassifier;
    /**
     * Whether to surface the destructive-command warning in the permission dialog.
     */
    private volatile boolean destructiveCommandWarningEnabled = false;

    /**
     * Installs (or removes) a permission gate. When set, every {@link #execute}
     * call consults the gate before invoking the tool. DENY short-circuits to
     * an error result; ASK blocks on the interactive permission callback
     * ({@code context.permissionAskCallback}) and, once the user allows, may
     * re-invoke the tool with a rewritten input (see {@link
     * com.claudecode.core.engine.PermissionAskCallback.Result#updatedInput}
     * — used by {@code requiresUserInteraction} tools such as AskUserQuestion
     * to fold collected answers into the input). When no callback is available
     * (headless), ASK auto-rejects.
     */
    public void setPermissionGate(PermissionGate gate) {
        this.permissionGate = gate;
        tools.values().stream()
            .filter(AgentTool.class::isInstance)
            .map(AgentTool.class::cast)
            .forEach(agent -> agent.setPermissionContextSupplier(
                gate == null ? null : gate::currentContext));
    }


    public Optional<ToolUseTag> resolveToolUseTag(
            String toolName, String inputJson, ToolUseRenderContext context) {
        Tool<?, ?> tool = resolveTool(toolName);
        if (tool == null || StringUtils.isBlank(inputJson)) return Optional.empty();
        try {
            JsonNode input = JsonUtils.getMapper().readTree(inputJson);
            if (input == null || !input.isObject()
                    || ToolInputValidation.validateInput(tool, input) != null) {
                return Optional.empty();
            }
            return tool.renderToolUseTag(
                input, context != null ? context : ToolUseRenderContext.empty());
        } catch (RuntimeException | IOException error) {
            log.debug("Unable to render tool-use tag for {}", toolName, error);
            return Optional.empty();
        }
    }

    /**
     * Installs the live conversation-history supplier (typically {@code
     * engine::getMessages}), used only to build the schema-not-sent hint appended
     * to a deferred tool's structural validation failure (see {@link #execute}).
     * Optional — null (unwired) just means that hint never appends, no other
     * behavior changes.
     */
    public void setMessagesSupplier(Supplier<List<Message>> supplier) {
        this.messagesSupplier = supplier;
    }


    public void setAutoModeClassifier(AutoModeClassifier classifier) {
        this.autoModeClassifier = classifier;
    }

    public PermissionGate getPermissionGate() {
        return permissionGate;
    }

    /**
     * Enables/disables the destructive-command warning surfaced in the permission dialog.
     */
    public void setDestructiveCommandWarningEnabled(boolean enabled) {
        this.destructiveCommandWarningEnabled = enabled;
    }

    /** Registers a tool. Replaces any existing tool with the same name. */
    public void register(Tool<?, ?> tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        Tool<?, ?> previous = tools.get(tool.name());
        if (previous != null) {
            aliases.entrySet().removeIf(entry -> entry.getValue().equals(previous.name()));
        }
        tools.put(tool.name(), tool);
        if (tool instanceof AgentTool agent) {
            PermissionGate gate = permissionGate;
            agent.setPermissionContextSupplier(gate == null ? null : gate::currentContext);
        }
        for (String alias : tool.aliases()) {
            if (StringUtils.isBlank(alias) || alias.equals(tool.name())) continue;
            if (tools.containsKey(alias)) {
                throw new IllegalArgumentException("Tool alias conflicts with canonical tool name: " + alias);
            }
            aliases.put(alias, tool.name());
        }
    }

    /**
     * Removes tool(s) whose name matches {@code predicate}. Used when an MCP
     * server disconnects / clear-auth so its tools stop appearing in the
     * model's tool catalog.
     *
     * @return number of tools removed
     */
    public int unregisterMatching(Predicate<String> predicate) {
        int removed = 0;
        for (String name : new ArrayList<>(tools.keySet())) {
            if (predicate.test(name)) {
                tools.remove(name);
                aliases.entrySet().removeIf(entry -> entry.getValue().equals(name));
                removed++;
            }
        }
        return removed;
    }

    /** Gets a tool by name. */
    public Optional<Tool<?, ?>> get(String name) {
        return Optional.ofNullable(resolveTool(name));
    }

    /** Returns all registered tools. */
    public Collection<Tool<?, ?>> getAll() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /** Preserves an explicit custom-agent tool order at the model boundary. */
    public void setModelVisibleToolOrder(List<String> names) {
        modelVisibleToolOrder = names == null ? List.of() : List.copyOf(names);
    }

    /** Returns tools matching the given predicate. */
    public List<Tool<?, ?>> filter(Predicate<Tool<?, ?>> predicate) {
        return tools.values().stream()
                .filter(predicate)
                .toList();
    }

    /** Returns the number of registered tools. */
    public int size() {
        return tools.size();
    }

    @Override
    public boolean isConcurrencySafe(String toolName, JsonNode input) {
        Tool<?, ?> tool = resolveTool(toolName);
        if (tool == null) return false;
        try {
            return isConcurrencySafeRaw(tool, input);
        } catch (Exception _) {
            return false;
        }
    }

    @Override
    public PostToolUseOutputResult processPostToolUseOutput(
            String toolName, JsonNode originalInput, JsonNode updatedOutput,
            ToolResult originalResult, ToolExecutionContext context) {
        Tool<?, ?> tool = resolveTool(toolName);
        if (tool == null) {
            return new PostToolUseOutputResult.Rejected("No such tool available: " + toolName);
        }
        try {
            JsonNode schema = tool.outputSchema();
            if (schema != null) {
                var validation = JsonSchemaValidator.shared()
                    .validateAgainstJsonSchema(updatedOutput, schema);
                if (validation.isFailure()) {
                    return new PostToolUseOutputResult.Rejected(
                        String.join("; ", validation.errors()));
                }
            }
            ToolResult mapped = tool.mapUpdatedOutput(updatedOutput, originalInput, context);
            if (mapped == null) {
                return new PostToolUseOutputResult.Rejected(
                    "Tool mapper returned no result for hook replacement");
            }
            ToolResult merged = new ToolResult(mapped.content(), mapped.isError(),
                originalResult.acceptFeedback(), updatedOutput,
                mapped.structuredOutput() != null ? mapped.structuredOutput()
                    : originalResult.structuredOutput(),
                originalResult.newMessages(), originalResult.contextModifier(),
                mapped.includeIsErrorField(), originalResult.afterResultEmitted(),
                originalResult.mcpMeta(), mapped.contentForm(),
                originalResult.userFeedbackBlocks());
            return new PostToolUseOutputResult.Applied(
                ToolResultStorage.process(merged, tool, context));
        } catch (Exception error) {
            String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
            return new PostToolUseOutputResult.Rejected(message);
        }
    }


    @Override
    public List<Message> applyToolResultBudget(List<Message> messages, String sessionId,
                                               String workingDirectory, String agentId) {
        if (!aggregateBudgetEnabled() || messages == null || sessionId == null || StringUtils.isBlank(sessionId)) {
            return messages;
        }
        ToolResultBudgetKey key = new ToolResultBudgetKey(sessionId,
            workingDirectory == null ? System.getProperty("user.dir", ".") : workingDirectory,
            agentId == null ? "" : agentId);
        ToolResultBudget.State state = toolResultBudgetStates.computeIfAbsent(key,
            _ -> restoreToolResultBudgetState(messages, key));
        int limit = aggregateBudgetLimit();
        return ToolResultBudget.apply(messages, state, limit,
            toolName -> {
                Tool<?, ?> tool = resolveTool(toolName);
                return tool != null && tool.maxResultSizeChars() == Integer.MAX_VALUE;
            },
            (_, toolUseId, content) -> ToolResultStorage.persistForBudget(
                toolUseId, content, key.workingDirectory(), key.sessionId()));
    }

    @Override
    public void restoreToolResultBudget(List<Message> messages,
                                        List<ToolResultBudget.Replacement> replacements,
                                        String sessionId,
                                        String workingDirectory,
                                        String agentId) {
        if (StringUtils.isBlank(sessionId)) return;
        ToolResultBudgetKey key = new ToolResultBudgetKey(sessionId,
            workingDirectory == null ? System.getProperty("user.dir", ".") : workingDirectory,
            agentId == null ? "" : agentId);
        toolResultBudgetStates.put(key, ToolResultBudget.restore(messages, replacements));
    }

    @Override
    public List<ToolResultBudget.Replacement> drainToolResultBudgetReplacements(
            String sessionId, String workingDirectory, String agentId) {
        if (StringUtils.isBlank(sessionId)) return List.of();
        ToolResultBudgetKey key = new ToolResultBudgetKey(sessionId,
            workingDirectory == null ? System.getProperty("user.dir", ".") : workingDirectory,
            agentId == null ? "" : agentId);
        ToolResultBudget.State state = toolResultBudgetStates.get(key);
        return state == null ? List.of() : state.drainNewReplacements();
    }

    private static ToolResultBudget.State restoreToolResultBudgetState(
            List<Message> messages, ToolResultBudgetKey key) {
        try {
            SessionManager manager = new SessionManager(key.workingDirectory());
            Path transcript = StringUtils.isBlank(key.agentId())
                ? manager.getSessionFile(key.sessionId())
                : manager.getAgentTranscriptPath(key.sessionId(), key.agentId());
            List<ToolResultBudget.Replacement> records = new SessionStorage()
                .readContentReplacements(transcript);
            return ToolResultBudget.restore(messages, records);
        } catch (Throwable _) {
            // A missing/unreadable transcript is a cold start; the in-memory
            // budget remains fail-open and will persist new decisions normally.
            return ToolResultBudget.newState();
        }
    }

    private static boolean aggregateBudgetEnabled() {
        try {
            var env = SubprocessEnvironment.snapshot();
            if (Strings.CS.equals("test", env.get("NODE_ENV"))
                    || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_BEDROCK"))
                    || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_VERTEX"))
                    || EnvUtils.isEnvTruthy(env.get("CLAUDE_CODE_USE_FOUNDRY"))
                    || env.containsKey("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC")
                    || env.containsKey("DISABLE_TELEMETRY")) return false;
            JsonNode global = Files.isRegularFile(ClaudePaths.GLOBAL_JSON)
                ? JsonUtils.readJson(ClaudePaths.GLOBAL_JSON) : null;
            return global != null && global.path("cachedGrowthBookFeatures")
                .path("tengu_hawthorn_steeple").asBoolean(false);
        } catch (Exception _) {
            return false;
        }
    }

    private static int aggregateBudgetLimit() {

        // Keep the GrowthBook override semantics identical while retaining
        // the current external feature gate.
        int fallback = 200_000;
        try {
            JsonNode global = Files.isRegularFile(ClaudePaths.GLOBAL_JSON)
                ? JsonUtils.readJson(ClaudePaths.GLOBAL_JSON) : null;
            JsonNode override = global == null ? null : global.path("cachedGrowthBookFeatures")
                .path("tengu_hawthorn_window");
            return override != null && override.isNumber() && override.canConvertToInt()
                && override.asInt() > 0 ? override.asInt() : fallback;
        } catch (Exception _) {
            return fallback;
        }
    }

    @Override
    public McpAttribution mcpAttribution(String toolName) {
        Tool<?, ?> tool = resolveTool(toolName);
        if (tool instanceof MCPTool mcpTool) {
            var info = mcpTool.getToolInfo();
            return new McpAttribution(info.serverId(), info.name());
        }
        return ToolExecutor.super.mcpAttribution(toolName);
    }

    /**
     * Implements ToolExecutor to bridge with QuerySession.
     * Looks up the tool by name, converts JsonNode input, and executes.
     */
    @Override
    public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context) {
        logRegistryEvent("execute.start", toolName, "-");
        if (context != null && context.toolDurationTiming() != null) {
            context.toolDurationTiming().claimBoundary();
        }
        Tool<?, ?> tool = resolveTool(toolName);
        if (tool == null) {
            logRegistryEvent("execute.unknown_tool", toolName, "-");
            return ToolResult.error(
                "<tool_use_error>Error: No such tool available: " + toolName + "</tool_use_error>");
        }
        JsonNode originalInput = input;
        input = ToolInputCoercion.coerce(tool.name(), input);
        JsonNode validationInput = input;
        if (Strings.CS.equals("SendMessage", tool.name()) && input != null && input.isObject()) {

            // assistant block. Those routing aliases are observational only;
            // Zod validates the original public input before backfill.
            ObjectNode publicInput = input.deepCopy();
            publicInput.remove(List.of("type", "recipient", "content"));
            validationInput = publicInput;
        }
        String validationError = ToolInputValidation.validateInput(tool, validationInput);
        if (validationError != null) {
            logRegistryEvent("validation.structural_failed", toolName, validationError);
            String steer = ToolInputCoercion.validationErrorSteer(tool.name(), originalInput);
            if (steer != null) validationError += "\n" + steer;
            String hint = buildSchemaNotSentHint(tool, context);
            return ToolResult.error(
                "<tool_use_error>InputValidationError: " + validationError + hint + "</tool_use_error>");
        }
        ValidationResult semanticValidation = validateRaw(tool, input, context);
        if (semanticValidation instanceof ValidationResult.Invalid(String message)) {

            // structural gate above, but this is a business-logic refusal (e.g.
            // ExitWorktreeTool's dirty-worktree safety gate), not a malformed-input one.
            logRegistryEvent("validation.semantic_failed", toolName, message);
            return ToolResult.error("<tool_use_error>" + message + "</tool_use_error>")
                .withToolUseResult("Error: " + message);
        }
        PermissionGate gate = this.permissionGate;
        if (gate != null) {
// Consult the tool's own checkPermissions and merge it into the rules/mode decision.
            var permCtx = gate.currentContext();
            PermissionDecision toolDecision = tool.checkPermissions(input, permCtx);
            PermissionDecision.Ask toolAsk = toolDecision instanceof PermissionDecision.Ask ask
                ? ask : null;
            JsonNode permissionInput = toolAsk != null && toolAsk.updatedInput() != null
                ? toolAsk.updatedInput() : input;
            String canonicalName = tool.name();
            PermissionDecisionResult result = gate.checkDetailed(
                canonicalName, permissionInput, toolDecision);
            logRegistryEvent("permission.decision", toolName, result.decision());
// Interactive tools (requiresUserInteraction) must always consult the human even when
// the mode would otherwise auto-allow (BYPASS/AUTO/ DONT_ASK) — their answer is
// collected during the prompt, so an automatic Allow would skip collection.
            if (tool.requiresUserInteraction() && result.decision() instanceof PermissionDecision.Allow) {
                result = new PermissionDecisionResult(PermissionDecision.ask(), result.reason());
            }
            if (result.decision() instanceof PermissionDecision.Deny deny) {


                String denial = StringUtils.isNotBlank(deny.message())
                    ? deny.message()
                    : result.reason() instanceof DecisionReason.Mode(PermissionMode mode1)
                        && mode1 == PermissionMode.DONT_ASK
                        ? MessageConstants.dontAskRejectMessage(canonicalName)
                        : "Permission to use " + canonicalName + " has been denied.";
                recordPermissionDenial(context, canonicalName, permissionInput);
                return ToolResult.error(denial).withToolUseResult("Error: " + denial);
            }
            if (result.decision() instanceof PermissionDecision.Ask) {
                AutoModeClassifier classifier = this.autoModeClassifier;
                if ((permCtx.mode() == PermissionMode.AUTO
                        || permissionGate != null && permissionGate.isPlanAutoModeActive())
                        && classifier != null
                        && !tool.requiresUserInteraction()) {
                    String action = compactToolUse(canonicalName, tool, permissionInput);
                    if (action.isEmpty()) {

                        // means the tool has no classifier-relevant security action.
                        return executeRegisteredTool(toolName, tool, permissionInput, context, null);
                    }
                    AutoModeClassifier.Decision classifierDecision;
                    try {
                        classifierDecision = classifier.classify(new AutoModeClassifier.Request(
                            context != null ? context.currentModel() : null,
                            context != null ? context.sessionId() : null,
                            context != null ? context.workingDirectory() : null,
                            canonicalName,
                            context != null ? context.toolUseId() : null,
                            permissionInput,
                            compactTranscript(context != null ? context.toolUseId() : null, action)));
                    } catch (RuntimeException _) {
                        classifierDecision = AutoModeClassifier.Decision.unavailable(
                            "Classifier unavailable - blocking for safety");
                    }
                    if (!classifierDecision.shouldBlock()) {
                        return executeRegisteredTool(toolName, tool, permissionInput, context, null);
                    }
                    String denial = classifierDecision.unavailable()
                        ? MessageConstants.buildClassifierUnavailableMessage(
                            canonicalName,
                            context != null && context.currentModel() != null
                                ? context.currentModel() : "The classifier model")
                        : MessageConstants.buildYoloRejectionMessage(classifierDecision.reason());
                    recordPermissionDenial(context, canonicalName, permissionInput);
                    return ToolResult.error(denial).withToolUseResult("Error: " + denial);
                }
                PermissionAskCallback askCb =
                    context != null ? context.permissionAskCallback() : null;
                if (askCb == null) {
// No interactive prompt available — headless (SDK / CI /
// REPL-less) ask→deny.

                    //      decision but no callback exists, it returns
// AUTO_REJECT_MESSAGE(tool.name).
                    String denial = MessageConstants.autoRejectMessage(canonicalName);
                    recordPermissionDenial(context, canonicalName, permissionInput);
                    return ToolResult.error(denial).withToolUseResult("Error: " + denial);
                }
                PermissionAskContext askCtx = buildAskContext(
                    canonicalName, permissionInput, result,
                    destructiveCommandWarningEnabled, toolAsk,
                    context.toolUseId());
                PermissionAskCallback.Result askResult = askCb.ask(askCtx);
                if (!askResult.allowed()) {
                    String fb = askResult.feedback();
                    if (askResult.directDenial()
                            && context.abortController() != null
                            && context.abortController().isAborted()) {
                        // SDK deny+interrupt aborts while normalizing the host
                        // response. The streaming tool executor then treats the
                        // in-flight denial as a user cancellation, replacing the
                        // host's explanatory text with the stable reject payload.
                        return ToolResult.error(MessageConstants.REJECT_MESSAGE)
                            .withToolUseResult("User rejected tool use");
                    }
                    boolean isSubagent = context.agentId() != null;
                    if (fb == null) {
// No-feedback reject = pure cancel.
                        if (isSubagent) {
                            return ToolResult.error(MessageConstants.SUBAGENT_REJECT_MESSAGE)
                                .withToolUseResult("User rejected tool use");
                        }
                        if (context.abortController() != null) {
                            context.abortController().abort("user_reject_permission");
                        }
                        return ToolResult.error(MessageConstants.REJECT_MESSAGE)
                            .withToolUseResult("User rejected tool use");
                    }
                    if (askResult.directDenial()) {
                        // SDK permission hosts return the final model-facing
                        // message directly; do not add the terminal UI reject
                        // prefix used for a local "No + feedback" response.
                        return ToolResult.error(fb).withToolUseResult("Error: " + fb);
                    }
// No+feedback = ask-back with feedback.
                    String rejection = (isSubagent
                            ? MessageConstants.SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX
                            : MessageConstants.REJECT_MESSAGE_WITH_REASON_PREFIX) + fb;
                    return ToolResult.error(rejection)
                        .withToolUseResult("Error: " + rejection)
                        .withUserFeedbackBlocks(askResult.feedbackContentBlocks());
                }
                if (!askResult.updatedPermissions().isEmpty()) {
                    gate.applyUpdates(askResult.updatedPermissions());
                }
                // Allowed — an interactive tool may have rewritten the input during the
                // prompt (e.g. AskUserQuestion folded the collected answers into updatedInput);
                // use it in place of the original. Other tools leave updatedInput null, so
                // this is a no-op for them.
                JsonNode execInput = askResult.updatedInput() != null
                    ? askResult.updatedInput() : permissionInput;
                // Record optional acceptFeedback (Yes+Tab amend) so the engine can append it
                // as an extra text content block alongside the tool_result.
                String acceptFeedback = askResult.feedback();
                return executeRegisteredTool(toolName, tool, execInput, context, acceptFeedback);
            }
        }
        return executeRegisteredTool(toolName, tool, input, context, null);
    }


    private List<String> compactTranscript(String currentToolUseId, String action) {
        List<String> compact = new ArrayList<>();
        Supplier<List<Message>> supplier = this.messagesSupplier;
        List<Message> messages = List.of();
        if (supplier != null) {
            try {
                List<Message> supplied = supplier.get();
                if (supplied != null) messages = supplied;
            } catch (RuntimeException _) {
                // The pending action remains classifiable even if history is unavailable.
            }
        }
        for (Message message : messages) {
            if (message instanceof UserMessage user && user.message() != null) {
                if (user.message().text() != null) {
                    compact.add("User: " + user.message().text() + "\n");
                } else if (user.message().blocks() != null) {
                    for (var block : user.message().blocks()) {
                        if (block instanceof TextBlock(String text1)) {
                            compact.add("User: " + text1 + "\n");
                        }
                    }
                }
            } else if (message instanceof AssistantMessage assistant
                    && assistant.message() != null
                    && assistant.message().content() != null) {
                for (var block : assistant.message().content()) {
                    if (!(block instanceof ToolUseBlock toolUse)) continue;
                    if (currentToolUseId != null && currentToolUseId.equals(toolUse.id())) continue;
                    Tool<?, ?> historyTool = resolveTool(toolUse.name());
                    if (historyTool == null) continue;
                    String line = compactToolUse(toolUse.name(), historyTool, toolUse.input());
                    if (!line.isEmpty()) compact.add(line);
                }
            } else if (message instanceof AttachmentMessage attachment
                    && attachment.payload() instanceof QueuedCommandAttachment queued) {
                compact.add("User: " + queued.text() + "\n");
            }
        }
        compact.add(action);
        return List.copyOf(compact);
    }

    private static String compactToolUse(String visibleName, Tool<?, ?> tool, JsonNode input) {
        Object projected;
        try {
            projected = tool.toAutoClassifierInput(input);
            if (projected == null) projected = input;
        } catch (RuntimeException _) {
            projected = input;
        }
        if (projected instanceof String text) {
            return text.isEmpty() ? "" : visibleName + " " + text + "\n";
        }
        return visibleName + " " + JsonUtils.toJson(projected) + "\n";
    }

    private Tool<?, ?> resolveTool(String requestedName) {
        Tool<?, ?> canonical = tools.get(requestedName);
        if (canonical != null) return canonical;
        String canonicalName = aliases.get(requestedName);
        return canonicalName == null ? null : tools.get(canonicalName);
    }

    @SuppressWarnings("unchecked")
    private static void recordPermissionDenial(ToolExecutionContext context,
                                               String toolName,
                                               JsonNode input) {
        if (context == null || context.permissionDenialSink() == null) return;
        Map<String, Object> toolInput = input != null && input.isObject()
            ? JsonUtils.getMapper().convertValue(input, Map.class)
            : Map.of();
        String sdkToolName = Strings.CS.equals("Agent", toolName) ? "Task" : toolName;
        context.permissionDenialSink().accept(new SDKMessage.PermissionDenial(
            sdkToolName, context.toolUseId(), toolInput));
    }

    private ToolResult executeRegisteredTool(String toolName, Tool<?, ?> tool, JsonNode input,
                                                ToolExecutionContext context, String acceptFeedback) {
        ToolExecutionContext.ToolDurationTiming timing =
            context != null ? context.toolDurationTiming() : null;
        long startNanos = timing != null ? timing.startNanos() : 0L;
        try {
            logRegistryEvent("execute.invoke", toolName, "-");
            ToolCallResult<?> invocation = executeRaw(tool, input, context);
            Object result = invocation.rawResult();
            logRegistryEvent("execute.success", toolName, "-");
            ToolResult mapped = invocation.mappedResult();
            ToolResult success = applySkillModifier(mapped != null
                ? mapped : toSuccessResult(result, tool, input));
            success = ToolResultStorage.process(success, tool, context);
            return acceptFeedback != null ? success.withAcceptFeedback(acceptFeedback) : success;
        } catch (Exception e) {
            logRegistryEvent("execute.failure", toolName, e.toString());
            return ToolResult.error(ToolErrors.formatError(e));
        } finally {
            if (timing != null) timing.recordSince(startNanos);
        }
    }


    private ToolResult applySkillModifier(ToolResult result) {
        ToolContextModifier mod = result.contextModifier();
        if (mod != null && mod.allowedTools() != null && !mod.allowedTools().isEmpty()
                && permissionGate != null) {
            List<PermissionRule> rules = mod.allowedTools().stream()
                .map(t -> PermissionRule.of(t, PermissionBehavior.ALLOW, RuleSource.SKILL))
                .toList();
            permissionGate.addRules(rules);
        }
        return result;
    }

    /**
     * Guidance appended to a deferred tool's structural {@code InputValidationError}.
     */
    private String buildSchemaNotSentHint(Tool<?, ?> tool, ToolExecutionContext context) {
        if (!toolSearchEnabled(context)) return "";
        if (!tools.containsKey(ToolSearchTool.NAME)) return "";
        if (!ToolSearchTool.isDeferredTool(tool)) return "";
        Supplier<List<Message>> supplier = this.messagesSupplier;
        if (supplier == null) return "";
        List<Message> messages;
        try {
            messages = supplier.get();
        } catch (Exception _) {
            return "";
        }
        if (messages == null || ToolSearchGate.extractDiscoveredToolNames(messages).contains(tool.name())) {
            return "";
        }
        return "\n\nThis tool's schema was not sent to the API — it was not in the discovered-tool "
            + "set derived from message history. Without the schema in your prompt, typed parameters "
            + "(arrays, numbers, booleans) get emitted as strings and the client-side parser rejects "
            + "them. Load the tool first: call " + ToolSearchTool.NAME + " with query \"select:"
            + tool.name() + "\", then retry this call.";
    }

    private static boolean toolSearchEnabled(ToolExecutionContext context) {
        return context != null
            ? ToolSearchGate.isEnabled(context.currentModel())
            : ToolSearchGate.isEnabled();
    }

    private static boolean isModelVisible(Tool<?, ?> tool, ToolExecutionContext context) {
        if (!tool.isEnabled()) return false;
        return !ToolSearchTool.NAME.equals(tool.name()) || toolSearchEnabled(context);
    }

    /**
     * Wraps a raw tool return value into a success {@link ToolResult}.
     */
    private static ToolResult toSuccessResult(Object execResult, Tool<?, ?> tool,
                                                JsonNode input) {
        if (execResult instanceof ToolResult tr) {

            return tr;
        }
        if (execResult instanceof StructuredToolOutput(String text1, Object toolUseResult)) {
            ToolResult result = ToolResult.success(text1).withToolUseResult(toolUseResult);

            return Strings.CS.equals("Workflow", tool.name())
                ? result.withExplicitIsErrorField() : result;
        }
        if (execResult instanceof RawBlocksOutput(
            List<ContentBlock> blocks
        )) {
            return new ToolResult(blocks, false)
                .withContentForm(ToolResultContentForm.BLOCKS);
        }
        String text = execResult != null ? execResult.toString() : "";
        if (Strings.CS.equals("EnterWorktree", tool.name())
                && Strings.CS.startsWith(text, "Error: ")) {
            String modelText = text.substring("Error: ".length());
            return ToolResult.error(modelText).withToolUseResult(text);
        }
        if (Strings.CS.equals("Bash", tool.name())
                && (input == null || !input.path("run_in_background").asBoolean(false))) {
            String command = input != null ? input.path("command").asText("") : "";
            boolean noOutputExpected = BashTool.isSilentBashCommand(command);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stdout", text);
            data.put("stderr", "");
            data.put("interrupted", false);
            data.put("isImage", false);
            data.put("noOutputExpected", noOutputExpected);
            String modelText = text.isEmpty()
                ? "(Bash completed with no output)" : text;
            return ToolResult.success(modelText)
                .withToolUseResult(data)
                .withExplicitIsErrorField();
        }
        return ToolResult.success(text);
    }

    PermissionAskContext buildAskContext(String toolName, JsonNode input,
                                            PermissionDecisionResult result,
                                            boolean destructiveCommandWarningEnabled) {
        return buildAskContext(toolName, input, result,
            destructiveCommandWarningEnabled, null, null);
    }

    PermissionAskContext buildAskContext(String toolName, JsonNode input,
                                            PermissionDecisionResult result,
                                            boolean destructiveCommandWarningEnabled,
                                            PermissionDecision.Ask toolAsk,
                                            String toolUseId) {
        PermissionDecision.Ask effectiveAsk = toolAsk != null
            ? toolAsk
            : result.decision() instanceof PermissionDecision.Ask ask ? ask : null;
        String reasonType;
        String reasonDetail;
        // Exhaustive over the DecisionReason union; only Rule/Mode are produced
        // by this port's permission flow today, so the remaining variants fall


        switch (result.reason()) {
            case DecisionReason.Rule r -> {
                reasonType = "rule";
                reasonDetail = PermissionEngine.permissionRuleToString(r.rule());
            }
            case DecisionReason.Mode m -> {
                reasonType = "mode";
                reasonDetail = m.mode().name();
            }
            default -> {
                reasonType = null;
                reasonDetail = null;
            }
        }

        String suggestionRuleContent = null;
        String suggestionLabel = null;
        String destructiveWarning = null;
        List<PermissionUpdate> suggestions = effectiveAsk == null
            ? List.of() : effectiveAsk.suggestions();
        if (input != null) {
            JsonNode cmdNode = input.get("command");
            if (cmdNode != null && cmdNode.isTextual()) {
                String command = cmdNode.asText();

                // which only computes bash command suggestions.
                if (Strings.CS.equals("Bash", toolName) && suggestions.isEmpty()) {
                    String cwd = System.getProperty("user.dir", ".");
                    Optional<CommandSuggestion> sug = CommandSuggestion.forBash(command, cwd);
                    if (sug.isPresent()) {
                        suggestionRuleContent = sug.get().ruleContent();
                        suggestionLabel = sug.get().label();
                        suggestions = List.of(new PermissionUpdate.AddRules(
                            List.of(new PermissionUpdate.RuleValue(
                                toolName, suggestionRuleContent)),
                            PermissionUpdate.Behavior.ALLOW,
                            PermissionUpdate.Destination.LOCAL_SETTINGS));
                    }
                }
                // Destructive-command warning: Bash uses DestructiveCommandWarning,

                // into tools/BashTool and tools/PowerShellTool respectively).
                if (destructiveCommandWarningEnabled) {
                    Optional<String> warn = switch (toolName) {
                        case "Bash"       -> DestructiveCommandWarning.check(command);
                        case "PowerShell" -> PowerShellDestructiveCommandWarning.check(command);
                        default           -> Optional.empty();
                    };
                    if (warn.isPresent()) {
                        destructiveWarning = warn.get();
                    }
                }
            }
        }

        if (effectiveAsk != null && effectiveAsk.suggestionRuleContent() != null) {
            suggestionRuleContent = effectiveAsk.suggestionRuleContent();
            suggestionLabel = effectiveAsk.suggestionLabel();
            if (suggestions.isEmpty()) {
                suggestions = List.of(new PermissionUpdate.AddRules(
                    List.of(new PermissionUpdate.RuleValue(
                        toolName, suggestionRuleContent)),
                    PermissionUpdate.Behavior.ALLOW,
                    PermissionUpdate.Destination.LOCAL_SETTINGS));
            }
        }
        Tool<?, ?> approvalTool = tools.get(toolName);
        String toolDescription = approvalTool == null ? "" : approvalTool.description();
        return PermissionAskContext.builder(toolName, input)
            .toolUseId(toolUseId)
            .decisionReason(reasonType, reasonDetail)
            .suggestion(suggestionRuleContent, suggestionLabel)
            .destructiveWarning(destructiveWarning)
            .blockedPath(effectiveAsk == null ? null : effectiveAsk.blockedPath())
            .customMessage(effectiveAsk == null ? null : effectiveAsk.message())
            .suggestions(suggestions)
            .toolDescription(toolDescription)
            .build();
    }

    @Override
    public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
        return getToolDefinitions((ToolExecutionContext) null);
    }

    @Override
    public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
            ToolExecutionContext context) {
        Map<String, Integer> order = modelVisibleOrderIndex();
        return tools.values().stream()
            .filter(tool -> isModelVisible(tool, context))
            .sorted(Comparator.comparingInt((Tool<?, ?> tool) ->
                    order.getOrDefault(tool.name(), Integer.MAX_VALUE))
                .thenComparing(Tool::name))
            .map(t -> new StreamingClient.StreamRequest.ToolDef(
                t.name(), t.prompt(context), modelVisibleSchema(t), null, null, null, null,
                false, t.strict(), eagerInputStreaming))
            .toList();
    }


    @Override
    public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(Set<String> discoveredToolNames) {
        return getToolDefinitions(discoveredToolNames, null);
    }

    @Override
    public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
            Set<String> discoveredToolNames, ToolExecutionContext context) {
        if (!toolSearchEnabled(context)) {
            return getToolDefinitions(context);
        }
        Set<String> discovered = discoveredToolNames != null ? discoveredToolNames : Set.of();
        Map<String, Integer> order = modelVisibleOrderIndex();
        return tools.values().stream()
            .filter(tool -> isModelVisible(tool, context))
            .filter(t -> !ToolSearchTool.isDeferredTool(t) || discovered.contains(t.name()))
            .sorted(Comparator.comparingInt((Tool<?, ?> tool) ->
                    order.getOrDefault(tool.name(), Integer.MAX_VALUE))
                .thenComparing(Tool::name))
            .map(t -> new StreamingClient.StreamRequest.ToolDef(
                t.name(), t.prompt(context), modelVisibleSchema(t), null, null, null, null,
                ToolSearchTool.isDeferredTool(t), t.strict(), eagerInputStreaming))
            .toList();
    }

    private Map<String, Integer> modelVisibleOrderIndex() {
        Map<String, Integer> order = new HashMap<>();
        for (int index = 0; index < modelVisibleToolOrder.size(); index++) {
            order.putIfAbsent(modelVisibleToolOrder.get(index), index);
        }
        return order;
    }

    /**
     * Full enabled inventory for {@code analyzeContextUsage}. Unlike the model
     * request view, deferred definitions remain present and are tagged so the
     * analyzer can count the eager and deferred groups independently.
     */
    public List<StreamingClient.StreamRequest.ToolDef> getContextAnalysisToolDefinitions() {
        return tools.values().stream()
            .filter(Tool::isEnabled)
            .sorted(Comparator.comparing(Tool::name))
            .map(t -> new StreamingClient.StreamRequest.ToolDef(
                t.name(), t.prompt(null), modelVisibleSchema(t), null, null, null, null,
                ToolSearchTool.isDeferredTool(t), t.strict(), eagerInputStreaming))
            .toList();
    }


    private static JsonNode modelVisibleSchema(Tool<?, ?> tool) {
        JsonNode schema = tool.inputSchema();
        if (AgentTeamsEnabled.isEnabled() || schema == null || !schema.isObject()) return schema;
        List<String> fields = switch (tool.name()) {
            case "Agent" -> List.of("name", "team_name", "mode", "cwd");
            case "ExitPlanMode" -> List.of("launchSwarm", "teammateCount");
            default -> List.of();
        };
        if (fields.isEmpty()) return schema;
        JsonNode copy = schema.deepCopy();
        JsonNode properties = copy.get("properties");
        if (properties != null && properties.isObject()) {
            for (String field : fields) ((ObjectNode) properties).remove(field);
        }
        return copy;
    }

    @Override
    public Map<String, String> getToolNameAliases() {
        return Map.copyOf(aliases);
    }

    @Override
    public List<String> getDeferredToolNames() {
        return tools.values().stream()
            .filter(Tool::isEnabled)
            .filter(ToolSearchTool::isDeferredTool)
            .sorted(Comparator.comparing(Tool::name))
            .map(Tool::name)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private <I, O> ToolCallResult<O> executeRaw(
            Tool<I, O> tool, JsonNode input, ToolExecutionContext context) {
        // Tools that accept JsonNode directly
        return tool.callWithResult((I) input, context);
    }

    @SuppressWarnings("unchecked")
    private <I, O> ValidationResult validateRaw(Tool<I, O> tool, JsonNode input, ToolExecutionContext context) {
        return tool.validateInput((I) input, context);
    }

    /**
     * Bridges the wildcard {@link Tool} to its input-aware concurrency check.
     */
    @SuppressWarnings("unchecked")
    private <I, O> boolean isConcurrencySafeRaw(Tool<I, O> tool, JsonNode input) {
        return tool.isConcurrencySafe((I) input);
    }
}
