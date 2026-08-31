package com.claudecode.core.message;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Classifies a raw API error response into one of the curated messages.
 */
public final class ApiErrorFriendlyText {

    private static final String PREFIX = "API Error";

    private ApiErrorFriendlyText() {}

    /** Curated text for a completed HTTP error response, or {@code null} if unrecognized. */
    public static String classify(int statusCode, String rawBody) {
        if (statusCode == 429) {
            return rateLimitMessage(rawBody);
        }
        if (statusCode == 400 && Strings.CS.contains(rawBody,
                "`tool_use` ids were found without `tool_result` blocks immediately after")) {
            return PREFIX + ": 400 due to tool use concurrency issues. Run /rewind to recover the conversation.";
        }
        if (statusCode == 400 && Strings.CS.contains(rawBody, "`tool_use` ids must be unique")) {
            return PREFIX + ": 400 duplicate tool_use ID in conversation history. Run /rewind to recover the conversation.";
        }
        if (Strings.CS.contains(rawBody, "Your credit balance is too low")) {
            return "Credit balance is too low";
        }
        if (statusCode == 400 && Strings.CI.contains(rawBody, "organization has been disabled")) {
            return "Your ANTHROPIC_API_KEY belongs to a disabled organization"
                + " · Update or unset the environment variable, or check model.json";
        }
        if (Strings.CI.contains(rawBody, "x-api-key")) {
            return "Invalid API key · check your ANTHROPIC_API_KEY / --api-key / model.json configuration";
        }
        if (statusCode == 401 || statusCode == 403) {
            String detail = extractMessage(rawBody);
            return detail != null
                ? "Authentication failed · check your API key configuration · " + PREFIX + ": " + detail
                : "Authentication failed · check your API key configuration";
        }
        if (statusCode == 404) {
            return "There's an issue with the selected model. It may not exist or you may not have"
                + " access to it. Run /model to pick a different model.";
        }
        return null;
    }

    private static String rateLimitMessage(String rawBody) {
        if (Strings.CS.contains(rawBody, "Extra usage is required for long context")) {
            return PREFIX + ": Extra usage is required for 1M context · use /model to switch to standard context";
        }
        String detail = extractMessage(rawBody);
        return PREFIX + ": Request rejected (429) · "
            + (detail != null ? detail : "this may be a temporary capacity issue — check status.anthropic.com");
    }

    /**
     * Extracts the nested {@code error.message} or top-level {@code message} field.
     * Returns {@code null} on any parse failure — deliberately no raw-body fallback
     * (see class Javadoc).
     */
    private static String extractMessage(String body) {
        if (StringUtils.isBlank(body)) return null;
        try {
            JsonNode root = JsonUtils.getMapper().readTree(body);
            JsonNode nested = root.path("error").path("message");
            if (nested.isTextual()) return nested.asText();
            JsonNode topLevel = root.path("message");
            if (topLevel.isTextual()) return topLevel.asText();
        } catch (Exception _) {
            // Non-JSON bodies yield no detail — caller falls back to generic text.
        }
        return null;
    }

    /** Curated text for a connection-level (no HTTP response) failure, or {@code null}. */
    public static String connectionFriendlyMessage(String rawMessage) {
        if (rawMessage != null && Strings.CI.contains(rawMessage, "timeout")) {
            return "Request timed out";
        }
        return null;
    }
}
