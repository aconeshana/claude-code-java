package com.claudecode.services.plugins.runtime;

import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.prompt.OutputStyleConfig;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import com.claudecode.services.hooks.HookEvent;
import com.claudecode.services.hooks.HookMatcher;
import com.claudecode.services.plugins.marketplace.PluginError;
import com.claudecode.tools.workflows.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable result of one {@link PluginRuntimeLoader#loadAll} pass: every runtime component
 * contributed by the currently-enabled plugins, plus the errors collected along the way (graceful
 * degradation — a broken plugin never aborts the load of its siblings).
 */
public record PluginRuntimeSnapshot(
    List<PluginCommandDefinition> commands,
    List<BuiltInAgentDefinitions.AgentDefinition> agents,
    List<PluginSkillDir> skillDirs,
    List<OutputStyleConfig> outputStyles,
    Map<HookEvent, List<HookMatcher>> hooks,
    List<McpServerConfig> mcpServers,
    Map<String, JsonNode> lspServers,
    List<WorkflowDefinition> workflows,
    List<PluginError> errors,
    int enabledCount,
    int disabledCount) {

    public PluginRuntimeSnapshot {
        commands = List.copyOf(commands);
        agents = List.copyOf(agents);
        skillDirs = List.copyOf(skillDirs);
        outputStyles = List.copyOf(outputStyles);
        Map<HookEvent, List<HookMatcher>> hooksCopy = new LinkedHashMap<>();
        hooks.forEach((event, matchers) -> hooksCopy.put(event, List.copyOf(matchers)));
        hooks = Collections.unmodifiableMap(hooksCopy);
        mcpServers = List.copyOf(mcpServers);
        lspServers = Map.copyOf(lspServers);
        workflows = List.copyOf(workflows);
        errors = List.copyOf(errors);
    }

    /**
     * A plugin skill root ({@code skills/} dir or manifest skill path).
     * {@code directSkillName} is set only when a manifestless marketplace
     * cache root is itself the skill directory; its physical basename is a
     * version hash, while the model-visible logical name is the plugin name.
     */
    public record PluginSkillDir(
            String pluginName, Path directory, String directSkillName) {
        public PluginSkillDir(String pluginName, Path directory) {
            this(pluginName, directory, null);
        }
    }

    public static PluginRuntimeSnapshot empty() {
        return new PluginRuntimeSnapshot(
            List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), Map.of(),
            List.of(), List.of(), 0, 0);
    }


    public int hookCommandCount() {
        return hooks.values().stream()
            .flatMap(List::stream)
            .mapToInt(m -> m.hooks().size())
            .sum();
    }
}
