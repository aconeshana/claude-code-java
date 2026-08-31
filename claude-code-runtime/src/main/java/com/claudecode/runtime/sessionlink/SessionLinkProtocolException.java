package com.claudecode.runtime.sessionlink;

/**
 * Protocol validation failure on the local Session Link transport.
 */
public final class SessionLinkProtocolException extends RuntimeException {

    public SessionLinkProtocolException(String message) {
        super(message);
    }

    public SessionLinkProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
