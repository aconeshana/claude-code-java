package com.claudecode.commands.impl.session;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;

import java.util.concurrent.ThreadLocalRandom;

/**
 * /exit — exits the REPL.
 *
 * <ul>
 *   <li>{@code GOODBYE_MESSAGES}, {@code getRandomGoodbyeMessage},
 *       {@code aliases: ['quit']}, {@code immediate: true}</li>
 * </ul>
 */
@SlashCommand(
    name = "exit",
    description = "Exit the REPL",
    aliases = "quit"
)
public class ExitCommand implements AnnotatedCommand {


    private static final String[] GOODBYE_MESSAGES = {
        "Goodbye!", "See ya!", "Bye!", "Catch you later!"
    };

    @Override
    public boolean isImmediate() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        return CommandResult.exit(getRandomGoodbyeMessage(), "prompt_input_exit");
    }


    static String getRandomGoodbyeMessage() {
        return GOODBYE_MESSAGES[ThreadLocalRandom.current().nextInt(GOODBYE_MESSAGES.length)];
    }
}
