package com.claudecode.services.agent;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Test double for {@link LlmClient}: returns a configurable text response (or
 * {@code null}) for every non-streaming call, recording each prompt for
 * assertions. matches the insights module's {@code RecordingLlmClient}.
 */
final class StubLlmClient implements LlmClient {

    /** Configured response text; {@code null} simulates an API failure/abort. */
    volatile String response;
    final List<String> prompts = new ArrayList<>();

    StubLlmClient(String response) {
        this.response = response;
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
        throw new UnsupportedOperationException("streaming not used by summary services");
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request) {
        String prompt = (String) request.messages().getFirst().content();
        prompts.add(prompt);
        if (response == null) return null;
        return ApiMessage.stub(request.model(), response);
    }

    @Override
    public String getModel() {
        return "stub-model";
    }
}
