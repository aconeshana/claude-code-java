package com.claudecode.commands.parsing;

import org.apache.commons.lang3.StringUtils;
/**
 * Parsed representation of a slash command entered by the user.
 */
public record ParsedSlashCommand(String commandName, String args, boolean isMcp) {

    /**
     * Returns {@code true} when the command name is non-null and non-blank, i.e. this record
     * represents a syntactically valid command.
     */
    public boolean isValid() {
        return StringUtils.isNotBlank(commandName);
    }
}
