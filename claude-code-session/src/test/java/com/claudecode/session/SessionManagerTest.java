package com.claudecode.session;

import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.UserMessage;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;





class SessionManagerTest {

    @Test
    void sessionIdExistsChecksTheExactProjectTranscript(@TempDir Path tempDir)
            throws IOException {
        SessionManager local = new SessionManager(tempDir, "/workspace/project");
        String existing = UUID.randomUUID().toString();
        String missing = UUID.randomUUID().toString();
        Path transcript = local.getSessionFile(existing);
        Files.createDirectories(transcript.getParent());
        Files.writeString(transcript, "{}\n");

        assertTrue(local.sessionIdExists(existing));
        assertFalse(local.sessionIdExists(missing));
    }

    @Test
    void deleteSessionPermanentlyRemovesTranscriptAndOwnedDirectory(@TempDir Path tempDir)
            throws Exception {
        SessionManager manager = new SessionManager(tempDir, "/workspace/project");
        String sessionId = UUID.randomUUID().toString();
        Path transcript = manager.getSessionFile(sessionId);
        Path result = manager.getToolResultsDir(sessionId).resolve("result.txt");
        Files.createDirectories(result.getParent());
        Files.writeString(transcript, "{}\n");
        Files.writeString(result, "large result");

        assertTrue(manager.deleteSessionPermanently(sessionId));
        assertFalse(Files.exists(transcript));
        assertFalse(Files.exists(result.getParent().getParent()));
    }

    @Test
    void deleteSessionPermanentlyRejectsNonUuidIds(@TempDir Path tempDir) {
        SessionManager manager = new SessionManager(tempDir, "/workspace/project");

        assertThrows(IllegalArgumentException.class,
            () -> manager.deleteSessionPermanently("../not-a-session"));
    }

    @TempDir
    Path tempDir;

    private SessionManager manager;
    private static final String TEST_CWD = "/projects/my.app";

    @BeforeEach
    void setUp() {
        manager = new SessionManager(tempDir, TEST_CWD);
    }

    @Test
    void createSessionReturnsUuid() {
        String sessionId = manager.createSession();
        assertNotNull(sessionId);
        assertFalse(sessionId.isEmpty());
        // No directory is pre-created — file is written on first append
    }

    @Test
    void getSessionFileResolvesCorrectly() {
        String id = "test-session-id";
        Path file = manager.getSessionFile(id);

        String sanitized = SessionManager.sanitizePath(TEST_CWD);
        Path expected = tempDir.resolve("projects").resolve(sanitized).resolve(id + ".jsonl");
        assertEquals(expected, file);
    }

    @Test
    void getToolResultsDirIsNestedUnderTheSessionDirectory() {
        String id = "test-session-id";
        Path expected = manager.getProjectDir().resolve(id).resolve("tool-results");
        assertEquals(expected, manager.getToolResultsDir(id));
    }

    @Test
    void listSessionsReturnsEmptyWhenNoSessions() {
        List<SessionInfo> sessions = manager.listSessions();
        assertTrue(sessions.isEmpty());
    }

    @Test
    void listSessionsFindsCreatedSessions() throws IOException {
        String id1 = manager.createSession();
        String id2 = manager.createSession();

        // Write user messages with text content so sessions pass summary filter

        Path file1 = manager.getSessionFile(id1);
        Files.createDirectories(file1.getParent());
        // User message with text — will be found by extractFirstPromptFromHead
        Files.writeString(file1,
            "{\"type\":\"user\",\"uuid\":\"a\",\"content\":[{\"type\":\"text\",\"text\":\"hello world\"}]," +
            "\"cwd\":\"/tmp\",\"sessionId\":\"" + id1 + "\",\"timestamp\":\"2026-01-01T00:00:00Z\"," +
            "\"userType\":\"external\",\"version\":\"1.0.0\",\"isSidechain\":false}\n");

        Path file2 = manager.getSessionFile(id2);
        Files.createDirectories(file2.getParent());
        Files.writeString(file2,
            "{\"type\":\"user\",\"uuid\":\"b\",\"content\":[{\"type\":\"text\",\"text\":\"another session\"}]," +
            "\"cwd\":\"/tmp\",\"sessionId\":\"" + id2 + "\",\"timestamp\":\"2026-01-01T00:00:01Z\"," +
            "\"userType\":\"external\",\"version\":\"1.0.0\",\"isSidechain\":false}\n");

        List<SessionInfo> sessions = manager.listSessions();
        assertEquals(2, sessions.size());

        SessionInfo s1 = sessions.stream()
                .filter(s -> s.id().equals(id1))
                .findFirst()
                .orElseThrow();
        assertEquals(-1, s1.messageCount(), "lite listings do not scan the whole transcript");
        assertEquals("hello world", s1.summary());

        SessionInfo s2 = sessions.stream()
                .filter(s -> s.id().equals(id2))
                .findFirst()
                .orElseThrow();
        assertEquals(-1, s2.messageCount());
    }

    @Test
    void listSessionsReadsTheOfficialNestedUserMessageShapeWrittenBySessionStorage() {
        String id = manager.createSession();
        Path file = manager.getSessionFile(id);
        UserMessage message = new UserMessage(
            "user-1", MessageContent.ofText("official nested prompt"),
            false, false, null, MessageOrigin.USER, null,
            Instant.parse("2026-07-29T00:00:00Z"), null, "default", id, null);
        new SessionStorage().appendMessageWithParent(file, message, id, TEST_CWD,
            false, null, "main", null, null);

        List<SessionInfo> sessions = manager.listSessions();

        assertEquals(1, sessions.size());
        assertEquals("official nested prompt", sessions.getFirst().summary());
    }

    @Test
    void extractFirstPromptSupportsNestedStringAndNestedBlockContent() {
        assertEquals("string prompt", SessionManager.extractFirstPromptFromHead(
            "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"string prompt\"}}\n"));
        assertEquals("block prompt", SessionManager.extractFirstPromptFromHead(
            "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"block prompt\"}]}}\n"));
    }

    @Test
    void extractFirstPromptFlattensMultilineTerminalPasteForRecentActivity() {
        String head = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
            + "\"first row\\n  [1m second row\\nthird row\"}}\n";

        assertEquals("first row   [1m second row third row",
            SessionManager.extractFirstPromptFromHead(head));
    }

    @Test
    void extractFirstPromptUsesCleanSlashCommandFallback() {
        String clear = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
            + "\"<command-name>/clear</command-name>\\n"
            + "<command-message>clear</command-message>\\n"
            + "<command-args></command-args>\"}}\n";

        assertEquals("/clear", SessionManager.extractFirstPromptFromHead(clear));
    }

    @Test
    void realPromptWinsOverEarlierSlashCommandFallback() {
        String head = """
            {"type":"user","message":{"role":"user","content":\
            "<command-name>/clear</command-name><command-args></command-args>"}}
            {"type":"user","message":{"role":"user","content":"fix the bug"}}
            """;

        assertEquals("fix the bug", SessionManager.extractFirstPromptFromHead(head));
    }

    @Test
    void listSessionsIgnoresNonJsonlFiles() throws IOException {
        Path projectDir = manager.getProjectDir();
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("not-a-session.txt"), "hello");
        Files.writeString(projectDir.resolve("also-not.json"), "{}");

        // create one real session with a user message
        String id = manager.createSession();
        Path file = manager.getSessionFile(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file,
            "{\"type\":\"user\",\"uuid\":\"c\",\"content\":[{\"type\":\"text\",\"text\":\"test\"}]," +
            "\"cwd\":\"/tmp\",\"sessionId\":\"" + id + "\",\"timestamp\":\"2026-01-01T00:00:00Z\"," +
            "\"userType\":\"external\",\"version\":\"1.0.0\",\"isSidechain\":false}\n");

        List<SessionInfo> sessions = manager.listSessions();
        assertEquals(1, sessions.size());
    }

    @Test
    void getAgentTranscriptPathBuildsSubagentsSubdirectory() {
        Path path = manager.getAgentTranscriptPath("parent-session-id", "a1234567890abcde");
        Path expected = manager.getProjectDir().resolve("parent-session-id")
            .resolve("subagents").resolve("agent-a1234567890abcde.jsonl");
        assertEquals(expected, path);
    }

    @Test
    void workflowPathsUseReleasedSessionLayout() {
        assertEquals(manager.getProjectDir().resolve("parent-session-id")
                .resolve("subagents").resolve("workflows").resolve("wf_123456-abc"),
            manager.getWorkflowTranscriptDir("parent-session-id", "wf_123456-abc"));
        assertEquals(manager.getProjectDir().resolve("parent-session-id")
                .resolve("workflows").resolve("wf_123456-abc.json"),
            manager.getWorkflowRunPath("parent-session-id", "wf_123456-abc"));
    }

    @Test
    void listSessionsIgnoresAgentTranscriptSubdirectory() throws IOException {
        // Main session file + a same-stem "subagents/" directory both exist —
// listSessions's non-recursive "*.jsonl" glob must only find the
        // main file, never mistake the sidechain directory for a session.
        String id = manager.createSession();
        Path file = manager.getSessionFile(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file,
            "{\"type\":\"user\",\"uuid\":\"d\",\"content\":[{\"type\":\"text\",\"text\":\"main session\"}]," +
            "\"cwd\":\"/tmp\",\"sessionId\":\"" + id + "\",\"timestamp\":\"2026-01-01T00:00:00Z\"," +
            "\"userType\":\"external\",\"version\":\"1.0.0\",\"isSidechain\":false}\n");

        Path agentFile = manager.getAgentTranscriptPath(id, "aabbccddeeff0011");
        Files.createDirectories(agentFile.getParent());
        Files.writeString(agentFile,
            "{\"type\":\"user\",\"uuid\":\"e\",\"content\":[{\"type\":\"text\",\"text\":\"sub-agent turn\"}]," +
            "\"cwd\":\"/tmp\",\"sessionId\":\"" + id + "\",\"timestamp\":\"2026-01-01T00:00:01Z\"," +
            "\"userType\":\"external\",\"version\":\"1.0.0\",\"isSidechain\":true," +
            "\"agentId\":\"aabbccddeeff0011\"}\n");

        List<SessionInfo> sessions = manager.listSessions();
        assertEquals(1, sessions.size());
        assertEquals(id, sessions.getFirst().id());
    }

    @Test
    void sanitizePathReplacesNonAlphanumeric() {
        assertEquals("-Users-foo-bar-project", SessionManager.sanitizePath("/Users/foo.bar/project"));
        assertEquals("-Users-example-Projects-foo",
            SessionManager.sanitizePath("/Users/example/Projects/foo"));
    }

    @Test
    void sanitizePathTruncatesLongPaths() {
        String longPath = "x".repeat(201);
        String result = SessionManager.sanitizePath(longPath);
        assertEquals("x".repeat(SessionManager.MAX_SANITIZED_LENGTH) + "-sutklk", result,
            "hash suffix must match TS simpleHash/djb2Hash portable fallback");
    }

    @Test
    void longPathReusesExistingDirectoryWithDifferentRuntimeHash() throws IOException {
        String longPath = "p".repeat(260);
        String canonical = SessionManager.canonicalizePath(longPath);
        String exact = SessionManager.sanitizePath(canonical);
        String prefix = exact.substring(0, SessionManager.MAX_SANITIZED_LENGTH) + "-";
        Path bunStyle = tempDir.resolve("projects").resolve(prefix + "different-runtime-hash");
        Files.createDirectories(bunStyle);

        SessionManager longPathManager = new SessionManager(tempDir, longPath);

        assertEquals(bunStyle, longPathManager.getProjectDir());
    }

    @Test
    void reAppendSessionMetadata_copiesMetadataToEof() throws IOException {
        String id = manager.createSession();
        Path file = manager.getSessionFile(id);
        Files.createDirectories(file.getParent());

        // Seed: title/name at head, then a bunch of noise, no re-append yet.
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"custom-title\",\"customTitle\":\"my-name\",\"sessionId\":\"")
          .append(id).append("\"}\n");
        sb.append("{\"type\":\"agent-name\",\"agentName\":\"my-name\",\"sessionId\":\"")
          .append(id).append("\"}\n");
        sb.append("{\"type\":\"agent-color\",\"agentColor\":\"cyan\",\"sessionId\":\"")
          .append(id).append("\"}\n");
        sb.append("{\"type\":\"tag\",\"tag\":\"work\",\"sessionId\":\"").append(id).append("\"}\n");
        sb.append("{\"type\":\"last-prompt\",\"lastPrompt\":\"fix wiring\",\"leafUuid\":\"leaf-7\",\"sessionId\":\"")
          .append(id).append("\"}\n");
        sb.append("{\"type\":\"agent-setting\",\"agentSetting\":\"reviewer\",\"sessionId\":\"")
          .append(id).append("\"}\n");
        sb.append("{\"type\":\"mode\",\"mode\":\"plan\",\"sessionId\":\"")
          .append(id).append("\"}\n");
        sb.append("{\"type\":\"worktree-state\",\"worktreeSession\":{\"path\":\"/tmp/wt\"},\"sessionId\":\"")
          .append(id).append("\"}\n");
        Files.writeString(file, sb.toString());

        long lengthBefore = Files.size(file);
        manager.reAppendSessionMetadata(id);
        long lengthAfter = Files.size(file);

        assertTrue(lengthAfter > lengthBefore, "reAppend should grow the file");
        String content = Files.readString(file);
        // After re-append the file should contain each metadata type twice.
        assertEquals(2, count(content, "\"type\":\"custom-title\""));
        assertEquals(2, count(content, "\"type\":\"agent-name\""));
        assertEquals(2, count(content, "\"type\":\"agent-color\""));
        assertEquals(2, count(content, "\"type\":\"tag\""));
        assertEquals(2, count(content, "\"type\":\"last-prompt\""));
        assertEquals(2, count(content, "\"type\":\"agent-setting\""));
        assertEquals(2, count(content, "\"type\":\"mode\""));
        assertEquals(2, count(content, "\"type\":\"worktree-state\""));
        assertTrue(Strings.CS.endsWith(content, "{\"type\":\"worktree-state\",\"worktreeSession\":{\"path\":\"/tmp/wt\"},\"sessionId\":\""
            + id + "\"}\n"));
        assertTrue(Strings.CS.contains(
            content.substring(content.lastIndexOf("\"type\":\"last-prompt\"")),
            "\"leafUuid\":\"leaf-7\""));

        // agent-setting, mode, then worktree-state.
        int lastPromptIdx = content.lastIndexOf("\"type\":\"last-prompt\"");
        int lastColorIdx = content.lastIndexOf("\"type\":\"agent-color\"");
        int lastNameIdx  = content.lastIndexOf("\"type\":\"agent-name\"");
        int lastTitleIdx = content.lastIndexOf("\"type\":\"custom-title\"");
        int lastTagIdx   = content.lastIndexOf("\"type\":\"tag\"");
        int lastSettingIdx = content.lastIndexOf("\"type\":\"agent-setting\"");
        int lastModeIdx = content.lastIndexOf("\"type\":\"mode\"");
        int lastWorktreeIdx = content.lastIndexOf("\"type\":\"worktree-state\"");
        assertTrue(lastTitleIdx > lastPromptIdx);
        assertTrue(lastTagIdx > lastTitleIdx);
        assertTrue(lastColorIdx > lastNameIdx, "agent-color re-append should be after agent-name");
        assertTrue(lastNameIdx > lastTagIdx);
        assertTrue(lastSettingIdx > lastColorIdx);
        assertTrue(lastModeIdx > lastSettingIdx);
        assertTrue(lastWorktreeIdx > lastModeIdx);
    }

    @Test
    void reAppendSessionMetadataPreservesClearedWorktreeAndDoesNotReviveClearedTitle()
            throws IOException {
        String id = manager.createSession();
        Path file = manager.getSessionFile(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file,
            "{\"type\":\"custom-title\",\"customTitle\":\"old\",\"sessionId\":\"" + id + "\"}\n"
            + "{\"type\":\"custom-title\",\"customTitle\":\"\",\"sessionId\":\"" + id + "\"}\n"
            + "{\"type\":\"worktree-state\",\"worktreeSession\":null,\"sessionId\":\"" + id + "\"}\n");

        manager.reAppendSessionMetadata(id);

        String content = Files.readString(file);
        assertEquals(2, count(content, "\"type\":\"custom-title\""));
        assertEquals(2, count(content, "\"type\":\"worktree-state\""));
        assertTrue(Strings.CS.endsWith(content, "{\"type\":\"worktree-state\",\"worktreeSession\":null,\"sessionId\":\""
            + id + "\"}\n"));
    }

    @Test
    void reAppendSessionMetadata_skipsMissingFile() {
        // Should not throw when the session has no JSONL yet.
        manager.reAppendSessionMetadata("no-such-session");
    }

    @Test
    void reAppendSessionMetadata_skipsEmptyMetadata() throws IOException {
        String id = manager.createSession();
        Path file = manager.getSessionFile(id);
        Files.createDirectories(file.getParent());
        // Session with only messages, no metadata entries — re-append is a no-op.
        Files.writeString(file, "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"" + id + "\"}\n");
        long lengthBefore = Files.size(file);
        manager.reAppendSessionMetadata(id);
        assertEquals(lengthBefore, Files.size(file), "no metadata → no writes");
    }

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    // ── parent-session lineage ───────────────────────────────────────────

    @Test
    void appendParentSession_thenReadBack() {
        String parentId = manager.createSession();
        String childId = manager.createSession();

        manager.appendParentSession(childId, parentId, "clear");

        assertEquals(parentId, manager.readParentSessionId(childId));
        assertEquals("clear", manager.readParentRelation(childId));
    }

    @Test
    void readParentSessionId_returnsNullWhenNoEntry() throws IOException {
        String id = manager.createSession();
        Path file = manager.getSessionFile(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"" + id + "\"}\n");

        assertNull(manager.readParentSessionId(id));
        assertNull(manager.readParentRelation(id));
    }

    @Test
    void appendParentSession_blankArgsAreNoOp() throws IOException {
        String id = manager.createSession();
        Path file = manager.getSessionFile(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"" + id + "\"}\n");
        long lengthBefore = Files.size(file);

        manager.appendParentSession(id, null, "clear");
        manager.appendParentSession(id, "", "clear");
        manager.appendParentSession(null, "some-parent", "clear");

        assertEquals(lengthBefore, Files.size(file), "blank parentSessionId/sessionId must not write");
    }

    @Test
    void reAppendSessionMetadata_reappendsParentSession() throws IOException {
        String id = manager.createSession();
        Path file = manager.getSessionFile(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file,
            "{\"type\":\"parent-session\",\"parentSessionId\":\"parent-1\",\"relation\":\"branch\",\"sessionId\":\""
                + id + "\"}\n"
            + "{\"type\":\"agent-color\",\"agentColor\":\"cyan\",\"sessionId\":\"" + id + "\"}\n");

        manager.reAppendSessionMetadata(id);

        String content = Files.readString(file);
        assertEquals(2, count(content, "\"type\":\"parent-session\""));
        assertEquals("parent-1", parentId(content));
        assertEquals("branch", manager.readParentRelation(id));
        // Most-critical-last ordering: parent-session re-append should land after agent-color.
        assertTrue(content.lastIndexOf("\"type\":\"parent-session\"")
            > content.lastIndexOf("\"type\":\"agent-color\""));
    }

    private String parentId(String content) {
        int idx = content.lastIndexOf("\"parentSessionId\":\"");
        int start = idx + "\"parentSessionId\":\"".length();
        int end = content.indexOf('"', start);
        return content.substring(start, end);
    }
}
