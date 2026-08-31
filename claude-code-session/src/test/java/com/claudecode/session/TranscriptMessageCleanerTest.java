package com.claudecode.session;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.HookSuccessAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TextReminderAttachment;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptMessageCleanerTest {

    @Test
    void releasedBinaryDropsProgressButKeepsOrdinaryExternalAttachments() {
        Message progress = new ProgressMessage("progress", "tick");
        Message attachment = new AttachmentMessage(
            "attachment", new TextReminderAttachment("keep me"));

        assertFalse(TranscriptMessageCleaner.isLoggableMessage(progress));
        assertTrue(TranscriptMessageCleaner.isLoggableMessage(attachment));
        assertEquals(List.of(attachment), TranscriptMessageCleaner.cleanMessagesForLogging(
            List.of(progress, attachment), List.of(progress, attachment), "external"));
    }

    @Test
    void releasedBinaryDropsOnlyCompletelyEmptyHookSuccessAttachments() {
        Message empty = new AttachmentMessage("empty-hook", new HookSuccessAttachment(
            "", "SessionStart", "hook-1", "SessionStart",
            "", "  ", 0, "echo ok", 12L));
        Message stdoutOnly = new AttachmentMessage("stdout-hook", new HookSuccessAttachment(
            "", "SessionStart", "hook-2", "SessionStart",
            "visible stdout", "", 0, "echo ok", 13L));
        Message content = new AttachmentMessage("content-hook", new HookSuccessAttachment(
            "loaded context", "SessionStart", "hook-3", "SessionStart",
            "", "", 0, "echo ok", 14L));

        assertFalse(TranscriptMessageCleaner.isLoggableMessage(empty));
        assertTrue(TranscriptMessageCleaner.isLoggableMessage(stdoutOnly));
        assertTrue(TranscriptMessageCleaner.isLoggableMessage(content));
        assertEquals(List.of(stdoutOnly, content),
            TranscriptMessageCleaner.cleanMessagesForLogging(
                List.of(empty, stdoutOnly, content)));
    }

    @Test
    void externalTranscriptHidesReplWrapperAndPromotesNativeVirtualMessages() {
        AssistantMessage wrapper = assistant("wrapper", false, List.of(
            toolUse("repl-1", "REPL"), toolUse("native-inline", "Bash")));
        AssistantMessage nestedAssistant = assistant("nested-assistant", true,
            List.of(toolUse("native-1", "Read")));
        UserMessage wrapperResult = user("wrapper-result", false, List.of(
            toolResult("repl-1", "wrapper output"),
            toolResult("native-inline", "inline output")));
        UserMessage nestedResult = user("nested-result", true,
            List.of(toolResult("native-1", "native output")));

        List<Message> cleaned = TranscriptMessageCleaner.cleanMessagesForLogging(
            List.of(wrapper, nestedAssistant, wrapperResult, nestedResult),
            List.of(wrapper, nestedAssistant, wrapperResult, nestedResult),
            "external");

        assertEquals(4, cleaned.size());
        AssistantMessage cleanedWrapper = (AssistantMessage) cleaned.getFirst();
        assertEquals(List.of("native-inline"), toolUseIds(cleanedWrapper));
        AssistantMessage cleanedNestedAssistant = (AssistantMessage) cleaned.get(1);
        assertNull(cleanedNestedAssistant.isVirtual());
        assertEquals(List.of("native-1"), toolUseIds(cleanedNestedAssistant));
        UserMessage cleanedWrapperResult = (UserMessage) cleaned.get(2);
        assertEquals(List.of("native-inline"), toolResultIds(cleanedWrapperResult));
        UserMessage cleanedNestedResult = (UserMessage) cleaned.get(3);
        assertNull(cleanedNestedResult.isVirtual());
        assertEquals(List.of("native-1"), toolResultIds(cleanedNestedResult));
    }

    @Test
    void fullConversationIdsRemoveAReplResultFromALaterIncrementalSlice() {
        AssistantMessage wrapper = assistant("wrapper", false,
            List.of(toolUse("repl-1", "REPL")));
        UserMessage result = user("wrapper-result", false,
            List.of(toolResult("repl-1", "hidden")));

        assertEquals(List.of(), TranscriptMessageCleaner.cleanMessagesForLogging(
            List.of(result), List.of(wrapper, result), "external"));
    }

    @Test
    void antTranscriptKeepsReplAndVirtualFlags() {
        AssistantMessage wrapper = assistant("wrapper", true,
            List.of(toolUse("repl-1", "REPL")));

        List<Message> cleaned = TranscriptMessageCleaner.cleanMessagesForLogging(
            List.of(wrapper), List.of(wrapper), "ant");

        assertEquals(List.of(wrapper), cleaned);
        assertEquals(Boolean.TRUE, cleaned.getFirst().isVirtual());
    }

    private static AssistantMessage assistant(String uuid, boolean virtual,
                                               List<ContentBlock> blocks) {
        return new AssistantMessage(
            uuid, AssistantContent.of("api-" + uuid, blocks, Usage.EMPTY), false,
            null, Instant.parse("2026-08-25T00:00:00Z"),
            null, null, null, null, null, null,
            virtual ? Boolean.TRUE : null, null, null, null);
    }

    private static UserMessage user(String uuid, boolean virtual,
                                    List<ContentBlock> blocks) {
        return new UserMessage(
            uuid, MessageContent.ofBlocks(blocks), false, false, null,
            MessageOrigin.USER, null, Instant.parse("2026-08-25T00:00:01Z"),
            null, null, null, null, null,
            virtual ? Boolean.TRUE : null, null, null);
    }

    private static ToolUseBlock toolUse(String id, String name) {
        return new ToolUseBlock(id, name, JsonUtils.getMapper().createObjectNode());
    }

    private static ToolResultBlock toolResult(String id, String text) {
        return new ToolResultBlock(id, List.of(new TextBlock(text)), false);
    }

    private static List<String> toolUseIds(AssistantMessage message) {
        return message.message().content().stream()
            .filter(ToolUseBlock.class::isInstance)
            .map(ToolUseBlock.class::cast)
            .map(ToolUseBlock::id)
            .toList();
    }

    private static List<String> toolResultIds(UserMessage message) {
        return message.message().blocks().stream()
            .filter(ToolResultBlock.class::isInstance)
            .map(ToolResultBlock.class::cast)
            .map(ToolResultBlock::toolUseId)
            .toList();
    }
}
