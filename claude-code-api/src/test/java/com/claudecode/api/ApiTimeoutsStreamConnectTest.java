package com.claudecode.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiTimeoutsStreamConnectTest {

    @Test
    void requestLevelConnectTimeoutOverridesGlobalApiTimeout() {
        CreateMessageRequest request = requestWith(Duration.ofSeconds(60));

        assertEquals(Duration.ofSeconds(60), ApiTimeouts.streamConnectTimeout(request));
    }

    @Test
    void absentRequestLevelConnectTimeoutFallsBackToGlobalApiTimeout() {
        CreateMessageRequest request = requestWith(null);

        assertEquals(ApiTimeouts.apiTimeout(), ApiTimeouts.streamConnectTimeout(request));
    }

    private static CreateMessageRequest requestWith(Duration connectTimeout) {
        return CreateMessageRequest.builder()
                .model("m")
                .maxTokens(16)
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
                .streamConnectTimeout(connectTimeout)
                .build();
    }
}
