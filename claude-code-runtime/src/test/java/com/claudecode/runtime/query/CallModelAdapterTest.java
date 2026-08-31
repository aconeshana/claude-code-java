package com.claudecode.runtime.query;

import com.claudecode.core.engine.FallbackTriggeredError;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.StreamingClient;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallModelAdapterTest {

    @Test
    void reactiveCompactBypassesLocalThresholdAfterProviderRejectsRequest() {
        MessageCompactor.CompactionResult compacted = new MessageCompactor.CompactionResult(
            new SystemMessage(
                "boundary", "compact_boundary", "info", ""),
            List.of(), List.of(), List.of(), List.of(), 10L);
        MessageCompactor compactor = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages,
                                                        boolean isAutoCompact) {
                return compacted;
            }
        };
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of().iterator();
            }

            @Override
            public String getModel() {
                return "test-model";
            }
        };

        QueryDeps.AutoCompactResult result = new CallModelAdapter(client, compactor)
            .reactiveCompact(List.of(), "test-model", "user",
                AutoCompactTrackingState.initial(), null);

        assertSame(compacted, result.compactionResult());
        assertEquals(0, result.consecutiveFailures());
    }

    private static final class UsageFailure extends RuntimeException
            implements MessageCompactor.UsageBearingFailure {
        private final Usage usage;

        private UsageFailure(String message, Usage usage) {
            super(message);
            this.usage = usage;
        }

        @Override
        public Usage compactionUsage() {
            return usage;
        }
    }

    @Test
    void mapsReactiveTooFewGroupsFailureToReleased197Code() {
        MessageCompactor compactor = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return true;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages,
                                                        boolean isAutoCompact) {
                throw new IllegalStateException(
                    "Not enough completed API rounds for reactive compact");
            }
        };
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of().iterator();
            }

            @Override
            public String getModel() {
                return "claude-sonnet-4-6";
            }
        };
        CallModelAdapter adapter = new CallModelAdapter(client, compactor);

        QueryDeps.AutoCompactResult result = adapter.autocompact(
            List.of(), "claude-sonnet-4-6", "sdk",
            AutoCompactTrackingState.initial(), null, 0);

        assertNull(result.compactionResult());
        assertEquals(1, result.consecutiveFailures());
        assertEquals("too_few_groups", result.compactError());
    }

    @Test
    void preservesReleased197ReactiveErrorDetailInsteadOfCollapsingItToError() {
        MessageCompactor compactor = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return true;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages,
                                                        boolean isAutoCompact) {
                throw new UsageFailure(
                    "summarization produced empty response",
                    new Usage(1, 0, 0, 0));
            }
        };
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of().iterator();
            }

            @Override
            public String getModel() {
                return "claude-sonnet-4-6";
            }
        };
        CallModelAdapter adapter = new CallModelAdapter(client, compactor);

        QueryDeps.AutoCompactResult result = adapter.autocompact(
            List.of(), "claude-sonnet-4-6", "sdk",
            AutoCompactTrackingState.initial(), null, 0);

        assertNull(result.compactionResult());
        assertEquals(1, result.consecutiveFailures());
        assertEquals("summarization produced empty response", result.compactError());
        assertEquals(new Usage(1, 0, 0, 0),
            result.compactionUsage());
    }

    /**
     * The loop's fallback handling keys on {@link FallbackTriggeredError}, so the
     * adapter has to hand the error through unchanged. Rewrapping it into a type
     * the loop does not catch would let a mid-stream fallback escape as an
     * ordinary query failure instead of retrying on the fallback model.
     */
    @Test
    void aMidStreamFallbackReachesTheLoopAsTheErrorItCatches() {
        boolean[] notified = {false};
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        throw new FallbackTriggeredError("primary-model", "fallback-model");
                    }

                    @Override
                    public StreamingEvent next() {
                        throw new NoSuchElementException();
                    }
                };
            }

            @Override
            public String getModel() {
                return "primary-model";
            }
        };
        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            "primary-model", 1024, "Be helpful", List.of(), true, List.of(), null, null,
            "fallback-model", null, null, null, () -> notified[0] = true, true, null, null);

        Iterator<StreamingClient.StreamingEvent> stream =
            new CallModelAdapter(client, null).callModel(request);

        FallbackTriggeredError raised =
            assertThrows(FallbackTriggeredError.class, stream::hasNext);
        assertEquals("fallback-model", raised.fallbackModel());
        assertTrue(notified[0], "the streaming-fallback callback still fires");
    }
}
