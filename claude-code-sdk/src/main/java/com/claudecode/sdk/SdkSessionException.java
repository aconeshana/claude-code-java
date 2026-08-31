package com.claudecode.sdk;

/** Promise-rejection equivalent for offline SDK session operations. */
public final class SdkSessionException extends RuntimeException {
    public SdkSessionException(String message) { super(message); }
    public SdkSessionException(Throwable cause) { super(cause); }
}
