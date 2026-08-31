package com.claudecode.services.cost;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.ApiMessageTiming;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.core.engine.SessionCostState;

/** Shared accounting wrapper for successful direct non-streaming Messages calls. */
public final class ApiCallAccounting {
    private ApiCallAccounting() {}

    public static ApiMessage createMessage(LlmClient client, CreateMessageRequest request) {
        long startedAt = System.currentTimeMillis();
        ApiMessage response = client.createMessage(request);
        long completedAt = System.currentTimeMillis();
        SessionCostState.get().recordApiRequest(
            response != null && response.model() != null ? response.model() : request.model(),
            response != null ? response.usage() : null,
            Math.max(0L, completedAt - startedAt),
            Math.max(0L, completedAt
                - ApiMessageTiming.lastAttemptStartMs(response, startedAt)));
        return response;
    }
}
