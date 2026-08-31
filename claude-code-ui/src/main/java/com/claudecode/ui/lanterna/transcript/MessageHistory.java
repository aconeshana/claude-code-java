package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.message.SDKMessage;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Session-scoped message store for replay.
 */
public final class MessageHistory {

    public enum RecordOutcome {
        IGNORED,
        APPENDED,
        COMPACTED
    }

    private final List<SDKMessage> events = new ArrayList<>();

    /** Record a message and report whether a completed compact reduced history. */
    public RecordOutcome record(SDKMessage msg) {
        if (!shouldRecord(msg)) return RecordOutcome.IGNORED;
        if (isCompletedCompactBoundary(msg)) {
            trimToLastCompletedBoundary();
            events.add(msg);
            return RecordOutcome.COMPACTED;
        }
        events.add(msg);
        return RecordOutcome.APPENDED;
    }

    public List<SDKMessage> events() { return Collections.unmodifiableList(events); }
    public void clear()              { events.clear(); }
    public boolean isEmpty()         { return events.isEmpty(); }

    /**
     * Drop the selected user event and all replay events after it. Without this
     * retained-event truncation, Ctrl+O could resurrect rows that conversation
     * rewind already removed.
     */
    public void truncateFromUserUuid(String uuid) {
        if (StringUtils.isBlank(uuid)) return;
        for (int i = 0; i < events.size(); i++) {
            SDKMessage event = events.get(i);
            if (event instanceof SDKMessage.User user
                    && user.message() != null
                    && uuid.equals(user.message().uuid())) {
                events.subList(i, events.size()).clear();
                return;
            }
        }
    }

    private static boolean shouldRecord(SDKMessage msg) {
        if (msg instanceof SDKMessage.CompactBoundary(_, _, var boundaryMessage)) {
            // QueryLoop emits a progress-only CompactBoundary before compaction
// and a boundaryMessage-bearing event only after success. Replay
// state contains only the completed system boundary.
            return boundaryMessage != null;
        }
        if (msg instanceof SDKMessage.StreamEvent(var eventType, _)) {
            // Keep only final-state tool events; skip animation intermediates and text deltas.
            return switch (eventType) {
                case "tool_result_success", "tool_result_error" -> true;
                default -> false;
            };
        }
        // Result: UI-irrelevant for replay (status bar update is re-sent on next real message).
        // StreamRequestStart: suppressed in dispatcher anyway.
        return !(msg instanceof SDKMessage.Result)
            && !(msg instanceof SDKMessage.StreamRequestStart);
    }

    private void trimToLastCompletedBoundary() {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (isCompletedCompactBoundary(events.get(i))) {
                if (i > 0) events.subList(0, i).clear();
                return;
            }
        }
    }

    private static boolean isCompletedCompactBoundary(SDKMessage message) {
        if (message instanceof SDKMessage.CompactBoundary(_, _, var boundaryMessage)) {
            return boundaryMessage != null;
        }
        return message instanceof SDKMessage.System system
            && system.message() != null
            && Strings.CS.equals("compact_boundary", system.message().subtype());
    }
}
