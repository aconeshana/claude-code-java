package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageHistoryCompactionTest {

    @Test
    void preliminaryBoundaryIsTransient() {
        MessageHistory history = new MessageHistory();

        history.record(new SDKMessage.CompactBoundary(List.of("u1"), Usage.EMPTY));

        assertEquals(List.of(), history.events(),
            "the pre-compact progress marker must not be retained for replay");
    }

    @Test
    void fullscreenHistoryKeepsOnlyOneCompletedCompactInterval() {
        MessageHistory history = new MessageHistory();
        SDKMessage.User ancient = user("u-ancient", "ancient");
        SDKMessage.CompactBoundary firstBoundary = boundary("b1");
        SDKMessage.User recent = user("u-recent", "recent");
        SDKMessage.CompactBoundary secondBoundary = boundary("b2");

        history.record(ancient);
        history.record(firstBoundary);
        assertEquals(List.of(ancient, firstBoundary), history.events(),
            "the first compact must not impose an arbitrary numeric cap");

        history.record(recent);
        history.record(secondBoundary);
        assertEquals(List.of(firstBoundary, recent, secondBoundary), history.events(),
            "the second compact retains the previous compact interval and drops older history");

        SDKMessage.User newest = user("u-newest", "newest");
        SDKMessage.CompactBoundary thirdBoundary = boundary("b3");
        history.record(newest);
        history.record(thirdBoundary);
        assertEquals(List.of(secondBoundary, newest, thirdBoundary), history.events(),
            "history must remain bounded by compact boundaries across repeated compaction");
    }

    @Test
    void resumedSystemBoundariesUseTheSameRetentionRule() {
        MessageHistory history = new MessageHistory();
        SDKMessage.System first = new SDKMessage.System(
            new SystemMessage("b1", "compact_boundary", "info", "compacted"));
        SDKMessage.User recent = user("u-recent", "recent");
        SDKMessage.System second = new SDKMessage.System(
            new SystemMessage("b2", "compact_boundary", "info", "compacted"));

        history.record(user("u-old", "old"));
        history.record(first);
        history.record(recent);
        history.record(second);

        assertEquals(List.of(first, recent, second), history.events(),
            "resume/continue replay must not resurrect pre-boundary history");
    }

    private static SDKMessage.User user(String uuid, String text) {
        return new SDKMessage.User(new UserMessage(uuid, MessageContent.ofText(text)));
    }

    private static SDKMessage.CompactBoundary boundary(String uuid) {
        SystemMessage marker = new SystemMessage(
            uuid, "compact_boundary", "info", "Conversation compacted");
        return new SDKMessage.CompactBoundary(List.of(), Usage.EMPTY, marker);
    }
}
