package com.claudecode.api;




public final class ApiStreamException extends ApiException {
    public enum Reason { WATCHDOG, STALE_CONNECTION, ABORTED, OTHER }

    private final Reason reason;

    public ApiStreamException(String message, int statusCode, Reason reason) {
        super(message, statusCode);
        this.reason = reason != null ? reason : Reason.OTHER;
    }

    public Reason reason() {
        return reason;
    }
}
