package com.claudecode.tools.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the coordinator-panel projection/eviction on {@link TaskRegistry} ({@link
 * TaskRegistry#listPanelAgentTasks}, {@link TaskRegistry#dismissAgent}, {@link
 * TaskRegistry#evictExpiredPanelTasks}).
 */
@Timeout(20)
class TaskRegistryPanelTest {

    private static TaskRegistry registry() {
        return new TaskRegistry(TaskStore.inMemory());
    }

    private static TaskState runningAgent(TaskRegistry registry, String desc) {
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, desc);
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        return task;
    }

    private static void completeWithDeadline(TaskRegistry registry, String taskId, Instant deadline) {
        registry.store().updateStatus(taskId, TaskStatus.COMPLETED);
        registry.store().setEvictAfter(taskId, deadline);
    }

    @Test
    void includesRunningLocalAgents_excludesTeammatesAndMainSession() {
        TaskRegistry registry = registry();
        TaskState agent = runningAgent(registry, "explore repo");

        // A teammate task must never appear in the coordinator panel.
        TaskState teammate = registry.store().create(TaskType.IN_PROCESS_TEAMMATE, "helper");
        registry.store().updateStatus(teammate.id(), TaskStatus.RUNNING);

        // A main-session-typed local_agent is the coordinator itself → excluded.
        TaskState main = runningAgent(registry, "root");
        registry.store().updateAgentType(main.id(), TaskRegistry.MAIN_SESSION_AGENT_TYPE);

        List<String> ids = registry.listPanelAgentTasks(Instant.now()).stream()
            .map(TaskState::id).toList();

        assertTrue(ids.contains(agent.id()), "running local_agent is a panel row");
        assertFalse(ids.contains(teammate.id()), "teammate belongs to the other subsystem");
        assertFalse(ids.contains(main.id()), "main-session agent is never a panel row");
    }

    @Test
    void doesNotExcludeForegroundAgents() {
        TaskRegistry registry = registry();
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, "foreground");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        registry.registerAgentForeground(new LocalAgentTask(task, registry.store()));

        List<String> ids = registry.listPanelAgentTasks(Instant.now()).stream()
            .map(TaskState::id).toList();

        assertTrue(ids.contains(task.id()),
            "the coordinator panel shows foreground agents (unlike listBackground)");
    }

    @Test
    void terminalWithinGraceIsShown_pastGraceIsHidden() {
        TaskRegistry registry = registry();
        Instant now = Instant.now();

        TaskState fresh = runningAgent(registry, "just finished");
        completeWithDeadline(registry, fresh.id(), now.plusSeconds(30));

        TaskState stale = runningAgent(registry, "long done");
        completeWithDeadline(registry, stale.id(), now.minusSeconds(1));

        List<String> ids = registry.listPanelAgentTasks(now).stream()
            .map(TaskState::id).toList();

        assertTrue(ids.contains(fresh.id()), "terminal row within its grace window is visible");
        assertFalse(ids.contains(stale.id()), "terminal row past its grace window is hidden");
    }

    @Test
    void dismissHidesRowImmediately() {
        TaskRegistry registry = registry();
        Instant now = Instant.now();
        TaskState task = runningAgent(registry, "dismiss me");
        completeWithDeadline(registry, task.id(), now.plusSeconds(30));

        registry.dismissAgent(task.id());

        List<String> ids = registry.listPanelAgentTasks(now).stream()
            .map(TaskState::id).toList();
        assertFalse(ids.contains(task.id()), "dismissed row (evictAfter=EPOCH) drops out at once");
    }

    @Test
    void sortsByStartTimeAscending() throws InterruptedException {
        TaskRegistry registry = registry();
        TaskState first = runningAgent(registry, "first");
        Thread.sleep(5);
        TaskState second = runningAgent(registry, "second");
        Thread.sleep(5);
        TaskState third = runningAgent(registry, "third");

        List<String> ids = registry.listPanelAgentTasks(Instant.now()).stream()
            .map(TaskState::id).toList();

        assertEquals(List.of(first.id(), second.id(), third.id()), ids);
    }

    @Test
    void evictExpired_removesPastGraceKeepsRunningAndWithinGrace() {
        TaskRegistry registry = registry();
        Instant now = Instant.now();

        TaskState running = runningAgent(registry, "still going");

        TaskState within = runningAgent(registry, "within grace");
        completeWithDeadline(registry, within.id(), now.plusSeconds(30));

        TaskState expired = runningAgent(registry, "expired");
        completeWithDeadline(registry, expired.id(), now.minusSeconds(1));

        List<String> evicted = registry.evictExpiredPanelTasks(now, null);

        assertEquals(List.of(expired.id()), evicted);
        assertTrue(registry.store().get(running.id()).isPresent(), "running task is never swept");
        assertTrue(registry.store().get(within.id()).isPresent(), "within-grace task is kept");
        assertTrue(registry.store().get(expired.id()).isEmpty(), "past-grace task is removed");
    }

    @Test
    void evictExpired_protectsViewedRowByClearingItsDeadline() {
        TaskRegistry registry = registry();
        Instant now = Instant.now();

        TaskState viewed = runningAgent(registry, "being viewed");
        completeWithDeadline(registry, viewed.id(), now.minusSeconds(1)); // already past grace

        List<String> evicted = registry.evictExpiredPanelTasks(now, viewed.id());

        assertTrue(evicted.isEmpty(), "the viewed row is protected from the sweep");
        assertTrue(registry.store().get(viewed.id()).isPresent(), "viewed row stays in the store");
        assertTrue(registry.store().evictAfter(viewed.id()).isEmpty(),
            "the viewed row's deadline is cleared so it lingers until the viewer leaves");
        // It remains visible in the panel while viewed.
        assertTrue(registry.listPanelAgentTasks(now).stream()
            .anyMatch(t -> t.id().equals(viewed.id())));
    }
}
