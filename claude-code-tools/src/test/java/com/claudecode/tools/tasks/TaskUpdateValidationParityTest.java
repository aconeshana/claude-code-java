package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.tools.ToolRegistry;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskUpdateValidationParityTest {

    @Test
    void invalidStatusIsReleased197InputValidationError() {
        TodoStore store = TodoStore.inMemory();
        Task task = store.create("A", "D", null, null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TaskUpdateTool(store));

        ToolResult result = registry.execute("TaskUpdate",
            JsonUtils.getMapper().createObjectNode()
                .put("taskId", task.id())
                .put("status", "bogus"),
            ToolExecutionContext.of(new AbortController(), "session"));

        assertTrue(result.isError());
        TextBlock content = (TextBlock) result.content().getFirst();
        assertTrue(Strings.CS.startsWith(
            content.text(), "<tool_use_error>InputValidationError:"), content.text());
        assertEquals(TodoStatus.PENDING, store.get(task.id()).orElseThrow().status());
    }
}
