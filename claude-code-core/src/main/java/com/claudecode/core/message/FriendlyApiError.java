package com.claudecode.core.message;

/**
 * Exposes a curated, non-raw description of an API failure, kept separate from {@code
 * Throwable.getMessage} so existing consumers of the raw message (retry classification, too-large
 * recovery) are unaffected.
 */
public interface FriendlyApiError {

    /** Curated, non-raw text for this failure, or {@code null} if no known pattern matched. */
    String friendlyMessage();
}
