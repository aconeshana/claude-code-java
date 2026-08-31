package com.claudecode.api;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * API rejection indicating that the submitted prompt exceeded the provider's context limit.
 */
public final class PromptTooLongException extends ApiException {

    public PromptTooLongException(String message, int statusCode,
                                  String errorType, Long retryAfterSeconds) {
        super(message, statusCode, errorType, retryAfterSeconds);
    }


    public static boolean matches(String message) {
        return message != null && Strings.CS.startsWith(message, "Prompt is too long");
    }

    /** Extracts the normalized provider message from common API error envelopes. */
    static String extractFromResponseBody(String body) {
        if (matches(body)) return body;
        if (StringUtils.isBlank(body)) return null;
        try {
            JsonNode root = JsonUtils.getMapper().readTree(body);
            JsonNode nested = root.path("error").path("message");
            if (nested.isTextual() && matches(nested.asText())) return nested.asText();
            JsonNode topLevel = root.path("message");
            if (topLevel.isTextual() && matches(topLevel.asText())) return topLevel.asText();
        } catch (Exception _) {
            // Non-JSON provider bodies were already checked as plain text above.
        }
        return null;
    }
}
