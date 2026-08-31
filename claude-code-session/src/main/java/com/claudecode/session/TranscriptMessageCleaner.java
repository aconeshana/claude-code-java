package com.claudecode.session;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.HookSuccessAttachment;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;


public final class TranscriptMessageCleaner {

    static final String REPL_TOOL_NAME = "REPL";

    private TranscriptMessageCleaner() {
    }


    public static boolean isLoggableMessage(Message message) {
        if (message == null || message instanceof ProgressMessage) return false;
        if (message instanceof AttachmentMessage attachment
                && attachment.payload() instanceof HookSuccessAttachment success
                && StringUtils.isEmpty(success.content())
                && StringUtils.isBlank(success.stdout())
                && StringUtils.isBlank(success.stderr())) {
            return false;
        }
        return true;
    }

    /** Cleans a complete or self-contained transcript slice for persistence. */
    public static List<Message> cleanMessagesForLogging(List<? extends Message> messages) {
        return cleanMessagesForLogging(messages, messages, SessionStorage.USER_TYPE);
    }

    



    public static List<Message> cleanMessagesForLogging(
            List<? extends Message> messages,
            List<? extends Message> allMessages) {
        return cleanMessagesForLogging(messages, allMessages, SessionStorage.USER_TYPE);
    }

    static List<Message> cleanMessagesForLogging(
            List<? extends Message> messages,
            List<? extends Message> allMessages,
            String userType) {
        Set<String> replIds = collectReplIds(allMessages, new HashSet<>());
        return cleanMessagesForLogging(messages, replIds, userType);
    }

    /** Incremental recorder variant retaining REPL ids across separate writes. */
    static List<Message> cleanMessagesForLogging(
            List<? extends Message> messages,
            Set<String> replIds,
            String userType) {
        if (messages == null || messages.isEmpty()) return List.of();
        Set<String> effectiveReplIds = replIds == null ? new HashSet<>() : replIds;
        // The wrapper message itself must teach the incremental recorder its id
        // before that same message is filtered out.
        collectReplIds(messages, effectiveReplIds);

        List<Message> filtered = messages.stream()
            .filter(TranscriptMessageCleaner::isLoggableMessage)
            .map(Message.class::cast)
            .toList();
        if (Strings.CS.equals("ant", userType)) return filtered;

        List<Message> transformed = new ArrayList<>(filtered.size());
        for (Message message : filtered) {
            Message cleaned = transformExternal(message, effectiveReplIds);
            if (cleaned != null) transformed.add(cleaned);
        }
        return List.copyOf(transformed);
    }

    static void rememberReplToolUseIds(
            List<? extends Message> messages,
            Set<String> into) {
        collectReplIds(messages, into);
    }

    private static Set<String> collectReplIds(
            List<? extends Message> messages,
            Set<String> into) {
        if (messages == null) return into;
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant)
                    || assistant.message() == null
                    || assistant.message().content() == null) {
                continue;
            }
            for (ContentBlock block : assistant.message().content()) {
                if (block instanceof ToolUseBlock toolUse
                        && REPL_TOOL_NAME.equals(toolUse.name())
                        && toolUse.id() != null) {
                    into.add(toolUse.id());
                }
            }
        }
        return into;
    }

    private static Message transformExternal(Message message, Set<String> replIds) {
        return switch (message) {
            case AssistantMessage assistant -> cleanAssistant(assistant);
            case UserMessage user -> cleanUser(user, replIds);
            default -> message;
        };
    }

    private static Message cleanAssistant(AssistantMessage assistant) {
        AssistantContent envelope = assistant.message();
        List<ContentBlock> content = envelope == null ? null : envelope.content();
        boolean hasRepl = content != null && content.stream().anyMatch(
            block -> block instanceof ToolUseBlock toolUse
                && REPL_TOOL_NAME.equals(toolUse.name()));
        List<ContentBlock> filtered = hasRepl ? content.stream()
            .filter(block -> !(block instanceof ToolUseBlock toolUse
                && REPL_TOOL_NAME.equals(toolUse.name())))
            .toList() : content;
        if (content != null && filtered.isEmpty()) return null;
        if (filtered == content && !Boolean.TRUE.equals(assistant.isVirtual())) return assistant;

        AssistantContent cleanedEnvelope = envelope == null ? null : new AssistantContent(
            envelope.id(), filtered, envelope.usage(), envelope.model(), envelope.stopReason(),
            envelope.stopSequence(), envelope.stopDetails());
        return new AssistantMessage(
            assistant.uuid(), cleanedEnvelope, assistant.isApiErrorMessage(),
            assistant.parentUuidValue(), assistant.timestampValue(),
            assistant.attributionSkill(), assistant.attributionPlugin(),
            assistant.attributionMcpServer(), assistant.attributionMcpTool(),
            assistant.apiError(), assistant.error(), null,
            assistant.requestId(), assistant.advisorModel(), assistant.isMeta());
    }

    private static Message cleanUser(UserMessage user, Set<String> replIds) {
        MessageContent envelope = user.message();
        List<ContentBlock> blocks = envelope == null ? null : envelope.blocks();
        boolean hasReplResult = blocks != null && blocks.stream().anyMatch(
            block -> block instanceof ToolResultBlock result
                && replIds.contains(result.toolUseId()));
        List<ContentBlock> filtered = hasReplResult ? blocks.stream()
            .filter(block -> !(block instanceof ToolResultBlock result
                && replIds.contains(result.toolUseId())))
            .toList() : blocks;
        if (blocks != null && filtered.isEmpty()) return null;
        if (filtered == blocks && !Boolean.TRUE.equals(user.isVirtual())) return user;

        MessageContent cleanedEnvelope = envelope == null ? null
            : new MessageContent(envelope.text(), filtered);
        return new UserMessage(
            user.uuid(), cleanedEnvelope, user.isMeta(), user.isCompactSummary(),
            user.toolUseResult(), user.origin(), user.parentUuidValue(), user.timestampValue(),
            user.imagePasteIds(), user.permissionMode(), user.sessionIdValue(),
            user.sourceToolAssistantUUID(), user.sourceToolUseID(), null,
            user.mcpMeta(), user.isVisibleInTranscriptOnly(), user.planContent(),
            user.summarizeMetadata());
    }
}
