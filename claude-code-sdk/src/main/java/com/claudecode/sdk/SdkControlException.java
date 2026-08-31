package com.claudecode.sdk;

/** Exceptional completion for a rejected or malformed SDK control request. */
public final class SdkControlException extends RuntimeException {
    private final String operation;
    private final String requestId;

    public SdkControlException(String operation, String requestId, String message) {
        super(message);
        this.operation = operation;
        this.requestId = requestId;
    }

    public SdkControlException(String operation, String requestId, Throwable cause) {
        super("SDK control request failed: " + operation, cause);
        this.operation = operation;
        this.requestId = requestId;
    }

    public String operation() { return operation; }
    public String requestId() { return requestId; }
}
