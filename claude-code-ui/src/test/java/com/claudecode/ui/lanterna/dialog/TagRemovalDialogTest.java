package com.claudecode.ui.lanterna.dialog;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagRemovalDialogTest {

    @Test
    void confirmAndCancelActionsStayLazyUntilSelection() {
        AtomicBoolean removed = new AtomicBoolean();
        CommandContext.TagRemovalRequest request = new CommandContext.TagRemovalRequest(
            "wip",
            () -> { removed.set(true); return CommandResult.of("Removed tag #wip"); },
            () -> CommandResult.of("Kept tag #wip"));
        AtomicReference<CommandResult> result = new AtomicReference<>();
        TagRemovalDialog dialog = new TagRemovalDialog();

        dialog.show(request, result::set);
        assertTrue(dialog.isActive());
        assertFalse(removed.get());

        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertTrue(removed.get());
        assertEquals("Removed tag #wip", result.get().output());
        assertFalse(dialog.isActive());
    }

    @Test
    void arrowDownAndEscapeChooseTheKeepPath() {
        CommandContext.TagRemovalRequest request = new CommandContext.TagRemovalRequest(
            "wip", () -> CommandResult.of("removed"), () -> CommandResult.of("kept"));
        AtomicReference<CommandResult> result = new AtomicReference<>();
        TagRemovalDialog dialog = new TagRemovalDialog();
        dialog.show(request, result::set);

        dialog.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("kept", result.get().output());

        result.set(null);
        dialog.show(request, result::set);
        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertEquals("kept", result.get().output());
    }
}
