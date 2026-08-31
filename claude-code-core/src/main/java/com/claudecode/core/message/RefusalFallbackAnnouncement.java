package com.claudecode.core.message;

import com.claudecode.core.model.ModelNames;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The notice that a refused turn was replayed on another model.
 */
public final class RefusalFallbackAnnouncement {


    public static final String SUBTYPE = "model_refusal_fallback";
    public static final String NO_FALLBACK_SUBTYPE = "model_refusal_no_fallback";

    private static final String LEVEL = "warning";

    /** The wording for a category whose safeguards are deliberately over-wide. */
    private static final String BROAD_SAFEGUARD_BODY =
        "'s safeguards flagged this message. The safeguards are intentionally broad right "
            + "now and may flag safe and routine coding, cybersecurity, or biology work. "
            + RefusalErrorMessage.SAFEGUARD_REFINEMENT_NOTICE;

    private static final String ROUTINE_BODY =
        "'s safeguards flagged this message. This sometimes happens with safe, normal "
            + "conversations.";

    /** Used when the category is one this build does not recognize. */
    private static final String UNNAMED_MODEL_BODY = "This model" + ROUTINE_BODY;

    /** Closes both this notice and the dialog that offers the same switch. */
    static final String FEEDBACK_LINE =
        "Send feedback with /feedback or learn more: " + RefusalErrorMessage.LEARN_MORE_URL;

    private RefusalFallbackAnnouncement() {
    }

    /**
     * Why the turn was flagged, worded for the category the server named. Shared
     * with {@link RefusalFallbackPromptCopy}, which asks the same question before
     * the switch that this class reports after it.
     */
    static String reasonClause(String refusedModel, String category) {
        if (RefusalCategory.usesBroadSafeguardCopy(category)) {
            return ModelNames.displayName(refusedModel) + BROAD_SAFEGUARD_BODY;
        }
        if (RefusalCategory.usesRoutineConversationCopy(category)) {
            return ModelNames.displayName(refusedModel) + ROUTINE_BODY;
        }
// An unrecognized category does not name the model.
        return UNNAMED_MODEL_BODY;
    }

    /**
     * The announcement the user reads: why the turn was flagged, where it went
     * instead, and how to report a false positive.
     *
     * @param refusedModel the model whose safeguards ended the turn
     * @param fallbackModel where the turn was replayed
     * @param category the refusal category the server sent, or {@code null}
     */
    public static String text(String refusedModel, String fallbackModel, String category) {
        return reasonClause(refusedModel, category)
            + " Switched to " + ModelNames.displayName(fallbackModel) + ". " + FEEDBACK_LINE;
    }


    public static SystemMessage row(String uuid, String refusedModel, String fallbackModel,
                                    StopDetails stopDetails, String requestId,
                                    List<String> retractedMessageUuids,
                                    String refusedUserMessageUuid) {
        String category = stopDetails == null ? null : stopDetails.category();
        return new SystemMessage(uuid, SUBTYPE, LEVEL,
            text(refusedModel, fallbackModel, category),
            null, Instant.now(), null, null, null, null, null,
            retractedMessageUuids, refusedUserMessageUuid,
            "retry", "refusal", refusedModel, fallbackModel, requestId,
            category, stopDetails == null ? null : stopDetails.explanation());
    }

    /** Backward-compatible row without request-id or refusal explanation metadata. */
    public static SystemMessage row(String uuid, String refusedModel, String fallbackModel,
                                    String category, List<String> retractedMessageUuids,
                                    String refusedUserMessageUuid) {
        StopDetails stopDetails = category == null ? null : new StopDetails(category, null);
        return row(uuid, refusedModel, fallbackModel, stopDetails, null,
            retractedMessageUuids, refusedUserMessageUuid);
    }

    /** The same row with a freshly minted uuid. */
    public static SystemMessage row(String refusedModel, String fallbackModel,
                                    StopDetails stopDetails, String requestId,
                                    List<String> retractedMessageUuids,
                                    String refusedUserMessageUuid) {
        return row(UUID.randomUUID().toString(), refusedModel, fallbackModel, stopDetails,
            requestId, retractedMessageUuids, refusedUserMessageUuid);
    }

    /** Backward-compatible overload without request-id or refusal explanation metadata. */
    public static SystemMessage row(String refusedModel, String fallbackModel, String category,
                                    List<String> retractedMessageUuids,
                                    String refusedUserMessageUuid) {
        return row(UUID.randomUUID().toString(), refusedModel, fallbackModel, category,
            retractedMessageUuids, refusedUserMessageUuid);
    }

    /** Diagnostic frame emitted before the visible error when no fallback exists. */
    public static SystemMessage noFallbackRow(String uuid, String refusedModel,
                                              StopDetails stopDetails, String requestId,
                                              String refusedUserMessageUuid) {
        return new SystemMessage(uuid, NO_FALLBACK_SUBTYPE, LEVEL, "",
            null, Instant.now(), null, null, null, null, null, null, refusedUserMessageUuid,
            null, null, refusedModel, null, requestId,
            stopDetails == null ? null : stopDetails.category(),
            stopDetails == null ? null : stopDetails.explanation());
    }

    /** The no-fallback diagnostic row with a freshly minted uuid. */
    public static SystemMessage noFallbackRow(String refusedModel, StopDetails stopDetails,
                                              String requestId,
                                              String refusedUserMessageUuid) {
        return noFallbackRow(UUID.randomUUID().toString(), refusedModel, stopDetails,
            requestId, refusedUserMessageUuid);
    }
}
