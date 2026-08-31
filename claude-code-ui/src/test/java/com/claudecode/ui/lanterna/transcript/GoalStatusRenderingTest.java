package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.googlecode.lanterna.TextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoalStatusRenderingTest {

    @Test
    void completedStatusKeepsMostSignificantStatsOnTitleLine() throws Exception {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();
        GoalStatusAttachment status = GoalStatusAttachment.achieved(
            "ship it", "done", 2, 90_000L, 1_200L);

        dispatcher.dispatch(new SDKMessage.Attachment(
            "goal_status", JsonUtils.getMapper().writeValueAsString(status), null), panel);

        assertEquals(List.of(
            "",
            "✓ Goal achieved (1m · 2 turns · 1.2k tokens)"), panel.lines);
    }

    @Test
    void verbosePendingStatusIncludesConditionAndLastReason() throws Exception {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setVerbose(true);
        StubPanel panel = new StubPanel();
        GoalStatusAttachment status = GoalStatusAttachment.pending(
            "all tests pass", "one test still fails");

        dispatcher.dispatch(new SDKMessage.Attachment(
            "goal_status", JsonUtils.getMapper().writeValueAsString(status), null), panel);

        assertEquals(List.of(
            "",
            "✶ Goal not yet met… continuing",
            "  Goal: all tests pass",
            "  Reason: one test still fails"), panel.lines);
    }

    private static final class StubPanel extends MessagePanel {
        private final List<String> lines = new ArrayList<>();

        @Override public void appendLine(String text, TextColor color) {
            lines.add(text);
        }
    }
}
