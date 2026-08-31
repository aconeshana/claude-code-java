package com.claudecode.core.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyToolNamesTest {

    @Test
    void exposesTheReleased197AliasTableInBothDirections() {
        assertEquals("Agent", LegacyToolNames.normalize("Task"));
        assertEquals("TaskStop", LegacyToolNames.normalize("KillBash"));
        assertEquals("TaskOutput", LegacyToolNames.normalize("AgentOutput"));
        assertEquals("ListAgents", LegacyToolNames.normalize("ListPeers"));
        assertEquals("SendUserMessage", LegacyToolNames.normalize("Brief"));
        assertEquals("ListMcpResourcesTool", LegacyToolNames.normalize("ListMcpResources"));
        assertEquals("ReadMcpResourceTool", LegacyToolNames.normalize("ReadMcpResource"));
        assertEquals("ReadMcpResourceDirTool", LegacyToolNames.normalize("ReadMcpResourceDir"));
        assertEquals("Read", LegacyToolNames.normalize("Read"));

        assertTrue(LegacyToolNames.legacyNames("TaskOutput").contains("AgentOutputTool"));
        assertTrue(LegacyToolNames.legacyNames("TaskOutput").contains("BashOutput"));
    }
}
