package com.claudecode.cli;

import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.tools.skills.Skill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;


class PluginCommandSkillListingTest {

    @Test
    void disabledPluginCommandIsExcludedFromModelSkillListing() throws Exception {
        Skill allowed = pluginCommand("demo:inspect", false);
        Skill userOnly = pluginCommand("demo:release", true);

        List<SkillListingEntry> entries = listing(List.of(allowed, userOnly));

        assertEquals(List.of("demo:inspect"), entries.stream()
            .map(SkillListingEntry::name).toList());
    }

    private static Skill pluginCommand(String name, boolean disabled) {
        return new Skill(name, name + " description", List.of(), "body", null,
            Skill.SkillSource.PLUGIN, null, null, null,
            Map.of("pluginCommand", true, "disableModelInvocation", disabled));
    }

    private static List<SkillListingEntry> listing(List<Skill> skills) {
        return CliPromptInventoryAssembler.skillListingEntries(skills);
    }
}
