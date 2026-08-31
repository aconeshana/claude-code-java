package com.claudecode.cli;

import com.claudecode.api.ApiException;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.annotation.Explanation;
import java.util.Iterator;
import java.util.List;

/**
 * Prevents an unusable default Anthropic route from reaching the transport.
 */
@Explanation("Java has no subscriber OAuth login, so unusable default routes fail locally")
final class CredentialGuardedLlmClient implements LlmClient {

    static final String ERROR_MESSAGE = "No usable model route is configured. Set ANTHROPIC_API_KEY, "
        + "configure ANTHROPIC_BASE_URL with credentials, or add a custom model.";

    private final LlmClient delegate;
    private final boolean usable;

    CredentialGuardedLlmClient(LlmClient delegate, boolean usable) {
        this.delegate = delegate;
        this.usable = usable;
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
        requireUsable();
        return delegate.createMessageStream(request);
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(
            CreateMessageRequest request, Runnable onRequestSubmitted) {
        requireUsable();
        return delegate.createMessageStream(request, onRequestSubmitted);
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request) {
        requireUsable();
        return delegate.createMessage(request);
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
        requireUsable();
        return delegate.createMessage(request, timeoutMillis);
    }

    @Override
    public long countTokens(String model, List<CreateMessageRequest.RequestMessage> messages,
                            List<CreateMessageRequest.ToolDefinition> tools) {
        requireUsable();
        return delegate.countTokens(model, messages, tools);
    }

    @Override
    public long countTokensFallback(String model,
                                    List<CreateMessageRequest.RequestMessage> messages,
                                    List<CreateMessageRequest.ToolDefinition> tools,
                                    String sessionId) {
        requireUsable();
        return delegate.countTokensFallback(model, messages, tools, sessionId);
    }

    @Override
    public String getModel() {
        return delegate.getModel();
    }

    private void requireUsable() {
        if (!usable) throw new ApiException(ERROR_MESSAGE, 401, "authentication_error");
    }
}
