package com.claudecode.tools;

import com.claudecode.tools.tasks.TaskCreateTool;
import com.claudecode.tools.tasks.TaskGetTool;
import com.claudecode.tools.tasks.TaskListTool;
import com.claudecode.tools.tasks.TaskUpdateTool;
import com.claudecode.tools.tasks.TodoStore;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.tools.messaging.SendMessageTool;


class ToolGateParityTest {

    private static final String NON_INTERACTIVE = "claude.code.nonInteractive";

    @AfterEach
    void restoreGates() {
        System.clearProperty(NON_INTERACTIVE);
        AgentTeamsEnabled.resetForTest();
    }

    @Test
    void released197TaskListToolsStayEnabledAcrossSessionModes() {
        TodoStore store = TodoStore.inMemory();
        var create = new TaskCreateTool(store);
        var get = new TaskGetTool(store);
        var list = new TaskListTool(store);
        var update = new TaskUpdateTool(store);

        System.setProperty(NON_INTERACTIVE, "false");
        assertTrue(create.isEnabled());
        assertTrue(get.isEnabled());
        assertTrue(list.isEnabled());
        assertTrue(update.isEnabled());

        System.setProperty(NON_INTERACTIVE, "true");
        assertTrue(create.isEnabled());
        assertTrue(get.isEnabled());
        assertTrue(list.isEnabled());
        assertTrue(update.isEnabled());
    }

    @Test
    void sendMessageNotGatedByAgentTeams() {
        SendMessageTool tool = new SendMessageTool();
        AgentTeamsEnabled.setEnabledForTest(false);
        assertTrue(tool.isEnabled(),
            "197 registers SendMessage by default, independent of the agent-teams gate");
        AgentTeamsEnabled.setEnabledForTest(true);
        assertTrue(tool.isEnabled());
    }
}
