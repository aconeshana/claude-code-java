package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.claudecode.ui.lanterna.status.GoalStatusHistory;

class LanternaReplScreenGoalTest {

    @Test
    void latestSuccessfulGoalSkipsSentinelsAndFailures() {
        GoalStatusAttachment achieved = GoalStatusAttachment.achieved(
            "first goal", "done", 1, 1_000L, 100L);
        List<Message> messages = List.of(
            new AttachmentMessage("a", achieved),
            new AttachmentMessage("b", GoalStatusAttachment.failed(
                "impossible goal", "cannot", 1, 1_000L, 100L)),
            new AttachmentMessage("c", GoalStatusAttachment.sentinel(true, "cleared")));

        assertEquals(achieved, GoalStatusHistory.latestSuccessful(messages));
    }
}
