package com.claudecode.cli;

import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.attachment.SkillListingFormatter;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.model.ModelSkillVisibility;
import com.claudecode.core.prompt.McpInstructionEntry;
import com.claudecode.core.prompt.SystemPromptSections;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.config.GlobalConfigStore;
import com.claudecode.services.config.SettingsSnapshots;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.agent.AgentDisplay;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Builds the cache-persisted agent, MCP-instruction, and skill prompt inventory.
 */
final class CliPromptInventoryAssembler {
    private CliPromptInventoryAssembler() {}

    static String buildAgentListingMessage(SkillLoader loader, String model) {
        return buildAgentListingMessage(loader, model, _ -> true);
    }

    static String buildAgentListingMessage(
            SkillLoader loader, String model,
            Predicate<BuiltInAgentDefinitions.AgentDefinition> available) {
        List<BuiltInAgentDefinitions.AgentDefinition> agents = new ArrayList<>(
            AgentDefinitionLoader.getActive(System.getProperty("user.dir")).stream()
                .filter(available != null ? available : _ -> true)
                .toList());
        if (agents.isEmpty()) return null;
        agents.sort(AgentDisplay::compareByName);
        StringBuilder out = new StringBuilder("Available agent types for the Agent tool:");
        for (var agent : agents) out.append('\n').append(agent.toPromptLine(true));
        out.append("\n\nWhen you launch multiple agents for independent work, send them ")
            .append("in a single message with multiple tool uses so they run concurrently.");
        try {
            List<Skill> skills = loader.loadAll();
            if (skills != null && !skills.isEmpty()) {
                out.append("\n\nThe following skills are available for use with the Skill tool:\n\n")
                    .append(SkillListingFormatter.formatWithinBudget(
                        skillListingEntries(skills, model), model));
            }
        } catch (Exception _) { }
        return out.toString();
    }

    static String insertMcpInstructions(String listing, List<McpInstructionEntry> instructions) {
        String section = SystemPromptSections.getMcpInstructionsSection(instructions);
        if (StringUtils.isBlank(section)) return listing;
        if (StringUtils.isBlank(listing)) return section;
        String header = "The following skills are available for use with the Skill tool:";
        int at = listing.indexOf(header);
        return at < 0 ? listing + "\n\n" + section
            : listing.substring(0, at) + section + "\n\n" + listing.substring(at);
    }

    static List<SkillListingEntry> skillListingEntries(List<Skill> skills) {
        return skillListingEntries(skills, null);
    }

    @Explanation("Hides the bundled claude-api discovery entry from GPT models")
    static List<SkillListingEntry> skillListingEntries(List<Skill> skills, String model) {
        Map<String, Double> usage = GlobalConfigStore.getSkillUsageScores();
        Set<String> shadowed = skills.stream().filter(s -> s.source() != Skill.SkillSource.BUNDLED)
            .map(Skill::name).collect(Collectors.toSet());
        return skills.stream().filter(s -> !s.disableModelInvocation())
            .filter(s -> s.source() != Skill.SkillSource.BUNDLED || !shadowed.contains(s.name()))
            .filter(s -> ModelSkillVisibility.isVisible(
                s.name(), s.source() == Skill.SkillSource.BUNDLED, model))
            .map(s -> new SkillListingEntry(s.name(), s.description(),
                s.source() == Skill.SkillSource.BUNDLED,
                StringUtils.isBlank(s.description()), usage.getOrDefault(s.name(), 0.0)))
            .toList();
    }

    static ObjectNode guideSettings(String cwd) {
        ObjectNode effective = SettingsSnapshots.effective(cwd);
        ObjectNode ordered = JsonUtils.getMapper().createObjectNode();
        JsonNode includeCoAuthor = effective.get("includeCoAuthoredBy");
        if (includeCoAuthor != null) ordered.set("includeCoAuthoredBy", includeCoAuthor.deepCopy());
        effective.fields().forEachRemaining(field -> {
            if (!Strings.CS.equals("includeCoAuthoredBy", field.getKey())
                    && !Strings.CS.equals("enabledMcpjsonServers", field.getKey())) {
                ordered.set(field.getKey(), field.getValue().deepCopy());
            }
        });

        JsonNode enabledMcp = projectGlobalConfig(cwd).get("enabledMcpjsonServers");
        if (enabledMcp == null) enabledMcp = effective.get("enabledMcpjsonServers");
        if (enabledMcp != null) ordered.set("enabledMcpjsonServers", enabledMcp.deepCopy());
        return ordered;
    }

    private static ObjectNode projectGlobalConfig(String cwd) {
        ObjectNode empty = JsonUtils.getMapper().createObjectNode();
        if (StringUtils.isBlank(cwd)) return empty;
        JsonNode projects = GlobalConfigStore.snapshot(ClaudePaths.GLOBAL_JSON).get("projects");
        if (projects == null || !projects.isObject()) return empty;
        JsonNode project = projects.get(Path.of(cwd).toAbsolutePath().normalize().toString());
        return project instanceof ObjectNode object ? object : empty;
    }
}
