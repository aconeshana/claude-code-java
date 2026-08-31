package com.claudecode.services.compact;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.ApiMessageTiming;
import com.claudecode.api.ApiException;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.core.engine.ApiMessageFormatter;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.ApiErrorMessages;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link CompactSummarizer} backed by a real model call.
 */
public final class LlmCompactSummarizer implements CompactSummarizer {


    private static final String SYSTEM_PROMPT =
        "You are a helpful AI assistant tasked with summarizing conversations.";

    private final LlmClient llmClient;
    private final Supplier<String> modelSupplier;
    private final StreamingClient streamingClient;
    private final Supplier<QuerySession> engineSupplier;

    /**
     * @param llmClient     client used to run the summarization call
     * @param modelSupplier resolves the model to use at call time (not a
     *                      fixed string) so a mid-session {@code /model}
     *                      switch is reflected on the next {@code /compact} —
     *                      pass {@code config::model} where {@code config}
     *                      is the engine's (mutable) {@code QuerySessionSpec}
     */
    public LlmCompactSummarizer(LlmClient llmClient, Supplier<String> modelSupplier) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier");
        this.streamingClient = null;
        this.engineSupplier = null;
    }

    /**
     * Creates the cache-sharing compact fork used by the main
     * interactive engine. The supplier is late-bound because the compact
     * service is constructed immediately before its owning engine.
     */
    public LlmCompactSummarizer(StreamingClient streamingClient,
                                Supplier<QuerySession> engineSupplier) {
        this.llmClient = null;
        this.modelSupplier = null;
        this.streamingClient = Objects.requireNonNull(streamingClient, "streamingClient");
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
    }

    @Override
    public String summarize(List<Message> messages, String compactPrompt) {
        return summarizeWithUsage(messages, compactPrompt).text();
    }

    @Override
    public void prepareManualCompact() {
        QuerySession engine = engineSupplier != null ? engineSupplier.get() : null;
        if (engine == null) return;
        var abortController = engine.execution().getAbortController();
        if (abortController.isAborted()) abortController.reset();
    }

    @Override
    public SummaryResult summarizeWithUsage(List<Message> messages, String compactPrompt) {
        if (streamingClient != null) {
            return summarizeWithCacheSharingFork(messages, compactPrompt);
        }

        List<CreateMessageRequest.RequestMessage> apiMessages = new ArrayList<>();
        for (StreamingClient.StreamRequest.RequestMessage m : ApiMessageFormatter.toRequestMessages(messages)) {
            apiMessages.add(new CreateMessageRequest.RequestMessage(m.role(), m.content()));
        }
        apiMessages.add(new CreateMessageRequest.RequestMessage("user", compactPrompt));

        Supplier<String> legacyModelSupplier = Objects.requireNonNull(
            modelSupplier, "legacy compact model supplier");
        LlmClient legacyClient = Objects.requireNonNull(llmClient, "legacy compact client");
        CreateMessageRequest request = CreateMessageRequest.builder()
            .model(Objects.requireNonNull(legacyModelSupplier.get(), "compact model"))
            .maxTokens((int) AutoCompactStrategy.SYSTEM_PROMPT_RESERVE)
            .systemPrompt(SYSTEM_PROMPT)
            .messages(apiMessages)
            .stream(false)
            .querySource("compact")
            .build();

        ApiMessage response;
        long startedAt = System.currentTimeMillis();
        try {
            response = legacyClient.createMessage(request);
        } catch (RuntimeException e) {
            // Prompt-too-long → PTL marker text so the caller's head-truncation

            // synthetic assistant message prefixed 'Prompt is too long' and
            // streamCompactSummary retries on that prefix — Java's Anthropic
            // client throws instead, so translate here; anything else
            // propagates as a normal compaction failure.
            return translatePromptTooLong(e);
        }
        String text = extractText(response);
        Usage usage = response != null && response.usage() != null
            ? response.usage() : Usage.EMPTY;
        long completedAt = System.currentTimeMillis();
        SessionCostState.get().recordApiRequest(
            response != null && response.model() != null ? response.model() : request.model(),
            usage, completedAt - startedAt,
            completedAt - ApiMessageTiming.lastAttemptStartMs(response, startedAt));
        return new SummaryResult(text, usage);
    }

    private SummaryResult summarizeWithCacheSharingFork(List<Message> messages, String compactPrompt) {
        QuerySession engine = engineSupplier != null ? engineSupplier.get() : null;
        if (engine == null) {
            throw new IllegalStateException("Compact cache-sharing fork requires a live QuerySession");
        }
        StreamingClient.StreamRequest request =
            engine.forks().buildCacheSharingRequest(messages, compactPrompt);

        try {
            StreamingClient client = Objects.requireNonNull(
                streamingClient, "cache-sharing compact client");
            long startedAt = System.currentTimeMillis();
            Iterator<StreamingClient.StreamingEvent> stream = client.createStream(request);
            SummaryResult result = consumeTextStream(stream);
            if (result.text() == null
                    || !Strings.CS.startsWith(result.text(), CompactService.PROMPT_TOO_LONG_MARKER)) {
                long completedAt = System.currentTimeMillis();
                long finalAttemptStartMs = stream instanceof StreamingClient.TimedStreamingIterator timed
                    && timed.lastAttemptStartMs() > 0L
                        ? timed.lastAttemptStartMs() : startedAt;
                SessionCostState.get().recordApiRequest(
                    request.model(), result.usage(), completedAt - startedAt,
                    completedAt - finalAttemptStartMs);
            }
            return result;
        } catch (RuntimeException e) {
            return translatePromptTooLong(e);
        }
    }

    private static SummaryResult consumeTextStream(Iterator<StreamingClient.StreamingEvent> stream) {
        StringBuilder text = new StringBuilder();
        Usage usage = Usage.EMPTY;
        boolean sawContentBlock = false;
        while (stream.hasNext()) {
            StreamingClient.StreamingEvent event = stream.next();
            switch (event) {
                case StreamingClient.StreamingEvent.MessageStartEvent start -> {
                    // Anthropic stream usage fields are cumulative snapshots,
                    // not deltas. In particular message_delta repeats the
                    // final output token count; adding start + delta would
                    // double-count the compact-summary request in billing.
                    if (start.usage() != null) usage = usage.updateCumulative(start.usage());
                    if (start.content() != null) {
                        if (!start.content().isEmpty()) sawContentBlock = true;
                        for (ContentBlock block : start.content()) {
                            if (block instanceof TextBlock(String text1) && text1 != null) {
                                text.append(text1);
                            }
                        }
                    }
                }
                case StreamingClient.StreamingEvent.ContentBlockStartEvent _ ->
                    sawContentBlock = true;
                case StreamingClient.StreamingEvent.ContentBlockDeltaEvent delta -> {
                    if (Strings.CS.equals("text_delta", delta.deltaType()) && delta.deltaText() != null) {
                        text.append(delta.deltaText());
                    }
                }
                case StreamingClient.StreamingEvent.MessageDeltaEvent delta -> {
                    if (delta.usage() != null) usage = usage.updateCumulative(delta.usage());
                }
                case StreamingClient.StreamingEvent.ErrorEvent error -> {
                    RuntimeException failure = error.exception() instanceof RuntimeException re
                        ? re : new RuntimeException(error.exception());
                    return translatePromptTooLong(failure);
                }
                default -> { /* block boundaries / stop carry no summary payload */ }
            }
        }
        if (!sawContentBlock) {
            throw new CompactException(
                "no assistant message in summarization response", usage);
        }
        return new SummaryResult(text.isEmpty() ? null : text.toString(), usage);
    }

    private static SummaryResult translatePromptTooLong(RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null && ApiErrorMessages.classify(msg) == ApiErrorMessages.TooLargeKind.PROMPT_TOO_LONG) {
            return new SummaryResult(CompactService.PROMPT_TOO_LONG_MARKER + ": " + msg, Usage.EMPTY);
        }
        if (e instanceof ApiException apiException) {
            if (ReleasedMediaRetry.classify(apiException) != null) throw apiException;
            throw releasedApiError(apiException);
        }
        throw e;
    }

    private static ApiException releasedApiError(ApiException failure) {
        String message = failure.getMessage();
        if (message != null && Strings.CS.startsWith(message, "API Error:")) {
            return failure;
        }
        String providerMessage = nestedProviderMessage(message);
        if (StringUtils.isBlank(providerMessage)) {
            providerMessage = message == null ? "Unknown API error" : message;
            if (Strings.CS.startsWith(providerMessage, "API request failed: ")) {
                providerMessage = providerMessage.substring("API request failed: ".length());
            }
        }
        String status = failure.statusCode() > 0 ? failure.statusCode() + " " : "";
        return new ApiException(
            "API Error: " + status + providerMessage,
            failure.statusCode(),
            failure.errorType(),
            failure.retryAfterSeconds());
    }

    private static String nestedProviderMessage(String rawMessage) {
        if (rawMessage == null) return null;
        int jsonStart = rawMessage.indexOf('{');
        if (jsonStart < 0) return null;
        try {
            JsonNode root = JsonUtils.getMapper().readTree(rawMessage.substring(jsonStart));
            JsonNode nested = root.path("error").path("message");
            if (nested.isTextual() && !StringUtils.isBlank(nested.asText())) return nested.asText();
            JsonNode topLevel = root.path("message");
            if (topLevel.isTextual() && !StringUtils.isBlank(topLevel.asText())) return topLevel.asText();
        } catch (Exception _) {
// Provider/proxy returned a non-JSON body; preserve icompatibility baseline text below.
        }
        return null;
    }

    private static String extractText(ApiMessage response) {
        if (response == null || response.content() == null) return null;
        AssistantMessage adapted = new AssistantMessage(
            null, AssistantContent.of(response.content()));
        return MessageConstants.getAssistantMessageText(adapted);
    }
}
