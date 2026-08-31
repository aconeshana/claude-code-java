package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves and buckets tool names for the {@code /agents} tools picker and validator.
 */
public final class AgentToolResolver {

    private AgentToolResolver() {}

    private static final Set<String> READ_ONLY = Set.of(
        "Glob", "Grep", "ExitPlanMode", "Read", "WebFetch", "TodoWrite", "WebSearch", "TaskStop");
    private static final Set<String> EDIT = Set.of("Edit", "Write", "NotebookEdit");
    private static final Set<String> EXECUTION = Set.of("Bash");
    private static final String MCP_PREFIX = "mcp__";
    private static final String SELF_TOOL_NAME = "Agent";

    public record Resolved(boolean hasWildcard, List<String> validTools, List<String> invalidTools) {}


    public static Resolved resolve(List<String> agentTools, Collection<String> availableToolNames) {
        if (agentTools == null || agentTools.contains("*")) {
            return new Resolved(true, List.copyOf(availableToolNames), List.of());
        }
        List<String> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String t : agentTools) {
            if (availableToolNames.contains(t)) valid.add(t);
            else invalid.add(t);
        }
        return new Resolved(false, valid, invalid);
    }

    public enum Bucket { READ_ONLY, EDIT, EXECUTION, MCP, OTHER }

    public record Bucketed(Map<Bucket, List<String>> byBucket) {}

    /** Groups {@code availableToolNames} into the 5 UI buckets, excluding the {@code Agent} tool itself. */
    public static Bucketed bucket(Collection<String> availableToolNames) {
        Map<Bucket, List<String>> byBucket = new EnumMap<>(Bucket.class);
        for (Bucket b : Bucket.values()) byBucket.put(b, new ArrayList<>());

        // Preserve insertion order, drop duplicates.
        for (String name : new LinkedHashSet<>(availableToolNames)) {
            if (SELF_TOOL_NAME.equals(name)) continue;
            Bucket b;
            if (READ_ONLY.contains(name)) b = Bucket.READ_ONLY;
            else if (EDIT.contains(name)) b = Bucket.EDIT;
            else if (EXECUTION.contains(name)) b = Bucket.EXECUTION;
            else if (Strings.CS.startsWith(name, MCP_PREFIX)) b = Bucket.MCP;
            else b = Bucket.OTHER;
            byBucket.get(b).add(name);
        }
        return new Bucketed(byBucket);
    }
}
