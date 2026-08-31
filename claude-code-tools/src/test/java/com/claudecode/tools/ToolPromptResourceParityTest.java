package com.claudecode.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.tools.files.GlobTool;
import com.claudecode.tools.files.GrepTool;
import com.claudecode.tools.files.NotebookEditTool;
import com.claudecode.tools.plan.ExitPlanModeTool;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.tasks.TaskOutputTool;
import com.claudecode.tools.tasks.TaskCreateTool;
import com.claudecode.tools.tasks.TaskGetTool;
import com.claudecode.tools.tasks.TaskListTool;
import com.claudecode.tools.tasks.TaskStopTool;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskUpdateTool;
import com.claudecode.tools.tasks.TodoStore;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;


class ToolPromptResourceParityTest {

    @BeforeEach
    void useReleasedPlanModeShape() {
        PlanFiles.configureMultiPlan(false);
    }

    @Test
    void descriptionsAreSparseOverridesAndPromptsAreRequired() {
        assertEquals(ToolTexts.prompt("Glob"), ToolTexts.description("Glob"));
        assertNotEquals(ToolTexts.prompt("TaskStop"), ToolTexts.description("TaskStop"));
        assertThrows(IllegalStateException.class,
            () -> ToolTexts.prompt("MissingToolTextFixture"));
    }

    @Test
    void fixedWirePromptsComeFromFrozenResources() {
        assertEquals(ToolTexts.prompt("Glob"),
            new GlobTool().prompt(null));
        assertEquals(ToolTexts.prompt("Grep"),
            new GrepTool().prompt(null));
        assertEquals(ToolTexts.prompt("NotebookEdit"),
            new NotebookEditTool().prompt(null));
        assertTrue(Strings.CS.contains(ToolTexts.prompt("ListMcpResourcesTool"),
            "'server' field \nindicating"));
        ExitPlanModeTool exitPlanMode = new ExitPlanModeTool();
        assertEquals(ToolTexts.description("ExitPlanMode"), exitPlanMode.description());
        assertEquals(ToolTexts.prompt("ExitPlanMode"),
            exitPlanMode.prompt(null));
        TaskOutputTool taskOutput = new TaskOutputTool(TaskStore.inMemory());
        assertEquals(ToolTexts.description("TaskOutput"), taskOutput.description());
        assertEquals(ToolTexts.prompt("TaskOutput"),
            taskOutput.prompt(null));
        TaskStopTool taskStop = new TaskStopTool(TaskStore.inMemory());
        assertEquals(ToolTexts.description("TaskStop"), taskStop.description());
        assertEquals(ToolTexts.prompt("TaskStop"),
            taskStop.prompt(null));
    }

    @Test
    void taskToolsKeepReleasedShortDescriptionsSeparateFromFullPrompts() {
        TodoStore store = TodoStore.inMemory();
        TaskCreateTool create = new TaskCreateTool(store);
        TaskGetTool get = new TaskGetTool(store);
        TaskListTool list = new TaskListTool(store);
        TaskUpdateTool update = new TaskUpdateTool(store);

        AgentTeamsEnabled.setEnabledForTest(false);
        try {
            assertEquals(ToolTexts.description("TaskCreate"), create.description());
            assertEquals(ToolTexts.description("TaskGet"), get.description());
            assertEquals(ToolTexts.description("TaskList"), list.description());
            assertEquals(ToolTexts.description("TaskUpdate"), update.description());
            assertEquals(ToolTexts.prompt("TaskCreate"), create.prompt(null));
            assertEquals(ToolTexts.prompt("TaskGet"), get.prompt(null));
            assertEquals(ToolTexts.prompt("TaskList"), list.prompt(null));
            assertEquals(ToolTexts.prompt("TaskUpdate"), update.prompt(null));

            AgentTeamsEnabled.setEnabledForTest(true);
            assertEquals(ToolTexts.prompt("TaskCreate", "teammate"), create.prompt(null));
            assertEquals(ToolTexts.prompt("TaskList", "teammate"), list.prompt(null));
            assertEquals(ToolTexts.prompt("TaskGet"), get.prompt(null));
            assertEquals(ToolTexts.prompt("TaskUpdate"), update.prompt(null));
        } finally {
            AgentTeamsEnabled.resetForTest();
        }
    }
}
