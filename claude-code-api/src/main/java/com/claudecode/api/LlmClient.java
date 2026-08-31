package com.claudecode.api;

import java.util.Iterator;
import java.util.List;

/**
 * Unified LLM client interface.
 * Supports multiple backends through the adapter pattern.
 */
public interface LlmClient {

    /**
     * Creates a streaming message, returning an iterator of stream events.
     */
    Iterator<StreamEvent> createMessageStream(CreateMessageRequest request);

    /**
     * Creates a streaming message and notifies the caller once the transport has accepted the
     * request for dispatch. The notification is deliberately earlier than response headers: side
     * requests such as first-turn session-title generation must be able to establish request order
     * without serializing the main model turn behind the helper response.
     *
     * <p>Transport implementations with an asynchronous dispatcher should override this method and
     * invoke {@code onRequestSubmitted} immediately after enqueueing the HTTP call. The fallback
     * preserves compatibility for in-memory/test providers by notifying immediately before their
     * existing synchronous implementation is entered.
     */
    default Iterator<StreamEvent> createMessageStream(
            CreateMessageRequest request,
            Runnable onRequestSubmitted) {
        if (onRequestSubmitted != null) onRequestSubmitted.run();
        return createMessageStream(request);
    }

    /**
     * Creates a non-streaming message (blocks until complete).
     */
    ApiMessage createMessage(CreateMessageRequest request);

    /**
     * Creates a non-streaming message with a per-request timeout, in
     * milliseconds. {@code timeoutMillis <= 0} means "use the client
     * default". Callers that need a bounded wall-clock (e.g. hook
     * evaluators, side-query classifiers) should route through this
     * overload — plain {@link #createMessage(CreateMessageRequest)} may
     * block for the adapter's default idle timeout, which is measured in
     * minutes for the streaming main loop.
     *
     * <p>Default implementation ignores the timeout so pre-existing
     * adapters keep compiling; concrete adapters should override.
     */
    default ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
        return createMessage(request);
    }

    /**
     * Count input tokens with Anthropic's Messages count-tokens endpoint.
     * Providers without that capability keep the default unsupported result;
     * callers match Claude Code's fallback/error isolation around it.
     */
    default long countTokens(
            String model,
            List<CreateMessageRequest.RequestMessage> messages,
            List<CreateMessageRequest.ToolDefinition> tools) {
        throw new UnsupportedOperationException("count_tokens is not supported by this provider");
    }

    /**
     * Claude Code's count-token fallback: issue a one-token non-streaming
     * Messages request and read its input usage when the count endpoint fails.
     */
    default long countTokensFallback(
            String model,
            List<CreateMessageRequest.RequestMessage> messages,
            List<CreateMessageRequest.ToolDefinition> tools,
            String sessionId) {
        throw new UnsupportedOperationException("token fallback is not supported by this provider");
    }

    /**
     * Returns the current model name.
     */
    String getModel();
}
