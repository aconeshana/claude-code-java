package com.claudecode.core.attachment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.message.AgentMentionAttachment;
import com.claudecode.core.message.AttachmentPayload;

/**
 * Detects {@code @agent-<type>} mentions in the user's input and emits an {@code agent_mention}
 * attachment for each matched, known agent type.
 */
public final class AgentMentionAttachmentProvider implements AttachmentProvider {

    // @agent-<type> (type may include colons/dots/@ for plugin-scoped agents).
    private static final Pattern LEGACY = Pattern.compile("@agent-([\\w:.@-]+)");
    // @"<type> (agent)" (autocomplete-selected).
    private static final Pattern QUOTED = Pattern.compile("@\"([\\w:.@-]+) \\(agent\\)\"");

    @Override
    public String name() {
        return "agent_mention";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        String input = ctx.input();
        if (StringUtils.isBlank(input)) {
            return List.of();
        }
        List<AgentDefinition> agents = ctx.activeAgents();
        Set<String> knownTypes = new LinkedHashSet<>();
        if (agents != null) {
            for (AgentDefinition a : agents) {
                if (a.agentType() != null) knownTypes.add(a.agentType());
            }
        }

        Set<String> mentioned = new LinkedHashSet<>();
        addMatches(mentioned, LEGACY.matcher(input));
        addMatches(mentioned, QUOTED.matcher(input));

        List<AttachmentPayload> out = new ArrayList<>();
        for (String type : mentioned) {
            if (knownTypes.contains(type)) {
                out.add(new AgentMentionAttachment(type));
            }
        }
        return out;
    }

    private static void addMatches(Set<String> sink, Matcher m) {
        while (m.find()) {
            sink.add(m.group(1));
        }
    }
}
