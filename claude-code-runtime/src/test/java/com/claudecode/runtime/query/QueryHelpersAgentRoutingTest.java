package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AttachmentRenderer;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * (agentId routing) integration tests for {@link QueryHelpers#drainQueuedCommands}.
 */
class QueryHelpersAgentRoutingTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override public String getModel() { return "test-model"; }
    };

    /** A real DefaultQuerySession carrying a given agentId + shared queue. */
    private static final class TestEngine extends DefaultQuerySession {
        TestEngine(String agentId, MessageQueueManager queue) {
            super(QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .agentId(agentId)
                .messageQueue(queue)
                .build());
        }
    }

    private static QueuedCommand mainPrompt() {
        return new QueuedCommand("hello from main", null, "prompt", QueuePriority.NEXT,
            false, null, false, false, null, null, null);
    }

    private static QueuedCommand subNotification(String agentId, QueuePriority priority) {
        return new QueuedCommand("done:" + agentId, null, "task-notification", priority,
            true, null, false, false, null, null, agentId);
    }

    private static Consumer<SDKMessage> collecting(List<String> emitted) {
        return m -> {
            if (m instanceof SDKMessage.User u) emitted.add(u.message().message().text());
        };
    }

    @Test
    void sharedQueue_subAgentDrainsOnlyItsOwnTaskNotification_includingLater() {
        MessageQueueManager queue = new MessageQueueManager();
        DefaultQuerySession main = new TestEngine(null, queue);
        DefaultQuerySession sub = new TestEngine("sub-1", queue);

        // Mixed backlog on the shared queue:
        queue.enqueue(subNotification("sub-1", QueuePriority.LATER)); // sub-1's, LATER priority
        queue.enqueue(mainPrompt());                                   // coordinator's, NEXT
        queue.enqueue(subNotification("sub-2", QueuePriority.NEXT));   // a *different* sub-agent's

        List<String> emitted = new ArrayList<>();
        QueryHelpers.drainQueuedCommands(sub, collecting(emitted));

        // Sub-agent took ONLY its own task-notification — and it was LATER
        // priority, proving Option 1 removed the priority ceiling. The main
        // prompt (agentId null) and sub-2's notification stay on the queue.
        assertEquals(List.of(AttachmentRenderer.wrapQueuedCommandText(
            "done:sub-1", "task-notification", null)), emitted);
        assertEquals(2, queue.size());
        assertTrue(queue.peek(c -> Strings.CS.equals("prompt", c.mode()) && c.agentId() == null) != null,
            "coordinator prompt must remain for the main engine");
        assertTrue(queue.peek(c -> Strings.CS.equals("sub-2", c.agentId())) != null,
            "another sub-agent's notification must remain for that sub-agent");

        // The coordinator then takes only its own prompt (not sub-2's), leaving
        // the sub-2 notification on the shared queue for that sub-agent's loop.
        List<String> mainEmitted = new ArrayList<>();
        QueryHelpers.drainQueuedCommands(main, collecting(mainEmitted));
        assertEquals(List.of(AttachmentRenderer.wrapQueuedCommandText(
            "hello from main", "prompt", null)), mainEmitted);
        assertEquals(1, queue.size());
        assertTrue(queue.peek(c -> Strings.CS.equals("sub-2", c.agentId())) != null,
            "sub-2's notification is left for the sub-2 engine");
    }

    @Test
    void sharedQueue_mainDrainsOnlyCoordinatorCommands_notSubAgentNotifications() {
        MessageQueueManager queue = new MessageQueueManager();
        DefaultQuerySession main = new TestEngine(null, queue);
        @SuppressWarnings("unused") DefaultQuerySession sub = new TestEngine("sub-1", queue);

        queue.enqueue(subNotification("sub-1", QueuePriority.LATER)); // addressed elsewhere
        queue.enqueue(mainPrompt());                                   // coordinator's

        List<String> emitted = new ArrayList<>();
        QueryHelpers.drainQueuedCommands(main, collecting(emitted));

        // Coordinator took only its own NEXT prompt; the sub-1 LATER notification
        // was NOT pulled into the main session (would otherwise corrupt routing).
        assertEquals(List.of(AttachmentRenderer.wrapQueuedCommandText(
            "hello from main", "prompt", null)), emitted);
        List<QueuedCommand> remaining = queue.dequeueAllMatching(_ -> true);
        assertEquals(1, remaining.size());
        assertEquals("sub-1", remaining.getFirst().agentId());
    }
}
