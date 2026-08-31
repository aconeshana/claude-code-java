package com.claudecode.core.engine;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ToolResultBudgetTest {

    @Test
    void selectsLargestFreshResultAndReappliesFrozenReplacement() {
        List<Message> messages = List.of(
            new AssistantMessage("assistant-1", AssistantContent.of(List.of(
                new ToolUseBlock("tool-1", "Bash", JsonNodeFactory.instance.objectNode()),
                new ToolUseBlock("tool-2", "Grep", JsonNodeFactory.instance.objectNode())))),
            new UserMessage("user-1", MessageContent.ofBlocks(List.of(
                new ToolResultBlock("tool-1", List.of(new TextBlock("12345678")), false),
                new ToolResultBlock("tool-2", List.of(new TextBlock("abcdefgh")), false)))));

        ToolResultBudget.State state = ToolResultBudget.newState();
        ToolResultBudget.Persister persister = (_, id, _) ->
            Optional.of("<persisted-output>" + id + "</persisted-output>");

        List<Message> first = ToolResultBudget.apply(messages, state, 10,
            _ -> false, persister);
        UserMessage firstUser = (UserMessage) first.get(1);
        ToolResultBlock firstResult = (ToolResultBlock) firstUser.message().blocks().getFirst();
        ToolResultBlock secondResult = (ToolResultBlock) firstUser.message().blocks().get(1);
        assertEquals("<persisted-output>tool-1</persisted-output>",
            ((TextBlock) firstResult.content().getFirst()).text());
        assertEquals("abcdefgh", ((TextBlock) secondResult.content().getFirst()).text());

        List<Message> second = ToolResultBudget.apply(messages, state, 10,
            _ -> false, (_, _, _) -> {
                throw new AssertionError("frozen replacement must not persist again");
            });
        UserMessage secondUser = (UserMessage) second.get(1);
        assertEquals(((TextBlock) firstResult.content().getFirst()).text(),
            ((TextBlock) ((ToolResultBlock) secondUser.message().blocks().getFirst())
                .content().getFirst()).text());
        assertSame(messages.getFirst(), second.getFirst());
    }

    @Test
    void skipPredicateFreezesInfiniteToolsWithoutPersisting() {
        List<Message> messages = List.of(
            new AssistantMessage("assistant-2", AssistantContent.of(List.of(
                new ToolUseBlock("read-1", "Read", JsonNodeFactory.instance.objectNode())))),
            new UserMessage("user-2", MessageContent.ofBlocks(List.of(
                new ToolResultBlock("read-1", List.of(new TextBlock("0123456789012345")), false)))));

        ToolResultBudget.State state = ToolResultBudget.newState();
        List<Message> result = ToolResultBudget.apply(messages, state, 1,
            "Read"::equals, (_, _, _) -> {
                throw new AssertionError("Read must be skipped");
            });
        assertSame(messages, result);
        assertEquals(List.of(), state.replacements().keySet().stream().toList());
    }

    @Test
    void replacementDecisionsCanBeDrainedAndRestoredForResume() {
        List<Message> messages = List.of(
            new AssistantMessage("assistant-3", AssistantContent.of(List.of(
                new ToolUseBlock("tool-3", "Bash", JsonNodeFactory.instance.objectNode())))),
            new UserMessage("user-3", MessageContent.ofBlocks(List.of(
                new ToolResultBlock("tool-3", List.of(new TextBlock("0123456789")), false)))));

        ToolResultBudget.State state = ToolResultBudget.newState();
        ToolResultBudget.apply(messages, state, 1, _ -> false,
            (_, _, _) -> Optional.of("<persisted-output>tool-3</persisted-output>"));
        assertEquals(List.of(new ToolResultBudget.Replacement(
            "tool-3", "<persisted-output>tool-3</persisted-output>")),
            state.drainNewReplacements());
        assertEquals(List.of(), state.drainNewReplacements());

        ToolResultBudget.State restored = ToolResultBudget.restore(messages, List.of(
            new ToolResultBudget.Replacement("tool-3", "<persisted-output>tool-3</persisted-output>")));
        List<Message> replay = ToolResultBudget.apply(messages, restored, 1,
            _ -> false, (_, _, _) -> {
                throw new AssertionError("restored replacement must not persist again");
            });
        UserMessage replayUser = (UserMessage) replay.get(1);
        assertEquals("<persisted-output>tool-3</persisted-output>",
            ((TextBlock) ((ToolResultBlock) replayUser.message().blocks().getFirst())
                .content().getFirst()).text());
    }
}
