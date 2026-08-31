package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.runtime.turn.UserInput;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link QueuedCommandMapper#isBlankQueuedCommand}, the guard
 * that stops {@code executeQueuedCommands} from submitting an empty user turn.
 *
 * <p>Real incident: a queued command reached the UI-edge drain with blank text and no
 * pasted image, producing a wire message with an empty text content block that strict
 * downstream backends reject with "message content cannot be empty".
 */
class LanternaReplScreenQueuedCommandGuardTest {

    @Test
    void blankTextWithNoPastedImageIsDropped() {
        assertTrue(QueuedCommandMapper.isBlankQueuedCommand(QueuedCommand.orphanedPermission(null), ""));
    }

    @Test
    void whitespaceOnlyTextWithNoPastedImageIsDropped() {
        QueuedCommand cmd = QueuedCommand.prompt("   ");
        assertTrue(QueuedCommandMapper.isBlankQueuedCommand(cmd, "   "));
    }

    @Test
    void nonBlankTextIsNotDropped() {
        QueuedCommand cmd = QueuedCommand.prompt("hello");
        assertFalse(QueuedCommandMapper.isBlankQueuedCommand(cmd, "hello"));
    }

    @Test
    void blankTextWithValidPastedImageIsNotDropped() {
        Map<Integer, PastedContent> pastedContents = Map.of(
            1, PastedContent.image(1, "base64data", "image/png", null, null));
        QueuedCommand cmd = new QueuedCommand("", pastedContents);

        assertFalse(QueuedCommandMapper.isBlankQueuedCommand(cmd, ""));
    }

    @Test
    void modelScheduledPromptSuppressesTheOrdinaryInitialAttachmentPass() {
        QueuedCommand cmd = QueuedCommand.modelScheduled(
            "resolved prompt", "raw prompt", "cron", null);
        UserInput ordinary = UserInput.builder("resolved prompt", "resolved prompt").build();

        UserInput scheduled = QueuedCommandMapper.applyQueuedCommandProvenance(ordinary, cmd);

        assertTrue(scheduled.suppressInitialAttachments());
        assertTrue(scheduled.isMeta());
    }

    @Test
    void bashModeUsesTheReleasedQueueEnvelope() {
        QueuedCommand bash = new QueuedCommand(
            "pwd", null, "bash", null, false, null, false, false,
            null, null, null);

        assertEquals("<bash-input>pwd</bash-input>", QueuedCommandMapper.envelope(bash));
        assertEquals("hello", QueuedCommandMapper.envelope(QueuedCommand.prompt("hello")));
    }

    @Test
    void queueOriginIsRetainedAtTheTurnBoundary() {
        UserInput ordinary = UserInput.builder("hello", "hello").build();
        QueuedCommand remote = new QueuedCommand(
            "hello", null, "prompt", null, false, "session-host", false, false,
            null, null, null);

        UserInput remoteInput = QueuedCommandMapper.applyQueuedCommandProvenance(ordinary, remote);
        UserInput notificationInput = QueuedCommandMapper.applyQueuedCommandProvenance(
            ordinary, QueuedCommand.notification("done"));

        assertEquals("remote", remoteInput.inputOrigin());
        assertEquals("task-notification", notificationInput.querySource());
    }
}
