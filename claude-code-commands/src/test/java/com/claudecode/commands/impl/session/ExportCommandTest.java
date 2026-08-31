package com.claudecode.commands.impl.session;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ExportCommandTest {

    private static UserMessage user(String text) {
        return new UserMessage("u-" + text.hashCode(), MessageContent.ofText(text));
    }

    private static AssistantMessage assistant(String text) {
        return new AssistantMessage("a-" + text.hashCode(),
            new AssistantContent("m", List.of(new TextBlock(text)), null));
    }

    private static CommandContext ctx(List<Message> messages, String cwd) {
        return CommandContext.builder(
            "m", () -> messages, () -> {}, _ -> {}, () -> Usage.EMPTY, _ -> 0.0, cwd, false)
            .build();
    }

    // ── metadata ─────────────────────────────────────────────────────────────

    @Test
    void metadata_matchesTs() {
        ExportCommand cmd = new ExportCommand();
        assertEquals("export", cmd.name());
        assertEquals("Export the current conversation to a file or clipboard", cmd.description());
        assertTrue(cmd.aliases().isEmpty());
    }

    // ── extractFirstPrompt ───────────────────────────────────────────────────

    @Test
    void extractFirstPrompt_firstLine50CharCap() {
        assertEquals("hello world",
            ExportCommand.extractFirstPrompt(List.of(user("hello world\nsecond line"))));
        String long60 = "x".repeat(60);
        String result = ExportCommand.extractFirstPrompt(List.of(user(long60)));
        assertEquals(50, result.length());
        assertTrue(Strings.CS.endsWith(result, "…"));
    }

    @Test
    void extractFirstPrompt_onlyLooksAtFirstUserMessage() {

        // yields '' even when a later user message has text.
        UserMessage toolResultOnly = new UserMessage("u1",
            MessageContent.ofToolResult("t1", List.of(new TextBlock("out")), false));
        assertEquals("", ExportCommand.extractFirstPrompt(
            List.of(toolResultOnly, user("real question"))));
    }

    // ── sanitizeFilename ─────────────────────────────────────────────────────

    @Test
    void sanitizeFilename_matchesTsRegexes() {
        assertEquals("fix-the-bug", ExportCommand.sanitizeFilename("Fix the BUG!"));
        assertEquals("a-b", ExportCommand.sanitizeFilename("a---b"));
        assertEquals("ab", ExportCommand.sanitizeFilename("-ab-"));
        // \s covers tabs too (not just spaces).
        assertEquals("a-b", ExportCommand.sanitizeFilename("a\tb"));
        assertEquals("", ExportCommand.sanitizeFilename("!!!"));
    }

    // ── renderToPlainText ────────────────────────────────────────────────────

    @Test
    void render_transcriptShapedOutput() {
        ToolUseBlock use = new ToolUseBlock("t1", "Bash",
            new ObjectMapper().createObjectNode().put("command", "ls -la"));
        AssistantMessage withTool = new AssistantMessage("a1",
            new AssistantContent("m", List.of(new TextBlock("Let me check."), use), null));
        UserMessage toolResult = new UserMessage("u2",
            MessageContent.ofToolResult("t1", List.of(new TextBlock("file1\nfile2")), false));

        String out = ExportCommand.renderToPlainText(
            List.of(user("list files"), withTool, toolResult, assistant("Done.")));
        assertTrue(Strings.CS.contains(out, "> list files"), out);
        assertTrue(Strings.CS.contains(out, "● Let me check."), out);
        assertTrue(Strings.CS.contains(out, "● Bash(ls -la)"), out);
        assertTrue(Strings.CS.contains(out, "  ⎿  file1"), out);
        assertTrue(Strings.CS.contains(out, "     file2"), out);
        assertTrue(Strings.CS.contains(out, "● Done."), out);
    }

    @Test
    void render_skipsApiErrorMessages() {
        AssistantMessage error = new AssistantMessage("e",
            new AssistantContent("m", List.of(new TextBlock("boom")), null),
            true, null, Instant.now());
        assertFalse(Strings.CS.contains(ExportCommand.renderToPlainText(List.of(error)), "boom"));
    }

    // ── execute routing ──────────────────────────────────────────────────────

    @Test
    void withFilename_writesFileDirectly(@TempDir Path dir) throws Exception {
        CommandResult r = new ExportCommand().execute(
            ctx(List.of(user("hi"), assistant("yo")), dir.toString()), "myfile");
        Path expected = dir.resolve("myfile.txt");
        assertTrue(Files.exists(expected), r.output());
        assertEquals("Conversation exported to: " + expected.toAbsolutePath(), r.output());
        assertTrue(Strings.CS.contains(Files.readString(expected), "> hi"));
    }

    @Test
    void filenameExtension_forcedToTxt(@TempDir Path dir) {
        new ExportCommand().execute(ctx(List.of(user("x")), dir.toString()), "notes.md");
        assertTrue(Files.exists(dir.resolve("notes.txt")));
        assertFalse(Files.exists(dir.resolve("notes.md")));
    }

    @Test
    void missingParentDirectory_failsLikeTs(@TempDir Path dir) {
        CommandResult r = new ExportCommand().execute(
            ctx(List.of(user("x")), dir.toString()), "no-such-dir/file");
        assertTrue(Strings.CS.startsWith(r.output(), "Failed to export conversation: "), r.output());
    }

    @Test
    void noArgs_withLauncher_handsOffContent() {
        AtomicReference<String> received = new AtomicReference<>();
        CommandContext ctx = CommandContext.builder(
            "m", () -> List.of(user("hi")), () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, "/tmp", false)
            .exportDialogLauncher(received::set)
            .build();
        CommandResult r = new ExportCommand().execute(ctx, "");
        assertTrue(r.silent());
        assertTrue(Strings.CS.contains(received.get(), "> hi"));
    }

    @Test
    void noArgs_headless_defaultFilenameFromFirstPrompt(@TempDir Path dir) {
        new ExportCommand().execute(ctx(List.of(user("Fix the Bug")), dir.toString()), "");
        try (var files = Files.list(dir).filter(p -> Strings.CS.endsWith(p.toString(), ".txt"))) {
            String name = files.findFirst().orElseThrow().getFileName().toString();
            assertTrue(name.matches("\\d{4}-\\d{2}-\\d{2}-\\d{6}-fix-the-bug\\.txt"), name);
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void emptyConversation_exportsEmptyFileNoGuard(@TempDir Path dir) {

        CommandResult r = new ExportCommand().execute(ctx(List.of(), dir.toString()), "empty");
        assertTrue(Strings.CS.startsWith(r.output(), "Conversation exported to: "), r.output());
        assertTrue(Files.exists(dir.resolve("empty.txt")));
    }
}
