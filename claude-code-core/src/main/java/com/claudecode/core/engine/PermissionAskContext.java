package com.claudecode.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Rich context passed to {@link PermissionAskCallback} when a tool invocation needs interactive
 * user approval.
 */
public record PermissionAskContext(
    /** Tool requesting approval (e.g. "Bash"). */
    String toolName,
    /** Tool input (may be null). */
    JsonNode input,
    /** Tool use ID (may be null). */
    String toolUseId,
    /** "rule" | "mode" | null — type of decision reason. */
    String decisionReasonType,
    /**
     * Human-readable detail about why ASK was triggered.
     * For type="rule": rule string like {@code "Bash(git:*)"}.
     * For type="mode": mode name like {@code "DEFAULT"}.
     * Null when decisionReasonType is null.
     */
    String decisionReasonDetail,
    /**
     * Suggested permanent allow-rule content, e.g. {@code "git:*"}.
     * Used to populate the "Yes, and don't ask again for …" button.
     * Null when no suggestion is available.
     */
    String suggestionRuleContent,
    /** Human-readable label for the suggestion, e.g. "git commands in myproject". */
    String suggestionLabel,
    /** Worker agent identifier for the worker badge (null in single-agent mode). */
    String workerId,
    /** CSS-style color name for the worker badge (null when workerId is null). */
    String workerColor,
    /**
     * Warning message about potentially destructive effects, e.g.
     * "Note: may discard uncommitted changes". Null if the command is benign.
     */
    String destructiveWarning,

    String blockedPath,
    /** Tool-provided approval prompt, or null for the generic wording. */
    String customMessage,
    /** Ordered permission updates applied by the "always allow" option. */
    List<PermissionUpdate> suggestions,
    /** Tool metadata description used by fallback/MCP approval presentation. */
    String toolDescription
) {
    public PermissionAskContext {
        suggestions = List.copyOf(suggestions == null ? List.of() : suggestions);
        toolDescription = toolDescription == null ? "" : toolDescription;
    }

    public static Builder builder(String toolName, JsonNode input) {
        return new Builder(toolName, input);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Minimal factory for callers that have no context beyond tool name + input. */
    public static PermissionAskContext simple(String toolName, JsonNode input, String toolUseId) {
        return builder(toolName, input).toolUseId(toolUseId).build();
    }

    /** Named construction for optional reason, worker, warning and suggestion metadata. */
    public static final class Builder {
        private final String toolName;
        private JsonNode input;
        private String toolUseId;
        private String decisionReasonType;
        private String decisionReasonDetail;
        private String suggestionRuleContent;
        private String suggestionLabel;
        private String workerId;
        private String workerColor;
        private String destructiveWarning;
        private String blockedPath;
        private String customMessage;
        private List<PermissionUpdate> suggestions = List.of();
        private String toolDescription = "";

        private Builder(String toolName, JsonNode input) {
            this.toolName = toolName;
            this.input = input;
        }

        private Builder(PermissionAskContext source) {
            toolName = source.toolName;
            input = source.input;
            toolUseId = source.toolUseId;
            decisionReasonType = source.decisionReasonType;
            decisionReasonDetail = source.decisionReasonDetail;
            suggestionRuleContent = source.suggestionRuleContent;
            suggestionLabel = source.suggestionLabel;
            workerId = source.workerId;
            workerColor = source.workerColor;
            destructiveWarning = source.destructiveWarning;
            blockedPath = source.blockedPath;
            customMessage = source.customMessage;
            suggestions = source.suggestions;
            toolDescription = source.toolDescription;
        }

        public Builder input(JsonNode value) { input = value; return this; }
        public Builder toolUseId(String value) { toolUseId = value; return this; }
        public Builder decisionReason(String type, String detail) {
            decisionReasonType = type;
            decisionReasonDetail = detail;
            return this;
        }
        public Builder suggestion(String ruleContent, String label) {
            suggestionRuleContent = ruleContent;
            suggestionLabel = label;
            return this;
        }
        public Builder worker(String id, String color) { workerId = id; workerColor = color; return this; }
        public Builder destructiveWarning(String value) { destructiveWarning = value; return this; }
        public Builder blockedPath(String value) { blockedPath = value; return this; }
        public Builder customMessage(String value) { customMessage = value; return this; }
        public Builder suggestions(List<PermissionUpdate> value) { suggestions = value; return this; }
        public Builder toolDescription(String value) { toolDescription = value; return this; }

        public PermissionAskContext build() {
            return new PermissionAskContext(toolName, input, toolUseId,
                decisionReasonType, decisionReasonDetail, suggestionRuleContent,
                suggestionLabel, workerId, workerColor, destructiveWarning,
                blockedPath, customMessage, suggestions, toolDescription);
        }
    }
}
