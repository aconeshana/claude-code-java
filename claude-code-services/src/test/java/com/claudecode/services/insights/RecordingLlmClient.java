package com.claudecode.services.insights;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

/**
 * Test double: answers each non-streaming call by routing the user prompt
 * through a responder function, recording every request for assertions.
 */
final class RecordingLlmClient implements LlmClient {

    final List<CreateMessageRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Function<String, String> responder;

    RecordingLlmClient(Function<String, String> responder) {
        this.responder = responder;
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
        throw new UnsupportedOperationException("streaming not used by insights");
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request) {
        requests.add(request);
        String prompt = (String) request.messages().getFirst().content();
        return ApiMessage.stub(request.model(), responder.apply(prompt));
    }

    @Override
    public String getModel() {
        return "fake-model";
    }

    /** Prompts of all recorded requests, in call order. */
    List<String> prompts() {
        List<String> result = new ArrayList<>();
        for (CreateMessageRequest request : requests) {
            result.add((String) request.messages().getFirst().content());
        }
        return result;
    }
}
