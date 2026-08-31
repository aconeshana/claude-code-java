package com.claudecode.services.insights;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Branch reconstruction: linear chains, forks, orphans, trailing children. */
class TranscriptLogLoaderTest {

    @TempDir
    Path tmp;

    private static final String SESSION = "11111111-2222-3333-4444-555555555555";

    private Path writeTranscript(String... lines) throws IOException {
        Path file = tmp.resolve(SESSION + ".jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private static String entry(String type, String uuid, String parentUuid, String timestamp) {
        String parent = parentUuid == null ? "null" : "\"" + parentUuid + "\"";
        return "{\"type\":\"" + type + "\",\"uuid\":\"" + uuid + "\",\"parentUuid\":" + parent
            + ",\"sessionId\":\"" + SESSION + "\",\"timestamp\":\"" + timestamp + "\","
            + "\"message\":{\"role\":\"" + (Strings.CS.equals("assistant", type) ? "assistant" : "user")
            + "\",\"content\":\"msg " + uuid + "\"}}";
    }

    private static String userEntry(
            String uuid, String parentUuid, String timestamp, String content) {
        String parent = parentUuid == null ? "null" : "\"" + parentUuid + "\"";
        return "{\"type\":\"user\",\"uuid\":\"" + uuid + "\",\"parentUuid\":" + parent
            + ",\"sessionId\":\"" + SESSION + "\",\"timestamp\":\"" + timestamp + "\","
            + "\"cwd\":\"/transcript/project\",\"message\":{\"role\":\"user\","
            + "\"content\":\"" + content + "\"}}";
    }

    private static String assistantEntry(
            String uuid, String parentUuid, String timestamp, String messageId, String content) {
        String parent = parentUuid == null ? "null" : "\"" + parentUuid + "\"";
        return "{\"type\":\"assistant\",\"uuid\":\"" + uuid + "\",\"parentUuid\":" + parent
            + ",\"sessionId\":\"" + SESSION + "\",\"timestamp\":\"" + timestamp + "\","
            + "\"message\":{\"id\":\"" + messageId + "\",\"role\":\"assistant\","
            + "\"content\":" + content + "}}";
    }

    private static String toolResultEntry(
            String uuid, String parentUuid, String timestamp, String toolUseId) {
        return "{\"type\":\"user\",\"uuid\":\"" + uuid + "\",\"parentUuid\":\""
            + parentUuid + "\",\"sessionId\":\"" + SESSION + "\",\"timestamp\":\""
            + timestamp + "\",\"message\":{\"role\":\"user\",\"content\":[{"
            + "\"type\":\"tool_result\",\"tool_use_id\":\"" + toolUseId
            + "\",\"content\":\"ok\"}]}}";
    }

    private static List<String> uuids(SessionLog log) {
        return log.messages().stream()
            .map(m -> m.path("uuid").asText())
            .collect(Collectors.toList());
    }

    @Test
    void linearChainProducesSingleBranchInRootToLeafOrder() throws IOException {
        Path file = writeTranscript(
            entry("user", "u1", null, "2026-01-05T10:00:00.000Z"),
            entry("assistant", "a1", "u1", "2026-01-05T10:00:05.000Z"),
            entry("user", "u2", "a1", "2026-01-05T10:01:00.000Z"));

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogs(file);

        assertEquals(1, logs.size());
        assertEquals(SESSION, logs.getFirst().sessionId());
        assertEquals(List.of("u1", "a1", "u2"), uuids(logs.getFirst()));
    }

    @Test
    void forkedChainProducesOneBranchPerLeaf() throws IOException {
        Path file = writeTranscript(
            entry("user", "u1", null, "2026-01-05T10:00:00.000Z"),
            entry("assistant", "a1", "u1", "2026-01-05T10:00:05.000Z"),
            entry("user", "u2a", "a1", "2026-01-05T10:01:00.000Z"),
            entry("user", "u2b", "a1", "2026-01-05T10:02:00.000Z"));

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogs(file);

        assertEquals(2, logs.size(), "keepAllLeaves: every leaf yields a branch");
        assertEquals(List.of("u1", "a1", "u2a"), uuids(logs.getFirst()));
        assertEquals(List.of("u1", "a1", "u2b"), uuids(logs.get(1)));
    }

    @Test
    void orphanedMessageAnchorsItsOwnPartialBranch() throws IOException {
        Path file = writeTranscript(
            entry("user", "u1", null, "2026-01-05T10:00:00.000Z"),
            entry("assistant", "a1", "u1", "2026-01-05T10:00:05.000Z"),
            // Parent uuid is not present in the file — walk stops at the orphan.
            entry("user", "orphan", "missing-parent", "2026-01-05T11:00:00.000Z"));

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogs(file);

        assertEquals(2, logs.size());
        assertEquals(List.of("u1", "a1"), uuids(logs.getFirst()));
        assertEquals(List.of("orphan"), uuids(logs.get(1)));
    }

    @Test
    void malformedLinesAndNonTranscriptEntriesAreSkipped() throws IOException {
        Path file = writeTranscript(
            "{not json at all",
            "{\"type\":\"summary\",\"summary\":\"s\",\"leafUuid\":\"u2\"}",
            entry("user", "u1", null, "2026-01-05T10:00:00.000Z"),
            "",
            entry("assistant", "a1", "u1", "2026-01-05T10:00:05.000Z"),
            "{\"type\":\"file-history-snapshot\",\"messageId\":\"m1\"}");

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogs(file);

        assertEquals(1, logs.size());
        assertEquals(List.of("u1", "a1"), uuids(logs.getFirst()));
    }

    @Test
    void terminalAttachmentRefinesLeafAndIsAppendedAsTrailing() throws IOException {
        // att1 is terminal but not user/assistant — the leaf is its nearest
        // user/assistant ancestor (a1), and att1 rides along as a trailing child.
        Path file = writeTranscript(
            entry("user", "u1", null, "2026-01-05T10:00:00.000Z"),
            entry("assistant", "a1", "u1", "2026-01-05T10:00:05.000Z"),
            entry("attachment", "att2", "a1", "2026-01-05T10:00:07.000Z"),
            entry("attachment", "att1", "a1", "2026-01-05T10:00:06.000Z"));

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogs(file);

        assertEquals(1, logs.size());
        // Trailing children sorted by timestamp ascending
        assertEquals(List.of("u1", "a1", "att1", "att2"), uuids(logs.getFirst()));
    }

    @Test
    void sessionIdFallsBackToFirstMessageWhenLeafHasNone() throws IOException {
        Path file = tmp.resolve(SESSION + ".jsonl");
        Files.writeString(file,
            entry("user", "u1", null, "2026-01-05T10:00:00.000Z") + "\n"
            // Leaf without a sessionId field
            + "{\"type\":\"assistant\",\"uuid\":\"a1\",\"parentUuid\":\"u1\","
            + "\"timestamp\":\"2026-01-05T10:00:05.000Z\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}\n");

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogs(file);

        assertEquals(1, logs.size());
        assertEquals(SESSION, logs.getFirst().sessionId());
    }

    @Test
    void legacyProgressEntriesAreBridgedOutOfTheChain() throws IOException {
        // u1 → progress p1 → progress p2 → a1: a1 must reparent onto u1.
        Path file = writeTranscript(
            entry("user", "u1", null, "2026-01-05T10:00:00.000Z"),
            entry("progress", "p1", "u1", "2026-01-05T10:00:01.000Z"),
            entry("progress", "p2", "p1", "2026-01-05T10:00:02.000Z"),
            entry("assistant", "a1", "p2", "2026-01-05T10:00:05.000Z"));

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogs(file);

        assertEquals(1, logs.size());
        assertEquals(List.of("u1", "a1"), uuids(logs.getFirst()));
    }

    @Test
    void exactEntryPointProjectsLeafSummaryPromptAndProjectOverride() throws IOException {
        Path file = writeTranscript(
            userEntry("u1", null, "2026-01-05T10:00:00.000Z",
                "<command-name>/model</command-name><command-args>sonnet</command-args>"),
            entry("assistant", "a1", "u1", "2026-01-05T10:00:05.000Z"),
            userEntry("u2", "a1", "2026-01-05T10:01:00.000Z",
                "<command-name>/review</command-name><command-args>the pr</command-args>"),
            "{\"type\":\"summary\",\"leafUuid\":\"u2\",\"summary\":\"Review session\"}");

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogsFromSessionFile(
            file, "/override/project", Predicate.isEqual("model"));

        assertEquals(1, logs.size());
        SessionLog log = logs.getFirst();
        assertEquals("u2", log.leafUuid());
        assertEquals("/review the pr", log.firstPrompt());
        assertEquals("Review session", log.summary());
        assertEquals("/override/project", log.projectPath());
        SessionMeta meta = SessionMetaExtractor.toSessionMeta(log);
        assertEquals("Review session", meta.summary());
        assertEquals("/review the pr", meta.firstPrompt());
        assertEquals("/override/project", meta.projectPath());
    }

    @Test
    void summariesStayAttachedToTheirExactForkLeaves() throws IOException {
        Path file = writeTranscript(
            userEntry("u1", null, "2026-01-05T10:00:00.000Z", "root"),
            entry("assistant", "a1", "u1", "2026-01-05T10:00:05.000Z"),
            userEntry("left", "a1", "2026-01-05T10:01:00.000Z", "left"),
            userEntry("right", "a1", "2026-01-05T10:02:00.000Z", "right"),
            "{\"type\":\"summary\",\"leafUuid\":\"left\",\"summary\":\"Left summary\"}",
            "{\"type\":\"summary\",\"leafUuid\":\"right\",\"summary\":\"Right summary\"}");

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogsFromSessionFile(
            file, null, _ -> false);

        assertEquals(List.of("left", "right"),
            logs.stream().map(SessionLog::leafUuid).toList());
        assertEquals(List.of("Left summary", "Right summary"),
            logs.stream().map(SessionLog::summary).toList());
    }

    @Test
    void preservedSegmentRelinksPrunesOldHistoryAndClearsStaleUsage() throws IOException {
        Path file = writeTranscript(
            userEntry("old", null, "2026-01-05T09:00:00.000Z", "old history"),
            assistantEntry("head", "old", "2026-01-05T09:01:00.000Z", "preserved", "[]")
                .replace("\"content\":[]", "\"usage\":{\"input_tokens\":190000,"
                    + "\"output_tokens\":20,\"cache_creation_input_tokens\":30,"
                    + "\"cache_read_input_tokens\":40},\"content\":[]"),
            userEntry("tail", "head", "2026-01-05T09:02:00.000Z", "preserved tail"),
            "{\"type\":\"system\",\"subtype\":\"compact_boundary\",\"uuid\":\"boundary\","
                + "\"parentUuid\":null,\"sessionId\":\"" + SESSION + "\","
                + "\"timestamp\":\"2026-01-05T10:00:00.000Z\",\"compactMetadata\":{"
                + "\"preservedSegment\":{\"headUuid\":\"head\",\"anchorUuid\":\"boundary\","
                + "\"tailUuid\":\"tail\"}}}",
            userEntry("after", "boundary", "2026-01-05T10:01:00.000Z", "after compact"));

        SessionLog log = TranscriptLogLoader.loadAllLogsFromSessionFile(
            file, null, _ -> false).stream()
            .filter(candidate -> "after".equals(candidate.leafUuid()))
            .findFirst().orElseThrow();

        assertEquals(List.of("boundary", "head", "tail", "after"), uuids(log));
        JsonNode usage = log.messages().get(1).path("message").path("usage");
        assertEquals(0, usage.path("input_tokens").asInt());
        assertEquals(0, usage.path("output_tokens").asInt());
        assertEquals(0, usage.path("cache_creation_input_tokens").asInt());
        assertEquals(0, usage.path("cache_read_input_tokens").asInt());
    }

    @Test
    void stalePreservedSegmentPrunesAtTheNewestBoundaryWithoutRelinking() throws IOException {
        Path file = writeTranscript(
            userEntry("old", null, "2026-01-05T09:00:00.000Z", "old"),
            userEntry("head", "old", "2026-01-05T09:01:00.000Z", "head"),
            "{\"type\":\"system\",\"subtype\":\"compact_boundary\",\"uuid\":\"seg-boundary\","
                + "\"parentUuid\":null,\"sessionId\":\"" + SESSION + "\","
                + "\"timestamp\":\"2026-01-05T09:02:00.000Z\",\"compactMetadata\":{"
                + "\"preservedSegment\":{\"headUuid\":\"head\",\"anchorUuid\":\"seg-boundary\","
                + "\"tailUuid\":\"head\"}}}",
            "{\"type\":\"system\",\"subtype\":\"compact_boundary\",\"uuid\":\"latest-boundary\","
                + "\"parentUuid\":null,\"sessionId\":\"" + SESSION + "\","
                + "\"timestamp\":\"2026-01-05T10:00:00.000Z\"}",
            userEntry("after", "latest-boundary", "2026-01-05T10:01:00.000Z", "after"));

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogsFromSessionFile(
            file, null, _ -> false);

        assertEquals(1, logs.size());
        assertEquals(List.of("latest-boundary", "after"), uuids(logs.getFirst()));
    }

    @Test
    void snipMetadataDeletesMiddleRangeAndRelinksSurvivors() throws IOException {
        Path file = writeTranscript(
            userEntry("u1", null, "2026-01-05T10:00:00.000Z", "root"),
            entry("assistant", "a1", "u1", "2026-01-05T10:00:05.000Z"),
            userEntry("u2", "a1", "2026-01-05T10:00:10.000Z", "remove"),
            entry("assistant", "a2", "u2", "2026-01-05T10:00:15.000Z"),
            "{\"type\":\"system\",\"uuid\":\"snip\",\"parentUuid\":\"a2\","
                + "\"sessionId\":\"" + SESSION + "\",\"timestamp\":\"2026-01-05T10:00:20.000Z\","
                + "\"snipMetadata\":{\"removedUuids\":[\"a1\",\"u2\"]}}",
            userEntry("u3", "snip", "2026-01-05T10:00:25.000Z", "after"));

        SessionLog log = TranscriptLogLoader.loadAllLogsFromSessionFile(
            file, null, _ -> false).getFirst();

        assertEquals(List.of("u1", "a2", "snip", "u3"), uuids(log));
    }

    @Test
    void parallelAssistantBlocksAndToolResultsAreRecoveredIntoTheLeafChain() throws IOException {
        Path file = writeTranscript(
            userEntry("u1", null, "2026-01-05T10:00:00.000Z", "parallel tools"),
            assistantEntry("asst-a", "u1", "2026-01-05T10:00:01.000Z", "msg-1",
                "[{\"type\":\"tool_use\",\"id\":\"tool-a\",\"name\":\"Read\",\"input\":{}}]"),
            assistantEntry("asst-b", "asst-a", "2026-01-05T10:00:02.000Z", "msg-1",
                "[{\"type\":\"tool_use\",\"id\":\"tool-b\",\"name\":\"Read\",\"input\":{}}]"),
            toolResultEntry("result-a", "asst-a", "2026-01-05T10:00:03.000Z", "tool-a"),
            toolResultEntry("result-b", "asst-b", "2026-01-05T10:00:04.000Z", "tool-b"),
            userEntry("next", "result-a", "2026-01-05T10:00:05.000Z", "continue"));

        SessionLog next = TranscriptLogLoader.loadAllLogsFromSessionFile(
            file, null, _ -> false).stream()
            .filter(log -> "next".equals(log.leafUuid()))
            .findFirst().orElseThrow();

        assertEquals(List.of(
            "u1", "asst-a", "asst-b", "result-b", "result-a", "next"),
            uuids(next));
    }

    @Test
    void completeFileScanKeepsRootsOutsideTheLegacyHundredMibTailWindow() throws IOException {
        Path file = tmp.resolve(SESSION + ".jsonl");
        String padding = "{\"ignored\":\"" + "x".repeat(1024 * 1024) + "\"}";
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(userEntry("root", null, "2026-01-05T10:00:00.000Z", "root prompt"));
            writer.newLine();
            for (int i = 0; i < 101; i++) {
                writer.write(padding);
                writer.newLine();
            }
            writer.write(entry("assistant", "leaf", "root", "2026-01-05T10:01:00.000Z"));
            writer.newLine();
        }

        SessionLog log = TranscriptLogLoader.loadAllLogsFromSessionFile(
            file, null, _ -> false).getFirst();

        assertTrue(Files.size(file) > 100L * 1024 * 1024);
        assertEquals(List.of("root", "leaf"), uuids(log));
        assertEquals("root prompt", log.firstPrompt());
    }

    @Test
    void parentCycleReturnsAFinitePartialBranchAndKeepsTerminalAttachments() throws IOException {
        Path file = writeTranscript(
            entry("user", "u1", "a1", "2026-01-05T10:00:00.000Z"),
            entry("assistant", "a1", "u1", "2026-01-05T10:00:05.000Z"),
            entry("attachment", "tail", "u1", "2026-01-05T10:00:06.000Z"));

        List<SessionLog> logs = TranscriptLogLoader.loadAllLogsFromSessionFile(
            file, null, _ -> false);

        assertEquals(1, logs.size());
// The cycle walk returns each UUID at most once, then appends the terminal
// auxiliary child selected by the shared.
        assertEquals(List.of("a1", "u1", "tail"), uuids(logs.getFirst()));
    }

    @Test
    void missingOrEmptyFileYieldsEmptyList() throws IOException {
        assertTrue(TranscriptLogLoader.loadAllLogs(tmp.resolve("nope.jsonl")).isEmpty());

        Path empty = tmp.resolve("empty.jsonl");
        Files.writeString(empty, "");
        assertTrue(TranscriptLogLoader.loadAllLogs(empty).isEmpty());
    }
}
