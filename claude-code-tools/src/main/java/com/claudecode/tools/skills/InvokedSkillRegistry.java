package com.claudecode.tools.skills;

import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Process-level record of which skills were invoked this session, kept so their guidance can be
 * re-attached to the model right after a compact summary discards the turn that originally invoked
 * them.
 */
public final class InvokedSkillRegistry {

    private static volatile InvokedSkillRegistry GLOBAL;

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public static InvokedSkillRegistry global() {
        InvokedSkillRegistry instance = GLOBAL;
        if (instance == null) {
            synchronized (InvokedSkillRegistry.class) {
                instance = GLOBAL;
                if (instance == null) {
                    instance = new InvokedSkillRegistry();
                    GLOBAL = instance;
                }
            }
        }
        return instance;
    }

    /**
     * Records (or refreshes) an invocation.
     */
    public synchronized void record(String agentId, Skill skill) {
        record(agentId, skill, skill.content());
    }

    /** Records the fully rendered/substituted skill body sent this invocation. */
    public synchronized void record(String agentId, Skill skill, String resolvedContent) {
        record(agentId, skill.name(), logicalPath(skill), resolvedContent);
    }

    /**
     * Records a dynamic prompt command using its logical command name/source.
     */
    public synchronized void record(String agentId, String name, String logicalPath,
                                    String resolvedContent) {
        Objects.requireNonNull(name, "name");
        String path = StringUtils.isBlank(logicalPath) ? name : logicalPath;
        entries.put(key(agentId, name), new Entry(
            agentId,
            name,
            path,
            resolvedContent == null ? "" : resolvedContent,
            Instant.now()));
    }

    private static String logicalPath(Skill skill) {
        String source = switch (skill.source()) {
            case USER -> "userSettings";
            case PROJECT -> "projectSettings";
            case MANAGED -> "policySettings";
            case BUILTIN -> "builtin";
            case BUNDLED -> "bundled";
            case MCP -> "mcp";
            case PLUGIN -> "plugin";
        };
        return source + ":" + skill.name();
    }

    /** Entries recorded for {@code agentId} (use {@code null} for the main session). */
    public synchronized List<Entry> entriesFor(String agentId) {
        return entries.values().stream()
            .filter(e -> Objects.equals(e.agentId(), agentId))
            .toList();
    }

    /**
     * Drops main-session invocations and all non-preserved agent entries on {@code /clear}.
     */
    public synchronized void clearForNewSession(Set<String> preservedAgentIds) {
        Set<String> preserved = preservedAgentIds == null ? Set.of() : preservedAgentIds;
        entries.entrySet().removeIf(entry -> {
            String agentId = entry.getValue().agentId();
            return agentId == null || !preserved.contains(agentId);
        });
    }

    /** Agent ids currently represented in the registry (main thread excluded). */
    public synchronized Set<String> agentIds() {
        return entries.values().stream()
            .map(Entry::agentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static String key(String agentId, String skillName) {
        return (agentId == null ? "" : agentId) + ":" + skillName;
    }

    public record Entry(String agentId, String name, String path, String content, Instant invokedAt) {}
}
