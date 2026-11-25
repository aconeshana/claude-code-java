package com.claudecode.commands.impl.integration;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.runtime.capabilities.SessionCapabilityPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * /skills — list available skills.
 */
@SlashCommand(
    name = "skills",
    description = "List available skills"
)
public class SkillsCommand implements AnnotatedCommand {

    private static final String SKILLS_DIR = ".claude/skills";

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String trimmed = args != null ? args.trim() : "";
        if (!trimmed.isEmpty()) {
            String[] parts = trimmed.split("\\s+", 2);
            String action = parts[0].toLowerCase(Locale.ROOT);
            if (Strings.CS.equals("enable", action) || Strings.CS.equals("disable", action)) {
                return handleToggle(context, parts.length > 1 ? parts[1].trim() : "",
                    Strings.CS.equals("enable", action));
            }
        }
        if (context.presentation().skillsDialogLauncher() != null) {
            context.presentation().skillsDialogLauncher().run();
            return CommandResult.skip();
        }
        // Headless fallback: flat text listing.
        return CommandResult.of(renderTextListing(context));
    }

    private CommandResult handleToggle(CommandContext context, String rawRest, boolean enabling) {
        SessionCapabilityPort capabilities = context != null && context.application() != null
            ? context.application().capabilities() : null;
        if (capabilities == null) {
            return CommandResult.of("Skill capability switching is not available in this session.");
        }
        SessionCapabilityPort.Scope scope = SessionCapabilityPort.Scope.SESSION;
        String target = "";
        for (String tok : (rawRest.isEmpty() ? new String[0] : rawRest.split("\\s+"))) {
            if (Strings.CS.equals("--default", tok) || Strings.CS.equals("--persist", tok)) {
                scope = SessionCapabilityPort.Scope.DEFAULT;
            } else if (Strings.CS.equals("--session", tok)) {
                scope = SessionCapabilityPort.Scope.SESSION;
            } else if (target.isEmpty()) {
                target = tok;
            }
        }
        if (target.isEmpty()) {
            return CommandResult.of("Usage: /skills " + (enabling ? "enable" : "disable")
                + " <skill-name|all> [--default]");
        }
        String sessionId = context.session().currentSessionId() != null
            ? context.session().currentSessionId().get() : "";
        var snap = capabilities.snapshot(sessionId, context.session().model());
        if (Strings.CS.equals("all", target)) {
            int count = 0;
            for (var s : snap.skills()) {
                boolean current = scope == SessionCapabilityPort.Scope.DEFAULT
                    ? s.defaultEnabled() : s.sessionEnabled();
                if (current != enabling) {
                    capabilities.toggle(new SessionCapabilityPort.ToggleRequest(
                        sessionId, SessionCapabilityPort.Kind.SKILL, s.name(), null, enabling, scope));
                    count++;
                }
            }
            return CommandResult.of((enabling ? "Enabled " : "Disabled ")
                + count + " skill(s) (" + scope.wire() + ").");
        }
        final String skillName = target;
        boolean exists = snap.skills().stream().anyMatch(s -> s.name().equals(skillName));
        if (!exists) {
            return CommandResult.of("Skill \"" + skillName + "\" not found.");
        }
        var outcome = capabilities.toggle(new SessionCapabilityPort.ToggleRequest(
            sessionId, SessionCapabilityPort.Kind.SKILL, skillName, null, enabling, scope));
        return CommandResult.of(outcome.message());
    }

    private String renderTextListing(CommandContext context) {
        Path skillsDir = Path.of(StringUtils.defaultIfBlank(
            context.session().workingDirectory(), System.getProperty("user.dir", ".")),
            SKILLS_DIR);
        StringBuilder sb = new StringBuilder("Skills\n");

        List<String> names = listSkillNames(skillsDir);
        if (names.isEmpty()) {
            sb.append("No skills found\n");
            sb.append("Create skills in .claude/skills/ or ~/.claude/skills/");
            return sb.toString();
        }
        sb.append(names.size()).append(names.size() == 1 ? " skill" : " skills").append('\n');
        sb.append("(").append(skillsDir).append(")\n");
        for (String n : names) sb.append("  ").append(n).append('\n');
        return sb.toString().stripTrailing();
    }

    /**
     * Enumerate skill names in {@code skillsDir}. Supports both layouts the
     * loader accepts: a bare {@code <name>.md} file, or a {@code <name>/SKILL.md}
     * directory.
     */
    private List<String> listSkillNames(Path skillsDir) {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) return names;
        try (Stream<Path> entries = Files.list(skillsDir)) {
            entries.sorted().forEach(p -> {
                String fileName = p.getFileName().toString();
                if (Files.isRegularFile(p) && Strings.CS.endsWith(fileName, ".md")) {
                    names.add(fileName.substring(0, fileName.length() - 3));
                } else if (Files.isDirectory(p) && Files.isRegularFile(p.resolve("SKILL.md"))) {
                    names.add(fileName);
                }
            });
        } catch (IOException _) {
            // best-effort listing
        }
        return names;
    }
}
