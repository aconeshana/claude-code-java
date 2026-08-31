package com.claudecode.core.message;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Names the messages a retraction takes back, in the shape the reader looks for.
 */
public final class WireMessages {

    /**
     * The sentinels a wire message may consist of without counting as content.
     */
    private static final List<String> PLACEHOLDER_BODIES = List.of(
        MessageConstants.NO_CONTENT_MESSAGE,
        MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE);

    private WireMessages() {
    }

    /**
     * The wire uuid of shard {@code shardIndex} of {@code logicalUuid}: the
     * logical uuid's prefix followed by the index as twelve hexadecimal digits.
     * Total length is preserved, so a wire uuid is indistinguishable from a
     * logical one at a glance and only the prefix may be compared.
     */
    public static String shardUuid(String logicalUuid, int shardIndex) {
        String prefix = logicalUuid.length() <= RetractedMessages.WIRE_UUID_PREFIX_LENGTH
            ? logicalUuid
            : logicalUuid.substring(0, RetractedMessages.WIRE_UUID_PREFIX_LENGTH);
        return prefix + StringUtils.leftPad(Integer.toHexString(shardIndex), 12, '0');
    }

    /**
     * The wire uuids of every part of {@code messages} that had something in it.
     * A message whose blocks are all blank or placeholders contributes nothing,
     * which keeps a retraction from claiming to have withdrawn a row the user
     * never saw.
     */
    public static List<String> retractedUuids(List<? extends Message> messages) {
        if (messages == null) return List.of();
        List<String> uuids = new ArrayList<>();
        for (Message message : messages) {
            if (message != null) collectUuids(message, uuids);
        }
        return List.copyOf(uuids);
    }

    private static void collectUuids(Message message, List<String> into) {
        switch (message) {
            case AssistantMessage assistant ->
                collectBlockUuids(assistant.uuid(), assistant.message().content(), into);
            case UserMessage user -> {
                MessageContent content = user.message();
                if (content == null) {
                    into.add(user.uuid());
                } else if (content.isText()) {
                    // A string body becomes a single text block, so it is never
                    // sharded but is judged by the same emptiness rule.
                    if (hasVisibleText(content.text())) into.add(user.uuid());
                } else {
                    collectBlockUuids(user.uuid(), content.blocks(), into);
                }
            }
// Progress, attachment and system rows pass through unexpanded and always count as
// content.
            default -> into.add(message.uuid());
        }
    }

    private static void collectBlockUuids(String logicalUuid, List<ContentBlock> blocks,
                                         List<String> into) {
        if (blocks == null || blocks.isEmpty()) return;
        boolean sharded = blocks.size() > 1;
        for (int i = 0; i < blocks.size(); i++) {
            if (!hasVisibleContent(blocks.get(i))) continue;
            into.add(sharded ? shardUuid(logicalUuid, i) : logicalUuid);
        }
    }

    /**
     * Whether a wire message consisting of this single block was worth showing.
     * Anything but text counts — a tool call has no body to be empty — while
     * text has to be more than whitespace and must not be one of the placeholder
     * bodies the client writes when it has nothing from the model.
     */
    private static boolean hasVisibleContent(ContentBlock block) {
        if (!(block instanceof TextBlock text)) return true;
        return hasVisibleText(text.text());
    }

    /** Whether a text body is more than whitespace and is not a placeholder. */
    private static boolean hasVisibleText(String body) {
        return StringUtils.isNotBlank(body) && !PLACEHOLDER_BODIES.contains(body);
    }
}
