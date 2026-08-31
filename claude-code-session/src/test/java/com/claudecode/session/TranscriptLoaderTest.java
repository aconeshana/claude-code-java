package com.claudecode.session;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.engine.RequestMessageNormalizer;
import com.claudecode.core.message.Message;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class TranscriptLoaderTest {

    private static final String SESSION = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path temp;

    @Test
    void loadTranscriptFileBridgesProgressRecoversParallelResultsAndKeepsReadOnlyWire()
            throws Exception {
        Path file = temp.resolve(SESSION + ".jsonl");
        write(file,
            user("u1", null, "2026-01-01T00:00:00Z", "start", false, null),
            assistantTool("a1", "u1", "2026-01-01T00:00:01Z", "msg-1", "tool-1"),
            progress("p1", "a1", "2026-01-01T00:00:02Z"),
            assistantTool("a2", "a1", "2026-01-01T00:00:03Z", "msg-1", "tool-2"),
            toolResult("tr2", "a2", "2026-01-01T00:00:04Z", "tool-2", false, null),
            toolResult("tr1", "p1", "2026-01-01T00:00:05Z", "tool-1", false, null),
            user("leaf", "tr1", "2026-01-01T00:00:06Z", "continue", false, null),
            system("duration", "leaf", "2026-01-01T00:00:07Z", "turn_duration"),
            system("notice", "duration", "2026-01-01T00:00:08Z", "informational"),
            "{\"type\":\"last-prompt\",\"leafUuid\":\"leaf\",\"explicit\":true,"
                + "\"sessionId\":\"" + SESSION + "\"}",
            "{malformed");
        byte[] before = Files.readAllBytes(file);

        TranscriptLoader.TranscriptFile loaded = new TranscriptLoader().loadTranscriptFile(file);
        List<String> chain = TranscriptLoader.buildConversationChain(loaded, "leaf").stream()
            .map(node -> node.path("uuid").asText()).toList();

        assertEquals(List.of("u1", "a1", "a2", "tr2", "tr1", "leaf", "duration", "notice"),
            chain);
        assertEquals(List.of("leaf"), loaded.leafUuids().stream().toList());
        assertFalse(loaded.messageEntries().containsKey("p1"));
        assertEquals(List.of("leaf"), loaded.lastPromptLeafUuid().stream().toList());
        assertArrayEquals(before, Files.readAllBytes(file),
            "loading a transcript must not mutate the persisted JSONL wire");

        List<Message> resumed = new TranscriptLoader().loadTranscriptFromFile(file).messages();
        JsonNode wire = JsonUtils.getMapper().valueToTree(
            RequestMessageNormalizer.normalizeForApi(resumed, false, false));
        assertEquals(List.of("user", "assistant", "user"),
            fields(wire, null, "role"));
        assertEquals(List.of("tool-1", "tool-2"),
            fields(wire.get(1).path("content"), "tool_use", "id"));
        assertEquals(List.of("tool-2", "tool-1"),
            fields(wire.get(2).path("content"), "tool_result", "tool_use_id"));
    }

    @Test
    void loadTranscriptFileAppliesPreservedMessagesAndSnipRelinks() throws Exception {
        Path file = temp.resolve("preserved.jsonl");
        write(file,
            user("old", null, "2026-01-01T00:00:00Z", "old", false, null),
            assistantText("head", "old", "2026-01-01T00:00:01Z", "kept", "one"),
            user("tail", "head", "2026-01-01T00:00:02Z", "kept two", false, null),
            compactBoundary("boundary", null, "2026-01-01T00:00:04Z",
                "{\"anchorUuid\":\"boundary\",\"uuids\":[\"head\",\"tail\"]}"),
            user("removed", "boundary", "2026-01-01T00:00:05Z", "remove me", false, null),
            systemWithSnip("snip", "removed", "2026-01-01T00:00:06Z", "removed"),
            user("leaf", "snip", "2026-01-01T00:00:07Z", "after snip", false, null));

        TranscriptLoader.TranscriptFile loaded =
            new TranscriptLoader().loadTranscriptFile(file, true);
        List<String> chain = TranscriptLoader.buildConversationChain(loaded, "leaf").stream()
            .map(node -> node.path("uuid").asText()).toList();

        assertFalse(loaded.messageEntries().containsKey("old"));
        assertFalse(loaded.messageEntries().containsKey("removed"));
        assertEquals("boundary", loaded.parentByUuid().get("head"));
        assertEquals("head", loaded.parentByUuid().get("tail"));
        assertEquals(List.of("boundary", "head", "tail", "snip", "leaf"), chain);
        assertEquals(0, loaded.messageEntries().get("head").path("message").path("usage")
            .path("input_tokens").asInt(-1));
    }

    @Test
    void importedHookSuccessContentSurvivesResumeAndReentersTheWire() throws Exception {
        Path file = temp.resolve("hook-success.jsonl");
        write(file,
            user("leaf", null, "2026-01-01T00:00:00Z", "continue", false, null),
            "{\"type\":\"attachment\",\"uuid\":\"hook\",\"parentUuid\":\"leaf\","
                + "\"timestamp\":\"2026-01-01T00:00:01Z\",\"sessionId\":\"" + SESSION + "\","
                + "\"attachment\":{\"type\":\"hook_success\",\"content\":\"loaded context\","
                + "\"hookName\":\"SessionStart\",\"toolUseID\":\"hook-1\","
                + "\"hookEvent\":\"SessionStart\",\"stdout\":\"\",\"stderr\":\"\","
                + "\"exitCode\":0,\"command\":\"echo ok\",\"durationMs\":12}}",
            "{\"type\":\"last-prompt\",\"leafUuid\":\"leaf\",\"explicit\":true,"
                + "\"sessionId\":\"" + SESSION + "\"}");

        List<Message> resumed = new TranscriptLoader().loadTranscriptFromFile(file).messages();
        JsonNode wire = JsonUtils.getMapper().valueToTree(
            RequestMessageNormalizer.normalizeForApi(resumed, false, false));

        assertEquals(List.of("leaf", "hook"), resumed.stream().map(Message::uuid).toList());
        assertTrue(Strings.CS.contains(wire.toString(),
            "SessionStart hook success: loaded context"));
    }

    @Test
    void loadTranscriptFromFileSupportsJsonShapesAndRejectsReleasedLimit() throws Exception {
        Path array = temp.resolve("array.json");
        Files.writeString(array, "[" + user("u1", null, "2026-01-01T00:00:00Z",
            "hello", false, null) + "]", StandardCharsets.UTF_8);
        Path object = temp.resolve("object.json");
        Files.writeString(object, "{\"messages\":[" + user("u2", null,
            "2026-01-01T00:00:00Z", "world", false, null) + "]}", StandardCharsets.UTF_8);

        TranscriptLoader loader = new TranscriptLoader();
        assertEquals(List.of("u1"), loader.loadTranscriptFromFile(array).messages().stream()
            .map(Message::uuid).toList());
        assertEquals(List.of("u2"), loader.loadTranscriptFromFile(object).messages().stream()
            .map(Message::uuid).toList());

        Path empty = temp.resolve("empty.json");
        Files.writeString(empty, "[]", StandardCharsets.UTF_8);
        assertEquals("no_messages", assertThrows(TranscriptLoader.TranscriptFileFormatException.class,
            () -> loader.loadTranscriptFromFile(empty)).code());

        Path huge = temp.resolve("huge.json");
        try (FileChannel channel = FileChannel.open(huge,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(256L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {' '}));
        }
        assertEquals("too_large", assertThrows(TranscriptLoader.TranscriptFileFormatException.class,
            () -> loader.loadTranscriptFromFile(huge)).code());
    }

    @Test
    void getAgentTranscriptPrependsForkContextAndReturnsAgentBudgetRecords() throws Exception {
        String agentId = "a1234567890abcdef";
        Path project = temp.resolve("project");
        Path parent = project.resolve(SESSION + ".jsonl");
        Path agent = project.resolve(SESSION).resolve("subagents")
            .resolve("agent-" + agentId + ".jsonl");
        write(parent,
            user("parent-u", null, "2026-01-01T00:00:00Z", "parent", false, null),
            assistantText("parent-a", "parent-u", "2026-01-01T00:00:01Z", "parent", "answer"));
        write(agent,
            "{\"type\":\"fork-context-ref\",\"agentId\":\"" + agentId + "\","
                + "\"parentSessionId\":\"" + SESSION + "\","
                + "\"parentLastUuid\":\"parent-a\",\"contextLength\":2}",
            user("agent-u", null, "2026-01-01T00:00:02Z", "child", true, agentId),
            assistantText("agent-a", "agent-u", "2026-01-01T00:00:03Z", "child", "done",
                true, agentId),
            "{\"type\":\"content-replacement\",\"agentId\":\"" + agentId + "\","
                + "\"replacements\":[{\"kind\":\"tool-result\",\"toolUseId\":\"tool-1\","
                + "\"replacement\":\"[trimmed]\"}]}");

        TranscriptLoader.AgentTranscript transcript = new TranscriptLoader()
            .getAgentTranscript(agent, agentId).orElseThrow();

        assertEquals(List.of("parent-u", "parent-a", "agent-u", "agent-a"),
            transcript.messages().stream().map(Message::uuid).toList());
        assertEquals(List.of("agent-u", "agent-a"),
            transcript.sidechainMessages().stream().map(Message::uuid).toList());
        assertEquals(List.of(new ToolResultBudget.Replacement("tool-1", "[trimmed]")),
            transcript.contentReplacements());
    }

    @Test
    void removeTranscriptMessageMatchesUuidFieldAndRetainsMalformedRows() throws Exception {
        Path file = temp.resolve("remove.jsonl");
        String parentMention = user("child", "target", "2026-01-01T00:00:01Z",
            "keep", false, null);
        String malformed = "not-json target";
        write(file,
            user("target", null, "2026-01-01T00:00:00Z", "drop", false, null),
            malformed,
            parentMention);

        new SessionStorage().removeTranscriptMessage(file, "target");

        String remaining = Files.readString(file);
        assertFalse(Strings.CS.contains(remaining, "\"uuid\":\"target\""));
        assertTrue(Strings.CS.contains(remaining, "\"parentUuid\":\"target\""));
        assertTrue(Strings.CS.contains(remaining, malformed));
    }

    @Test
    void removeTranscriptMessageDoesNotRewriteAnOldRowInAnOversizedTranscript()
            throws Exception {
        Path file = temp.resolve("oversized-remove.jsonl");
        byte[] firstRow = (user("old", null, "2026-01-01T00:00:00Z", "keep", false, null)
            + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(firstRow));
            channel.position(50L * 1024 * 1024 + 1);
            channel.write(ByteBuffer.wrap(new byte[] {'\n'}));
        }

        new SessionStorage().removeTranscriptMessage(file, "old");

        ByteBuffer prefix = ByteBuffer.allocate(firstRow.length);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.read(prefix);
        }
        assertArrayEquals(firstRow, prefix.array(),
            "released 2.1.197 leaves an old row intact when the bounded rewrite cap applies");
    }

    private static void write(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static List<String> fields(JsonNode array, String type, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            if (type == null || type.equals(item.path("type").asText())) {
                values.add(item.path(field).asText());
            }
        }
        return List.copyOf(values);
    }

    private static String user(String uuid, String parent, String timestamp, String text,
                               boolean sidechain, String agentId) {
        return envelope("user", uuid, parent, timestamp, sidechain, agentId,
            "\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}");
    }

    private static String assistantText(String uuid, String parent, String timestamp,
                                        String messageId, String text) {
        return assistantText(uuid, parent, timestamp, messageId, text, false, null);
    }

    private static String assistantText(String uuid, String parent, String timestamp,
                                        String messageId, String text,
                                        boolean sidechain, String agentId) {
        return envelope("assistant", uuid, parent, timestamp, sidechain, agentId,
            "\"message\":{\"id\":\"" + messageId + "\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"" + text + "\"}],\"model\":\"claude\","
                + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":9,"
                + "\"output_tokens\":1,\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0}}");
    }

    private static String assistantTool(String uuid, String parent, String timestamp,
                                        String messageId, String toolUseId) {
        return envelope("assistant", uuid, parent, timestamp, false, null,
            "\"message\":{\"id\":\"" + messageId + "\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\","
                + "\"id\":\"" + toolUseId + "\",\"name\":\"Bash\",\"input\":{}}],"
                + "\"model\":\"claude\",\"stop_reason\":null,\"usage\":{"
                + "\"input_tokens\":1,\"output_tokens\":1,"
                + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}");
    }

    private static String toolResult(String uuid, String parent, String timestamp,
                                     String toolUseId, boolean sidechain, String agentId) {
        return envelope("user", uuid, parent, timestamp, sidechain, agentId,
            "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"" + toolUseId + "\",\"content\":\"ok\"}]}");
    }

    private static String progress(String uuid, String parent, String timestamp) {
        return envelope("progress", uuid, parent, timestamp, false, null,
            "\"content\":\"working\"");
    }

    private static String system(String uuid, String parent, String timestamp, String subtype) {
        return envelope("system", uuid, parent, timestamp, false, null,
            "\"subtype\":\"" + subtype + "\",\"content\":\"notice\"");
    }

    private static String compactBoundary(String uuid, String parent, String timestamp,
                                          String preservedMessages) {
        return envelope("system", uuid, parent, timestamp, false, null,
            "\"subtype\":\"compact_boundary\",\"content\":\"summary\","
                + "\"compactMetadata\":{\"preservedMessages\":" + preservedMessages + "}");
    }

    private static String systemWithSnip(String uuid, String parent, String timestamp,
                                         String removedUuid) {
        return envelope("system", uuid, parent, timestamp, false, null,
            "\"subtype\":\"history_snip\",\"content\":\"snipped\","
                + "\"snipMetadata\":{\"removedUuids\":[\"" + removedUuid + "\"]}");
    }

    private static String envelope(String type, String uuid, String parent, String timestamp,
                                   boolean sidechain, String agentId, String body) {
        String parentJson = parent == null ? "null" : "\"" + parent + "\"";
        String agent = agentId == null ? "" : ",\"agentId\":\"" + agentId + "\"";
        return "{\"type\":\"" + type + "\",\"uuid\":\"" + uuid + "\","
            + "\"parentUuid\":" + parentJson + ",\"timestamp\":\"" + timestamp + "\","
            + "\"sessionId\":\"" + SESSION + "\",\"isSidechain\":" + sidechain
            + agent + "," + body + "}";
    }
}
