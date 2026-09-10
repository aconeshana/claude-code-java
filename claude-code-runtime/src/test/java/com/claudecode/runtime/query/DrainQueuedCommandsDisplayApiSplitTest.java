package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.AttachmentRenderer;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for a real-world bug: a mid-turn queued prompt drained by
 * {@link QueryHelpers#drainQueuedCommands} showed the API-only "IMPORTANT: after
 * completing your current task..." preamble in the transcript, instead of the
 * raw text the user typed. The official {@code query.ts} keeps these two
 * streams separate — the raw attachment is what's stored/displayed, and
 * {@code wrapCommandText}'s wrapping is applied only once, at wire-serialization
 * time. See feedback-echo-vs-api-content-two-streams.md.
 */
class DrainQueuedCommandsDisplayApiSplitTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override public String getModel() { return "test-model"; }
    };

    @Test
    void promptDrain_emitsRawTextButRecordsWrappedTextForTheApi() {
        MessageQueueManager queue = new MessageQueueManager();
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .messageQueue(queue)
            .build());
        queue.enqueue(QueuedCommand.prompt("typed while busy"));

        List<String> emitted = new ArrayList<>();
        QueryHelpers.drainQueuedCommands(engine, m -> {
            if (m instanceof SDKMessage.User u) emitted.add(u.message().message().text());
        });

        assertEquals(List.of("typed while busy"), emitted,
            "the UI/transcript stream must show only what the user actually typed");

        List<Message> history = engine.getMutableMessages();
        UserMessage lastApiMessage = (UserMessage) history.get(history.size() - 1);
        assertEquals(
            AttachmentRenderer.wrapQueuedCommandText("typed while busy", "prompt", null),
            lastApiMessage.message().text(),
            "the API-bound conversation history must still carry the urgency wrapper");
    }
}
