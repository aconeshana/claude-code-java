package com.claudecode.commands.impl.info;


import com.claudecode.commands.CommandContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageCommandTest {

    @Test
    void released197UsageIsAvailableForSessionAccounting() {
        UsageCommand command = new UsageCommand();
        assertTrue(command.isAvailable(CommandContext.minimal()));
    }

    @Test
    void name_and_description() {
        UsageCommand cmd = new UsageCommand();
        assertEquals("usage", cmd.name());
        assertEquals("Show plan usage limits", cmd.description());
        assertTrue(cmd.aliases().isEmpty());
    }

}
