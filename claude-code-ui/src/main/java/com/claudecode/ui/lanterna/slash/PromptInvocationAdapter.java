package com.claudecode.ui.lanterna.slash;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.commands.prompt.PromptInvocationLifecycle;
import com.claudecode.commands.tooling.ToolingCommandPorts;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.runtime.turn.UserInput;

import java.util.Map;
import java.util.ArrayList;

/**
 * Adapts a command-layer {@link PromptInvocation} to the front-end-neutral turn contract without
 * projecting structured content to a string.
 */
public final class PromptInvocationAdapter {

    private PromptInvocationAdapter() { }

    public static UserInput toUserInput(String displayText, PromptInvocation invocation,
                                        Map<Integer, PastedContent> pasted,
                                        String permissionMode) {
        if (invocation == null) {
            throw new IllegalArgumentException("invocation must not be null");
        }
        return UserInput.forPrompt(
            displayText,
            invocation.scalarTextContent()
                && invocation.content().size() == 1
                && invocation.content().getFirst() instanceof TextBlock text
                    ? text.text()
                    : MessageContent.ofBlocks(invocation.content()),
            pasted,
            permissionMode,
            invocation.progressMessage(),
            invocation.allowedTools(),
            invocation.model(),
            invocation.effort(),
            invocation.precedingUserMessages(),
            invocation.suppressInitialAttachments(),
            invocation.suppressCommandPermissions());
    }

    public static HookDispatcher.HookOutcome installTurnScopedState(
            PromptInvocation invocation, String originalPrompt, String commandName,
            HookDispatcher hookDispatcher,
            ToolingCommandPorts.SkillAttribution invokedSkills) {
        return PromptInvocationLifecycle.install(
            invocation, originalPrompt, commandName, hookDispatcher, invokedSkills);
    }

    /** Compatibility overload retained for adapters/tests that only have the command name. */
    public static void installTurnScopedState(
            PromptInvocation invocation, String commandName,
            HookDispatcher hookDispatcher,
            ToolingCommandPorts.SkillAttribution invokedSkills) {
        PromptInvocationLifecycle.install(
            invocation, commandName, commandName, hookDispatcher, invokedSkills);
    }

    /** Adds successful expansion-hook stdout to the hidden slash-command preamble. */
    public static UserInput applyExpansionOutcome(
            UserInput input, HookDispatcher.HookOutcome outcome) {
        if (outcome == null || !outcome.hasAdditionalContext()) return input;
        ArrayList<MessageContent> preceding = new ArrayList<>(input.precedingUserMessages());
        outcome.additionalContexts().stream()
            .filter(StringUtils::isNotBlank)
            .map(MessageConstants::wrapInSystemReminder)
            .map(MessageContent::ofText)
            .forEach(preceding::add);
        return input.toBuilder().precedingUserMessages(preceding).build();
    }

    public static String commandNameFromDisplay(String displayText) {
        return PromptInvocationLifecycle.commandNameFromInput(displayText);
    }

}
