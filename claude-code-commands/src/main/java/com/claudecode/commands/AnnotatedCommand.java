package com.claudecode.commands;

import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.metadata.CommandMetadataResolver;
import com.claudecode.commands.metadata.SlashCommand;

/**
 * Command whose immutable identity metadata is declared with {@link SlashCommand}.
 */
public interface AnnotatedCommand extends Command {

    @Override
    default CommandMetadata metadata() {
        return CommandMetadataResolver.resolve(getClass());
    }
}
