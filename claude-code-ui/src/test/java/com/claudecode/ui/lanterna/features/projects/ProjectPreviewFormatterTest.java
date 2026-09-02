package com.claudecode.ui.lanterna.features.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ProjectPreviewFormatter} contract: transcripts flatten to role-prefixed
 * plain-text lines for the project drawer's preview mode — user text, assistant
 * text, thinking summaries, and tool-use markers; empty/meta entries drop out.
 */
class ProjectPreviewFormatterTest {

    private static UserMessage user(String text) {
        return new UserMessage("u-" + text.hashCode(), MessageContent.ofText(text));
    }

    private static AssistantMessage assistant(String text) {
        return new AssistantMessage("a-" + text.hashCode(),
            AssistantContent.of(List.<ContentBlock>of(new TextBlock(text))));
    }

    @Test
    void userAndAssistantTextBecomeRolePrefixedLines() {
        List<Message> messages = List.of(
            user("fix the flaky test"),
            assistant("Looking at the test now."));

        List<String> lines = ProjectPreviewFormatter.toPreviewLines(messages);

        assertEquals(List.of("You: fix the flaky test", "Claude: Looking at the test now."), lines);
    }

    @Test
    void multilineTextSplitsIntoSeparateLines() {
        List<Message> messages = List.of(assistant("first\nsecond\n\nthird"));

        List<String> lines = ProjectPreviewFormatter.toPreviewLines(messages);

        assertEquals(List.of("Claude: first", "second", "", "third"), lines);
    }

    @Test
    void blankMessagesDropOut() {
        List<Message> messages = List.of(user("   "), assistant("hello"));

        assertEquals(List.of("Claude: hello"), ProjectPreviewFormatter.toPreviewLines(messages));
    }

    @Test
    void emptyTranscriptYieldsSinglePlaceholderLine() {
        assertEquals(List.of("(no messages)"), ProjectPreviewFormatter.toPreviewLines(List.of()));
    }
}
