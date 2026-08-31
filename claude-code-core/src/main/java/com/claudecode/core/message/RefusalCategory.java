package com.claudecode.core.message;

import java.util.Set;

/**
 * Classifies the {@link StopDetails#category refusal category} that decides which announcement
 * wording a refusal fallback uses and what may be reported to telemetry.
 */
public final class RefusalCategory {

    /** Categories that get the "safeguards are intentionally broad" wording. */
    private static final Set<String> BROAD_SAFEGUARD = Set.of("cyber", "bio");

    /** Categories that get the "sometimes happens with safe, normal conversations" wording. */
    private static final Set<String> ROUTINE_CONVERSATION =
        Set.of("frontier_llm", "reasoning_extraction");

    
    public static final String OTHER = "other";

    private RefusalCategory() {
    }

    /**
     * Whether {@code category} takes the long broad-safeguard announcement body.
     */
    public static boolean usesBroadSafeguardCopy(String category) {
        return category != null && BROAD_SAFEGUARD.contains(category);
    }

    /** Whether {@code category} takes the short routine-conversation announcement body. */
    public static boolean usesRoutineConversationCopy(String category) {
        return category != null && ROUTINE_CONVERSATION.contains(category);
    }

    /**
     * Collapses anything outside the four known categories into {@link #OTHER}
     * so an unbounded server-side vocabulary cannot leak into telemetry.
     */
    public static String normalizeForTelemetry(String category) {
        return usesBroadSafeguardCopy(category) || usesRoutineConversationCopy(category)
            ? category : OTHER;
    }
}
