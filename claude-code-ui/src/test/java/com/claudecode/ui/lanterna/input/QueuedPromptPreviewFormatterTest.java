package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.MessageConstants;
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
    void aResurrectedBashInputShowsAsItsBangCommand() {
        assertEquals(List.of("  ❯ !sleep 5"), QueuedPromptPreviewFormatter.format(
            List.of(QueuedCommand.prompt("<bash-input>sleep 5</bash-input>"))));
    }

    @Test
    void aResurrectedCommandBreadcrumbShowsAsItsSlashEcho() {
        QueuedCommand breadcrumb = QueuedCommand.prompt(
            """
            <command-message>srv:review</command-message>
            <command-name>/srv:review</command-name>
            <command-args>pr 12</command-args>""");

        assertEquals(List.of("  ❯ /srv:review pr 12"),
            QueuedPromptPreviewFormatter.format(List.of(breadcrumb)));
    }

    @Test
    void aResurrectedLocalCommandOutputAndInterruptionShowAsResponseRows() {
        assertEquals(List.of(
                Figures.RESULT_PREFIX + "Renamed",
                Figures.RESULT_INDENT + "to x",
                Figures.RESULT_PREFIX + "Interrupted · What should Claude do instead?"),
            QueuedPromptPreviewFormatter.format(List.of(
                QueuedCommand.prompt("<local-command-stdout>Renamed\nto x</local-command-stdout>"),
                QueuedCommand.prompt(MessageConstants.INTERRUPT_MESSAGE))));
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
