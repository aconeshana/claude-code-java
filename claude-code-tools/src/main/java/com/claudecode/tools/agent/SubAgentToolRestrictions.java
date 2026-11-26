package com.claudecode.tools.agent;

import com.claudecode.core.engine.PostToolUseOutputResult;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.message.Message;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.tools.ToolErrors;
import com.claudecode.tools.output.SyntheticOutputTool;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Enforces sub-agent tool allow/disallow sets, recursion depth limits, and
 * structured-output tool injection via {@link RestrictedToolExecutor}.
 */
final class SubAgentToolRestrictions {

    private SubAgentToolRestrictions() {}

    static ToolExecutor createRestrictedToolExecutor(ToolExecutor toolExecutor, 
            SubAgentRequest request, List<String> effectiveToolNames,
            ToolExecutionContext toolDefinitionContext) {
        // Defense-in-depth: even if a caller manages to put an interactive / coordination tool
        // (AskUserQuestion, ExitPlanMode,...) into the sub-agent's tool list, the restricted
        // executor refuses it and hides it from the model.
        Set<String> requested = new LinkedHashSet<>(effectiveToolNames);

        // including Agent; recursive fork attempts are rejected by the injected
        // fork directive/history guard rather than by changing this definition.
        Set<String> allowed = new HashSet<>(requested);
        Set<String> denied = request.fork()
            ? new HashSet<>()
            : new HashSet<>(AgentTool.AGENT_DISALLOWED_TOOLS);
        if (!request.fork()
                && request.agentDepth() >= normalizedMaxDepth(
                    request.subagentMaxDepthSnapshot())) {
            denied.add("Agent");
        }
        boolean synchronousAgent = !request.async();
        if (synchronousAgent) {
            denied.remove("TaskStop");
        }
        if (!request.fork()) {
            allowed.removeAll(AgentTool.AGENT_DISALLOWED_TOOLS);
            if (synchronousAgent && requested.contains("TaskStop")) {
                allowed.add("TaskStop");
            }
        }
        if (request.disallowedTools() != null) {
            denied.addAll(request.disallowedTools());
        }
        allowed.removeAll(denied);
        SyntheticOutputTool structuredOutput = null;
        if (request.jsonSchema() != null) {
            SyntheticOutputTool.CreateResult created = SyntheticOutputTool.create(request.jsonSchema());
            if (created instanceof SyntheticOutputTool.CreateResult.Ok(SyntheticOutputTool tool)) {
                structuredOutput = tool;
            } else if (created instanceof SyntheticOutputTool.CreateResult.Err(String error)) {
                throw new IllegalArgumentException(error);
            }
        }
        List<String> ordered = effectiveToolNames.stream()
            .filter(StringUtils::isNotBlank)
            .filter(name -> !denied.contains(name))
            .distinct()
            .toList();
        return RestrictedToolExecutor.create(toolExecutor, allowed, denied,
            structuredOutput, ordered, toolDefinitionContext);
    }

    static List<String> effectiveToolNames(ToolExecutor toolExecutor, SubAgentRequest request) {
        List<String> source = request.tools().isEmpty()
            ? toolExecutor.getToolDefinitions().stream()
                .map(StreamingClient.StreamRequest.ToolDef::name).toList()
            : request.tools();
        Set<String> denied = new HashSet<>(request.fork()
            ? Set.of() : AgentTool.AGENT_DISALLOWED_TOOLS);
        if (!request.fork()
                && request.agentDepth() >= normalizedMaxDepth(
                    request.subagentMaxDepthSnapshot())) {
            denied.add("Agent");
        }
        if (request.disallowedTools() != null) denied.addAll(request.disallowedTools());
        return source.stream()
            .filter(StringUtils::isNotBlank)
            .filter(name -> !denied.contains(name))
            .distinct()
            .toList();
    }

    static int normalizedMaxDepth(Integer configured) {
        return configured != null && configured >= 1 && configured <= 5
            ? configured : 2;
    }

    /**
     * Restricted tool executor that only allows specified tools.
     */
    private record RestrictedToolExecutor(ToolExecutor delegate,
                                          Set<String> allowedTools,
                                          Set<String> deniedTools,
                                          SyntheticOutputTool structuredOutput,
                                          List<String> orderedToolNames,
                                          List<StreamingClient.StreamRequest.ToolDef> eagerDefinitions,
                                          List<StreamingClient.StreamRequest.ToolDef> deferredDefinitions)
            implements ToolExecutor {

        static RestrictedToolExecutor create(
                ToolExecutor delegate, Set<String> allowedTools,
                Set<String> deniedTools, SyntheticOutputTool structuredOutput,
                List<String> orderedToolNames,
                ToolExecutionContext definitionContext) {
            Set<String> allowedSnapshot = Set.copyOf(allowedTools);
            Set<String> deniedSnapshot = Set.copyOf(deniedTools);
            List<String> orderSnapshot = List.copyOf(orderedToolNames);
            List<StreamingClient.StreamRequest.ToolDef> eager = freezeDefinitions(
                delegate.getToolDefinitions(definitionContext), allowedSnapshot,
                deniedSnapshot, structuredOutput, orderSnapshot);
            List<StreamingClient.StreamRequest.ToolDef> deferred = freezeDefinitions(
                delegate.getToolDefinitions(new LinkedHashSet<>(orderSnapshot),
                    definitionContext),
                allowedSnapshot, deniedSnapshot, structuredOutput, orderSnapshot);
            return new RestrictedToolExecutor(delegate, allowedSnapshot,
                deniedSnapshot, structuredOutput, orderSnapshot, eager, deferred);
        }

        @Override
        public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context) {
            if (structuredOutput != null && SyntheticOutputTool.NAME.equals(toolName)) {
                try {
                    Object value = structuredOutput.call(input, context);
                    if (value instanceof StructuredToolOutput(String text, Object toolUseResult)) {
                        return ToolResult.success(text).withToolUseResult(toolUseResult);
                    }
                    return ToolResult.success(value == null ? "" : value.toString());
                } catch (Exception e) {
                    return ToolResult.error(ToolErrors.formatError(e));
                }
            }
            if (deniedTools.contains(toolName)
                    || !allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
                return ToolResult.error(
                    "Tool '" + toolName + "' is not allowed for this sub-agent");
            }
            return delegate.execute(toolName, input, context);
        }

        @Override
        public PostToolUseOutputResult processPostToolUseOutput(
                String toolName, JsonNode originalInput, JsonNode updatedOutput,
                ToolResult originalResult, ToolExecutionContext context) {
            if (deniedTools.contains(toolName)
                    || !allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
                return new PostToolUseOutputResult.Rejected(
                    "Tool '" + toolName + "' is not allowed for this sub-agent");
            }
            return delegate.processPostToolUseOutput(
                toolName, originalInput, updatedOutput, originalResult, context);
        }

        @Override
        public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
            return eagerDefinitions;
        }

        @Override
        public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                ToolExecutionContext context) {
            return eagerDefinitions;
        }

        @Override
        public List<Message> applyToolResultBudget(List<Message> messages,
                String sessionId, String workingDirectory, String agentId) {
            return delegate.applyToolResultBudget(
                messages, sessionId, workingDirectory, agentId);
        }

        @Override
        public void restoreToolResultBudget(List<Message> messages,
                List<ToolResultBudget.Replacement> replacements,
                String sessionId, String workingDirectory, String agentId) {
            delegate.restoreToolResultBudget(
                messages, replacements, sessionId, workingDirectory, agentId);
        }

        @Override
        public List<ToolResultBudget.Replacement>
                drainToolResultBudgetReplacements(
                    String sessionId, String workingDirectory, String agentId) {
            return delegate.drainToolResultBudgetReplacements(
                sessionId, workingDirectory, agentId);
        }

        @Override
        public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                Set<String> discoveredToolNames, ToolExecutionContext context) {
            Set<String> discovered = discoveredToolNames != null
                ? discoveredToolNames : Set.of();
            return deferredDefinitions.stream()
                .filter(def -> !def.deferLoading() || discovered.contains(def.name()))
                .toList();
        }

        private static List<StreamingClient.StreamRequest.ToolDef> freezeDefinitions(
                List<StreamingClient.StreamRequest.ToolDef> source,
                Set<String> allowedTools, Set<String> deniedTools,
                SyntheticOutputTool structuredOutput,
                List<String> orderedToolNames) {
            List<StreamingClient.StreamRequest.ToolDef> definitions = new ArrayList<>(
                source.stream()
                .filter(def -> !deniedTools.contains(def.name()))
                .filter(def -> allowedTools.isEmpty() || allowedTools.contains(def.name()))
                .map(RestrictedToolExecutor::copyDefinition)
                .toList());
            if (structuredOutput != null) {
                definitions.add(new StreamingClient.StreamRequest.ToolDef(
                    structuredOutput.name(), structuredOutput.description(),
                    structuredOutput.inputSchema()));
            }
            if (!orderedToolNames.isEmpty()) {
                Map<String, Integer> order = new HashMap<>();
                for (int i = 0; i < orderedToolNames.size(); i++) {
                    order.putIfAbsent(orderedToolNames.get(i), i);
                }
                definitions.sort(Comparator
                    .comparingInt((StreamingClient.StreamRequest.ToolDef def) ->
                        order.getOrDefault(def.name(), Integer.MAX_VALUE))
                    .thenComparing(StreamingClient.StreamRequest.ToolDef::name));
            }
            return List.copyOf(definitions);
        }

        private static StreamingClient.StreamRequest.ToolDef copyDefinition(
                StreamingClient.StreamRequest.ToolDef definition) {
            Object schema = definition.inputSchema();
            if (schema instanceof JsonNode node) {
                schema = node.deepCopy();
            } else if (schema != null) {
                schema = JsonUtils.getMapper().valueToTree(schema);
            }
            return new StreamingClient.StreamRequest.ToolDef(
                definition.name(), definition.description(), schema,
                definition.type(), definition.maxUses(),
                definition.allowedDomains() == null ? null
                    : List.copyOf(definition.allowedDomains()),
                definition.blockedDomains() == null ? null
                    : List.copyOf(definition.blockedDomains()),
                definition.deferLoading(), definition.strict(),
                definition.eagerInputStreaming());
        }
    }
}
