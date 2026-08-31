package com.claudecode.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiMessageTimingTest {
    @Test
    void retainsTheNonStreamingResponseRequestIdForSyntheticStreamReplay() {
        ApiMessage message = ApiMessage.stub("model", "ok");

        ApiMessageTiming.attach(message, 123L, "req-sync-197");

        assertEquals("req-sync-197", ApiMessageTiming.requestId(message));
        assertEquals(123L, ApiMessageTiming.lastAttemptStartMs(message, 0L));
    }
}
