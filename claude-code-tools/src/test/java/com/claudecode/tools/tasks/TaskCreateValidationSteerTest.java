package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.tools.ToolRegistry;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCreateValidationSteerTest {

    @Test
    void taskCollectionMistakeAppendsReleased197Steer() {
        ToolRegistry registry = registry();
        ObjectNode input = JsonUtils.getMapper().createObjectNode();
        input.putArray("tasks").addObject().put("subject", "one");

        String text = text(registry.execute("TaskCreate", input, context()));

        assertTrue(text.endsWith("""
            TaskCreate creates ONE task per call and has no `tasks` or `todos` parameter. Call TaskCreate once per task, passing `subject` (a brief title) and `description` (what needs to be done) as top-level string parameters.</tool_use_error>"""), text);
    }

    @Test
    void agentParametersMistakeAppendsReleased197Steer() {
        ToolRegistry registry = registry();
        ObjectNode input = JsonUtils.getMapper().createObjectNode();
        input.put("prompt", "delegate this");
        input.put("subagent_type", "general-purpose");

        String text = text(registry.execute("TaskCreate", input, context()));

        assertTrue(text.endsWith("""
            This call used Agent-tool parameters (`prompt`/`subagent_type`). TaskCreate adds an item to the task list and takes `subject` and `description` string parameters. To delegate work to a subagent, use the Agent tool instead.</tool_use_error>"""), text);
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TaskCreateTool(TodoStore.inMemory()));
        return registry;
    }

    private static ToolExecutionContext context() {
        return ToolExecutionContext.of(new AbortController(), "session");
    }

    private static String text(ToolResult result) {
        return ((TextBlock) result.content().getFirst()).text();
    }
}
