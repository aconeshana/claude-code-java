package com.claudecode.runtime.query;


import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.RequestMessageNormalizer;
import com.claudecode.core.engine.ApiMessageFormatter;
import com.claudecode.core.engine.FallbackTriggeredError;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MidConversationSystemSupport;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolBatchSummarizer;
import com.claudecode.core.engine.ToolCallInfo;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolSearchGate;
import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.core.message.AgentListingDeltaAttachment;
import com.claudecode.core.message.AutoModeReminderAttachment;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentRenderer;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.HookAdditionalContextAttachment;
import com.claudecode.core.message.McpInstructionsDeltaAttachment;
import com.claudecode.core.message.PlanModeReminderAttachment;
import com.claudecode.core.message.OutputStyleAttachment;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.SkillListingAttachment;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;

import java.util.Objects;
import java.util.function.Predicate;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared helpers for the query turn loop, used by the production {@code QueryLoop} so the loop
 * cannot drift.
 */
final class QueryHelpers {

    private QueryHelpers() {}

    // ------------------------------------------------------------------------
    // Terminal → wire resultType, and result emission (Phase 6/9).
    // ------------------------------------------------------------------------

    public static String terminalToResultType(Terminal terminal) {
        return switch (terminal) {
            case Terminal.Normal _,
                 Terminal.StopHookPrevented _,
                 Terminal.HookStopped _ -> SDKMessage.Result.SUCCESS;
            case Terminal.MaxTurns _ -> SDKMessage.Result.ERROR_MAX_TURNS;
            case Terminal.MaxStructuredOutputRetries _ -> SDKMessage.Result.ERROR_MAX_STRUCTURED_OUTPUT_RETRIES;
            case Terminal.MaxBudget _ -> SDKMessage.Result.ERROR_MAX_BUDGET;
            case Terminal.PromptTooLong _,
                 Terminal.StreamError _,
                 Terminal.Aborted _ -> SDKMessage.Result.ERROR_DURING_EXECUTION;
        };
    }

    /**
     * Emits the final SDK result with query-scoped usage/API duration. The
     * structured output is {@code null} when JSON-schema mode was not active or
     * its synthetic tool never succeeded.
     */
    public static void emitResult(DefaultQuerySession engine, Usage queryUsage, Terminal terminal,
                                   Exception error, int numTurns, String stopReason,
                                   long loopStartTime, JsonNode structuredOutput,
                                   long durationApiMs, Consumer<SDKMessage> emit) {
        String resultType = terminalToResultType(terminal);
        double totalCost = SessionCostState.get().totalCostUsd();

        // itself. Keep that live reference on the result so held-back stdout
        // observes later background/notification requests at serialization.
        Map<String, Usage> modelUsage = SessionCostState.get().liveUsageByModel();
        Map<String, Double> modelCosts = SessionCostState.get().liveCostByModel();
        List<SDKMessage.PermissionDenial> denials = engine.getPermissionDenials();
        String fastModeState = engine.getFastModeState();
        boolean success = SDKMessage.Result.SUCCESS.equals(resultType);
        String resultText = "";
        boolean isApiErrorMessage = false;
        List<Message> allMessages = engine.getMessages();
        for (int i = allMessages.size() - 1; i >= 0; i--) {
            if (allMessages.get(i) instanceof AssistantMessage am) {
                isApiErrorMessage = am.isApiErrorMessage();
                if (am.message() != null && am.message().content() != null) {
                    List<ContentBlock> blocks = am.message().content();
                    for (int j = blocks.size() - 1; j >= 0; j--) {
                        if (blocks.get(j) instanceof TextBlock(String text)) {
                            resultText = text != null ? text : "";
                            break;
                        }
                    }
                }
                break;
            }
        }
        List<String> resultErrors;
        if (terminal instanceof Terminal.Aborted) {
            List<String> abortedErrors = new ArrayList<>();
            abortedErrors.add(buildExecutionDiagnostic(allMessages, stopReason));
            if (error != null && error.getMessage() != null) {
                abortedErrors.add(error.getMessage());
            }
            resultErrors = List.copyOf(abortedErrors);
        } else {
            resultErrors = error != null
                ? List.of(String.valueOf(error.getMessage())) : List.of();
        }
        emit.accept(new SDKMessage.Result(
            resultType,
            List.copyOf(allMessages),
            queryUsage,
            modelUsage,
            modelCosts,
            engine.getSessionId(),
            totalCost,
            denials,
            fastModeState,
            structuredOutput,
            System.currentTimeMillis() - loopStartTime,
            Math.max(0, durationApiMs),
            engine.getQueryTtftMs(),
            engine.getQueryTtftStreamMs(),
            engine.getQueryTimeToRequestMs(),
            numTurns,
            stopReason,
            UUID.randomUUID().toString(),
            resultText,
            !success || isApiErrorMessage,
            resultErrors
        ));
    }


    private static String buildExecutionDiagnostic(List<Message> messages, String stopReason) {
        Message result = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message candidate = messages.get(i);
            if (candidate instanceof AssistantMessage || candidate instanceof UserMessage) {
                result = candidate;
                break;
            }
        }
        String resultType = result != null ? result.type() : "undefined";
        String lastContentType = result instanceof AssistantMessage assistant
            ? lastAssistantContentType(assistant) : "n/a";
        return "[ede_diagnostic] result_type=" + resultType
            + " last_content_type=" + lastContentType
            + " stop_reason=" + stopReason;
    }

    private static String lastAssistantContentType(AssistantMessage assistant) {
        if (assistant.message() == null || assistant.message().content() == null
                || assistant.message().content().isEmpty()) {
            return "none";
        }
        ContentBlock block = assistant.message().content().getLast();
        return switch (block) {
            case TextBlock _ -> "text";
            case ThinkingBlock _ -> "thinking";
            case ToolUseBlock _ -> "tool_use";
            case ToolResultBlock _ -> "tool_result";
            default -> block.getClass().getSimpleName();
        };
    }

/**
     * Fires StopFailure hooks on failure exit paths.
     */
    public static void dispatchStopFailure(DefaultQuerySession engine) {
        dispatchStopFailure(engine, SDKMessage.Result.ERROR_DURING_EXECUTION);
    }

    public static void dispatchStopFailure(DefaultQuerySession engine, String reason) {
        HookDispatcher hooks = engine.getHookDispatcher();
        if (hooks == null) return;
        try {
            hooks.dispatchStopFailure(reason);
        } catch (Throwable _) { /* hooks must never poison the loop */ }
    }

    // ------------------------------------------------------------------------
    // Tool-result synthesis, interruption, queued commands (Phase 7/9).
    // ------------------------------------------------------------------------

    /** Synthesizes an is_error tool_result for every tool_use lacking a result. */
    public static void synthesizeMissingToolResults(DefaultQuerySession engine, AssistantMessage assistantMsg,
                                                     String errorMessage, Consumer<SDKMessage> emit) {
        if (assistantMsg == null || assistantMsg.message() == null
                || assistantMsg.message().content() == null) {
            return;
        }
        for (ContentBlock block : assistantMsg.message().content()) {
            if (block instanceof ToolUseBlock tub && !hasToolResultFor(engine, tub.id())) {
                emit.accept(new SDKMessage.StreamEvent("tool_result_error",
                    tub.name() + "|" + errorMessage));
                engine.getMutableMessages().add(new UserMessage(
                    UUID.randomUUID().toString(),
                    MessageContent.ofToolResult(tub.id(),
                        List.of(new TextBlock(errorMessage)), true),
                    false, false, null, MessageOrigin.USER, null, Instant.now(), null, null, null,
                    assistantMsg.uuid()));
            }
        }
    }

    public static boolean hasToolResultFor(DefaultQuerySession engine, String toolUseId) {
        List<Message> messages = engine.getMutableMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um
                    && um.message() != null && um.message().blocks() != null) {
                for (ContentBlock b : um.message().blocks()) {
                    if (b instanceof ToolResultBlock trb && toolUseId.equals(trb.toolUseId())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Surfaces the user interruption as a visible user message. */
    public static void emitInterruptionMessage(DefaultQuerySession engine, boolean toolUse, Consumer<SDKMessage> emit) {
        String content = toolUse
            ? MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE
            : MessageConstants.INTERRUPT_MESSAGE;
        UserMessage msg = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(new TextBlock(content))),
            false, false, null, null, null, Instant.now(), null, null,
            engine.getSessionId(), null);
        engine.getMutableMessages().add(msg);
        emit.accept(new SDKMessage.User(msg));
        recordTranscript(engine, msg);
    }

    /** One plain isMeta user message per stop-hook blocking error. */
    public static void injectStopHookFeedback(DefaultQuerySession engine, Consumer<SDKMessage> emit, String content) {
        UserMessage msg = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofText(content),
            true, false, null, MessageOrigin.USER, null, Instant.now(), null, null,
            engine.getSessionId(), null);
        engine.getMutableMessages().add(msg);
        emit.accept(new SDKMessage.User(msg));
        recordTranscript(engine, msg);
    }

    /**
     * Drains queued commands between tool batches, routing by agent id.
     */
    public static void drainQueuedCommands(DefaultQuerySession engine, Consumer<SDKMessage> emit) {
        var queue = engine.getMessageQueue();
        if (queue == null || !queue.hasCommands()) return;

        String myAgentId = engine.getConfig().agentId();
        boolean isMain = (myAgentId == null);

        Predicate<QueuedCommand> filter = isMain
            ? cmd -> (cmd.priority() != null ? cmd.priority() : QueuePriority.NEXT).order()
                        <= QueuePriority.NEXT.order()
                    && (!Strings.CS.startsWith(cmd.text(), "/") || cmd.skipSlashCommands())
                    && cmd.agentId() == null
                    && (Strings.CS.equals("prompt", cmd.mode())
                        || Strings.CS.equals("task-notification", cmd.mode())
                        // SDK orphaned-permission replay — mode carries a payload (see Task #31).
                        || (Strings.CS.equals("orphaned-permission", cmd.mode()) && cmd.orphanedPermission() != null))

            // no priority ceiling (so LATER is reachable), never user prompts.
            : cmd -> Strings.CS.equals("task-notification", cmd.mode())
                    && Objects.equals(cmd.agentId(), myAgentId);

        List<QueuedCommand> drained = queue.dequeueAllMatching(filter);
        for (QueuedCommand cmd : drained) {
// SDK orphaned-permission replay: re-execute the tool_use with the out-of-band
// permission decision.
            if (Strings.CS.equals("orphaned-permission", cmd.mode()) && cmd.orphanedPermission() != null) {
                OrphanedPermissionExecutor.execute(cmd.orphanedPermission(), engine, emit);
                continue;
            }

            // command's raw text in the appropriate "you were interrupted"
            // preamble. The attachment-service 'queued_commands' provider is
            // intentionally NOT registered (QueryLoop already drains the same
            // queue here) to avoid double-emitting.
            String wrapped = AttachmentRenderer.wrapQueuedCommandText(cmd.text(), cmd.mode(), cmd.originKind());
            UserMessage msg = new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofText(wrapped),
                cmd.isMeta(),
                false, null, MessageOrigin.USER, null, Instant.now(), null, null,
                engine.getSessionId(), null);
            engine.getMutableMessages().add(msg);
            emit.accept(new SDKMessage.User(msg));
            recordTranscript(engine, msg);
        }
    }


    public static String handleFallback(DefaultQuerySession engine, FallbackTriggeredError fte, Consumer<SDKMessage> emit) {
        String fallback = engine.getConfig().fallbackModel();
        if (StringUtils.isBlank(fallback)) return null;
        emit.accept(new SDKMessage.System(new SystemMessage(
            UUID.randomUUID().toString(), "model_fallback", "warning",
            "Switched to " + ModelNames.displayName(fallback)
                + " due to high demand for " + ModelNames.displayName(fte.originalModel()))));
        return fallback;
    }

    // ------------------------------------------------------------------------
    // Hooks context injection + transcript (Phase 3/8).
    // ------------------------------------------------------------------------

    public static void injectHookContext(DefaultQuerySession engine, String context) {
        engine.injectSystemReminder(context);
    }

    /** Best-effort transcript persistence. */
    public static void recordTranscript(DefaultQuerySession engine, Message message) {
        var sink = engine.getTranscriptSink();
        if (sink == null) return;
        try {
            sink.record(engine.getSessionId(), message);
        } catch (Throwable _) { /* fire-and-forget */ }
    }

    // ------------------------------------------------------------------------
    // Request message building + CLAUDE.md context (Phase 4/8).
    // ------------------------------------------------------------------------


    public static List<StreamingClient.StreamRequest.RequestMessage> buildRequestMessages(
            DefaultQuerySession engine, String claudeMdUserContext) {
        return buildRequestMessages(engine, engine.getMessages(), claudeMdUserContext);
    }

    /**
     * Variant used by cache-sharing forks such as {@code /compact}: it applies the exact main-loop
     * context injection / normalization protocol to an explicit fork history instead of implicitly
     * reading the engine's mutable history.
     */
    public static List<StreamingClient.StreamRequest.RequestMessage> buildRequestMessages(
            DefaultQuerySession engine, List<Message> conversationMessages, String claudeMdUserContext) {
        return buildRequestMessages(engine, conversationMessages, claudeMdUserContext,
            engine.getConfig().model(), List.of());
    }


    public static List<StreamingClient.StreamRequest.RequestMessage> buildRequestMessages(
            DefaultQuerySession engine, List<Message> conversationMessages, String claudeMdUserContext,
            String model, List<? extends Message> leadingMessages) {
        boolean toolSearchEnabled = ToolSearchGate.isEnabled(model);
        ToolExecutionContext toolPromptContext = toolPromptContext(engine, model);
        List<StreamingClient.StreamRequest.ToolDef> availableToolDefinitions = toolSearchEnabled
            ? engine.getConfig().toolExecutor().getToolDefinitions(
                ToolSearchGate.extractDiscoveredToolNames(engine.getMessages()),
                toolPromptContext)
            : engine.getConfig().toolExecutor().getToolDefinitions(toolPromptContext);
        return buildRequestMessages(engine, conversationMessages, claudeMdUserContext,
            model, leadingMessages, availableToolDefinitions);
    }

    static ToolExecutionContext toolPromptContext(
            DefaultQuerySession engine, String currentModel) {
        QuerySessionSpec config = engine.getConfig();
        return ToolExecutionContext.builder(
                engine.getAbortController(), engine.getSessionId())
            .workingDirectory(config.workingDirectory())
            .currentModel(currentModel)
            .enabledTools(config.tools())
            .agentDepth(config.agentDepth())
            .subagentMaxDepthSnapshot(config.subagentMaxDepthSnapshot())
            .build();
    }

    /**
     * Main-loop variant that reuses the exact tool-definition snapshot sent on
     * the request. Tool schemas can be expensive to materialize; the query path
     * computes the available tools once rather than rebuilding all
     * definitions again solely for message normalization.
     */
    public static List<StreamingClient.StreamRequest.RequestMessage> buildRequestMessages(
            DefaultQuerySession engine, List<Message> conversationMessages, String claudeMdUserContext,
            String model, List<? extends Message> leadingMessages,
            List<StreamingClient.StreamRequest.ToolDef> availableToolDefinitions) {

      // Initial Agent/MCP/Skill inventory attachments are created after the first user
// message and persist in the typed transcript.
        List<Message> promotedLeading = new ArrayList<>(leadingMessages);
        List<Message> conversationForNormalization = new ArrayList<>(conversationMessages.size());
        boolean beforeFirstAssistant = true;
        int currentUserSegmentStart = 0;
        for (Message message : conversationMessages) {
            if (message instanceof AssistantMessage) {
                beforeFirstAssistant = false;
                conversationForNormalization.add(message);
                currentUserSegmentStart = conversationForNormalization.size();
                continue;
            }
            if (beforeFirstAssistant && isInitialLeadingAttachment(message)) {
                if (!promotedLeading.contains(message)) promotedLeading.add(message);
            } else if (isInitialLeadingAttachment(message)) {

                // injects them at the start of the next post-assistant user
                // segment. Reorder only the wire normalization input; JSONL
                // retains its append-log order.
                conversationForNormalization.add(currentUserSegmentStart, message);
                currentUserSegmentStart++;
            } else {
                conversationForNormalization.add(message);
            }
        }

        List<Message> normalizationInput = new ArrayList<>(
            conversationForNormalization.size() + 1);
        if (StringUtils.isNotBlank(claudeMdUserContext)) {
            normalizationInput.add(MessageFactory.createUserMessage(
                claudeMdUserContext, true));
        }
        normalizationInput.addAll(conversationForNormalization);

        // Normalize the conversation for the API: strip meta image/document blocks
        // for too-large errors, map to wire turns, strip tool_reference when tool
        // search is off, and merge consecutive user turns. (ApiMessageFormatter is
        // the pure mapper; RequestMessageNormalizer owns all the policy steps — see
        // its class doc. The CLAUDE.md context turn prepended above is fused into
        // the following user turn by that merge step.)
      boolean toolSearchEnabled = ToolSearchGate.isEnabled(model);
      Set<String> availableToolNames = availableToolDefinitions.stream()
          .map(StreamingClient.StreamRequest.ToolDef::name)
          .filter(Objects::nonNull)
          .collect(Collectors.toUnmodifiableSet());

      List<StreamingClient.StreamRequest.RequestMessage> requestMessages = new ArrayList<>(
          RequestMessageNormalizer.normalizeForApi(
              normalizationInput, engine.getConfig().isThinkingEnabled(),
              toolSearchEnabled, model, availableToolNames,
              engine.getConfig().toolExecutor().getToolNameAliases()));

        boolean midConversationSystem =
            MidConversationSystemSupport.isEnabled(model);
        if (!promotedLeading.isEmpty()) {
            List<StreamingClient.StreamRequest.RequestMessage> leadingWire =
                ApiMessageFormatter.toRequestMessages(
                    promotedLeading, false, midConversationSystem);
            if (midConversationSystem) {
                int insertAt = firstUserInsertionIndex(requestMessages);
                if (insertAt > 0) requestMessages.addAll(insertAt, leadingWire);
                else requestMessages.addAll(leadingWire);
            } else {
                leadingWire = coalesceLeadingUserMessagesWithoutSeam(leadingWire);
                requestMessages.addAll(0, leadingWire);
                requestMessages = new ArrayList<>(
                    RequestMessageNormalizer.mergeConsecutiveRequestMessages(requestMessages));
            }
        }

        if (toolSearchEnabled) {
            List<String> deferredNames = engine.getConfig().toolExecutor().getDeferredToolNames();
            if (!deferredNames.isEmpty()) {
                String deferredListing = deferredNames.stream().sorted()
                    .collect(Collectors.joining("\n"));
                requestMessages.addFirst(new StreamingClient.StreamRequest.RequestMessage(
                    "user", "<available-deferred-tools>\n" + deferredListing + "\n</available-deferred-tools>"));
            }
        }

        // The prepended context-reminder/deferred-tools turns must participate
        // in the same merge as the real conversation. Normalizing the engine
        // history before prepending left two adjacent user messages on the wire.
        requestMessages = new ArrayList<>(
            RequestMessageNormalizer.mergeConsecutiveRequestMessages(requestMessages));

        boolean listingAlreadyAttached = Stream.concat(
                promotedLeading.stream(), normalizationInput.stream())
            .anyMatch(message -> message instanceof AttachmentMessage attachment
                && attachment.payload() instanceof AgentListingDeltaAttachment);
        var attachmentService = engine.getAttachmentService();
        boolean inventoryManagedByAttachments = attachmentService != null
            && (attachmentService.hasProvider("agent_listing_delta")
                || attachmentService.hasProvider("skill_listing"));
        String listing = listingAlreadyAttached || inventoryManagedByAttachments
            ? null : agentListing(engine);
        if (StringUtils.isNotBlank(listing)) {
            if (!midConversationSystem) {
                requestMessages.addFirst(new StreamingClient.StreamRequest.RequestMessage(
                    "user", MessageConstants.wrapInSystemReminder(listing)));
                requestMessages = new ArrayList<>(
                    RequestMessageNormalizer.mergeConsecutiveRequestMessages(requestMessages));
            } else {
                int insertAt = firstUserInsertionIndex(requestMessages);
                if (insertAt > 0) {
                    requestMessages.add(insertAt,
                        new StreamingClient.StreamRequest.RequestMessage("system", listing));
                }
            }
        }
        return preserveAtMentionedImageReadSeam(requestMessages);
    }


    static List<StreamingClient.StreamRequest.RequestMessage>
            preserveAtMentionedImageReadSeam(
                List<StreamingClient.StreamRequest.RequestMessage> messages) {
        List<StreamingClient.StreamRequest.RequestMessage> result = new ArrayList<>();
        for (StreamingClient.StreamRequest.RequestMessage message : messages) {
            if (!Strings.CS.equals("user", message.role())
                    || !(message.content() instanceof List<?> rawBlocks)) {
                result.add(message);
                continue;
            }

            List<Map<String, Object>> blocks = new ArrayList<>(rawBlocks.size());
            boolean validBlocks = true;
            for (Object rawBlock : rawBlocks) {
                if (!(rawBlock instanceof Map<?, ?> rawMap)) {
                    validBlocks = false;
                    break;
                }
                Map<String, Object> block = new LinkedHashMap<>();
                rawMap.forEach((key, value) -> block.put(String.valueOf(key), value));
                blocks.add(block);
            }
            if (!validBlocks) {
                result.add(message);
                continue;
            }

            List<Integer> reminderIndexes = new ArrayList<>();
            List<Map<String, Object>> imageBlocks = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                Map<String, Object> block = blocks.get(i);
                if (Strings.CS.equals("image", String.valueOf(block.get("type")))) {
                    imageBlocks.add(block);
                } else if (Strings.CS.equals("text", String.valueOf(block.get("type")))
                        && Strings.CS.startsWith(String.valueOf(block.get("text")),
                            "<system-reminder>\nCalled the Read tool with the following input:")) {
                    reminderIndexes.add(i);
                }
            }
            if (reminderIndexes.size() != 1 || imageBlocks.isEmpty()) {
                result.add(message);
                continue;
            }

            int reminderIndex = reminderIndexes.getFirst();
            String reminder = String.valueOf(blocks.get(reminderIndex).get("text"));
            List<Map<String, Object>> remainder = new ArrayList<>(blocks.size() - 1);
            remainder.addAll(imageBlocks);
            for (int i = 0; i < blocks.size(); i++) {
                Map<String, Object> block = blocks.get(i);
                if (i != reminderIndex
                        && !Strings.CS.equals("image", String.valueOf(block.get("type")))) {
                    remainder.add(block);
                }
            }
            result.add(new StreamingClient.StreamRequest.RequestMessage("user", reminder));
            result.add(new StreamingClient.StreamRequest.RequestMessage("user", remainder));
        }
        return result;
    }

    private static boolean isInitialLeadingAttachment(Message message) {
        if (!(message instanceof AttachmentMessage attachment)) return false;
        return switch (attachment.payload()) {
            case AgentListingDeltaAttachment listing -> listing.isInitial();
            // MCP instructions are emitted between Agent and Skill by
            // buildAttachments. Before the first assistant turn they are the
            // initial snapshot and must be promoted with the other inventory
            // blocks so currentDate cannot split this cache prefix.
            case McpInstructionsDeltaAttachment _ -> true;
            case SkillListingAttachment listing -> listing.isInitial();

            // the same attachment turn, after Skill and before currentDate.
            // Later sparse reminders occur after an assistant turn and are not
            // promoted by the beforeFirstAssistant guard above.
            case PlanModeReminderAttachment _ -> true;
            case AutoModeReminderAttachment _ -> true;
            case HookAdditionalContextAttachment _ -> true;
// The initial output-style reminder is part of the same leading user turn

            case OutputStyleAttachment _ -> true;
            default -> false;
        };
    }


    @SuppressWarnings("unchecked")
    private static List<StreamingClient.StreamRequest.RequestMessage>
            coalesceLeadingUserMessagesWithoutSeam(
                List<StreamingClient.StreamRequest.RequestMessage> messages) {
        if (messages.size() < 2 || messages.stream().anyMatch(m -> !Strings.CS.equals("user", m.role()))) {
            return messages;
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (StreamingClient.StreamRequest.RequestMessage message : messages) {
            if (message.content() instanceof String text) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "text");
                block.put("text", text);
                blocks.add(block);
            } else if (message.content() instanceof List<?> list) {
                blocks.addAll((List<Map<String, Object>>) list);
            }
        }
        return List.of(new StreamingClient.StreamRequest.RequestMessage("user", blocks));
    }

    private static int firstUserInsertionIndex(
            List<StreamingClient.StreamRequest.RequestMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (Strings.CS.equals("user", messages.get(i).role())) return i + 1;
        }
        return -1;
    }

    /** Listing text from the app layer's prompt runtime; null when not wired. */
    public static String agentListing(DefaultQuerySession engine) {
        if (engine.getConfig().promptRuntimeSupplier() == null) return null;
        try {
            var runtime = engine.getConfig().promptRuntimeSupplier().get();
            String listing = runtime != null ? runtime.agentListingMessage() : null;
            if (StringUtils.isBlank(listing)) return listing;
            double total = engine.getConfig().maxBudgetUsd();
            if (total > 0) {
                double used = engine.getCostCalculator().calculateCost(engine.getTotalUsage());
                double remaining = Math.max(0, total - used);
                listing += "\n\nUSD budget: $" + compactDecimal(used)
                    + "/$" + compactDecimal(total) + "; $"
                    + compactDecimal(remaining) + " remaining";
            }
            return listing;
        } catch (Exception _) {
            return null;
        }
    }

    private static String compactDecimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Loads CLAUDE.md files and builds the {@code <system-reminder>} user context
     * message prepended to every API call.
     */
    public static String buildClaudeMdUserContext(DefaultQuerySession engine) {
        if (Strings.CS.equals("1", SubprocessEnvironment.get(
                "CLAUDE_CODE_DISABLE_CLAUDE_MDS"))) {
            return null;
        }
        String workingDir = engine.getConfig().workingDirectory();

        boolean supplierPresent = engine.getConfig().claudeMdContentSupplier() != null;
        String claudeMd = null;
        if (supplierPresent) {
            try {
                claudeMd = engine.getConfig().claudeMdContentSupplier().get();
            } catch (Throwable _) {
                // A configured supplier is authoritative even when it fails: do
                // not bypass setting-source gates by falling back to raw files.
                claudeMd = "";
            }
        }

        // The app-layer supplier already applies --setting-sources, explicit
        // --add-dir paths, and the memory scanner's scope rules.  Its blank
        // result therefore means "no CLAUDE.md is enabled" rather than "read
        // the files again".  Keep the filesystem path only for legacy callers
        // that did not wire a supplier at all.
        if (!supplierPresent) {
            List<String> sections = new ArrayList<>();

            Path userClaudeMd = ClaudePaths.CLAUDE_MD;
            String userContent = readClaudeMdFile(userClaudeMd);
            if (userContent != null) {
                sections.add(formatClaudeMdSection(userClaudeMd,
                    "(user's private global instructions for all projects)", userContent));
            }

            if (workingDir != null) {
                List<Path> parentPaths = new ArrayList<>();
                Path path = Path.of(workingDir);
                Path current = path.toAbsolutePath().normalize();
                while (current != null) {
                    Path candidate = current.resolve("CLAUDE.md");
                    if (Files.isRegularFile(candidate)) {
                        parentPaths.add(candidate);
                    }
                    current = current.getParent();
                }
                for (int i = parentPaths.size() - 1; i >= 0; i--) {
                    Path p = parentPaths.get(i);
                    if (p.equals(userClaudeMd)) continue;
                    boolean isProjectLevel = p.getParent() != null
                        && p.getParent().equals(path.toAbsolutePath().normalize());
                    String desc = isProjectLevel
                        ? "(project instructions, checked into the codebase)"
                        : "(user's private global instructions for all projects)";
                    String content = readClaudeMdFile(p);
                    if (content != null) sections.add(formatClaudeMdSection(p, desc, content));
                }
            }
            claudeMd = sections.isEmpty() ? null : String.join("\n\n", sections);
        }

        String currentDate = "Today's date is " + LocalDate.now() + ".";

        StringBuilder ctx = new StringBuilder();
        if (StringUtils.isNotBlank(claudeMd)) {
            ctx.append("# claudeMd\n");
            ctx.append("""
                Codebase and user instructions are shown below. Be sure to adhere to \
                these instructions. IMPORTANT: These instructions OVERRIDE any default \
                behavior and you MUST follow them exactly as written.

                """);
            ctx.append(claudeMd.strip());
            ctx.append("\n");
        }
        ctx.append("# currentDate\n").append(currentDate);

        return "<system-reminder>\nAs you answer the user's questions, you can use the following context:\n"
            + ctx
            + "\n\n      IMPORTANT: this context may or may not be relevant to your tasks. "
            + "You should not respond to this context unless it is highly relevant to your task.\n</system-reminder>\n";
    }

    private static String readClaudeMdFile(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            String content = Files.readString(path);
            return StringUtils.isBlank(content) ? null : content.strip();
        } catch (Exception _) {
            return null;
        }
    }

    private static String formatClaudeMdSection(Path path, String description, String content) {
        return "Contents of " + path.toAbsolutePath() + " " + description + ":\n\n" + content;
    }

    // ------------------------------------------------------------------------
    // Preamble helpers (skills discovery, session-init message, auto-memory) — Phase 8.
    // ------------------------------------------------------------------------

    /** Cache-only, non-blocking skills discovery — matches QueryLoop#loadSkillsAndPlugins. */
    public static void loadSkillsAndPlugins(DefaultQuerySession engine) {
        String workingDir = engine.getConfig().workingDirectory();
        if (workingDir == null) return;
        Path skillsDir = Path.of(workingDir, ".claude", "skills");
        if (!Files.isDirectory(skillsDir)) return;
        try {
            // Real skill layout is .claude/skills/<name>/SKILL.md — the skill
            // name is the DIRECTORY name (flat *.md files are not skills).
            var skillDirs = skillsDir.toFile().listFiles(f ->
                f.isDirectory() && !Strings.CS.startsWith(f.getName(), ".")
                    && new File(f, "SKILL.md").isFile());
            if (skillDirs != null) {
                for (var dir : skillDirs) {
                    engine.getDiscoveredSkillNames().add(dir.getName());
                }
            }
        } catch (Exception _) {
            // Non-fatal: skills loading should not break the query
        }
    }

    /**
     * Builds the auto-memory system-prompt section (types, save protocol, recall
     * caveats) plus the truncated MEMORY.md content — matches
     *. Returns {@code null} when the
     * auto-memory feature is disabled or no prompt is produced.
     */
    public static String loadMemoryPrompt(DefaultQuerySession engine) {
        String workingDir = engine.getConfig().workingDirectory();
        if (workingDir == null) return null;
        if (Strings.CS.equals("0", SubprocessEnvironment.get(
                "CLAUDE_CODE_DISABLE_AUTO_MEMORY"))) {

            return null;
        }
        try {
            Path workingDirPath = Path.of(workingDir);
            Path memoryDir = AutoMemoryPrompt.resolveAutoMemPath(workingDirPath);
            // Best-effort directory creation so the model can write without checking.
            try {
                Files.createDirectories(memoryDir);
            } catch (Exception _) {}
            return AutoMemoryPrompt.buildAutoMemoryPrompt(workingDirPath);
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Truncation applied to UserPromptSubmit hook output before injection.
     */
    public static final int MAX_HOOK_OUTPUT_LENGTH = 10_000;

    public static String truncateHookOutput(String content) {
        if (content != null && content.length() > MAX_HOOK_OUTPUT_LENGTH) {
            return content.substring(0, MAX_HOOK_OUTPUT_LENGTH)
                + "… [output truncated - exceeded " + MAX_HOOK_OUTPUT_LENGTH + " characters]";
        }
        return content;
    }

    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------

    public static String resolveRuntimeModel(DefaultQuerySession engine) {


        // Resolve aliases (opus/sonnet/…) to concrete ids, matching
        // ModelNames.parseUserSpecifiedModel used for the model setting.
        String override = engine.getModelOverride();
        if (StringUtils.isNotBlank(override)) {
            return ModelNames.parseUserSpecifiedModel(override);
        }
        QuerySessionSpec config = engine.getConfig();
        PermissionModeKind mode = null;
        var supplier = config.permissionModeSupplier();
        if (supplier != null) {
            try {
                mode = supplier.get();
            } catch (Exception _) {
                // permission subsystem mid-teardown — treat as non-plan
            }
        }
        return ModelNames.runtimeMainLoopModel(
            config.model(), mode, mostRecentAssistantExceeds200k(engine));
    }

    public static boolean mostRecentAssistantExceeds200k(DefaultQuerySession engine) {
        List<Message> messages = engine.getMutableMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage am
                    && am.message() != null && am.message().usage() != null) {
                var u = am.message().usage();
                String model = engine.getModelOverride();
                if (StringUtils.isBlank(model)) model = engine.getConfig().model();
                return TokenEstimator.contextTokens(u, model) > 200_000;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------
    // Tool-batch summary helpers (Phase 2/9).
    // ------------------------------------------------------------------------

    /** Per-query snapshot form — the gate is already resolved by {@link QueryConfig}. */
    public static boolean shouldFireToolUseSummary(ToolBatchSummarizer summarizer, String agentId,
            boolean aborted, boolean emitToolUseSummaries) {
        return summarizer != null && agentId == null && !aborted && emitToolUseSummaries;
    }

    /** Legacy/env form, kept for tests that inject an env map. */
    public static boolean shouldFireToolUseSummary(ToolBatchSummarizer summarizer, String agentId,
            boolean aborted, Function<String, String> envLookup) {
        return shouldFireToolUseSummary(summarizer, agentId, aborted,
            emitToolUseSummariesEnabled(envLookup));
    }

    private static boolean emitToolUseSummariesEnabled(Function<String, String> envLookup) {
        return EnvUtils.isEnvTruthy(
            envLookup.apply("CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES"));
    }

    public static List<ToolCallInfo> buildToolCallInfo(List<ContentBlock> toolUseBlocks, DefaultQuerySession engine) {
        List<Message> messages = engine.getMutableMessages();
        List<ToolCallInfo> result = new ArrayList<>(toolUseBlocks.size());
        for (ContentBlock block : toolUseBlocks) {
            if (!(block instanceof ToolUseBlock tub)) continue;
            Object output = null;
            outer:
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof UserMessage um
                        && um.message() != null && um.message().blocks() != null) {
                    for (ContentBlock b : um.message().blocks()) {
                        if (b instanceof ToolResultBlock trb && tub.id().equals(trb.toolUseId())) {
                            output = trb.content();
                            break outer;
                        }
                    }
                }
            }
            result.add(new ToolCallInfo(tub.name(), tub.input(), output));
        }
        return result;
    }

    public static String extractLastAssistantText(AssistantMessage assistantMsg) {
        if (assistantMsg == null || assistantMsg.message() == null
                || assistantMsg.message().content() == null) {
            return null;
        }
        String lastText = null;
        for (ContentBlock block : assistantMsg.message().content()) {
            if (block instanceof TextBlock(String text)) {
                lastText = text;
            }
        }
        return lastText;
    }

    // ------------------------------------------------------------------------
    // Stream-block accumulator (rules of thinking — order + signature
    // preservation for verbatim replay).
    // ------------------------------------------------------------------------

    public static class BlockBuilder {
        public final String type;
        public final String id;
        public final String name;
        public final ContentBlock completeBlock;
        public final StringBuilder text = new StringBuilder();
        public final StringBuilder thinking = new StringBuilder();
        public final StringBuilder signature = new StringBuilder();
        public final StringBuilder inputJson = new StringBuilder();

        public BlockBuilder(String type, String id, String name) {
            this(type, id, name, null);
        }

        public BlockBuilder(String type, String id, String name, ContentBlock completeBlock) {
            this.type = type != null ? type : "text";
            this.id = id;
            this.name = name;
            this.completeBlock = completeBlock;
        }

        public static String typeForDelta(String deltaType) {
            return switch (deltaType) {
                case "thinking_delta", "signature_delta" -> "thinking";
                case "input_json_delta" -> "tool_use";
                default -> "text";
            };
        }

        public ContentBlock build() {
            if (completeBlock != null) return completeBlock;
            return switch (type) {
                case "tool_use" -> {
                    JsonNode inputNode;
                    try {
                        String json = inputJson.toString();
                        inputNode = json.isEmpty()
                            ? JsonUtils.getMapper().createObjectNode()
                            : JsonUtils.getMapper().readTree(json);
                    } catch (Exception _) {
                        inputNode = JsonUtils.getMapper().createObjectNode();
                    }
                    yield new ToolUseBlock(id, name, inputNode);
                }
                case "thinking" -> (thinking.isEmpty() && signature.isEmpty())
                    ? null
                    : new ThinkingBlock(thinking.toString(),
                        signature.isEmpty() ? null : signature.toString());
                case "server_tool_use" -> {
                    JsonNode inputNode;
                    try {
                        String json = inputJson.toString();
                        inputNode = json.isEmpty()
                            ? JsonUtils.getMapper().createObjectNode()
                            : JsonUtils.getMapper().readTree(json);
                    } catch (Exception _) {
                        inputNode = JsonUtils.getMapper().createObjectNode();
                    }
                    yield new ServerToolUseBlock(id, name, inputNode);
                }
                default -> text.isEmpty() ? null : new TextBlock(text.toString());
            };
        }
    }
}
