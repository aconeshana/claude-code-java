package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TombstoneMessage;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageRewindPolicyTest {

    private static UserMessage user(String uuid, String text) {
        return new UserMessage(uuid, MessageContent.ofText(text));
    }

    @Test
    void findsRawSelectableUserByTheRenderableUuidPrefix() {
        UserMessage target = user("123456789012345678901234-raw", "prompt");
        List<Message> messages = List.of(target);

        MessageRewindPolicy.Match match = MessageRewindPolicy.findSelectableUser(
            messages, "123456789012345678901234-normalized-block").orElseThrow();

        assertSame(target, match.message());
        assertEquals(0, match.index());
    }

    @Test
    void syntheticAndNonMeaningfulTrailingMessagesDoNotRequireConfirmation() {
        UserMessage target = user("u1", "prompt");
        UserMessage toolResult = new UserMessage("tr", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("tool-1", List.of(new TextBlock("ok")), false))));
        AssistantMessage blankAssistant = new AssistantMessage("a0",
            AssistantContent.of(List.of(new TextBlock("   "))));
        List<Message> messages = List.of(
            target,
            user("synthetic", MessageConstants.INTERRUPT_MESSAGE),
            toolResult,
            new ProgressMessage("p", "working"),
            new SystemMessage("s", "notice", "info", "done"),
            blankAssistant,
            new TombstoneMessage("t", "old"));

        assertTrue(MessageRewindPolicy.messagesAfterAreOnlySynthetic(messages, 0));
    }

    @Test
    void payloadOnlyToolResultDoesNotRequireConfirmation() {
        UserMessage target = user("u1", "prompt");
        UserMessage toolResult = new UserMessage(
            "tr", MessageContent.ofText("renderable tool output"), false, false,
            Map.of("kind", "FileChangeResult"), MessageOrigin.USER, null,
            Instant.now(), null, null);

        assertTrue(MessageRewindPolicy.messagesAfterAreOnlySynthetic(
            List.of(target, toolResult), 0));
    }

    @Test
    void assistantTextOrToolUseRequiresConfirmation() {
        UserMessage target = user("u1", "prompt");
        AssistantMessage text = new AssistantMessage("a1",
            AssistantContent.of(List.of(new TextBlock("meaningful"))));
        AssistantMessage tool = new AssistantMessage("a2",
            AssistantContent.of(List.of(new ToolUseBlock(
                "tool-1", "Read", JsonNodeFactory.instance.objectNode()))));

        assertFalse(MessageRewindPolicy.messagesAfterAreOnlySynthetic(
            List.of(target, text), 0));
        assertFalse(MessageRewindPolicy.messagesAfterAreOnlySynthetic(
            List.of(target, tool), 0));
    }

    @Test
    void laterRealUserRequiresConfirmation() {
        UserMessage target = user("u1", "first");

        assertFalse(MessageRewindPolicy.messagesAfterAreOnlySynthetic(
            List.of(target, user("u2", "second")), 0));
    }
}
