package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class ApiRequestDumperTest {

    @TempDir Path dir;

    private static String wireBody(String toolsJson, String messagesJson) {
        return """
            {"model":"claude-sonnet-5","max_tokens":8192,
             "system":[{"type":"text","text":"You are Claude Code, Anthropic's official CLI for Claude.","cache_control":{"type":"ephemeral"}},
                       {"type":"text","text":"MAIN PROMPT","cache_control":{"type":"ephemeral"}}],
             "thinking":{"type":"adaptive"},
             "tools":%s,
             "messages":%s,
             "stream":true}
            """.formatted(toolsJson, messagesJson);
    }

    private static final String BASH_TOOL =
        "[{\"name\":\"Bash\",\"description\":\"run\",\"input_schema\":{\"type\":\"object\"},\"cache_control\":{\"type\":\"ephemeral\"}}]";

    private List<JsonNode> readEntries(String sessionId) throws Exception {
        Path f = dir.resolve(sessionId + ".jsonl");
        if (!Files.exists(f)) return List.of();
        return Files.readAllLines(f).stream()
            .filter(l -> !StringUtils.isBlank(l))
            .map(l -> {
                try { return JsonUtils.getMapper().readTree(l); }
                catch (Exception e) { throw new RuntimeException(e); }
            })
            .toList();
    }

    @Test
    void firstRequestWritesInitAndUserMessages() throws Exception {
        ApiRequestDumper dumper = new ApiRequestDumper(true, dir);
        dumper.dumpNow("s1", wireBody(BASH_TOOL,
            "[{\"role\":\"user\",\"content\":\"hello\"}]"), "t0");

        List<JsonNode> entries = readEntries("s1");
        assertEquals(3, entries.size());
        assertEquals("init", entries.getFirst().get("type").asText());
        JsonNode init = entries.getFirst().get("data");
        assertEquals("claude-sonnet-5", init.get("model").asText());
        assertTrue(init.get("system").isArray(), "wire system block array preserved");
        assertEquals("adaptive", init.get("thinking").get("type").asText(),
            "API-layer fields must be visible in the dump");
        assertEquals("ephemeral",
            init.get("tools").get(0).get("cache_control").get("type").asText());
        assertFalse(init.has("messages"), "init data must exclude messages");
        assertEquals("message", entries.get(1).get("type").asText());
        assertEquals("hello", entries.get(1).get("data").get("content").asText());
        assertEquals("request", entries.get(2).get("type").asText());
        assertEquals(1, entries.get(2).get("sequence").asInt());
        assertEquals("hello",
            entries.get(2).get("data").get("messages").get(0).get("content").asText());
        assertTrue(entries.get(2).hasNonNull("sha256"));
    }

    @Test
    void secondTurnWritesOnlyNewUserMessages() throws Exception {
        ApiRequestDumper dumper = new ApiRequestDumper(true, dir);
        dumper.dumpNow("s1", wireBody(BASH_TOOL,
            "[{\"role\":\"user\",\"content\":\"q1\"}]"), "t0");
        dumper.dumpNow("s1", wireBody(BASH_TOOL,
            "[{\"role\":\"user\",\"content\":\"q1\"},"
            + "{\"role\":\"assistant\",\"content\":\"a1\"},"
            + "{\"role\":\"user\",\"content\":\"q2\"}]"), "t1");

        List<JsonNode> entries = readEntries("s1");
        // init + q1 + request#1 + q2 + request#2. The incremental compatibility
        // stream still omits assistant entries; each request snapshot is complete.
        assertEquals(5, entries.size());
        assertEquals("init", entries.getFirst().get("type").asText());
        assertEquals("q1", entries.get(1).get("data").get("content").asText());
        assertEquals("request", entries.get(2).get("type").asText());
        assertEquals("q2", entries.get(3).get("data").get("content").asText());
        assertEquals("request", entries.get(4).get("type").asText());
        assertEquals("assistant",
            entries.get(4).get("data").get("messages").get(1).get("role").asText());
    }

    @Test
    void changedToolsWriteSystemUpdate() throws Exception {
        ApiRequestDumper dumper = new ApiRequestDumper(true, dir);
        dumper.dumpNow("s1", wireBody(BASH_TOOL,
            "[{\"role\":\"user\",\"content\":\"q1\"}]"), "t0");
        String twoTools = "[{\"name\":\"Bash\",\"description\":\"run\",\"input_schema\":{}},"
            + "{\"name\":\"Grep\",\"description\":\"search\",\"input_schema\":{}}]";
        dumper.dumpNow("s1", wireBody(twoTools,
            "[{\"role\":\"user\",\"content\":\"q1\"}]"), "t1");

        List<JsonNode> entries = readEntries("s1");
        assertEquals(5, entries.size());
        assertEquals("request", entries.get(2).get("type").asText());
        assertEquals("system_update", entries.get(3).get("type").asText());
        assertEquals(2, entries.get(3).get("data").get("tools").size());
        assertEquals("request", entries.get(4).get("type").asText());
    }

    @Test
    void sessionsGetSeparateFilesAndState() throws Exception {
        ApiRequestDumper dumper = new ApiRequestDumper(true, dir);
        dumper.dumpNow("s1", wireBody(BASH_TOOL, "[{\"role\":\"user\",\"content\":\"a\"}]"), "t0");
        dumper.dumpNow("s2", wireBody(BASH_TOOL, "[{\"role\":\"user\",\"content\":\"b\"}]"), "t0");

        assertEquals("init", readEntries("s1").getFirst().get("type").asText());
        assertEquals("init", readEntries("s2").getFirst().get("type").asText(),
            "each session must get its own init entry");
    }

    @Test
    void disabledDumperWritesNothing() throws Exception {
        ApiRequestDumper dumper = new ApiRequestDumper(false, dir);
        dumper.dump("s1", wireBody(BASH_TOOL, "[{\"role\":\"user\",\"content\":\"x\"}]"));
        Thread.sleep(50);
        assertFalse(Files.exists(dir.resolve("s1.jsonl")));
        assertFalse(dumper.isEnabled());
    }

    @Test
    void blockContentMessagesArePreservedVerbatim() throws Exception {
        ApiRequestDumper dumper = new ApiRequestDumper(true, dir);
        String messages = "[{\"role\":\"user\",\"content\":"
            + "[{\"type\":\"text\",\"text\":\"hi\",\"cache_control\":{\"type\":\"ephemeral\"}}]}]";
        dumper.dumpNow("s1", wireBody(BASH_TOOL, messages), "t0");

        JsonNode msg = readEntries("s1").get(1).get("data");
        assertTrue(msg.get("content").isArray());
        assertEquals("ephemeral",
            msg.get("content").get(0).get("cache_control").get("type").asText(),
            "wire-level cache markers must appear in the dump verbatim");
        JsonNode snapshot = readEntries("s1").get(2).get("data");
        assertEquals("ephemeral", snapshot.get("messages").get(0).get("content")
            .get(0).get("cache_control").get("type").asText());
    }
}
