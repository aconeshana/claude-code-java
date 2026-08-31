package com.claudecode.sdk;

import java.util.Objects;

/** Available skill or slash command. */
public record SlashCommand(String name, String description, String argumentHint) {
    public SlashCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(argumentHint, "argumentHint");
    }
}
