package com.claudecode.runtime.query;

import com.claudecode.core.engine.*;
import com.claudecode.core.error.ErrorUtils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.HookErrorDuringExecutionAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One tool-use turn's single-tool execution step, shared by every {@link ToolRunner} implementation
 * so serial and concurrent execution cannot drift in permission/hook/context/result-message
 * behavior.
 */
final class ToolExecution {

    private static final Logger log = LoggerFactory.getLogger(ToolExecution.class);

    private ToolExecution() {}

    /**
     * Outcome of running one tool via {@link #step}.
     *
     * @param error              whether the tool call errored (is_error result or exception)
     * @param lastError          the exception thrown, if any (soft is_error results have none)
     * @param preventContinuation a hook demanded the whole query stop
     * @param stopReason         hook-supplied reason when {@code preventContinuation}
     * @param structuredOutput   the StructuredOutput payload, or {@code null}
     */
    public record StepResult(
        boolean error,
        Exception lastError,
        boolean preventContinuation,
        String stopReason,
        JsonNode structuredOutput) {}

    /**
     * Built result of one tool, without the history append / stream emit — the
     * caller decides ordering (serial = immediate, concurrent = after join).
     *
     * @param tub          the tool-use block this result answers
     * @param resultMsg    the tool_result user message (already assembled)
     * @param structuredOutput the StructuredOutput payload, or {@code null}
     * @param error        whether the tool call errored
     * @param lastError    the exception thrown, if any
     */
    public record ToolStep(
        ToolUseBlock tub,
        UserMessage resultMsg,
        JsonNode structuredOutput,
        boolean error,
        Exception lastError,
        boolean preventContinuation,
        String stopReason,
        List<Message> newMessages,
        Runnable afterResultEmitted) {}

    /**
     * Serial entry point: runs one tool, appends its result to history, emits it,
     * and returns the folded outcome. Order is the caller's iteration order.
     */
    public static StepResult step(
        ToolUseBlock tub,
        DefaultQuerySession engine,
        Consumer<SDKMessage> emit,
        String sourceAssistantUuid
    ) {
        ToolStep ts = execute(tub, engine, null, emit, sourceAssistantUuid);
        engine.getMutableMessages().add(ts.resultMsg());
        emit.accept(new SDKMessage.User(ts.resultMsg()));
        runAfterResultEmitted(ts.afterResultEmitted());
        QueryHelpers.recordTranscript(engine, ts.resultMsg());
        // Inject any extra conversation messages the tool run produced

        // ~1566) being appended right after the tool_result.
        if (ts.newMessages() != null) {
            for (Message m : ts.newMessages()) {
                engine.getMutableMessages().add(m);
                SDKMessage wrapped = toSdkMessage(m);
                if (wrapped != null) emit.accept(wrapped);
                QueryHelpers.recordTranscript(engine, m);
            }
        }
        return new StepResult(ts.error(), ts.lastError(),
            ts.preventContinuation(), ts.stopReason(), ts.structuredOutput());
    }

    /**
     * Core tool execution shared by every runner. Builds the tool_result
     * {@link UserMessage} (and captures the structured-output payload) but does
     * NOT append it to history or emit it — the caller is responsible for
     * ordering. {@code siblingAbort} (may be {@code null}) is the per-batch
     * abort signal used for sibling-cancel on a Bash error in concurrent runs;
     * when non-null it is honored alongside the engine-level abort.
     */
    public static ToolStep execute(
        ToolUseBlock tub,
        DefaultQuerySession engine,
        AbortController siblingAbort,
        Consumer<SDKMessage> emit,
        String sourceAssistantUuid
    ) {
        engine.sessionMetricsTracker().toolCall(tub.id());
        try {
            return executeTracked(tub, engine, siblingAbort, emit, sourceAssistantUuid);
        } finally {
            engine.sessionMetricsTracker().toolResult(tub.id());
        }
    }

    private static ToolStep executeTracked(
        ToolUseBlock tub,
        DefaultQuerySession engine,
        AbortController siblingAbort,
        Consumer<SDKMessage> emit,
        String sourceAssistantUuid
    ) {
        JsonNode input = tub.input();
        AbortController abortCtrl = (siblingAbort != null) ? siblingAbort : engine.getAbortController();


        // placeholders for every tool skipped after the query is aborted.
        // A sibling-error cascade (siblingAbort aborted but the engine itself
        // is NOT aborted) gets the distinct sibling-error message so the model
// doesn't read it as a user reject (matches StreamingToolExecutor).
        if (abortCtrl.isAborted()) {

            // sibling error (hasErrored) wins over a plain engine abort, so the
            // tool that triggered the cascade — and any sibling — report the
            // sibling-error text rather than the user-reject text.
            String cancelText = (abortCtrl == siblingAbort
                    && siblingAbort.isAborted()
                    && MessageConstants.SIBLING_ERROR_REASON.equals(siblingAbort.getReason()))
                ? MessageConstants.siblingErrorMessage(tub.name())
                : MessageConstants.abortMessage(engine.getAbortController().getReason());
            UserMessage cancelMsg = new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofToolResult(tub.id(),
                    List.of(new TextBlock(cancelText)), true),
                false, false, null, MessageOrigin.USER, null, Instant.now(), null, null, null,
                sourceAssistantUuid);
            return new ToolStep(tub, cancelMsg, null, false, null, false, null, null, null);
        }

// ---- Tool Pre/Post hook dispatch.
        boolean preventContinuation = false;
        String stopReason = null;
        List<Message> hookContextMessages = new ArrayList<>();
        JsonNode preSpecificOutput = null;
        HookDispatcher hooks = engine.getHookDispatcher();
        if (hooks != null) {
            HookDispatcher.HookOutcome pre = hooks.dispatchPreToolUseWithOutcome(tub.name(), input, tub.id());
            if (pre.preventContinuation()) {
                preventContinuation = true;
                stopReason = pre.stopReason();
            }
            appendHookContexts(hookContextMessages, pre);
            if (!pre.proceed()) {

                // result and never executes the tool. Synthesize a denial result.
                String deny = String.join("\n", pre.blockingErrors());
                if (StringUtils.isBlank(deny) && pre.hasAdditionalContext()) {
                    deny = pre.additionalContext();
                }
                if (StringUtils.isBlank(deny)) {
                    deny = "blocked by PreToolUse hook";
                }
                UserMessage deniedMsg = new UserMessage(
                    UUID.randomUUID().toString(),
                    MessageContent.ofToolResult(tub.id(),
                        List.of(new TextBlock(deny)), true),
                    false, false, "Error: " + deny, MessageOrigin.USER, null, Instant.now(), null, null, null,
                    sourceAssistantUuid);
                engine.addPermissionDenial(new SDKMessage.PermissionDenial(
                    Strings.CS.equals("Agent", tub.name()) ? "Task" : tub.name(),
                    tub.id(), permissionDenialInput(input)));
                return new ToolStep(tub, deniedMsg, null, true, null, preventContinuation, stopReason, null, null);
            }
            JsonNode updatedInput = pre.specificOutput("PreToolUse")
                .map(output -> output.get("updatedInput"))
                .orElse(null);
            if (updatedInput != null && updatedInput.isObject()) {
                input = updatedInput.deepCopy();
            }
            preSpecificOutput = pre.specificOutput("PreToolUse").orElse(null);
        }

        // Per-call execution context, honoring the engine's configured
        // workingDirectory (NOT the process cwd) — ToolRunnerWorkingDirectoryTest.
        // Progress sink is wrapped to:

// onProgress({toolUseID,...}) identity injection; (2) emit SDKMessage.Progress into the
// message stream for structured progress.

        ToolExecutionContext.ProgressSink parentSink = engine.getConfig().progressSink();
        ToolExecutionContext.ProgressSink stampedSink =
            (parentSink == ToolExecutionContext.ProgressSink.NOOP && emit == null)
                ? ToolExecutionContext.ProgressSink.NOOP
                : update -> {
                    ToolExecutionContext.ProgressUpdate stamped = update.withIdentity(tub.id(), null);
                    // (1) side-channel: status line
                    if (parentSink != ToolExecutionContext.ProgressSink.NOOP) {
                        parentSink.accept(stamped);
                    }

                    if (stamped.dataType() != null
                            && !Strings.CS.equals("agent_background_hint", stamped.dataType())
                            && emit != null) {
                        emit.accept(new SDKMessage.Progress(
                            MessageFactory.createProgressMessage(
                                stamped.toolUseId(), stamped.parentToolUseId(),
                                new ProgressMessage.ProgressData(
                                    stamped.dataType(),
                                    stamped.output(),
                                    stamped.fullOutput(),
                                    stamped.elapsedSeconds(),
                                    stamped.totalLines(),
                                    stamped.totalBytes(),
                                    stamped.timeoutMs(),
                                    null,
                                    !stamped.complete(),
                                    stamped.agentMessage(),
                                    stamped.prompt(),
                                    stamped.agentId(),
                                    stamped.progressValue(),
                                    stamped.total(),
                                    stamped.progressMessage(),
                                    stamped.query(),
                                    stamped.resultCount(),
                                    stamped.resolvedModel()))));
                    }
                };
        PermissionModeKind currentPermissionMode = null;
        var permissionModeSupplier = engine.getConfig().permissionModeSupplier();
        if (permissionModeSupplier != null) {
            try {
                currentPermissionMode = permissionModeSupplier.get();
            } catch (RuntimeException _) {
                // The live permission subsystem may be tearing down. Preserve
                // the legacy null snapshot instead of failing the tool call.
            }
        }
        PermissionAskCallback permissionAskCallback = permissionHookCallback(
            engine.getPermissionAskCallback(), hooks, preSpecificOutput,
            tub.name(), input, tub.id(), engine.getAbortController());
        ToolExecutionContext.ToolDurationTiming toolDurationTiming =
            new ToolExecutionContext.ToolDurationTiming(
                SessionCostState.get()::recordToolDuration);
        ToolExecutionContext ctx = ToolExecutionContext
            .builder(abortCtrl, engine.getSessionId())
            .workingDirectory(engine.getConfig().workingDirectory())
            .progressSink(stampedSink)
            .permissionAskCallback(permissionAskCallback)
            .fileStateCache(engine.getFileStateCache())
            .fileHistoryManager(engine.getFileHistoryManager())
            .currentUserMessageId(sourceAssistantUuid)
            .messageQueueManager(engine.getMessageQueue())
            .agentId(engine.getConfig().agentId())
            .agentDepth(engine.getConfig().agentDepth())
            .subagentMaxDepthSnapshot(engine.getConfig().subagentMaxDepthSnapshot())
            .nestedMemoryAttachmentTriggers(engine.getNestedMemoryAttachmentTriggers())
            .loadedNestedMemoryPaths(engine.getLoadedNestedMemoryPaths())
            .teamMemoryEnabled(engine.getConfig().teamMemoryEnabledSupplier().get())
            .currentModel(engine.getConfig().model())
            .sandboxConfig(engine.getConfig().sandboxConfigSupplier().get())
            .readDenyIgnorePatterns(engine.getConfig().readDenyIgnorePatternsSupplier().get())
            .toolUseId(tub.id())
            .toolDurationTiming(toolDurationTiming)
            .turnTokenBudget(engine.getTurnTokenBudget())
            .workingDirectoryController(engine.workingDirectoryController())
            .enabledTools(engine.getConfig().tools())
            .permissionDenialSink(engine.permissionDenialRecorder())
            .currentPermissionMode(currentPermissionMode)
            .conversationMessages(engine.getMessages())
            .renderedSystemPrompt(engine.fetchSystemPromptParts())
            .build();

        ToolExecutor executor = engine.getConfig().toolExecutor();
        ToolExecutor.McpAttribution mcpAttribution = executor.mcpAttribution(tub.name());
        if (mcpAttribution != null) {

            engine.activateMcpAttribution(
                mcpAttribution.serverName(), mcpAttribution.toolName());
        }
        ToolResult result;
        long toolStartMs = System.currentTimeMillis();
        try {
            result = executor.execute(tub.name(), input, ctx);
        } catch (Exception e) {
            log.warn("[TOOL] Tool execution failed [sessionId={}, tool={}, toolUseId={}, "
                    + "agentId={}, aborted={}, failureType={}]",
                engine.getSessionId(), tub.name(), tub.id(), engine.getConfig().agentId(),
                engine.getAbortController().isAborted(), e.getClass().getName(),
                ErrorUtils.redactedForLogging(e));
            // Tool errors never terminate the query.
            String errorText = "Tool execution failed: " + e.getMessage();
            HookDispatcher.HookOutcome failureOutcome = dispatchPostToolUseFailure(
                hooks, tub, input, errorText, engine.getAbortController().isAborted());
            if (failureOutcome.preventContinuation()) {
                preventContinuation = true;
                stopReason = failureOutcome.stopReason();
            }
            appendHookContexts(hookContextMessages, failureOutcome);
            UserMessage errMsg = new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofToolResult(tub.id(),
                    List.of(new TextBlock(errorText)), true),
                false, false, null, MessageOrigin.USER, null, Instant.now(), null, null, null,
                sourceAssistantUuid);
            return new ToolStep(tub, errMsg, null, true, e, preventContinuation, stopReason,
                List.copyOf(hookContextMessages), null);
        } finally {
            if (!toolDurationTiming.handled()) {
                toolDurationTiming.recordElapsed(
                    Math.max(0L, System.currentTimeMillis() - toolStartMs));
            }
        }


        ToolContextModifier modifier = result.contextModifier();
        if (modifier != null && !modifier.isEmpty()) {
            engine.applyContextModifier(modifier);
        }


        // concurrency-safe tools. The engine-level abort never sets siblingAbort,
        // so this only affects concurrent batches that self-abort. When the engine
        // itself is aborted (user interrupt), the user-reject CANCEL_MESSAGE is
        // used instead.

        // with `thisToolErrored` so the tool that triggered the cascade keeps its
        // own real error result (it is the cause, not a victim). Without this, a
        // Bash error would be swallowed and replaced by the sibling-cancel text.
        if (siblingAbort != null && siblingAbort.isAborted() && !result.isError()) {
            String cancelText = engine.getAbortController().isAborted()
                ? MessageConstants.CANCEL_MESSAGE
                : MessageConstants.siblingErrorMessage(tub.name());
            UserMessage cancelMsg = new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofToolResult(tub.id(),
                    List.of(new TextBlock(cancelText)), true),
                false, false, null, MessageOrigin.USER, null, Instant.now(), null, null, null,
                sourceAssistantUuid);
            return new ToolStep(tub, cancelMsg, null, false, null, preventContinuation, stopReason, null, null);
        }


        JsonNode hookUpdatedOutput = null;
        if (hooks != null) {
            HookDispatcher.HookOutcome post;
            if (result.isError()) {
                String errorText = MessageConstants.extractTextContent(result.content(), "\n").trim();
                post = dispatchPostToolUseFailure(hooks, tub, input, errorText,
                    engine.getAbortController().isAborted());
            } else {
                JsonNode toolOutput = result.toolUseResult() instanceof JsonNode n ? n : null;
                post = hooks.dispatchPostToolUseWithOutcome(tub.name(), input, toolOutput, tub.id());
            }
            if (post.preventContinuation()) {
                preventContinuation = true;
                stopReason = post.stopReason();
            }
            appendHookContexts(hookContextMessages, post);
            if (!result.isError()) {
                JsonNode specific = post.specificOutput("PostToolUse").orElse(null);
                if (specific != null) {
                    JsonNode updated = specific.get("updatedToolOutput");
                    if (updated == null || updated.isNull()) {
                        updated = specific.get("updatedMCPToolOutput");
                    }
                    if (updated != null && !updated.isNull()) {
                        hookUpdatedOutput = updated.deepCopy();
                    }
                }
            }
        }

        if (hookUpdatedOutput != null) {
            PostToolUseOutputResult replacement = executor.processPostToolUseOutput(
                tub.name(), input, hookUpdatedOutput, result, ctx);
            switch (replacement) {
                case PostToolUseOutputResult.Applied applied -> result = applied.result();
                case PostToolUseOutputResult.Rejected rejected -> hookContextMessages.add(
                    new AttachmentMessage(UUID.randomUUID().toString(),
                        new HookErrorDuringExecutionAttachment(
                            rejected.reason(), "PostToolUse:" + tub.name(),
                            tub.id(), "PostToolUse")));
            }
        }

        // LSP passive-diagnostics hook: notify after a successful Write/Edit.
        if (!result.isError()
                && (Strings.CS.equals("Write", tub.name()) || Strings.CS.equals("Edit", tub.name()))
                && input != null) {
            FileChangeListener listener = engine.getFileChangeListener();
            if (listener != null) {
                JsonNode fp = input.get("file_path");
                if (fp != null && fp.isTextual()) {
                    listener.onFileChanged(Path.of(fp.asText()), tub.name());
                }
            }
        }


        UserMessage resultMsg;
        if (result.acceptFeedback() != null || !result.userFeedbackBlocks().isEmpty()) {
            ToolResultBlock trb = new ToolResultBlock(
                tub.id(), result.content(), result.isError(), result.includeIsErrorField(),
                preservesToolResultBlocks(result));
            List<ContentBlock> resultBlocks = new ArrayList<>();
            resultBlocks.add(trb);
            if (result.acceptFeedback() != null) {
                resultBlocks.add(new TextBlock(result.acceptFeedback()));
            }
            resultBlocks.addAll(result.userFeedbackBlocks());
            resultMsg = new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofBlocks(List.copyOf(resultBlocks)),
                false, false,
                result.toolUseResult(),
                MessageOrigin.USER, null, Instant.now(), null, null, null,
                sourceAssistantUuid, null, result.mcpMeta());
        } else {
            resultMsg = new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofBlocks(List.of(new ToolResultBlock(
                    tub.id(), result.content(), result.isError(), result.includeIsErrorField(),
                    preservesToolResultBlocks(result)))),
                false, false,
                result.toolUseResult(),
                MessageOrigin.USER, null, Instant.now(), null, null, null,
                sourceAssistantUuid, null, result.mcpMeta());
        }

        // Thread the structured_output payload: captured generically from any tool

        // special-casing a tool name. The legacy hard-coded "StructuredOutput"
        // tool-name fallback is kept for backward compatibility.
        JsonNode structuredOutput = result.structuredOutput();
        if (structuredOutput == null
                && Strings.CS.equals("StructuredOutput", tub.name())
                && result.toolUseResult() instanceof JsonNode node) {
            structuredOutput = node;
        }

        List<Message> newMessages = mergeMessages(result.newMessages(), hookContextMessages);
        return new ToolStep(tub, resultMsg, structuredOutput, result.isError(), null,
            preventContinuation, stopReason, newMessages, result.afterResultEmitted());
    }

    private static HookDispatcher.HookOutcome dispatchPostToolUseFailure(
            HookDispatcher hooks, ToolUseBlock toolUse, JsonNode input,
            String error, boolean isInterrupt) {
        if (hooks == null) return HookDispatcher.HookOutcome.PROCEED;
        return hooks.dispatchPostToolUseFailureWithOutcome(
            toolUse.name(), input, toolUse.id(), error, isInterrupt);
    }

    private static void appendHookContexts(List<Message> target,
                                           HookDispatcher.HookOutcome outcome) {
        for (String context : outcome.additionalContexts()) {
            if (StringUtils.isNotBlank(context)) {
                target.add(MessageFactory.createUserMessage(
                    MessageConstants.wrapInSystemReminder(context), true));
            }
        }
    }

    private static List<Message> mergeMessages(List<Message> toolMessages,
                                               List<Message> hookMessages) {
        if ((toolMessages == null || toolMessages.isEmpty()) && hookMessages.isEmpty()) {
            return null;
        }
        var merged = new ArrayList<Message>();
        if (toolMessages != null) merged.addAll(toolMessages);
        merged.addAll(hookMessages);
        return List.copyOf(merged);
    }

    private static PermissionAskCallback permissionHookCallback(
            PermissionAskCallback delegate, HookDispatcher hooks, JsonNode preSpecific,
            String toolName, JsonNode input, String toolUseId,
            AbortController abortController) {
        if (hooks == null && delegate == null) return null;
        return context -> {
            String preDecision = preSpecific == null
                ? "" : preSpecific.path("permissionDecision").asText("");
            if (Strings.CS.equals("allow", preDecision)) {
                return PermissionAskCallback.Result.allowWithInput(input);
            }
            HookDispatcher.HookOutcome request = hooks == null
                ? HookDispatcher.HookOutcome.PROCEED
                : hooks.dispatchPermissionRequestWithOutcome(toolName, input, toolUseId);
            JsonNode decision = request.specificOutput("PermissionRequest")
                .map(output -> output.get("decision")).orElse(null);
            if (decision != null && decision.isObject()) {
                String behavior = decision.path("behavior").asText();
                if (Strings.CS.equals("allow", behavior)) {
                    JsonNode updated = decision.get("updatedInput");
                    List<PermissionUpdate> updates = PermissionUpdateJsonCodec.fromJson(
                        decision.get("updatedPermissions"));
                    return PermissionAskCallback.Result.allowWithInputAndPermissions(
                        updated != null && updated.isObject() ? updated : null, updates);
                }
                if (Strings.CS.equals("deny", behavior)) {
                    String message = decision.path("message").asText("Permission denied by hook");
                    if (decision.path("interrupt").asBoolean(false) && abortController != null) {
                        abortController.abort(message);
                    }
                    return PermissionAskCallback.Result.denyWithDirectMessage(message);
                }
            }
            if (hooks != null && delegate != null) {
                hooks.dispatchNotification(
                    "Claude needs your permission to use " + toolName,
                    "Permission required", "permission_prompt");
            }
            PermissionAskCallback.Result answer = delegate != null
                ? delegate.ask(context) : PermissionAskCallback.Result.deny();
            if (!answer.allowed() && hooks != null) {
                String reason = answer.feedback() != null ? answer.feedback() : "User denied permission";
                HookDispatcher.HookOutcome denied = hooks.dispatchPermissionDeniedWithOutcome(
                    toolName, input, toolUseId, reason);
                boolean retry = denied.specificOutput("PermissionDenied")
                    .map(output -> output.path("retry").asBoolean(false)).orElse(false);
                if (retry && delegate != null) {
                    return delegate.ask(context);
                }
            }
            return answer;
        };
    }

    private static boolean preservesToolResultBlocks(ToolResult result) {
        return result.contentForm() == ToolResultContentForm.BLOCKS;
    }

    private static void runAfterResultEmitted(Runnable callback) {
        if (callback == null) return;
        try {
            callback.run();
        } catch (RuntimeException _) {
            // SDK emission is authoritative; an internal lifecycle observer must
            // never turn a completed tool call into a model-visible failure.
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> permissionDenialInput(JsonNode input) {
        if (input == null || !input.isObject()) return Map.of();
        try {
            return JsonUtils.getMapper().convertValue(input, Map.class);
        } catch (IllegalArgumentException _) {
            return Map.of();
        }
    }

    /**
     * Wraps an injected conversation {@link Message} ({@code newMessages}) into the
     * corresponding {@link SDKMessage} for streaming.  Returns {@code null} for
     * message kinds that have no direct SDKMessage equivalent (they are still
     * appended to history, just not re-streamed).
     */
    static SDKMessage toSdkMessage(Message m) {
        if (m instanceof UserMessage u) {
            boolean synthetic = u.isMeta()
                || Boolean.TRUE.equals(u.isVisibleInTranscriptOnly());
            return new SDKMessage.User(u, false, null, null, null, synthetic);
        }
        if (m instanceof AssistantMessage a) return new SDKMessage.Assistant(a, null);
        if (m instanceof SystemMessage s) return new SDKMessage.System(s);
        return null;
    }
}
