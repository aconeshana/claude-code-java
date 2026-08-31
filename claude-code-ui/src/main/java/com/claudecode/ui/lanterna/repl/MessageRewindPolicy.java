package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.HumanTurns;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.UserMessage;
import java.util.List;
import java.util.Optional;

/**
 * Pure Message Actions rewind predicates.
 */
final class MessageRewindPolicy {

    private static final int NORMALIZED_UUID_PREFIX_LENGTH = 24;

    private MessageRewindPolicy() {}

    record Match(int index, UserMessage message) {}

    static Optional<Match> findSelectableUser(List<Message> messages, String renderUuid) {
        if (messages == null || renderUuid == null || StringUtils.isBlank(renderUuid)) {
            return Optional.empty();
        }
        String prefix = renderUuid.substring(0,
            Math.min(NORMALIZED_UUID_PREFIX_LENGTH, renderUuid.length()));
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String uuid = message == null ? null : message.uuid();
            if (uuid == null || !Strings.CS.equals(
                    uuid.substring(0, Math.min(NORMALIZED_UUID_PREFIX_LENGTH, uuid.length())), prefix)) continue;
            if (message instanceof UserMessage user
                    && HumanTurns.isTypedTurn(user)) {
                return Optional.of(new Match(i, user));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    static boolean messagesAfterAreOnlySynthetic(List<Message> messages, int fromIndex) {
        return HumanTurns.messagesAfterAreOnlySynthetic(messages, fromIndex);
    }
}
