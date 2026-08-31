package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageHistoryRewindTest {

    @Test
    void truncatingAtUserPreventsLaterRowsFromReappearingOnReplay() {
        MessageHistory history = new MessageHistory();
        UserMessage first = new UserMessage("u1", MessageContent.ofText("first"));
        UserMessage selected = new UserMessage("u2", MessageContent.ofText("second"));
        AssistantMessage answer = new AssistantMessage("a2",
            AssistantContent.of(List.of(new TextBlock("answer"))));
        history.record(new SDKMessage.User(first));
        history.record(new SDKMessage.User(selected));
        history.record(new SDKMessage.Assistant(answer, Usage.EMPTY));

        history.truncateFromUserUuid("u2");

        assertEquals(1, history.events().size());
        assertEquals(first, ((SDKMessage.User) history.events().getFirst()).message());
    }
}
