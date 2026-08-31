package com.claudecode.api;

import com.claudecode.core.message.FriendlyApiError;

/**
 * Exception thrown by API client operations.
 */
public class ApiException extends RuntimeException implements FriendlyApiError {

    private final int statusCode;
    private final String errorType;
    private final Long retryAfterSeconds;
    private final String friendlyMessage;

    public ApiException(String message, int statusCode) {
        this(message, statusCode, (String) null, null);
    }

    public ApiException(String message, int statusCode, String errorType) {
        this(message, statusCode, errorType, null);
    }

    public ApiException(String message, int statusCode, Throwable cause) {
        this(message, statusCode, cause, null);
    }

/** Connection-level failure (no HTTP response) with a curated {@link #friendlyMessage}. */
    public ApiException(String message, int statusCode, Throwable cause, String friendlyMessage) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorType = null;
        this.retryAfterSeconds = null;
        this.friendlyMessage = friendlyMessage;
    }

    /**
     * Full constructor — {@code retryAfterSeconds} matches the {@code Retry-After}
     * response header (integer seconds), used by {@link RetryInterceptor} to
     * override its exponential backoff when the server specifies an exact wait.
     */
    public ApiException(String message, int statusCode, String errorType, Long retryAfterSeconds) {
        this(message, statusCode, errorType, retryAfterSeconds, null);
    }

/** Full constructor with a curated {@link #friendlyMessage} for display layers. */
    public ApiException(String message, int statusCode, String errorType, Long retryAfterSeconds,
                        String friendlyMessage) {
        super(message);
        this.statusCode = statusCode;
        this.errorType = errorType;
        this.retryAfterSeconds = retryAfterSeconds;
        this.friendlyMessage = friendlyMessage;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorType() {
        return errorType;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public String friendlyMessage() {
        return friendlyMessage;
    }
}
