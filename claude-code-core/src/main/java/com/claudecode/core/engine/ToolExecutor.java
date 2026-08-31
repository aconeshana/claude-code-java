package com.claudecode.core.engine;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.StreamingClient.StreamRequest.ToolDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.message.Message;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface for executing tools requested by the assistant.
 * Implementations handle the actual tool logic (file read, bash, etc.).
 *
 * <ul>
 *   <li>expose the
 *       active tool catalogue and render dynamic tool prompts from the current
 *       request context before assembling the model request.</li>
 * </ul>
 */
public interface ToolExecutor {

    record McpAttribution(String serverName, String toolName) {}

    /**
     * Executes a tool and returns the result.
     */
    ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context);

    /**
     * Revalidates and remaps a PostToolUse replacement through the owning tool.
     * Implementations that do not own a tool catalogue keep the replacement as
     * a plain text/JSON result; the production registry additionally applies
     * output schemas, tool-specific mapping, and large-result persistence.
     */
    default PostToolUseOutputResult processPostToolUseOutput(
            String toolName, JsonNode originalInput, JsonNode updatedOutput,
            ToolResult originalResult, ToolExecutionContext context) {
        String rendered = updatedOutput != null && updatedOutput.isTextual()
            ? updatedOutput.asText() : String.valueOf(updatedOutput);
        ToolResult replacement = ToolResult.success(rendered)
            .withToolUseResult(updatedOutput);
        return new PostToolUseOutputResult.Applied(replacement);
    }

    /**
     * Returns the raw MCP identity for a registered tool.
     */
    default McpAttribution mcpAttribution(String toolName) {
        if (toolName == null || !Strings.CS.startsWith(toolName, "mcp__")) return null;
        String remainder = toolName.substring("mcp__".length());
        int separator = remainder.indexOf("__");
        if (separator <= 0 || separator + 2 >= remainder.length()) return null;
        return new McpAttribution(
            remainder.substring(0, separator), remainder.substring(separator + 2));
    }

    /**
     * Returns tool definitions for the API request.
     * Default returns empty list (no tools advertised to the model).
     */
    default List<ToolDef> getToolDefinitions() {
        return List.of();
    }

    /**
     * Returns tool definitions rendered for the current model request. Tools
     * with dynamic prompts may use the request context's model, working
     * directory, and enabled-tool catalogue; legacy executors retain the
     * context-free behavior through the default delegation.
     */
    default List<ToolDef> getToolDefinitions(ToolExecutionContext context) {
        return getToolDefinitions();
    }

    /**
     * Returns tool definitions for the API request, given the set of deferred tool names already
     * "discovered" (via a {@code tool_reference} earlier in the conversation — see {@code
     * QueryLoop#extractDiscoveredToolNames}).
     */
    default List<ToolDef> getToolDefinitions(Set<String> discoveredToolNames) {
        return getToolDefinitions();
    }

    /** Context-aware ToolSearch variant of {@link #getToolDefinitions(Set)}. */
    default List<ToolDef> getToolDefinitions(
            Set<String> discoveredToolNames, ToolExecutionContext context) {
        return getToolDefinitions(discoveredToolNames);
    }

    /**
     * Returns persisted/legacy tool names mapped to the canonical names used in the current API tool
     * catalogue.
     */
    default Map<String, String> getToolNameAliases() {
        return Map.of();
    }


    default List<String> getDeferredToolNames() {
        return List.of();
    }

    /**
     * Whether the named tool is safe for concurrent execution with other tools given this specific
     * input.
     */
    default boolean isConcurrencySafe(String toolName, JsonNode input) {
        return false;
    }

    /**
     * Applies the optional message-level aggregate tool-result budget to the
     * request view. Implementations that own persistence may override; the
     * default leaves the transcript untouched.
     */
    default List<Message> applyToolResultBudget(List<Message> messages,
                                                String sessionId,
                                                String workingDirectory,
                                                String agentId) {
        return messages;
    }

    /**
     * Restores message-level result-budget decisions when a transcript is
     * loaded. The default executor has no persistent state.
     */
    default void restoreToolResultBudget(List<Message> messages,
                                         List<ToolResultBudget.Replacement> replacements,
                                         String sessionId,
                                         String workingDirectory,
                                         String agentId) {}

    /**
     * Drains replacement decisions made by the last budget application. The
     * query loop persists these records after request assembly so resume can
     * reproduce the byte-identical preview and cache prefix.
     */
    default List<ToolResultBudget.Replacement> drainToolResultBudgetReplacements(
            String sessionId, String workingDirectory, String agentId) {
        return List.of();
    }
}
