package com.claudecode.http;

import java.io.IOException;

/**
 * Raised when an HTTP response exceeds the caller's hard byte limit.
 *
 * <ul>
 *   <li>Axios
 *       {@code maxContentLength} rejection for bodies over 10MB.</li>
 * </ul>
 */
public final class ResponseTooLargeException extends IOException {
    public ResponseTooLargeException(long maximumBytes) {
        super("HTTP response exceeds maximum size of " + maximumBytes + " bytes");
    }
}
