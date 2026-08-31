package com.claudecode.core.message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for user-message visibility.
 *
 * <ul>
 *   <li>transcript-only
 *       user messages stay hidden in the normal view and visible in transcript mode.</li>
 * </ul>
 */
class MessageVisibilityTest {

    @Test
    void transcriptOnlyUserMessageIsHiddenNormallyAndVisibleInTranscriptMode() {
        UserMessage summary = new UserMessage(
            "summary", MessageContent.ofText("continuation prompt"),
            false, true, null, MessageOrigin.COMPACT_SUMMARY,
            null, Instant.now(), null, null, null, null, null,
            null, null, true);

        assertFalse(MessageConstants.shouldShowUserMessage(summary, false));
        assertTrue(MessageConstants.shouldShowUserMessage(summary, true));
    }
}
