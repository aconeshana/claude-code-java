package com.claudecode.cli;

import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliSessionIdTest {

    @Test
    void acceptsAnUnusedUuidAsTheInitialSessionIdentity(@TempDir Path tempDir) {
        String id = "11111111-2222-4333-8444-555555555555";
        SessionIdentity identity = resolve(tempDir, snapshot("--session-id", id), new StringWriter());

        assertEquals(id, identity.get());
    }

    @Test
    void rejectsInvalidAndAlreadyUsedSessionIds(@TempDir Path tempDir) throws Exception {
        StringWriter invalidError = new StringWriter();
        CliLaunchAbort invalid = assertThrows(CliLaunchAbort.class,
            () -> resolve(tempDir, snapshot("--session-id", "not-a-uuid"), invalidError));
        assertEquals(1, invalid.exitCode());
        assertEquals("Error: Invalid session ID. Must be a valid UUID.\n", invalidError.toString());

        String existing = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        SessionManager manager = new SessionManager(tempDir, "/workspace/project");
        Files.createDirectories(manager.getSessionFile(existing).getParent());
        Files.writeString(manager.getSessionFile(existing), "{}\n");
        StringWriter collisionError = new StringWriter();
        CliLaunchAbort collision = assertThrows(CliLaunchAbort.class,
            () -> CliWorkspaceBootstrap.resolveInitialSessionIdentity(
                snapshot("--session-id", existing).session(), manager,
                output(collisionError)));
        assertEquals(1, collision.exitCode());
        assertEquals("Error: Session ID " + existing + " is already in use.\n",
            collisionError.toString());
    }

    @Test
    void rejectsContinueOrResumeWithoutForkSessionSupport(@TempDir Path tempDir) {
        String id = "11111111-2222-4333-8444-555555555555";
        for (String[] argv : new String[][] {
                {"--session-id", id, "--continue"},
                {"--session-id", id, "--resume"},
                {"--session-id", id, "--resume", "other"}}) {
            StringWriter error = new StringWriter();
            CliLaunchAbort failure = assertThrows(CliLaunchAbort.class,
                () -> resolve(tempDir, snapshot(argv), error));
            assertEquals(1, failure.exitCode());
            assertEquals("Error: --session-id can only be used with --continue or --resume "
                + "if --fork-session is also specified.\n", error.toString());
        }
    }

    private static SessionIdentity resolve(
            Path tempDir, CliLaunchRequest request, StringWriter error) {
        return CliWorkspaceBootstrap.resolveInitialSessionIdentity(
            request.session(), new SessionManager(tempDir, "/workspace/project"), output(error));
    }

    private static CliOutput output(StringWriter error) {
        return CliOutput.borrowed(new PrintWriter(error, true));
    }

    private static CliLaunchRequest snapshot(String... argv) {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        new CommandLine(cli).parseArgs(argv);
        return cli.snapshotLaunchRequest();
    }
}
