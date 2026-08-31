package com.claudecode.core.queue;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.SDKMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageQueueManagerTest {


    @Test
    void enqueuePendingNotification_nullPriorityDefaultsToLater() {
        MessageQueueManager queue = new MessageQueueManager();
        queue.enqueuePendingNotification(new QueuedCommand(
            "pending", null, "task-notification", null,
            false, null, false, false, null, null, null));

        assertEquals(1, queue.size());
        assertEquals(QueuePriority.LATER, queue.peek().priority(),
            "unset priority must default to LATER for pending notifications");
    }


    @Test
    void enqueue_nullPriorityDefaultsToNext() {
        MessageQueueManager queue = new MessageQueueManager();
        queue.enqueue(new QueuedCommand(
            "user input", null, "prompt", null,
            false, null, false, false, null, null, null));

        assertEquals(1, queue.size());
        assertEquals(QueuePriority.NEXT, queue.peek().priority(),
            "unset priority must default to NEXT for user input");
    }

    /**
     * An explicit priority is always honored (never overridden by the default),
     * for both enqueue paths.
     */
    @Test
    void explicitPriorityIsHonored() {
        MessageQueueManager queue = new MessageQueueManager();
        queue.enqueue(new QueuedCommand(
            "urgent", null, "prompt", QueuePriority.NOW,
            false, null, false, false, null, null, null));
        queue.enqueuePendingNotification(new QueuedCommand(
            "urgent notification", null, "task-notification", QueuePriority.NEXT,
            false, null, false, false, null, null, null));

        assertEquals(2, queue.size());
        assertEquals(QueuePriority.NOW, queue.peek().priority());
        assertEquals(QueuePriority.NEXT, queue.peek(c -> Strings.CS.equals(c.mode(), "task-notification")).priority());
    }

    @Test
    void scheduledPromptRetainsReleasedModelScheduledOriginThroughQueueCopy() {
        MessageQueueManager queue = new MessageQueueManager();

        queue.enqueuePendingNotification(
            QueuedCommand.modelScheduled("resolved", "raw", "cron", null));

        QueuedCommand queued = queue.peek();
        assertTrue(queued.modelScheduledOrigin());
        assertTrue(queued.skipSlashCommands());
        assertTrue(queued.isMeta());
        assertEquals(QueuePriority.LATER, queued.priority());
        assertEquals("raw", queued.preExpansionValue());
        assertEquals("cron", queued.workload());
    }

    @Test
    void sdkEventsDrainFifoWithoutEnteringTheModelCommandQueue() {
        MessageQueueManager queue = new MessageQueueManager();
        SDKMessage.TaskStarted started = new SDKMessage.TaskStarted(
            "b12345678", "toolu_1", "events", "local_bash", null, null);
        SDKMessage.TaskUpdated updated = new SDKMessage.TaskUpdated(
            "b12345678", Map.of("status", "completed"));

        queue.enqueueSdkEvent(started);
        queue.enqueueSdkEvent(updated);

        assertEquals(0, queue.size());
        assertEquals(List.of(started, updated), queue.drainSdkEvents());
        assertEquals(List.of(), queue.drainSdkEvents());
    }

    @Test
    void parentBoundaryWaitsForTerminalPatchAfterBackgroundFinalAssistant()
            throws Exception {
        MessageQueueManager queue = new MessageQueueManager();
        SDKMessage.Assistant terminalAssistant = new SDKMessage.Assistant(null, null);
        SDKMessage.TaskUpdated terminalPatch = new SDKMessage.TaskUpdated(
            "a12345678", Map.of("status", "completed"));
        queue.enqueuePendingTerminalAssistant("a12345678", terminalAssistant);

        Thread updater = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            queue.enqueueSdkEvent(terminalPatch);
        });
        assertTrue(queue.awaitPendingTerminalAssistants(1_000));
        updater.join();

        assertEquals(List.of(terminalAssistant, terminalPatch), queue.drainSdkEvents());
    }
}
