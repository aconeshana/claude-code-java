package com.claudecode.commands.impl.info;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.CommandResult;

import java.util.List;

/**
 * Base class for stub commands that are not yet implemented.
 */
public class StubCommand implements Command {

    private final String cmdName;
    private final String cmdDescription;
    private final List<String> cmdAliases;

    public StubCommand(String name, String description) {
        this(name, description, List.of());
    }

    public StubCommand(String name, String description, List<String> aliases) {
        this.cmdName = name;
        this.cmdDescription = description;
        this.cmdAliases = aliases;
    }

    @Override public CommandMetadata metadata() {
        return new CommandMetadata(cmdName, cmdDescription, cmdAliases);
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        return CommandResult.of("/" + cmdName + ": Not yet implemented");
    }

    /**
     * Stub commands are excluded from /help and slash-command suggestions, but remain reachable by
     * typing the full name (dispatch still works).
     */
    @Override public boolean isHidden() { return true; }
}
