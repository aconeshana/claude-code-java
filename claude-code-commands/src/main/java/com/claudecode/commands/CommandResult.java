package com.claudecode.commands;

import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.commands.metadata.CommandMetadataEncoder;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;

import java.util.List;

/**
 * Result of executing a slash command.
 */
public record CommandResult(String output, String headlessOutput,
                            boolean shouldExit, boolean shouldQuery, boolean silent,
                            String newSessionName, String exitReason,
                            PromptInvocation promptInvocation,
                            CommandOutputChannel outputChannel,
                            CommandResultDisplay display,
                            boolean persist,
                            List<String> metaMessages) {

    public CommandResult {
        output = output == null ? "" : output;
        headlessOutput = headlessOutput == null ? "" : headlessOutput;
        outputChannel = outputChannel == null ? CommandOutputChannel.NONE : outputChannel;
        display = display == null ? CommandResultDisplay.SYSTEM : display;
        metaMessages = List.copyOf(metaMessages == null ? List.of() : metaMessages);

        // Keep the legacy silent bit and the typed display contract coherent.
        if (silent || display == CommandResultDisplay.SKIP) {
            silent = true;
            display = CommandResultDisplay.SKIP;
            persist = false;
            outputChannel = CommandOutputChannel.NONE;
        }
    }

    /** Backwards-compatible constructor for the pre-completion-contract shape. */
    public CommandResult(String output, String headlessOutput,
                         boolean shouldExit, boolean shouldQuery, boolean silent,
                         String newSessionName, String exitReason,
                         PromptInvocation promptInvocation) {
        this(output, headlessOutput, shouldExit, shouldQuery, silent,
            newSessionName, exitReason, promptInvocation,
            silent ? CommandOutputChannel.NONE
                : shouldQuery ? CommandOutputChannel.NONE : CommandOutputChannel.STDOUT,
            silent ? CommandResultDisplay.SKIP
                : shouldQuery ? CommandResultDisplay.USER : CommandResultDisplay.SYSTEM,
            !silent, List.of());
    }

    /** Backwards-compatible constructor: ordinary commands expose their display text headlessly. */
    public CommandResult(String output, boolean shouldExit, boolean shouldQuery, boolean silent,
                         String newSessionName, String exitReason,
                         PromptInvocation promptInvocation) {
        this(output, output, shouldExit, shouldQuery, silent,
            newSessionName, exitReason, promptInvocation);
    }

    /** Backwards-compatible constructor for local-command callers. */
    public CommandResult(String output, boolean shouldExit, boolean shouldQuery, boolean silent,
                         String newSessionName, String exitReason) {
        this(output, shouldExit, shouldQuery, silent, newSessionName, exitReason, null);
    }

    /** Create a result with display output that does not exit or query. */
    public static CommandResult of(String output) {
        return new CommandResult(output, output, false, false, false, null, null, null,
            CommandOutputChannel.STDOUT, CommandResultDisplay.SYSTEM, true, List.of());
    }


    public static CommandResult local(String output) {
        return new CommandResult(output, output, false, false, false, null, null, null,
            CommandOutputChannel.STDOUT, CommandResultDisplay.LOCAL, true, List.of());
    }


    public static CommandResult localJsx(String output) {
        return new CommandResult(output, output, false, false, false, null, null, null,
            CommandOutputChannel.STDOUT, CommandResultDisplay.USER, true, List.of());
    }


    public static CommandResult localError(String output) {
        return new CommandResult(output, "", false, false, false, null, null, null,
            CommandOutputChannel.STDERR, CommandResultDisplay.LOCAL, true, List.of());
    }


    public static CommandResult error(String output) {
        return new CommandResult(output, "", false, false, false, null, null, null,
            CommandOutputChannel.STDERR, CommandResultDisplay.USER, true, List.of());
    }

    /**
     * Create transcript/UI-only display output with an empty SDK/headless result.
     */
    public static CommandResult displayOnly(String output) {
        return new CommandResult(output, "", false, false, false, null, null, null,
            CommandOutputChannel.STDOUT, CommandResultDisplay.USER, true, List.of());
    }

    /**
     * Create a result that signals the REPL to exit with an unspecified reason.
     * Kept for callers pre-dating the {@code exitReason} field; new callers
     * should prefer {@link #exit(String, String)} so SessionEnd hooks receive
     * a meaningful {@code reason} tag.
     */
    public static CommandResult exit(String output) {
        return new CommandResult(output, output, true, false, false, null, "other", null,
            CommandOutputChannel.STDOUT, CommandResultDisplay.SYSTEM, true, List.of());
    }


    public static CommandResult exit(String output, String reason) {
        return new CommandResult(output, output, true, false, false, null, reason, null,
            CommandOutputChannel.STDOUT, CommandResultDisplay.SYSTEM, true, List.of());
    }

    /**
     * Create a result that injects {@code prompt} as a new query to the AI engine.
     */
    public static CommandResult forQuery(String prompt) {
        return forPrompt(PromptInvocation.text(prompt));
    }

    public static CommandResult forLocalJsxQuery(
            String commandName, String args, String output) {
        String stdout = "<local-command-stdout>" + output + "</local-command-stdout>";
        PromptInvocation invocation = PromptInvocation.builder(
                List.of(new TextBlock(stdout)))
            .precedingUserMessages(List.of(MessageContent.ofText(
                CommandMetadataEncoder.encodeCommandInputTags(
                    commandName, args == null ? "" : args))))
            .scalarTextContent(true)
            // The command turn is a bare [CMD]+[STDOUT] envelope carrying the
            // synthetic /plan prompt. It must not collect plan-mode reentry /
            // active reminders nor command-permission system reminders; those
            // (reentry + plan active) are deferred to the next real user turn,
            // matching the official bundle's plan re-entry scheduling.
            .suppressInitialAttachments(true)
            .suppressCommandPermissions(true)
            .contentLength(stdout.length())
            .build();
        return new CommandResult(output, output, false, true, false,
            null, null, invocation, CommandOutputChannel.NONE,
            CommandResultDisplay.USER, true, List.of());
    }

    /** Create a structured prompt-command result without flattening content blocks. */
    public static CommandResult forPrompt(PromptInvocation invocation) {
        if (invocation == null) throw new IllegalArgumentException("invocation must not be null");
        return new CommandResult(invocation.textContent(), invocation.textContent(), false, true, false,
            null, null, invocation, CommandOutputChannel.NONE, CommandResultDisplay.USER,
            true, List.of());
    }

    /**
     * Create a silent result — the command has handed off rendering to its own UI (typically a floating
     * dialog or modal).
     */
    public static CommandResult skip() {
        return new CommandResult("", "", false, false, true, null, null, null,
            CommandOutputChannel.NONE, CommandResultDisplay.SKIP, false, List.of());
    }

    /**
     * Create a rename result: display {@code confirmMessage} as a system message AND signal the REPL to
     * update the prompt-bar banner to {@code name}.
     */
    public static CommandResult rename(String name, String confirmMessage) {
        return new CommandResult(confirmMessage, confirmMessage,
            false, false, false, name, null, null,
            CommandOutputChannel.STDOUT, CommandResultDisplay.SYSTEM, true, List.of());
    }


    public CommandResult withMetaMessages(List<String> messages) {
        return new CommandResult(output, headlessOutput, shouldExit, shouldQuery, silent,
            newSessionName, exitReason, promptInvocation, outputChannel, display,
            persist, messages);
    }
}
