package com.claudecode.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.lang3.Strings;

class SessionOperationsServiceTest {
    private static final String SOURCE = "11111111-1111-4111-8111-111111111111";
    private static final String U1 = "22222222-2222-4222-8222-222222222222";
    private static final String P1 = "33333333-3333-4333-8333-333333333333";
    private static final String A1 = "44444444-4444-4444-8444-444444444444";
    private static final String U2 = "55555555-5555-4555-8555-555555555555";

    @TempDir Path tempDir;

    @Test
    void readsLatestConversationChainAndAppliesSdkFilteringAndPagination() throws Exception {
        Path project = tempDir.resolve("project");
        SessionManager manager = new SessionManager(tempDir.resolve(".claude"), project.toString());
        Files.createDirectories(manager.projectDirectory());
        Files.writeString(manager.getSessionFile(SOURCE), String.join("\n",
            row("user", "u1", null, SOURCE, "first", false, false),
            row("assistant", "a1", "u1", SOURCE, "answer", false, false),
            row("system", "s1", "a1", SOURCE, "notice", false, false),
            row("user", "meta", "s1", SOURCE, "hidden", true, false),
            row("user", "u2", "s1", SOURCE, "second", false, false),
            row("assistant", "side", "u1", SOURCE, "side", false, true)) + "\n");

        SessionOperationsService service = new SessionOperationsService(tempDir.resolve(".claude"));
        List<SessionOperationsService.HistoryMessage> messages = service.getSessionMessages(
            SOURCE, project.toString(), 2, 1, true);

        assertEquals(List.of("a1", "s1"), messages.stream().map(
            SessionOperationsService.HistoryMessage::uuid).toList());
        assertEquals(List.of("assistant", "system"), messages.stream().map(
            SessionOperationsService.HistoryMessage::type).toList());
        assertTrue(service.getSessionMessages("not-a-uuid", (String) null, null, null, false).isEmpty());
    }

    @Test
    void infoUsesOfficialSdkSummaryPriority() throws Exception {
        Path project = tempDir.resolve("project");
        SessionManager manager = new SessionManager(tempDir.resolve(".claude"), project.toString());
        Files.createDirectories(manager.projectDirectory());
        Files.writeString(manager.getSessionFile(SOURCE), String.join("\n",
            "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"" + SOURCE
                + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"cwd\":\"/old\","
                + "\"message\":{\"role\":\"user\",\"content\":\"first\"}}",
            "{\"type\":\"summary\",\"summary\":\"summary\",\"sessionId\":\"" + SOURCE + "\"}",
            "{\"type\":\"last-prompt\",\"lastPrompt\":\"last prompt\",\"sessionId\":\"" + SOURCE + "\"}") + "\n");

        SessionInfo info = new SessionOperationsService(tempDir.resolve(".claude"))
            .getSessionInfo(SOURCE, project.toString()).orElseThrow();

        assertEquals("last prompt", info.summary());
        assertEquals("first", info.firstPrompt());
    }

    @Test
    void renameAndTagAppendExactLocalWireAndRejectEmptyValues() throws Exception {
        Path project = tempDir.resolve("project");
        SessionManager manager = new SessionManager(tempDir.resolve(".claude"), project.toString());
        Files.createDirectories(manager.projectDirectory());
        Files.writeString(manager.getSessionFile(SOURCE), row(
            "user", "u1", null, SOURCE, "hello", false, false) + "\n");
        SessionOperationsService service = new SessionOperationsService(tempDir.resolve(".claude"));

        service.renameSession(SOURCE, "  Named  ", project.toString());
        service.tagSession(SOURCE, "  work  ", project.toString());
        service.tagSession(SOURCE, null, project.toString());

        List<String> lines = Files.readAllLines(manager.getSessionFile(SOURCE));
        assertEquals("{\"type\":\"custom-title\",\"customTitle\":\"Named\",\"sessionId\":\"" + SOURCE + "\"}", lines.get(1));
        assertEquals("{\"type\":\"tag\",\"tag\":\"work\",\"sessionId\":\"" + SOURCE + "\"}", lines.get(2));
        assertEquals("{\"type\":\"tag\",\"tag\":\"\",\"sessionId\":\"" + SOURCE + "\"}", lines.get(3));
        assertThrows(IllegalArgumentException.class,
            () -> service.renameSession(SOURCE, "  ", project.toString()));
        assertThrows(IllegalArgumentException.class,
            () -> service.tagSession(SOURCE, "  ", project.toString()));
    }

    @Test
    void forkRemapsIdsBridgesProgressAndStopsAtRequestedMessage() throws Exception {
        Path project = tempDir.resolve("project");
        SessionManager manager = new SessionManager(tempDir.resolve(".claude"), project.toString());
        Files.createDirectories(manager.projectDirectory());
        Files.writeString(manager.getSessionFile(SOURCE), String.join("\n",
            row("user", U1, null, SOURCE, "first", false, false),
            row("progress", P1, U1, SOURCE, "progress", false, false),
            row("assistant", A1, P1, SOURCE, "answer", false, false),
            row("user", U2, A1, SOURCE, "later", false, false),
            "{\"type\":\"content-replacement\",\"sessionId\":\"" + SOURCE
                + "\",\"replacements\":[{\"messageId\":\"" + A1 + "\"}]}") + "\n");
        AtomicInteger ids = new AtomicInteger();
        SessionOperationsService service = new SessionOperationsService(tempDir.resolve(".claude"),
            Clock.fixed(Instant.parse("2026-02-03T04:05:06Z"), ZoneOffset.UTC),
            () -> "00000000-0000-4000-8000-" + String.format("%012d", ids.incrementAndGet()));

        SessionOperationsService.ForkedSession fork = service.forkSession(
            SOURCE, project.toString(), A1, "  Branch  ");

        List<JsonNode> entries = Files.readAllLines(manager.getSessionFile(fork.sessionId())).stream()
            .map(line -> {
                try { return JsonUtils.getMapper().readTree(line); }
                catch (Exception e) { throw new AssertionError(e); }
            }).toList();
        assertEquals(List.of("user", "assistant", "content-replacement", "custom-title"),
            entries.stream().map(e -> e.path("type").asText()).toList());
        assertNotEquals(U1, entries.getFirst().path("uuid").asText());
        assertEquals(entries.getFirst().path("uuid").asText(), entries.get(1).path("parentUuid").asText());
        assertEquals("Branch", entries.getLast().path("customTitle").asText());
        assertEquals("2026-02-03T04:05:06Z", entries.get(1).path("timestamp").asText());
        assertFalse(entries.stream().anyMatch(e -> Strings.CS.equals(
            U2, e.path("forkedFrom").path("messageUuid").asText())));
        assertNull(entries.getFirst().get("teamName"));
    }

    private static String row(String type, String uuid, String parent, String sessionId,
                              String content, boolean meta, boolean sidechain) throws Exception {
        var node = JsonUtils.getMapper().createObjectNode();
        node.put("type", type).put("uuid", uuid).put("sessionId", sessionId)
            .put("timestamp", "2025-01-01T00:00:00Z");
        if (parent == null) node.putNull("parentUuid"); else node.put("parentUuid", parent);
        if (meta) node.put("isMeta", true);
        if (sidechain) node.put("isSidechain", true);
        node.set("message", JsonUtils.getMapper().createObjectNode()
            .put("role", Strings.CS.equals("assistant", type) ? "assistant" : "user")
            .put("content", content));
        return JsonUtils.getMapper().writeValueAsString(node);
    }
}
