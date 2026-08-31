package com.claudecode.commands.impl.git;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.session.SessionCommandPort;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conversation branching.
 */
@SlashCommand(
    name = "branch",
    description = "Create a branch of the current conversation at this point",
    aliases = "fork"
)
public class BranchCommand implements AnnotatedCommand {

    public BranchCommand() { }

    @Override
    public String argumentHint() { return "[name]"; }

    /**
     * Reads the whole current transcript ({@code readMessages}) and copies it
     * into the fork's JSONL — on a long conversation that is enough file I/O
     * to visibly freeze the Lanterna GUI thread, so the dispatcher runs this
     * on a virtual thread instead (same rationale as {@code /compact}).
     */
    @Override
    public boolean isLongRunning() {
        return true;
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        return executeWithAdditionalMessages(context, args, List.of());
    }


    public CommandResult executeWithAdditionalMessages(CommandContext context, String args,
                                                       List<Message> additionalMessages) {
        String currentSessionId = context.session().currentSessionId() != null
            ? context.session().currentSessionId().get() : null;
        if (StringUtils.isBlank(currentSessionId)) {
            return CommandResult.of(
                "No active session to branch. /branch must be run from inside a live conversation.");
        }

        SessionCommandPort sessions = context.application().sessions();
        Path currentTranscript = sessions.transcriptPath(currentSessionId);
        if (!sessions.hasTranscript(currentSessionId)) {
            return CommandResult.of(
                "No conversation to branch — transcript not found at " + currentTranscript);
        }

        // Allocate the fork's session ID + directory, then copy the transcript.
        String forkSessionId;
        try {
            forkSessionId = sessions.createSession();
        } catch (Exception e) {
            return CommandResult.of("Failed to allocate branch session: " + e.getMessage());
        }
        SessionCommandPort.ForkResult forkResult;
        try {
            forkResult = sessions.fork(currentSessionId, forkSessionId);
        } catch (RuntimeException e) {
            return CommandResult.of("Failed to copy transcript into branch " + forkSessionId
                + ": " + e.getMessage());
        }
        if (forkResult.status() == SessionCommandPort.ForkResult.Status.NO_MESSAGES) {
            return CommandResult.of("No messages to branch yet.");
        }
        if (forkResult.status() == SessionCommandPort.ForkResult.Status.NO_CONVERSATION) {
            return CommandResult.of("Failed to copy transcript into branch " + forkSessionId
                + ": no conversation to fork");
        }
        // A fork gets an independent plan copy. Plain /resume keeps the same
        // session id and therefore reuses its own <sessionId>.md file; copying
        // the outgoing session's plan during resume would overwrite the target.
        context.application().tooling().plans().copy(currentSessionId, forkSessionId);
        List<Message> messages = new ArrayList<>(forkResult.messages());
        if (additionalMessages != null && !additionalMessages.isEmpty()) {
            try {
                sessions.appendMessages(forkSessionId, additionalMessages);
                messages.addAll(additionalMessages);
            } catch (RuntimeException e) {
                return CommandResult.of("Failed to append /btw exchange to branch "
                    + forkSessionId + ": " + e.getMessage());
            }
        }

        String label = args == null ? "" : args.trim();
        String effectiveForkTitle = null;
        try {
            String baseName = label.isEmpty() ? deriveFirstPrompt(messages) : label;
            effectiveForkTitle = getUniqueForkName(sessions, baseName);
            sessions.saveCustomTitle(forkSessionId, effectiveForkTitle);
        } catch (Exception _) { /* best-effort — naming must not block the branch */ }



        // fire SessionEnd on the outgoing session, switch identity, reset the
        // displayed cost (a fresh fork has no stored cost record even though
        // its transcript inherits full history), then fire SessionStart on
        // the fork and surface its additionalContext as a synthetic
        // system-reminder message.
        List<Message> messagesToLoad = messages;
        if (context.session().loadMessages() != null) {
            HookDispatcher hooks = context.session().hookDispatcher();
            if (hooks != null) {
                try { hooks.dispatchSessionEnd("resume"); } catch (Exception _) {}
            }
            if (context.session().sessionIdSwitcher() != null) {
                context.session().sessionIdSwitcher().accept(forkSessionId);
            }
            if (context.session().resetSessionCost() != null) {
                try { context.session().resetSessionCost().run(); } catch (Exception _) {}
            }
            if (hooks != null) {
                try {
                    HookDispatcher.HookOutcome outcome = hooks.dispatchSessionStartWithOutcome("resume");
                    if (outcome.hasAdditionalContext()) {
                        messagesToLoad = new ArrayList<>(messages);
                        messagesToLoad.add(systemReminderMessage(outcome.additionalContext(), forkSessionId));
                    }
                } catch (Exception _) {}
            }
            context.session().loadMessages().accept(messagesToLoad);
        }

        String titleSuffix = label.isEmpty() ? "" : " \"" + label + "\"";
        StringBuilder sb = new StringBuilder();
        sb.append("Branched conversation").append(titleSuffix).append(".\n");
        sb.append("New session: ").append(forkSessionId).append("\n");
        int copiedMessages = forkResult.messageCount()
            + (additionalMessages == null ? 0 : additionalMessages.size());
        sb.append("Copied ").append(copiedMessages).append(" message")
          .append(copiedMessages == 1 ? "" : "s").append(" from ").append(currentSessionId);
        if (context.session().loadMessages() == null) {
            sb.append("\n\nResume with: /resume ").append(forkSessionId);
        } else {
            sb.append("\nYou are now in the branch.");
            sb.append("\nTo return to the original: /resume ").append(currentSessionId);
        }
        // When a fork title was computed, surface it via CommandResult.rename so
        // the dispatcher refreshes InputPanel's prompt-bar banner via
        // setAgentName — otherwise the badge stays blank until a later
// /resume redoes restoreSessionColor. matches /rename's contract; the
        // title is already persisted to JSONL via saveCustomTitle above, so
        // /resume continues to read it back identically.
        if (StringUtils.isNotBlank(effectiveForkTitle)) {
            return CommandResult.rename(effectiveForkTitle, sb.toString());
        }
        return CommandResult.of(sb.toString());
    }

    /**
     * Builds the same {@code <system-reminder>} user message shape as
     * {@code QueryEngine#injectSystemReminder}, for the SessionStart hook's
     * {@code additionalContext} — {@code BranchCommand} has no direct engine
     * reference, only {@code CommandSessionState#loadMessages}, so the message is
     * appended to the list handed to that callback instead of injected
     * on-engine.
     */
    private static Message systemReminderMessage(String context, String sessionId) {
        return new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofText(MessageConstants.wrapInSystemReminder(context)),
            /* isMeta */ true,
            /* isCompactSummary */ false,
            /* toolUseResult */ null,
            MessageOrigin.USER,
            /* parentUuid */ null,
            Instant.now(),
            /* imagePasteIds */ null,
            /* permissionMode */ null,
            sessionId,
            /* sourceToolAssistantUUID */ null
        );
    }


    static String deriveFirstPrompt(List<Message> messages) {
        UserMessage firstUser = null;
        for (Message m : messages) {
            if (m instanceof UserMessage um) {
                firstUser = um;
                break;
            }
        }
        if (firstUser == null || firstUser.message() == null) return "Branched conversation";
        MessageContent content = firstUser.message();
        String raw = content.isText() ? content.text() : firstTextBlock(content.blocks());
        if (raw == null) return "Branched conversation";
        String collapsed = raw.replaceAll("\\s+", " ").trim();
        if (collapsed.length() > 100) collapsed = collapsed.substring(0, 100);
        return collapsed.isEmpty() ? "Branched conversation" : collapsed;
    }

    private static String firstTextBlock(List<ContentBlock> blocks) {
        if (blocks == null) return null;
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) return tb.text();
        }
        return null;
    }


    private String getUniqueForkName(SessionCommandPort sessions, String baseName) {
        String candidateName = baseName + " (Branch)";
        List<String> existingTitles = sessions.listSessions().stream()
            .map(SessionCommandPort.LocatedSession::customTitle)
            .filter(Objects::nonNull)
            .toList();


        // {exact:true})) normalizes both sides to lowercase+trim.
        String candidateNormalized = candidateName.toLowerCase(Locale.ROOT).trim();
        boolean collides = existingTitles.stream()
            .anyMatch(t -> candidateNormalized.equals(t.toLowerCase(Locale.ROOT).trim()));
        if (!collides) {
            return candidateName;
        }

        // The numbered-suffix regex extraction that follows is case-SENSITIVE

        Set<Integer> usedNumbers = new HashSet<>();
        usedNumbers.add(1); // " (Branch)" without a number is treated as 1
        Pattern forkNumberPattern = Pattern.compile(
            "^" + Pattern.quote(baseName) + " \\(Branch(?: (\\d+))?\\)$");
        for (String title : existingTitles) {
            Matcher match = forkNumberPattern.matcher(title);
            if (match.matches()) {
                usedNumbers.add(match.group(1) != null ? Integer.parseInt(match.group(1)) : 1);
            }
        }

        int nextNumber = 2;
        while (usedNumbers.contains(nextNumber)) nextNumber++;
        return baseName + " (Branch " + nextNumber + ")";
    }

    /**
     * Appends {@code {type:"custom-title", customTitle, sessionId}} to the
     * fork's session JSONL — same shape/entry type as {@code /rename}
     * ({@link RenameCommand}) so {@code /resume}/{@code /status} read it
     * back identically regardless of which command wrote it.
     */
}
