package com.claudecode.runtime.query;


import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.FallbackTriggeredError;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.Usage;

import java.util.*;

/**
 * Production implementation of {@link QueryDeps}.
 */
class CallModelAdapter implements QueryDeps {

    private final StreamingClient client;
    private final MessageCompactor compactor;

    public CallModelAdapter(StreamingClient client, MessageCompactor compactor) {
        this.client = client;
        this.compactor = compactor;
    }

    @Override
    public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
        Iterator<StreamingClient.StreamingEvent> base = client.createStream(request);
        return new FallbackAwareIterator(base, request);
    }

    @Override
    public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
        if (compactor == null) return new MessageCompactor.MicrocompactResult(messages);
        return compactor.microcompactMessages(messages);
    }

    @Override
    public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
        if (compactor == null) return false;
        return compactor.shouldAutoCompact(messages, model, querySource);
    }

    @Override
    public AutoCompactResult autocompact(List<Message> messages, String model,
                                         String querySource, AutoCompactTrackingState tracking,
                                         String customInstructions, long snipTokensFreed) {
        if (compactor == null) return new AutoCompactResult(null, null);
        if (!compactor.shouldAutoCompact(messages, model, querySource, snipTokensFreed)) {
            return new AutoCompactResult(null, null);
        }
        try {
            // isAutoCompact=true; customInstructions comes from the PreCompact hook
// (matches production runAutoCompact passing mergeHookInstructions(null, hookText)).
            MessageCompactor.CompactionResult result =
                compactor.compactConversation(messages, true, customInstructions, model);
            return new AutoCompactResult(result, 0, null);
        } catch (Exception e) {
            // Circuit breaker: propagate the incremented failure count so the loop
            // can stop retrying once it reaches
            // AutoCompactTrackingState.MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES.
            Usage failureUsage = e instanceof MessageCompactor.UsageBearingFailure usageFailure
                ? usageFailure.compactionUsage() : Usage.EMPTY;
            return new AutoCompactResult(
                null, tracking.consecutiveFailures() + 1, compactFailureCode(e), failureUsage);
        }
    }

    @Override
    public AutoCompactResult reactiveCompact(List<Message> messages, String model,
                                              String querySource,
                                              AutoCompactTrackingState tracking,
                                              String customInstructions) {
        if (compactor == null
                || Strings.CS.equals("compact", querySource)
                || Strings.CS.equals("session_memory", querySource)) {
            return new AutoCompactResult(null, null);
        }
        try {
            MessageCompactor.CompactionResult result =
                compactor.compactConversation(messages, true, customInstructions, model);
            return new AutoCompactResult(result, 0, null);
        } catch (Exception e) {
            Usage failureUsage = e instanceof MessageCompactor.UsageBearingFailure usageFailure
                ? usageFailure.compactionUsage() : Usage.EMPTY;
            return new AutoCompactResult(
                null, tracking.consecutiveFailures() + 1, compactFailureCode(e), failureUsage);
        }
    }

    private static String compactFailureCode(Exception error) {
        String detail = error.getMessage();
        String message = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(message, "not enough api-round groups")
                || Strings.CS.contains(message, "not enough completed api rounds")
                || Strings.CS.contains(message, "not enough messages")
                || Strings.CS.contains(message, "fewer than 2 groups")) {
            return "too_few_groups";
        }
        if (Strings.CS.contains(message, "abort") || Strings.CS.contains(message, "cancel")) return "aborted";
        if (Strings.CS.contains(message, "exhaust")) return "exhausted";
        if (Strings.CS.contains(message, "media")) return "media_unstrippable";

        // detail for reason=error; compact_error is an open string, not an enum.
        return StringUtils.isBlank(detail) ? "error" : detail;
    }

    @Override
    public String uuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Wraps a base stream iterator so a {@link FallbackTriggeredError} raised
     * mid-stream fires {@code request.onStreamingFallback} before it continues
     * up to the loop, which withdraws the abandoned rows and retries
     * {@code callModel} on the fallback model. The error is rethrown as itself:
     * the loop's fallback handling keys on this exact type.
     */
    private static class FallbackAwareIterator implements Iterator<StreamingClient.StreamingEvent> {

        private final Iterator<StreamingClient.StreamingEvent> base;
        private final StreamingClient.StreamRequest request;
        private StreamingClient.StreamingEvent buffered;
        private boolean exhausted = false;

        FallbackAwareIterator(Iterator<StreamingClient.StreamingEvent> base,
                              StreamingClient.StreamRequest request) {
            this.base = base;
            this.request = request;
        }

        @Override
        public boolean hasNext() {
            if (exhausted) return false;
            if (buffered != null) return true;
            try {
                if (!base.hasNext()) {
                    exhausted = true;
                    return false;
                }
                buffered = base.next();
                return true;
            } catch (FallbackTriggeredError err) {
                if (request.onStreamingFallback() != null) {
                    request.onStreamingFallback().run();
                }
                exhausted = true;
                throw err;
            }
        }

        @Override
        public StreamingClient.StreamingEvent next() {
            if (!hasNext()) throw new NoSuchElementException();
            StreamingClient.StreamingEvent result = buffered;
            buffered = null;
            return result;
        }
    }
}
