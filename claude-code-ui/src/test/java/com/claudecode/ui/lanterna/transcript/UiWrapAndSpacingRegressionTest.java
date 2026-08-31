package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.MarkdownRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
/** Ad hoc regression checks for the two UI bugs reported 2026-08-12/13. */
class UiWrapAndSpacingRegressionTest {

    @Test
    void longUnbrokenParagraphWrapsAtTerminalWidthInsteadOfOverrunning() {
        MessagePanel panel = new MessagePanel();
        String longLine = "这是一段很长的中文段落用来验证换行逻辑是否正确处理宽字符宽度并且不会在没有任何空白字符的情况下把整行都挤在同一行里显示出来测试测试测试测试测试";
        panel.appendMarkdown(longLine, new MarkdownRenderer(), true);

        List<MessagePanel.StyledLine> rows = panel.displayRowsForTest(40);
        assertTrue(rows.size() > 1, "long CJK paragraph must wrap across multiple rows, got: " + rows);
        for (MessagePanel.StyledLine row : rows) {
            int width = 0;
            for (MessagePanel.Segment seg : row.segments()) width += FormatUtils.displayWidth(seg.text());
            assertTrue(width <= 40, "row must not overrun terminal width, got " + width + ": " + row);
        }
    }

    @Test
    void oneShotAssistantTextFollowedByToolCallGetsBlankLineSeparator() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(new SDKMessage.Assistant(
            new AssistantMessage("assistant-1", AssistantContent.of(List.of(
                new TextBlock("Here is my plan.")))), null), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Bash|toolu_1"), panel);

        List<MessagePanel.StyledLine> rows = panel.displayRowsForTest(80);
        boolean hasBlankBetween = false;
        for (int i = 1; i < rows.size(); i++) {
            String prevText = plain(rows.get(i - 1));
            String curText = plain(rows.get(i));
            if (!StringUtils.isBlank(prevText) && StringUtils.isBlank(curText)) {
                hasBlankBetween = true;
                break;
            }
        }
        assertTrue(hasBlankBetween,
            "one-shot assistant text followed by a tool call must keep a blank-line separator, got: " + rows);
    }

    private static String plain(MessagePanel.StyledLine line) {
        StringBuilder sb = new StringBuilder();
        for (MessagePanel.Segment s : line.segments()) sb.append(s.text());
        return sb.toString();
    }
}
