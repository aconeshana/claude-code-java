package com.claudecode.commands.impl.terminal;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.impl.terminal.CopyCommand.CodeBlock;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.commands.testing.FakeSettingsManagementPort;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class CopyCommandTest {

    private Predicate<String> realClipboard;
    private final List<String> clipboardWrites = new ArrayList<>();

    @BeforeEach
    void stubClipboard() {
        // Don't clobber the developer's real clipboard while the suite runs.
        realClipboard = CopyCommand.clipboardDelivery;
        CopyCommand.clipboardDelivery = text -> {
            clipboardWrites.add(text);
            return true;
        };
    }

    @AfterEach
    void restoreClipboard() {
        CopyCommand.clipboardDelivery = realClipboard;
    }

    private static AssistantMessage assistant(String text) {
        return new AssistantMessage("a-" + text.hashCode(),
            new AssistantContent("m1", List.of(new TextBlock(text)), null));
    }


    private static CopyCommand cmd() {
        return new CopyCommand(() -> false);
    }

    private static CommandContext ctxWith(List<Message> messages) {
        return CommandContext.builder(
            "m", () -> messages, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, "/tmp", false).build();
    }

    // ── metadata ─────────────────────────────────────────────────────────────

    @Test
    void metadata_noInventedAliases() {
        CopyCommand cmd = cmd();
        assertEquals("copy", cmd.name());
        assertEquals("Copy Claude's last response to clipboard (or /copy N for the Nth-latest)",
            cmd.description());
        assertTrue(cmd.aliases().isEmpty(), "TS has no aliases — the old 'yank' was invented");
    }

    // ── collectRecentAssistantTexts ──────────────────────────────────────────

    @Test
    void collect_walksNewestFirstAndSkipsErrors() {
        List<Message> messages = List.of(
            assistant("oldest"),
            new AssistantMessage("err",
                new AssistantContent("m", List.of(new TextBlock("api error")), null),
                true, null, Instant.now()),
            new UserMessage("u1", MessageContent.ofText("q")),
            assistant("newest"));
        List<String> texts = CopyCommand.collectRecentAssistantTexts(messages);
        assertEquals(List.of("newest", "oldest"), texts);
    }

    @Test
    void collect_capsAtMaxLookback() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 25; i++) messages.add(assistant("msg" + i));
        assertEquals(20, CopyCommand.collectRecentAssistantTexts(messages).size());
    }

    @Test
    void collect_joinsTextBlocksWithDoubleNewline() {
        AssistantMessage multi = new AssistantMessage("a1",
            new AssistantContent("m1",
                List.of(new TextBlock("part one"), new TextBlock("part two")), null));
        List<String> texts = CopyCommand.collectRecentAssistantTexts(List.of(multi));
        assertEquals(List.of("part one\n\npart two"), texts);
    }

    // ── arg routing (error copy verbatim) ────────────────────────────────────

    @Test
    void noMessages_reportsNothingToCopy() {
        CommandResult r = cmd().execute(ctxWith(List.of()), "");
        assertEquals("No assistant message to copy", r.output());
    }

    @Test
    void invalidArg_usageError() {
        CommandResult r = cmd().execute(ctxWith(List.of(assistant("hi"))), "abc");
        assertEquals("Usage: /copy [N] where N is 1 (latest), 2, 3, … Got: abc", r.output());
        assertEquals("Usage: /copy [N] where N is 1 (latest), 2, 3, … Got: 0",
            cmd().execute(ctxWith(List.of(assistant("hi"))), "0").output());
        assertEquals("Usage: /copy [N] where N is 1 (latest), 2, 3, … Got: 1.5",
            cmd().execute(ctxWith(List.of(assistant("hi"))), "1.5").output());
    }

    @Test
    void argBeyondAvailable_reportsCount() {
        assertEquals("Only 1 assistant message available to copy",
            cmd().execute(ctxWith(List.of(assistant("hi"))), "2").output());
        assertEquals("Only 2 assistant messages available to copy",
            cmd().execute(
                ctxWith(List.of(assistant("a"), assistant("b"))), "5").output());
    }

    // ── launcher routing ─────────────────────────────────────────────────────

    @Test
    void launcher_receivesBlocksAndSkipFlagForPlainText() {
        AtomicReference<Object[]> captured = new AtomicReference<>();
        CommandContext ctx = CommandContext.builder(
            "m", () -> List.of(assistant("no code here")), () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, "/tmp", false)
            .copyPickerLauncher((text, blocks, skip) ->
                captured.set(new Object[]{text, blocks, skip}))
            .build();
        CommandResult r = cmd().execute(ctx, "");
        assertTrue(r.silent());
        assertEquals("no code here", captured.get()[0]);
        assertTrue(((List<?>) captured.get()[1]).isEmpty());
        assertEquals(true, captured.get()[2], "no code blocks → skipPicker");
    }

    @Test
    void launcher_opensPickerWhenBlocksExist() {
        String md = "look:\n```java\nint x = 1;\n```\ndone";
        AtomicReference<Object[]> captured = new AtomicReference<>();
        CommandContext ctx = CommandContext.builder(
            "m", () -> List.of(assistant("old"), assistant(md)), () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, "/tmp", false)
            .copyPickerLauncher((text, blocks, skip) ->
                captured.set(new Object[]{text, blocks, skip}))
            .build();
        cmd().execute(ctx, "1");
        assertEquals(md, captured.get()[0]);
        assertEquals(1, ((List<?>) captured.get()[1]).size());
        assertEquals(false, captured.get()[2], "code blocks present → show picker");
    }

    @Test
    void launcher_receivesSelectedSecondLatestText() {
        AtomicReference<Object[]> captured = new AtomicReference<>();
        CommandContext ctx = CommandContext.builder(
            "m", () -> List.of(assistant("older"), assistant("newer")), () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, "/tmp", false)
            .copyPickerLauncher((text, blocks, skip) ->
                captured.set(new Object[]{text, blocks, skip}))
            .build();
        cmd().execute(ctx, "2");
        assertEquals("older", captured.get()[0]);
    }

    // ── extractCodeBlocks ────────────────────────────────────────────────────

    @Test
    void extract_fencedBlocksWithLang() {
        List<CodeBlock> blocks = CopyCommand.extractCodeBlocks(
            "text\n```python\nprint('hi')\nprint('bye')\n```\nmore\n```\nplain\n```");
        assertEquals(2, blocks.size());
        assertEquals("print('hi')\nprint('bye')", blocks.getFirst().code());
        assertEquals("python", blocks.getFirst().lang());
        assertEquals("plain", blocks.get(1).code());
        assertNull(blocks.get(1).lang());
    }

    @Test
    void extract_indentedBlocks() {
        List<CodeBlock> blocks = CopyCommand.extractCodeBlocks(
            "para\n\n    indented code\n    line two\n\npara");
        assertEquals(1, blocks.size());
        assertEquals("indented code\nline two", blocks.getFirst().code());
        assertNull(blocks.getFirst().lang());
    }

    @Test
    void extract_ignoresStrippedXmlTagRegions() {

        List<CodeBlock> blocks = CopyCommand.extractCodeBlocks(
            "<commit_analysis>\n```sh\nsecret\n```\n</commit_analysis>\n```js\nvisible\n```");
        assertEquals(1, blocks.size());
        assertEquals("visible", blocks.getFirst().code());
        assertEquals("js", blocks.getFirst().lang());
    }

    @Test
    void extract_noBlocks() {
        assertTrue(CopyCommand.extractCodeBlocks("just some `inline code` text").isEmpty());
    }

    // ── fileExtension ────────────────────────────────────────────────────────

    @Test
    void fileExtension_sanitizesAndDefaults() {
        assertEquals(".python", CopyCommand.fileExtension("python"));
        assertEquals(".tsx", CopyCommand.fileExtension("tsx"));
        assertEquals(".txt", CopyCommand.fileExtension(null));
        assertEquals(".txt", CopyCommand.fileExtension("plaintext"));
        assertEquals(".txt", CopyCommand.fileExtension("!!!"));
        // Path traversal guard: non-alphanumerics stripped.
        assertEquals(".etcpasswd", CopyCommand.fileExtension("../../etc/passwd"));
    }

    // ── applyCopy ────────────────────────────────────────────────────────────

    @Test
    void applyCopy_writeOnly_writesFileWithoutClipboardMessage() {
        String result = CopyCommand.applyCopy("hello\nworld", "copy.txt", false, true);
        assertTrue(Strings.CS.startsWith(result, "Written to "), result);
        assertTrue(Strings.CS.endsWith(result, "copy.txt"), result);
    }

    @Test
    void applyCopy_reportsCharsLinesAndFile() {
        String result = CopyCommand.applyCopy("ab\ncd", "response.md", false, false);
        assertTrue(Strings.CS.startsWith(result, "Copied to clipboard (5 characters, 2 lines)"), result);
        assertTrue(Strings.CS.contains(result, "Also written to "), result);
        assertTrue(Strings.CS.endsWith(result, "response.md"), result);
    }

    @Test
    void countLines_matchesTsCountCharInStringPlusOne() {
        assertEquals(1, CopyCommand.countLines("no newline"));
        assertEquals(3, CopyCommand.countLines("a\nb\nc"));
        assertEquals(2, CopyCommand.countLines("trailing\n"));
    }

    @Test
    void copyFullResponseEnabled_skipsPickerEvenWithBlocks() {
        String md = "```java\nint x = 1;\n```";
        AtomicReference<Object[]> captured = new AtomicReference<>();
        CommandContext ctx = CommandContext.builder(
            "m", () -> List.of(assistant(md)), () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, "/tmp", false)
            .copyPickerLauncher((text, blocks, skip) ->
                captured.set(new Object[]{text, blocks, skip}))
            .build();
        new CopyCommand(() -> true).execute(ctx, "");
        assertEquals(true, captured.get()[2],
            "copyFullResponse=true must skip the picker (TS call() short-circuit)");
    }

    @Test
    void applyCopy_alwaysPreference_persistsAndAppendsHint(@TempDir Path dir) {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        String result = CopyCommand.applyCopy(
            "text", "response.md", true, false, settings.preferences());
        assertTrue(Strings.CS.contains(result, "Preference saved. Use /config to change copyFullResponse"),
            result);
        assertTrue(settings.copyFullResponse);
        assertEquals(List.of("text"), clipboardWrites);
    }
}
