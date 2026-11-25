package com.claudecode.core.attachment;

import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SkillListingAttachment;
import com.claudecode.core.message.SkillListingEntry;

/**
 * Bidirectional delta-based skill listing for the Skill tool.
 * Announces newly enabled skills as well as skills disabled mid-session without touching
 * the system prompt or top-level tool definitions.
 */
public final class SkillListingAttachmentProvider implements AttachmentProvider {

    private final Map<String, Set<String>> activeByAgent = new LinkedHashMap<>();

    @Override
    public String name() {
        return "skill_listing";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        if (ctx.toolNames() == null
                || ctx.toolNames().stream().noneMatch(t -> Strings.CS.equals("Skill", t))) {
            return List.of();
        }
        List<SkillListingEntry> skills = ctx.skills() != null ? ctx.skills() : List.of();

        String agentKey = ctx.agentId() != null ? ctx.agentId() : "";
        Set<String> active = activeByAgent.computeIfAbsent(agentKey, _ -> new LinkedHashSet<>());

        // Reconstruct announced active set from persisted skill_listing attachments when
        // resuming a session into a fresh provider instance.
        if (active.isEmpty() && ctx.messages() != null) {
            for (Message message : ctx.messages()) {
                if (message instanceof AttachmentMessage attachment
                        && attachment.payload() instanceof SkillListingAttachment previous) {
                    active.addAll(previous.names());
                    previous.removedNames().forEach(active::remove);
                }
            }
        }

        Set<String> currentNames = new LinkedHashSet<>();
        for (SkillListingEntry s : skills) {
            currentNames.add(s.name());
        }

        List<SkillListingEntry> addedSkills = new ArrayList<>();
        for (SkillListingEntry s : skills) {
            if (!active.contains(s.name())) {
                addedSkills.add(s);
            }
        }

        List<String> removedNames = new ArrayList<>();
        for (String prev : active) {
            if (!currentNames.contains(prev)) {
                removedNames.add(prev);
            }
        }

        if (addedSkills.isEmpty() && removedNames.isEmpty()) {
            return List.of();
        }

        boolean isInitial = active.isEmpty();
        for (SkillListingEntry s : addedSkills) {
            active.add(s.name());
        }
        removedNames.forEach(active::remove);

        String content = addedSkills.isEmpty()
            ? ""
            : SkillListingFormatter.formatWithinBudget(addedSkills);
        List<String> addedNames = addedSkills.stream().map(SkillListingEntry::name).toList();
        return List.of(new SkillListingAttachment(
            content, addedSkills.size(), isInitial, addedNames, List.copyOf(removedNames)));
    }
}
