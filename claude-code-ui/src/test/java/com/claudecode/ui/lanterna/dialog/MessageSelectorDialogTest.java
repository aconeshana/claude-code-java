package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.components.SpinnerFrames;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import org.apache.commons.lang3.Strings;
import java.time.Instant;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MessageSelectorDialog} — drives {@link MessageSelectorDialog#handleKey}
 * directly (no real GUI thread needed, same pattern as {@code AddDirDialogTest}) and reads the
 * outcome via the {@link MessageSelectorDialog#show} result callback.
 */
class MessageSelectorDialogTest {

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static UserMessage realUser(String text) {
        return new UserMessage("u-" + text.hashCode(), MessageContent.ofText(text));
    }

    private static UserMessage equalCopy(UserMessage message) {
        return new UserMessage(
            message.uuid(), message.message(), message.isMeta(), message.isCompactSummary(),
            message.toolUseResult(), message.origin(), message.parentUuidValue(),
            message.timestampValue(), message.imagePasteIds(), message.permissionMode(),
            message.sessionIdValue(), message.sourceToolAssistantUUID(), message.sourceToolUseID(),
            message.isVirtual(), message.mcpMeta(), message.isVisibleInTranscriptOnly(),
            message.planContent(), message.summarizeMetadata());
    }

    private static UserMessage metaUser(String text) {
        return new UserMessage("m", MessageContent.ofText(text), true, false, null,
            MessageOrigin.USER, null, Instant.now(), null, null, null);
    }

    private static UserMessage compactSummaryUser(String text) {
        return new UserMessage("cs", MessageContent.ofText(text), false, true, null,
            MessageOrigin.USER, null, Instant.now(), null, null, null);
    }

    private static UserMessage toolResultUser() {
        return new UserMessage("tr", MessageContent.ofToolResult(
            "tool-1", List.of(new TextBlock("ok")), false));
    }

    /** No-op executor for tests that never reach the Summarize path. */
    private static final MessageSelectorDialog.SummarizeExecutor NOOP_EXECUTOR =
        (_, _, _, _, _) -> {};

    private static void key(MessageSelectorDialog d, KeyStroke k) {
        d.handleKey(k, new AtomicBoolean(true));
    }

    private static void repeatKey(MessageSelectorDialog dialog, KeyStroke key, int count) {
        for (int index = 0; index < count; index++) {
            key(dialog, key);
        }
    }



    @Test
    void isSelectable_plainUserText_true() {
        assertTrue(MessageSelectorDialog.isSelectable(realUser("fix the bug")));
    }

    @Test
    void isSelectable_metaMessage_false() {
        assertFalse(MessageSelectorDialog.isSelectable(metaUser("hidden")));
    }

    @Test
    void isSelectable_compactSummary_false() {
        assertFalse(MessageSelectorDialog.isSelectable(compactSummaryUser("summary")));
    }

    @Test
    void isSelectable_visibleInTranscriptOnly_false() {
        UserMessage visibleOnly = new UserMessage(
            "visible-only", MessageContent.ofText("hidden"), false, false, null,
            MessageOrigin.USER, null, Instant.now(), null, null, null, null, null,
            null, null, true);

        assertFalse(MessageSelectorDialog.isSelectable(visibleOnly));
    }

    @Test
    void isSelectable_toolResultWrapper_false() {
        assertFalse(MessageSelectorDialog.isSelectable(toolResultUser()));
    }

    @Test
    void isSelectable_syntheticInterruptMessage_false() {
        assertFalse(MessageSelectorDialog.isSelectable(realUser(MessageConstants.INTERRUPT_MESSAGE)));
    }

    @Test
    void isSelectable_syntheticRejectMessage_false() {
        assertFalse(MessageSelectorDialog.isSelectable(realUser(MessageConstants.REJECT_MESSAGE)));
    }

    @Test
    void isSelectable_localCommandStdout_false() {
        assertFalse(MessageSelectorDialog.isSelectable(
            realUser("<local-command-stdout>ok</local-command-stdout>")));
    }

    @Test
    void isSelectable_localCommandStderr_false() {
        assertFalse(MessageSelectorDialog.isSelectable(
            realUser("<local-command-stderr>oops</local-command-stderr>")));
    }

    @Test
    void isSelectable_bashStdout_false() {
        assertFalse(MessageSelectorDialog.isSelectable(
            realUser("<bash-stdout>total 0</bash-stdout>")));
    }

    @Test
    void isSelectable_bashStderr_false() {
        assertFalse(MessageSelectorDialog.isSelectable(
            realUser("<bash-stderr>not found</bash-stderr>")));
    }

    @Test
    void isSelectable_taskNotification_false() {
        assertFalse(MessageSelectorDialog.isSelectable(
            realUser("<task-notification>done</task-notification>")));
    }

    @Test
    void isSelectable_tick_false() {
        assertFalse(MessageSelectorDialog.isSelectable(realUser("<tick>1</tick>")));
    }

    @Test
    void isSelectable_bareTeammateMarkupTypedByUser_true() {
        assertTrue(MessageSelectorDialog.isSelectable(
            realUser("<teammate-message>hi</teammate-message>")));
    }

    @Test
    void isSelectable_nonHumanOrigin_false() {
        UserMessage taskNotification = new UserMessage(
            "task", MessageContent.ofText("background task finished"), false, false, null,
            MessageOrigin.TASK_NOTIFICATION, null, Instant.now(), null, null);

        assertFalse(MessageSelectorDialog.isSelectable(taskNotification));
    }

    @Test
    void isSelectable_missingLegacyOrigin_true() {
        UserMessage legacyHuman = new UserMessage(
            "legacy", MessageContent.ofText("old transcript prompt"), false, false, null,
            null, null, Instant.now(), null, null);

        assertTrue(MessageSelectorDialog.isSelectable(legacyHuman));
    }

    @Test
    void isSelectable_teammateMessageWithAttributes_false() {
        assertFalse(MessageSelectorDialog.isSelectable(
            realUser("<teammate-message teammate_id=\"agent-1\">hi</teammate-message>")));
    }

    @Test
    void isSelectable_teammateMarkupEmbeddedInTypedText_true() {
        assertTrue(MessageSelectorDialog.isSelectable(
            realUser("please inspect <teammate-message teammate_id=\"agent-1\">this</teammate-message>")));
    }

    @Test
    void isSelectable_wrappedTeammateMessage_false() {
        assertFalse(MessageSelectorDialog.isSelectable(realUser(
            "Another Claude session sent a message while you were working:\n"
                + "<teammate-message teammate_id=\"agent-1\">hi</teammate-message>")));
    }

    @Test
    void isSelectable_underscoreTaskNotificationTypedByUser_true() {
        assertTrue(MessageSelectorDialog.isSelectable(
            realUser("<task_notification>done</task_notification>")));
    }

    @Test
    void isSelectable_blockMessagesInspectAllJoinedTextBlocksLike197() {
        UserMessage message = new UserMessage("blocks", MessageContent.ofBlocks(List.of(
            new TextBlock("<task-notification>old notification</task-notification>"),
            new TextBlock("actual user prompt"))));

        assertFalse(MessageSelectorDialog.isSelectable(message));
    }

    // ── idle / activation ───────────────────────────────────────────────────

    @Test
    void idle_hasZeroPreferredSize_andIsInactive() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        assertFalse(d.isActive());
        assertEquals(new TerminalSize(0, 0), d.calculatePreferredSize());
    }

    @Test
    void handleKey_noOpWhileIdle() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(ENTER, deliver);
        assertTrue(deliver.get());
        assertFalse(d.isActive());
    }

    @Test
    void show_activatesDialog() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(realUser("hi")), NOOP_EXECUTOR, _ -> {});
        assertTrue(d.isActive());
    }

    @Test
    void liveMessageSourceRefreshesRowsAndKeepsTheReleasedNumericCursor() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage first = realUser("first prompt");
        UserMessage appended = realUser("appended while open");
        AtomicReference<List<Message>> live = new AtomicReference<>(List.of(first));
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(live::get, null, null, NOOP_EXECUTOR, result::set,
            null, null, null);

        live.set(List.of(first, appended));

        assertTrue(rendered(d).contains("appended while open"));
        key(d, ENTER);
        assertSame(appended, result.get().message(),
            "197 keeps the numeric cursor index when a live row is inserted before current");
    }

    @Test
    void liveMessageSourceDetectsStructurallyEqualIdentityReplacement() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage selected = realUser("selected prompt");
        AtomicReference<List<Message>> live = new AtomicReference<>(List.of(selected));
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(live::get, null, null, NOOP_EXECUTOR, result::set,
            null, null, null);
        key(d, UP);

        UserMessage replacement = equalCopy(selected);
        live.set(List.of(replacement));
        key(d, ENTER);

        assertSame(replacement, result.get().message(),
            "the selector must track React-style object identity, not record equality");
    }

    @Test
    void liveFileHistoryStateRefreshesRowsWithoutMessageChanges(@TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage selected = realUser("selected prompt");
        AtomicReference<List<Message>> live = new AtomicReference<>(List.of(selected));
        var fileHistory = newFileHistoryManager(backupRoot);
        d.show(live::get, fileHistory, null, NOOP_EXECUTOR, _ -> {},
            null, null, null);

        assertTrue(rendered(d).contains("⚠ No code restore"));

        fileHistory.makeSnapshot(selected.uuid());

        String refreshed = rendered(d);
        assertFalse(refreshed.contains("⚠ No code restore"));
        assertTrue(refreshed.contains("No code changes"),
            "197 reruns row metadata when its reactive file-history state changes");
    }

    @Test
    void imageOnlyHumanTurn_remainsSelectable() {
        var source = JsonNodeFactory.instance.objectNode()
            .put("type", "base64")
            .put("media_type", "image/png")
            .put("data", "abc123");
        UserMessage imageOnly = new UserMessage(
            "image-only", MessageContent.ofBlocks(List.of(new ImageBlock(source))));
        MessageSelectorDialog d = new MessageSelectorDialog();
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(List.of(imageOnly), NOOP_EXECUTOR, result::set);

        key(d, UP);
        key(d, ENTER);

        assertSame(imageOnly, result.get().message());
    }

    @Test
    void previousSessionVirtualRowResumesTheParentSession() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        AtomicBoolean resumed = new AtomicBoolean(false);
        d.show(List.of(realUser("hello")), null, null, NOOP_EXECUTOR, _ -> {},
            "parent-session", () -> resumed.set(true));

        repeatKey(d, UP, 2);
        assertTrue(rendered(d).contains("/resume parent-session (previous session)"));
        key(d, ENTER);

        assertTrue(resumed.get());
        assertFalse(d.isActive());
    }

    @Test
    void fullscreenVisibleWindowUsesHalfTerminalHeightAndShowsCountsAboveAndBelow() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.setTerminalRowsSupplier(() -> 20);
        List<Message> messages = java.util.stream.IntStream.range(0, 10)
            .mapToObj(index -> (Message) realUser("message " + index))
            .toList();
        d.show(messages, NOOP_EXECUTOR, _ -> {});

        assertTrue(rendered(d).contains("9 more above"));
        repeatKey(d, UP, 10);
        assertTrue(rendered(d).contains("9 more below"));
    }

    @Test
    void fileHistoryListShowsPerTurnDiffAndNoRestoreMetadata(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.setTerminalRowsSupplier(() -> 48);
        UserMessage first = realUser("first prompt");
        UserMessage second = realUser("second prompt");
        FileChangeResult edit = FileChangeResult.edited(
            backupRoot.resolve("changed.txt").toString(),
            List.of(new StructuredPatchHunk(1, 1, 1, 1, List.of("-old", "+new"))));
        UserMessage toolResult = new UserMessage(
            "tool-result", MessageContent.ofToolResult(
                "tool-1", List.of(new TextBlock("updated")), false),
            false, false, edit, MessageOrigin.TOOL_RESULT,
            null, Instant.now(), null, null);
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(first.uuid());
        d.show(List.of(first, toolResult, second), fhm, NOOP_EXECUTOR, _ -> {});

        String output = rendered(d);
        assertTrue(output.contains("Restore the code and/or conversation to the point before…"));
        assertTrue(output.contains("changed.txt +1 -1"));
        assertTrue(output.contains("⚠ No code restore"));
    }

    @Test
    void rawFileResultWithoutStructuredPatchIsIgnoredLike197(@TempDir Path backupRoot)
            throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage first = realUser("first prompt");
        UserMessage second = realUser("second prompt");
        UserMessage incompleteResult = new UserMessage(
            "tool-result", MessageContent.ofToolResult(
                "tool-1", List.of(new TextBlock("updated")), false),
            false, false, Map.of("filePath", "/tmp/ghost.txt"), MessageOrigin.TOOL_RESULT,
            null, Instant.now(), null, null);
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(first.uuid());

        d.show(List.of(first, incompleteResult, second), fhm, NOOP_EXECUTOR, _ -> {});

        assertFalse(rendered(d).contains("ghost.txt"));
        assertTrue(diffStats(List.of(first, incompleteResult, second),
            first.uuid(), second.uuid()).filesChanged().isEmpty());
    }

    @Test
    void rawCreateResultWithExplicitEmptyStructuredPatchStillCounts(
            @TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage first = realUser("first prompt");
        UserMessage second = realUser("second prompt");
        UserMessage createResult = new UserMessage(
            "tool-result", MessageContent.ofToolResult(
                "tool-1", List.of(new TextBlock("created")), false),
            false, false, Map.of(
                "filePath", "/tmp/created.txt",
                "structuredPatch", List.of(),
                "type", "create",
                "content", "one\ntwo"),
            MessageOrigin.TOOL_RESULT, null, Instant.now(), null, null);
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(first.uuid());

        d.show(List.of(first, createResult, second), fhm, NOOP_EXECUTOR, _ -> {});

        FileHistoryManager.DiffStats stats = diffStats(
            List.of(first, createResult, second), first.uuid(), second.uuid());
        assertEquals(List.of("/tmp/created.txt"), stats.filesChanged());
        assertEquals(2, stats.insertions());
        assertEquals(0, stats.deletions());
    }

    @Test
    void malformedTruthyStructuredPatchStillListsTheFileLike197() throws Exception {
        UserMessage first = realUser("first prompt");
        UserMessage second = realUser("second prompt");
        UserMessage malformedResult = new UserMessage(
            "tool-result", MessageContent.ofToolResult(
                "tool-1", List.of(new TextBlock("updated")), false),
            false, false, Map.of(
                "filePath", "/tmp/malformed-patch.txt",
                "structuredPatch", "not-an-array"),
            MessageOrigin.TOOL_RESULT, null, Instant.now(), null, null);

        FileHistoryManager.DiffStats stats = diffStats(
            List.of(first, malformedResult, second), first.uuid(), second.uuid());

        assertEquals(List.of("/tmp/malformed-patch.txt"), stats.filesChanged());
        assertEquals(0, stats.insertions());
        assertEquals(0, stats.deletions());
    }

    @Test
    void malformedCreateWithoutContentDoesNotFallThroughToPatchCountsLike197()
            throws Exception {
        UserMessage first = realUser("first prompt");
        UserMessage second = realUser("second prompt");
        FileChangeResult malformedCreate = new FileChangeResult(
            "/tmp/malformed-create.txt",
            List.of(new StructuredPatchHunk(1, 1, 1, 1, List.of("-old", "+new"))),
            "create", null, null, null, null, false, null);
        UserMessage toolResult = new UserMessage(
            "malformed-create-result", MessageContent.ofToolResult(
                "tool-1", List.of(new TextBlock("created")), false),
            false, false, malformedCreate, MessageOrigin.TOOL_RESULT,
            null, Instant.now(), null, null);

        FileHistoryManager.DiffStats stats = diffStats(
            List.of(first, toolResult, second), first.uuid(), second.uuid());

        assertEquals(List.of("/tmp/malformed-create.txt"), stats.filesChanged());
        assertEquals(0, stats.insertions());
        assertEquals(0, stats.deletions());
    }

    @Test
    void messageRowsUseOfficialDisplayFormatting() {
        UserMessage displayTag = realUser(
            "<system-reminder>hidden</system-reminder>\nvisible prompt");
        UserMessage bash = realUser("<bash-input>git status</bash-input>");
        UserMessage command = realUser("<command-message>model</command-message>\n"
            + "<command-args>opus</command-args>");
        UserMessage skill = realUser("<command-message>pdf</command-message>\n"
            + "<skill-format>true</skill-format>");
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.setTerminalRowsSupplier(() -> 44);
        d.show(List.of(displayTag, bash, command, skill), NOOP_EXECUTOR, _ -> {});

        String output = rendered(d);
        assertTrue(output.contains("visible prompt"));
        assertFalse(output.contains("hidden"));
        assertTrue(output.contains("! git status"));
        assertTrue(output.contains("/model opus"));
        assertTrue(output.contains("Skill(pdf)"));
    }

    @Test
    void commandMessageWithoutArgsKeepsTheOfficialTrailingSpace() throws Exception {
        UserMessage command = realUser("<command-message>clear</command-message>\n"
            + "<command-args></command-args>");
        Method display = MessageSelectorDialog.class.getDeclaredMethod(
            "messageDisplay", UserMessage.class, boolean.class, int.class);
        display.setAccessible(true);

        assertEquals("/clear ", display.invoke(null, command, true, 80));
    }

    @Test
    void blockMessageDisplayJoinsAllTextBlocksLike197() {
        UserMessage message = new UserMessage("blocks", MessageContent.ofBlocks(List.of(
            new TextBlock("earlier block"), new TextBlock("final block"))));
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(message), NOOP_EXECUTOR, _ -> {});

        String output = rendered(d);
        assertTrue(output.contains("earlier block…"), output);
        assertFalse(output.contains("final block"), output);
    }

    @Test
    void blockMessageDisplayKeepsTextWhenALaterBlockIsNotText() {
        UserMessage message = new UserMessage("blocks", MessageContent.ofBlocks(List.of(
            new TextBlock("earlier block"),
            new ImageBlock(JsonNodeFactory.instance.objectNode().put("type", "base64")))));
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(message), NOOP_EXECUTOR, _ -> {});

        String output = rendered(d);
        assertTrue(output.contains("earlier block"), output);
        assertFalse(output.contains("(no prompt)"), output);
    }

    @Test
    void emptyTextMessageUsesThe197EmptyMessageLabel() throws Exception {
        Method display = MessageSelectorDialog.class.getDeclaredMethod(
            "messageDisplay", UserMessage.class, boolean.class, int.class);
        display.setAccessible(true);

        assertEquals("((empty message))", display.invoke(null, realUser(""), true, 80));
    }

    @Test
    void messageRowTruncationTracksTheLiveTerminalWidth() {
        String prompt = "start " + "x".repeat(65) + " WIDE_TAIL";
        AtomicInteger columns = new AtomicInteger(60);
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.setTerminalColumnsSupplier(columns::get);
        d.show(List.of(realUser(prompt)), NOOP_EXECUTOR, _ -> {});

        String narrow = rendered(d);
        assertFalse(narrow.contains("WIDE_TAIL"));
        assertTrue(narrow.contains("…"), "truncated selector rows retain the 197 ellipsis");
        columns.set(100);
        String wide = rendered(d);
        assertTrue(wide.contains("WIDE_TAIL"), wide);
    }

    @Test
    void multilineMessageRowEndsAtTheFirstLineWithAnEllipsis() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(realUser("first line\nsecond line")), NOOP_EXECUTOR, _ -> {});

        String output = rendered(d);
        assertTrue(output.contains("first line…"));
        assertFalse(output.contains("second line"));
    }

    @Test
    void confirmationWithoutSnapshotNamesTheConversation(@TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        d.show(List.of(msg), newFileHistoryManager(backupRoot), NOOP_EXECUTOR, _ -> {});

        key(d, UP);
        key(d, ENTER);

        String output = rendered(d);
        assertTrue(output.contains("Confirm you want to restore the conversation"), output);
        assertTrue(output.contains("before you sent this message:"), output);
    }

    @Test
    void diffStatsUseSeparateAddedAndRemovedColors(@TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, _ -> {});

        key(d, UP);
        key(d, ENTER);
        BasicTextImage image = renderedImage(d);
        String output = imageText(image);
        int addedOffset = output.indexOf("+1 -1");
        assertTrue(addedOffset >= 0, output);
        int columns = image.getSize().getColumns() + 1;
        int addedRow = addedOffset / columns;
        int addedColumn = addedOffset % columns;
        int removedColumn = addedColumn + 3;
        TextColor added = image.getCharacterAt(addedColumn, addedRow).getForegroundColor();
        TextColor removed = image.getCharacterAt(removedColumn, addedRow).getForegroundColor();

        assertEquals(com.claudecode.ui.lanterna.theme.LanternaTheme.diffAddedWord(), added);
        assertEquals(com.claudecode.ui.lanterna.theme.LanternaTheme.diffRemovedWord(), removed);
    }

    @Test
    void confirmationUsesBorderAndUnquotedFullMessage(@TempDir Path backupRoot) {
        String prompt = "restore this complete message without clipping now";
        UserMessage msg = realUser(prompt);
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, _ -> {});

        key(d, UP);
        key(d, ENTER);

        String output = rendered(d);
        assertTrue(output.contains("│ " + prompt));
        assertFalse(output.contains("\"" + prompt.substring(0, 20)));
    }

    @Test
    void confirmationPreservesOnlyTheFirstFourPromptLines(@TempDir Path backupRoot) {
        UserMessage oneLine = realUser("line one");
        UserMessage multiline = realUser(
            "line one\nline two\nline three\nline four\nline five");
        var oneLineHistory = newFileHistoryManager(backupRoot.resolve("one"));
        oneLineHistory.makeSnapshot(oneLine.uuid());
        var multilineHistory = newFileHistoryManager(backupRoot.resolve("multi"));
        multilineHistory.makeSnapshot(multiline.uuid());
        MessageSelectorDialog oneLineDialog = new MessageSelectorDialog();
        oneLineDialog.show(List.of(oneLine), oneLineHistory, NOOP_EXECUTOR, _ -> {});
        MessageSelectorDialog multilineDialog = new MessageSelectorDialog();
        multilineDialog.show(List.of(multiline), multilineHistory, NOOP_EXECUTOR, _ -> {});

        key(oneLineDialog, UP);
        key(oneLineDialog, ENTER);
        key(multilineDialog, UP);
        key(multilineDialog, ENTER);

        String output = rendered(multilineDialog);
        assertTrue(output.contains("line one"));
        assertTrue(output.contains("line two"));
        assertTrue(output.contains("line three"));
        assertTrue(output.contains("line four"));
        assertFalse(output.contains("line five"));
        assertEquals(oneLineDialog.calculatePreferredSize().getRows() + 3,
            multilineDialog.calculatePreferredSize().getRows());
    }

    @Test
    void confirmationTrimsOuterWhitespaceBeforeApplyingTheFourLineLimit(
            @TempDir Path backupRoot) {
        UserMessage plain = realUser("trimmed prompt");
        UserMessage padded = realUser("  trimmed prompt  \n\n");
        var plainHistory = newFileHistoryManager(backupRoot.resolve("plain"));
        plainHistory.makeSnapshot(plain.uuid());
        var paddedHistory = newFileHistoryManager(backupRoot.resolve("padded"));
        paddedHistory.makeSnapshot(padded.uuid());
        MessageSelectorDialog plainDialog = new MessageSelectorDialog();
        plainDialog.show(List.of(plain), plainHistory, NOOP_EXECUTOR, _ -> {});
        MessageSelectorDialog paddedDialog = new MessageSelectorDialog();
        paddedDialog.show(List.of(padded), paddedHistory, NOOP_EXECUTOR, _ -> {});

        key(plainDialog, UP);
        key(plainDialog, ENTER);
        key(paddedDialog, UP);
        key(paddedDialog, ENTER);

        assertEquals(plainDialog.calculatePreferredSize().getRows(),
            paddedDialog.calculatePreferredSize().getRows());
        assertTrue(rendered(paddedDialog).contains("│ trimmed prompt"));
    }

    @Test
    void confirmationWrapsLongPromptInsteadOfClippingItsTail(@TempDir Path backupRoot) {
        String prompt = "prefix " + "x".repeat(70) + " tail-marker";
        UserMessage msg = realUser(prompt);
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, _ -> {});

        key(d, UP);
        key(d, ENTER);

        assertTrue(rendered(d).contains("tail-marker"));
    }

    @Test
    void restoreOptionsAreNumberedAndSummarizeInputIsInline(@TempDir Path backupRoot) {
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        AtomicReference<String> feedback = new AtomicReference<>();
        MessageSelectorDialog.SummarizeExecutor executor = (_, _, value, onSuccess, _) -> {
            feedback.set(value);
            onSuccess.run();
        };
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(msg), fhm, executor, _ -> {});

        key(d, UP);
        key(d, ENTER);
        assertTrue(rendered(d).contains("1. Restore conversation"));
        key(d, DOWN);
        assertTrue(rendered(d).contains("2. Summarize from here: add context (optional)"));
        for (char c : "2fa auth".toCharArray()) key(d, new KeyStroke(c, false, false));
        assertTrue(rendered(d).contains("2. Summarize from here: 2fa auth"));
        key(d, ENTER);

        assertEquals("2fa auth", feedback.get());
        assertFalse(d.isActive());
    }

    @Test
    void summarizeFeedbackKeepsTheCursorTailVisibleAtTheTerminalEdge(
            @TempDir Path backupRoot) {
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.setTerminalColumnsSupplier(() -> 60);
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, _ -> {});

        key(d, UP);
        key(d, ENTER);
        key(d, DOWN);
        String feedback = "prefix-" + "x".repeat(48) + "-tail";
        for (char character : feedback.toCharArray()) {
            key(d, new KeyStroke(character, false, false));
        }

        String output = rendered(d);
        assertTrue(output.contains("2. Summarize from here:"), output);
        assertTrue(output.contains("-tail"),
            "2.1.197 horizontally scrolls the focused inline input to keep its cursor visible\n"
                + output);
    }

    @Test
    void escapeWhileSummarizeInputIsFocusedReturnsToMessageList(@TempDir Path backupRoot) {
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, _ -> {});

        key(d, UP);
        key(d, ENTER);
        key(d, DOWN);
        key(d, ESC);

        assertTrue(d.isActive());
        assertTrue(rendered(d).contains("Restore the code and/or conversation to the point before…"));
    }

    @Test
    void numberKeySelectsRestoreOption(@TempDir Path backupRoot) {
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, result::set);

        key(d, UP);
        key(d, ENTER);
        key(d, new KeyStroke('1', false, false));

        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION,
            result.get().action());
    }

    // ── safe default selection (virtual "(current)" entry) ─────────────────────

    @Test
    void openingDialog_defaultCursorIsSafeCurrentEntry_doubleEnterDoesNothing() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        AtomicBoolean resultSet = new AtomicBoolean(false);
        d.show(List.of(msg), NOOP_EXECUTOR, sel -> { result.set(sel); resultSet.set(true); });


        // placeholder, so an immediate Enter (or two) must not start a real rewind.
        key(d, ENTER);
        assertTrue(resultSet.get(), "the default cursor must resolve (with null) rather than no-op silently");
        assertNull(result.get());
        assertFalse(d.isActive());
    }

    @Test
    void navigatingUpThenSelecting_entersOptionsAndCanRestore() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(List.of(msg), NOOP_EXECUTOR, result::set);

        key(d, UP);    // move off the trailing "(current)" row onto the real message
        key(d, ENTER); // pick it -> options phase, default "Restore conversation"
        key(d, ENTER); // confirm

        MessageSelectorDialog.Selection sel = result.get();
        assertSame(msg, sel.message());
        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION, sel.action());
        assertFalse(d.isActive());
    }

    @Test
    void ctrlUpRunsDefaultJumpToTopInsteadOfSingleStep() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage first = realUser("first");
        UserMessage second = realUser("second");
        UserMessage third = realUser("third");
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(List.of(first, second, third), NOOP_EXECUTOR, result::set);

        key(d, new KeyStroke(KeyType.ARROW_UP, true, false, false));
        key(d, ENTER);
        key(d, ENTER);

        assertSame(first, result.get().message());
    }

    @Test
    void customSelectBindingWorksAndNullUnbindSuppressesEnter(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"MessageSelector","bindings":{
                "space":"messageSelector:select",
                "enter":null
              }}
            ]
            """);
        UserKeybindingsStore store = createStore(file, true);
        try {
            MessageSelectorDialog d = new MessageSelectorDialog();
            d.setKeybindingsStore(store);
            UserMessage msg = realUser("hello");
            AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
            d.show(List.of(msg), NOOP_EXECUTOR, result::set);

            key(d, UP);
            key(d, ENTER);
            assertTrue(d.isActive(), "null-unbound Enter must not select the message");

            key(d, new KeyStroke(' ', false, false));
            key(d, ENTER); // options phase still uses its Select behavior
            assertSame(msg, result.get().message());
        } finally {
            store.dispose();
        }
    }

    @Test
    void selectBindingsDriveRestoreOptionsWithoutStealingInputCharacters(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Select","bindings":{
                "x":"select:next",
                "z":"select:accept"
              }}
            ]
            """);
        UserKeybindingsStore store = createStore(file, true);
        try {
            MessageSelectorDialog d = new MessageSelectorDialog();
            d.setKeybindingsStore(store);
            UserMessage msg = realUser("hello");
            AtomicReference<MessageSelectorDialog.RestoreAction> action = new AtomicReference<>();
            AtomicReference<String> feedback = new AtomicReference<>();
            MessageSelectorDialog.SummarizeExecutor executor =
                (_, selectedAction, value, onSuccess, _) -> {
                    action.set(selectedAction);
                    feedback.set(value);
                    onSuccess.run();
                };
            d.showPreselected(List.of(msg), null, msg, () -> {}, executor, _ -> {});

            key(d, new KeyStroke('x', false, false));
            key(d, new KeyStroke('j', false, false));
            key(d, new KeyStroke('z', false, false));

            assertEquals(MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM, action.get());
            assertEquals("j", feedback.get(),
                "the default j navigation binding is inactive while the inline input has focus");
            assertFalse(d.isActive());
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(Path file, boolean enabled) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, enabled);
    }

    @Test
    void escapeOnPickMessage_resolvesWithNull() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        AtomicBoolean resultSet = new AtomicBoolean(false);
        d.show(List.of(realUser("hi")), NOOP_EXECUTOR, sel -> { result.set(sel); resultSet.set(true); });

        key(d, ESC);

        assertTrue(resultSet.get());
        assertNull(result.get());
        assertFalse(d.isActive());
    }

    @Test
    void emptyMessageList_stillActivates_noRealEntriesToSelect() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(), NOOP_EXECUTOR, _ -> {});

        assertTrue(d.isActive());
        assertTrue(rendered(d).contains("Esc to cancel"));
        key(d, ENTER);
        assertTrue(d.isActive(), "197 leaves the empty selector open when Enter has no target");
        key(d, ESC);
        assertFalse(d.isActive());
    }

    @Test
    void messageListUsesThe197ContinueAndCancelFooter() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(realUser("hi")), NOOP_EXECUTOR, _ -> {});

        String output = rendered(d);
        assertTrue(output.contains("Enter to continue · Esc to cancel"));
        assertFalse(output.contains("Esc to exit"));
    }

    // ── options phase: never mind / summarize + feedback ────────────────────────

    @Test
    void neverMind_returnsToMessageListInsteadOfClosing(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        AtomicBoolean resultSet = new AtomicBoolean(false);
        d.show(List.of(msg), fhm, NOOP_EXECUTOR,
            sel -> { result.set(sel); resultSet.set(true); });

        key(d, UP);
        key(d, ENTER); // enter options phase
        key(d, UP); // released Select wraps from the first option to "Never mind"
        key(d, ENTER); // "Never mind" -> back to message list, not closed

        assertFalse(resultSet.get(), "Never mind must not resolve a selection");
        assertTrue(d.isActive(), "dialog stays open on the message list");

        // Confirm we're back on the message list and can still complete a real pick.
        key(d, UP);
        key(d, ENTER);
        key(d, ENTER);
        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION, result.get().action());
    }

    @Test
    void preselectedNeverMindClosesWithoutRunningPreRestore() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        AtomicBoolean preRestore = new AtomicBoolean(false);
        AtomicBoolean resultSet = new AtomicBoolean(false);
        d.showPreselected(List.of(msg), null, msg, () -> preRestore.set(true),
            NOOP_EXECUTOR, _ -> resultSet.set(true));

        key(d, UP); // released Select wraps from the first option to "Never mind"
        key(d, ENTER);

        assertFalse(preRestore.get());
        assertTrue(resultSet.get());
        assertFalse(d.isActive());
    }

    @Test
    void preselectedRestoreRunsPreRestoreOnlyWhenUserConfirms() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        AtomicBoolean preRestore = new AtomicBoolean(false);
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.showPreselected(List.of(msg), null, msg, () -> preRestore.set(true),
            NOOP_EXECUTOR, result::set);

        key(d, ENTER);

        assertTrue(preRestore.get());
        assertSame(msg, result.get().message());
        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION,
            result.get().action());
        assertFalse(d.isActive());
    }

    @Test
    void restoreOptionArrowNavigationWrapsLikeReleasedSelect() {
        MessageSelectorDialog cancelDialog = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        AtomicBoolean cancelResultSet = new AtomicBoolean(false);
        cancelDialog.showPreselected(List.of(msg), null, msg, () -> {},
            NOOP_EXECUTOR, _ -> cancelResultSet.set(true));

        key(cancelDialog, UP);
        key(cancelDialog, ENTER);

        assertTrue(cancelResultSet.get(), "up from the first option wraps to Never mind");
        assertFalse(cancelDialog.isActive());

        MessageSelectorDialog restoreDialog = new MessageSelectorDialog();
        AtomicReference<MessageSelectorDialog.Selection> restored = new AtomicReference<>();
        restoreDialog.showPreselected(List.of(msg), null, msg, () -> {},
            NOOP_EXECUTOR, restored::set);

        key(restoreDialog, UP);
        key(restoreDialog, DOWN);
        key(restoreDialog, ENTER);

        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION,
            restored.get().action(), "down from the last option wraps to the first");
    }

    @Test
    void restoreOptionHomeAndEndUseReleasedSelectBoundaries() {
        UserMessage msg = realUser("hello");

        MessageSelectorDialog endDialog = new MessageSelectorDialog();
        AtomicBoolean endResultSet = new AtomicBoolean(false);
        endDialog.showPreselected(List.of(msg), null, msg, () -> {},
            NOOP_EXECUTOR, _ -> endResultSet.set(true));
        key(endDialog, new KeyStroke(KeyType.END));
        key(endDialog, ENTER);
        assertTrue(endResultSet.get(), "End focuses Never mind, which closes a preselected menu");

        MessageSelectorDialog homeDialog = new MessageSelectorDialog();
        AtomicReference<MessageSelectorDialog.Selection> restored = new AtomicReference<>();
        homeDialog.showPreselected(List.of(msg), null, msg, () -> {},
            NOOP_EXECUTOR, restored::set);
        key(homeDialog, UP);
        key(homeDialog, new KeyStroke(KeyType.HOME));
        key(homeDialog, ENTER);
        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION,
            restored.get().action());
    }

    @Test
    void rawHomeAndEndDoNotActAsMessageSelectorTopOrBottomBindings() {
        UserMessage first = realUser("first");
        UserMessage second = realUser("second");

        MessageSelectorDialog homeDialog = new MessageSelectorDialog();
        AtomicBoolean homeResultSet = new AtomicBoolean(false);
        AtomicReference<MessageSelectorDialog.Selection> homeResult = new AtomicReference<>();
        homeDialog.show(List.of(first, second), NOOP_EXECUTOR, selection -> {
            homeResultSet.set(true);
            homeResult.set(selection);
        });
        key(homeDialog, new KeyStroke(KeyType.HOME));
        key(homeDialog, ENTER);
        assertTrue(homeResultSet.get());
        assertNull(homeResult.get(), "Home is not the released picker top binding");

        MessageSelectorDialog endDialog = new MessageSelectorDialog();
        AtomicReference<MessageSelectorDialog.Selection> endResult = new AtomicReference<>();
        endDialog.show(List.of(first, second), NOOP_EXECUTOR, endResult::set);
        key(endDialog, UP);
        key(endDialog, new KeyStroke(KeyType.END));
        key(endDialog, ENTER);
        assertSame(second, endResult.get().message(),
            "End is not the released picker bottom binding");
    }

    @Test
    void restoreStaysOpenUntilTheExecutorReportsSuccess(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        AtomicReference<Runnable> pendingSuccess = new AtomicReference<>();
        MessageSelectorDialog.RestoreExecutor executor = (_, _, onSuccess, _) ->
            pendingSuccess.set(onSuccess);
        d.show(List.of(msg), fhm, executor, NOOP_EXECUTOR, _ -> {});

        key(d, UP);
        key(d, ENTER);
        key(d, ENTER);

        assertTrue(d.isActive());
        assertTrue(rendered(d).contains("Restore conversation"));
        assertFalse(rendered(d).contains("❯"),
            "197 disables option focus while a confirmed restore is running");
        pendingSuccess.get().run();
        assertFalse(d.isActive());
    }

    @Test
    void directConversationRestoreCanBeCancelledWhileExecutorIsRunning() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        AtomicReference<Runnable> pendingSuccess = new AtomicReference<>();
        AtomicBoolean resultDelivered = new AtomicBoolean(false);
        MessageSelectorDialog.RestoreExecutor executor = (_, _, onSuccess, _) ->
            pendingSuccess.set(onSuccess);
        d.show(List.of(msg), null, executor, NOOP_EXECUTOR,
            _ -> resultDelivered.set(true));

        key(d, UP);
        key(d, ENTER);
        assertTrue(d.isActive());

        key(d, ESC);

        assertFalse(d.isActive(),
            "without a confirmation message, 197 leaves the modal cancel binding active");
        assertTrue(resultDelivered.get());
        pendingSuccess.get().run();
        assertFalse(d.isActive());
    }

    @Test
    void restoreFailureStaysInlineUntilEscape(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        MessageSelectorDialog.RestoreExecutor executor = (_, _, _, onFailure) ->
            onFailure.accept("Failed to restore the conversation:\nboom");
        d.show(List.of(msg), fhm, executor, NOOP_EXECUTOR, _ -> {});

        key(d, UP);
        key(d, ENTER);
        key(d, ENTER);

        assertTrue(d.isActive());
        String output = rendered(d);
        assertTrue(output.contains("Failed to restore the conversation:"));
        assertFalse(output.contains("Error: Failed to restore the conversation:"));
        assertTrue(output.contains("boom"));
        assertTrue(output.contains("Esc to cancel"));
        assertEquals(5, d.calculatePreferredSize().getRows());
        key(d, ESC);
        assertFalse(d.isActive());
    }

    @Test
    void preselectedSummarizeRunsPreRestoreBeforeExecutor() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        AtomicBoolean preRestore = new AtomicBoolean(false);
        AtomicBoolean executorSawPreRestore = new AtomicBoolean(false);
        MessageSelectorDialog.SummarizeExecutor executor =
            (_, _, _, onSuccess, _) -> {
                executorSawPreRestore.set(preRestore.get());
                onSuccess.run();
            };
        d.showPreselected(List.of(msg), null, msg, () -> preRestore.set(true),
            executor, _ -> {});

        key(d, DOWN);  // Summarize from here
        key(d, ENTER); // submit empty inline feedback

        assertTrue(executorSawPreRestore.get());
        assertFalse(d.isActive());
    }

    @Test
    void inFlightSummarizeSwallowsKeysInsteadOfLeakingIntoThePrompt() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        MessageSelectorDialog.SummarizeExecutor executor = (_, _, _, _, _) -> {};
        d.showPreselected(List.of(msg), null, msg, () -> {}, executor, _ -> {});

        key(d, DOWN);
        key(d, ENTER);
        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(new KeyStroke('x', false, false), deliver);

        assertFalse(deliver.get());
        assertTrue(d.isActive());
    }

    @Test
    void summarizeFrom_executorReceivesTypedFeedback_successHidesDialog(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        String[] capturedFeedback = {"not set"};
        MessageSelectorDialog.SummarizeExecutor executor = (message, action, feedback, onSuccess, _) -> {
            assertSame(msg, message);
            assertEquals(MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM, action);
            capturedFeedback[0] = feedback;
            onSuccess.run();
        };
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        AtomicBoolean resultSet = new AtomicBoolean(false);
        d.show(List.of(msg), fhm, executor,
            sel -> { result.set(sel); resultSet.set(true); });

        key(d, UP);
        key(d, ENTER); // options phase, default row 0 = "Restore conversation"
        key(d, new KeyStroke(KeyType.ARROW_DOWN)); // row 1 = "Summarize from here"

        for (char c : "focus on auth".toCharArray()) {
            key(d, new KeyStroke(c, false, false));
        }
        key(d, ENTER); // -> summarizing phase, executor runs synchronously above

        assertEquals("focus on auth", capturedFeedback[0]);
        assertTrue(resultSet.get());
        assertNull(result.get(), "Summarize resolves with a null Selection — it's handled via the executor");
        assertFalse(d.isActive(), "executor's onSuccess must hide the dialog");
    }

    @Test
    void summarizeFeedbackUsesReleasedSingleLinePasteAndCtrlAEditing(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        AtomicReference<String> feedback = new AtomicReference<>();
        MessageSelectorDialog.SummarizeExecutor executor = (_, _, value, onSuccess, _) -> {
            feedback.set(value);
            onSuccess.run();
        };
        d.show(List.of(msg), fhm, executor, _ -> {});

        key(d, UP);
        key(d, ENTER);
        key(d, DOWN);
        key(d, new PasteKeyStroke("world\nignored"));
        key(d, new KeyStroke('a', true, false));
        for (char character : "hello ".toCharArray()) {
            key(d, new KeyStroke(character, false, false));
        }
        key(d, ENTER);

        assertEquals("hello world", feedback.get());
    }

    @Test
    void summarizeFeedbackSupportsReleasedWordKillAndGraphemeEditing(
            @TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog wordDialog = new MessageSelectorDialog();
        UserMessage wordMessage = realUser("word");
        var wordHistory = newFileHistoryManager(
            Files.createDirectories(backupRoot.resolve("word")));
        wordHistory.makeSnapshot(wordMessage.uuid());
        AtomicReference<String> wordFeedback = new AtomicReference<>();
        MessageSelectorDialog.SummarizeExecutor wordExecutor = (_, _, value, onSuccess, _) -> {
            wordFeedback.set(value);
            onSuccess.run();
        };
        wordDialog.show(List.of(wordMessage), wordHistory, wordExecutor, _ -> {});
        key(wordDialog, UP);
        key(wordDialog, ENTER);
        key(wordDialog, DOWN);
        for (char character : "alpha beta".toCharArray()) {
            key(wordDialog, new KeyStroke(character, false, false));
        }
        key(wordDialog, new KeyStroke('w', true, false));
        key(wordDialog, ENTER);
        assertEquals("alpha", wordFeedback.get());

        MessageSelectorDialog graphemeDialog = new MessageSelectorDialog();
        UserMessage graphemeMessage = realUser("grapheme");
        var graphemeHistory = newFileHistoryManager(
            Files.createDirectories(backupRoot.resolve("grapheme")));
        graphemeHistory.makeSnapshot(graphemeMessage.uuid());
        AtomicReference<String> graphemeFeedback = new AtomicReference<>();
        MessageSelectorDialog.SummarizeExecutor graphemeExecutor = (_, _, value, onSuccess, _) -> {
            graphemeFeedback.set(value);
            onSuccess.run();
        };
        graphemeDialog.show(List.of(graphemeMessage), graphemeHistory, graphemeExecutor, _ -> {});
        key(graphemeDialog, UP);
        key(graphemeDialog, ENTER);
        key(graphemeDialog, DOWN);
        key(graphemeDialog, new PasteKeyStroke("e\u0301x"));
        key(graphemeDialog, new KeyStroke('b', true, false));
        key(graphemeDialog, new KeyStroke(KeyType.BACKSPACE));
        key(graphemeDialog, ENTER);
        assertEquals("x", graphemeFeedback.get());
    }

    @Test
    void summarizeFrom_emptyFeedbackPassedAsNull(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        String[] capturedFeedback = {"not set"};
        MessageSelectorDialog.SummarizeExecutor executor = (_, _, feedback, onSuccess, _) -> {
            capturedFeedback[0] = feedback;
            onSuccess.run();
        };
        d.show(List.of(msg), fhm, executor, _ -> {});

        key(d, UP);
        key(d, ENTER);
        key(d, new KeyStroke(KeyType.ARROW_DOWN));
        key(d, ENTER); // submit empty inline feedback

        assertNull(capturedFeedback[0]);
        assertFalse(d.isActive());
    }

    @Test
    void summarizeUpTo_isExposedAndUsesUpToDirection(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        AtomicReference<MessageSelectorDialog.RestoreAction> action = new AtomicReference<>();
        MessageSelectorDialog.SummarizeExecutor executor = (_, selectedAction, _, onSuccess, _) -> {
            action.set(selectedAction);
            onSuccess.run();
        };
        d.show(List.of(msg), fhm, executor, _ -> {});

        key(d, UP);
        key(d, ENTER);
        key(d, DOWN); // Summarize from here
        key(d, DOWN); // Summarize up to here
        key(d, ENTER);

        assertEquals(MessageSelectorDialog.RestoreAction.SUMMARIZE_UP_TO, action.get());
        assertFalse(d.isActive());
    }

    @Test
    void summarizeFeedbackPersistsWhenFocusMovesBetweenOptions(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        AtomicReference<String> feedback = new AtomicReference<>();
        MessageSelectorDialog.SummarizeExecutor executor = (_, action, value, onSuccess, _) -> {
            assertEquals(MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM, action);
            feedback.set(value);
            onSuccess.run();
        };
        d.show(List.of(msg), fhm, executor, _ -> {});

        key(d, UP);
        key(d, ENTER);
        key(d, new KeyStroke(KeyType.ARROW_DOWN)); // "Summarize from here"
        for (char c : "context".toCharArray()) key(d, new KeyStroke(c, false, false));
        key(d, DOWN); // "Summarize up to here"
        key(d, UP);   // back to "Summarize from here"
        key(d, ENTER);

        assertEquals("context", feedback.get());
        assertFalse(d.isActive());
    }

    @Test
    void summarize_staysOpenWhileExecutorHasNotReportedBack(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        AtomicReference<Runnable> pendingSuccess = new AtomicReference<>();
        MessageSelectorDialog.SummarizeExecutor executor = (_, _, _, onSuccess, _) ->
            pendingSuccess.set(onSuccess);
        d.show(List.of(msg), fhm, executor, _ -> {});

        key(d, UP);
        key(d, ENTER);
        key(d, new KeyStroke(KeyType.ARROW_DOWN));
        key(d, ENTER); // summarizing, executor "in flight" (hasn't called back yet)

        String output = rendered(d);
        assertTrue(output.contains("Rewind"));
        assertTrue(output.contains("Confirm you want to restore"));
        assertTrue(output.contains("hello"));
        assertTrue(output.contains("Summarizing…"));
        assertTrue(SpinnerFrames.defaultAnimationFrames().stream()
                .anyMatch(frame -> output.contains(frame + "  Summarizing…"))
            || output.contains(SpinnerFrames.REDUCED_MOTION_DOT + "  Summarizing…"));
        key(d, ESC);
        assertTrue(d.isActive(), "dialog must stay open while the executor hasn't reported back");

        pendingSuccess.get().run();
        assertFalse(d.isActive());
    }

    @Test
    void summarize_failureShowsErrorAndOnlyEscapeCloses(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        MessageSelectorDialog.SummarizeExecutor executor = (_, _, _, _, onFailure) ->
            onFailure.accept("boom");
        d.show(List.of(msg), fhm, executor, _ -> {});

        key(d, UP);
        key(d, ENTER);
        key(d, new KeyStroke(KeyType.ARROW_DOWN));
        key(d, ENTER); // executor fails synchronously above

        assertTrue(d.isActive(), "failure must not close — TS has no in-place retry, only Esc");
        String output = rendered(d);
        assertTrue(output.contains("Rewind"));
        assertTrue(output.contains("Failed to summarize:"));
        assertFalse(output.contains("Error: Failed to summarize:"));
        assertTrue(output.contains("boom"));
        assertTrue(output.contains("Esc to cancel"));
        assertEquals(5, d.calculatePreferredSize().getRows());
        key(d, ESC);
        assertFalse(d.isActive(), "Esc is the only way out once an error is shown");
    }

// ── Ctrl+C / Ctrl+D double-press exit ─────────────────────────────────────────

    @Test
    void singleCtrlC_doesNotClose() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.show(List.of(realUser("hi")), NOOP_EXECUTOR, _ -> {});
        key(d, new KeyStroke('c', true, false));
        assertTrue(d.isActive());
    }

    @Test
    void doubleCtrlC_requestsApplicationExitWithoutResolvingTheDialog() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        AtomicInteger exits = new AtomicInteger();
        AtomicBoolean resultSet = new AtomicBoolean(false);
        d.setExitAction(exits::incrementAndGet);
        d.show(List.of(realUser("hi")), NOOP_EXECUTOR, _ -> resultSet.set(true));
        key(d, new KeyStroke('c', true, false));
        key(d, new KeyStroke('c', true, false));
        assertEquals(1, exits.get());
        assertTrue(d.isActive(), "the app exit callback owns teardown");
        assertFalse(resultSet.get());
    }

    @Test
    void doubleCtrlD_requestsApplicationExit() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        AtomicInteger exits = new AtomicInteger();
        d.setExitAction(exits::incrementAndGet);
        d.show(List.of(realUser("hi")), NOOP_EXECUTOR, _ -> {});
        key(d, new KeyStroke('d', true, false));
        key(d, new KeyStroke('d', true, false));
        assertEquals(1, exits.get());
        assertTrue(d.isActive());
    }

    @Test
    void ctrlCAndCtrlDKeepIndependentDoublePressState() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        AtomicInteger exits = new AtomicInteger();
        d.setExitAction(exits::incrementAndGet);
        d.show(List.of(realUser("hi")), NOOP_EXECUTOR, _ -> {});
        key(d, new KeyStroke('c', true, false));
        key(d, new KeyStroke('d', true, false));
        assertEquals(0, exits.get());
        key(d, new KeyStroke('c', true, false));
        assertEquals(1, exits.get(), "Ctrl-D must not erase the armed Ctrl-C press");
    }

    @Test
    void globalExitBindingsAndExplicitUnbindsAreHonored(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Global","bindings":{
                "ctrl+c":null,
                "ctrl+d":null,
                "x":"app:interrupt",
                "y":"app:exit"
              }}
            ]
            """);
        UserKeybindingsStore store = createStore(file, true);
        try {
            MessageSelectorDialog d = new MessageSelectorDialog();
            AtomicInteger exits = new AtomicInteger();
            d.setKeybindingsStore(store);
            d.setExitAction(exits::incrementAndGet);
            d.show(List.of(realUser("hi")), NOOP_EXECUTOR, _ -> {});

            repeatKey(d, new KeyStroke('c', true, false), 2);
            repeatKey(d, new KeyStroke('d', true, false), 2);
            assertEquals(0, exits.get(), "explicitly unbound defaults must stay consumed");

            key(d, new KeyStroke('x', false, false));
            assertTrue(rendered(d).contains("again to exit"));
            key(d, new KeyStroke('x', false, false));
            repeatKey(d, new KeyStroke('y', false, false), 2);

            assertEquals(2, exits.get());
        } finally {
            store.dispose();
        }
    }

    @Test
    void ctrlExitAcceptsTheReleasedInclusive800MillisecondBoundary() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        AtomicInteger exits = new AtomicInteger();
        AtomicInteger now = new AtomicInteger(1_000);
        d.setExitAction(exits::incrementAndGet);
        d.setCurrentTimeMillisForTest(now::get);
        d.show(List.of(realUser("hi")), NOOP_EXECUTOR, _ -> {});

        key(d, new KeyStroke('c', true, false));
        now.set(1_800);
        key(d, new KeyStroke('c', true, false));

        assertEquals(1, exits.get());
    }

    // ── "Restore code" / "Restore code and conversation" (file-history enabled) ──

    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private FileHistoryManager newFileHistoryManager(Path backupRoot) {
        return new FileHistoryManager(
            SessionIdentity.of("test-session"),
            backupRoot, backupRoot);
    }

    @Test
    void show_fileHistoryDisabled_selectingMessageRestoresConversationDirectly() {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(List.of(msg), NOOP_EXECUTOR, result::set); // 3-arg show — fileHistoryManager == null

        key(d, UP);
        key(d, ENTER); // no checkpoint subsystem -> direct conversation restore

        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION, result.get().action());
    }

    private FileHistoryManager changedFileHistoryManager(Path backupRoot, String messageId)
            throws Exception {
        FileHistoryManager manager = newFileHistoryManager(backupRoot);
        Path changed = backupRoot.resolve("changed.txt");
        Files.writeString(changed, "before");
        manager.makeSnapshot(messageId);
        manager.trackEdit(changed.toString());
        Files.writeString(changed, "after");
        return manager;
    }

    @Test
    void selectingAMessageLoadsTheRestoreDiffOffTheGuiThreadLike197(
            @TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());
        AtomicReference<Runnable> pendingUiWork = new AtomicReference<>();
        CountDownLatch queued = new CountDownLatch(1);
        d.setGuiInvoker(work -> {
            pendingUiWork.set(work);
            queued.countDown();
        });
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, _ -> {});

        key(d, UP);
        key(d, ENTER);

        assertFalse(Strings.CS.contains(rendered(d), "Confirm you want to restore"));
        assertTrue(queued.await(2, TimeUnit.SECONDS));
        pendingUiWork.get().run();
        String output = rendered(d);
        assertTrue(Strings.CS.contains(output, "Confirm you want to restore"), output);
        assertTrue(Strings.CS.contains(output, "Restore code and conversation"), output);
    }

    @Test
    void preselectedMessageShowsConfirmationBeforeItsRestoreDiffFinishes(
            @TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());
        AtomicReference<Runnable> pendingUiWork = new AtomicReference<>();
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        CountDownLatch queued = new CountDownLatch(1);
        d.setGuiInvoker(work -> {
            pendingUiWork.set(work);
            queued.countDown();
        });

        d.showPreselected(List.of(msg), fhm, msg, () -> {}, NOOP_EXECUTOR, result::set);

        String initial = rendered(d);
        assertTrue(Strings.CS.contains(
            initial, "Confirm you want to restore the conversation"), initial);
        assertFalse(Strings.CS.contains(initial, "Restore code and conversation"), initial);
        assertTrue(queued.await(2, TimeUnit.SECONDS));
        pendingUiWork.get().run();
        assertTrue(Strings.CS.contains(rendered(d), "Restore code and conversation"));
        key(d, ENTER);
        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CODE_AND_CONVERSATION,
            result.get().action());
    }

    @Test
    void staleDiffFromAnEarlierShowCannotReopenItsConfirmation(
            @TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage first = realUser("first");
        UserMessage second = realUser("second");
        var fhm = changedFileHistoryManager(backupRoot, first.uuid());
        AtomicReference<Runnable> pendingUiWork = new AtomicReference<>();
        CountDownLatch queued = new CountDownLatch(1);
        d.setGuiInvoker(work -> {
            pendingUiWork.set(work);
            queued.countDown();
        });
        d.show(List.of(first), fhm, NOOP_EXECUTOR, _ -> {});
        key(d, UP);
        key(d, ENTER);
        assertTrue(queued.await(2, TimeUnit.SECONDS));

        d.show(List.of(second), fhm, NOOP_EXECUTOR, _ -> {});
        pendingUiWork.get().run();

        String output = rendered(d);
        assertFalse(Strings.CS.contains(output, "Confirm you want to restore"), output);
        assertTrue(Strings.CS.contains(output, "second"), output);
    }

    @Test
    void fileHistoryEnabledWithoutChangedFilesOmitsCodeRestoreOptions(
            @TempDir Path backupRoot) {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot(msg.uuid());
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.showPreselected(List.of(msg), fhm, msg, () -> {}, NOOP_EXECUTOR, result::set);

        key(d, ENTER);

        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION,
            result.get().action());
    }

    @Test
    void show_fileHistoryEnabled_showsThreeRestoreOptions_inTsOrder(@TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());


        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, result::set);
        key(d, UP);
        key(d, ENTER); // -> options phase, cursor at index 0
        key(d, ENTER); // confirm index 0
        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CODE_AND_CONVERSATION, result.get().action());
    }

    @Test
    void sixRestoreOptionsUseFiveRowWindowWithScrollMarkers(
            @TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, _ -> {});

        key(d, UP);
        key(d, ENTER);
        String firstWindow = rendered(d);
        assertTrue(firstWindow.contains("↓ 5. Summarize up to here"));
        assertFalse(firstWindow.contains("6. Never mind"));

        key(d, new KeyStroke(KeyType.PAGE_DOWN));
        String lastWindow = rendered(d);
        assertTrue(lastWindow.contains("↑ 2. Restore conversation"));
        assertTrue(lastWindow.contains("6. Never mind"));

        key(d, new KeyStroke(KeyType.PAGE_UP));
        String returnedWindow = rendered(d);
        assertTrue(returnedWindow.contains("↓ 5. Summarize up to here"));
        assertFalse(returnedWindow.contains("6. Never mind"));
    }

    @Test
    void shortTerminalUsesReleasedDynamicOptionPageSize(
            @TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        d.setTerminalRowsSupplier(() -> 10);
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, result::set);

        key(d, UP);
        key(d, ENTER);
        String firstPage = rendered(d);
        assertTrue(firstPage.contains("1. Restore code and conversation"));
        assertTrue(firstPage.contains("2. Restore conversation"));
        assertFalse(firstPage.contains("3. Restore code"));

        key(d, new KeyStroke(KeyType.PAGE_DOWN));
        key(d, ENTER);
        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CODE,
            result.get().action());
    }

    @Test
    void fullWidthNumberSelectsRestoreOptionLikeReleasedSelect(
            @TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());
        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, result::set);

        key(d, UP);
        key(d, ENTER);
        key(d, new KeyStroke('２', false, false));

        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION,
            result.get().action());
    }

    @Test
    void rawHomeAndEndMoveRestoreOptionFocusLikeReleasedSelect(
            @TempDir Path backupRoot) throws Exception {
        UserMessage msg = realUser("hello");

        MessageSelectorDialog homeDialog = new MessageSelectorDialog();
        var homeHistory = changedFileHistoryManager(
            Files.createDirectories(backupRoot.resolve("home")), msg.uuid());
        AtomicReference<MessageSelectorDialog.Selection> homeResult = new AtomicReference<>();
        homeDialog.show(List.of(msg), homeHistory, NOOP_EXECUTOR, homeResult::set);
        key(homeDialog, UP);
        key(homeDialog, ENTER);
        key(homeDialog, DOWN);
        key(homeDialog, new KeyStroke(KeyType.HOME));
        key(homeDialog, ENTER);
        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CODE_AND_CONVERSATION,
            homeResult.get().action());

        MessageSelectorDialog endDialog = new MessageSelectorDialog();
        var endHistory = changedFileHistoryManager(
            Files.createDirectories(backupRoot.resolve("end")), msg.uuid());
        AtomicBoolean endClosed = new AtomicBoolean(false);
        endDialog.showPreselected(List.of(msg), endHistory, msg, () -> {},
            NOOP_EXECUTOR, _ -> endClosed.set(true));
        key(endDialog, new KeyStroke(KeyType.END));
        key(endDialog, ENTER);
        assertTrue(endClosed.get(), "End focuses Never mind");
        assertFalse(endDialog.isActive());
    }

    @Test
    void selectRestoreConversation_atIndexOne_whenFileHistoryEnabled(@TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());

        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, result::set);
        key(d, UP);
        key(d, ENTER);  // -> options phase, index 0 (both)
        key(d, DOWN);   // -> index 1 (conversation)
        key(d, ENTER);
        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION, result.get().action());
    }

    @Test
    void selectRestoreCode_emitsSelectionWithRestoreCodeAction(@TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());

        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, result::set);
        key(d, UP);
        key(d, ENTER);  // -> options phase, index 0 (both)
        key(d, DOWN);   // -> index 1 (conversation)
        key(d, DOWN);   // -> index 2 (code)
        key(d, ENTER);

        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CODE, result.get().action());
        assertSame(msg, result.get().message());
    }

    @Test
    void selectRestoreCodeAndConversation_emitsSelectionWithBothAction(@TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());

        AtomicReference<MessageSelectorDialog.Selection> result = new AtomicReference<>();
        d.show(List.of(msg), fhm, NOOP_EXECUTOR, result::set);
        key(d, UP);
        key(d, ENTER); // -> options phase, cursor already at index 0 = both
        key(d, ENTER);

        assertEquals(MessageSelectorDialog.RestoreAction.RESTORE_CODE_AND_CONVERSATION, result.get().action());
    }

    @Test
    void fileHistoryEnabled_neverMind_stillReturnsToMessageList(@TempDir Path backupRoot) throws Exception {
        MessageSelectorDialog d = new MessageSelectorDialog();
        UserMessage msg = realUser("hello");
        var fhm = changedFileHistoryManager(backupRoot, msg.uuid());

        d.show(List.of(msg), fhm, NOOP_EXECUTOR, _ -> {});
        key(d, UP);
        key(d, ENTER); // -> options phase
        key(d, UP);    // wraps to "Never mind" (last option)
        key(d, ENTER);

        assertTrue(d.isActive(), "Never mind returns to the message list, not close");
    }

    private static FileHistoryManager.DiffStats diffStats(
            List<Message> messages, String fromUuid, String toUuid) throws Exception {
        Method method = MessageSelectorDialog.class.getDeclaredMethod(
            "computeDiffStatsBetweenMessages", List.class, String.class, String.class);
        method.setAccessible(true);
        return (FileHistoryManager.DiffStats) method.invoke(null, messages, fromUuid, toUuid);
    }

    private static String rendered(MessageSelectorDialog dialog) {
        return imageText(renderedImage(dialog));
    }

    private static BasicTextImage renderedImage(MessageSelectorDialog dialog) {
        TerminalSize size = dialog.calculatePreferredSize();
        dialog.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        dialog.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        return image;
    }

    private static String imageText(BasicTextImage image) {
        TerminalSize size = image.getSize();
        StringBuilder text = new StringBuilder(size.getColumns() * size.getRows());
        for (int row = 0; row < size.getRows(); row++) {
            for (int column = 0; column < size.getColumns(); column++) {
                text.append(image.getCharacterAt(column, row).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
