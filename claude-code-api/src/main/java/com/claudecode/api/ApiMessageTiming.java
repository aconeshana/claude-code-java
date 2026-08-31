package com.claudecode.api;

import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Transport timing attached to a successful non-streaming API response. */
public final class ApiMessageTiming {
    private static final Map<ApiMessage, Long> LAST_ATTEMPT_STARTS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ApiMessage, String> REQUEST_IDS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private ApiMessageTiming() {}

    public static ApiMessage attach(ApiMessage message, long lastAttemptStartMs) {
        return attach(message, lastAttemptStartMs, null);
    }

    public static ApiMessage attach(ApiMessage message, long lastAttemptStartMs, String requestId) {
        if (message != null && lastAttemptStartMs > 0L) {
            LAST_ATTEMPT_STARTS.put(message, lastAttemptStartMs);
        }
        if (message != null && StringUtils.isNotBlank(requestId)) {
            REQUEST_IDS.put(message, requestId);
        }
        return message;
    }

    public static long lastAttemptStartMs(ApiMessage message, long fallbackStartMs) {
        if (message == null) return fallbackStartMs;
        Long value = LAST_ATTEMPT_STARTS.get(message);
        return value != null && value > 0L ? value : fallbackStartMs;
    }

    public static String requestId(ApiMessage message) {
        return message == null ? null : REQUEST_IDS.get(message);
    }
}
