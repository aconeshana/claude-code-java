package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MessageActionsControllerTest {

    @Test
    void toggleAndNavigationDoNotAppendStatusRowsToTranscript() {
        MessagePanel messages = new MessagePanel();
        append(messages, "u1", MessagePanel.LogicalMessageKind.USER, "first", "first", null, null);
        append(messages, "a1", MessagePanel.LogicalMessageKind.ASSISTANT, "answer", null, null, null);
        InputPanel input = new InputPanel();
        MessageActionsController controller = new MessageActionsController(
            null, messages, input, _ -> {});
        int before = messages.snapshotLineCount();

        controller.toggle();
        controller.previous();

        assertTrue(controller.isActiveForTest());
        assertEquals("u1", messages.selectedLogicalMessage().orElseThrow().id());
        assertEquals(before, messages.snapshotLineCount());
    }

    @Test
    void copyAndPrimaryCopyUseLogicalMetadataWithoutMouseSelection() {
        MessagePanel messages = new MessagePanel();
        append(messages, "user", MessagePanel.LogicalMessageKind.USER,
            "prompt", "prompt", null, null);
        append(messages, "tool", MessagePanel.LogicalMessageKind.TOOL,
            "visible result", null, "command", "git status");
        InputPanel input = new InputPanel();
        AtomicReference<String> copied = new AtomicReference<>();
        MessageActionsController controller = new MessageActionsController(
            null, messages, input, copied::set);

        controller.toggle();
        controller.bottom();
        controller.copyPrimaryInput();

        assertEquals("git status", copied.get());
        assertFalse(controller.isActiveForTest());
    }

    @Test
    void enterDelegatesSelectedUserIdentityToRewindInsteadOfCopyingTextIntoInput() {
        MessagePanel messages = new MessagePanel();
        int line = messages.snapshotLineCount();
        messages.appendLine("visible", LanternaTheme.inputText());
        messages.registerLogicalMessage("render-u1", "raw-u1",
            MessagePanel.LogicalMessageKind.USER, line, line,
            "visible", "editable\ntext", null, null, false);
        InputPanel input = new InputPanel();
        input.setText("draft survives until restore runs");
        AtomicReference<String> editedUuid = new AtomicReference<>();
        MessageActionsController controller = new MessageActionsController(
            null, messages, input, _ -> {}, editedUuid::set);
        int before = messages.snapshotLineCount();

        controller.toggle();
        controller.edit();

        assertEquals("raw-u1", editedUuid.get());
        assertEquals("draft survives until restore runs", input.getText());
        assertFalse(controller.isActiveForTest());
        assertEquals(before, messages.snapshotLineCount());
    }

    @Test
    void escapeCollapsesExpandedMessageBeforeLeavingButCtrlCLeavesImmediately() {
        MessagePanel messages = new MessagePanel();
        append(messages, "user", MessagePanel.LogicalMessageKind.USER,
            "prompt", "prompt", null, null);
        int line = messages.snapshotLineCount();
        messages.appendLine("diagnostics", LanternaTheme.inputText());
        messages.registerLogicalMessage("system", MessagePanel.LogicalMessageKind.SYSTEM,
            line, line, "diagnostics", null, null, null, true);
        InputPanel input = new InputPanel();
        MessageActionsController controller = new MessageActionsController(
            null, messages, input, _ -> {});

        controller.toggle();
        controller.bottom();
        controller.edit();
        assertTrue(messages.selectedLogicalMessage().orElseThrow().expanded());

        controller.escape();
        assertTrue(controller.isActiveForTest());
        assertFalse(messages.selectedLogicalMessage().orElseThrow().expanded());

        controller.edit();
        controller.forceExit();
        assertFalse(controller.isActiveForTest());
        assertTrue(messages.selectedLogicalMessage().isEmpty());
    }

    private static void append(MessagePanel panel, String id,
                               MessagePanel.LogicalMessageKind kind,
                               String copyText, String editText,
                               String primaryLabel, String primaryInput) {
        int line = panel.snapshotLineCount();
        panel.appendLine(copyText, LanternaTheme.inputText());
        panel.registerLogicalMessage(id, kind, line, line, copyText, editText,
            primaryLabel, primaryInput, false);
    }
}
