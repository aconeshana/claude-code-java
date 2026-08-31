package com.claudecode.commands.impl.info;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.text.UnicodeSanitizer;

import java.util.Set;

/**
 * /tag — toggle a searchable tag on the current session.
 */
@SlashCommand(
    name = "tag",
    description = "Toggle a searchable tag on the current session"
)
public class TagCommand implements AnnotatedCommand {

    private static final Set<String> HELP_ARGS = Set.of(
        "help", "-h", "--help", "list", "show", "display", "current", "view",
        "get", "check", "describe", "print", "version", "about", "status", "?");

    static final String HELP = """
        Usage: /tag <tag-name>

        Toggle a searchable tag on the current session.
        Run the same command again to remove the tag.
        Tags are displayed after the branch name in /resume and can be searched with /.

        Examples:
          /tag bugfix        # Add tag
          /tag bugfix        # Remove tag (toggle)
          /tag feature-auth
          /tag wip""";

    public TagCommand() { }

    @Override
    public String argumentHint() { return "<tag-name>"; }

    @Override
    public boolean isAvailable(CommandContext context) {
        return Strings.CS.equals("ant", System.getenv("USER_TYPE"));
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String trimmedArgs = args == null ? "" : args.trim();
        if (trimmedArgs.isEmpty() || HELP_ARGS.contains(trimmedArgs)) {
            return CommandResult.of(HELP);
        }

        String sessionId = context.session().currentSessionId() != null
            ? context.session().currentSessionId().get() : null;
        if (StringUtils.isBlank(sessionId)) {
            return CommandResult.of("No active session to tag");
        }

        String normalizedTag = normalizeTag(trimmedArgs);
        if (normalizedTag.isEmpty()) {
            return CommandResult.of("Tag name cannot be empty");
        }

        String currentTag = context.application().sessions().readTag(sessionId);
        if (normalizedTag.equals(currentTag)) {
            if (context.presentation().tagRemovalLauncher() == null) {
                return CommandResult.of(
                    "Tag removal confirmation is unavailable for #" + normalizedTag);
            }
            context.presentation().tagRemovalLauncher().accept(new CommandContext.TagRemovalRequest(
                normalizedTag,
                () -> removeTag(context, sessionId, normalizedTag),
                () -> CommandResult.of("Kept tag #" + normalizedTag)));
            return CommandResult.skip();
        }

        context.application().sessions().saveTag(sessionId, normalizedTag);
        return CommandResult.of("Tagged session with #" + normalizedTag);
    }

    static String normalizeTag(String value) {
        String sanitized = UnicodeSanitizer.sanitize(value == null ? "" : value);
        return sanitized.trim();
    }

    private CommandResult removeTag(CommandContext context, String sessionId, String tag) {
        context.application().sessions().saveTag(sessionId, "");
        return CommandResult.of("Removed tag #" + tag);
    }
}
