package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.metrics.LlmRequestTraceRecord;
import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.metrics.TrajectoryHeaderCache;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TranscriptRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class GatewayTrajectoryHandlerTest {

    private static final String TOKEN = "t".repeat(48);

    @TempDir
    Path tempHome;

    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(30)).build();

    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override
            public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<SessionHostInfo> list() {
                return List.of();
            }
        });

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Test
    @Timeout(20)
    void servesFourProtocolTrajectoryParallelToolsAndSubagentDrillDown() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        String agentId = "sub888";
        Path workDir = tempHome.resolve("workspace");
        Files.createDirectories(workDir);

        SessionManager mgr = new SessionManager(tempHome, workDir.toString());
        TranscriptRecorder recorder = new TranscriptRecorder(mgr, new SessionStorage());
        recorder.recordPromptStart(sessionId, "user");

        // Store a 7-day System + Tools header snapshot
        TrajectoryHeaderCache headerCache = new TrajectoryHeaderCache(
            tempHome.resolve("cache").resolve("trajectory-headers"),
            Duration.ofDays(7),
            System::currentTimeMillis);
        String headerSha = headerCache.store(
            "You are a 4-protocol test agent.",
            List.of(
                new TrajectoryHeaderCache.ToolSpec("Read", "Read file",
                    JsonUtils.getMapper().createObjectNode().put("type", "object")),
                new TrajectoryHeaderCache.ToolSpec("Agent", "Spawn subagent",
                    JsonUtils.getMapper().createObjectNode().put("type", "object"))));

        // Turn 1: User prompt -> Gemini step with 2 parallel Read tool calls + 1 Agent call
        UserMessage u1 = new UserMessage(UUID.randomUUID().toString(),
            new MessageContent("Inspect A and B in parallel, then delegate", null));
        recorder.recordTranscript(sessionId, u1);

        AssistantMessage a1 = new AssistantMessage(
            UUID.randomUUID().toString(),
            AssistantContent.apiResponse(
                "msg_gem_1",
                List.of(
                    new ThinkingBlock("Planning parallel reads...", "sig_1"),
                    new ToolUseBlock("call_r1", "Read",
                        JsonUtils.getMapper().createObjectNode().put("file_path", "A.java")),
                    new ToolUseBlock("call_r2", "Read",
                        JsonUtils.getMapper().createObjectNode().put("file_path", "B.java")),
                    new ToolUseBlock("call_ag1", "Agent",
                        JsonUtils.getMapper().createObjectNode()
                            .put("subagent_type", "Explore")
                            .put("description", "Audit module"))),
                new Usage(300, 500, 0, 900, 1700L, 320L),
                "gemini-2.5-pro",
                "tool_use",
                null),
            false, null, Instant.ofEpochMilli(10_200L),
            null, null, null, null, null, null, null, null, null, null);
        recorder.recordTranscript(sessionId, a1);

        UserMessage trReads = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(
                new ToolResultBlock("call_r1", List.of(new TextBlock("class A {}")), false),
                new ToolResultBlock("call_r2", List.of(new TextBlock("class B {}")), false))),
            false, false, null, MessageOrigin.USER, null, Instant.ofEpochMilli(10_350L),
            null, null, null, null, null, null, null, null, null, null);
        recorder.recordTranscript(sessionId, trReads);

        UserMessage trAgent = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(
                new ToolResultBlock("call_ag1", List.of(new TextBlock("Subagent audit complete")), false))),
            false, false, Map.of("agentId", agentId), MessageOrigin.USER, null,
            Instant.ofEpochMilli(10_900L),
            null, null, null, null, null, null, null, null, null, null);
        recorder.recordTranscript(sessionId, trAgent);

        // Metrics with overlapping parallel intervals for call_r1 [10210..10340] and call_r2 [10215..10330],
        // followed by non-overlapping call_ag1 [10360..10890]
        long seq = 0;
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_000L, sessionId, SessionMetricsEvent.Kind.SESSION_START,
            null, 0, 0, null, 0, 0, 0, 0, false));
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_010L, sessionId, SessionMetricsEvent.Kind.TURN_START,
            u1.uuid(), 1, 0, null, 0, 0, 0, 0, false));
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_020L, sessionId, SessionMetricsEvent.Kind.STEP_START,
            u1.uuid(), 1, 1, null, 0, 0, 0, 0, false));
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_090L, sessionId, SessionMetricsEvent.Kind.ASSISTANT_FIRST_TOKEN,
            u1.uuid(), 1, 1, null, 0, 0, 0, 0, false));
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_200L, sessionId, SessionMetricsEvent.Kind.ASSISTANT_MESSAGE,
            u1.uuid(), 1, 1, null, 0, 0, 0, 0, false));
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_210L, sessionId, SessionMetricsEvent.Kind.TOOL_CALL,
            u1.uuid(), 1, 1, "call_r1", 0, 0, 0, 0, false));
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_215L, sessionId, SessionMetricsEvent.Kind.TOOL_CALL,
            u1.uuid(), 1, 1, "call_r2", 0, 0, 0, 0, false));
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_330L, sessionId, SessionMetricsEvent.Kind.TOOL_RESULT,
            u1.uuid(), 1, 1, "call_r2", 0, 0, 0, 0, false));
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_340L, sessionId, SessionMetricsEvent.Kind.TOOL_RESULT,
            u1.uuid(), 1, 1, "call_r1", 0, 0, 0, 0, false));
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_360L, sessionId, SessionMetricsEvent.Kind.TOOL_CALL,
            u1.uuid(), 1, 1, "call_ag1", 0, 0, 0, 0, false));
        recorder.recordSessionMetrics(sessionId, new SessionMetricsEvent(
            1, seq++, 10_890L, sessionId, SessionMetricsEvent.Kind.TOOL_RESULT,
            u1.uuid(), 1, 1, "call_ag1", 0, 0, 0, 0, false));

        recorder.recordLlmRequestTrace(sessionId, new LlmRequestTraceRecord(
            1, sessionId, 1, 1, "assistant", 10_020L, 10_200L,
            "gemini", "gemini", "gemini · generativelanguage.googleapis.com",
            "gemini-2.5-pro", "gemini-2.5-pro", headerSha,
            "{\"protocol\":\"gemini\",\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true}}}",
            null, 1, 3, 500L, "completed", null, null));
        assertThat(recorder.awaitPendingWrites(sessionId, 5_000)).isTrue();

        // Also write the subagent's sidechain transcript (`subagents/agent-sub888.jsonl`)
        Path subFile = mgr.getAgentTranscriptPath(sessionId, agentId);
        TranscriptRecorder subRecorder = new TranscriptRecorder(
            mgr, new SessionStorage(), workDir.toString(), true, agentId, subFile, "Explore");
        subRecorder.recordPromptStart(sessionId, "agent");
        subRecorder.recordTranscript(sessionId, new UserMessage(UUID.randomUUID().toString(),
            new MessageContent("Audit module", null)));
        subRecorder.recordTranscript(sessionId, new AssistantMessage(
            UUID.randomUUID().toString(),
            AssistantContent.apiResponse(
                "msg_sub_1",
                List.of(
                    new ToolUseBlock("sub_call_1", "Grep",
                        JsonUtils.getMapper().createObjectNode().put("pattern", "TODO"))),
                new Usage(120, 80, 0, 40, 240L, 30L),
                "glm-5.1",
                "tool_use",
                null),
            false, null, Instant.ofEpochMilli(10_500L),
            null, null, null, null, null, null, null, null, null, null));
        subRecorder.recordTranscript(sessionId, new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(
                new ToolResultBlock("sub_call_1", List.of(new TextBlock("A.java:1:// TODO")), false))),
            false, false, null, MessageOrigin.USER, null, Instant.ofEpochMilli(10_600L),
            null, null, null, null, null, null, null, null, null, null));
        subRecorder.recordLlmRequestTrace(sessionId, new LlmRequestTraceRecord(
            1, sessionId, 1, 1, "assistant", 10_400L, 10_500L,
            "chat", "chat", "chat · open.bigmodel.cn",
            "glm-5.1", "glm-5.1", null,
            "{\"protocol\":\"chat\",\"reasoning_effort\":\"high\"}",
            null, 0, 0, 0L, "completed", null, null));
        assertThat(subRecorder.awaitPendingWrites(sessionId, 5_000)).isTrue();

        server = GatewayServer.builder(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
            .webWorkers(new WebWorkerDirectory(tempHome.resolve("web-workers")))
            .build();
        server.start();

        // 1. Verify parent session trajectory
        Request mainReq = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + "/api/sessions/" + sessionId + "/trajectory")
            .header("Authorization", "Bearer " + TOKEN)
            .build();
        try (Response resp = client.newCall(mainReq).execute()) {
            assertThat(resp.code()).isEqualTo(200);
            JsonNode root = JsonUtils.getMapper().readTree(resp.body().string());
            assertThat(root.path("total_turns").asInt()).isEqualTo(1);

            JsonNode reqNum = root.path("request_numbers").get(0);
            assertThat(reqNum.path("provider").asText()).isEqualTo("gemini · generativelanguage.googleapis.com");
            assertThat(reqNum.path("model").asText()).isEqualTo("gemini-2.5-pro");
            assertThat(reqNum.path("retry").asInt()).isEqualTo(1);
            assertThat(reqNum.path("usage").path("output").asInt()).isEqualTo(500);
            assertThat(reqNum.path("usage").path("reasoning").asInt()).isEqualTo(320);
            assertThat(reqNum.path("requestConfig").path("protocol").asText()).isEqualTo("gemini");

            JsonNode groups = root.path("turns").get(0).path("groups");
            // Group 0: SYSTEM header cell ("2 tools"), Group 1: user message, Group 2: Step 1
            assertThat(groups.get(0).path("cells").get(0).path("kind").asText()).isEqualTo("system");
            JsonNode stepCells = groups.get(2).path("cells");
            // Assistant + Read#1 + Read#2 + Agent + inline subtool (Grep) = 5 cells!
            assertThat(stepCells.size()).isEqualTo(5);

            // Read#1 and Read#2 share parallelGroupId with parallelSize = 2; Agent is NOT in parallel batch!
            JsonNode read1 = stepCells.get(1);
            JsonNode read2 = stepCells.get(2);
            JsonNode agentCell = stepCells.get(3);
            JsonNode subtoolCell = stepCells.get(4);
            assertThat(read1.path("parallelSize").asInt()).isEqualTo(2);
            assertThat(read2.path("parallelSize").asInt()).isEqualTo(2);
            assertThat(read1.path("parallelGroupId").asText())
                .isEqualTo(read2.path("parallelGroupId").asText());
            assertThat(read1.path("parallelBatchSavedMs").asLong()).isGreaterThan(0L);
            assertThat(agentCell.has("parallelGroupId")).isFalse();
            assertThat(agentCell.path("subagentId").asText()).isEqualTo(agentId);
            assertThat(agentCell.path("subagentSteps").asInt()).isEqualTo(1);
            assertThat(agentCell.path("subagentTools").asInt()).isEqualTo(1);
            assertThat(subtoolCell.path("kind").asText()).isEqualTo("subtool");
            assertThat(subtoolCell.path("toolName").asText()).isEqualTo("Grep");
        }

        // 2. Verify subagent drill-down trajectory (`GET /api/sessions/{id}/agents/{agentId}/trajectory`)
        Request subReq = new Request.Builder()
            .url("http://127.0.0.1:" + server.port()
                + "/api/sessions/" + sessionId + "/agents/" + agentId + "/trajectory")
            .header("Authorization", "Bearer " + TOKEN)
            .build();
        try (Response subResp = client.newCall(subReq).execute()) {
            assertThat(subResp.code()).isEqualTo(200);
            JsonNode subRoot = JsonUtils.getMapper().readTree(subResp.body().string());
            assertThat(subRoot.path("agent_id").asText()).isEqualTo(agentId);
            assertThat(subRoot.path("total_turns").asInt()).isEqualTo(1);
            JsonNode subReqNum = subRoot.path("request_numbers").get(0);
            assertThat(subReqNum.path("provider").asText()).isEqualTo("chat · open.bigmodel.cn");
            assertThat(subReqNum.path("model").asText()).isEqualTo("glm-5.1");
            assertThat(subReqNum.path("usage").path("reasoning").asInt()).isEqualTo(30);
        }

        // 3. Verify MAX_SUBAGENT_DEPTH = 2 guard rejects spawnDepth = 3
        Path deepMeta = subFile.resolveSibling("agent-" + agentId + ".meta.json");
        Files.writeString(deepMeta, "{\"agentType\":\"Explore\",\"spawnDepth\":3}", StandardCharsets.UTF_8);
        try (Response deepResp = client.newCall(subReq).execute()) {
            assertThat(deepResp.code()).isEqualTo(400);
        }
    }
}
