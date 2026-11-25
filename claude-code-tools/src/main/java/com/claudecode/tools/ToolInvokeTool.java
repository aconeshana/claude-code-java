package com.claudecode.tools;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * Protocol-universal invocation gateway for deferred and dynamically enabled tools
 * (including MCP tools and default-disabled built-in tools).
 *
 * <p>Keeping a single fixed {@code ToolInvoke} definition in the top-level {@code tools[]}
 * array allows tools and MCP servers to be enabled or disabled mid-session via
 * {@code <system-reminder>} deltas without ever mutating {@code tools[]} across Anthropic,
 * OpenAI Chat, OpenAI Responses, and Google Gemini protocols.
 */
@Explanation("Protocol-universal deferred tool invocation bridge preserving 100% prefix cache across all 4 wire protocols")
@BuiltInTool(name = ToolInvokeTool.NAME)
public final class ToolInvokeTool extends AnnotatedTool<JsonNode, ToolResult> {

    public static final String NAME = "ToolInvoke";

    private static final String PROMPT = """
        Invokes a deferred or dynamically enabled tool (such as an MCP tool or on-demand built-in tool) by name.

        How to use:
        1. Check <system-reminder> messages for available deferred tools.
        2. If you do not yet have the target tool's parameter schema in this conversation, call ToolSearch first (e.g. {"query": "select:<tool_name>"}) to fetch its JSONSchema definition.
        3. Call ToolInvoke with {"tool_name": "<exact_tool_name>", "arguments": { ... }} matching the fetched schema.
        4. Never call a tool after a <system-reminder> announces that it is no longer available or disabled.""";

    private final ToolRegistry toolRegistry;

    public ToolInvokeTool(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public boolean alwaysLoad() {
        return true;
    }

    @Override
    public String description() {
        return PROMPT;
    }

    @Override
    public String description(JsonNode input, ToolExecutionContext context) {
        String target = extractTargetToolName(input);
        return StringUtils.isBlank(target) ? "Invoke deferred tool" : "Invoke tool: " + target;
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return PROMPT;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = createObjectSchema();
        ObjectNode props = (ObjectNode) schema.get("properties");

        ObjectNode nameSchema = mapper().createObjectNode();
        nameSchema.put("type", "string");
        nameSchema.put("description",
            "Exact canonical name of the tool to invoke (fetch its schema with ToolSearch first if needed).");
        props.set("tool_name", nameSchema);

        ObjectNode argsSchema = mapper().createObjectNode();
        argsSchema.put("type", "object");
        argsSchema.put("description",
            "JSON object of arguments conforming to the target tool's input schema.");
        props.set("arguments", argsSchema);

        ArrayNode required = schema.putArray("required");
        required.add("tool_name");
        return schema;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        String target = extractTargetToolName(input);
        if (StringUtils.isBlank(target) || NAME.equals(target) || toolRegistry == null) {
            return false;
        }
        return toolRegistry.isConcurrencySafe(target, extractTargetArguments(input));
    }

    @Override
    public Optional<ToolUseTag> renderToolUseTag(JsonNode input, ToolUseRenderContext context) {
        String target = extractTargetToolName(input);
        if (StringUtils.isBlank(target)) return Optional.empty();
        return Optional.of(ToolUseTag.dim(target));
    }

    /**
     * Permission checking is delegated to the unwrapped target tool inside
     * {@link ToolRegistry#execute(String, JsonNode, ToolExecutionContext)}.
     */
    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }

    @Override
    public ToolResult call(JsonNode input, ToolExecutionContext context) {
        String target = extractTargetToolName(input);
        if (StringUtils.isBlank(target)) {
            return ToolResult.error("<tool_use_error>InputValidationError: 'tool_name' is required</tool_use_error>");
        }
        if (NAME.equals(target)) {
            return ToolResult.error("<tool_use_error>Cannot recursively invoke ToolInvoke</tool_use_error>");
        }
        return toolRegistry.execute(target, extractTargetArguments(input), context);
    }

    public static String extractTargetToolName(JsonNode input) {
        if (input == null || !input.isObject()) return null;
        if (input.hasNonNull("tool_name")) return input.get("tool_name").asText("").trim();
        if (input.hasNonNull("name")) return input.get("name").asText("").trim();
        return null;
    }

    public static JsonNode extractTargetArguments(JsonNode input) {
        if (input == null || !input.isObject()) {
            return mapper().createObjectNode();
        }
        JsonNode args = input.get("arguments");
        if (args == null || args.isNull()) {
            args = input.get("input");
        }
        return args != null && !args.isNull() ? args : mapper().createObjectNode();
    }
}
