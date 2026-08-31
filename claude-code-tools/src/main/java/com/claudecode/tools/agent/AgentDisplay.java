package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.agent.AgentSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure display-logic helpers for the {@code /agents} list/detail UI — no I/O, no UI framework
 * dependency, independently testable.
 */
public final class AgentDisplay {

    private AgentDisplay() {}

    /** An agent definition plus the source that shadows it, if any. */
    public record ResolvedAgent(
        BuiltInAgentDefinitions.AgentDefinition agent,
        AgentSource overriddenBy
    ) {}

    /**
     * Pairs every definition in {@code allAgents} with the source that
     * currently shadows it (a same-named definition from
     * {@link AgentDefinitionLoader#getActive}), or {@code null} if it's the
     * active one. {@code allAgents} is expected in lowest-precedence-first
     * order (as returned by {@link AgentDefinitionLoader#getAll}).
     */
    public static List<ResolvedAgent> resolveOverrides(
            List<BuiltInAgentDefinitions.AgentDefinition> allAgents,
            List<BuiltInAgentDefinitions.AgentDefinition> activeAgents) {
        List<ResolvedAgent> out = new ArrayList<>(allAgents.size());
        for (BuiltInAgentDefinitions.AgentDefinition a : allAgents) {
            BuiltInAgentDefinitions.AgentDefinition active = activeAgents.stream()
                .filter(x -> x.agentType().equals(a.agentType()))
                .findFirst()
                .orElse(a);
            AgentSource overriddenBy = active.source() == a.source() ? null : active.source();
            out.add(new ResolvedAgent(a, overriddenBy));
        }
        return out;
    }

    public static int compareByName(BuiltInAgentDefinitions.AgentDefinition a, BuiltInAgentDefinitions.AgentDefinition b) {
        return a.agentType().compareToIgnoreCase(b.agentType());
    }

    /** {@code "inherit"} for null/`"inherit"`, otherwise the raw model value. */
    public static String resolveModelDisplay(BuiltInAgentDefinitions.AgentDefinition a) {
        String model = a.model();
        if (model == null || Strings.CS.equals(model, "inherit")) return "inherit";
        return model;
    }
}
