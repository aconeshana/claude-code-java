package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.commons.lang3.Strings;
import com.claudecode.api.ApiException;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.CustomModelRoutingClient;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CredentialGuardedLlmClientTest {

    @Test
    void unavailableFallbackFailsBeforeDispatchCallbackOrDelegate() {
        var calls = new AtomicInteger();
        var submitted = new AtomicInteger();
        LlmClient guarded = new CredentialGuardedLlmClient(new RecordingClient(calls), false);

        ApiException failure = assertThrows(ApiException.class,
            () -> guarded.createMessageStream(request("sonnet"), submitted::incrementAndGet));

        assertEquals(401, failure.statusCode());
        assertEquals("authentication_error", failure.errorType());
        assertEquals(0, calls.get());
        assertEquals(0, submitted.get());
    }

    @Test
    void customRouteBypassesUnavailableFallback() {
        var fallbackCalls = new AtomicInteger();
        var customCalls = new AtomicInteger();
        var config = new CustomModelConfig("gateway-main", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of());
        LlmClient routing = new CustomModelRoutingClient(
            new CredentialGuardedLlmClient(new RecordingClient(fallbackCalls), false),
            model -> Strings.CS.equals("gateway-main", model)
                ? Optional.of(config) : Optional.empty(),
            _ -> new RecordingClient(customCalls));

        routing.createMessage(request("gateway-main"));

        assertEquals(0, fallbackCalls.get());
        assertEquals(1, customCalls.get());
    }

    private static CreateMessageRequest request(String model) {
        return CreateMessageRequest.builder().model(model).maxTokens(1).build();
    }

    private record RecordingClient(AtomicInteger calls) implements LlmClient {
        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            calls.incrementAndGet();
            return Collections.emptyIterator();
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            calls.incrementAndGet();
            return null;
        }

        @Override
        public String getModel() {
            return "sonnet";
        }
    }
}
