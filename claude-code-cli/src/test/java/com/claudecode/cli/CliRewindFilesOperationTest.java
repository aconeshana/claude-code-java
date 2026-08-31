package com.claudecode.cli;

import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliRewindFilesOperationTest {

    @Test
    void cliRequiresResumeAndRejectsAPrompt() {
        StringWriter missingResumeError = new StringWriter();
        ClaudeCodeCli missingResume = new ClaudeCodeCli();
        missingResume.setErrorWriter(new PrintWriter(missingResumeError, true));

        int missingResumeCode = ClaudeCodeCli.commandLine(missingResume)
            .execute("--rewind-files", "message-id");

        assertEquals(1, missingResumeCode);
        assertEquals("Error: --rewind-files requires --resume\n",
            missingResumeError.toString());

        StringWriter promptError = new StringWriter();
        ClaudeCodeCli withPrompt = new ClaudeCodeCli();
        withPrompt.setErrorWriter(new PrintWriter(promptError, true));

        int promptCode = ClaudeCodeCli.commandLine(withPrompt).execute(
            "--rewind-files", "message-id", "--resume", "session-id", "prompt");

        assertEquals(1, promptCode);
        assertEquals("Error: --rewind-files is a standalone operation and cannot be used with a prompt\n",
            promptError.toString());
    }

    @Test
    void operationRestoresTheSelectedUserCheckpointAndExits(@TempDir Path tempDir)
            throws Exception {
        String messageId = "user-message-id";
        SessionIdentity identity = SessionIdentity.of("session-id");
        FileHistoryManager history = new FileHistoryManager(
            identity, tempDir, tempDir.resolve("backups"));
        Path changed = tempDir.resolve("changed.txt");
        Files.writeString(changed, "before");
        history.makeSnapshot(messageId);
        history.trackEdit(changed.toString());
        Files.writeString(changed, "after");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(noopClient())
            .model("claude-sonnet-4-6")
            .workingDirectory(tempDir.toString())
            .sessionIdentity(identity)
            .initialFileHistoryManager(history)
            .initialMessages(List.of(new UserMessage(
                messageId, MessageContent.ofText("change the file"))))
            .build());
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();

        int exitCode = CliRewindFilesOperation.run(
            engine, messageId,
            CliOutput.borrowed(new PrintWriter(output, true)),
            CliOutput.borrowed(new PrintWriter(error, true)));

        assertEquals(0, exitCode);
        assertEquals("before", Files.readString(changed));
        assertEquals("Files rewound to state at message " + messageId + "\n",
            output.toString());
        assertEquals("", error.toString());
    }

    @Test
    void operationRejectsAnIdThatIsNotAUserMessage(@TempDir Path tempDir) {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(noopClient())
            .model("claude-sonnet-4-6")
            .workingDirectory(tempDir.toString())
            .build());
        StringWriter error = new StringWriter();

        int exitCode = CliRewindFilesOperation.run(
            engine, "missing",
            CliOutput.borrowed(new PrintWriter(new StringWriter(), true)),
            CliOutput.borrowed(new PrintWriter(error, true)));

        assertEquals(1, exitCode);
        assertEquals("Error: --rewind-files requires a user message UUID, but missing "
            + "is not a user message in this session\n", error.toString());
    }

    private static StreamingClient noopClient() {
        return new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of().iterator();
            }

            @Override
            public String getModel() {
                return "claude-sonnet-4-6";
            }
        };
    }
}
