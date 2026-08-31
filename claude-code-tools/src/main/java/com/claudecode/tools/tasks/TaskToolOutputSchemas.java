package com.claudecode.tools.tasks;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class TaskToolOutputSchemas {

    private TaskToolOutputSchemas() {}

    static JsonNode todoWrite() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = schema.putObject("properties");
        properties.set("oldTodos", todoArray());
        properties.set("newTodos", todoArray());
        require(schema, "oldTodos", "newTodos");
        return schema;
    }

    static JsonNode taskCreate() {
        ObjectNode schema = objectSchema();
        ObjectNode task = objectSchema();
        ObjectNode taskProperties = task.putObject("properties");
        taskProperties.set("id", stringSchema());
        taskProperties.set("subject", stringSchema());
        require(task, "id", "subject");
        schema.putObject("properties").set("task", task);
        require(schema, "task");
        return schema;
    }

    static JsonNode taskGet() {
        ObjectNode schema = objectSchema();
        ObjectNode task = taskDetails();
        ArrayNode types = task.putArray("type");
        types.add("object");
        types.add("null");
        schema.putObject("properties").set("task", task);
        require(schema, "task");
        return schema;
    }

    static JsonNode taskList() {
        ObjectNode schema = objectSchema();
        ObjectNode item = objectSchema();
        ObjectNode properties = item.putObject("properties");
        properties.set("id", stringSchema());
        properties.set("subject", stringSchema());
        properties.set("status", statusSchema());
        properties.set("owner", stringSchema());
        properties.set("blockedBy", stringArray());
        require(item, "id", "subject", "status", "blockedBy");
        ObjectNode tasks = arraySchema();
        tasks.set("items", item);
        schema.putObject("properties").set("tasks", tasks);
        require(schema, "tasks");
        return schema;
    }

    static JsonNode taskUpdate() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = schema.putObject("properties");
        properties.set("success", booleanSchema());
        properties.set("taskId", stringSchema());
        properties.set("updatedFields", stringArray());
        properties.set("error", stringSchema());
        ObjectNode statusChange = objectSchema();
        ObjectNode changeProperties = statusChange.putObject("properties");
        changeProperties.set("from", stringSchema());
        changeProperties.set("to", stringSchema());
        require(statusChange, "from", "to");
        properties.set("statusChange", statusChange);
        require(schema, "success", "taskId", "updatedFields");
        return schema;
    }

    private static ObjectNode taskDetails() {
        ObjectNode task = objectSchema();
        ObjectNode properties = task.putObject("properties");
        properties.set("id", stringSchema());
        properties.set("subject", stringSchema());
        properties.set("description", stringSchema());
        properties.set("status", statusSchema());
        properties.set("blocks", stringArray());
        properties.set("blockedBy", stringArray());
        require(task, "id", "subject", "description", "status", "blocks", "blockedBy");
        return task;
    }

    private static ObjectNode todoArray() {
        ObjectNode array = arraySchema();
        ObjectNode item = objectSchema();
        ObjectNode properties = item.putObject("properties");
        properties.set("content", stringSchema());
        properties.set("status", statusSchema());
        properties.set("activeForm", stringSchema());
        require(item, "content", "status", "activeForm");
        array.set("items", item);
        return array;
    }

    private static ObjectNode objectSchema() {
        ObjectNode schema = JsonUtils.getMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode arraySchema() {
        return JsonUtils.getMapper().createObjectNode().put("type", "array");
    }

    private static ObjectNode stringArray() {
        ObjectNode array = arraySchema();
        array.set("items", stringSchema());
        return array;
    }

    private static ObjectNode stringSchema() {
        return JsonUtils.getMapper().createObjectNode().put("type", "string");
    }

    private static ObjectNode booleanSchema() {
        return JsonUtils.getMapper().createObjectNode().put("type", "boolean");
    }

    private static ObjectNode statusSchema() {
        ObjectNode schema = stringSchema();
        schema.putArray("enum").add("pending").add("in_progress").add("completed");
        return schema;
    }

    private static void require(ObjectNode schema, String... names) {
        ArrayNode required = schema.putArray("required");
        for (String name : names) required.add(name);
    }
}
