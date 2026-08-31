package com.claudecode.commands.impl.integration;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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



        if (context.presentation().skillsDialogLauncher() != null) {
            context.presentation().skillsDialogLauncher().run();
            return CommandResult.skip();
        }
        // Headless fallback: flat text listing.
        return CommandResult.of(renderTextListing(context));
    }

    private String renderTextListing(CommandContext context) {
        Path skillsDir = Path.of(context.session().workingDirectory(), SKILLS_DIR);
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
