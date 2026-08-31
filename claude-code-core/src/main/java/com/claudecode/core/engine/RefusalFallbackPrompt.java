package com.claudecode.core.engine;

import com.claudecode.core.message.RefusalFallbackDecision;

import java.util.List;

/**
 * Asks the user whether a refused turn should move to another model.
 */
@FunctionalInterface
public interface RefusalFallbackPrompt {

    String DIALOG_KIND = "refusal_fallback_prompt";

    /**
     * Whether a remote consumer declared support for this dialog kind. Local
     * hosts render the dialog themselves and therefore use the default.
     */
    default boolean consumerSupportsDialog() {
        return true;
    }

    /**
     * Puts the question to the user and waits. Must not be called from the
     * Lanterna GUI thread.
     *
     * @return the choice, or {@code null} when the host closed the dialog
     *         without one
     */
    RefusalFallbackDecision.Choice ask(Request request);

    /**
     * Everything the dialog renders.
     */
    record Request(String refusedModel, String fallbackModel, String category,
                   String guidanceText, List<String> retractedMessageUuids) {

        public Request {
            retractedMessageUuids = retractedMessageUuids == null
                ? List.of() : List.copyOf(retractedMessageUuids);
        }
    }

    /**
     * Asks, and resolves every way of not getting an answer to.
     */
    static RefusalFallbackDecision.Choice askOrCancel(RefusalFallbackPrompt prompt,
                                                      Request request) {
        if (prompt == null) return RefusalFallbackDecision.Choice.CANCELLED;
        RefusalFallbackDecision.Choice choice;
        try {
            choice = prompt.ask(request);
        } catch (RuntimeException _) {
            // The turn is already ending; a broken host must not replace the
            // refusal the user needs to see with its own stack trace.
            return RefusalFallbackDecision.Choice.CANCELLED;
        }
        return choice == null ? RefusalFallbackDecision.Choice.CANCELLED : choice;
    }
}
