package com.claudecode.cli;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.metadata.CommandMetadataEncoder;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.prompt.ArgumentSubstitutor;
import com.claudecode.tools.skills.BundledSkillPromptRenderer;
import com.claudecode.tools.skills.Skill;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * CommandRegistry projection of one user-invocable skill.
 */
final class CliSkillPromptCommand implements Command {

    private final Skill skill;
    private final Path cwd;

    CliSkillPromptCommand(Skill skill, Path cwd) {
        this.skill = skill;
        this.cwd = cwd;
    }

    @Override public CommandMetadata metadata() {
        return new CommandMetadata(skill.name(), skill.commandDescription(), skill.aliases());
    }

    @Override public String argumentHint() { return skill.argumentHint(); }

    @Override public String menuDescription() { return skill.menuDescription(); }

    @Override public List<String> argumentNames() { return skill.argumentNames(); }

    @Override public boolean supportsNonInteractive() { return true; }

    @Override public boolean isHidden() { return !skill.userInvocable(); }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String rawArgs = args == null ? "" : args;
        String prompt = render(rawArgs);
        String source = skill.source().name().toLowerCase(Locale.ROOT);
        PromptInvocation invocation = PromptInvocation.builder(List.of(new TextBlock(prompt)))
            .allowedTools(skill.allowedTools())
            .model(skill.model())
            .effort(skill.effort())
            .disableModelInvocation(skill.disableModelInvocation())
            .source(source)
            .loadedFrom(source)
            .userFacingName(skill.name())
            .hasUserSpecifiedDescription(true)
            .contentLength(prompt.length())
            .context(skill.context())
            .skillRoot(skill.sourceFile() == null ? null : skill.sourceFile().getParent())
            .precedingUserMessages(List.of(MessageContent.ofText(
                CommandMetadataEncoder.encodeSlashCommandLoading(
                    skill.name(), rawArgs.isEmpty() ? null : rawArgs))))
            .build();
        return CommandResult.forPrompt(invocation);
    }

    private String render(String args) {
        if (skill.source() == Skill.SkillSource.BUNDLED
                && BundledSkillPromptRenderer.handles(skill.name())) {
            return BundledSkillPromptRenderer.render(
                skill.name(), skill.content(), args.isEmpty() ? null : args, cwd);
        }
        return ArgumentSubstitutor.substitute(
            skill.content(), args.isEmpty() ? null : args,
            skill.argumentNames(), true);
    }
}
