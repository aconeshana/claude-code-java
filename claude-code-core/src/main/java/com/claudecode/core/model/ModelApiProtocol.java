package com.claudecode.core.model;

import com.claudecode.core.annotation.Explanation;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Wire protocol used by a user-defined model endpoint.
 */
@Explanation("Wire protocol selection for user-defined model endpoints")
public enum ModelApiProtocol {
    ANTHROPIC("anthropic"),
    OPENAI_CHAT("chat"),
    OPENAI_RESPONSES("responses");

    private final String configValue;

    ModelApiProtocol(String configValue) {
        this.configValue = configValue;
    }

    @JsonValue
    public String configValue() {
        return configValue;
    }

    public String displayName() {
        return switch (this) {
            case ANTHROPIC -> "Anthropic Messages";
            case OPENAI_CHAT -> "Chat Completions";
            case OPENAI_RESPONSES -> "Responses";
        };
    }

    @JsonCreator
    public static ModelApiProtocol fromConfigValue(String value) {
        if (value == null) throw new IllegalArgumentException("Protocol is required");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "anthropic", "messages" -> ANTHROPIC;
            case "chat", "openai-chat", "chat-completions" -> OPENAI_CHAT;
            case "responses", "openai-responses" -> OPENAI_RESPONSES;
            default -> throw new IllegalArgumentException("Unsupported model protocol: " + value);
        };
    }
}
