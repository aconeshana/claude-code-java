package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.UserMessage;
import com.claudecode.ui.lanterna.input.PromptHistory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranscriptControllerLocalAgentInputTest {

    @Test
    void pendingHumanInputSurvivesViewRebuildUntilSidechainPersistsIt() {
        UserMessage persisted = new UserMessage("old", MessageContent.ofText("initial"));
        UserMessage pending = user("pending", "继续", Instant.parse("2026-08-27T01:00:00Z"));

        List<Message> merged = TranscriptController.mergePendingLocalAgentInput(
            List.of(persisted), List.of(pending));

        assertEquals(List.of("initial", "继续"), merged.stream()
            .map(UserMessage.class::cast).map(message -> message.message().text()).toList());
    }

    @Test
    void persistedContinuationReplacesTheTemporaryDisplayCopy() {
        UserMessage pending = user("pending", "继续", Instant.parse("2026-08-27T01:00:00Z"));
        UserMessage persisted = user("disk", "继续", Instant.parse("2026-08-27T01:00:01Z"));

        List<Message> merged = TranscriptController.mergePendingLocalAgentInput(
            List.of(persisted), List.of(pending));

        assertEquals(1, merged.size());
        assertEquals("disk", merged.getFirst().uuid());
    }

    @Test
    void viewedAgentArrowHistoryContainsOnlyNewestFirstHumanPrompts() {
        UserMessage first = user("first", "first prompt", Instant.parse("2026-08-27T01:00:00Z"));
        UserMessage second = user("second", "second prompt", Instant.parse("2026-08-27T01:00:01Z"));
        UserMessage meta = new UserMessage("meta", MessageContent.ofText("hidden"), true,
            false, null, MessageOrigin.USER, null, Instant.now(), null, null);
        UserMessage envelope = user("peer",
            "<teammate-message teammate_id=\"x\">peer</teammate-message>", Instant.now());

        assertEquals(List.of("second prompt", "first prompt"),
            TranscriptController.promptHistoryFromMessages(
                List.of(first, meta, envelope, second)).stream()
                .map(PromptHistory.Entry::display).toList());
    }

    @Test
    void viewedAgentHistoryUsesYbfFiltersRatherThanTheBroaderHumanTurnFilter() {
        UserMessage compact = new UserMessage("compact", MessageContent.ofText("compact text"),
            false, true, null, MessageOrigin.USER, null, Instant.now(), null, null);
        UserMessage visibleOnly = new UserMessage("visible", MessageContent.ofText("visible text"),
            false, false, null, MessageOrigin.USER, null, Instant.now(), null, null,
            null, null, null, null, null, true);

        assertEquals(List.of("visible text", "compact text"),
            TranscriptController.promptHistoryFromMessages(List.of(compact, visibleOnly)).stream()
                .map(PromptHistory.Entry::display).toList());
    }

    private static UserMessage user(String uuid, String text, Instant timestamp) {
        return new UserMessage(uuid, MessageContent.ofText(text), false, false,
            null, MessageOrigin.USER, null, timestamp, null, null);
    }
}
