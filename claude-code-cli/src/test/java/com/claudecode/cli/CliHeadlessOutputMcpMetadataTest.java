package com.claudecode.cli;

import com.claudecode.tools.mcp.McpToolProvider;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliHeadlessOutputMcpMetadataTest {

    @Test
    void sdkAgentCatalogPlacesCustomAgentsBeforeStableBuiltIns() {
        assertEquals(List.of(
                "bgplan", "reviewer", "claude", "Explore", "general-purpose",
                "Plan", "statusline-setup"),
            CliHeadlessOutput.orderSdkAgentNames(List.of(
                "general-purpose", "statusline-setup", "Explore", "Plan",
                "claude", "bgplan", "reviewer", "claude-code-guide")));
    }

    @Test
    void liveMcpRuntimePopulatesInitServersAndPromptCommands() {
        var base = new StdoutMessageWriter.SdkOutputMetadata(
            "session", "/tmp/project", "claude-sonnet-4-6", "bypassPermissions",
            List.of("Read"), List.of(), List.of("compact"), "ANTHROPIC_API_KEY",
            "2.1.197", "default", List.of(), List.of(), List.of());
        McpToolProvider provider = new McpToolProvider() {
            @Override
            public Map<String, String> snapshotServerStatuses() {
                Map<String, String> statuses = new TreeMap<>();
                statuses.put("alpha", "connected");
                statuses.put("zeta", "pending");
                return statuses;
            }

            @Override
            public List<String> promptCommandNames() {
                return List.of("mcp__alpha__greet", "mcp__zeta__search");
            }
        };

        var result = CliHeadlessOutput.withMcpRuntime(base, provider);

        assertEquals(List.of(
                new StdoutMessageWriter.McpServerStatus("alpha", "connected"),
                new StdoutMessageWriter.McpServerStatus("zeta", "pending")),
            result.mcpServers());
        assertEquals(List.of("compact", "mcp__alpha__greet", "mcp__zeta__search"),
            result.slashCommands());
    }
}
