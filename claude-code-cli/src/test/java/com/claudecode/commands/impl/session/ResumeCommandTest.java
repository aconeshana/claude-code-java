package com.claudecode.commands.impl.session;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.session.ResumeRequest;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeCommandTest {

    @Test
    void emptyArgs_headlessPointsAtInteractivePicker(@TempDir Path baseDir) {

        // headless text session list was removed (2026-07-12); a single pointer
        // line remains.
        SessionManager manager = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        ResumeCommand cmd = new ResumeCommand();
        CommandResult r = cmd.execute(
            ProviderTestCommandPorts.withSessions(CommandContext.minimal(), manager, storage), "");
        assertTrue(Strings.CS.contains(r.output(), "interactive session picker requires the REPL"),
            "got: " + r.output());
    }

    @Test
    void withArg_emptySessionDir_tsNoConversationsText(@TempDir Path baseDir) {

        SessionManager manager = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        ResumeCommand cmd = new ResumeCommand();
        CommandResult r = cmd.execute(
            ProviderTestCommandPorts.withSessions(CommandContext.minimal(), manager, storage), "anything");
        assertEquals("No conversations found to resume.", r.output().trim());
    }

    @Test
    void aliases_matchTs() {
        assertEquals(List.of("continue"), new ResumeCommand().aliases(),
            "TS aliases: ['continue'] — the Java-invented 'restore' was removed (2026-07-12)");
    }

    @Test
    void exactIdMatch_loadsMessages(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String id = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(id),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));
        storage.appendMessage(mgr.getSessionFile(id),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("world")));

        List<Message> loaded = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(loaded::addAll)
            .build();

        CommandResult r = new ResumeCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), id);

        // Resume now routes through MessagesDeserializer (same recovery pipeline
        // as every other load): a trailing user message triggers a continuation
        // sentinel, so 2 transcript messages + 1 sentinel = 3.
        assertEquals(3, loaded.size(), "loads both transcript messages (+ recovery sentinel)");
        assertTrue(loaded.stream().anyMatch(m -> m instanceof UserMessage um
                && um.message().text() != null && Strings.CS.contains(um.message().text(), "hello")),
            "original 'hello' message must be loaded");
        assertTrue(loaded.stream().anyMatch(m -> m instanceof UserMessage um
                && um.message().text() != null && Strings.CS.contains(um.message().text(), "world")),
            "original 'world' message must be loaded");
        assertTrue(Strings.CS.contains(r.output(), "Resumed session " + id),
            "expected resume confirmation; got: " + r.output());
    }

    @Test
    void exactIdMatch_switchesEngineSessionIdToTheResumedSession(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String id = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(id),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        List<String> switchedTo = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(_ -> {})
            .sessionIdSwitcher(switchedTo::add)
            .build();

        new ResumeCommand().execute(ProviderTestCommandPorts.withSessions(ctx, mgr, storage), id);

        assertEquals(List.of(id), switchedTo,
            "without this, new messages after /resume <id> would keep writing to the old session file");
    }

    @Test
    void idPrefix_isNotAcceptedBecauseTsRequiresAFullUuid(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String id = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(id),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));

        List<Message> loaded = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(loaded::addAll)
            .build();

        CommandResult r = new ResumeCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), id.substring(0, 8));

        assertTrue(loaded.isEmpty(), "TS does not resume by UUID prefix");
        assertEquals("Session " + id.substring(0, 8) + " was not found.", r.output().trim());
    }

    @Test
    void exactId_delegatesToTheSharedResumePipeline(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String id = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(id),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        List<ResumeRequest> requests = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .resumeLauncher(requests::add)
            .build();

        CommandResult result = new ResumeCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), id);

        assertTrue(result.silent(), "the shared resume pipeline owns rendering");
        assertEquals(1, requests.size());
        ResumeRequest request = requests.getFirst();
        assertEquals(id, request.sessionId());
        assertEquals(mgr.getSessionFile(id), request.sessionFile());
        assertEquals(ResumeRequest.Entrypoint.SLASH_COMMAND_SESSION_ID, request.entrypoint());
    }

    @Test
    void exactTitle_delegatesWithTitleEntrypoint(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String id = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(id),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));
        setCustomTitle(mgr, storage, id, "payments");

        List<ResumeRequest> requests = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .resumeLauncher(requests::add)
            .build();

        CommandResult result = new ResumeCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "payments");

        assertTrue(result.silent());
        assertEquals(ResumeRequest.Entrypoint.SLASH_COMMAND_TITLE,
            requests.getFirst().entrypoint());
    }

    @Test
    void missingSession_returnsErrorMessage(@TempDir Path baseDir) {

        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        storage.appendMessage(mgr.getSessionFile(mgr.createSession()),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));

        CommandResult r = new ResumeCommand().execute(
            ProviderTestCommandPorts.withSessions(CommandContext.minimal(), mgr, storage),
            "00000000-0000-0000-0000-000000000000");
        assertTrue(Strings.CS.contains(r.output(), "was not found."),
            "expected TS not-found message; got: " + r.output());
    }

    @Test
    void resumeWithoutLoaderHandle_explainsLimitation(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String id = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(id),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));

        // Minimal context has loadMessages == null.
        CommandResult r = new ResumeCommand().execute(
            ProviderTestCommandPorts.withSessions(CommandContext.minimal(), mgr, storage), id);
        assertNotNull(r.output());
        assertTrue(Strings.CS.contains(r.output(), "--resume"),
            "expected guidance pointing at --resume flag; got: " + r.output());
    }


    private static void setCustomTitle(SessionManager mgr, SessionStorage storage,
                                       String id, String title) {
        var entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "custom-title");
        entry.put("customTitle", title);
        entry.put("sessionId", id);
        storage.appendCustomEntry(mgr.getSessionFile(id), entry);
    }

    @Test
    void customTitleExactMatch_resumes(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String id = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(id),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));
        setCustomTitle(mgr, storage, id, "My Payment Fix");

        List<Message> loaded = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(loaded::addAll)
            .build();


        CommandResult r = new ResumeCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "  my payment fix ");

        assertTrue(Strings.CS.contains(r.output(), "Resumed"), "output: " + r.output());
        assertFalse(loaded.isEmpty());
    }

    @Test
    void customTitleMultipleMatches_asksToPick(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        for (int i = 0; i < 2; i++) {
            String id = mgr.createSession();
            storage.appendMessage(mgr.getSessionFile(id),
                new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));
            setCustomTitle(mgr, storage, id, "dup");
        }

        CommandResult r = new ResumeCommand().execute(
            ProviderTestCommandPorts.withSessions(CommandContext.minimal(), mgr, storage), "dup");


        assertTrue(Strings.CS.contains(r.output(), 
            "Found 2 sessions matching dup. Please use /resume to pick a specific session."),
            "got: " + r.output());
    }

    @Test
    void noTitleMatch_tsNotFoundText(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        storage.appendMessage(mgr.getSessionFile(mgr.createSession()),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));

        CommandResult r = new ResumeCommand().execute(
            ProviderTestCommandPorts.withSessions(CommandContext.minimal(), mgr, storage), "no-such-title");
        assertEquals("Session no-such-title was not found.", r.output().trim());
    }
}
