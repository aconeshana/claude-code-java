package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.tools.tasks.TaskNotificationBridge;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * (agentId routing) end-to-end test: a sub-agent's background bash completion
 * notification, bridged from the task store into the SHARED session queue, is
 * finally delivered to the owning sub-agent's engine loop (and never to the
 * coordinator) — even though it is {@code LATER} priority (Option 1 / Route A).
 *
 * <p>Wires the same pieces production does: a shared {@link MessageQueueManager},
 * {@link TaskRegistry#global} (with a sub-owned task), and a
 * {@link TaskNotificationBridge} pointed at that shared queue.
 */
class AgentRoutingIntegrationTest {

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

    @AfterEach
    void resetGlobal() {
        TaskRegistry.resetGlobalForTest();
    }

    @Test
    void subAgentBackgroundBashCompletion_routesToSubAgentNotCoordinator() {
        MessageQueueManager queue = new MessageQueueManager();

        // Global task registry holds a sub-agent-owned background task.
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskRegistry.setGlobalForTest(registry);

        // Bridge terminal transitions into the SHARED session queue.
        new TaskNotificationBridge(queue).register();

        TaskState task = registry.store().create(TaskType.LOCAL_BASH, "sleep 1", "sub-1");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        registry.store().updateStatus(task.id(), TaskStatus.COMPLETED); // fires completion

        // The bridge must have enqueued a LATER task-notification tagged with the
        // sub-agent id (so only that agent's loop drains it).
        assertEquals(1, queue.size(), "bridge enqueued exactly one notification");
        QueuedCommand notification = queue.peek(_ -> true);
        assertEquals("task-notification", notification.mode());
        assertEquals("sub-1", notification.agentId());
        assertNotNull(notification.priority(), "notification carries a priority");

        // The sub-agent engine drains its own LATER notification; the coordinator
        // (agentId == null) does NOT pull it into the main session.
        DefaultQuerySession subEngine = new TestEngine("sub-1", queue);
        DefaultQuerySession mainEngine = new TestEngine(null, queue);

        List<String> subEmitted = new ArrayList<>();
        Consumer<SDKMessage> subEmit = m -> {
            if (m instanceof SDKMessage.User u) subEmitted.add(u.message().message().text());
        };
        subEngine.conversation().drainQueuedCommands(subEmit);

        assertEquals(1, subEmitted.size(), "sub-agent loop delivered its completion notification");
        assertTrue(Strings.CS.contains(subEmitted.getFirst(), "sub-1") || Strings.CS.contains(subEmitted.getFirst(), "task_notification"),
            "delivered payload is the task notification XML: " + subEmitted.getFirst());
        assertEquals(0, queue.size(), "notification consumed by the sub-agent loop");

// Coordinator has nothing left to drain — the notification never compatibility baselineto main.
        List<String> mainEmitted = new ArrayList<>();
        Consumer<SDKMessage> mainEmit = m -> {
            if (m instanceof SDKMessage.User u) mainEmitted.add(u.message().message().text());
        };
        mainEngine.conversation().drainQueuedCommands(mainEmit);
        assertTrue(mainEmitted.isEmpty(), "coordinator must not receive the sub-agent's notification");
    }
}
