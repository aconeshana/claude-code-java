package com.claudecode.commands.impl.session;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.session.ResumeRequest;
import com.claudecode.commands.session.SessionCommandPort;
import com.claudecode.core.message.Message;
import com.claudecode.runtime.session.MessagesDeserializer;

import java.util.List;
import java.util.Optional;
import com.claudecode.core.util.UuidUtils;

@SlashCommand(
    name = "resume",
    description = "Resume a previous conversation",
    aliases = "continue"
)
public class ResumeCommand implements AnnotatedCommand {

    public ResumeCommand() { }

    @Override
    public String argumentHint() { return "[conversation id or search term]"; }

    /** Worktree enumeration and transcript metadata scans are blocking I/O. */
    @Override
    public boolean isLongRunning() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String trimmed = args == null ? "" : args.trim();

        if (!trimmed.isEmpty()) {
            return resumeById(context, trimmed);
        }






        return CommandResult.of(
            "The interactive session picker requires the REPL. Use /resume <session-id>.");
    }

    private CommandResult resumeById(CommandContext context, String trimmed) {
        SessionCommandPort sessionsPort = context.application().sessions();
        List<SessionCommandPort.LocatedSession> sessions = sessionsPort.listSessions();

        if (sessions.isEmpty()) {
            return CommandResult.of("No conversations found to resume.");
        }
        Optional<SessionCommandPort.LocatedSession> match = findExactId(sessionsPort, trimmed);
        ResumeRequest.Entrypoint entrypoint = ResumeRequest.Entrypoint.SLASH_COMMAND_SESSION_ID;


// — /resume <title-set-via-/rename>. Gated by isCustomTitleEnabled

        if (match.isEmpty() && isCustomTitleEnabled()) {
            List<SessionCommandPort.LocatedSession> titleMatches =
                sessionsPort.searchExactCustomTitle(trimmed);
            if (titleMatches.size() == 1) {
                match = Optional.of(titleMatches.getFirst());
                entrypoint = ResumeRequest.Entrypoint.SLASH_COMMAND_TITLE;
            } else if (titleMatches.size() > 1) {

                return CommandResult.of("Found " + titleMatches.size() + " sessions matching "
                    + trimmed + ". Please use /resume to pick a specific session.");
            }
        }

        if (match.isEmpty()) {

            return CommandResult.of("Session " + trimmed + " was not found.");
        }

        SessionCommandPort.LocatedSession selected = match.get();
        if (context.session().resumeLauncher() != null) {
            context.session().resumeLauncher().accept(new ResumeRequest(
                selected.id(),
                selected.sessionFile(), selected.cwd(),
                entrypoint));
            return CommandResult.skip();
        }

        if (context.session().loadMessages() == null) {
            return CommandResult.of(
                "Found session " + selected.id() + " but this REPL does not "
              + "support in-place resume. Restart with `--resume " + selected.id() + "`.");
        }

        try {
            List<Message> raw = sessionsPort.readMessages(selected.sessionFile());
            // Route through MessagesDeserializer so the same conversation-recovery
            // pipeline (incl. pre-compact_boundary pruning) applies as at every

            // the same recovery pass that relinks preserved segments.
            List<Message> messages = MessagesDeserializer.deserialize(raw);
            context.session().loadMessages().accept(messages);
            // Repoint the engine's write-target identity at the resumed
            // session too, or every message sent afterward silently keeps
            // landing in the OLD session's JSONL file — see
            // CommandSessionState#sessionIdSwitcher.
            if (context.session().sessionIdSwitcher() != null) {
                context.session().sessionIdSwitcher().accept(selected.id());
            }
            return CommandResult.of(String.format(
                "Resumed session %s — loaded %d message%s.",
                selected.id(),
                messages.size(),
                messages.size() == 1 ? "" : "s"));
        } catch (Exception e) {
            return CommandResult.of("Failed to resume " + selected.id() + ": " + e.getMessage());
        }
    }


    static boolean isCustomTitleEnabled() {
        return true;
    }


    private Optional<SessionCommandPort.LocatedSession> findExactId(
            SessionCommandPort sessions, String query) {
        if (!UuidUtils.isValid(query)) return Optional.empty();
        return sessions.findExactSessionId(query);
    }

}
