package com.claudecode.tools.agent;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reverse index from an MCP server name to the custom-agent files that declare it under their
 * frontmatter {@code mcpServers:} field.
 */
public final class AgentMcpServerIndex {

    private AgentMcpServerIndex() {}

    /**
     * Returns the sorted, deduplicated list of custom-agent names that reference {@code serverName} in
     * their frontmatter {@code mcpServers} list.
     */
    public static List<String> usedByForServer(String cwd, String serverName) {
        if (StringUtils.isBlank(serverName)) return List.of();
        List<BuiltInAgentDefinitions.AgentDefinition> all =
            AgentDefinitionLoader.getAll(cwd);
        List<String> names = new ArrayList<>();
        for (BuiltInAgentDefinitions.AgentDefinition a : all) {
            if (a.mcpServers() != null && a.mcpServers().contains(serverName)) {
                if (!names.contains(a.agentType())) names.add(a.agentType());
            }
        }
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    /**
     * Bulk variant — returns {@code serverName → List<agentName>} for every
     * server referenced by any custom agent. Useful to precompute the entire
     * mapping when rendering the {@code /mcp} list panel.
     */
    public static Map<String, List<String>> buildIndex(String cwd) {
        Map<String, List<String>> byServer = new LinkedHashMap<>();
        for (BuiltInAgentDefinitions.AgentDefinition a : AgentDefinitionLoader.getAll(cwd)) {
            if (a.mcpServers() == null || a.mcpServers().isEmpty()) continue;
            for (String server : a.mcpServers()) {
                if (StringUtils.isBlank(server)) continue;
                byServer.computeIfAbsent(server, _ -> new ArrayList<>())
                    .add(a.agentType());
            }
        }
        // Sort each bucket + freeze.
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : byServer.entrySet()) {
            List<String> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.naturalOrder());
            frozen.put(e.getKey(), List.copyOf(sorted));
        }
        return Map.copyOf(frozen);
    }
}
