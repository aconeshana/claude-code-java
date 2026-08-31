package com.claudecode.commands.diff;

import com.claudecode.commands.diff.TurnDiffExtractor.TurnDiff;
import com.claudecode.commands.diff.TurnDiffExtractor.TurnFileDiff;
import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnDiffExtractorTest {

    private static UserMessage prompt(String text) {
        return new UserMessage("u-" + text.hashCode(), MessageContent.ofText(text));
    }

    private static UserMessage metaPrompt(String text) {
        return new UserMessage("meta-" + text.hashCode(), MessageContent.ofText(text),
            true, false, null, MessageOrigin.USER, null, Instant.now(), null, null, null);
    }

    private static UserMessage toolResult(Object payload) {
        return new UserMessage("tr-" + System.nanoTime(), MessageContent.ofText(""),
            false, false, payload, MessageOrigin.USER, null, Instant.now(), null, null, null);
    }

    private static AssistantMessage assistant(String text) {
        return new AssistantMessage("a-" + text.hashCode(),
            new AssistantContent("m1", List.of(new TextBlock(text)), null));
    }

    private static StructuredPatchHunk hunk(String... lines) {
        return new StructuredPatchHunk(1, lines.length, 1, lines.length, List.of(lines));
    }

    // ── single turn, Edit-shaped record payload ──────────────────────────────

    @Test
    void singleTurn_singleFileEdit() {
        List<Message> messages = List.of(
            prompt("fix the bug"),
            assistant("editing"),
            toolResult(FileChangeResult.edited("/src/A.java", List.of(hunk("+new line", "-old line", " ctx")))));

        List<TurnDiff> turns = TurnDiffExtractor.extract(messages);
        assertEquals(1, turns.size());

        TurnDiff turn = turns.getFirst();
        assertEquals(1, turn.turnIndex());
        assertEquals("fix the bug", turn.userPromptPreview());
        assertEquals(new DiffData.Stats(1, 1, 1), turn.stats());

        assertEquals(1, turn.files().size());
        TurnFileDiff file = turn.files().getFirst();
        assertEquals("/src/A.java", file.filePath());
        assertFalse(file.isNewFile());
        assertEquals(1, file.linesAdded());
        assertEquals(1, file.linesRemoved());
        assertEquals(1, file.hunks().size());
    }

    // ── create → synthetic hunk ──────────────────────────────────────────────

    @Test
    void createWithEmptyPatch_synthesizesHunkFromContent() {
        List<Message> messages = List.of(
            prompt("make a file"),
            toolResult(FileChangeResult.created("/tmp/new.txt", "alpha\nbeta\ngamma")));

        TurnDiff turn = TurnDiffExtractor.extract(messages).getFirst();
        TurnFileDiff file = turn.files().getFirst();
        assertTrue(file.isNewFile());
        assertEquals(3, file.linesAdded());
        assertEquals(0, file.linesRemoved());

        StructuredPatchHunk synthetic = file.hunks().getFirst();
        assertEquals(0, synthetic.oldStart());
        assertEquals(0, synthetic.oldLines());
        assertEquals(1, synthetic.newStart());
        assertEquals(3, synthetic.newLines());
        assertEquals(List.of("+alpha", "+beta", "+gamma"), synthetic.lines());
    }

    @Test
    void createContentWithTrailingNewline_keepsEmptyLastLineLikeTs() {
        List<Message> messages = List.of(
            prompt("make a file"),
            toolResult(FileChangeResult.created("/tmp/n.txt", "a\n")));

        TurnFileDiff file = TurnDiffExtractor.extract(messages).getFirst().files().getFirst();
        assertEquals(2, file.linesAdded());
        assertEquals(List.of("+a", "+"), file.hunks().getFirst().lines());
    }

    // ── Map-shaped payload (JSONL resume) ────────────────────────────────────

    @Test
    void mapPayload_fromResumedSession_isCoerced() {
        Map<String, Object> payload = Map.of(
            "filePath", "/src/B.java",
            "structuredPatch", List.of(Map.of(
                "oldStart", 5, "oldLines", 2, "newStart", 5, "newLines", 3,
                "lines", List.of(" ctx", "-gone", "+here", "+too"))));
        List<Message> messages = List.of(prompt("resume edit"), toolResult(payload));

        TurnDiff turn = TurnDiffExtractor.extract(messages).getFirst();
        TurnFileDiff file = turn.files().getFirst();
        assertEquals("/src/B.java", file.filePath());
        assertEquals(2, file.linesAdded());
        assertEquals(1, file.linesRemoved());
        assertEquals(5, file.hunks().getFirst().oldStart());
        assertEquals(3, file.hunks().getFirst().newLines());
    }

    @Test
    void mapPayload_createShape() {
        Map<String, Object> payload = Map.of(
            "filePath", "/tmp/c.txt",
            "structuredPatch", List.of(),
            "type", "create",
            "content", "x\ny");
        TurnDiff turn = TurnDiffExtractor.extract(
            List.of(prompt("write it"), toolResult(payload))).getFirst();
        TurnFileDiff file = turn.files().getFirst();
        assertTrue(file.isNewFile());
        assertEquals(2, file.linesAdded());
    }

    @Test
    void coerce_rejectsForeignPayloads() {
        assertNull(TurnDiffExtractor.coerce("just a string result"));
        assertNull(TurnDiffExtractor.coerce(null));
        assertNull(TurnDiffExtractor.coerce(42));
    }

    @Test
    void nonFileEditToolResults_areIgnored() {
        // Bash-style Map payload — no filePath, so not a file edit.
        List<Message> messages = List.of(
            prompt("run something"),
            toolResult(Map.of("stdout", "ok", "stderr", "")));
        assertTrue(TurnDiffExtractor.extract(messages).isEmpty());
    }

    // ── multi-turn grouping + reverse order ──────────────────────────────────

    @Test
    void multipleTurns_groupedAndReversed() {
        List<Message> messages = List.of(
            prompt("first task"),
            toolResult(FileChangeResult.edited("/a.txt", List.of(hunk("+a")))),
            prompt("second task"),
            toolResult(FileChangeResult.edited("/b.txt", List.of(hunk("+b1", "+b2")))));

        List<TurnDiff> turns = TurnDiffExtractor.extract(messages);
        assertEquals(2, turns.size());
        // Most recent first.
        assertEquals(2, turns.getFirst().turnIndex());
        assertEquals("second task", turns.getFirst().userPromptPreview());
        assertEquals("/b.txt", turns.getFirst().files().getFirst().filePath());
        assertEquals(1, turns.get(1).turnIndex());
        assertEquals("first task", turns.get(1).userPromptPreview());
        assertEquals("/a.txt", turns.get(1).files().getFirst().filePath());
    }

    @Test
    void turnIndex_countsPromptsWithoutEdits() {

        // edit-less turn is later dropped from the result.
        List<Message> messages = List.of(
            prompt("just chatting"),
            assistant("sure"),
            prompt("now edit"),
            toolResult(FileChangeResult.edited("/a.txt", List.of(hunk("+a")))));

        List<TurnDiff> turns = TurnDiffExtractor.extract(messages);
        assertEquals(1, turns.size());
        assertEquals(2, turns.getFirst().turnIndex());
    }

    // ── same file edited multiple times in one turn ──────────────────────────

    @Test
    void sameFileEditedTwice_hunksAppendAndCountsAccumulate() {
        List<Message> messages = List.of(
            prompt("iterate"),
            toolResult(FileChangeResult.edited("/a.txt", List.of(hunk("+one", "-gone")))),
            toolResult(FileChangeResult.edited("/a.txt", List.of(hunk("+two", "+three")))));

        TurnDiff turn = TurnDiffExtractor.extract(messages).getFirst();
        assertEquals(1, turn.files().size());
        TurnFileDiff file = turn.files().getFirst();
        assertEquals(2, file.hunks().size());
        assertEquals(3, file.linesAdded());
        assertEquals(1, file.linesRemoved());
        assertEquals(new DiffData.Stats(1, 3, 1), turn.stats());
    }

    @Test
    void createdThenEdited_staysNewFile() {
        List<Message> messages = List.of(
            prompt("create and tweak"),
            toolResult(FileChangeResult.created("/n.txt", "v1")),
            toolResult(FileChangeResult.edited("/n.txt", List.of(hunk("+v2", "-v1")))));

        TurnFileDiff file = TurnDiffExtractor.extract(messages).getFirst().files().getFirst();
        assertTrue(file.isNewFile());
        assertEquals(2, file.hunks().size());
        assertEquals(2, file.linesAdded());
        assertEquals(1, file.linesRemoved());
    }

    // ── meta / empty turns ───────────────────────────────────────────────────

    @Test
    void metaMessages_doNotStartTurns() {
        // The meta message must not close the first turn — the edit after it
        // still belongs to turn 1.
        List<Message> messages = List.of(
            prompt("real prompt"),
            metaPrompt("<system>injected context</system>"),
            toolResult(FileChangeResult.edited("/a.txt", List.of(hunk("+a")))));

        List<TurnDiff> turns = TurnDiffExtractor.extract(messages);
        assertEquals(1, turns.size());
        assertEquals(1, turns.getFirst().turnIndex());
        assertEquals("real prompt", turns.getFirst().userPromptPreview());
    }

    @Test
    void turnsWithoutFileEdits_areDropped() {
        List<Message> messages = List.of(
            prompt("hello"),
            assistant("hi there"),
            prompt("bye"));
        assertTrue(TurnDiffExtractor.extract(messages).isEmpty());
    }

    @Test
    void toolResultBeforeAnyPrompt_isIgnored() {
        List<Message> messages = List.of(
            toolResult(FileChangeResult.edited("/orphan.txt", List.of(hunk("+x")))));
        assertTrue(TurnDiffExtractor.extract(messages).isEmpty());
    }

    // ── prompt preview ───────────────────────────────────────────────────────

    @Test
    void promptPreview_truncatedTo29CharsPlusEllipsis() {
        String longPrompt = "abcdefghijklmnopqrstuvwxyz0123456789"; // 36 chars
        List<Message> messages = List.of(
            prompt(longPrompt),
            toolResult(FileChangeResult.edited("/a.txt", List.of(hunk("+a")))));

        String preview = TurnDiffExtractor.extract(messages).getFirst().userPromptPreview();
        assertEquals(longPrompt.substring(0, 29) + "…", preview);
        assertEquals(30, preview.length());
    }

    @Test
    void promptPreview_exactly30Chars_notTruncated() {
        String prompt30 = "x".repeat(30);
        List<Message> messages = List.of(
            prompt(prompt30),
            toolResult(FileChangeResult.edited("/a.txt", List.of(hunk("+a")))));
        assertEquals(prompt30, TurnDiffExtractor.extract(messages).getFirst().userPromptPreview());
    }

    @Test
    void promptPreview_blockContent_isEmptyStringLikeTs() {

        UserMessage blockPrompt = new UserMessage("u-blocks",
            MessageContent.ofBlocks(List.of(new TextBlock("block text"))));
        List<Message> messages = List.of(
            blockPrompt,
            toolResult(FileChangeResult.edited("/a.txt", List.of(hunk("+a")))));
        assertEquals("", TurnDiffExtractor.extract(messages).getFirst().userPromptPreview());
    }
}
