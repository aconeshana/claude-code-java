package com.claudecode.commands.prompt;


import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptShellExecutionTest {

    @Test
    void expandsBlockAndWhitespaceDelimitedInlineCommands() {
        List<String> calls = new ArrayList<>();
        String input = """
            before
            ```!
              printf block
            ```
            inline !`printf inline`
            do-not-expand`!`printf adjacent`
            """;

        String result = PromptShellExecution.expand(input, (command, _) -> {
            calls.add(command);
            return Strings.CS.endsWith(command, "block") ? "$block" : "$inline";
        });

        assertEquals(List.of("printf block", "printf inline"), calls);
        assertEquals("""
            before
            $block
            inline $inline
            do-not-expand`!`printf adjacent`
            """, result);
    }

    @Test
    void leavesEmptyShellPatternsUntouched() {
        String input = "before ```!\n \n``` after !` `";
        assertEquals(input, PromptShellExecution.expand(input,
            (_, _) -> { throw new AssertionError("must not execute"); }));
    }
}
