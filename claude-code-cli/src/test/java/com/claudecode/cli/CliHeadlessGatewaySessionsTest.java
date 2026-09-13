package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.*;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.query.DefaultQuerySessionFactory;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import org.assertj.core.api.Assertions;
import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the gateway headless-session supervisor over the real gateway
 * port types: open/close lifecycle, project-path validation, the cross-project
 * permission gate, and the TaskRegistry registration the TUI pill reads.
 */
class CliHeadlessGatewaySessionsTest {

    @TempDir
    Path mainProject;
    @TempDir
    Path otherProject;

    @BeforeEach
    void isolateGlobalRegistry() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
    }

    @AfterEach
    void restoreGlobalRegistry() {
        TaskRegistry.resetGlobalForTest();
    }

    /** A minimal streaming client: the assembled engine never submits a turn here. */
    private static final class EmptyStreamingClient implements StreamingClient {
        @Override
        public Iterator<StreamingClient.StreamingEvent> createStream(
                StreamingClient.StreamRequest request) {
            return new Iterator<>() {
                @Override public boolean hasNext() { return false; }
                @Override public StreamingClient.StreamingEvent next() {
                    throw new NoSuchElementException();
                }
            };
        }

        @Override
        public String getModel() {
            return "claude-sonnet-5";
        }
    }

    private CliHeadlessGatewaySessions sessions() {
        CliHeadlessSessionFactory factory = new CliHeadlessSessionFactory(
            new EmptyStreamingClient(), new ToolRegistry(), new DefaultQuerySessionFactory(),
            new PermissionGate(
                ToolPermissionContext.of(mainProject)),
            "claude-sonnet-5", mainProject.toString(), null);
        return new CliHeadlessGatewaySessions(factory, mainProject.toString());
    }

    @Test
    void openRegistersWebSessionTaskAndCloseCompletesIt() {
        CliHeadlessGatewaySessions sessions = sessions();
        GatewayHeadlessSessions.Opened opened = sessions.open(
            new GatewayHeadlessSessions.OpenRequest("ws-1", mainProject.toString()));

        assertThat(opened.session().info().id()).isEqualTo("ws-1");
        assertThat(opened.projectPath()).isEqualTo(mainProject.toString());
        assertThat(sessions.find("ws-1")).isPresent();

        var tasks = TaskRegistry.global().store().list().stream()
            .filter(task -> task.type() == TaskType.WEB_SESSION)
            .toList();
        assertThat(tasks).hasSize(1);
        String taskId = tasks.getFirst().id();
        assertThat(sessions.transcriptPathForTask(taskId)).isNotNull();
        assertThat(sessions.isWebSessionTask(taskId)).isTrue();

        assertThat(sessions.close("ws-1")).isTrue();
        assertThat(sessions.find("ws-1")).isEmpty();
        assertThat(sessions.isWebSessionTask(taskId)).isFalse();
        assertThat(TaskRegistry.global().store().get(taskId))
            .hasValueSatisfying(task -> assertThat(task.status())
                .isEqualTo(TaskStatus.COMPLETED));
        assertThat(sessions.close("ws-1")).isFalse();
    }

    @Test
    void listShowsOpenSessionsWithTheirProjects() {
        CliHeadlessGatewaySessions sessions = sessions();
        assertThat(sessions.list()).isEmpty();
        sessions.open(new GatewayHeadlessSessions.OpenRequest("ws-1", mainProject.toString()));
        sessions.open(new GatewayHeadlessSessions.OpenRequest("ws-2", mainProject.toString()));

        var listing = sessions.list();
        assertThat(listing).hasSize(2);
        assertThat(listing.getFirst().sessionId()).isEqualTo("ws-1");
        assertThat(listing.getFirst().projectPath()).isEqualTo(mainProject.toString());

        sessions.close("ws-1");
        assertThat(sessions.list()).singleElement()
            .satisfies(entry -> assertThat(entry.sessionId()).isEqualTo("ws-2"));
    }

    @Test
    void openWithoutSessionIdMintsOne() {
        CliHeadlessGatewaySessions sessions = sessions();
        GatewayHeadlessSessions.Opened opened = sessions.open(
            new GatewayHeadlessSessions.OpenRequest(null, mainProject.toString()));
        assertThat(opened.session().info().id()).isNotBlank();
    }

    @Test
    void openSameIdTwiceReturnsSameSession() {
        CliHeadlessGatewaySessions sessions = sessions();
        GatewayHeadlessSessions.Opened first = sessions.open(
            new GatewayHeadlessSessions.OpenRequest("ws-dup", mainProject.toString()));
        GatewayHeadlessSessions.Opened second = sessions.open(
            new GatewayHeadlessSessions.OpenRequest("ws-dup", mainProject.toString()));
        assertThat(second.session()).isSameAs(first.session());
        assertThat(TaskRegistry.global().store().list().stream()
            .filter(task -> task.type() == TaskType.WEB_SESSION))
            .hasSize(1);
    }

    @Test
    void openWithPriorDiskTranscriptResumesMessageLevel() throws IOException {
        // Persist a conversation under the session id first.
        SessionManager manager =
            new SessionManager(mainProject.toString());
        Path transcript = manager.getSessionFile("ws-resume");
        Files.createDirectories(transcript.getParent());
        List<String> lines = new ArrayList<>();
        UserMessage user =
            new UserMessage("u-1",
                new MessageContent("prior prompt", null));
        AssistantMessage assistant =
            new AssistantMessage("a-1",
                new AssistantContent(
                    null,
                    List.of(new TextBlock("prior answer")),
                    null));
        lines.add(JsonUtils.getMapper()
            .writeValueAsString(user));
        lines.add(JsonUtils.getMapper()
            .writeValueAsString(assistant));
        Files.write(transcript, lines,
            StandardCharsets.UTF_8);

        CliHeadlessGatewaySessions sessions = sessions();
        // No explicit project_path: the resume path reads the disk transcript.
        GatewayHeadlessSessions.Opened opened =
            sessions.open(new GatewayHeadlessSessions.OpenRequest("ws-resume", null));
        assertThat(opened.resumed()).isTrue();
    }

    @Test
    void openWithoutDiskTranscriptIsFresh() {
        CliHeadlessGatewaySessions sessions = sessions();
        GatewayHeadlessSessions.Opened opened =
            sessions.open(new GatewayHeadlessSessions.OpenRequest("ws-fresh", null));
        assertThat(opened.resumed()).isFalse();
    }

    @Test
    void openedHistorySessionRestoresDurableMetrics() throws IOException {
        // One persisted turn: a user row (promptSource + uuid — the turn id
        // readMetricTurnIds requires) followed by a contiguous
        // java-session-metrics event stream. Opening the history session
        // restores the fold into the live engine: the same rows the TUI
        // /resume path consumes.
        String sessionId = "ws-metrics";
        SessionManager manager = new SessionManager(mainProject.toString());
        Path transcript = manager.getSessionFile(sessionId);
        Files.createDirectories(transcript.getParent());
        List<String> lines = new ArrayList<>();
        UserMessage user = new UserMessage("turn-1",
            new MessageContent("prior prompt", null));
        ObjectNode userRow = (ObjectNode) JsonUtils.getMapper()
            .readTree(JsonUtils.getMapper().writeValueAsString(user));
        userRow.put("promptSource", "typed");
        lines.add(JsonUtils.getMapper().writeValueAsString(userRow));
        lines.add(metricRow(sessionId, 0, "session/start", null, 0, 0));
        lines.add(metricRow(sessionId, 1, "turn/start", "turn-1", 1, 0));
        lines.add(metricRow(sessionId, 2, "step/start", "turn-1", 1, 1));
        String usage = "uncachedInputTokens:100,outputTokens:50,"
            + "cacheWriteTokens:10,cacheReadTokens:900";
        lines.add(metricRow(sessionId, 3, "assistant/usage", "turn-1", 1, 1, usage));
        lines.add(metricRow(sessionId, 4, "assistant/message", "turn-1", 1, 1));
        lines.add(metricRow(sessionId, 5, "step/end", "turn-1", 1, 1));
        lines.add(metricRow(sessionId, 6, "turn/end", "turn-1", 1, 1));
        Files.write(transcript, lines, StandardCharsets.UTF_8);

        CliHeadlessGatewaySessions sessions = sessions();
        sessions.open(new GatewayHeadlessSessions.OpenRequest(sessionId, null));

        SessionMetricsSnapshot metrics = sessions.liveEngine(sessionId)
            .orElseThrow()
            .execution()
            .getSessionMetrics();
        assertThat(metrics.complete()).isTrue();
        assertThat(metrics.turns()).isEqualTo(1);
        assertThat(metrics.steps()).isEqualTo(1);
        assertThat(metrics.uncachedInputTokens()).isEqualTo(100);
        assertThat(metrics.outputTokens()).isEqualTo(50);
        assertThat(metrics.cacheWriteTokens()).isEqualTo(10);
        assertThat(metrics.cacheReadTokens()).isEqualTo(900);
    }

    @Test
    void openedHistorySessionWithDiscontinuousMetricsStaysIncomplete()
            throws IOException {
        // A seq gap poisons the restore: the fold must stay INCOMPLETE so
        // the gateway serves null instead of a partial total (spec §6).
        String sessionId = "ws-gap";
        SessionManager manager = new SessionManager(mainProject.toString());
        Path transcript = manager.getSessionFile(sessionId);
        Files.createDirectories(transcript.getParent());
        List<String> lines = new ArrayList<>();
        UserMessage user = new UserMessage("turn-1",
            new MessageContent("prior prompt", null));
        ObjectNode userRow = (ObjectNode) JsonUtils.getMapper()
            .readTree(JsonUtils.getMapper().writeValueAsString(user));
        userRow.put("promptSource", "typed");
        lines.add(JsonUtils.getMapper().writeValueAsString(userRow));
        lines.add(metricRow(sessionId, 0, "session/start", null, 0, 0));
        lines.add(metricRow(sessionId, 1, "turn/start", "turn-1", 1, 0));
        lines.add(metricRow(sessionId, 2, "step/start", "turn-1", 1, 1));
        // seq 3 missing: the restore rejects the stream wholesale.
        lines.add(metricRow(sessionId, 4, "step/end", "turn-1", 1, 1));
        lines.add(metricRow(sessionId, 5, "turn/end", "turn-1", 1, 1));
        Files.write(transcript, lines, StandardCharsets.UTF_8);

        CliHeadlessGatewaySessions sessions = sessions();
        sessions.open(new GatewayHeadlessSessions.OpenRequest(sessionId, null));

        SessionMetricsSnapshot metrics = sessions.liveEngine(sessionId)
            .orElseThrow()
            .execution()
            .getSessionMetrics();
        assertThat(metrics.complete()).isFalse();
    }

    /**
     * One persisted {@code java-session-metrics} row in the transcript
     * writer's exact wire shape, optionally carrying usage buckets.
     */
    private static String metricRow(String sessionId, long seq, String event,
            String turnId, long turn, long step) {
        return metricRow(sessionId, seq, event, turnId, turn, step, null);
    }

    private static String metricRow(String sessionId, long seq, String event,
            String turnId, long turn, long step, String usageFields) {
        ObjectNode row = JsonUtils.getMapper().createObjectNode();
        row.put("type", "java-session-metrics");
        row.put("schemaVersion", 1);
        row.put("seq", seq);
        row.put("time", 1_700_000_000_000L + seq * 100);
        row.put("sessionId", sessionId);
        row.put("event", event);
        if (turnId != null) row.put("turnId", turnId);
        if (turn > 0) row.put("turn", turn);
        if (step > 0) row.put("step", step);
        if (usageFields != null) {
            for (String pair : usageFields.split(",")) {
                int split = pair.indexOf(':');
                row.put(pair.substring(0, split).strip(),
                    Long.parseLong(pair.substring(split + 1).strip()));
            }
        }
        return JsonUtils.getMapper().valueToTree(row).toString();
    }

    @Test
    void openRejectsMissingProjectDirectory() {
        CliHeadlessGatewaySessions sessions = sessions();
        String missing = otherProject.resolve("gone").toString();
        Assertions.assertThatThrownBy(() ->
            sessions.open(new GatewayHeadlessSessions.OpenRequest("ws-x", missing)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("project_path");
    }

    @Test
    void openFreshSessionPersistsExactlyOneSessionStartRow() throws IOException {
        // The recorder flushes transcript rows asynchronously, so a restore
        // that reads after the sink install races its own session/start row:
        // an unflushed queue makes the read miss the row and the empty-events
        // restore branch emits a second session/start with a reset seq,
        // permanently breaking the strict-sequence restore. A fresh open must
        // persist exactly one session/start row and keep the live fold
        // complete, so a later reopen restores the fold from a clean stream.
        String sessionId = "ws-fresh-single-start";
        CliHeadlessGatewaySessions sessions = sessions();
        sessions.open(new GatewayHeadlessSessions.OpenRequest(sessionId, null));

        SessionMetricsSnapshot metrics = sessions.liveEngine(sessionId)
            .orElseThrow()
            .execution()
            .getSessionMetrics();
        assertThat(metrics.complete()).isTrue();

        Path transcript = new SessionManager(mainProject.toString())
            .getSessionFile(sessionId);
        // The recorder's write queue is asynchronous; wait until the row lands.
        List<String> rows = awaitRows(transcript, 1);
        assertThat(rows).isNotEmpty();
        List<String> starts = rows.stream()
            .filter(line -> Strings.CS.contains(line, "\"event\":\"session/start\""))
            .toList();
        assertThat(starts).hasSize(1);
        // The whole stream stays strictly sequential: one seq-0 row only.
        List<Long> seqs = new ArrayList<>();
        for (String line : rows) {
            seqs.add(JsonUtils.getMapper()
                .readTree(line).path("seq").asLong());
        }
        assertThat(seqs).containsExactly(0L);
    }

    /** Reads the transcript's custom metric rows, waiting for at least {@code min} to flush. */
    private static List<String> awaitRows(Path transcript, int min) throws IOException {
        List<String> rows = List.of();
        for (int i = 0; i < 200; i++) {
            if (Files.isRegularFile(transcript)) {
                rows = Files.readAllLines(transcript, StandardCharsets.UTF_8)
                    .stream()
                    .filter(line -> Strings.CS.contains(line, "\"java-session-metrics\""))
                    .toList();
                if (rows.size() >= min) return rows;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for transcript rows", e);
            }
        }
        return rows;
    }

    @Test
    void crossProjectSessionGetsItsOwnGate() throws IOException {
        CliHeadlessSessionFactory factory = new CliHeadlessSessionFactory(
            new EmptyStreamingClient(), new ToolRegistry(),
            new DefaultQuerySessionFactory(),
            new PermissionGate(
                ToolPermissionContext.of(mainProject)),
            "m", mainProject.toString(), null);
        PermissionGate crossGate =
            factory.gateFor(otherProject.toString());
        // toRealPath resolves the macOS /var → /private/var symlink divergence.
        assertThat(crossGate.currentContext().workingDirectory().toRealPath())
            .isEqualTo(otherProject.toRealPath());
    }
}
