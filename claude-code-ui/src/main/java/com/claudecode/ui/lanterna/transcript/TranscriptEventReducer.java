package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.message.SDKMessage;
import java.util.function.BooleanSupplier;

/**
 * Applies one SDK event to authoritative replay state and the visible panel.
 */
public final class TranscriptEventReducer {

    private final MessageHistory history;
    private final MessageCollapser collapser;
    private final MessagePanel panel;
    private final BooleanSupplier suppressVisibleTranscript;

    public TranscriptEventReducer(MessageHistory history, MessageCollapser collapser,
                                  MessagePanel panel,
                                  BooleanSupplier suppressVisibleTranscript) {
        this.history = history;
        this.collapser = collapser;
        this.panel = panel;
        this.suppressVisibleTranscript = suppressVisibleTranscript;
    }

    public void accept(SDKMessage message) {
        MessageHistory.RecordOutcome outcome = history.record(message);
        if (message instanceof SDKMessage.CompactBoundary boundary
                && boundary.boundaryMessage() == null) {
            return;
        }
        if (suppressVisibleTranscript.getAsBoolean()) return;
        if (outcome == MessageHistory.RecordOutcome.COMPACTED) {
            panel.clear();
            collapser.resetTurn();
            for (SDKMessage retained : history.events()) {
                collapser.dispatch(retained, panel);
            }
            return;
        }
        collapser.dispatch(message, panel);
    }
}
