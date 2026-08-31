package com.claudecode.tools.bash;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CommandSemanticsTest {

    @Test
    void bashSearchAndPredicateStatusesAreNotErrorsAtExitOne() {
        assertFalse(CommandSemantics.bash("grep needle file", 1, "", "").isError());
        assertEquals("No matches found",
            CommandSemantics.bash("grep needle file", 1, "", "").message());
        assertFalse(CommandSemantics.bash("find . -name '*.java'", 1, "", "").isError());
        assertFalse(CommandSemantics.bash("test -f missing", 1, "", "").isError());
        assertTrue(CommandSemantics.bash("grep needle file", 2, "", "").isError());
    }

    @Test
    void bashUsesLastPipelineCommandAndDoesNotSplitQuotedPipe() {
        assertFalse(CommandSemantics.bash("printf 'a|b'", 0, "", "").isError());
        assertEquals("No matches found",
            CommandSemantics.bash("cat file | rg needle", 1, "", "").message());
    }

    @Test
    void powerShellExternalExecutablesFollowTheirOwnStatuses() {
        assertFalse(CommandSemantics.powerShell("& 'C:\\bin\\rg.exe' needle file", 1, "", "").isError());
        assertFalse(CommandSemantics.powerShell("findstr needle file", 1, "", "").isError());
        assertFalse(CommandSemantics.powerShell("robocopy src dst", 1, "", "").isError());
        assertTrue(CommandSemantics.powerShell("robocopy src dst", 8, "", "").isError());
    }
}
