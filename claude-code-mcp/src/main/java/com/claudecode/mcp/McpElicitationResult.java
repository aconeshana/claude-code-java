package com.claudecode.mcp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.core.serialization.JsonUtils;

import org.apache.commons.lang3.StringUtils;
/**
 * Model-facing result helpers for URL elicitation terminal actions.
 */
final class McpElicitationResult {

    private McpElicitationResult() {}

    static ObjectNode terminal(String toolName, String action) {
        String normalizedAction = StringUtils.isBlank(action) ? "cancel" : action;
        String verb = switch (normalizedAction) {
            case "decline" -> "declined";
            case "cancel" -> "canceled";
            default -> normalizedAction + "ed";
        };
        String message = "URL elicitation was " + verb + " by the user. The tool \""
            + toolName + "\" could not complete because it requires the user to open a URL.";
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("isError", true);
        result.put("elicitationAction", normalizedAction);
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", message);
        return result;
    }
}
