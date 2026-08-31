package com.claudecode.http;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResponseBodiesTest {

    @Test
    void readsBodiesWithinTheLimit() throws Exception {
        byte[] expected = "hello".getBytes(StandardCharsets.UTF_8);
        try (ResponseBody body = ResponseBody.create(expected, MediaType.get("text/plain"))) {
            assertArrayEquals(expected, ResponseBodies.readByteArray(body, expected.length));
        }
    }

    @Test
    void rejectsBodiesBeforeBufferingPastTheHardLimit() {
        byte[] oversized = "123456".getBytes(StandardCharsets.UTF_8);
        try (ResponseBody body = ResponseBody.create(oversized, MediaType.get("text/plain"))) {
            assertThrows(ResponseTooLargeException.class,
                () -> ResponseBodies.readByteArray(body, 5));
        }
    }
}
