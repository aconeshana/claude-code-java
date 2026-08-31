package com.claudecode.commands.impl.config;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;












@SlashCommand(
    name = "output-style",
    description = "Deprecated: use /config to change output style"
)
public class OutputStyleCommand implements AnnotatedCommand {

    @Override
    public boolean isHidden() {
        return true;
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        return CommandResult.of("/output-style has been deprecated. Use /config to change your "
            + "output style, or set it in your settings file. Changes take effect on the next session.");
    }
}
