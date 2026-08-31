package com.claudecode.core.model;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.message.TokenEstimator;
import org.apache.commons.lang3.Strings;

import java.util.List;

/**
 * Model-specific visibility rules for bundled skills.
 * GPT models do not receive the bundled {@code claude-api} discovery entry,
 * while explicit slash-command invocation remains available outside the
 * model-facing Skill tool.
 */
@Explanation("Prevents GPT models from auto-loading the Claude API reference skill")
public final class ModelSkillVisibility {
    private static final String CLAUDE_API = "claude-api";

    private ModelSkillVisibility() {}

    public static boolean isVisible(String name, boolean bundled, String model) {
        return !bundled || !Strings.CS.equals(CLAUDE_API, name)
            || !TokenEstimator.isGptModel(model);
    }

    public static List<SkillListingEntry> filter(List<SkillListingEntry> skills, String model) {
        if (skills == null || skills.isEmpty() || !TokenEstimator.isGptModel(model)) {
            return skills == null ? List.of() : skills;
        }
        return skills.stream()
            .filter(skill -> isVisible(skill.name(), skill.bundled(), model))
            .toList();
    }
}
