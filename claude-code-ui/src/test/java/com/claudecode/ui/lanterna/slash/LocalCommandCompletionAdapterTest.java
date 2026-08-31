package com.claudecode.ui.lanterna.slash;

import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.SDKMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCommandCompletionAdapterTest {

    @Test
    void ordinarySystemCompletionUsesStdoutSystemMessages() {
        List<SDKMessage> messages = LocalCommandCompletionAdapter.toMessages(
            "cost", "", CommandResult.of("Total cost: $0.00"));

        assertEquals(2, messages.size());
        assertInstanceOf(SDKMessage.System.class, messages.getFirst());
        SDKMessage.System output = assertInstanceOf(SDKMessage.System.class, messages.get(1));
        assertEquals("local_command", output.message().subtype());
        assertEquals("<local-command-stdout>Total cost: $0.00</local-command-stdout>",
            output.message().content());
    }

    @Test
    void localTextCompletionUsesUserInputAndSystemOutput() {
        List<SDKMessage> messages = LocalCommandCompletionAdapter.toMessages(
            "cost", "", CommandResult.local("Total cost: $0.00"));

        assertEquals(2, messages.size());
        SDKMessage.User input = assertInstanceOf(SDKMessage.User.class, messages.getFirst());
        assertEquals("/cost", input.message().message().text());
        SDKMessage.System output = assertInstanceOf(SDKMessage.System.class, messages.get(1));
        assertEquals("local_command", output.message().subtype());
        assertEquals("<local-command-stdout>Total cost: $0.00</local-command-stdout>",
            output.message().content());
    }

    @Test
    void emptyLocalCompletionStillEchoesCommandInput() {
        List<SDKMessage> messages = LocalCommandCompletionAdapter.toMessages(
            "clear", "", CommandResult.local(""));

        assertEquals(1, messages.size());
        SDKMessage.User input = assertInstanceOf(SDKMessage.User.class, messages.getFirst());
        assertEquals("/clear", input.message().message().text());
    }

    @Test
    void promptFailureUsesUserMessagesAndStderrWrapper() {
        List<SDKMessage> messages = LocalCommandCompletionAdapter.toMessages(
            "mcp__srv__prompt", "arg", CommandResult.error("Error: network down"));

        assertEquals(2, messages.size());
        SDKMessage.User input = assertInstanceOf(SDKMessage.User.class, messages.getFirst());
        assertEquals("/mcp__srv__prompt arg", input.message().message().text());
        SDKMessage.User output = assertInstanceOf(SDKMessage.User.class, messages.get(1));
        assertEquals("<local-command-stderr>Error: network down</local-command-stderr>",
            output.message().message().text());
    }

    @Test
    void metaMessagesAreModelVisibleUserMessagesAfterCompletion() {
        CommandResult result = CommandResult.of("Allowed Bash")
            .withMetaMessages(List.of("Permission granted for: Bash"));

        List<SDKMessage> messages = LocalCommandCompletionAdapter.toMessages(
            "permissions", "", result);

        assertEquals(3, messages.size());
        SDKMessage.User meta = assertInstanceOf(SDKMessage.User.class, messages.get(2));
        assertTrue(meta.message().isMeta());
        assertEquals("Permission granted for: Bash", meta.message().message().text());
    }

    @Test
    void skippedCompletionProducesNoMessages() {
        assertTrue(LocalCommandCompletionAdapter.toMessages(
            "btw", "question", CommandResult.skip()).isEmpty());
    }
}
