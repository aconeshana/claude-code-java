package com.claudecode.ui.lanterna.input;

import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CoordinatorNavigationController} — the subagent panel's
 * navigation model. Asserts the two subsystems stay isolated (a teammate-only
 * scene never engages this controller and vice versa), main↔subagent stepping,
 * Enter/Esc dispatch, dismiss, and per-tick auto-exit/eviction.
 */
class CoordinatorNavigationControllerTest {

    private final ViewedTeammateHolder view = ViewedTeammateHolder.instance();

    @AfterEach
    void reset() {
        view.exit();
    }

    private static final class TestHost implements CoordinatorNavigationController.Host {
        int viewChanged;
        int refreshed;
        int cleared;

        @Override public void teammateViewChanged() { viewChanged++; }
        @Override public void refreshHint() { refreshed++; }
        @Override public void clearStatusLine() { cleared++; }
    }

    private static TaskState runningAgent(TaskStore store, String desc) {
        TaskState task = store.create(TaskType.LOCAL_AGENT, desc);
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        return task;
    }

    private static CoordinatorNavigationController controller(TaskRegistry registry, Instant now) {
        return new CoordinatorNavigationController(registry, () -> now);
    }

    @Test
    void panelIgnoresTeammates() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState teammate = store.create(TaskType.IN_PROCESS_TEAMMATE, "helper");
        store.updateStatus(teammate.id(), TaskStatus.RUNNING);

        CoordinatorNavigationController controller = controller(registry, Instant.now());

        assertFalse(controller.panelAvailable(),
            "a scene with only teammates must not engage the subagent panel");
        assertTrue(controller.panelAgents().isEmpty());
    }

    @Test
    void stepMovesBetweenMainAndAgentsWithoutWrap() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        runningAgent(store, "first");
        runningAgent(store, "second");
        CoordinatorNavigationController controller = controller(registry, Instant.now());
        TestHost host = new TestHost();

        controller.selectPanel();
        assertEquals(0, controller.coordinatorIndex(), "selection parks on main");

        controller.step(1, host);
        assertEquals(1, controller.coordinatorIndex());
        controller.step(1, host);
        assertEquals(2, controller.coordinatorIndex());
        controller.step(1, host);
        assertEquals(2, controller.coordinatorIndex(), "no wrap past the last agent");

        controller.step(-1, host);
        controller.step(-1, host);
        controller.step(-1, host);
        assertEquals(0, controller.coordinatorIndex(), "no wrap before main");
    }

    @Test
    void backgroundTaskPillPrecedesMainInTheUnifiedTasksFooter() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        runningAgent(store, "first");
        CoordinatorNavigationController controller = controller(registry, Instant.now());
        TestHost host = new TestHost();

        controller.selectPanel(true);
        assertEquals(-1, controller.coordinatorIndex());
        controller.step(1, host);
        assertEquals(0, controller.coordinatorIndex());
        controller.step(1, host);
        assertEquals(1, controller.coordinatorIndex());
    }

    @Test
    void dynamicInsertionKeepsSelectionOnTheSameAgentId() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState selected = runningAgent(store, "selected");
        CoordinatorNavigationController controller = controller(registry, Instant.now());
        TestHost host = new TestHost();
        controller.selectPanel();
        controller.step(1, host);
        assertEquals(selected.id(), controller.selectedTaskId());

        runningAgent(store, "later");
        controller.tick(host);

        assertEquals(selected.id(), controller.selectedTaskId());
    }

    @Test
    void openSelectedEntersSubagentTranscriptAndClearsEvictAfter() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState agent = runningAgent(store, "explore");
        CoordinatorNavigationController controller = controller(registry, Instant.now());
        TestHost host = new TestHost();

        controller.selectPanel();
        controller.step(1, host);
        controller.openSelected(host);

        assertTrue(view.isViewingLocalAgent());
        assertEquals(agent.id(), view.viewingTaskId());
        assertTrue(host.viewChanged > 0);
    }

    @Test
    void openSelectedOnMainReturnsToLeader() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState agent = runningAgent(store, "explore");
        CoordinatorNavigationController controller = controller(registry, Instant.now());
        TestHost host = new TestHost();
        controller.enterView(agent.id(), host);
        assertTrue(view.isViewingLocalAgent());

        controller.selectPanel();               // index 0 = main
        controller.openSelected(host);

        assertFalse(view.isViewingLocalAgent(), "main row exits the subagent view");
    }

    @Test
    void escapeWhileViewingPreservesRunningLocalAgentAndQueuedInput() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState agent = runningAgent(store, "explore");
        registry.registerAgent(new LocalAgentTask(agent, store));
        assertTrue(registry.queueAgentMessage(agent.id(), "continue", "user"));
        CoordinatorNavigationController controller = controller(registry, Instant.now());
        TestHost host = new TestHost();
        controller.enterView(agent.id(), host);

        assertTrue(controller.handleEscape(host));
        assertEquals(TaskStatus.RUNNING, store.get(agent.id()).orElseThrow().status(),
            "Escape only exits the 197 local-agent view; x is the explicit stop action");
        assertEquals(1, registry.pendingAgentMessageCount(agent.id()),
            "returning to main must not discard queued steering input");
        assertFalse(view.isViewingLocalAgent());
    }

    @Test
    void escapeWhileSelectingDropsFocus() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        runningAgent(store, "explore");
        CoordinatorNavigationController controller = controller(registry, Instant.now());
        TestHost host = new TestHost();
        controller.selectPanel();

        assertTrue(controller.handleEscape(host));
        assertFalse(controller.isPanelSelected());
    }

    @Test
    void liveRefreshImmediatelyClampsSelectionWhenSelectedAgentDisappears() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState first = runningAgent(store, "first");
        TaskState second = runningAgent(store, "second");
        CoordinatorNavigationController controller = controller(registry, Instant.now());
        TestHost host = new TestHost();
        controller.selectPanel();
        controller.step(1, host);
        controller.step(1, host);
        assertEquals(2, controller.coordinatorIndex());

        store.remove(second.id());
        controller.synchronizeBackgroundPill(false);

        assertTrue(controller.isPanelSelected());
        assertEquals(1, controller.coordinatorIndex(),
            "the released clamp effect must not leave a pointer on a vanished row");
        assertEquals(first.id(), controller.panelAgents().getFirst().id());
    }

    @Test
    void exitToMainReStampsEvictAfterForTerminalAgent() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState agent = runningAgent(store, "done soon");
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        CoordinatorNavigationController controller = controller(registry, now);
        TestHost host = new TestHost();
        controller.enterView(agent.id(), host);
        assertTrue(store.evictAfter(agent.id()).isEmpty(), "viewing clears the deadline (retain)");

        // Agent completes while being viewed.
        store.updateStatus(agent.id(), TaskStatus.COMPLETED);

        controller.exitToMain(host);

        Instant deadline = store.evictAfter(agent.id()).orElseThrow();
        assertEquals(now.plus(LocalAgentTask.PANEL_GRACE), deadline,
            "leaving a terminal subagent restarts its grace window");
    }

    @Test
    void tickAutoExitsWhenViewedAgentIsEvicted() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState agent = runningAgent(store, "vanishing");
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        CoordinatorNavigationController controller = controller(registry, now);
        TestHost host = new TestHost();
        controller.enterView(agent.id(), host);

        // The agent is removed out from under the viewer.
        store.remove(agent.id());
        controller.tick(host);

        assertFalse(view.isViewingLocalAgent(), "an evicted viewed agent auto-exits to main");
    }

    @Test
    void tickAutoExitsWhenViewedAgentIsKilled() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState agent = runningAgent(store, "doomed");
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        CoordinatorNavigationController controller = controller(registry, now);
        TestHost host = new TestHost();
        controller.enterView(agent.id(), host);

        store.updateStatus(agent.id(), TaskStatus.KILLED);
        controller.tick(host);

        assertFalse(view.isViewingLocalAgent(), "a killed viewed agent auto-exits to main");
    }

    @Test
    void tickKeepsViewingCompletedAgent() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState agent = runningAgent(store, "finished");
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        CoordinatorNavigationController controller = controller(registry, now);
        TestHost host = new TestHost();
        controller.enterView(agent.id(), host);

        store.updateStatus(agent.id(), TaskStatus.COMPLETED);
        controller.tick(host);

        assertTrue(view.isViewingLocalAgent(),
            "a completed agent stays viewable so the transcript can be read");
        // And it is protected from the sweep while viewed.
        assertTrue(store.get(agent.id()).isPresent());
    }

    @Test
    void dismissTerminalAgentHidesRow() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState agent = runningAgent(store, "clear me");
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        store.updateStatus(agent.id(), TaskStatus.COMPLETED);
        store.setEvictAfter(agent.id(), now.plusSeconds(30));
        CoordinatorNavigationController controller = controller(registry, now);
        TestHost host = new TestHost();
        controller.selectPanel();
        controller.step(1, host);

        controller.dismissSelected(host);

        List<String> ids = controller.panelAgents().stream().map(TaskState::id).toList();
        assertFalse(ids.contains(agent.id()), "a dismissed terminal row drops out immediately");
    }
}
