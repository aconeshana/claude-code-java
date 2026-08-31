package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.permissions.PermissionMode;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.tools.tasks.InProcessTeammateTask;
import com.claudecode.tools.workflows.WorkflowRun;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PromptTaskNavigationControllerTest {

    @AfterEach
    void clearViewedAgent() {
        ViewedTeammateHolder.instance().exit();
    }

    @Test
    void permissionModesFollowOfficialCycle() {
        assertEquals(PermissionMode.ACCEPT_EDITS,
            PromptTaskNavigationController.nextPermissionMode(PermissionMode.DEFAULT));
        assertEquals(PermissionMode.PLAN,
            PromptTaskNavigationController.nextPermissionMode(PermissionMode.ACCEPT_EDITS));
        assertEquals(PermissionMode.BYPASS_PERMISSIONS,
            PromptTaskNavigationController.nextPermissionMode(PermissionMode.PLAN));
        assertEquals(PermissionMode.DEFAULT,
            PromptTaskNavigationController.nextPermissionMode(PermissionMode.BYPASS_PERMISSIONS));
    }

    @Test
    void escapeFromOrdinaryLocalAgentReturnsToMainWithoutTeammateInterruption() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState task = store.createWithId("agent-1", TaskType.LOCAL_AGENT, "research", null);
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        PromptTaskNavigationController controller = new PromptTaskNavigationController();
        controller.setRegistry(registry);
        ViewedTeammateHolder.instance().enterLocalAgentViewing(task.id());
        TestHost host = new TestHost();

        TextBox.Result result = controller.handleTeammateKey(
            new KeyStroke(KeyType.ESCAPE), host);

        assertEquals(TextBox.Result.HANDLED, result);
        assertFalse(ViewedTeammateHolder.instance().isViewing());
        assertTrue(host.viewChanged);
        assertFalse(host.interrupted);
    }

    @Test
    void ordinaryLocalAgentHintDoesNotExitViewOrExposeTeammateKeys() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        TaskState task = store.createWithId("agent-2", TaskType.LOCAL_AGENT, "index repository", null);
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        PromptTaskNavigationController controller = new PromptTaskNavigationController();
        controller.setRegistry(registry);
        ViewedTeammateHolder.instance().enterLocalAgentViewing(task.id());

        PromptTaskNavigationController.TeammateHint hint = controller.teammateHint(new TestHost());

        assertTrue(ViewedTeammateHolder.instance().isViewing());
        assertEquals("Viewing @index repository", hint.main());
        assertEquals("esc: return", hint.suffix());
    }

    @Test
    void workflowIsOwnedByReleasedCoordinatorRowNotOrdinaryBackgroundPill() {
        TaskStore taskStore = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(taskStore);
        TaskState task = taskStore.createWithId(
            "workflow-task", TaskType.LOCAL_WORKFLOW, "Research ecosystem", null);
        taskStore.updateStatus(task.id(), TaskStatus.RUNNING);
        var agent = JsonUtils.getMapper().createObjectNode();
        agent.put("type", "workflow_agent");
        agent.put("index", 1);
        agent.put("state", "progress");
        WorkflowRunStore runs = new WorkflowRunStore();
        runs.put(WorkflowRun.builder("workflow-run", task.id(), TaskStatus.RUNNING)
            .workflowName("ecosystem-briefing")
            .summary("Research ecosystem")
            .script("")
            .agentCount(1)
            .workflowProgress(List.of(agent))
            .totalTokens(53_800)
            .durationMs(188_000)
            .build());
        PromptTaskNavigationController controller = new PromptTaskNavigationController();
        controller.setRegistry(registry);

        assertEquals("", controller.pillView().label());
        assertFalse(controller.pillAvailable(),
            "2.1.197 renders local_workflow through vEc/ETf with footerSelection=workflows");
    }

    private static final class TestHost implements PromptTaskNavigationController.Host {
        boolean interrupted;
        boolean viewChanged;
        boolean treeExpanded;

        @Override public void openTasksDialog() {}
        @Override public void refreshHint() {}
        @Override public void clearStatusLine() {}
        @Override public void showTeammateStatus(InProcessTeammateTask task) {}
        @Override public void showInterruptedHint() { interrupted = true; }
        @Override public void showPermissionModeHint(PermissionMode mode) {}
        @Override public void teammateViewChanged() { viewChanged = true; }
        @Override public void setTeammateTreeExpanded(boolean expanded) {
            treeExpanded = expanded;
        }
        @Override public boolean isTeammateTreeExpanded() { return treeExpanded; }
    }
}
