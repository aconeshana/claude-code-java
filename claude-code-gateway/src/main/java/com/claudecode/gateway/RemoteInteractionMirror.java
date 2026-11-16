package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.apache.commons.lang3.Strings;

/**
 * Projects a remote interactive process's {@code interaction.*} Session Link frames into the
 * {@code permission.*} mirror vocabulary the web client renders, so a permission ask raised by a
 * TUI session reaches the browser exactly like a local one.
 */
final class RemoteInteractionMirror {

    private static final Set<String> SHARED_FIELDS = Set.of(
        "decision_reason_type", "decision_reason_detail", "suggestion_rule_content",
        "suggestion_label", "destructive_warning", "blocked_path", "custom_message",
        "tool_description", "questions");

    private RemoteInteractionMirror() {
    }

    static ObjectNode asked(JsonNode remote) {
        ObjectNode asked = JsonUtils.getMapper().createObjectNode();
        asked.put("request_id", remote.path("request_id").asText());
        asked.put("tool", remote.path("tool_name").asText());
        JsonNode input = remote.get("input_raw");
        if (input != null && !input.isNull()) asked.set("input", input);
        for (String field : SHARED_FIELDS) {
            JsonNode value = remote.get(field);
            if (value != null && !value.isNull()) asked.set(field, value);
        }
        return asked;
    }

    /**
     * The sudo prompt a browser renders: the id to answer under, and what is
     * asking for a password. Deliberately not routed through {@link #asked} —
     * that shape is a tool approval, and a sudo prompt has no tool input to
     * show and no allow/deny answer.
     *
     * <p>No password is ever present in this payload, and the field set is
     * fixed rather than copied, so a future remote field cannot leak into the
     * broadcast stream by accident.
     */
    static ObjectNode sudoAsked(JsonNode remote) {
        ObjectNode asked = JsonUtils.getMapper().createObjectNode();
        asked.put("request_id", remote.path("request_id").asText());
        putIfText(asked, "executable", remote);
        putIfText(asked, "command", remote);
        return asked;
    }

    /** The sudo outcome: submitted or cancelled, and which side submitted. */
    static ObjectNode sudoResolved(JsonNode remote) {
        ObjectNode resolved = JsonUtils.getMapper().createObjectNode();
        resolved.put("request_id", remote.path("request_id").asText());
        resolved.put("resolution",
            Strings.CS.equals("submit", remote.path("behavior").asText()) ? "submitted" : "cancelled");
        resolved.put("origin",
            Strings.CS.equals("local", remote.path("origin").asText()) ? "tui" : "web");
        return resolved;
    }

    private static void putIfText(ObjectNode target, String field, JsonNode source) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual()) target.put(field, value.asText());
    }

    static ObjectNode resolved(JsonNode remote) {
        ObjectNode resolved = JsonUtils.getMapper().createObjectNode();
        resolved.put("request_id", remote.path("request_id").asText());
        resolved.put("resolution",
            Strings.CS.equals("allow", remote.path("behavior").asText()) ? "allowed" : "denied");
        resolved.put("origin",
            Strings.CS.equals("local", remote.path("origin").asText()) ? "tui" : "web");
        if (remote.hasNonNull("message")) resolved.put("feedback", remote.get("message").asText());
        return resolved;
    }
}
