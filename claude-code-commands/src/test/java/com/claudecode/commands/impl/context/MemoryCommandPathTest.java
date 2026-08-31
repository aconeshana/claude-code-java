package com.claudecode.commands.impl.context;


import com.claudecode.core.config.ClaudePaths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryCommandPathTest {

    @Test
    void userMemoryPathUsesClaudeConfigHome() {
        assertEquals(ClaudePaths.CLAUDE_MD, MemoryCommand.userMemoryPath());
    }
}
