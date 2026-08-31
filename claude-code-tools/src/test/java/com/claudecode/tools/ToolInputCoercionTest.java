package com.claudecode.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

class ToolInputCoercionTest {
    @Test void coercesOnlyExactSemanticLiterals() {
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("-i", "false");
        input.put("head_limit", "30");
        input.put("context", "3x");
        var result = ToolInputCoercion.coerce("Grep", input);
        assertTrue(result.path("-i").isBoolean());
        assertFalse(result.path("-i").asBoolean());
        assertEquals(30, result.path("head_limit").asInt());
        assertTrue(result.path("context").isTextual());
    }

    @Test void coercesNestedSendMessageApprove() {
        var input = JsonUtils.getMapper().createObjectNode();
        var message = input.putObject("message");
        message.put("approve", "true");
        assertTrue(ToolInputCoercion.coerce("SendMessage", input)
            .path("message").path("approve").asBoolean());
    }

    @Test void repairsReleased197TaskCreateAliasesAndWrapperObjects() {
        var input = JsonUtils.getMapper().createObjectNode();
        var task = input.putObject("task");
        task.put("name", "Fix persistence");
        task.put("content", "Make task JSON compatible");
        task.put("active_form", "Fixing persistence");
        task.put("status", "in_progress");
        task.put("metadata", "invalid metadata is dropped");

        var result = ToolInputCoercion.coerce("TaskCreate", input);

        assertEquals("Fix persistence", result.path("subject").asText());
        assertEquals("Make task JSON compatible", result.path("description").asText());
        assertEquals("Fixing persistence", result.path("activeForm").asText());
        assertFalse(result.has("task"));
        assertFalse(result.has("name"));
        assertFalse(result.has("content"));
        assertFalse(result.has("active_form"));
        assertFalse(result.has("status"));
        assertFalse(result.has("metadata"));
    }

    @Test void backfillsReleased197TaskCreateSubjectAndDescription() {
        var wrappedString = JsonUtils.getMapper().createObjectNode();
        wrappedString.put("task", "Run the complete compatibility regression suite");
        var fromString = ToolInputCoercion.coerce("TaskCreate", wrappedString);
        assertEquals("Run the complete compatibility regression suite",
            fromString.path("subject").asText());
        assertEquals("Run the complete compatibility regression suite",
            fromString.path("description").asText());

        var descriptionOnly = JsonUtils.getMapper().createObjectNode();
        descriptionOnly.put("description", "A very long description that should become the task "
            + "subject without exceeding the released client title limit and without splitting "
            + "a word in the middle");
        var fromDescription = ToolInputCoercion.coerce("TaskCreate", descriptionOnly);
        assertTrue(fromDescription.path("subject").asText().length() <= 80);
        assertEquals(descriptionOnly.path("description"), fromDescription.path("description"));
    }

    @Test void repairsReleased197TaskUpdateAliases() {
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("task_id", "17");
        input.put("active_form", "Running tests");

        var result = ToolInputCoercion.coerce("TaskUpdate", input);

        assertEquals("17", result.path("taskId").asText());
        assertEquals("Running tests", result.path("activeForm").asText());
        assertFalse(result.has("task_id"));
        assertFalse(result.has("active_form"));
    }

    @Test void taskAliasesUseReleased197JavaScriptWhitespaceSemantics() {
        var blankAlias = JsonUtils.getMapper().createObjectNode();
        blankAlias.put("title", "\u00a0\ufeff");

        var unchanged = ToolInputCoercion.coerce("TaskCreate", blankAlias);

        assertTrue(unchanged.has("title"));
        assertFalse(unchanged.has("subject"));
        assertFalse(unchanged.has("description"));

        var descriptionOnly = JsonUtils.getMapper().createObjectNode();
        descriptionOnly.put("description", "\u00a0Fix the task\ufeff");

        var coerced = ToolInputCoercion.coerce("TaskCreate", descriptionOnly);

        assertEquals("Fix the task", coerced.path("subject").asText());
        assertEquals("\u00a0Fix the task\ufeff", coerced.path("description").asText());

        var javaOnlyWhitespace = JsonUtils.getMapper().createObjectNode();
        javaOnlyWhitespace.put("title", "\u001c");
        javaOnlyWhitespace.put("description", "desc");

        var preserved = ToolInputCoercion.coerce("TaskCreate", javaOnlyWhitespace);

        assertEquals("\u001c", preserved.path("subject").asText());
        assertFalse(preserved.has("title"));

        var updateAlias = JsonUtils.getMapper().createObjectNode();
        updateAlias.put("id", "\u001c");
        assertEquals("\u001c",
            ToolInputCoercion.coerce("TaskUpdate", updateAlias).path("taskId").asText());
    }

    @Test void taskCreateDerivesSubjectFromTheFirstDescriptionLineLikeReleased197() {
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("description", "Short task title\nDetailed requirements stay in the description");

        var coerced = ToolInputCoercion.coerce("TaskCreate", input);

        assertEquals("Short task title", coerced.path("subject").asText());
        assertEquals(input.path("description"), coerced.path("description"));
    }
}
