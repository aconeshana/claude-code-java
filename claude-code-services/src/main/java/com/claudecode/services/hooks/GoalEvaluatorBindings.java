package com.claudecode.services.hooks;

import com.claudecode.api.ApiProviderResolver;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.services.model.GoalContextWindowPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Request-shaping inputs the Stop-condition evaluator borrows from the main
 * session so its side query looks like the session's own traffic: system-prompt
 * identity, Messages API metadata, effort, tool definitions, and the model's
 * context window used for transcript budgeting. Every read is fail-safe so a
 * broken supplier degrades to the unbound default instead of failing the hook.
 *
 * <ul>
 *   <li>{@code src/utils/hooks/execPromptHook.ts} — Stop-hook evaluator request
 *       parameters mirrored from the main query (effort, tools, metadata).</li>
 *   <li>{@code src/utils/hooks/execPromptHook.ts} — transcript budget derived
 *       from the evaluator model's context window.</li>
 * </ul>
 */
public final class GoalEvaluatorBindings {

    static final long DEFAULT_CONTEXT_WINDOW = 200_000L;

    private volatile Supplier<String> identitySupplier = () -> "";
    private volatile Supplier<JsonNode> metadataSupplier = () -> null;
    private volatile Supplier<String> effortSupplier = () -> null;
    private volatile Supplier<List<CreateMessageRequest.ToolDefinition>> toolsSupplier = List::of;
    private volatile ToLongFunction<String> contextWindowResolver =
        GoalEvaluatorBindings::defaultContextWindow;

    /**
     * Binds the per-session request parameters in one call.
     *
     * @param identity system-prompt identity prefix prepended to the evaluator system prompt
     * @param metadata Messages API metadata shared with the main session request
     * @param effort   resolved request effort used both on the wire and in Stop-hook ARGUMENTS
     * @param tools    model-visible tool definitions mirrored onto the evaluator request
     */
    public void bind(Supplier<String> identity, Supplier<JsonNode> metadata,
                     Supplier<String> effort,
                     Supplier<List<CreateMessageRequest.ToolDefinition>> tools) {
        this.identitySupplier = identity != null ? identity : () -> "";
        this.metadataSupplier = metadata != null ? metadata : () -> null;
        this.effortSupplier = effort != null ? effort : () -> null;
        this.toolsSupplier = tools != null ? tools : List::of;
    }

    /** Resolves the model's effective context window for transcript budgeting. */
    public void setContextWindowResolver(ToLongFunction<String> resolver) {
        this.contextWindowResolver = resolver != null
            ? resolver : GoalEvaluatorBindings::defaultContextWindow;
    }

    /** Snapshot for a child agent dispatcher whose effort comes from the agent definition. */
    GoalEvaluatorBindings withEffort(Supplier<String> effort) {
        GoalEvaluatorBindings child = new GoalEvaluatorBindings();
        child.identitySupplier = identitySupplier;
        child.metadataSupplier = metadataSupplier;
        child.effortSupplier = effort != null ? effort : () -> null;
        child.toolsSupplier = toolsSupplier;
        child.contextWindowResolver = contextWindowResolver;
        return child;
    }

    String identity() {
        try {
            String identity = identitySupplier.get();
            return identity == null ? "" : identity;
        } catch (RuntimeException _) {
            return "";
        }
    }

    JsonNode metadata() {
        try {
            return metadataSupplier.get();
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** Bound effort, else {@code "high"} for Anthropic models and {@code null} otherwise. */
    String effort(String currentModel) {
        try {
            String effort = effortSupplier.get();
            if (StringUtils.isNotBlank(effort)) return effort;
            return currentModel != null && Strings.CI.contains(currentModel, "claude-") ? "high" : null;
        } catch (RuntimeException _) {
            return null;
        }
    }

    List<CreateMessageRequest.ToolDefinition> tools() {
        try {
            List<CreateMessageRequest.ToolDefinition> tools = toolsSupplier.get();
            return tools == null ? List.of() : List.copyOf(tools);
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    long contextWindow(String model) {
        long contextWindow;
        try {
            contextWindow = contextWindowResolver.applyAsLong(model);
        } catch (RuntimeException _) {
            contextWindow = DEFAULT_CONTEXT_WINDOW;
        }
        return contextWindow <= 0L ? DEFAULT_CONTEXT_WINDOW : contextWindow;
    }

    private static long defaultContextWindow(String model) {
        return GoalContextWindowPolicy.contextWindow(model, ApiProviderResolver.resolve(),
            SubprocessEnvironment.get("ANTHROPIC_BASE_URL"));
    }
}
