package com.claudecode.core.message;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.List;

/**
 * Finds the turn the user actually typed and never got an answer to.
 */
public final class HumanTurns {

    /** Bodies that are a wrapped machine payload rather than something typed. */
    private static final List<String> MACHINE_TAGS = List.of(
        "local-command-stdout",
        "local-command-stderr",
        "bash-stdout",
        "bash-stderr",
        "task-notification",
        "tick");

    /** A teammate message is announced by this line before its opening tag. */
    private static final String TEAMMATE_TAG = "teammate-message";
    private static final String TEAMMATE_PREAMBLE =
        "Another Claude session sent a message";

    private static final String COMPACT_BOUNDARY = "compact_boundary";

    private HumanTurns() {
    }

    /**
     * The uuid of the last typed turn after the last assistant reply, or
     * {@code null} when the conversation has no unanswered turn — which is the
     * normal state at the moment a refusal arrives only if the refusal followed
     * tool results rather than a question.
     */
    public static String lastUnansweredHumanTurnUuid(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) return null;
        List<? extends Message> chain = sinceLastCompactBoundary(messages);

        int lastAssistant = -1;
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (chain.get(i) instanceof AssistantMessage) {
                lastAssistant = i;
                break;
            }
        }
        for (int i = chain.size() - 1; i > lastAssistant; i--) {
            if (chain.get(i) instanceof UserMessage user && isTypedTurn(user)) {
                return user.uuid();
            }
        }
        return null;
    }

    /**
     * The tail of the conversation from the most recent compact boundary
     * onwards, boundary row included. The whole list when there is none.
     */
    private static List<? extends Message> sinceLastCompactBoundary(
            List<? extends Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof SystemMessage system
                    && Strings.CS.equals(COMPACT_BOUNDARY, system.subtype())) {
                return messages.subList(i, messages.size());
            }
        }
        return messages;
    }

    /** Whether this user row is something a person entered and can be selected by rewind. */
    public static boolean isTypedTurn(UserMessage user) {
        if (user == null) return false;
        if (user.isMeta() || user.isCompactSummary()) return false;
        if (Boolean.TRUE.equals(user.isVisibleInTranscriptOnly())) return false;
        if (user.origin() != null && user.origin() != MessageOrigin.USER) return false;

        MessageContent content = user.message();
        if (content != null && content.blocks() != null && !content.blocks().isEmpty()
                && content.blocks().getFirst() instanceof ToolResultBlock) {
            return false;
        }

        String body = StringUtils.strip(bodyText(content));
        if (StringUtils.isEmpty(body)) return true;
        if (MessageConstants.SYNTHETIC_MESSAGES.contains(body)) return false;
        return !isWrappedMachinePayload(body);
    }

    /** Index of the last rewind-selectable user turn, or {@code -1}. */
    public static int lastTypedTurnIndex(List<? extends Message> messages) {
        if (messages == null) return -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage user && isTypedTurn(user)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Whether every message after {@code fromIndex} is bookkeeping that 2.1.197 ignores when
     * deciding if an interrupted prompt can be restored automatically.
     */
    public static boolean messagesAfterAreOnlySynthetic(
            List<? extends Message> messages, int fromIndex) {
        if (messages == null) return true;
        for (int index = fromIndex + 1; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message == null) continue;
            if (MessageConstants.isSyntheticMessage(message)) continue;
            if (MessageConstants.isToolUseResultMessage(message)) continue;
            switch (message.type()) {
                case "progress", "system", "attachment" -> { }
                case "user" -> {
                    UserMessage user = (UserMessage) message;
                    if (user.isMeta()) continue;
                    return false;
                }
                case "assistant" -> {
                    if (assistantHasMeaningfulContent((AssistantMessage) message)) return false;
                }
                default -> {
                    // Tombstones and other bookkeeping envelopes do not block auto-restore.
                }
            }
        }
        return true;
    }

    /** The joined text-block body, or the string body, whichever this row has. */
    private static String bodyText(MessageContent content) {
        return MessageConstants.getContentText(content);
    }

    private static boolean assistantHasMeaningfulContent(AssistantMessage assistant) {
        if (assistant.message() == null || assistant.message().content() == null) return false;
        for (ContentBlock block : assistant.message().content()) {
            if (block instanceof TextBlock(String text) && StringUtils.isNotBlank(text)) return true;
            if (block instanceof ToolUseBlock) return true;
        }
        return false;
    }

    private static boolean isWrappedMachinePayload(String body) {
        for (String tag : MACHINE_TAGS) {
            if (Strings.CS.contains(body, "<" + tag + ">")) return true;
        }
        String openingTag = "<" + TEAMMATE_TAG + " ";
        if (Strings.CS.startsWith(body, openingTag)) return true;
        // A teammate message may also arrive behind the preamble that introduces
        // it, in which case the tag opens the second line.
        return Strings.CS.startsWith(body, TEAMMATE_PREAMBLE)
            && Strings.CS.startsWith(body.substring(
                Math.min(body.indexOf('\n') + 1, body.length())), openingTag);
    }
}
