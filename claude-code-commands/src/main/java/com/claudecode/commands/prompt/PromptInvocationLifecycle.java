package com.claudecode.commands.prompt;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.commands.tooling.ToolingCommandPorts;

/**
 * Installs and clears the session state associated with one prompt-command invocation.
 */
public final class PromptInvocationLifecycle {

    private PromptInvocationLifecycle() { }

    public static HookDispatcher.HookOutcome install(PromptInvocation invocation,
                                                     String originalPrompt,
                                                     String commandName,
                                                     HookDispatcher hookDispatcher,
                                                     ToolingCommandPorts.SkillAttribution invokedSkills) {
        if (invocation == null) return HookDispatcher.HookOutcome.PROCEED;

        HookDispatcher.InvocationHooks hooks = invocation.hooks();
        if (hooks != null && !hooks.isEmpty() && hookDispatcher != null) {
            hookDispatcher.installInvocationHooks(hooks, invocation.skillRoot());
        }

        if (!invocation.suppressSkillAttribution()
                && invokedSkills != null && commandName != null && !StringUtils.isBlank(commandName)) {
            String source = firstNonBlank(invocation.source(), invocation.loadedFrom());
            String logicalPath = source == null ? commandName : source + ":" + commandName;
            invokedSkills.record(commandName, logicalPath, invocation.textContent());
        }

        if (hookDispatcher == null) return HookDispatcher.HookOutcome.PROCEED;
        return hookDispatcher.dispatchUserPromptExpansionWithOutcome(
            invocation.isMcp() ? "mcp_prompt" : "slash_command",
            commandName,
            commandArgsFromInput(originalPrompt),
            firstNonBlank(invocation.source(), invocation.loadedFrom()),
            originalPrompt);
    }

    /** Compatibility overload for callers predating UserPromptExpansion. */
    public static void install(PromptInvocation invocation,
                               String commandName,
                               HookDispatcher hookDispatcher,
                               ToolingCommandPorts.SkillAttribution invokedSkills) {
        install(invocation, commandName, commandName, hookDispatcher, invokedSkills);
    }

    public static void clear(HookDispatcher hookDispatcher) {
        if (hookDispatcher != null) hookDispatcher.clearInvocationHooks();
    }

    /** Extract the qualified command name from {@code /name [args]}. */
    public static String commandNameFromInput(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (Strings.CS.startsWith(value, "/")) value = value.substring(1);
        int separator = value.indexOf(' ');
        if (separator >= 0) value = value.substring(0, separator);
        return StringUtils.isBlank(value) ? null : value;
    }

    static String commandArgsFromInput(String input) {
        if (input == null) return "";
        String value = input.trim();
        int separator = value.indexOf(' ');
        return separator < 0 ? "" : value.substring(separator + 1).trim();
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.isNotBlank(first)) return first;
        if (StringUtils.isNotBlank(second)) return second;
        return null;
    }
}
