package com.claudecode.session;

import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SessionSearchTest {

    @Test
    void listsAndResolvesSessionsAcrossSameRepoWorktrees(@TempDir Path baseDir) {
        String main = "/repo/main";
        String worktree = "/repo/worktree-a";
        SessionManager mainManager = new SessionManager(baseDir, main);
        SessionManager worktreeManager = new SessionManager(baseDir, worktree);
        SessionStorage storage = new SessionStorage();
        String mainId = writeSession(mainManager, storage, "main");
        String worktreeId = writeSession(worktreeManager, storage, "worktree");

        SessionSearch search = new SessionSearch(
            baseDir, main, () -> List.of(main, worktree));

        assertEquals(List.of(mainId, worktreeId).stream().sorted().toList(),
            search.listSessions().stream().map(SessionSearch.LocatedSession::id).sorted().toList());
        SessionSearch.LocatedSession found = search.findExactSessionId(worktreeId).orElseThrow();
        assertEquals(worktreeManager.getSessionFile(worktreeId), found.sessionFile());
        assertEquals(worktree, found.cwd());
    }

    @Test
    void exactTitleSearchDeduplicatesSessionIdAndKeepsNewestCopy(@TempDir Path baseDir) throws Exception {
        String main = "/repo/main";
        String worktree = "/repo/worktree-a";
        SessionManager mainManager = new SessionManager(baseDir, main);
        SessionManager worktreeManager = new SessionManager(baseDir, worktree);
        SessionStorage storage = new SessionStorage();
        String id = UUID.randomUUID().toString();
        writeSession(mainManager, storage, id, "main");
        setTitle(mainManager, storage, id, "Payments");
        Thread.sleep(5);
        writeSession(worktreeManager, storage, id, "worktree");
        setTitle(worktreeManager, storage, id, " payments ");

        SessionSearch search = new SessionSearch(
            baseDir, main, () -> List.of(main, worktree));

        List<SessionSearch.LocatedSession> matches = search.searchExactCustomTitle("PAYMENTS");
        assertEquals(1, matches.size());
        assertEquals(worktreeManager.getSessionFile(id), matches.getFirst().sessionFile());
    }

    @Test
    void unifiedTitleSearchSupportsContainsAndAiTitle(@TempDir Path baseDir) throws Exception {
        String cwd = "/repo/main";
        SessionManager manager = new SessionManager(baseDir, cwd);
        SessionStorage storage = new SessionStorage();
        String id = writeSession(manager, storage, "prompt");
        var entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "ai-title");
        entry.put("aiTitle", "Quarterly Payments Review");
        entry.put("sessionId", id);
        storage.appendCustomEntry(manager.getSessionFile(id), entry);

        SessionSearch search = new SessionSearch(baseDir, cwd, () -> List.of(cwd));

        assertEquals(id, search.searchCustomTitle(" payments ", false, 10).getFirst().id());
        assertTrue(search.searchCustomTitle("payments", true, 10).isEmpty());
        assertEquals(id, search.searchCustomTitle("quarterly payments review", true, 1)
            .getFirst().id());
    }

    @Test
    void aliasDirectoryMakesCurrentProjectSessionsDiscoverable(@TempDir Path baseDir) throws Exception {
        Path current = Files.createDirectories(baseDir.resolve("current"));
        Path added = Files.createDirectories(baseDir.resolve("added"));
        SessionManager currentManager = new SessionManager(baseDir, current.toString());
        SessionManager addedManager = new SessionManager(baseDir, added.toString());
        String id = writeSession(currentManager, new SessionStorage(), "aliased session");
        addedManager.recordSessionAlias(currentManager.projectDirectory());
        Path aliasFile = addedManager.projectDirectory().resolve(".session-aliases");
        Files.writeString(aliasFile, "\u0000-invalid\n", StandardOpenOption.APPEND);
        addedManager.recordSessionAlias(currentManager.projectDirectory());

        SessionSearch search = new SessionSearch(baseDir, added.toString(), () -> List.of(added.toString()));
        SessionSearch.LocatedSession found = search.listSessions().stream()
            .filter(session -> session.id().equals(id)).findFirst().orElseThrow();

        assertTrue(found.isAlias());
        assertEquals(currentManager.getSessionFile(id).toRealPath(), found.sessionFile().toRealPath());
        String canonicalProjectDirectory = currentManager.projectDirectory().toRealPath().toString();
        assertEquals(1, Files.readAllLines(aliasFile).stream()
            .filter(line -> line.equals(canonicalProjectDirectory)).count());
    }

    @Test
    void historicalProjectDirectoryFallbackUsesNewestLiteCwd(@TempDir Path baseDir) throws Exception {
        Path cwd = Files.createDirectories(baseDir.resolve("moved-project"));
        Path historical = Files.createDirectories(baseDir.resolve("projects").resolve("historical-key"));
        String id = UUID.randomUUID().toString();
        Files.writeString(historical.resolve(id + ".jsonl"),
            "{\"type\":\"user\",\"cwd\":\"" + cwd + "\",\"message\":{\"content\":\"historical\"}}\n");

        SessionSearch search = new SessionSearch(baseDir, cwd.toString(), () -> List.of(cwd.toString()));

        assertEquals(historical.resolve(id + ".jsonl"), search.listSessions().getFirst().sessionFile());
    }

    @Test
    void allCompatibleLongPathHashDirectoriesAreDiscovered(@TempDir Path baseDir) throws Exception {
        String cwd = "/" + "very-long-segment/".repeat(20);
        SessionManager manager = new SessionManager(baseDir, cwd);
        String exactName = SessionManager.sanitizePath(manager.projectPath());
        String prefix = exactName.substring(0, SessionManager.MAX_SANITIZED_LENGTH) + "-";
        Files.createDirectories(baseDir.resolve("projects").resolve(exactName));
        Files.createDirectories(baseDir.resolve("projects").resolve(prefix + "bunhash"));
        Files.createDirectories(baseDir.resolve("projects").resolve(prefix + "nodehash"));

        assertEquals(3, manager.compatibleProjectDirectories().size());
    }

    @Test
    void metadataOnlyTranscriptRemainsPickerResumableWithFallbackTitle(@TempDir Path baseDir) throws Exception {
        String cwd = "/repo/main";
        SessionManager manager = new SessionManager(baseDir, cwd);
        String id = UUID.randomUUID().toString();
        Path file = manager.getSessionFile(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"type\":\"agent-name\",\"agentName\":\"hidden\",\"sessionId\":\"" + id + "\"}\n");

        SessionSearch search = new SessionSearch(baseDir, cwd, () -> List.of(cwd));

        List<SessionSearch.LocatedSession> listed = search.listSessions();
        assertEquals(1, listed.size());
        assertEquals("(session)", listed.getFirst().info().summary());
        assertEquals(file, search.findExactSessionId(id).orElseThrow().sessionFile());
    }

    @Test
    void progressiveListingDoesNotPreloadTheRemainingSessions(@TempDir Path baseDir) {
        String cwd = "/repo/main";
        SessionManager manager = new SessionManager(baseDir, cwd);
        SessionStorage storage = new SessionStorage();
        for (int i = 0; i < 75; i++) writeSession(manager, storage, "prompt " + i);

        SessionSearch.ProgressiveListing listing = new SessionSearch(
            baseDir, cwd, () -> List.of(cwd)).progressiveSessions();

        assertEquals(50, listing.loadMore(50).size());
        assertTrue(listing.hasMore());
        assertEquals(25, listing.loadMore(50).size());
        assertFalse(listing.hasMore());
    }

    private static String writeSession(SessionManager manager, SessionStorage storage, String text) {
        String id = manager.createSession();
        writeSession(manager, storage, id, text);
        return id;
    }

    private static void writeSession(SessionManager manager, SessionStorage storage,
                                     String id, String text) {
        storage.appendMessage(manager.getSessionFile(id),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText(text)));
    }

    private static void setTitle(SessionManager manager, SessionStorage storage,
                                 String id, String title) {
        var entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "custom-title");
        entry.put("customTitle", title);
        entry.put("sessionId", id);
        storage.appendCustomEntry(manager.getSessionFile(id), entry);
    }
}
