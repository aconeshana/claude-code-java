package com.claudecode.http;

import okhttp3.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bounded response-body readers for endpoints with strict payload limits.
 *
 * <ul>
 *   <li>enforces
 *       {@code MAX_HTTP_CONTENT_LENGTH} while the response is read.</li>
 * </ul>
 */
public final class ResponseBodies {

    private ResponseBodies() {}

    public static byte[] readByteArray(ResponseBody body, long maximumBytes) throws IOException {
        if (maximumBytes < 0) throw new IllegalArgumentException("maximumBytes must be non-negative");
        long contentLength = body.contentLength();
        if (contentLength > maximumBytes) throw new ResponseTooLargeException(maximumBytes);

        int initialSize = contentLength > 0 && contentLength <= Integer.MAX_VALUE
            ? (int) contentLength : 8192;
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize);
        InputStream input = body.byteStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) throw new ResponseTooLargeException(maximumBytes);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
