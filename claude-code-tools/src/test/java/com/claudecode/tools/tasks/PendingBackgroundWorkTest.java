package com.claudecode.tools.tasks;

import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@Timeout(20)
class PendingBackgroundWorkTest {

    private static TaskRegistry registry() {
        return new TaskRegistry(TaskStore.inMemory());
    }

    /** A task in the requested state, walked through RUNNING as the store demands. */
    private static TaskState task(TaskRegistry registry, TaskType type, TaskStatus status) {
        TaskState created = registry.store().create(type, type + " task");
        registry.store().updateStatus(created.id(), TaskStatus.RUNNING);
        if (status != TaskStatus.RUNNING) {
            registry.store().updateStatus(created.id(), status);
        }
        return created;
    }

    private static QueuedCommand notification(String taskId, String agentId) {
        return new QueuedCommand("<task-notification/>", null, "task-notification",
            QueuePriority.LATER, true, null, false, false, null, null, agentId, null, taskId);
    }

    @Test
    void countsRunningBackgroundAgentsAndWorkflowsSeparately() {
        TaskRegistry registry = registry();
        task(registry, TaskType.LOCAL_AGENT, TaskStatus.RUNNING);
        task(registry, TaskType.LOCAL_AGENT, TaskStatus.RUNNING);
        task(registry, TaskType.LOCAL_WORKFLOW, TaskStatus.RUNNING);

        PendingBackgroundWork pending = PendingBackgroundWork.count(registry, List.of());

        assertEquals(2, pending.pendingAgents());
        assertEquals(1, pending.pendingWorkflows());
        assertTrue(pending.any());
    }

    @Test
    void aForegroundAgentIsWorkTheTurnDrivesNotWorkItWaitsOn() {
        TaskRegistry registry = registry();
        TaskState foreground = task(registry, TaskType.LOCAL_AGENT, TaskStatus.RUNNING);
        registry.registerAgentForeground(new LocalAgentTask(foreground, registry.store()));

        assertEquals(PendingBackgroundWork.NONE,
            PendingBackgroundWork.count(registry, List.of()));
    }

    @Test
    void mainSessionAgentIsTheCoordinatorItselfAndNeverPending() {
        TaskRegistry registry = registry();
        TaskState main = task(registry, TaskType.LOCAL_AGENT, TaskStatus.RUNNING);
        registry.store().updateAgentType(main.id(), TaskRegistry.MAIN_SESSION_AGENT_TYPE);

        assertEquals(PendingBackgroundWork.NONE,
            PendingBackgroundWork.count(registry, List.of()));
    }

    @Test
    void terminalTaskCountsUntilItsNotificationHasBeenAnnounced() {
        TaskRegistry registry = registry();
        TaskState agent = task(registry, TaskType.LOCAL_AGENT, TaskStatus.COMPLETED);

        assertEquals(1, PendingBackgroundWork.count(registry, List.of()).pendingAgents(),
            "the result has not reached the transcript yet, so the turn still waits");

        registry.store().markNotified(agent.id());

        assertEquals(PendingBackgroundWork.NONE,
            PendingBackgroundWork.count(registry, List.of()));
    }

    @Test
    void pausedTaskIsNotPendingEvenThoughItIsNotFinished() {
        TaskRegistry registry = registry();
        task(registry, TaskType.LOCAL_WORKFLOW, TaskStatus.PAUSED);

        assertEquals(PendingBackgroundWork.NONE,
            PendingBackgroundWork.count(registry, List.of()),
            "released tests running|completed|failed|killed; a paused workflow is not "
                + "something the turn is waiting to hear back from");
    }

    @Test
    void anUndrainedNotificationHoldsTheTurnOpenAfterTheTaskWentQuiet() {
        TaskRegistry registry = registry();
        TaskState agent = task(registry, TaskType.LOCAL_AGENT, TaskStatus.COMPLETED);
        registry.store().markNotified(agent.id());

        assertEquals(1, PendingBackgroundWork.count(registry,
            List.of(notification(agent.id(), null))).pendingAgents(),
            "the task store has nothing left to say, but the queued notification does");
    }

    @Test
    void theSameTaskSeenInBothSourcesIsCountedOnce() {
        TaskRegistry registry = registry();
        TaskState agent = task(registry, TaskType.LOCAL_AGENT, TaskStatus.RUNNING);

        assertEquals(1, PendingBackgroundWork.count(registry,
            List.of(notification(agent.id(), null))).pendingAgents());
    }

    @Test
    void aSubAgentOwnedNotificationBelongsToThatAgentsLoopNotTheMainTurn() {
        TaskRegistry registry = registry();
        TaskState agent = task(registry, TaskType.LOCAL_AGENT, TaskStatus.COMPLETED);
        registry.store().markNotified(agent.id());

        assertEquals(PendingBackgroundWork.NONE, PendingBackgroundWork.count(registry,
            List.of(notification(agent.id(), "agent-7"))));
    }

    @Test
    void nonNotificationQueueEntriesAreIgnored() {
        TaskRegistry registry = registry();
        TaskState agent = task(registry, TaskType.LOCAL_AGENT, TaskStatus.COMPLETED);
        registry.store().markNotified(agent.id());
        QueuedCommand typed = new QueuedCommand("hello", null, "prompt",
            QueuePriority.LATER, false, null, false, false, null, null, null, null, agent.id());

        assertEquals(PendingBackgroundWork.NONE,
            PendingBackgroundWork.count(registry, List.of(typed)));
    }

    @Test
    void aWaitingTurnReportsItsOwnSliceAndRemembersWhenTheWaitBegan() {
        PendingBackgroundWork pending = new PendingBackgroundWork(1, 0);

        PendingBackgroundWork.Resolved resolved = PendingBackgroundWork.resolve(
            pending, 500L, 1_000L, 1_600L, null);

        assertEquals(500L, resolved.durationMs());
        assertEquals(1, resolved.pendingBackgroundAgentCount());
        assertNull(resolved.pendingWorkflowCount(), "0 workflows must stay off the row");
        assertEquals(1_000L, resolved.backgroundWaitStartTime());
        assertTrue(resolved.waiting());
    }

    @Test
    void aSecondWaitingTurnDoesNotRestartTheWaitClock() {
        PendingBackgroundWork.Resolved resolved = PendingBackgroundWork.resolve(
            new PendingBackgroundWork(1, 0), 300L, 5_000L, 5_300L, 1_000L);

        assertEquals(1_000L, resolved.backgroundWaitStartTime());
    }

    @Test
    void theTurnThatFindsNothingPendingReportsTheWholeWaitAndClearsIt() {
        PendingBackgroundWork.Resolved resolved = PendingBackgroundWork.resolve(
            PendingBackgroundWork.NONE, 300L, 9_000L, 9_400L, 1_000L);

        assertEquals(8_400L, resolved.durationMs(),
            "the user waited from the first dispatching turn, not just this turn");
        assertNull(resolved.backgroundWaitStartTime());
        assertNull(resolved.pendingBackgroundAgentCount());
        assertNull(resolved.pendingWorkflowCount());
    }

    @Test
    void anOrdinaryTurnReportsItsOwnActiveDuration() {
        PendingBackgroundWork.Resolved resolved = PendingBackgroundWork.resolve(
            PendingBackgroundWork.NONE, 742L, 1_000L, 1_800L, null);

        assertEquals(742L, resolved.durationMs(),
            "the active elapsed time already excludes pauses; wall clock must not "
                + "replace it when no wait was in progress");
        assertNull(resolved.backgroundWaitStartTime());
    }
}
