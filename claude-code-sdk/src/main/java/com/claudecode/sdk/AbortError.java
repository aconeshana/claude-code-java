package com.claudecode.sdk;

/**
 * SDK-visible cancellation identity.
{@code AbortError}.</li></ul>
 */
public final class AbortError extends RuntimeException {
    public AbortError(String message) { super(message); }
    public AbortError(String message, Throwable cause) { super(message, cause); }
}
