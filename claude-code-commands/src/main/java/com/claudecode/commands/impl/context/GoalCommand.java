package com.claudecode.commands.impl.context;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.text.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Session-scoped completion goal backed by a Stop prompt hook.
 */
@SlashCommand(
    name = "goal",
    description = "Set a goal Claude checks before stopping"
)
public final class GoalCommand implements AnnotatedCommand {

    static final int MAX_CONDITION_LENGTH = 4000;
    private static final Set<String> CLEAR_ALIASES = Set.of(
        "clear", "stop", "off", "reset", "none", "cancel");
    public static final String TRUST_ERROR =
        "/goal is only available in trusted workspaces. Restart, accept the trust dialog, and try again.";
    public static final String HOOKS_ERROR =
        "/goal can't run while hooks are restricted (disableAllHooks or allowManagedHooksOnly is set in settings or by policy).";

    @Override public String argumentHint() { return "[<condition> | clear]"; }

    @Override public boolean isImmediate() { return true; }

    @Override public boolean supportsNonInteractive() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String value = args == null ? "" : args.trim();
        HookDispatcher hooks = context.session().hookDispatcher();

        if (value.isEmpty()) {
            if (!context.session().nonInteractive() && context.presentation().goalDialogLauncher() != null) {
                context.presentation().goalDialogLauncher().run();
                return CommandResult.skip();
            }
            if (hooks == null || hooks.activeGoal().isEmpty()) {
                return CommandResult.of("No goal set. Usage: `/goal <condition>`");
            }
            HookDispatcher.ActiveGoal active = hooks.activeGoal().orElseThrow();
            String turns = active.iterations() == 0
                ? "not yet evaluated"
                : active.iterations() + " " + StringUtils.plural(active.iterations(), "turn");
            String reason = active.lastReason() == null ? ""
                : "\nLast check: " + active.lastReason().trim() + "\n";
            return CommandResult.of("Goal active: " + active.condition()
                + " (" + turns + ")" + reason);
        }

        if (CLEAR_ALIASES.contains(value.toLowerCase(Locale.ROOT))) {
            String condition = hooks != null ? hooks.clearGoal() : null;
            if (condition == null) return CommandResult.of("No goal set");
            appendGoalStatus(context, GoalStatusAttachment.sentinel(true, condition));
            return CommandResult.of("Goal cleared: " + condition);
        }

        if (value.length() > MAX_CONDITION_LENGTH) {
            return CommandResult.of("Goal condition is limited to " + MAX_CONDITION_LENGTH
                + " characters (got " + value.length() + ")");
        }

        String gateError = resolveGateError(context);
        if (gateError != null) return CommandResult.of(gateError);
        if (hooks == null || !hooks.setGoal(value, tokenCount(context.session().usageSupplier().get()))) {
            return CommandResult.of(HOOKS_ERROR);
        }

        appendGoalStatus(context, GoalStatusAttachment.sentinel(false, value));
        String activation = activationPrompt(value);
        PromptInvocation prompt = PromptInvocation.builder(List.of(new TextBlock(activation)))
            .precedingUserMessages(List.of(
                MessageContent.ofText(commandInput(value)),
                MessageContent.ofText("<local-command-stdout>Goal set: " + value
                    + "</local-command-stdout>")))
            .scalarTextContent(true)
            .suppressInitialAttachments(true)
            .suppressCommandPermissions(true)
            .suppressSkillAttribution(true)
            .suppressLastPrompt(true)
            .contentLength(activation.length())
            .build();
        return new CommandResult("Goal set: " + value, "Goal set: " + value,
            false, true, false, null, null, prompt);
    }

    private static String commandInput(String condition) {
        return "<command-name>/goal</command-name>\n"
            + "            <command-message>goal</command-message>\n"
            + "            <command-args>" + condition + "</command-args>";
    }

    static String activationPrompt(String condition) {
        return "A session-scoped Stop hook is now active with condition: \"" + condition
            + "\". Briefly acknowledge the goal, then immediately start (or continue) "
            + "working toward it — treat the condition itself as your directive and do not "
            + "pause to ask the user what to do. The hook will block stopping until the "
            + "condition holds. It auto-clears once the condition is met — do not tell the "
            + "user to run `/goal clear` after success; that's only for clearing a goal early.";
    }

    private static String resolveGateError(CommandContext context) {
        if (context.session().goalGate() != null) return context.session().goalGate().get();
        return context.session().nonInteractive() ? null : TRUST_ERROR;
    }

    private static long tokenCount(Usage usage) {
        if (usage == null) return 0L;
        return usage.inputTokens() + usage.outputTokens()
            + usage.cacheCreationInputTokens() + usage.cacheReadInputTokens();
    }

    private static void appendGoalStatus(CommandContext context, GoalStatusAttachment payload) {
        Message message = new AttachmentMessage(UUID.randomUUID().toString(), payload);
        Consumer<Message> appender = context.session().messageAppender();
        if (appender != null) {
            appender.accept(message);
        } else if (context.session().transcriptRecorder() != null) {
            context.session().transcriptRecorder().accept(message);
        }
    }

}
