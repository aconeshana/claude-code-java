package com.claudecode.core.tool;

import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Map;

/**
 * Canonical compatibility table for persisted and configured legacy tool names.
 */
public final class LegacyToolNames {

    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("Task", "Agent"),
        Map.entry("KillShell", "TaskStop"),
        Map.entry("KillBash", "TaskStop"),
        Map.entry("AgentOutputTool", "TaskOutput"),
        Map.entry("BashOutputTool", "TaskOutput"),
        Map.entry("AgentOutput", "TaskOutput"),
        Map.entry("BashOutput", "TaskOutput"),
        Map.entry("ListPeers", "ListAgents"),
        Map.entry("Brief", "SendUserMessage"),
        Map.entry("ListMcpResources", "ListMcpResourcesTool"),
        Map.entry("ReadMcpResource", "ReadMcpResourceTool"),
        Map.entry("ReadMcpResourceDir", "ReadMcpResourceDirTool")
    );

    private LegacyToolNames() {}

    public static String normalize(String name) {
        return ALIASES.getOrDefault(name, name);
    }

    public static List<String> legacyNames(String canonicalName) {
        return ALIASES.entrySet().stream()
            .filter(entry -> Strings.CS.equals(entry.getValue(), canonicalName))
            .map(Map.Entry::getKey)
            .toList();
    }
}
