package com.claudecode.core.message;

import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drops the messages a refusal-fallback announcement took back, so a resumed session never restores
 * content that was streamed and then retracted.
 */
public final class RetractedMessages {

    private static final Logger log = LoggerFactory.getLogger(RetractedMessages.class);

    /**
     * How many leading characters of a uuid identify the logical message.
     *
     * <p>A streamed message is sharded into wire messages whose uuids are
     * {@code logicalUuid.slice(0, 24) + shardIndex.toString(16).padStart(12,"0")}
     * (bundle: {@code YGe} and {@code HAt}). The announcement stores wire
     * uuids while the transcript stores logical ones, so this prefix is the
     * only thing the two representations share.
     */
    public static final int WIRE_UUID_PREFIX_LENGTH = 24;


    private static final String REFUSAL_FALLBACK = "model_refusal_fallback";

    private RetractedMessages() {}

    /**
     * Returns {@code messages} without the entries any refusal-fallback
     * announcement retracted. The input list is returned as-is when nothing
     * was retracted, which is what makes a second pass free.
     *
     * <p>System entries are never dropped — not the announcement itself, and
     * not an unrelated system row that happens to share a retracted prefix.
     */
    public static List<Message> filter(List<Message> messages) {
        if (messages == null) return List.of();
        Set<String> retracted = retractedPrefixes(messages);
        if (retracted.isEmpty()) return messages;

        List<Message> kept = messages.stream()
            .filter(m -> m instanceof SystemMessage
                || m.uuid() == null
                || !retracted.contains(prefixOf(m.uuid())))
            .toList();
        if (kept.size() != messages.size()) {
            log.debug("Dropped {} retracted message(s) from a {}-entry chain",
                messages.size() - kept.size(), messages.size());
        }
        return kept;
    }

    private static Set<String> retractedPrefixes(List<Message> messages) {
        Set<String> prefixes = new HashSet<>();
        for (Message m : messages) {
            if (!(m instanceof SystemMessage sm)
                    || !Strings.CS.equals(REFUSAL_FALLBACK, sm.subtype())
                    || sm.retractedMessageUuids() == null) {
                continue;
            }
            for (String wireUuid : sm.retractedMessageUuids()) {
                if (wireUuid != null) prefixes.add(prefixOf(wireUuid));
            }
        }
        return prefixes;
    }

    private static String prefixOf(String uuid) {
        return uuid.length() <= WIRE_UUID_PREFIX_LENGTH
            ? uuid : uuid.substring(0, WIRE_UUID_PREFIX_LENGTH);
    }
}
