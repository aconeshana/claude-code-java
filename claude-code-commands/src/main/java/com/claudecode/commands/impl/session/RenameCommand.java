package com.claudecode.commands.impl.session;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import org.apache.commons.lang3.StringUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * /rename — rename the current conversation.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code src/commands/rename/rename.ts} {@code performRename} / {@code generateSessionName}
 *       — a bare {@code /rename} generates the name under the command's abort signal; an aborted
 *       generation yields no name, so the command reports {@code Could not generate a name} and
 *       does not rename (authoritative logic from the 2.1.236 bundle, {@code R2i} / {@code w7r})
 *       </li>
 * </ul>
 */
@SlashCommand(
    name = "rename",
    description = "Rename the current conversation"
)
public class RenameCommand implements AnnotatedCommand {

    /** Thread generating a name for the execution in flight, or {@code null} when idle. */
    private final AtomicReference<Thread> generating = new AtomicReference<>();

    public RenameCommand() { }

    @Override public boolean isImmediate() { return true; }

    /**
     * A bare {@code /rename} generates the name with a model request ({@code titleGenerator}),
     * so the dispatcher shows the command as working instead of letting the prompt look hung.
     */
    @Override public boolean isLongRunning() { return true; }

    /** 236 declares {@code /rename} {@code type:"local-jsx"}. */
    @Override public boolean isLocalJsx() { return true; }

    @Override public String argumentHint() { return "[name]"; }

    /** Aborts an in-flight name generation, the way 236 aborts {@code generateSessionName}. */
    @Override
    public boolean cancelInFlight() {
        Thread thread = generating.getAndSet(null);
        if (thread == null) return false;
        thread.interrupt();
        return true;
    }

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


    private String tryGenerateName(CommandContext context) {
        if (context.session().titleGenerator() == null) return null;
        List<Message> messages = context.session().messagesSupplier() != null
            ? context.session().messagesSupplier().get() : List.of();
        if (messages == null || messages.isEmpty()) return null;
        List<Message> after = MessageConstants.getMessagesAfterCompactBoundary(messages);
        Thread current = Thread.currentThread();
        generating.set(current);
        try {
            String name = context.session().titleGenerator().apply(after);
            // A cancelled generation yields no name even when the request completed anyway.
            return generating.compareAndSet(current, null) ? name : null;
        } catch (Exception _) {
            return null;
        } finally {
            generating.compareAndSet(current, null);
            // Consume a cancel interrupt so it cannot leak into the dispatcher's thread.
            Thread.interrupted();
        }
    }
}
