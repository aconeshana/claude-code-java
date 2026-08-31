package com.claudecode.session;

import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMetricsPersistenceTest {
    @Test
    void readsMetricRowsWithoutTreatingThemAsConversationMessages(@TempDir Path dir)
        throws Exception {
        Path file = dir.resolve("s.jsonl");
        SessionStorage storage = new SessionStorage();
        ObjectNode start = metric("s", 0, "session/start", null);
        storage.appendCustomEntry(file, start);

        assertEquals(1, storage.readSessionMetrics(file).size());
        assertTrue(storage.readMessages(file).isEmpty());
    }

    @Test
    void javaForkPreservesRetainedTurnMetricsAndResequences(@TempDir Path dir)
        throws Exception {
        Path source = dir.resolve("source.jsonl");
        Path target = dir.resolve("target.jsonl");
        String user = "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"source\",\"promptSource\":\"typed\","
            + "\"parentUuid\":null,\"isSidechain\":false,\"message\":{\"role\":\"user\",\"content\":\"hi\"}}";
        Files.writeString(source, user + "\n"
            + JsonUtils.getMapper().writeValueAsString(metric("source", 0, "session/start", null)) + "\n"
            + JsonUtils.getMapper().writeValueAsString(metric("source", 1, "turn/start", "u1")) + "\n");

        assertEquals(List.of("u1"), new SessionStorage().readMetricTurnIds(source));

        new SessionForkService().fork(source, target, "source", "fork");

        List<JsonNode> rows = JsonUtils.readJsonLines(target);
        List<JsonNode> metrics = rows.stream()
            .filter(row -> "java-session-metrics".equals(row.path("type").asText()))
            .toList();
        assertEquals(2, metrics.size());
        assertEquals(0, metrics.get(0).path("seq").asLong());
        assertEquals(1, metrics.get(1).path("seq").asLong());
        assertEquals("fork", metrics.get(1).path("sessionId").asText());
    }

    private static ObjectNode metric(String sessionId, long seq, String event, String turnId) {
        ObjectNode row = JsonUtils.getMapper().createObjectNode();
        row.put("type", SessionMetricsEvent.TRANSCRIPT_TYPE);
        row.put("schemaVersion", 1);
        row.put("seq", seq);
        row.put("time", seq + 1);
        row.put("sessionId", sessionId);
        row.put("event", event);
        if (turnId != null) {
            row.put("turnId", turnId);
            row.put("turn", 1);
        }
        return row;
    }
}
