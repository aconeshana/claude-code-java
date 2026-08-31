package com.claudecode.tools.tasks;

import com.claudecode.tools.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskToolOutputSchemaTest {

    @Test
    void released197TaskAndTodoToolsExposeOutputSchemas() {
        assertRequired(new TodoWriteTool(), "oldTodos", "newTodos");
        assertRequired(new TaskCreateTool(TodoStore.inMemory()), "task");
        assertRequired(new TaskGetTool(TodoStore.inMemory()), "task");
        assertRequired(new TaskListTool(TodoStore.inMemory()), "tasks");
        assertRequired(new TaskUpdateTool(TodoStore.inMemory()),
            "success", "taskId", "updatedFields");
    }

    private static void assertRequired(Tool<?, ?> tool, String... expected) {
        JsonNode schema = tool.outputSchema();
        assertNotNull(schema, tool.name() + " output schema");
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.path("properties").isObject());
        Set<String> required = StreamSupport.stream(
                schema.path("required").spliterator(), false)
            .map(JsonNode::asText)
            .collect(Collectors.toSet());
        assertEquals(Set.of(expected), required);
    }
}
