package com.claudecode.commands;

import com.claudecode.commands.metadata.CommandMetadata;

import java.util.List;

/**
 * Slash command interface.
 */
public interface Command {

    /** Immutable command identity metadata. */
    CommandMetadata metadata();

    /** Command name without the leading slash, e.g. "help", "exit". */
    default String name() {
        return metadata().name();
    }

    /** Human-readable description shown in /help output. */
    default String description() {
        return metadata().description();
    }

    /** Menu-specific description used by slash-command typeahead. */
    default String menuDescription() {
        return description();
    }

    /** Alternative names for this command (e.g. "quit" for "exit"). */
    default List<String> aliases() {
        return metadata().aliases();
    }

    /** Execute the command with the given context and argument string. */
    CommandResult execute(CommandContext context, String args);

    /** Whether this command is available in the current environment. */
    default boolean isAvailable(CommandContext context) {
        return true;
    }

    /**
     * Optional argument hint shown as ghost text after Tab-completing this command.
     */
    default String argumentHint() {
        return null;
    }

    /**
     * Ordered argument names used for progressive prompt-command hints and positional named-argument
     * substitution.
     */
    default List<String> argumentNames() {
        return List.of();
    }

    /**
     * Whether this command can execute immediately mid-query.
     */
    default boolean isImmediate() {
        return false;
    }

    /**
     * Whether this command is present in the non-interactive slash-command catalogue used by {@code
     * --print}/{@code --no-interactive}.
     */
    default boolean supportsNonInteractive() {
        return false;
    }

    /**
     * Whether this command's {@link #execute} may block for a long time on slow I/O (e.g.
     */
    default boolean isLongRunning() {
        return false;
    }

    /**
     * Whether this command should be excluded from user-visible listings (/help output and
     * slash-command suggestions / tab-complete).
     */
    default boolean isHidden() {
        return false;
    }
}
