package com.claudecode.commands;

/**
 * Transcript channel used by a completed local slash command.
 */
public enum CommandOutputChannel {
    STDOUT,
    STDERR,
    NONE;


    public String wrap(String output) {
        String tag = switch (this) {
            case STDOUT -> "local-command-stdout";
            case STDERR -> "local-command-stderr";
            case NONE -> throw new IllegalStateException("NONE has no transcript wrapper");
        };
        return "<" + tag + ">" + (output == null ? "" : output) + "</" + tag + ">";
    }
}
