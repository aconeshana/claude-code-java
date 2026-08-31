package com.claudecode.core.attachment;

import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.claudecode.core.message.AgentListingDeltaAttachment;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.Message;

/**
 * Diffs the active agent pool against what has already been announced in prior {@code
 * agent_listing_delta} attachments, emitting only the added/removed types.
 */
public final class AgentListingDeltaAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "agent_listing_delta";
    }

    @Override
    public boolean isEnabled(FeatureFlagRegistry flags) {
        return flags.isEnabled(FeatureFlag.AGENT_LISTING_DELTA);
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        List<AgentDefinition> active = ctx.activeAgents();
        if (active == null) active = List.of();
// The Agent tool must be in the pool or the listing is unactionable.
        if (ctx.toolNames() == null || ctx.toolNames().stream().noneMatch("Agent"::equals)) {
            return List.of();
        }

        Set<String> availableMcpServers = availableMcpServers(ctx);
        active = active.stream()
            .filter(a -> hasRequiredMcpServers(a, availableMcpServers))
            .toList();

        // Reconstruct the announced set from prior deltas in the transcript.
        Set<String> announced = new HashSet<>();
        for (Message m : ctx.messages()) {
            if (!(m instanceof AttachmentMessage am)) continue;
            if (am.payload() instanceof AgentListingDeltaAttachment prev) {
                announced.addAll(prev.addedTypes());
                prev.removedTypes().forEach(announced::remove);
            }
        }

        Set<String> currentTypes = new TreeSet<>();
        for (AgentDefinition a : active) {
            if (a.agentType() != null) currentTypes.add(a.agentType());
        }

        List<AgentDefinition> added = new ArrayList<>();
        for (AgentDefinition a : active) {
            if (a.agentType() != null && !announced.contains(a.agentType())) {
                added.add(a);
            }
        }
        added.sort((x, y) -> x.agentType().compareToIgnoreCase(y.agentType()));

        List<String> removed = new ArrayList<>();
        for (String t : announced) {
            if (!currentTypes.contains(t)) removed.add(t);
        }
        removed.sort(String::compareToIgnoreCase);

        if (added.isEmpty() && removed.isEmpty()) {
            return List.of();
        }

        List<String> addedLines = new ArrayList<>(added.size());
        for (AgentDefinition a : added) addedLines.add(formatAgentLine(a));
        boolean isInitial = announced.isEmpty();

        return List.of(new AgentListingDeltaAttachment(
            added.stream().map(AgentDefinition::agentType).toList(),
            addedLines,
            removed,
            isInitial,
            true));
    }


    private static String formatAgentLine(AgentDefinition agent) {
        return agent.toPromptLine(true);
    }

    /**
     * Parses {@code mcp__<server>__<tool>}-prefixed tool names into their declaring server ids.
     */
    private static Set<String> availableMcpServers(AttachmentContext ctx) {
        if (ctx.toolNames() == null) return Set.of();
        Set<String> servers = new HashSet<>();
        for (String name : ctx.toolNames()) {
            if (!Strings.CS.startsWith(name, "mcp__")) continue;
            String remainder = name.substring("mcp__".length());
            int sep = remainder.indexOf("__");
            if (sep > 0) servers.add(remainder.substring(0, sep));
        }
        return servers;
    }

/**
     * Case-insensitive substring match.
     */
    private static boolean hasRequiredMcpServers(AgentDefinition agent, Set<String> available) {
        if (agent.mcpServers() == null || agent.mcpServers().isEmpty()) return true;
        return agent.mcpServers().stream().allMatch(required ->
            available.stream().anyMatch(a -> Strings.CI.contains(a, required)));
    }
}
