package com.claudecode.session;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionForkServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void forkPreservesRawFieldsAndRewritesOnlyForkProtocolFields() throws Exception {
        Path source = tempDir.resolve("source.jsonl");
        Path target = tempDir.resolve("fork.jsonl");
        Files.writeString(source, String.join("\n",
            "{\"type\":\"user\",\"uuid\":\"u1\",\"parentUuid\":null,\"sessionId\":\"old\",\"isSidechain\":false,\"slug\":\"source-plan-slug\",\"gitBranch\":\"main\",\"unknown\":{\"keep\":1}}",
            "{\"type\":\"progress\",\"uuid\":\"p1\",\"sessionId\":\"old\",\"isSidechain\":false}",
            "{\"type\":\"assistant\",\"uuid\":\"a1\",\"parentUuid\":\"u1\",\"sessionId\":\"old\",\"isSidechain\":false,\"message\":{\"content\":[],\"extra\":\"keep\"}}",
            "{\"type\":\"system\",\"uuid\":\"s-side\",\"parentUuid\":\"a1\",\"sessionId\":\"old\",\"isSidechain\":true}",
            "{\"type\":\"attachment\",\"uuid\":\"x1\",\"parentUuid\":\"a1\",\"sessionId\":\"old\",\"isSidechain\":false,\"attachment\":{\"kind\":\"file\"}}",
            "{\"type\":\"content-replacement\",\"sessionId\":\"old\",\"replacements\":[{\"toolUseId\":\"t1\",\"replacement\":\"preview\"}]}",
            "{\"type\":\"content-replacement\",\"sessionId\":\"other\",\"replacements\":[{\"toolUseId\":\"ignore\"}]}",
            "{\"type\":\"custom-title\",\"sessionId\":\"old\",\"customTitle\":\"Original\"}",
            "malformed") + "\n");

        SessionForkService.ForkResult result = new SessionForkService()
            .fork(source, target, "old", "new");

        assertEquals(3, result.messageCount());
        assertEquals(1, result.contentReplacementCount());
        List<JsonNode> lines = Files.readAllLines(target).stream()
            .filter(line -> !StringUtils.isBlank(line))
            .map(line -> {
                try {
                    return JsonUtils.getMapper().readTree(line);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList();
        assertEquals(4, lines.size());

        assertEquals("u1", lines.getFirst().path("uuid").asText());
        assertTrue(lines.getFirst().path("parentUuid").isNull());
        assertEquals(1, lines.getFirst().path("unknown").path("keep").asInt());
        assertEquals("main", lines.getFirst().path("gitBranch").asText());
        assertFalse(lines.getFirst().has("slug"),
            "a fork must generate an independent plan namespace");

        assertEquals("u1", lines.get(1).path("parentUuid").asText());
        assertEquals("keep", lines.get(1).path("message").path("extra").asText());
        assertEquals("a1", lines.get(2).path("parentUuid").asText());

        for (int i = 0; i < 3; i++) {
            JsonNode entry = lines.get(i);
            assertEquals("new", entry.path("sessionId").asText());
            assertFalse(entry.path("isSidechain").asBoolean(true));
            assertEquals("old", entry.path("forkedFrom").path("sessionId").asText());
            assertEquals(entry.path("uuid").asText(),
                entry.path("forkedFrom").path("messageUuid").asText());
        }

        JsonNode replacements = lines.get(3);
        assertEquals("content-replacement", replacements.path("type").asText());
        assertEquals("new", replacements.path("sessionId").asText());
        assertEquals("t1", replacements.path("replacements").get(0).path("toolUseId").asText());
    }

    @Test
    void forkRejectsTranscriptWithoutMainConversationMessages() throws Exception {
        Path source = tempDir.resolve("source.jsonl");
        Files.writeString(source,
            "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"old\",\"isSidechain\":true}\n");

        assertThrows(SessionForkService.NoMessagesToForkException.class,
            () -> new SessionForkService().fork(source, tempDir.resolve("fork.jsonl"), "old", "new"));
    }
}
