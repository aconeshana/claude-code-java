package com.claudecode.commands.result;

import com.claudecode.commands.CommandOutputChannel;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.CommandResultDisplay;
import com.claudecode.core.message.MessageContent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandResultTest {

    @Test
    void regularDisplayOutputIsAlsoTheHeadlessResult() {
        CommandResult result = CommandResult.of("Total cost: $0.00");

        assertEquals("Total cost: $0.00", result.output());
        assertEquals("Total cost: $0.00", result.headlessOutput());
        assertEquals(CommandOutputChannel.STDOUT, result.outputChannel());
        assertEquals(CommandResultDisplay.SYSTEM, result.display());
        assertTrue(result.persist());
    }

    @Test
    void localTextResultUsesUserInputAndSystemOutputCompletionKind() {
        CommandResult result = CommandResult.local("Session cost: $0.00");

        assertEquals("Session cost: $0.00", result.output());
        assertEquals("Session cost: $0.00", result.headlessOutput());
        assertEquals(CommandOutputChannel.STDOUT, result.outputChannel());
        assertEquals(CommandResultDisplay.LOCAL, result.display());
        assertTrue(result.persist());
    }

    @Test
    void localJsxResultUsesUserMessagesForInputAndOutput() {
        CommandResult result = CommandResult.localJsx("Enabled plan mode");

        assertEquals(CommandOutputChannel.STDOUT, result.outputChannel());
        assertEquals(CommandResultDisplay.USER, result.display());
        assertEquals("Enabled plan mode", result.headlessOutput());
        assertFalse(result.shouldQuery());
    }

    @Test
    void localFailureKeepsUserInputAndSystemStderrWithEmptyHeadlessResult() {
        CommandResult result = CommandResult.localError("java.lang.IllegalStateException: boom");

        assertEquals("", result.headlessOutput());
        assertEquals(CommandOutputChannel.STDERR, result.outputChannel());
        assertEquals(CommandResultDisplay.LOCAL, result.display());
        assertTrue(result.persist());
    }

    @Test
    void displayOnlyOutputIsVisibleInteractivelyButEmptyInHeadlessResult() {
        CommandResult result = CommandResult.displayOnly(
            "Compacted (ctrl+o to see full summary)");

        assertEquals("Compacted (ctrl+o to see full summary)", result.output());
        assertEquals("", result.headlessOutput());
        assertEquals(CommandOutputChannel.STDOUT, result.outputChannel());
        assertEquals(CommandResultDisplay.USER, result.display(),
            "TS compact displayText is emitted as a user local-command-stdout replay");
    }

    @Test
    void promptFailureUsesStderrButKeepsTsHeadlessSuccessResultEmpty() {
        CommandResult result = CommandResult.error("Error: network down");

        assertEquals("Error: network down", result.output());
        assertEquals("", result.headlessOutput(),
            "TS local/prompt command exceptions do not populate resultText");
        assertEquals(CommandOutputChannel.STDERR, result.outputChannel());
        assertEquals(CommandResultDisplay.USER, result.display());
        assertTrue(result.persist());
        assertFalse(result.shouldQuery());
    }

    @Test
    void localJsxQueryCanSeparateCallbackOutputFromModelPrompt() {
        CommandResult result = CommandResult.forLocalJsxQuery(
            "plan", "build a game", "Enabled plan mode");

        assertEquals("Enabled plan mode", result.output());
        assertEquals(
            "<local-command-stdout>Enabled plan mode</local-command-stdout>",
            result.promptInvocation().textContent());
        assertEquals(List.of(MessageContent.ofText("""
            <command-name>/plan</command-name>
                        <command-message>plan</command-message>
                        <command-args>build a game</command-args>""")),
            result.promptInvocation().precedingUserMessages());
        assertTrue(result.promptInvocation().scalarTextContent());
        assertTrue(result.shouldQuery());
    }

    @Test
    void skipHasNoChannelAndCannotPersist() {
        CommandResult result = CommandResult.skip();

        assertEquals(CommandOutputChannel.NONE, result.outputChannel());
        assertEquals(CommandResultDisplay.SKIP, result.display());
        assertFalse(result.persist());
        assertTrue(result.silent());
    }
}
