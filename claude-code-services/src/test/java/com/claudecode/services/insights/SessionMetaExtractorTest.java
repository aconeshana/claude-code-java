package com.claudecode.services.insights;


import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** logToSessionMeta / extractToolStats / deduplicateSessionBranches semantics. */
class SessionMetaExtractorTest {

    private static final String SESSION = "11111111-2222-3333-4444-555555555555";

    private static JsonNode json(String s) {
        try {
            return JsonUtils.getMapper().readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode userText(String ts, String text) {
        return json("{\"type\":\"user\",\"timestamp\":\"" + ts + "\",\"cwd\":\"/proj\","
            + "\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}}");
    }

    private static JsonNode assistant(String ts, String messageJson) {
        return json("{\"type\":\"assistant\",\"timestamp\":\"" + ts + "\","
            + "\"message\":" + messageJson + "}");
    }

    // ── logToSessionMeta ─────────────────────────────────────────────────────

    @Test
    void countsDurationStartTimeAndFirstPrompt() {
        SessionLog log = new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z", "hello world"),
            assistant("2026-01-05T10:01:00.000Z",
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                    + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50}}"),
            // tool_result-only user message — not a human message
            json("{\"type\":\"user\",\"timestamp\":\"2026-01-05T10:02:00.000Z\","
                + "\"message\":{\"role\":\"user\",\"content\":"
                + "[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"ok\"}]}}"),
            userText("2026-01-05T10:30:00.000Z", "again")));

        assertTrue(SessionMetaExtractor.hasValidDates(log));
        SessionMeta meta = SessionMetaExtractor.toSessionMeta(log);

        assertEquals(SESSION, meta.sessionId());
        assertEquals("/proj", meta.projectPath());
        assertEquals("2026-01-05T10:00:00.000Z", meta.startTime());
        assertEquals(30.0, meta.durationMinutes());
        assertEquals(2, meta.userMessageCount());
        assertEquals(1, meta.assistantMessageCount());
        assertEquals(100, meta.inputTokens());
        assertEquals(50, meta.outputTokens());
        assertEquals("hello world", meta.firstPrompt());
    }

    @Test
    void exactLogProjectionWinsOverFallbackPromptProjectAndSummary() {
        SessionLog log = new SessionLog(
            SESSION,
            List.of(
                userText("2026-01-05T10:00:00.000Z", "fallback prompt"),
                assistant("2026-01-05T10:01:00.000Z",
                    "{\"role\":\"assistant\",\"content\":[]}")),
            null,
            "projected prompt",
            "projected summary",
            "/projected/path");

        SessionMeta meta = SessionMetaExtractor.toSessionMeta(log);

        assertEquals("projected prompt", meta.firstPrompt());
        assertEquals("projected summary", meta.summary());
        assertEquals("/projected/path", meta.projectPath());
    }

    @Test
    void toolStatsCoverToolsLanguagesLinesGitAndFlags() {
        SessionLog log = new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z", "do stuff"),
            assistant("2026-01-05T10:00:10.000Z", "{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"tool_use\",\"name\":\"Edit\",\"input\":{"
                +   "\"file_path\":\"/x/Foo.java\",\"old_string\":\"a\\nb\",\"new_string\":\"a\\nc\\nd\"}},"
                + "{\"type\":\"tool_use\",\"name\":\"Write\",\"input\":{"
                +   "\"file_path\":\"/x/y.py\",\"content\":\"l1\\nl2\\nl3\"}},"
                + "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{"
                +   "\"command\":\"git commit -m x && git push\"}},"
                + "{\"type\":\"tool_use\",\"name\":\"Task\",\"input\":{}},"
                + "{\"type\":\"tool_use\",\"name\":\"mcp__srv__tool\",\"input\":{}},"
                + "{\"type\":\"tool_use\",\"name\":\"WebSearch\",\"input\":{}},"
                + "{\"type\":\"tool_use\",\"name\":\"WebFetch\",\"input\":{}}]}")));

        SessionMeta meta = SessionMetaExtractor.toSessionMeta(log);

        assertEquals(1L, meta.toolCounts().get("Edit"));
        assertEquals(1L, meta.toolCounts().get("Bash"));
        assertEquals(Map.of("Java", 1L, "Python", 1L), meta.languages());
        assertEquals(2, meta.filesModified());
        // Edit: [a,b] → [a,c,d] = +2/-1; Write: 3 lines
        assertEquals(5, meta.linesAdded());
        assertEquals(1, meta.linesRemoved());
        assertEquals(1, meta.gitCommits());
        assertEquals(1, meta.gitPushes());
        assertTrue(meta.usesTaskAgent());
        assertTrue(meta.usesMcp());
        assertTrue(meta.usesWebSearch());
        assertTrue(meta.usesWebFetch());
    }

    @Test
    void toolErrorsAreCountedAndCategorized() {
        SessionLog log = new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z", "hi"),
            json("{\"type\":\"user\",\"timestamp\":\"2026-01-05T10:00:30.000Z\","
                + "\"message\":{\"role\":\"user\",\"content\":["
                + "{\"type\":\"tool_result\",\"is_error\":true,"
                +   "\"content\":\"Command failed with exit code 1\"},"
                + "{\"type\":\"tool_result\",\"is_error\":true,"
                +   "\"content\":\"File has been modified since read\"},"
                + "{\"type\":\"tool_result\",\"is_error\":true,"
                +   "\"content\":\"something inexplicable\"},"
                + "{\"type\":\"tool_result\",\"content\":\"fine\"}]}}")));

        SessionMeta meta = SessionMetaExtractor.toSessionMeta(log);

        assertEquals(3, meta.toolErrors());
        assertEquals(1L, meta.toolErrorCategories().get("Command Failed"));
        assertEquals(1L, meta.toolErrorCategories().get("File Changed"));
        assertEquals(1L, meta.toolErrorCategories().get("Other"));
    }

    @Test
    void responseTimesOnlyCountGapsBetween2sAnd1h() {
        SessionLog log = new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z", "hi"),
            assistant("2026-01-05T10:00:05.000Z",
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}"),
            userText("2026-01-05T10:00:06.000Z", "too fast"),      // 1s gap — skipped
            userText("2026-01-05T10:00:15.000Z", "real reply")));  // 10s gap — counted

        SessionMeta meta = SessionMetaExtractor.toSessionMeta(log);

        assertEquals(List.of(10.0), meta.userResponseTimes());
    }

    @Test
    void interruptionsDetectedInStringAndArrayContent() {
        SessionLog log = new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z", "start"),
            userText("2026-01-05T10:01:00.000Z", "[Request interrupted by user]"),
            json("{\"type\":\"user\",\"timestamp\":\"2026-01-05T10:02:00.000Z\","
                + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"[Request interrupted by user for tool use]\"}]}}")));

        SessionMeta meta = SessionMetaExtractor.toSessionMeta(log);

        assertEquals(2, meta.userInterruptions());
    }

    @Test
    void messageHoursUseLocalTimezoneAndTimestampsAreRaw() {
        String ts = "2026-01-05T23:30:00.000Z";
        SessionLog log = new SessionLog(SESSION, List.of(userText(ts, "late night")));

        SessionMeta meta = SessionMetaExtractor.toSessionMeta(log);

        int expectedHour = ZonedDateTime
            .ofInstant(Instant.parse(ts), ZoneId.systemDefault()).getHour();
        assertEquals(List.of(expectedHour), meta.messageHours());
        assertEquals(List.of(ts), meta.userMessageTimestamps());
    }

    @Test
    void firstPromptFlattensNewlinesAndTruncatesAt200Chars() {
        String prompt = ("line1\\nline2 " + "x".repeat(300)).trim();
        SessionLog log = new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z", prompt)));

        SessionMeta meta = SessionMetaExtractor.toSessionMeta(log);

        assertEquals(201, meta.firstPrompt().length());
        assertTrue(Strings.CS.endsWith(meta.firstPrompt(), "…"));
        assertTrue(Strings.CS.startsWith(meta.firstPrompt(), "line1 line2"));
        assertFalse(Strings.CS.contains(meta.firstPrompt(), "\n"));
    }

    @Test
    void firstPromptSkipsXmlMetadataCommandsWithoutArgsAndFormatsBashInput() {
        // Pure IDE metadata → skipped; the second message is the real prompt.
        SessionLog xmlLog = new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z",
                "<ide_opened_file>x.ts</ide_opened_file>"),
            userText("2026-01-05T10:00:01.000Z", "real prompt")));
        assertEquals("real prompt",
            SessionMetaExtractor.toSessionMeta(xmlLog).firstPrompt());

        // Slash command with args → "/cmd args"; without args → skipped.
        SessionLog cmdLog = new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z",
                "<command-name>/noargs</command-name>"),
            userText("2026-01-05T10:00:01.000Z",
                "<command-name>/review</command-name><command-args>the pr</command-args>")));
        assertEquals("/review the pr",
            SessionMetaExtractor.toSessionMeta(cmdLog).firstPrompt());

        // Bash input → "! cmd"
        SessionLog bashLog = new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z",
                "<bash-input>ls -la</bash-input>")));
        assertEquals("! ls -la",
            SessionMetaExtractor.toSessionMeta(bashLog).firstPrompt());

        // Nothing meaningful at all
        SessionLog emptyLog = new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z",
                "<ide_selection>sel</ide_selection>")));
        assertEquals("No prompt",
            SessionMetaExtractor.toSessionMeta(emptyLog).firstPrompt());
    }

    @Test
    void hasValidDatesRequiresParseableFirstAndLeafTimestamps() {
        assertFalse(SessionMetaExtractor.hasValidDates(
            new SessionLog(SESSION, List.of())));
        assertFalse(SessionMetaExtractor.hasValidDates(new SessionLog(SESSION, List.of(
            json("{\"type\":\"user\",\"message\":{\"content\":\"no timestamp\"}}")))));
        assertFalse(SessionMetaExtractor.hasValidDates(new SessionLog(SESSION, List.of(
            userText("not-a-date", "hi")))));
        assertTrue(SessionMetaExtractor.hasValidDates(new SessionLog(SESSION, List.of(
            userText("2026-01-05T10:00:00.000Z", "hi")))));
    }

    // ── deduplicateBranches ──────────────────────────────────────────────────

    private static SessionMeta meta(String id, long userMessages, double duration) {
        return SessionMeta.builder(id, "", "2026-01-05T00:00:00.000Z")
            .durationMinutes(duration).userMessageCount(userMessages).build();
    }

    @Test
    void deduplicateKeepsMostUserMessagesThenLongestDuration() {
        SessionMeta s1Small = meta("s1", 3, 10);
        SessionMeta s1Big = meta("s1", 5, 1);
        SessionMeta s2Short = meta("s2", 2, 5);
        SessionMeta s2Long = meta("s2", 2, 9);
        SessionMeta s3 = meta("s3", 1, 1);

        List<SessionMeta> result = SessionMetaExtractor.deduplicateBranches(
            List.of(s1Small, s1Big, s2Short, s2Long, s3));

        assertEquals(List.of(s1Big, s2Long, s3), result);
    }
}
