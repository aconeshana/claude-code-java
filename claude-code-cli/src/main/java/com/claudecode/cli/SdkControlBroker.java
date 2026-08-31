package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.OrphanedPermission;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.RefusalFallbackPrompt;
import com.claudecode.core.engine.PermissionUpdateJsonCodec;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.RefusalFallbackDecision;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.WireMessages;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.config.PermissionSettings;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SdkControlBroker implements RefusalFallbackPrompt {

    private static final Logger LOG = LoggerFactory.getLogger(SdkControlBroker.class);
    private static final ObjectMapper MAPPER = JsonUtils.getMapper();
    private static final long DEFAULT_USER_DIALOG_TIMEOUT_MS = 300_000L;

    /** A pending permission ask awaiting its matching control_response. */
    private record PendingAsk(String requestId, CompletableFuture<JsonNode> future,
                              String toolUseId, String toolName, boolean permission) {}

    private final CliOutput out;
    private final QuerySession engine;
    private final PermissionGate permissionGate;
    private final String cwd;
    private final boolean enforceOutputOrdering;
    private final Map<String, PendingAsk> pendingByRequestId = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> toolUseOutputWaiters =
        new ConcurrentHashMap<>();
    private final Set<String> writtenToolUseIds = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<Void>> messageOutputWaiters =
        new ConcurrentHashMap<>();
    private final Set<String> writtenMessageUuids = ConcurrentHashMap.newKeySet();

    private final Set<String> handledOrphanedToolUseIds = new ConcurrentSkipListSet<>();
    private volatile Set<String> supportedDialogKinds = Set.of();

    public SdkControlBroker(PrintWriter out, QuerySession engine, PermissionGate permissionGate, String cwd) {
        this(CliOutput.borrowed(out), engine, permissionGate, cwd, false);
    }

    SdkControlBroker(PrintWriter out, QuerySession engine, PermissionGate permissionGate,
                     String cwd, boolean enforceOutputOrdering) {
        this(CliOutput.borrowed(out), engine, permissionGate, cwd, enforceOutputOrdering);
    }

    SdkControlBroker(CliOutput out, QuerySession engine, PermissionGate permissionGate,
                     String cwd, boolean enforceOutputOrdering) {
        this.out = out;
        this.engine = engine;
        this.permissionGate = permissionGate;
        this.cwd = cwd;
        this.enforceOutputOrdering = enforceOutputOrdering;
    }

    void configureSupportedDialogKinds(JsonNode dialogKinds) {
        if (dialogKinds == null || !dialogKinds.isArray()) {
            supportedDialogKinds = Set.of();
            return;
        }
        Set<String> declared = new LinkedHashSet<>();
        for (JsonNode value : dialogKinds) {
            if (declared.size() >= 32) break;
            if (!value.isTextual()) continue;
            String kind = value.asText();
            if (kind.isEmpty() || kind.length() > 64) continue;
            declared.add(kind);
        }
        supportedDialogKinds = Set.copyOf(declared);
    }

    @Override
    public boolean consumerSupportsDialog() {
        return supportedDialogKinds.contains(DIALOG_KIND);
    }

    @Override
    public RefusalFallbackDecision.Choice ask(RefusalFallbackPrompt.Request dialog) {
        if (!consumerSupportsDialog()) return RefusalFallbackDecision.Choice.CANCELLED;

        ObjectNode request = MAPPER.createObjectNode();
        request.put("subtype", "request_user_dialog");
        request.put("dialog_kind", DIALOG_KIND);
        ObjectNode payload = request.putObject("payload");
        payload.put("originalModel", dialog.refusedModel());
        payload.put("fallbackModel", dialog.fallbackModel());
        if (dialog.category() != null) {
            payload.put("apiRefusalCategory", dialog.category());
        }
        if (dialog.guidanceText() != null) {
            payload.put("guidanceText", dialog.guidanceText());
        }
        payload.set("retractedMessageUuids",
            MAPPER.valueToTree(dialog.retractedMessageUuids()));

        if (!awaitRetractedMessagesOutput(dialog.retractedMessageUuids())) {
            return RefusalFallbackDecision.Choice.CANCELLED;
        }

        PendingAsk pending = beginGenericRequest(request);
        AutoCloseable abortRegistration = engine.execution().getAbortController()
            .registerOnAbort(() -> cancelPending(pending, cancelledDialogResponse()));
        try {
            long timeoutMillis = userDialogTimeoutMillis();
            JsonNode response = timeoutMillis > 0
                ? pending.future().get(timeoutMillis, TimeUnit.MILLISECONDS)
                : pending.future().join();
            return dialogChoice(response);
        } catch (TimeoutException _) {
            completePending(pending, cancelledDialogResponse());
            return RefusalFallbackDecision.Choice.CANCELLED;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            cancelPending(pending, cancelledDialogResponse());
            return RefusalFallbackDecision.Choice.CANCELLED;
        } catch (ExecutionException | RuntimeException _) {
            return RefusalFallbackDecision.Choice.CANCELLED;
        } finally {
            closeQuietly(abortRegistration);
        }
    }

    private static RefusalFallbackDecision.Choice dialogChoice(JsonNode response) {
        if (response == null
                || !Strings.CS.equals("completed", response.path("behavior").asText())) {
            return RefusalFallbackDecision.Choice.CANCELLED;
        }
        return switch (response.path("result").asText()) {
            case "retry_fallback" -> RefusalFallbackDecision.Choice.RETRY_FALLBACK;
            case "edit_prompt" -> RefusalFallbackDecision.Choice.EDIT_PROMPT;
            default -> RefusalFallbackDecision.Choice.CANCELLED;
        };
    }

    private static long userDialogTimeoutMillis() {
        String configured = SubprocessEnvironment.get("CLAUDE_CODE_USER_DIALOG_TIMEOUT_MS");
        if (StringUtils.isBlank(configured)) return DEFAULT_USER_DIALOG_TIMEOUT_MS;
        try {
            return Long.parseLong(configured);
        } catch (NumberFormatException _) {
            return DEFAULT_USER_DIALOG_TIMEOUT_MS;
        }
    }

    private static ObjectNode cancelledDialogResponse() {
        return MAPPER.createObjectNode().put("behavior", "cancelled");
    }

    private void completePending(PendingAsk pending, JsonNode fallback) {
        if (pendingByRequestId.remove(pending.requestId(), pending)) {
            pending.future().complete(fallback);
        }
    }

    private void cancelPending(PendingAsk pending, JsonNode fallback) {
        if (!pendingByRequestId.remove(pending.requestId(), pending)) return;
        ObjectNode cancel = MAPPER.createObjectNode();
        cancel.put("type", "control_cancel_request");
        cancel.put("request_id", pending.requestId());
        StdoutMessageWriter.writeControlMessage(cancel, out);
        pending.future().complete(fallback);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception _) {
            // Abort-listener cleanup is best effort.
        }
    }

    /**
     * Issues a {@code can_use_tool} control_request and blocks until the controller's
     * {@code control_response} arrives (matched by {@code request_id}), then maps the
     * decision into a {@link PermissionAskCallback.Result}.
     */
    public PermissionAskCallback.Result askPermission(PermissionAskContext ctx) {
        String requestId = UUID.randomUUID().toString();
        String toolUseId = ctx.toolUseId();

        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "control_request");
        root.put("request_id", requestId);
        ObjectNode request = root.putObject("request");
        request.put("subtype", "can_use_tool");
        request.put("tool_name", ctx.toolName());
        request.put("display_name", ctx.toolName());
        request.set("input", ctx.input() != null ? ctx.input() : MAPPER.nullNode());
        String description = wireDescription(toolDescription(ctx));
        if (description != null) request.put("description", description);
        if (toolUseId != null) request.put("tool_use_id", toolUseId);


        // and omits the field. The raw decision TYPE is never sent, and there is NO
        // permission_mode field on the can_use_tool request (that is a separate
        // control_request subtype), so we must not emit it here.
        String decisionReason = serializeDecisionReason(ctx);
        if (decisionReason != null) request.put("decision_reason", decisionReason);


        String agentId = engine.configuration().getConfig().agentId();
        if (agentId != null) request.put("agent_id", agentId);

        String blockedPath = ctx.blockedPath();
        ArrayNode suggestions = permissionSuggestions(ctx, blockedPath);
        if (!suggestions.isEmpty()) request.set("permission_suggestions", suggestions);
        if (blockedPath != null) request.put("blocked_path", blockedPath);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        awaitAssistantOutputFor(request);
        pendingByRequestId.put(requestId,
            new PendingAsk(requestId, future, toolUseId, ctx.toolName(), true));
        // Register before publishing the request: a fast controller may respond
        // immediately after reading stdout, and that response must not be mistaken
        // for an orphaned permission.
        StdoutMessageWriter.writeControlMessage(root, out);
        JsonNode result = future.join(); // blocks the virtual thread until the reader delivers the response
        return toResult(result);
    }

/**
     * Forwards an MCP elicitation over the same.
     */
    public JsonNode askElicitation(String serverName, JsonNode params) {
        return askElicitation(serverName, params, Set.of());
    }

    /**
     * Forwards an MCP elicitation after every active MCP tool use that caused it
     * has been flushed as an assistant row. The ids are ordering-only metadata.
     */
    public JsonNode askElicitation(String serverName, JsonNode params,
                                   Set<String> activeToolUseIds) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("subtype", "elicitation");
        request.put("mcp_server_name", serverName);
        if (params != null && params.isObject()) {
            copyIfPresent(params, request, "message", "message");
            copyIfPresent(params, request, "mode", "mode");
            copyIfPresent(params, request, "url", "url");
            copyIfPresent(params, request, "elicitationId", "elicitation_id");
            copyIfPresent(params, request, "requestedSchema", "requested_schema");
        }
        try {
            return sendGenericRequest(request, activeToolUseIds);
        } catch (RuntimeException _) {
            return MAPPER.createObjectNode().put("action", "cancel");
        }
    }

    /** Forwards an SDK callback hook and returns its raw hook JSON output. */
    public JsonNode askHookCallback(String callbackId, JsonNode input, String toolUseId) {
        return askHookCallback(callbackId, input, toolUseId, 0);
    }

    public JsonNode askHookCallback(String callbackId, JsonNode input, String toolUseId,
                                    long timeoutSeconds) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("subtype", "hook_callback");
        request.put("callback_id", callbackId);
        request.set("input", input != null ? input : MAPPER.createObjectNode());
        if (toolUseId != null) request.put("tool_use_id", toolUseId);
        try {
            return sendGenericRequest(request, timeoutSeconds > 0 ? timeoutSeconds * 1000L : 0);
        } catch (RuntimeException _) {
            return MAPPER.createObjectNode();
        }
    }

    /** Exchanges one JSON-RPC message with an SDK-hosted MCP server. */
    public JsonNode askMcpMessage(String serverName, JsonNode message) {
        return askMcpMessageAsync(serverName, message).join();
    }

    /**
     * Publishes one SDK MCP control request immediately and completes when the
     * controller returns its nested {@code mcp_response}.
     */
    public CompletableFuture<JsonNode> askMcpMessageAsync(String serverName, JsonNode message) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("subtype", "mcp_message");
        request.put("server_name", serverName);
        request.set("message", message);
        return beginGenericRequest(request).future()
            .thenApply(response -> response.path("mcp_response"));
    }

    /**
     * Publishes an SDK MCP notification without retaining a pending control request.
     */
    public void sendMcpMessage(String serverName, JsonNode message) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "control_request");
        root.put("request_id", UUID.randomUUID().toString());
        ObjectNode request = root.putObject("request");
        request.put("subtype", "mcp_message");
        request.put("server_name", serverName);
        request.set("message", message);
        StdoutMessageWriter.writeControlMessage(root, out);
    }

    private JsonNode sendGenericRequest(ObjectNode request, Set<String> orderingToolUseIds) {
        PendingAsk ask = beginGenericRequest(request, orderingToolUseIds);
        return ask.future().join();
    }

    private JsonNode sendGenericRequest(ObjectNode request, long timeoutMillis) {
        PendingAsk ask = beginGenericRequest(request);
        CompletableFuture<JsonNode> future = ask.future();
        String requestId = ask.requestId();
        if (timeoutMillis <= 0) return future.join();
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            if (pendingByRequestId.remove(requestId) != null) {
                ObjectNode cancel = MAPPER.createObjectNode();
                cancel.put("type", "control_cancel_request");
                cancel.put("request_id", requestId);
                StdoutMessageWriter.writeControlMessage(cancel, out);
            }
            throw new IllegalStateException("SDK control request timed out", error);
        }
    }

    private PendingAsk beginGenericRequest(ObjectNode request) {
        return beginGenericRequest(request, Set.of());
    }

    private PendingAsk beginGenericRequest(ObjectNode request, Set<String> orderingToolUseIds) {
        String requestId = UUID.randomUUID().toString();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "control_request");
        root.put("request_id", requestId);
        root.set("request", request);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        PendingAsk ask = new PendingAsk(
            requestId, future, null, request.path("subtype").asText(), false);
        awaitAssistantOutputFor(request, orderingToolUseIds);
        pendingByRequestId.put(requestId, ask);
        StdoutMessageWriter.writeControlMessage(root, out);
        return ask;
    }

    /**
     * Acknowledges one assistant row after stdout has flushed it. This releases
     * both control requests associated with its tool uses and refusal dialogs
     * that name the row in {@code retractedMessageUuids}.
     */
    void onAssistantMessageWritten(AssistantMessage assistant) {
        onRetractableMessageWritten(assistant);
        if (!enforceOutputOrdering
                || assistant == null
                || assistant.message() == null
                || assistant.message().content() == null) {
            return;
        }
        for (var block : assistant.message().content()) {
            if (!(block instanceof ToolUseBlock toolUse)
                    || toolUse.id() == null
                    || StringUtils.isBlank(toolUse.id())) {
                continue;
            }
            writtenToolUseIds.add(toolUse.id());
            CompletableFuture<Void> waiter = toolUseOutputWaiters.remove(toolUse.id());
            if (waiter != null) waiter.complete(null);
        }
    }

    /** Acknowledges a tool-result user row after stdout has flushed it. */
    void onUserMessageWritten(UserMessage user) {
        onRetractableMessageWritten(user);
    }

    private void onRetractableMessageWritten(Message message) {
        if (!enforceOutputOrdering || message == null) return;
        for (String uuid : WireMessages.retractedUuids(List.of(message))) {
            writtenMessageUuids.add(uuid);
            CompletableFuture<Void> waiter = messageOutputWaiters.remove(uuid);
            if (waiter != null) waiter.complete(null);
        }
    }

    private boolean awaitRetractedMessagesOutput(List<String> messageUuids) {
        if (!enforceOutputOrdering || messageUuids == null || messageUuids.isEmpty()) {
            return true;
        }
        List<String> pendingUuids = messageUuids.stream()
            .filter(StringUtils::isNotBlank)
            .distinct()
            .filter(uuid -> !writtenMessageUuids.contains(uuid))
            .toList();
        if (pendingUuids.isEmpty()) return true;

        CompletableFuture<?>[] waiters = pendingUuids.stream()
            .map(this::messageOutputFuture)
            .toArray(CompletableFuture[]::new);
        CompletableFuture<Void> allWritten = CompletableFuture.allOf(waiters);
        AutoCloseable abortRegistration = engine.execution().getAbortController()
            .registerOnAbort(() -> allWritten.completeExceptionally(
                new IllegalStateException("Refusal dialog was aborted before output flushed")));
        try {
            allWritten.join();
            return true;
        } catch (RuntimeException _) {
            for (int i = 0; i < pendingUuids.size(); i++) {
                String uuid = pendingUuids.get(i);
                CompletableFuture<?> waiter = waiters[i];
                messageOutputWaiters.remove(uuid, waiter);
            }
            return false;
        } finally {
            closeQuietly(abortRegistration);
        }
    }

    private CompletableFuture<Void> messageOutputFuture(String messageUuid) {
        if (writtenMessageUuids.contains(messageUuid)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> waiter = messageOutputWaiters.computeIfAbsent(
            messageUuid, _ -> new CompletableFuture<>());
        if (writtenMessageUuids.contains(messageUuid)
                && messageOutputWaiters.remove(messageUuid, waiter)) {
            waiter.complete(null);
        }
        return waiter;
    }

    private void awaitAssistantOutputFor(JsonNode request) {
        awaitAssistantOutputFor(request, Set.of());
    }

    private void awaitAssistantOutputFor(JsonNode request, Set<String> orderingToolUseIds) {
        if (!enforceOutputOrdering || request == null) return;

        // lifecycle callback before system/init, while still preserving the
        // assistant-before-PreToolUse ordering for real tool hooks.
        if (Strings.CS.equals("hook_callback", request.path("subtype").asText())
                && request.path("input").path("tool_use_id").isMissingNode()) {
            return;
        }
        Set<String> toolUseIds = new HashSet<>();
        if (orderingToolUseIds != null) {
            orderingToolUseIds.stream()
                .filter(StringUtils::isNotBlank)
                .forEach(toolUseIds::add);
        }
        String toolUseId = request.path("tool_use_id").asText(null);
        if (StringUtils.isBlank(toolUseId)) {
            toolUseId = request.path("message")
                .path("params")
                .path("_meta")
                .path("claudecode/toolUseId")
                .asText(null);
        }
        if (StringUtils.isNotBlank(toolUseId)) toolUseIds.add(toolUseId);
        for (String id : toolUseIds) awaitAssistantOutputFor(id);
    }

    private void awaitAssistantOutputFor(String toolUseId) {
        if (writtenToolUseIds.contains(toolUseId)) return;

        CompletableFuture<Void> waiter = toolUseOutputWaiters.computeIfAbsent(
            toolUseId, _ -> new CompletableFuture<>());
        // Close the signal-before-registration race: the writer may have added
// the id between the first contains and computeIfAbsent.
        if (writtenToolUseIds.contains(toolUseId)
                && toolUseOutputWaiters.remove(toolUseId, waiter)) {
            waiter.complete(null);
        }
        waiter.join();
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target,
                                      String sourceName, String targetName) {
        JsonNode value = source.get(sourceName);
        if (value != null && !value.isNull()) target.set(targetName, value);
    }

    boolean hasPendingResponse(JsonNode msg) {
        String requestId = msg.path("response").path("request_id").asText(null);
        return requestId != null && pendingByRequestId.containsKey(requestId);
    }

    private static String toolDescription(PermissionAskContext ctx) {
        if (ctx.input() == null) return ctx.customMessage();
        String explicit = ctx.input().path("description").asText(null);
        if (StringUtils.isNotBlank(explicit)) return explicit;
        String command = ctx.input().path("command").asText(null);
        if (StringUtils.isNotBlank(command)) return command;
        return ctx.customMessage();
    }

    private static String wireDescription(String description) {
        if (description == null || description.length() <= 50) return description;

// UTF-16 code units, so substring is deliberately used instead of a
        // Unicode-code-point truncator here.
        return description.substring(0, 49) + "…";
    }

    private ArrayNode permissionSuggestions(PermissionAskContext ctx, String blockedPath) {
        ArrayNode suggestions = ctx.suggestions().isEmpty()
            ? MAPPER.createArrayNode()
            : PermissionUpdateJsonCodec.toJson(ctx.suggestions());

        // Compatibility for embedders still constructing the pre-typed context.
        if (suggestions.isEmpty() && blockedPath != null) {
            try {
                Path parent = Path.of(blockedPath).getParent();
                if (parent != null) {
                    ObjectNode directory = MAPPER.createObjectNode();
                    directory.put("type", "addDirectories");
                    directory.putArray("directories").add(parent.toString());
                    directory.put("destination", "session");
                    suggestions.add(directory);
                }
            } catch (RuntimeException _) {
                // Invalid path text remains visible as blocked_path; omit only
                // the derived addDirectories suggestion.
            }
            switch (permissionGate.currentMode()) {
                case DEFAULT, PLAN -> {
                    ObjectNode mode = MAPPER.createObjectNode();
                    mode.put("type", "setMode");
                    mode.put("mode", "acceptEdits");
                    mode.put("destination", "session");
                    suggestions.add(mode);
                }
                default -> { }
            }
        }

        String command = ctx.input() != null ? ctx.input().path("command").asText(null) : null;
        boolean allSession = true;
        for (JsonNode suggestion : suggestions) {
            if (!Strings.CS.equals("session", suggestion.path("destination").asText())) {
                allSession = false;
                break;
            }
        }
        if (Strings.CS.equals("Bash", ctx.toolName()) && command != null && !StringUtils.isBlank(command) && allSession) {
            suggestions.insert(0, addRuleSuggestion(ctx.toolName(), command, "localSettings"));
        } else if (ctx.suggestionRuleContent() != null) {
            suggestions.add(addRuleSuggestion(
                ctx.toolName(), ctx.suggestionRuleContent(), "localSettings"));
        }
        return suggestions;
    }

    private static ObjectNode addRuleSuggestion(String toolName, String rule, String destination) {
        ObjectNode suggestion = MAPPER.createObjectNode();
        suggestion.put("type", "addRules");
        ObjectNode ruleObj = MAPPER.createObjectNode();
        ruleObj.put("toolName", toolName);
        ruleObj.put("ruleContent", rule);
        suggestion.putArray("rules").add(ruleObj);
        suggestion.put("behavior", "allow");
        suggestion.put("destination", destination);
        return suggestion;
    }

    /**
     * Dispatches an inbound {@code control_response} from the controller. Completes the
     * matching pending ask, or — when no pending ask matches (the request context was
     * lost) — recovers the decision as an orphaned permission replay.
     */
    public void onControlResponse(JsonNode msg) {
        JsonNode response = msg.path("response");
        if (response.isMissingNode() || response.isNull()) return;
        String subtype = response.path("subtype").asText(null);
        String requestId = response.path("request_id").asText(null);
        JsonNode inner = response.path("response"); // permissionResult (behavior/toolUseID/...)

        if (Strings.CS.equals("success", subtype)) {
            if (requestId != null) {
                PendingAsk ask = pendingByRequestId.remove(requestId);
                if (ask != null) {
                    ask.future().complete(inner);
                    return;
                }
            }
            // No matching pending ask → controller lost the request context; recover as orphaned.
            handleOrphanedPermissionResponse(inner);
        } else if (Strings.CS.equals("error", subtype)) {
            if (requestId != null) {
                PendingAsk ask = pendingByRequestId.remove(requestId);
                if (ask != null) {
                    if (ask.permission()) ask.future().complete(errorDenyNode());
                    else ask.future().completeExceptionally(new IllegalStateException(
                        response.path("error").asText("SDK control request failed")));
                }
            }
            // Errors for unknown requests are ignored.
        }
    }


    private void handleOrphanedPermissionResponse(JsonNode permissionResult) {
        if (permissionResult == null || permissionResult.isMissingNode()) return;
        String toolUseId = permissionResult.path("toolUseID").asText(null);
        if (StringUtils.isBlank(toolUseId)) return;
        if (handledOrphanedToolUseIds.contains(toolUseId)) return; // already recovered

        Optional<AssistantMessage> unresolved = engine.conversation().findUnresolvedToolUse(toolUseId);
        if (unresolved.isEmpty()) return; // unknown id or already resolved — nothing to replay

        handledOrphanedToolUseIds.add(toolUseId);
        engine.conversation().getMessageQueue().enqueue(
            QueuedCommand.orphanedPermission(new OrphanedPermission(toolUseId, permissionResult)));


// abortController.abort while converting the decision during tool re-use),
        // so the rejection tool_result is emitted first. The interrupt is honored in
        // OrphanedPermissionExecutor after the replay step completes.
    }


    public void close() {
        IllegalStateException closed = new IllegalStateException("SDK control channel closed");
        for (CompletableFuture<Void> waiter : toolUseOutputWaiters.values()) {
            waiter.completeExceptionally(closed);
        }
        toolUseOutputWaiters.clear();
        for (CompletableFuture<Void> waiter : messageOutputWaiters.values()) {
            waiter.completeExceptionally(closed);
        }
        messageOutputWaiters.clear();
        for (PendingAsk ask : pendingByRequestId.values()) {
// Cancel the outstanding request so the SDK consumer's canUseTool callback (and any UI
// prompt) is.
            ObjectNode cancel = MAPPER.createObjectNode();
            cancel.put("type", "control_cancel_request");
            cancel.put("request_id", ask.requestId());
            StdoutMessageWriter.writeControlMessage(cancel, out);
            if (ask.permission()) ask.future().complete(errorDenyNode());
            else ask.future().completeExceptionally(
                new IllegalStateException("SDK control channel closed"));
        }
        pendingByRequestId.clear();
    }

    /** Maps a control_response inner payload (or synthetic deny) to a PermissionAskCallback.Result. */
    private PermissionAskCallback.Result toResult(JsonNode permissionResult) {
        if (permissionResult == null || permissionResult.isMissingNode()) return deniedByController(null);
        String behavior = permissionResult.path("behavior").asText(null);
        if (Strings.CS.equals("allow", behavior)) {
            // The SDK host may piggyback permission updates (addRules/setMode/…)

            // applyPermissionUpdates + persistPermissionUpdates (editable
            // destinations survive a restart; session/cliArg remain in-memory).
            JsonNode updatedInput = permissionResult.path("updatedInput");
            if (updatedInput != null && !updatedInput.isMissingNode()
                && !updatedInput.isObject()) {
                // PermissionResultSchema requires an object when updatedInput
                // is present; reject malformed host responses instead of
                // passing a scalar/array into tool execution.
                return deniedByController("Permission response contains invalid updatedInput");
            }
            applyUpdatedPermissions(permissionResult.path("updatedPermissions"));

            // object as "use original input" (mobile hosts send {} when they
            // cannot echo the original tool arguments).
            JsonNode input = (updatedInput != null && !updatedInput.isMissingNode()
                && !updatedInput.isNull()
                && !(updatedInput.isObject() && updatedInput.isEmpty()))
                ? updatedInput : null;
            return PermissionAskCallback.Result.allowWithInput(input);
        }

        if (!Strings.CS.equals("deny", behavior)) {
            return deniedByController("Permission response missing valid behavior: " + behavior);
        }

        if (permissionResult.path("interrupt").asBoolean(false)) {
            engine.submission().interrupt();
        }
        JsonNode message = permissionResult.path("message");
        String reason = (message != null && !message.isMissingNode() && !message.isNull())
            ? message.asText() : null;
        return deniedByController(reason);
    }

    /**
     * Applies {@code updatedPermissions} from an allow control_response.
     */
    private void applyUpdatedPermissions(JsonNode updates) {
        if (updates == null || !updates.isArray()) return;

        // update invalidates the whole field, rather than partially applying
        // the valid entries that happen to precede it.
        if (!validUpdatedPermissions(updates)) {
            LOG.warn("Ignoring malformed updatedPermissions from SDK host");
            return;
        }
        for (JsonNode u : updates) {
            if (!(u instanceof ObjectNode)) continue;
            String type = u.path("type").asText(null);
            if (type == null) continue;
            switch (type) {
                case "addRules" -> {
                    if (permissionGate != null) addRulesFromUpdate(u);
                }
                case "replaceRules" -> {
                    if (permissionGate != null) replaceRulesFromUpdate(u);
                }
                case "removeRules" -> {
                    if (permissionGate != null) removeRulesFromUpdate(u);
                }
                case "setMode" -> {
                    if (permissionGate != null) {
                        String mode = u.path("mode").asText(null);
                        if (mode != null) {
                            permissionGate.applyPermissionUpdateMode(
                                PermissionGate.parseMode(mode));
                        }
                    }
                }
                case "addDirectories" -> {
                    if (permissionGate != null) applyDirectoriesFromUpdate(u, false);
                }
                case "removeDirectories" -> {
                    if (permissionGate != null) applyDirectoriesFromUpdate(u, true);
                }
            }
        }

        persistUpdatedPermissions(updates);
    }

    private static boolean validUpdatedPermissions(JsonNode updates) {
        for (JsonNode update : updates) {
            if (!update.isObject()) return false;
            String type = update.path("type").asText(null);
            if (type == null || !validPermissionDestination(update.path("destination").asText(null))) {
                return false;
            }
            switch (type) {
                case "addRules", "replaceRules", "removeRules" -> {
                    if (!validPermissionBehavior(update.path("behavior").asText(null))
                        || !update.path("rules").isArray()) return false;
                    for (JsonNode rule : update.path("rules")) {
                        if (!rule.isObject() || !rule.path("toolName").isTextual()) return false;
                        JsonNode content = rule.get("ruleContent");
                        if (content != null && !content.isTextual()) return false;
                    }
                }
                case "setMode" -> {
                    if (!validExternalPermissionMode(update.path("mode").asText(null))) return false;
                }
                case "addDirectories", "removeDirectories" -> {
                    if (!update.path("directories").isArray()) return false;
                    for (JsonNode directory : update.path("directories")) {
                        if (!directory.isTextual()) return false;
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validPermissionDestination(String value) {
        return Strings.CS.equals("userSettings", value)
            || Strings.CS.equals("projectSettings", value)
            || Strings.CS.equals("localSettings", value)
            || Strings.CS.equals("session", value)
            || Strings.CS.equals("cliArg", value);
    }

    private static boolean validPermissionBehavior(String value) {
        return Strings.CS.equals("allow", value)
            || Strings.CS.equals("deny", value)
            || Strings.CS.equals("ask", value);
    }

    private static boolean validExternalPermissionMode(String value) {
        return Strings.CS.equals("default", value)
            || Strings.CS.equals("acceptEdits", value)
            || Strings.CS.equals("bypassPermissions", value)
            || Strings.CS.equals("plan", value)
            || Strings.CS.equals("dontAsk", value);
    }

    private void addRulesFromUpdate(JsonNode u) {
        PermissionBehavior behavior = parseBehavior(u.path("behavior").asText("allow"));
        RuleSource source = mapDestination(u.path("destination").asText("project"));
        JsonNode rules = u.path("rules");
        if (!rules.isArray()) return;
        List<PermissionRule> parsed = new ArrayList<>();
        for (JsonNode r : rules) {
            if (!r.isObject() && !r.isTextual()) continue;
            PermissionRule pr = toPermissionRule(r, behavior, source);
            if (pr.toolName() != null) parsed.add(pr);
        }
        if (!parsed.isEmpty()) permissionGate.addRules(parsed);
    }

    private void replaceRulesFromUpdate(JsonNode u) {
        PermissionBehavior behavior = parseBehavior(u.path("behavior").asText("allow"));
        RuleSource source = mapDestination(u.path("destination").asText("project"));
        JsonNode rules = u.path("rules");
        if (!rules.isArray()) return;
        List<PermissionRule> replacement = new ArrayList<>();
        for (JsonNode r : rules) {
            if (!r.isObject() && !r.isTextual()) continue;
            PermissionRule pr = toPermissionRule(r, behavior, source);
            if (pr.toolName() != null) replacement.add(pr);
        }

        permissionGate.replaceRules(behavior, source, replacement);
    }

    private void removeRulesFromUpdate(JsonNode u) {
        PermissionBehavior behavior = parseBehavior(u.path("behavior").asText("allow"));
        RuleSource source = mapDestination(u.path("destination").asText("project"));
        JsonNode rules = u.path("rules");
        if (!rules.isArray()) return;
        Set<String> keys = new HashSet<>();
        for (JsonNode r : rules) {
            if (!r.isObject() && !r.isTextual()) continue;
            PermissionRule pr = toPermissionRule(r, behavior, source);
            if (pr.toolName() != null) keys.add(pr.toolName() + "|" + pr.pattern().orElse(""));
        }
        if (!keys.isEmpty()) {
            permissionGate.removeRules(pr -> pr.behavior() == behavior
                && pr.source() == source
                && keys.contains(pr.toolName() + "|" + pr.pattern().orElse("")));
        }
    }

    private void applyDirectoriesFromUpdate(JsonNode u, boolean remove) {
        RuleSource source = mapDestination(u.path("destination").asText("session"));
        JsonNode dirs = u.path("directories");
        if (!dirs.isArray()) return;
        List<Path> paths = new ArrayList<>();
        for (JsonNode d : dirs) {
            if (d.isTextual() && !StringUtils.isBlank(d.asText())) paths.add(Path.of(d.asText()));
        }
        if (paths.isEmpty()) return;
        if (remove) {
            permissionGate.removeDirectories(paths);
        } else {

// (additionalWorkingDirectories uses {path, source}); match that.
            permissionGate.addDirectories(paths, source);
        }
    }

    private static PermissionBehavior parseBehavior(String s) {
        return switch (s) {
            case "deny" -> PermissionBehavior.DENY;
            case "ask" -> PermissionBehavior.ASK;
            default -> PermissionBehavior.ALLOW;
        };
    }

    private static RuleSource mapDestination(String d) {
        return switch (d) {
            case "userSettings" -> RuleSource.USER_SETTINGS;
            case "localSettings" -> RuleSource.LOCAL_SETTINGS;
            case "session" -> RuleSource.SESSION;
            default -> RuleSource.PROJECT_SETTINGS; // projectSettings / unknown
        };
    }

    /** Builds a {@link PermissionRule} from a {@code PermissionRuleValueSchema} node (object or bare string). */
    private static PermissionRule toPermissionRule(JsonNode r, PermissionBehavior behavior, RuleSource source) {
        if (r.isTextual()) {
            return PermissionEngine.permissionRuleFromString(r.asText(), behavior, source);
        }
        String toolName = r.path("toolName").asText(null);
        String ruleContent = r.path("ruleContent").asText(null);
        return (StringUtils.isNotBlank(ruleContent))
            ? PermissionRule.withPattern(toolName, behavior, source, ruleContent)
            : PermissionRule.of(toolName, behavior, source);
    }


    private void persistUpdatedPermissions(JsonNode updates) {
        if (updates == null || !updates.isArray()) return;
        for (JsonNode u : updates) {
            if (!(u instanceof ObjectNode)) continue;
            String destination = u.path("destination").asText(null);
            if (!supportsPersistence(destination)) continue;
            RuleSource tier = mapDestination(destination);
            String type = u.path("type").asText(null);
            if (type == null) continue;
            try {
                switch (type) {
                    case "addRules" -> persistRules(u, tier, false);
                    case "replaceRules" -> persistRules(u, tier, true);
                    case "removeRules" -> persistRemoveRules(u, tier);
                    case "setMode" -> {
                        String mode = u.path("mode").asText(null);
                        if (mode != null) PermissionSettings.saveDefaultPermissionMode(cwd, mode, tier);
                    }
                    case "addDirectories" -> persistDirectories(u, tier, false);
                    case "removeDirectories" -> persistDirectories(u, tier, true);
                    default -> { /* unknown update type: no-op */ }
                }
            } catch (RuntimeException ex) {
                LOG.warn("Failed to persist permission update '{}' to {}: {}", type, tier, ex.getMessage());
            }
        }
    }

    private static boolean supportsPersistence(String d) {
        return Strings.CS.equals("localSettings", d) || Strings.CS.equals("userSettings", d) || Strings.CS.equals("projectSettings", d);
    }

    private void persistRules(JsonNode u, RuleSource tier, boolean replace) {
        PermissionBehavior behavior = parseBehavior(u.path("behavior").asText("allow"));
        JsonNode rules = u.path("rules");
        if (!rules.isArray()) return;
        List<String> ruleStrings = new ArrayList<>();
        for (JsonNode r : rules) {
            if (!r.isObject() && !r.isTextual()) continue;
            PermissionRule pr = toPermissionRule(r, behavior, tier);
            if (pr.toolName() == null) continue;
            ruleStrings.add(PermissionEngine.permissionRuleToString(pr));
        }
        if (replace) {

            // clears the entire behavior/source bucket.
            PermissionSettings.replacePermissionRules(cwd, behavior, ruleStrings, tier);
        } else if (!ruleStrings.isEmpty()) {
            for (String rs : ruleStrings) {
                PermissionSettings.addPermissionRule(cwd, behavior, rs, tier);
            }
        }
    }

    private void persistRemoveRules(JsonNode u, RuleSource tier) {
        PermissionBehavior behavior = parseBehavior(u.path("behavior").asText("allow"));
        JsonNode rules = u.path("rules");
        if (!rules.isArray()) return;
        if (rules.isEmpty()) {

            // removeRules list is empty, creating the editable source if needed.
            PermissionSettings.replacePermissionRules(cwd, behavior, List.of(), tier);
            return;
        }
        for (JsonNode r : rules) {
            if (!r.isObject() && !r.isTextual()) continue;
            PermissionRule pr = toPermissionRule(r, behavior, tier);
            if (pr.toolName() == null) continue;
            PermissionSettings.removePermissionRuleForUpdate(
                cwd, behavior, PermissionEngine.permissionRuleToString(pr), tier);
        }
    }

    private void persistDirectories(JsonNode u, RuleSource tier, boolean remove) {
        JsonNode dirs = u.path("directories");
        if (!dirs.isArray()) return;
        List<String> list = new ArrayList<>();
        for (JsonNode d : dirs) {
            if (d.isTextual() && !StringUtils.isBlank(d.asText())) list.add(d.asText());
        }

        // (possibly empty) array, which also creates a missing settings file.
        // Additions with no usable entries remain a no-op.
        if (list.isEmpty() && !remove) return;
        if (remove) {
            PermissionSettings.removeAdditionalDirectories(cwd, list, tier);
        } else {
            PermissionSettings.addAdditionalDirectories(cwd, list, tier);
        }
    }

    /** Deny delivered by the controller — keeps the query alive (SDK controller already answered). */
    private static PermissionAskCallback.Result deniedByController(String reason) {
        if (StringUtils.isBlank(reason)) reason = "Permission denied by controller";
        return PermissionAskCallback.Result.denyWithDirectMessage(reason);
    }

    /** Synthetic deny payload for an error response or shutdown cancellation. */
    private static JsonNode errorDenyNode() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("behavior", "deny");
        n.put("message", "Permission request failed (control channel error)");
        return n;
    }


    private static String serializeDecisionReason(PermissionAskContext ctx) {
        String type = ctx.decisionReasonType();
        if (type == null) return null;
        return switch (type) {
            case "rule", "mode", "subcommandResults", "permissionPromptTool" -> null;

            // outside the BASH_CLASSIFIER/TRANSCRIPT_CLASSIFIER feature gate)
            // yields `undefined`, so we omit the field rather than sending detail.
            case "hook", "asyncAgent", "sandboxOverride", "workingDir", "safetyCheck", "other" ->
                ctx.decisionReasonDetail();
            default -> null;
        };
    }
}
