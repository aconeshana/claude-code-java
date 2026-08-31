package com.claudecode.runtime.turn;

import com.claudecode.core.message.PastedContent;
import java.util.Map;

/**
 * The result of one completed turn, handed to {@link SessionSink#onTurnComplete}.
 */
public record TurnOutcome(
        boolean userCancel,
        boolean restored,
        boolean restoreEligible,
        boolean permissionRejected,
        boolean refusalFallbackEdit,
        long elapsedMs,
        String restoredInput,
        Map<Integer, PastedContent> restoredImageChips,
        String restoredPermissionMode,
        String effectivePermissionMode) {

    /** Backward-compatible constructor for turns that cannot end in a refusal-fallback edit. */
    public TurnOutcome(boolean userCancel, boolean restored, boolean restoreEligible,
                       boolean permissionRejected, long elapsedMs,
                       String restoredInput, Map<Integer, PastedContent> restoredImageChips,
                       String restoredPermissionMode, String effectivePermissionMode) {
        this(userCancel, restored, restoreEligible, permissionRejected, false, elapsedMs,
            restoredInput, restoredImageChips, restoredPermissionMode, effectivePermissionMode);
    }

    /** Backward-compatible constructor for outcomes without restore-eligibility state. */
    public TurnOutcome(boolean userCancel, boolean restored, boolean permissionRejected,
                       long elapsedMs,
                       String restoredInput, Map<Integer, PastedContent> restoredImageChips,
                       String restoredPermissionMode, String effectivePermissionMode) {
        this(userCancel, restored, false, permissionRejected, false, elapsedMs, restoredInput,
            restoredImageChips, restoredPermissionMode, effectivePermissionMode);
    }

    /** Backward-compatible constructor for outcomes without permission-rejection state. */
    public TurnOutcome(boolean userCancel, boolean restored, long elapsedMs,
                       String restoredInput, Map<Integer, PastedContent> restoredImageChips,
                       String restoredPermissionMode, String effectivePermissionMode) {
        this(userCancel, restored, false, false, false, elapsedMs, restoredInput,
            restoredImageChips, restoredPermissionMode, effectivePermissionMode);
    }

    /** Backward-compatible constructor for adapters that do not publish live mode state. */
    public TurnOutcome(boolean userCancel, boolean restored, long elapsedMs,
                       String restoredInput, Map<Integer, PastedContent> restoredImageChips,
                       String restoredPermissionMode) {
        this(userCancel, restored, false, false, false, elapsedMs, restoredInput,
            restoredImageChips, restoredPermissionMode, restoredPermissionMode);
    }
}
