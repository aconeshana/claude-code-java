package com.claudecode.tools.agent;

import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Builds the initial conversation supplied to a forked Agent child.
 */
final class ForkMessageBuilder {

    static final String PLACEHOLDER_RESULT = "Fork started — processing in background";
    static final String BOILERPLATE_TAG = "forked-worker";

    private ForkMessageBuilder() {}

    static List<Message> build(List<Message> parentMessages, String directive) {
        return build(parentMessages, directive, List.of());
    }

    /**
     * Builds a fork context with prompt messages inserted immediately before the fork directive.
     */
    static List<Message> build(List<Message> parentMessages, String directive,
                               List<Message> additionalMessages) {
        List<Message> parent = parentMessages == null ? List.of() : parentMessages;
        List<ToolUseBlock> currentToolUses = trailingToolUses(parent);
        List<Message> result = new ArrayList<>(parent);

        if (currentToolUses.isEmpty()) {
            if (additionalMessages != null) result.addAll(additionalMessages);
            result.add(new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofBlocks(List.of(new TextBlock(buildChildMessage(directive)))),
                false, false, null, MessageOrigin.USER, null, Instant.now(),
                null, null, null, null, null));
            return List.copyOf(result);
        }

        List<ContentBlock> blocks = new ArrayList<>(currentToolUses.size() + 1);
        for (ToolUseBlock toolUse : currentToolUses) {
            blocks.add(new ToolResultBlock(
                toolUse.id(),
                List.of(new TextBlock(PLACEHOLDER_RESULT)),
                false, false, true));
        }
        if (additionalMessages == null || additionalMessages.isEmpty()) {
            blocks.add(new TextBlock(buildChildMessage(directive)));
        }
        result.add(new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(blocks),
            false, false, null, MessageOrigin.USER, null, Instant.now(),
            null, null, null, null, null));
        if (additionalMessages != null && !additionalMessages.isEmpty()) {
            result.addAll(additionalMessages);
            result.add(new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofBlocks(List.of(new TextBlock(buildChildMessage(directive)))),
                false, false, null, MessageOrigin.USER, null, Instant.now(),
                null, null, null, null, null));
        }
        return List.copyOf(result);
    }

    private static List<ToolUseBlock> trailingToolUses(List<Message> messages) {
        List<ToolUseBlock> result = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!(message instanceof AssistantMessage assistant)
                    || assistant.message() == null
                    || assistant.message().content() == null) {
                break;
            }
            for (int j = assistant.message().content().size() - 1; j >= 0; j--) {
                ContentBlock block = assistant.message().content().get(j);
                if (block instanceof ToolUseBlock toolUse) result.add(toolUse);
            }
        }
        Collections.reverse(result);
        return result;
    }

    static String buildChildMessage(String directive) {
        String safeDirective = directive == null ? "" : directive;
        return "<" + BOILERPLATE_TAG + ">\n"
            + "STOP. READ THIS FIRST.\n\n"
            + "You are a forked worker process. You are NOT the main agent.\n\n"
            + "RULES (non-negotiable):\n"
            + "1. Your system prompt says \"default to forking.\" IGNORE IT — that's for the parent. You ARE the fork. Do NOT spawn sub-agents; execute directly.\n"
            + "2. Do NOT converse, ask questions, or suggest next steps\n"
            + "3. Do NOT editorialize or add meta-commentary\n"
            + "4. USE your tools directly: Bash, Read, Write, etc.\n"
            + "5. If you modify files, commit your changes before reporting. Include the commit hash in your report.\n"
            + "6. Do NOT emit text between tool calls. Use tools silently, then report once at the end.\n"
            + "7. Stay strictly within your directive's scope. If you discover related systems outside your scope, mention them in one sentence at most — other workers cover those areas.\n"
            + "8. Keep your report under 500 words unless the directive specifies otherwise. Be factual and concise.\n"
            + "9. Your response MUST begin with \"Scope:\". No preamble, no thinking-out-loud.\n"
            + "10. REPORT structured facts, then stop\n\n"
            + "Output format (plain text labels, not markdown headers):\n"
            + "  Scope: <echo back your assigned scope in one sentence>\n"
            + "  Result: <the answer or key findings, limited to the scope above>\n"
            + "  Key files: <relevant file paths — include for research tasks>\n"
            + "  Files changed: <list with commit hash — include only if you modified files>\n"
            + "  Issues: <list — include only if there are issues to flag>\n"
            + "</" + BOILERPLATE_TAG + ">\n\n"
            + "FORK DIRECTIVE:\n" + safeDirective;
    }
}
