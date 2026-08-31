package com.claudecode.runtime.query;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.FallbackTriggeredError;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model fallback abandons everything the current {@code query} invocation has already streamed,
 * so the loop must withdraw those rows instead of leaving them stranded above the retried answer.
 */
class QueryLoopRetractionTest {

    /** Streams {@code events}, then throws {@code failure} instead of the next event. */
    private static Iterator<StreamingClient.StreamingEvent> failingAfter(
            List<StreamingClient.StreamingEvent> events, RuntimeException failure) {
        Iterator<StreamingClient.StreamingEvent> delegate = events.iterator();
        return new Iterator<>() {
            private boolean thrown;

            @Override
            public boolean hasNext() {
                return delegate.hasNext() || !thrown;
            }

            @Override
            public StreamingClient.StreamingEvent next() {
                if (delegate.hasNext()) return delegate.next();
                if (thrown) throw new NoSuchElementException();
                thrown = true;
                throw failure;
            }
        };
    }

    private static List<StreamingClient.StreamingEvent> completedTextBlock(String id, String text) {
        return List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                id, "primary-model", List.of(), new Usage(10, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", text),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0)
        );
    }

    private static List<StreamingClient.StreamingEvent> endTurn(String id, String text) {
        List<StreamingClient.StreamingEvent> events =
            new ArrayList<>(completedTextBlock(id, text));
        events.add(new StreamingClient.StreamingEvent.MessageDeltaEvent(
            "end_turn", new Usage(0, 5, 0, 0)));
        events.add(new StreamingClient.StreamingEvent.MessageStopEvent());
        return events;
    }

    private static QueryDeps deps(
            List<Supplier<Iterator<StreamingClient.StreamingEvent>>> attempts) {
        List<Supplier<Iterator<StreamingClient.StreamingEvent>>> remaining =
            new ArrayList<>(attempts);
        return new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                if (remaining.isEmpty()) throw new AssertionError("unexpected extra request");
                return remaining.removeFirst().get();
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public QueryDeps.AutoCompactResult autocompact(
                    List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions,
                    long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }

            @Override
            public String uuid() {
                return UUID.randomUUID().toString();
            }
        };
    }

    private static DefaultQuerySession session() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            // Required by the spec but never consulted: every request in this test
            // goes through the QueryDeps.callModel seam.
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    throw new AssertionError("requests must go through QueryDeps");
                }

                @Override
                public String getModel() {
                    return "primary-model";
                }
            })
            .systemPrompt("Be helpful")
            .model("primary-model")
            .build());
        engine.getConfig().setFallbackModel("fallback-model");
        return engine;
    }

    private static List<SDKMessage> run(DefaultQuerySession engine, QueryDeps deps) {
        List<Message> history = List.of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi there")));
        engine.getMutableMessages().addAll(history);
        QueryParams params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("primary-model")
            .querySource("user")
            .deps(deps)
            .build();
        List<SDKMessage> drained = new ArrayList<>();
        Iterator<SDKMessage> iter = new QueryLoop(engine, params);
        while (iter.hasNext()) drained.add(iter.next());
        return drained;
    }

    @Test
    void midStreamFallbackTombstonesEveryBlockItAlreadyStreamed() {
        DefaultQuerySession engine = session();
        List<SDKMessage> messages = run(engine, deps(List.of(
            () -> failingAfter(completedTextBlock("msg-1", "Half an answer"),
                new FallbackTriggeredError("primary-model", "fallback-model")),
            () -> endTurn("msg-2", "The retried answer").iterator())));

        List<String> retracted = messages.stream()
            .filter(SDKMessage.Tombstone.class::isInstance)
            .map(m -> ((SDKMessage.Tombstone) m).replacedUuid())
            .toList();
        String abandoned = messages.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(m -> ((SDKMessage.Assistant) m).message().uuid())
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected the partial assistant block"));

        assertEquals(List.of(abandoned), retracted,
            "the abandoned block is withdrawn exactly once: " + messages);
        assertTrue(messages.indexOf(messages.stream()
                .filter(SDKMessage.Tombstone.class::isInstance).findFirst().orElseThrow())
                > messages.indexOf(messages.stream()
                .filter(SDKMessage.Assistant.class::isInstance).findFirst().orElseThrow()),
            "the tombstone follows the row it retracts");
    }

    @Test
    void retractedBlocksAreDroppedFromTheHistoryTheRetryResends() {
        DefaultQuerySession engine = session();
        run(engine, deps(List.of(
            () -> failingAfter(completedTextBlock("msg-1", "Half an answer"),
                new FallbackTriggeredError("primary-model", "fallback-model")),
            () -> endTurn("msg-2", "The retried answer").iterator())));

        boolean stranded = engine.getMutableMessages().stream()
            .anyMatch(m -> m instanceof AssistantMessage assistant
                && assistant.message().content().stream()
                    .anyMatch(b -> Strings.CS.contains(b.toString(), "Half an answer")));
        assertFalse(stranded, "the abandoned block must not be resent: "
            + engine.getMutableMessages());
    }

    @Test
    void tombstonesDeleteTheSameRowsFromThePersistedTranscriptPort() {
        DefaultQuerySession engine = session();
        List<String> removed = new ArrayList<>();
        engine.setTranscriptSink(new TranscriptSink() {
            @Override public void record(String sessionId, Message message) {}
            @Override public void remove(String sessionId, String messageUuid) {
                removed.add(messageUuid);
            }
        });

        List<SDKMessage> messages = run(engine, deps(List.of(
            () -> failingAfter(completedTextBlock("msg-1", "Half an answer"),
                new FallbackTriggeredError("primary-model", "fallback-model")),
            () -> endTurn("msg-2", "The retried answer").iterator())));

        assertEquals(messages.stream()
            .filter(SDKMessage.Tombstone.class::isInstance)
            .map(message -> ((SDKMessage.Tombstone) message).replacedUuid())
            .toList(), removed);
    }

    @Test
    void aFallbackRaisedBeforeTheRequestEvenOpenedRetractsNothing() {
        DefaultQuerySession engine = session();
        List<SDKMessage> messages = run(engine, deps(List.of(
            () -> {
                throw new FallbackTriggeredError("primary-model", "fallback-model");
            },
            () -> endTurn("msg-2", "The retried answer").iterator())));

        assertTrue(messages.stream().noneMatch(SDKMessage.Tombstone.class::isInstance),
            "nothing was rendered yet, so nothing may be withdrawn: " + messages);
    }
}
