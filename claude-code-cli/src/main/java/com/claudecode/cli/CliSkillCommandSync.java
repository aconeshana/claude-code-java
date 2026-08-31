package com.claudecode.cli;

import com.claudecode.commands.CommandRegistry;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Projects user-invocable bundled skills into the slash/headless command registry.
 */
final class CliSkillCommandSync {
    private final Set<String> registered = new HashSet<>();

    static int sync(CommandRegistry registry, SkillLoader loader, Path cwd) {
        return new CliSkillCommandSync().sync(registry, loader.loadAll(), cwd);
    }

    synchronized int sync(CommandRegistry registry, List<Skill> skills, Path cwd) {
        List<Skill> visible = (skills == null ? List.<Skill>of() : skills).stream()
            .filter(Skill::commandProjection)
            .filter(Skill::userInvocable)
            .toList();
        List<CliSkillPromptCommand> commands = visible.stream()
            .map(skill -> new CliSkillPromptCommand(skill, cwd))
            .toList();
        Set<String> previous = Set.copyOf(registered);
        registry.replaceMatching(previous::contains, commands);
        registered.clear();
        visible.forEach(skill -> registered.add(skill.name().toLowerCase(Locale.ROOT)));
        return visible.size();
    }
}
