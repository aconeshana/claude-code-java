package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelNames;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for a real-world bug: {@code /compact} on a live session
 * whose model was a bare alias (e.g. {@code "sonnet"} via {@code /model sonnet})
 * sent that literal alias on the wire and the API rejected it with 404. The
 * normal query-loop turn resolves aliases via
 * {@link QueryHelpers#resolveRuntimeModel}, but the cache-sharing compact fork
 * built its request directly from {@code config.model()}, bypassing that
 * resolution — see {@link DefaultQuerySession#buildCacheSharingRequest}.
 */
class CompactCacheSharingModelResolutionTest {

    /** Never invoked directly by this test — only needed to satisfy the builder. */
    private record NoopClient() implements StreamingClient {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return List.<StreamingEvent>of().iterator();
        }
        @Override
        public String getModel() { return "noop"; }
    }

    @Test
    void compactForkResolvesBareAliasToConcreteId() {
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(new NoopClient())
            .model("sonnet")
            .maxTurns(3)
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);

        List<Message> messages = List.of(
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));
        StreamingClient.StreamRequest request =
            engine.forks().buildCacheSharingRequest(messages, "Summarize this conversation.");

        assertEquals(ModelNames.defaultMainLoopModel(), request.model(),
            "compact cache-sharing fork must resolve the bare alias, not send it verbatim");
    }

    @Test
    void compactForkLeavesConcreteIdUntouched() {
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(new NoopClient())
            .model("claude-opus-4-8")
            .maxTurns(3)
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);

        List<Message> messages = List.of(
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));
        StreamingClient.StreamRequest request =
            engine.forks().buildCacheSharingRequest(messages, "Summarize this conversation.");

        assertEquals("claude-opus-4-8", request.model());
    }
}
