package com.claudecode.tools.tasks;

import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskActivityDescriptionTest {

    @TempDir
    Path cwd;

    @Test
    void fileActivitiesUseReleased197RelativePathSummaries() {
        Path source = cwd.resolve("src/App.java");

        assertEquals("Writing src/App.java",
            describe("Write", input().put("file_path", source.toString())));
        assertEquals("Editing src/App.java",
            describe("Edit", input().put("file_path", source.toString())));
        assertEquals("Reading src/App.java",
            describe("Read", input().put("file_path", source.toString())));
        assertEquals("Editing notebook notebooks/demo.ipynb",
            describe("NotebookEdit", input().put("notebook_path",
                cwd.resolve("notebooks/demo.ipynb").toString())));
    }

    @Test
    void released197DescriptionsCoverEveryToolThatPublishesBoardActivity() {
        assertEquals("Searching for TaskStore",
            describe("Grep", input().put("pattern", "TaskStore")));
        assertEquals("Finding **/*.java",
            describe("Glob", input().put("pattern", "**/*.java")));
        assertEquals("Fetching https://example.com/reference",
            describe("WebFetch", input().put("url", "https://example.com/reference")));
        assertEquals("Monitoring: waiting for server",
            describe("Monitor", input().put("description", "waiting for server")));
        assertEquals("delegated task",
            describe("Agent", input().put("description", "  delegated\n\t task  ")));
        assertEquals("Searching for current release notes",
            describe("WebSearch", input().put("query", "current release notes")));
    }

    @Test
    void released197FallbacksAndTruncationMatchToolContracts() {
        String longValue = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

        assertEquals("Writing file", describe("Write", input()));
        assertEquals("Editing file", describe("Edit", input()));
        assertEquals("Editing notebook", describe("NotebookEdit", input()));
        assertEquals("Fetching web page", describe("WebFetch", input()));
        assertEquals("Monitoring", describe("Monitor", input()));
        assertEquals("Running task", describe("Agent", input()));
        assertEquals("Searching the web", describe("WebSearch", input()));
        assertEquals("Running command", describe("Bash", input()));
        assertEquals("Running " + FormatUtils.truncate(longValue, 50),
            describe("PowerShell", input().put("command", longValue)));
        assertEquals("Searching for " + FormatUtils.truncate(longValue, 50),
            describe("Grep", input().put("pattern", longValue)));
        assertEquals("Running ", describe("Bash",
            input().put("command", "pwd").put("description", "")),
            "released uses nullish fallback, so an explicit empty description stays empty");
        assertNull(describe("TaskList", input()));
    }

    private String describe(String name, ObjectNode input) {
        return TaskActivityDescription.describe(
            new ToolUseBlock("tool-use", name, input), cwd);
    }

    private static ObjectNode input() {
        return JsonUtils.getMapper().createObjectNode();
    }
}
