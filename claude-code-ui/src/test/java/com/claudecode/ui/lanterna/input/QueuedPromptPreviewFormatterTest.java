package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.queue.QueuedCommand;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueuedPromptPreviewFormatterTest {

    @Test
    void formatsPromptBashAndMultilineCommands() {
        QueuedCommand bash = new QueuedCommand(
            "pwd", null, "bash", null, false, null, false, false,
            null, null, null);
        QueuedCommand prefixedBash = new QueuedCommand(
            "!echo ready", null, "bash", null, false, null, false, false,
            null, null, null);

        assertEquals(List.of(
                "  ❯ first",
                "    second",
                "  ❯ !pwd",
                "  ❯ !echo ready"),
            QueuedPromptPreviewFormatter.format(Arrays.asList(
                null, QueuedCommand.prompt("first\nsecond"), bash, prefixedBash)));
    }

    @Test
    void prefersTheHumanPreExpansionValue() {
        QueuedCommand command = new QueuedCommand(
            "expanded prompt", null, "prompt", null, false, null, false, false,
            "raw prompt", null, null);

        assertEquals(List.of("  ❯ raw prompt"),
            QueuedPromptPreviewFormatter.format(List.of(command)));
    }

    @Test
    void hidesMetaCommandsAndTaskNotifications() {
        QueuedCommand nonMetaNotification = new QueuedCommand(
            "hidden notification", null, "task-notification", null, false,
            null, false, false, null, null, null);
        assertEquals(List.of("  ❯ visible"), QueuedPromptPreviewFormatter.format(List.of(
            QueuedCommand.modelScheduled("hidden schedule", "raw schedule", "cron", null),
            QueuedCommand.notification("hidden notification"),
            nonMetaNotification,
            QueuedCommand.prompt("visible"))));
    }

    @Test
    void nullOrEmptyQueueProducesNoPreviewLines() {
        assertEquals(List.of(), QueuedPromptPreviewFormatter.format(null));
        assertEquals(List.of(), QueuedPromptPreviewFormatter.format(List.of()));
    }
}
