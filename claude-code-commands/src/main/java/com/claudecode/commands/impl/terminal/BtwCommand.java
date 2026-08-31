package com.claudecode.commands.impl.terminal;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SlashCommand(
    name = "btw",
    description = "Ask a quick side question without interrupting the main conversation"
)
public class BtwCommand implements AnnotatedCommand {

    private static final Logger log = LoggerFactory.getLogger(BtwCommand.class);

    public BtwCommand() {}

    static final String SIDE_QUESTION_WRAPPER =
        """
        <system-reminder>This is a side question from the user. You must answer this question directly in a single response.

        IMPORTANT CONTEXT:
        - You are a separate, lightweight agent spawned to answer this one question
        - The main agent is NOT interrupted - it continues working independently in the background
        - You share the conversation context but are a completely separate instance
        - Do NOT reference being interrupted or what you were "previously doing" - that framing is incorrect

        CRITICAL CONSTRAINTS:
        - You have NO tools available - you cannot read files, run commands, search, or take any actions
        - This is a one-off response - there will be no follow-up turns
        - You can ONLY provide information based on what you already know from the conversation context
        - NEVER say things like "Let me try...", "I'll now...", "Let me check...", or promise to take any action
        - If you don't know the answer, say so - do not offer to look it up or investigate

        Simply answer the question with the information you have.</system-reminder>

        """;

    /** Shared SDK/UI entry point for the exact one-turn side-question wrapper. */
    public static String wrapQuestion(String question) {
        return SIDE_QUESTION_WRAPPER + (question == null ? "" : question);
    }

    @Override
    public boolean isImmediate() { return true; }

    @Override
    public String argumentHint() { return "<question>"; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String question = args == null ? "" : args.trim();
        if (question.isEmpty()) {
            return CommandResult.of("Usage: /btw <your question>");
        }


        incrementBtwUseCount(context);

        // Preferred path: hand off to the UI dialog launcher. The dialog owns
        // spinner / scroll / cancel + runs sideQuestionRunner on a background
        // thread, and critically does NOT add anything to the main transcript —
        // that's the entire point of a "side question". Return silent so the REPL
        // skips its echo + result rendering.
        if (context.presentation().btwDialogLauncher() != null) {
            context.presentation().btwDialogLauncher().accept(question);
            return CommandResult.skip();
        }

        // Fallback (non-interactive / tests / no GUI wired): run inline and
        // surface the answer as a transcript line. This violates the side-question
        // isolation invariant — only acceptable when there is no UI to host the
        // dialog.
        if (context.session().sideQuestionRunner() == null) {
            return CommandResult.of("[/btw] Side questions are not available in this context.");
        }
        try {
            String wrappedQuestion = wrapQuestion(question);
            String response = context.session().sideQuestionRunner().apply(wrappedQuestion);
            if (StringUtils.isBlank(response)) {
                return CommandResult.of("[/btw] No response received.");
            }
            return CommandResult.of("/btw " + question + "\n\n" + response);
        } catch (Exception e) {
            return CommandResult.of("[/btw] Error: " + e.getMessage());
        }
    }

    /**
     * Read-modify-write {@code btwUseCount} in  (the
     * {@code GlobalConfig} file through the settings port. Best-effort:
     * missing config is treated as count=0; write failures are logged at debug
     * level only — bookkeeping must never break the side-question feature itself.
     */
    private void incrementBtwUseCount(CommandContext context) {
        try {
            context.application().settings().preferences().incrementBtwUseCount();
        } catch (RuntimeException e) {
            log.debug("[btw] failed to update btwUseCount: {}", e.getMessage());
        }
    }
}
