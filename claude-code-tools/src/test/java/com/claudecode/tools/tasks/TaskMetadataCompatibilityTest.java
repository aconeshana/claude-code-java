package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskMetadataCompatibilityTest {

    @Test
    void metadataUpdatePreservesExistingJsonNullValues() {
        TodoStore store = TodoStore.inMemory();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nullable", null);
        Task task = store.create("metadata", "compatibility", null, metadata);
        TaskUpdateTool tool = new TaskUpdateTool(store);
        var input = JsonUtils.getMapper().createObjectNode().put("taskId", task.id());
        input.putObject("metadata").put("added", "value");

        tool.call(input, ToolExecutionContext.of(new AbortController(), "session"));

        Task updated = store.get(task.id()).orElseThrow();
        assertNull(updated.metadata().orElseThrow().get("nullable"));
        assertEquals("value", updated.metadata().orElseThrow().get("added"));
    }
}
