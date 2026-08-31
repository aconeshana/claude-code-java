package com.claudecode.commands.impl.session;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RenameCommandTest {

    @Test
    void rename_requiresActiveSession() {
        // minimal context has no session id
        CommandResult r = new RenameCommand().execute(CommandContext.minimal(), "new-name");
        assertFalse(r.shouldQuery());
        assertTrue(Strings.CS.contains(r.output(), "no active session"), r.output());
    }

    @Test
    void rename_noArg_returnsUsageHint() {
        CommandContext ctx = contextWithSession("test-session-id");
        CommandResult r = new RenameCommand().execute(ctx, "");
        assertTrue(Strings.CS.contains(r.output(), "Usage: /rename"), r.output());
        assertFalse(r.shouldQuery());
    }

    @Test
    void rename_withName_persistsToTranscript(@TempDir Path dir) throws Exception {
        SessionManager mgr = new SessionManager(dir, "/test/project");
        String id = mgr.createSession();
        RenameCommand cmd = new RenameCommand();
        CommandContext ctx = contextWithSession(id, mgr, false);
        CommandResult r = cmd.execute(ctx, "My Cool PR");
        assertTrue(Strings.CS.contains(r.output(), "My Cool PR"), r.output());


        // (JSONL is the single source of truth).
        Path jsonl = mgr.getSessionFile(id);
        assertTrue(Files.isRegularFile(jsonl),
            "session JSONL must be written at: " + jsonl);
        String content = Files.readString(jsonl);
        assertTrue(Strings.CS.contains(content, "\"type\":\"custom-title\""), content);
        assertTrue(Strings.CS.contains(content, "\"type\":\"agent-name\""), content);
        assertTrue(Strings.CS.contains(content, "\"customTitle\":\"My Cool PR\""), content);
        assertTrue(Strings.CS.contains(content, "\"agentName\":\"My Cool PR\""), content);
    }

    @Test
    void teammateCannotRenameItself() {
        CommandResult result = new RenameCommand().execute(
            contextWithSession("s1", null, true), "new-name");

        assertEquals(
            "Cannot rename: This session is a swarm teammate. "
                + "Teammate names are set by the team leader.",
            result.output());
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static CommandContext contextWithSession(String sessionId) {
        return contextWithSession(sessionId, null, false);
    }

    private static CommandContext contextWithSession(
            String sessionId, SessionManager manager, boolean teammate) {
        CommandContext.Builder builder = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, ".", false)
            .currentSessionId(() -> sessionId)
            .toolingCommands(ProviderTestCommandPorts.collaboration(teammate));
        if (manager != null) {
            builder.sessionCommands(ProviderTestCommandPorts.sessions(manager, new SessionStorage()));
        }
        return builder.build();
    }

}
