package com.claudecode.tools.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

class ForkMessageBuilderTest {

    @Test
    void preservesParentHistoryAndAppendsOneStructuredPlaceholderTurn() {
        ToolUseBlock first = new ToolUseBlock("tool-1", "Read", JsonNodeFactory.instance.objectNode());
        ToolUseBlock second = new ToolUseBlock("tool-2", "Bash", JsonNodeFactory.instance.objectNode());
        AssistantMessage assistant = new AssistantMessage(
            "assistant-1", AssistantContent.of("api-1", List.of(first, second)));
        List<Message> built = ForkMessageBuilder.build(
            List.of(new UserMessage("user-1", MessageContent.ofText("parent")), assistant),
            "inspect the repository");

        assertEquals(3, built.size());
        UserMessage forkTurn = assertInstanceOf(UserMessage.class, built.getLast());
        List<ContentBlock> blocks = forkTurn.message().blocks();
        assertEquals(3, blocks.size());
        ToolResultBlock firstResult = assertInstanceOf(ToolResultBlock.class, blocks.getFirst());
        ToolResultBlock secondResult = assertInstanceOf(ToolResultBlock.class, blocks.get(1));
        assertEquals("tool-1", firstResult.toolUseId());
        assertEquals("tool-2", secondResult.toolUseId());
        assertTrue(firstResult.preserveContentBlocks());
        assertEquals(ForkMessageBuilder.PLACEHOLDER_RESULT,
            ((TextBlock) firstResult.content().getFirst()).text());
        assertTrue(Strings.CS.contains(((TextBlock) blocks.getLast()).text(), "inspect the repository"));
    }

    @Test
    void btwExchangeIsInsertedBeforeTheForkDirective() {
        UserMessage parent = new UserMessage("parent", MessageContent.ofText("main question"));
        UserMessage sideQuestion = new UserMessage("side-user", MessageContent.ofText("why?"));
        AssistantMessage sideAnswer = new AssistantMessage(
            "side-assistant", AssistantContent.of(List.of(new TextBlock("because"))));

        List<Message> built = ForkMessageBuilder.build(
            List.of(parent), "why?", List.of(sideQuestion, sideAnswer));

        assertEquals(List.of(parent, sideQuestion, sideAnswer), built.subList(0, 3));
        UserMessage directive = assertInstanceOf(UserMessage.class, built.getLast());
        TextBlock directiveText = assertInstanceOf(
            TextBlock.class, directive.message().blocks().getFirst());
        assertTrue(Strings.CS.contains(directiveText.text(), "FORK DIRECTIVE:\nwhy?"));
    }
}
