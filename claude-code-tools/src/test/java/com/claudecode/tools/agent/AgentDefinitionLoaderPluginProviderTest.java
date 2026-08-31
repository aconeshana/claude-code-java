package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.agent.AgentSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AgentDefinitionLoaderPluginProviderTest {

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        AgentDefinitionLoader.setPluginAgentsProvider(null);
    }

    private static BuiltInAgentDefinitions.AgentDefinition pluginAgent(String type) {
        return BuiltInAgentDefinitions.AgentDefinition.builder(type, "from a plugin")
            .tools(List.of("*")).systemPrompt("plugin prompt")
            .source(AgentSource.PLUGIN).build();
    }

    @Test
    void providerAgentsAppearBetweenBuiltInsAndCustom() {
        AgentDefinitionLoader.setPluginAgentsProvider(
            () -> List.of(pluginAgent("myplugin:reviewer")));

        List<BuiltInAgentDefinitions.AgentDefinition> all =
            AgentDefinitionLoader.getAll(tmp.toString());
        var match = all.stream()
            .filter(a -> Strings.CS.equals(a.agentType(), "myplugin:reviewer")).toList();
        assertEquals(1, match.size());
        assertEquals(AgentSource.PLUGIN, match.getFirst().source());

        int lastBuiltIn = -1;
        int pluginIdx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).source() == AgentSource.BUILT_IN) lastBuiltIn = i;
            if (Strings.CS.equals(all.get(i).agentType(), "myplugin:reviewer")) pluginIdx = i;
        }
        assertTrue(pluginIdx > lastBuiltIn, "plugin agents slot after built-ins");
    }

    @Test
    void projectAgentOverridesPluginAgentInGetActive() throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("shadow.md"), """
            ---
            name: shadow
            description: project version wins
            ---
            project prompt""");
        AgentDefinitionLoader.setPluginAgentsProvider(() -> List.of(pluginAgent("shadow")));

        var active = AgentDefinitionLoader.getActive(tmp.toString()).stream()
            .filter(a -> Strings.CS.equals(a.agentType(), "shadow")).toList();
        assertEquals(1, active.size());
        assertEquals(AgentSource.PROJECT, active.getFirst().source(),
            "custom agents override plugin agents (TS last-wins merge)");
    }

    @Test
    void clearingProviderRemovesPluginAgents() {
        AgentDefinitionLoader.setPluginAgentsProvider(
            () -> List.of(pluginAgent("myplugin:tmp")));
        assertTrue(AgentDefinitionLoader.getAll(tmp.toString()).stream()
            .anyMatch(a -> Strings.CS.equals(a.agentType(), "myplugin:tmp")));

        AgentDefinitionLoader.setPluginAgentsProvider(null);
        assertTrue(AgentDefinitionLoader.getAll(tmp.toString()).stream()
            .noneMatch(a -> Strings.CS.equals(a.agentType(), "myplugin:tmp")));
    }

    @Test
    void throwingProviderDegradesToNoPluginAgents() {
        AgentDefinitionLoader.setPluginAgentsProvider(() -> {
            throw new IllegalStateException("boom");
        });
        // Must not propagate — graceful degradation.
        List<BuiltInAgentDefinitions.AgentDefinition> all =
            AgentDefinitionLoader.getAll(tmp.toString());
        assertTrue(all.stream().noneMatch(a -> a.source() == AgentSource.PLUGIN));
    }
}
