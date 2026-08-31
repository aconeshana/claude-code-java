package com.claudecode.runtime.query;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.HookDispatcher;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.serialization.JsonUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Concurrent implementation of {@link ToolRunner}.
 */
final class ConcurrentToolRunner implements ToolRunner {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentToolRunner.class);

    private static final int DEFAULT_MAX_CONCURRENCY = 10;
    private static final String CONCURRENCY_ENV = "CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY";

    private static int maxConcurrency() {

        // exists only so tests can force a small cap (env can't be set per-JVM).
        String raw = SubprocessEnvironment.get(CONCURRENCY_ENV);
        if (raw == null) raw = System.getProperty("claude.code.maxToolUseConcurrency");
        if (StringUtils.isNotBlank(raw)) {
            try {
                int v = Integer.parseInt(raw.trim());
                if (v > 0) return v;
            } catch (NumberFormatException _) {
                // fall through to default
            }
        }
        return DEFAULT_MAX_CONCURRENCY;
    }

    @Override
    public RunOutcome run(
        List<ContentBlock> toolUseBlocks,
        DefaultQuerySession engine,
        boolean structuredOutputMode,
        int currentTurn,
        Consumer<SDKMessage> emit,
        String sourceAssistantUuid
    ) {
// Partition into ordered batches.
        List<List<ToolUseBlock>> batches = partition(toolUseBlocks, engine.getConfig().toolExecutor());

        boolean errorDuringExecution = false;
        Exception lastError = null;
        boolean preventContinuation = false;
        String stopReason = null;
        JsonNode structuredOutput = null;

        int maxConcurrency = maxConcurrency();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, maxConcurrency))) {
            for (List<ToolUseBlock> batch : batches) {
                if (batch.size() == 1) {
                    // Serial batch: run in order, append + emit immediately.
                    ToolExecution.StepResult r = ToolExecution.step(
                        batch.getFirst(), engine, emit, sourceAssistantUuid);
                    if (r.error()) {
                        errorDuringExecution = true;
                        lastError = r.lastError();
                    }
                    if (r.preventContinuation()) {
                        preventContinuation = true;
                        if (r.stopReason() != null) stopReason = r.stopReason();
                    }
                    if (r.structuredOutput() != null) structuredOutput = r.structuredOutput();
                    continue;
                }


                // StreamingToolExecutor (the active production path), which only
                // yields a tool's results once every earlier tool in the batch
                // has been yielded. This keeps the streamed transcript order
                // faithful to production. A Bash error still aborts in-flight
                // siblings promptly (not just queued ones) via siblingAbort, so
                // cascading failures stop quickly. This is the production

                // emits in completion order and is the disabled fallback).
                AbortController siblingAbort = new AbortController();
                List<Future<ToolExecution.ToolStep>> futures =
                    new ArrayList<>(batch.size());
                // A genuine engine-level (user) interrupt aborts the batch and
                // cancels every future, including the running tool — the whole
                // turn is being torn down. A self-cascade (Bash error below) does
                // NOT take this path: it skips the offending tool's own future so
                // its real error survives.
                engine.getAbortController().onAbort(() -> {
                    log.warn("[ABORT] engine-level abort cascade hit a concurrent batch of {} tool(s) "
                            + "(reason={}) — cancel(true) on every in-flight Future: {}",
                        futures.size(), engine.getAbortController().getReason(),
                        batch.stream().map(b -> b.name() + ":" + b.id()).toList());
                    siblingAbort.abort(engine.getAbortController().getReason());
                    futures.forEach(f -> f.cancel(true));
                });

                int idx = 0;
                for (ToolUseBlock tub : batch) {
                    final int myIdx = idx++;
                    futures.add(pool.submit(() -> {
                        ToolExecution.ToolStep step;
                        try {
                            step = ToolExecution.execute(tub, engine, siblingAbort, emit, sourceAssistantUuid);
                        } catch (Throwable _) {
                            Thread.currentThread().interrupt();
                            step = cancelledToolStep(tub,
                                cancelMessageFor(tub, engine, siblingAbort), sourceAssistantUuid);
                        }

                        // its own result (tengu thisToolErrored guard). siblingAbort
                        // itself is still flagged so in-flight siblings' ToolExecution
                        // sibling-guard reports them as sibling-cancelled.
                        if (step.error() && Strings.CS.equals("Bash", tub.name())) {
                            siblingAbort.abort(MessageConstants.SIBLING_ERROR_REASON);
                            for (int j = 0; j < futures.size(); j++) {
                                if (j != myIdx) futures.get(j).cancel(true);
                            }
                        }
                        return step;
                    }));
                }

                // Emit + append in ORIGINAL tool order (block on each future in
                // turn, so a slower earlier tool gates later ones — same as
                // StreamingToolExecutor.getCompletedResults). Progress messages
                // still stream inline as each tool runs (handled inside
                // ToolExecution via emit), matching ST's immediate progress.
                for (int i = 0; i < batch.size(); i++) {
                    ToolExecution.ToolStep ts;
                    try {
                        ts = futures.get(i).get();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        log.warn("[ABORT] batch tool '{}' ({}) hit InterruptedException while collecting "
                                + "its result (engineAborted={}, engineReason={}, siblingAborted={}, "
                                + "siblingReason={})",
                            batch.get(i).name(), batch.get(i).id(), engine.getAbortController().isAborted(),
                            engine.getAbortController().getReason(), siblingAbort.isAborted(),
                            siblingAbort.getReason());
                        ts = cancelledToolStep(batch.get(i),
                            cancelMessageFor(batch.get(i), engine, siblingAbort), sourceAssistantUuid);
                    } catch (ExecutionException ee) {
                        log.warn("[ABORT] batch tool '{}' ({}) hit ExecutionException while collecting "
                                + "its result (engineAborted={}, engineReason={}, siblingAborted={}, "
                                + "siblingReason={})",
                            batch.get(i).name(), batch.get(i).id(), engine.getAbortController().isAborted(),
                            engine.getAbortController().getReason(), siblingAbort.isAborted(),
                            siblingAbort.getReason(), ee.getCause());
                        ts = cancelledToolStep(batch.get(i),
                            cancelMessageFor(batch.get(i), engine, siblingAbort), sourceAssistantUuid);
                    } catch (CancellationException _) {
                        // queued sibling never started before siblingAbort fired.
                        log.warn("[ABORT] batch tool '{}' ({}) was CANCELLED while collecting its result "
                                + "— its Future.cancel(true) may have raced a real, already-computed "
                                + "result (engineAborted={}, engineReason={}, siblingAborted={}, "
                                + "siblingReason={})",
                            batch.get(i).name(), batch.get(i).id(), engine.getAbortController().isAborted(),
                            engine.getAbortController().getReason(), siblingAbort.isAborted(),
                            siblingAbort.getReason());
                        ts = cancelledToolStep(batch.get(i),
                            cancelMessageFor(batch.get(i), engine, siblingAbort), sourceAssistantUuid);
                    }
                    engine.getMutableMessages().add(ts.resultMsg());
                    emit.accept(new SDKMessage.User(ts.resultMsg()));
                    if (ts.afterResultEmitted() != null) {
                        try {
                            ts.afterResultEmitted().run();
                        } catch (RuntimeException _) {
                            // Internal lifecycle callback; tool_result emission wins.
                        }
                    }
                    QueryHelpers.recordTranscript(engine, ts.resultMsg());
                    if (ts.newMessages() != null) {
                        for (Message m : ts.newMessages()) {
                            SDKMessage wrapped = ToolExecution.toSdkMessage(m);
                            if (wrapped != null) emit.accept(wrapped);
                            QueryHelpers.recordTranscript(engine, m);
                        }
                        engine.getMutableMessages().addAll(ts.newMessages());
                    }
                    if (ts.error()) {
                        errorDuringExecution = true;
                        if (ts.lastError() != null) lastError = ts.lastError();
                    }
                    if (ts.preventContinuation()) {
                        preventContinuation = true;
                        if (ts.stopReason() != null) stopReason = ts.stopReason();
                    }
                    if (ts.structuredOutput() != null) structuredOutput = ts.structuredOutput();
                }
            }
        }

        HookDispatcher hooks = engine.getHookDispatcher();
        if (hooks != null && !toolUseBlocks.isEmpty()) {
            HookDispatcher.HookOutcome batchOutcome = hooks.dispatchPostToolBatchWithOutcome(
                buildPostToolBatchCalls(toolUseBlocks, engine.getMessages()));
            if (batchOutcome.preventContinuation()) {
                preventContinuation = true;
                if (batchOutcome.stopReason() != null) stopReason = batchOutcome.stopReason();
            }
            for (String context : batchOutcome.additionalContexts()) {
                if (StringUtils.isBlank(context)) continue;
                UserMessage message = MessageFactory.createUserMessage(
                    MessageConstants.wrapInSystemReminder(context), true);
                engine.getMutableMessages().add(message);
                emit.accept(new SDKMessage.User(message));
                QueryHelpers.recordTranscript(engine, message);
            }
        }

        return new RunOutcome(errorDuringExecution, lastError, currentTurn,
            preventContinuation, stopReason, structuredOutput);
    }

    private static JsonNode buildPostToolBatchCalls(
            List<ContentBlock> blocks, List<Message> messages) {
        var calls = JsonUtils.getMapper().createArrayNode();
        for (ContentBlock block : blocks) {
            if (!(block instanceof ToolUseBlock toolUse)) continue;
            var call = calls.addObject();
            call.put("tool_name", toolUse.name());
            call.set("tool_input", toolUse.input());
            call.put("tool_use_id", toolUse.id());
            ToolResultBlock result = findToolResult(messages, toolUse.id());
            if (result != null) {
                call.set("tool_response", JsonUtils.getMapper().valueToTree(result.content()));
                call.put("is_error", result.isError());
            }
        }
        return calls;
    }

    private static ToolResultBlock findToolResult(List<Message> messages, String toolUseId) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof UserMessage user)) continue;
            if (user.message() == null || user.message().blocks() == null) continue;
            for (ContentBlock block : user.message().blocks()) {
                if (block instanceof ToolResultBlock result
                        && Strings.CS.equals(toolUseId, result.toolUseId())) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Placeholder for a tool whose executor thread was interrupted or threw outside the tool's own
     * error path — keeps the tool_use paired in history.
     */
    private static ToolExecution.ToolStep cancelledToolStep(
        ToolUseBlock tub, String message, String sourceAssistantUuid) {
        UserMessage cancelMsg = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofToolResult(tub.id(),
                List.of(new TextBlock(message)), true),
            false, false, null, MessageOrigin.USER, null, Instant.now(), null, null, null,
            sourceAssistantUuid);
        return new ToolExecution.ToolStep(tub, cancelMsg, null, false, null, false, null, null, null);
    }

    /**
     * Picks the cancellation message for a tool that did not produce a real
     * result: a sibling-error cascade (siblingAbort aborted but the engine
     * itself was not aborted) gets {@link MessageConstants#siblingErrorMessage};
     * an engine-level abort (user interrupt / reject) gets the reject-style
     * {@link MessageConstants#CANCEL_MESSAGE}.
     */
    private static String cancelMessageFor(
        ToolUseBlock tub, DefaultQuerySession engine, AbortController siblingAbort
    ) {
        // Sibling error wins over a plain engine abort, but only when the sibling

        // hasErrored before the engine-aborted branch). An engine abort
        // propagates into the sibling controller with its own reason, so it must
        // NOT be mistaken for a sibling cascade.
        if (siblingAbort != null && siblingAbort.isAborted()
                && MessageConstants.SIBLING_ERROR_REASON.equals(siblingAbort.getReason())) {
            return MessageConstants.siblingErrorMessage(tub.name());
        }
        return MessageConstants.abortMessage(engine.getAbortController().getReason());
    }

    /**
     * Groups consecutive concurrency-safe tool-use blocks into one batch.
     */
    private static List<List<ToolUseBlock>> partition(
        List<ContentBlock> blocks, ToolExecutor executor
    ) {
        List<List<ToolUseBlock>> batches = new ArrayList<>();
        List<ToolUseBlock> current = null;
        for (ContentBlock block : blocks) {
            if (!(block instanceof ToolUseBlock tub)) continue;

            boolean safe = tub.input() != null
                && executor.isConcurrencySafe(tub.name(), tub.input());
            if (safe) {
                if (current == null) {
                    current = new ArrayList<>();
                    batches.add(current);
                }
                current.add(tub);
            } else {
                current = null;
                List<ToolUseBlock> single = new ArrayList<>();
                single.add(tub);
                batches.add(single);
            }
        }
        return batches;
    }
}
