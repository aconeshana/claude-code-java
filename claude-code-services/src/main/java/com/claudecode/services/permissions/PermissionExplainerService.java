package com.claudecode.services.permissions;


import org.apache.commons.lang3.StringUtils;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.core.engine.PermissionExplainerCallback;
import com.claudecode.core.engine.PermissionExplanation;
import com.claudecode.services.model.SideQuery;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements {@link PermissionExplainerCallback} via {@link SideQuery} with a forced {@code
 * explain_command} tool call.
 */
public class PermissionExplainerService implements PermissionExplainerCallback {

    private static final Logger log = LoggerFactory.getLogger(PermissionExplainerService.class);

    private static final String SYSTEM_PROMPT =
        "Analyze shell commands and explain what they do, why you're running them, and potential risks.";

    private static final String TOOL_NAME = "explain_command";

    private static final JsonNode EXPLAIN_TOOL_SCHEMA;

    static {
        ObjectMapper m = JsonUtils.getMapper();
        ObjectNode schema = m.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = m.createObjectNode();

        ObjectNode explanation = m.createObjectNode();
        explanation.put("type", "string");
        explanation.put("description", "What this command does (1-2 sentences)");
        props.set("explanation", explanation);

        ObjectNode reasoning = m.createObjectNode();
        reasoning.put("type", "string");
        reasoning.put("description",
            "Why YOU are running this command. Start with \"I\" - e.g. \"I need to check the file contents\"");
        props.set("reasoning", reasoning);

        ObjectNode risk = m.createObjectNode();
        risk.put("type", "string");
        risk.put("description", "What could go wrong, under 15 words");
        props.set("risk", risk);

        ObjectNode riskLevel = m.createObjectNode();
        riskLevel.put("type", "string");
        ArrayNode enums = m.createArrayNode();
        enums.add("LOW"); enums.add("MEDIUM"); enums.add("HIGH");
        riskLevel.set("enum", enums);
        riskLevel.put("description",
            "LOW (safe dev workflows), MEDIUM (recoverable changes), HIGH (dangerous/irreversible)");
        props.set("riskLevel", riskLevel);

        schema.set("properties", props);
        ArrayNode required = m.createArrayNode();
        required.add("explanation"); required.add("reasoning");
        required.add("risk"); required.add("riskLevel");
        schema.set("required", required);

        EXPLAIN_TOOL_SCHEMA = schema;
    }

    private static final CreateMessageRequest.ToolDefinition EXPLAIN_TOOL =
        new CreateMessageRequest.ToolDefinition(
            TOOL_NAME,
            "Provide an explanation of a shell command",
            EXPLAIN_TOOL_SCHEMA
        );

    private final SideQuery sideQuery;

    private final String mainLoopModel;

    public PermissionExplainerService(SideQuery sideQuery, String mainLoopModel) {
        this.sideQuery = sideQuery;
        this.mainLoopModel = mainLoopModel;
    }

    @Override
    public PermissionExplanation explain(String toolName, JsonNode input, String description) {
        try {
            String formattedInput = formatInput(input);
            String userPrompt = buildPrompt(toolName, description, formattedInput);

            JsonNode toolInput = sideQuery.queryToolForced(
                mainLoopModel, SYSTEM_PROMPT, userPrompt, EXPLAIN_TOOL, 1024);
            return parseExplanation(toolInput);

        } catch (Exception e) {
            log.debug("Permission explainer failed for tool {}: {}", toolName, e.getMessage());
            return null;
        }
    }

    private static String formatInput(JsonNode input) {
        if (input == null) return "";
        if (input.isTextual()) return input.asText();
        try {
            return JsonUtils.toPrettyJson(input);
        } catch (Exception _) {
            return input.toString();
        }
    }

    private static String buildPrompt(String toolName, String description, String formattedInput) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool: ").append(toolName).append('\n');
        if (StringUtils.isNotBlank(description)) {
            sb.append("Description: ").append(description).append('\n');
        }
        sb.append("\nInput:\n").append(formattedInput);
        sb.append("\n\nExplain this command in context.");
        return sb.toString();
    }

    private static PermissionExplanation parseExplanation(JsonNode input) {
        if (input == null) return null;
        String riskLevel  = textOrNull(input, "riskLevel");
        String explanation = textOrNull(input, "explanation");
        String reasoning  = textOrNull(input, "reasoning");
        String risk       = textOrNull(input, "risk");
        if (riskLevel == null || explanation == null) return null;
        return new PermissionExplanation(riskLevel, explanation,
            reasoning != null ? reasoning : "", risk != null ? risk : "");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f != null && f.isTextual()) ? f.asText() : null;
    }
}
