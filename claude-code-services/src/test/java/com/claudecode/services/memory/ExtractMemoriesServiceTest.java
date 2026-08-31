package com.claudecode.services.memory;

import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class ExtractMemoriesServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static UserMessage userMsg(String uuid) {
        return new UserMessage(uuid, MessageContent.ofText("hi"));
    }

    private static AssistantMessage assistantMsg(String uuid) {
        return new AssistantMessage(uuid, AssistantContent.of(List.of(new TextBlock("ok"))));
    }

    private static AssistantMessage assistantWithToolUse(String uuid, String toolName, String filePath) {
        ObjectNode input = MAPPER.createObjectNode().put("file_path", filePath);
        ToolUseBlock block = new ToolUseBlock("tu-" + uuid, toolName, input);
        return new AssistantMessage(uuid, AssistantContent.of(List.of(block)));
    }

    // ── countModelVisibleMessagesSince ─────────────────────────────────────

    @Test
    void countsOnlyUserAndAssistantMessages() {
        List<Message> messages = List.of(
            userMsg("u1"), assistantMsg("a1"),
            new SystemMessage("s1", "info", "info", "noise"),
            userMsg("u2"));

        int count = ExtractMemoriesService.countModelVisibleMessagesSince(messages, null);

        assertEquals(3, count);
    }

    @Test
    void countsOnlyMessagesAfterCursor() {
        List<Message> messages = List.of(userMsg("u1"), assistantMsg("a1"), userMsg("u2"), assistantMsg("a2"));

        int count = ExtractMemoriesService.countModelVisibleMessagesSince(messages, "a1");

        assertEquals(2, count); // u2, a2 — not u1/a1, not a1 itself
    }

    @Test
    void fallsBackToFullCountWhenCursorNotFound() {
        List<Message> messages = List.of(userMsg("u1"), assistantMsg("a1"));

        int count = ExtractMemoriesService.countModelVisibleMessagesSince(messages, "missing-uuid");

        assertEquals(2, count);
    }

    // ── hasMemoryWritesSince ─────────────────────────────────────────────────

    // hasMemoryWritesSince's third param is a *working directory* — isAutoMemPath
    // resolves the real auto-memory dir from it internally (git-root + ~/.claude/
    // projects/<sanitized>/memory), it is NOT the memory dir itself.

    @Test
    void detectsMemoryWriteSinceCursor(@TempDir Path workingDir) {
        Path memDir = AutoMemoryPrompt.resolveAutoMemPath(workingDir);
        List<Message> messages = List.of(
            userMsg("u1"),
            assistantWithToolUse("a1", "Write", memDir.resolve("note.md").toString()));

        assertTrue(ExtractMemoriesService.hasMemoryWritesSince(messages, "u1", workingDir));
    }

    @Test
    void ignoresWritesOutsideMemoryDir(@TempDir Path workingDir) {
        List<Message> messages = List.of(
            userMsg("u1"),
            assistantWithToolUse("a1", "Write", "/etc/passwd"));

        assertFalse(ExtractMemoriesService.hasMemoryWritesSince(messages, "u1", workingDir));
    }

    @Test
    void ignoresWritesBeforeCursor(@TempDir Path workingDir) {
        Path memDir = AutoMemoryPrompt.resolveAutoMemPath(workingDir);
        List<Message> messages = List.of(
            assistantWithToolUse("a1", "Write", memDir.resolve("note.md").toString()),
            userMsg("u1"));

        // Cursor is u1 — the write at a1 happened BEFORE the cursor, must not count.
        assertFalse(ExtractMemoriesService.hasMemoryWritesSince(messages, "u1", workingDir));
    }

    @Test
    void ignoresNonEditWriteTools(@TempDir Path workingDir) {
        Path memDir = AutoMemoryPrompt.resolveAutoMemPath(workingDir);
        List<Message> messages = List.of(
            userMsg("u1"),
            assistantWithToolUse("a1", "Read", memDir.resolve("note.md").toString()));

        assertFalse(ExtractMemoriesService.hasMemoryWritesSince(messages, "u1", workingDir));
    }

    // ── extractAsync default-off contract ───────────────────────────────────

    @Test
    void offByDefault_doesNotThrowOrBlock() {
        // extractMemoriesEnabled is unset in the test environment (default false) —
        // extractAsync must return immediately without touching the filesystem,
        // constructing a sub-DefaultQuerySession, or throwing.
        var service = new ExtractMemoriesService(null, null);
        assertDoesNotThrow(() -> service.extractAsync(List.of(userMsg("u1")), null));
        // drainPending on an idle service must also be a safe no-op.
        assertDoesNotThrow(() -> service.drainPending(100));
    }
}
