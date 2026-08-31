package com.claudecode.commands.impl.config;


import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputStyleCommandTest {

    @Test
    void alwaysReturnsTheOriginalDeprecationNotice() {
        CommandResult r = new OutputStyleCommand().execute(CommandContext.minimal(), "");
        assertEquals("/output-style has been deprecated. Use /config to change your output "
            + "style, or set it in your settings file. Changes take effect on the next session.",
            r.output());
    }

    @Test
    void metadataMatchesTheHiddenDeprecatedCommand() {
        OutputStyleCommand command = new OutputStyleCommand();
        assertEquals("Deprecated: use /config to change output style", command.description());
        assertTrue(command.isHidden());
        assertFalse(command.supportsNonInteractive());
    }
}
