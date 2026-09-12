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
import org.apache.commons.lang3.Strings;

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
            .filter(row -> Strings.CS.equals("java-session-metrics", row.path("type").asText()))
            .toList();
        assertEquals(2, metrics.size());
        assertEquals(0, metrics.getFirst().path("seq").asLong());
        assertEquals(1, metrics.get(1).path("seq").asLong());
        assertEquals("fork", metrics.get(1).path("sessionId").asText());
    }

    @Test
    void midTurnInjectedPromptRowDoesNotEnterTheMetricsCoverageObligation(
        @TempDir Path dir) throws Exception {
        // Historical poison shape: a queued prompt drained mid-turn was stamped
        // with the then-current promptSource ("typed") while chained onto a
        // tool_result row. It participates in an already-measured turn and must
        // not require its own metrics turn, or the restore completeness check
        // fails forever after every restart.
        Path file = dir.resolve("poisoned.jsonl");
        String toolResult = "{\"type\":\"user\",\"uuid\":\"tr-1\",\"isSidechain\":false,"
            + "\"message\":{\"role\":\"user\",\"content\":"
            + "[{\"type\":\"tool_result\",\"tool_use_id\":\"call-1\",\"content\":\"ok\"}]}}";
        String drained = "{\"type\":\"user\",\"uuid\":\"drained-1\",\"isSidechain\":false,"
            + "\"parentUuid\":\"tr-1\",\"promptSource\":\"typed\","
            + "\"origin\":{\"kind\":\"human\"},"
            + "\"message\":{\"role\":\"user\",\"content\":\"queued while busy\"}}";
        String opener = "{\"type\":\"user\",\"uuid\":\"turn-1\",\"isSidechain\":false,"
            + "\"parentUuid\":null,\"promptSource\":\"typed\","
            + "\"message\":{\"role\":\"user\",\"content\":\"the real turn\"}}";
        Files.writeString(file, toolResult + "\n" + drained + "\n" + opener + "\n");

        assertEquals(List.of("turn-1"), new SessionStorage().readMetricTurnIds(file),
            "the mid-turn injected row is excluded; the real turn opener remains");
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
