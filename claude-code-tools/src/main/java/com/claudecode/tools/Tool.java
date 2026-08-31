package com.claudecode.tools;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;

/**
 * Abstract base class for all tools.
 */
public abstract class Tool<I, O> {

    /** Returns the immutable canonical name and input-only aliases. */
    public abstract ToolIdentity identity();

    /** Returns the unique canonical tool name. */
    public final String name() {
        return identity().name();
    }

    /**
     * Alternate input-only names accepted by the executor.
     */
    public final List<String> aliases() {
        return identity().aliases();
    }

    /** Returns a human-readable description of the tool. */
    public abstract String description();

    /**
     * Input/context-aware description hook.
     */
    public String description(JsonNode input, ToolExecutionContext context) {
        return description();
    }

    /**
     * Dynamic prompt hook used by deferred discovery and MCP prompt builders.
     * The default is the ordinary tool description, matching the compatibility builder's
     * fallback for tools that do not provide a separate prompt.
     */
    public String prompt(ToolExecutionContext context) {
        return description();
    }

    /** Returns the JSON Schema describing valid inputs. */
    public abstract JsonNode inputSchema();

    /** Optional one-line capability hint consumed by ToolSearch. */
    public String searchHint() {
        return "";
    }

    /** Whether this tool must be fully loaded on the first request. */
    public boolean alwaysLoad() {
        return false;
    }

    /** Whether the operation is irreversible for the supplied input. */
    public boolean isDestructive(JsonNode input) {
        return false;
    }

    /** Whether the operation may access an open-world external target. */
    public boolean isOpenWorld(JsonNode input) {
        return false;
    }

    /** Search/read/list classification used by condensed tool rendering. */
    public SearchReadClassification searchReadClassification(JsonNode input) {
        return SearchReadClassification.NONE;
    }

    /** Whether this is an MCP proxy tool. */
    public boolean isMcp() {
        return false;
    }

    /** Whether this is an LSP proxy tool. */
    public boolean isLsp() {
        return false;
    }

    /** Dynamic MCP server/tool metadata; null for non-MCP tools. */
    public ToolMcpInfo mcpInfo() {
        return null;
    }

    /** Optional strict-schema marker used by API adapters. Static tools declare it in {@link BuiltInTool}. */
    public boolean strict() {
        return false;
    }

    /** Executes the tool with the given input and context. */
    public abstract O call(I input, ToolExecutionContext context);

    /**
     * Executes the tool once and returns both its public Java value and optional
     * model-facing mapping. The default keeps the historical {@link #call} plus
     * {@link #mapResult} contract; tools whose mapping needs per-invocation state
     * override this method and keep that state in ordinary local variables.
     */
    public ToolCallResult<O> callWithResult(I input, ToolExecutionContext context) {
        O rawResult = call(input, context);
        JsonNode jsonInput = input instanceof JsonNode node ? node : null;
        return new ToolCallResult<>(rawResult, mapResult(rawResult, jsonInput, context));
    }

    /**
     * Optional final result mapping hook.
     */
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        return null;
    }

    /** Optional JSON schema for a PostToolUse replacement. */
    public JsonNode outputSchema() {
        return null;
    }

    /**
     * Maps a hook-provided replacement through the same public result contract.
     * Java tools commonly expose textual raw values, so the default first gives
     * their ordinary mapper that representation and then falls back to a plain
     * JSON/text result when no specialized mapping applies.
     */
    public ToolResult mapUpdatedOutput(JsonNode updatedOutput, JsonNode input,
                                       ToolExecutionContext context) {
        Object raw = updatedOutput != null && updatedOutput.isTextual()
            ? updatedOutput.asText() : updatedOutput;
        ToolResult mapped = mapResult(raw, input, context);
        if (mapped != null) return mapped.withToolUseResult(updatedOutput);
        String rendered = updatedOutput != null && updatedOutput.isTextual()
            ? updatedOutput.asText() : String.valueOf(updatedOutput);
        return ToolResult.success(rendered).withToolUseResult(updatedOutput);
    }


    public ValidationResult validateInput(I input, ToolExecutionContext context) {
        return ValidationResult.valid();
    }

    /**
     * Checks whether the tool is allowed to execute with the given input.
     * Default implementation returns ASK.
     */
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.ask();
    }

    /**
     * Returns the compact security-relevant projection used by the auto-mode transcript classifier.
     */
    public Object toAutoClassifierInput(JsonNode input) {
        return "";
    }

    /**
     * Whether this tool is safe for concurrent (parallel) execution.
     */
    public boolean isConcurrencySafe() {
        return false;
    }

    /**
     * Input-aware concurrency check.
     */
    public boolean isConcurrencySafe(I input) {
        return isConcurrencySafe();
    }

    /**
     * Whether this tool only reads data (no side effects). Default false;
     * class-invariant built-in values are declared in {@link BuiltInTool}.
     */
    public boolean isReadOnly() {
        return false;
    }

    /** Input-aware read-only classification. */
    public boolean isReadOnly(I input) {
        return isReadOnly();
    }

    /**
     * Whether this tool's schema is withheld from the model's first request.
     */
    public boolean shouldDefer() {
        return false;
    }

    /** Whether this tool is currently enabled. Default true. */
    public boolean isEnabled() {
        return true;
    }

    /**
     * Maximum size in characters for this tool's result before it is capped.
     */
    public int maxResultSizeChars() {
        return 100_000;
    }

    /**
     * Upper clamp applied by shared large-result persistence.
     */
    public int persistenceThresholdCeiling() {
        return 50_000;
    }

    /**
     * Whether this tool requires a human in the loop and must never be auto-approved (e.g.
     */
    public boolean requiresUserInteraction() {
        return false;
    }

    /**
     * Whether this tool acts as a transparent wrapper whose inner calls are shown directly — the
     * wrapper itself is hidden from the UI.
     */
    public boolean isTransparentWrapper() {
        return false;
    }

    /**
     * Optional extra metadata tag rendered after the tool name in the TUI, e.g.
     */
    public Optional<ToolUseTag> renderToolUseTag(JsonNode input, ToolUseRenderContext context) {
        return Optional.empty();
    }

    /**
     * Helper to create a simple JSON Schema object node.
     */
    protected static ObjectNode createObjectSchema() {
        ObjectNode schema = JsonUtils.getMapper().createObjectNode();
        schema.put("type", "object");
        schema.set("properties", JsonUtils.getMapper().createObjectNode());
        return schema;
    }

    /**
     * Helper to get the shared ObjectMapper.
     */
    protected static ObjectMapper mapper() {
        return JsonUtils.getMapper();
    }


    public record SearchReadClassification(boolean isSearch, boolean isRead, boolean isList) {
        public static final SearchReadClassification NONE = new SearchReadClassification(false, false, false);
    }

    /** MCP metadata preserved from discovery, without forcing MCP onto static tools. */
    public record ToolMcpInfo(String serverName, String toolName) {
    }
}
