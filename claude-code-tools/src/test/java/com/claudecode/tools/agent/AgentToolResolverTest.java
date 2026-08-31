package com.claudecode.tools.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolResolverTest {

    private static final Set<String> AVAILABLE = Set.of(
        "Read", "Edit", "Write", "Bash", "Glob", "Grep", "WebFetch", "WebSearch",
        "ExitPlanMode", "TodoWrite", "TaskStop", "NotebookEdit", "Agent",
        "mcp__github__list_prs");

    @Test
    void resolve_nullTools_hasWildcard() {
        var r = AgentToolResolver.resolve(null, AVAILABLE);
        assertTrue(r.hasWildcard());
        assertEquals(AVAILABLE.size(), r.validTools().size());
    }

    @Test
    void resolve_wildcardMarker_hasWildcard() {
        var r = AgentToolResolver.resolve(List.of("*"), AVAILABLE);
        assertTrue(r.hasWildcard());
    }

    @Test
    void resolve_partitionsValidAndInvalid() {
        var r = AgentToolResolver.resolve(List.of("Read", "Bash", "NoSuchTool"), AVAILABLE);
        assertFalse(r.hasWildcard());
        assertEquals(List.of("Read", "Bash"), r.validTools());
        assertEquals(List.of("NoSuchTool"), r.invalidTools());
    }

    @Test
    void bucket_groupsJavaToolNamesCorrectly() {
        var b = AgentToolResolver.bucket(AVAILABLE).byBucket();
        assertTrue(b.get(AgentToolResolver.Bucket.READ_ONLY).containsAll(
            List.of("Glob", "Grep", "ExitPlanMode", "Read", "WebFetch", "TodoWrite", "WebSearch", "TaskStop")));
        assertTrue(b.get(AgentToolResolver.Bucket.EDIT).containsAll(List.of("Edit", "Write", "NotebookEdit")));
        assertEquals(List.of("Bash"), b.get(AgentToolResolver.Bucket.EXECUTION));
    }

    @Test
    void bucket_mcpToolsGroupedSeparately() {
        var b = AgentToolResolver.bucket(AVAILABLE).byBucket();
        assertEquals(List.of("mcp__github__list_prs"), b.get(AgentToolResolver.Bucket.MCP));
    }

    @Test
    void bucket_excludesSelf() {
        var b = AgentToolResolver.bucket(AVAILABLE).byBucket();
        for (var tools : b.values()) {
            assertFalse(tools.contains("Agent"), "Agent tool must not appear in any bucket");
        }
    }

    @Test
    void bucket_unknownToolFallsIntoOther() {
        var b = AgentToolResolver.bucket(Set.of("SomeFutureTool")).byBucket();
        assertEquals(List.of("SomeFutureTool"), b.get(AgentToolResolver.Bucket.OTHER));
    }
}
