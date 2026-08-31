package com.claudecode.commands.impl.session;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import org.apache.commons.lang3.StringUtils;
import java.util.List;

/**
 * /rename — rename the current conversation.
 */
@SlashCommand(
    name = "rename",
    description = "Rename the current conversation"
)
public class RenameCommand implements AnnotatedCommand {

    public RenameCommand() { }

    @Override public boolean isImmediate() { return true; }

    @Override public String argumentHint() { return "[name]"; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.application().tooling().collaboration().isTeammateSession()) {
            return CommandResult.of(
                "Cannot rename: This session is a swarm teammate. "
                    + "Teammate names are set by the team leader.");
        }

        String sessionId = context.session().currentSessionId() != null
            ? context.session().currentSessionId().get() : null;
        if (StringUtils.isBlank(sessionId)) {
            return CommandResult.of(
                "Cannot rename: no active session. Start a conversation first.");
        }

        String newName = args == null ? "" : args.trim();
        if (newName.isEmpty()) {

            String generated = tryGenerateName(context);
            if (StringUtils.isBlank(generated)) {
                return CommandResult.of(
                    "Could not generate a name: no conversation context yet. "
                  + "Usage: /rename <name>");
            }
            newName = generated;
        }

        try {
            context.application().sessions().saveCustomTitle(sessionId, newName);
            context.application().sessions().saveAgentName(sessionId, newName);
            // TODO(analytics): logEvent('tengu_session_renamed', {source:'user'})
            //                  logEvent('tengu_agent_name_set',  {source:'user'})
            return CommandResult.rename(newName, "Session renamed to: " + newName);
        } catch (RuntimeException e) {
            return CommandResult.of("Failed to rename session: " + e.getMessage());
        }
    }


    private static String tryGenerateName(CommandContext context) {
        if (context.session().titleGenerator() == null) return null;
        List<Message> messages = context.session().messagesSupplier() != null
            ? context.session().messagesSupplier().get() : List.of();
        if (messages == null || messages.isEmpty()) return null;
        List<Message> after = MessageConstants.getMessagesAfterCompactBoundary(messages);
        try {
            return context.session().titleGenerator().apply(after);
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Appends {@code {type:"custom-title", customTitle, sessionId}} to the session JSONL.
     */
    /**
     * Appends {@code {type:"agent-name", agentName, sessionId}} to the session JSONL.
     */
}
