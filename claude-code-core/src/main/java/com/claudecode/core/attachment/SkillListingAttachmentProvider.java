package com.claudecode.core.attachment;

import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SkillListingAttachment;
import com.claudecode.core.message.SkillListingEntry;

/**
 * Delta-based skill listing for the Skill tool.
 */
public final class SkillListingAttachmentProvider implements AttachmentProvider {

    private final Map<String, Set<String>> sentByAgent = new LinkedHashMap<>();

    @Override
    public String name() {
        return "skill_listing";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        List<SkillListingEntry> skills = ctx.skills();
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }

        if (ctx.toolNames() == null
                || ctx.toolNames().stream().noneMatch(t -> Strings.CS.equals("Skill", t))) {
            return List.of();
        }
        String agentKey = ctx.agentId() != null ? ctx.agentId() : "";
        Set<String> sent = sentByAgent.computeIfAbsent(agentKey, _ -> new HashSet<>());
// The provider instance is new after process restart, so reconstruct the
// announced set from persisted skill_listing
        // attachments. Without this scan, --resume/--continue re-emits the
        // entire initial skill catalogue into JSONL even though it is already
        // present in the restored conversation.
        for (Message message : ctx.messages()) {
            if (message instanceof AttachmentMessage attachment
                    && attachment.payload() instanceof SkillListingAttachment previous) {
                sent.addAll(previous.names());
            }
        }
        List<SkillListingEntry> newSkills = new ArrayList<>();
        for (SkillListingEntry s : skills) {
            if (!sent.contains(s.name())) {
                newSkills.add(s);
            }
        }
        if (newSkills.isEmpty()) {
            return List.of();
        }
        boolean isInitial = sent.isEmpty();
        for (SkillListingEntry s : newSkills) {
            sent.add(s.name());
        }
        String content = SkillListingFormatter.formatWithinBudget(newSkills);
        List<String> names = newSkills.stream().map(SkillListingEntry::name).toList();
        return List.of(new SkillListingAttachment(content, newSkills.size(), isInitial, names));
    }

}
