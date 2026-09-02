package com.claudecode.ui.lanterna.features.projects;

import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens a transcript into role-prefixed plain-text lines for the project
 * drawer's preview mode. Deliberately lossy — a preview skims, it does not
 * replay: user/assistant text is kept verbatim (multiline splits), tool calls
 * become one {@code ⏺ [name]} marker each, and everything else (system/meta/
 * blank entries) drops out. A Java-side extension with no 197 counterpart.
 */
public final class ProjectPreviewFormatter {

    private ProjectPreviewFormatter() {}

    public static List<String> toPreviewLines(List<Message> messages) {
        List<String> lines = new ArrayList<>();
        for (Message message : messages == null ? List.<Message>of() : messages) {
            switch (message) {
                case UserMessage user -> appendPrefixed(lines, "You: ",
                    MessageConstants.getUserMessageText(user));
                case AssistantMessage assistant -> appendAssistant(lines, assistant);
                default -> { /* system/meta/attachment entries are not preview content */ }
            }
        }
        return lines.isEmpty() ? List.of("(no messages)") : List.copyOf(lines);
    }

    private static void appendAssistant(List<String> lines, AssistantMessage assistant) {
        if (assistant.message() == null || assistant.message().content() == null) return;
        for (ContentBlock block : assistant.message().content()) {
            switch (block) {
                case TextBlock text -> appendPrefixed(lines, "Claude: ", text.text());
                case ToolUseBlock tool -> lines.add("⏺ [" + tool.name() + "]");
                default -> { /* thinking/tool_results stay out of the skim view */ }
            }
        }
    }

    /** First physical line gets the role prefix; continuation lines stay raw. */
    private static void appendPrefixed(List<String> lines, String prefix, String text) {
        if (StringUtils.isBlank(text)) return;
        String[] split = text.strip().split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            lines.add((i == 0 ? prefix : "") + split[i]);
        }
    }
}
