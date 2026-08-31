package com.claudecode.tools.agent;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.agent.AgentSource;
import org.apache.commons.lang3.StringUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/** Validates agent field values before save. */
public final class AgentValidator {

    private AgentValidator() {}

    private static final Pattern AGENT_TYPE_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9]$");

    public record ValidationResult(boolean isValid, List<String> errors, List<String> warnings) {}


    public static String validateAgentType(String agentType) {
        if (StringUtils.isEmpty(agentType)) {
            return "Agent type is required";
        }
        if (!AGENT_TYPE_PATTERN.matcher(agentType).matches()) {
            return "Agent type must start and end with alphanumeric characters and contain only letters, numbers, and hyphens";
        }
        if (agentType.length() < 3) {
            return "Agent type must be at least 3 characters long";
        }
        if (agentType.length() > 50) {
            return "Agent type must be less than 50 characters";
        }
        return null;
    }

    public static ValidationResult validate(String agentType, AgentSource source, String whenToUse,
            List<String> tools, String systemPrompt, Collection<String> availableToolNames,
            List<BuiltInAgentDefinitions.AgentDefinition> existingAgents) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (StringUtils.isEmpty(agentType)) {
            errors.add("Agent type is required");
        } else {
            String typeError = validateAgentType(agentType);
            if (typeError != null) errors.add(typeError);

            existingAgents.stream()
                .filter(a -> a.agentType().equals(agentType) && a.source() != source)
                .findFirst()
                .ifPresent(dup -> errors.add(
                    "Agent type \"" + agentType + "\" already exists in " + dup.source().displayName()));
        }

        if (StringUtils.isEmpty(whenToUse)) {
            errors.add("Description (description) is required");
        } else if (whenToUse.length() < 10) {
            warnings.add("Description should be more descriptive (at least 10 characters)");
        } else if (whenToUse.length() > 5000) {
            warnings.add("Description is very long (over 5000 characters)");
        }

        if (tools == null) {
            warnings.add("Agent has access to all tools");
        } else if (tools.isEmpty()) {
            warnings.add("No tools selected - agent will have very limited capabilities");
        } else {
            AgentToolResolver.Resolved resolved = AgentToolResolver.resolve(tools, availableToolNames);
            if (!resolved.invalidTools().isEmpty()) {
                errors.add("Invalid tools: " + String.join(", ", resolved.invalidTools()));
            }
        }

        if (StringUtils.isEmpty(systemPrompt)) {
            errors.add("System prompt is required");
        } else if (systemPrompt.length() < 20) {
            errors.add("System prompt is too short (minimum 20 characters)");
        } else if (systemPrompt.length() > 10000) {
            warnings.add("System prompt is very long (over 10,000 characters)");
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }
}
