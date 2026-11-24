package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.ServerToolResultBlock;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.SystemMessage;
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
import com.claudecode.session.AgentMetadata;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TrajectoryTranscriptReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * {@code GET /api/sessions/{sessionId}/trajectory} and
 * {@code GET /api/sessions/{sessionId}/agents/{agentId}/trajectory}:
 * projects main-session and subagent JSONL transcripts, {@code java-session-metrics},
 * 4-protocol {@code java-llm-request} traces, and 7-day {@link TrajectoryHeaderCache}
 * headers into the WebUI Trajectory ledger and timeline model.
 */
@Explanation("Serves 4-protocol Trajectory projections with parallel tool lanes and subagent drill-down")
final class GatewayTrajectoryHandler {

    static final int DEFAULT_TURN_LIMIT = 50;
    static final int MAX_TURN_LIMIT = 200;
    static final int MAX_SUBAGENT_DEPTH = 2;
    private static final int MAX_DETAIL_CHARS = 32_000;
    private static final String TRUNCATION_SUFFIX = "\n…[truncated]";
    private static final Pattern AGENT_ID_TEXT_PATTERN =
        Pattern.compile("\\bagentId\\s*[:=]\\s*([A-Za-z0-9_-]{4,64})");
    private static final Set<String> READ_ONLY_COISSUED_TOOLS = Set.of(
        "Read", "Glob", "Grep", "LS", "WebSearch", "WebFetch", "Agent", "Task", "ToolSearch");

    private final GatewayHeadlessSessions headless;
    private final GatewaySessionMessagesPort messagesPort;
    private final Function<String, OptionalLong> runningSince;
    private final Function<String, Optional<MirrorHub.StreamingDraft>> streaming;
    private final Predicate<String> isLiveSession;
    private final Path configHome;
    private final TrajectoryTranscriptReader reader = new TrajectoryTranscriptReader();

    private record ToolOutcome(ToolResultBlock result, Object toolUseResult, Long timestampMs) {}

    private static final class StepMetrics {
        Long stepStartMs;
        Long firstTokenMs;
        Long assistantMessageMs;
        Long stepEndMs;
        SessionMetricsEvent usageEvent;
    }

    private record ToolSpan(String callId, String toolName, Long startMs, Long endMs, int orderIndex) {
        long durationMs() {
            if (startMs == null || endMs == null) return 0L;
            return Math.max(0L, endMs - startMs);
        }
    }

    private record ParallelToolMeta(
            String groupId,
            int size,
            int index,
            Long batchWallMs,
            Long batchSavedMs,
            ArrayNode siblings) {}

    GatewayTrajectoryHandler(
            GatewayHeadlessSessions headless,
            GatewaySessionMessagesPort messagesPort,
            Function<String, OptionalLong> runningSince,
            Function<String, Optional<MirrorHub.StreamingDraft>> streaming,
            Predicate<String> isLiveSession,
            Path configHome) {
        this.headless = Objects.requireNonNull(headless, "headless");
        this.messagesPort = messagesPort == null ? new GatewaySessionMessagesPort() {} : messagesPort;
        this.runningSince = Objects.requireNonNull(runningSince, "runningSince");
        this.streaming = Objects.requireNonNull(streaming, "streaming");
        this.isLiveSession = isLiveSession == null ? headless::isOpen : isLiveSession;
        this.configHome = configHome;
    }

    void handleSession(HttpExchange exchange, String sessionId) throws IOException {
        Path effectiveHome = resolveConfigHome();
        SessionManager sessionMgr = new SessionManager(effectiveHome, ".");
        Path sessionFile = sessionMgr.findSessionFile(sessionId).orElse(null);

        String openHeadlessId = headless.isOpen(sessionId) ? sessionId : null;
        List<Message> liveRows = null;
        try {
            liveRows = messagesPort.messages(sessionId, openHeadlessId).orElse(null);
        } catch (RuntimeException _) {
            liveRows = null;
        }

        if (sessionFile == null && liveRows == null) {
            if (isLiveSession.test(sessionId)) {
                liveRows = List.of();
            } else {
                respondJson(exchange, 404, errorBody("not_found", "unknown session: " + sessionId));
                return;
            }
        }

        TrajectoryTranscriptReader.TrajectoryTranscript transcript =
            reader.readSession(sessionFile, liveRows);
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        Long runningAt = runningSince.apply(sessionId).isPresent()
            ? runningSince.apply(sessionId).getAsLong() : null;
        MirrorHub.StreamingDraft liveDraft = streaming.apply(sessionId).orElse(null);

        ObjectNode response = projectTrajectory(
            sessionId, null, null, 0, transcript, effectiveHome, query, runningAt, liveDraft);
        respondJson(exchange, 200, response);
    }

    void handleAgent(
            HttpExchange exchange, String sessionId, String agentId, boolean agentRunning)
            throws IOException {
        Path effectiveHome = resolveConfigHome();
        SessionManager sessionMgr = new SessionManager(effectiveHome, ".");
        Path agentFile = sessionMgr.findAgentTranscriptPath(sessionId, agentId).orElse(null);

        List<Message> liveRows = null;
        try {
            liveRows = messagesPort.agentMessages(sessionId, agentId).orElse(null);
        } catch (RuntimeException _) {
            liveRows = null;
        }

        if (agentFile == null && (liveRows == null || liveRows.isEmpty())) {
            respondJson(exchange, 404, errorBody("not_found",
                "unknown agent: " + agentId + " in session " + sessionId));
            return;
        }

        int spawnDepth = 1;
        String agentType = null;
        if (agentFile != null && Files.isRegularFile(agentFile)) {
            Optional<AgentMetadata> meta = new SessionStorage().readAgentMetadata(agentFile);
            if (meta.isPresent()) {
                agentType = meta.get().agentType();
            }
            spawnDepth = readSpawnDepth(agentFile);
        }
        if (spawnDepth > MAX_SUBAGENT_DEPTH) {
            respondJson(exchange, 400, errorBody("max_depth_exceeded",
                "subagent trajectory depth exceeds limit (" + MAX_SUBAGENT_DEPTH + ")"));
            return;
        }

        Optional<TrajectoryTranscriptReader.TrajectoryTranscript> loaded =
            reader.readAgent(agentFile, agentId, liveRows);
        if (loaded.isEmpty()) {
            respondJson(exchange, 404, errorBody("not_found",
                "unreadable agent transcript: " + agentId));
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        Long runningAt = agentRunning ? System.currentTimeMillis() : null;
        ObjectNode response = projectTrajectory(
            sessionId, agentId, agentType, spawnDepth, loaded.get(), effectiveHome,
            query, runningAt, null);
        respondJson(exchange, 200, response);
    }

    private Path resolveConfigHome() {
        return configHome != null ? configHome : ClaudePaths.currentClaudeHome();
    }

    private static int readSpawnDepth(Path agentFile) {
        String name = agentFile.getFileName().toString();
        if (!Strings.CS.endsWith(name, ".jsonl")) return 1;
        Path metaFile = agentFile.resolveSibling(
            name.substring(0, name.length() - ".jsonl".length()) + ".meta.json");
        if (!Files.isRegularFile(metaFile)) return 1;
        try {
            JsonNode node = JsonUtils.getMapper().readTree(
                Files.readString(metaFile, StandardCharsets.UTF_8));
            int depth = node.path("spawnDepth").asInt(1);
            return Math.max(1, depth);
        } catch (IOException _) {
            return 1;
        }
    }

    private static ObjectNode projectTrajectory(
            String sessionId,
            String agentId,
            String agentType,
            int agentDepth,
            TrajectoryTranscriptReader.TrajectoryTranscript transcript,
            Path effectiveHome,
            Map<String, String> query,
            Long runningSinceMs,
            MirrorHub.StreamingDraft streamingDraft) {
        ObjectMapper mapper = JsonUtils.getMapper();
        TrajectoryHeaderCache headerCache = new TrajectoryHeaderCache(
            effectiveHome.resolve("cache").resolve("trajectory-headers"),
            TrajectoryHeaderCache.DEFAULT_TTL,
            System::currentTimeMillis);

        // 1. Index SessionMetricsEvents by (turn, step), turnStart, and callId.
        Map<String, StepMetrics> stepMetricsByKey = new HashMap<>();
        Map<Long, Long> turnStartByTurn = new HashMap<>();
        Map<String, Long> toolStartByCallId = new HashMap<>();
        Map<String, Long> toolEndByCallId = new HashMap<>();
        for (SessionMetricsEvent ev : transcript.metricsEvents()) {
            switch (ev.kind()) {
                case TURN_START -> turnStartByTurn.putIfAbsent(ev.turn(), ev.time());
                case STEP_START -> stepMetrics(stepMetricsByKey, ev.turn(), ev.step()).stepStartMs = ev.time();
                case ASSISTANT_FIRST_TOKEN ->
                    stepMetrics(stepMetricsByKey, ev.turn(), ev.step()).firstTokenMs = ev.time();
                case ASSISTANT_MESSAGE ->
                    stepMetrics(stepMetricsByKey, ev.turn(), ev.step()).assistantMessageMs = ev.time();
                case STEP_END -> stepMetrics(stepMetricsByKey, ev.turn(), ev.step()).stepEndMs = ev.time();
                case ASSISTANT_USAGE -> stepMetrics(stepMetricsByKey, ev.turn(), ev.step()).usageEvent = ev;
                case TOOL_CALL -> {
                    if (ev.callId() != null) toolStartByCallId.putIfAbsent(ev.callId(), ev.time());
                }
                case TOOL_RESULT -> {
                    if (ev.callId() != null) toolEndByCallId.put(ev.callId(), ev.time());
                }
                default -> { }
            }
        }

        // 2. Index LlmRequestTraceRecords by (turn, step) and compaction traces by turn.
        Map<String, LlmRequestTraceRecord> assistantTraceByKey = new LinkedHashMap<>();
        List<LlmRequestTraceRecord> compactionTraces = new ArrayList<>();
        for (LlmRequestTraceRecord rec : transcript.requestTraces()) {
            if (Strings.CS.equals("compaction", rec.purpose())) {
                compactionTraces.add(rec);
            } else if (rec.turn() > 0 && rec.step() > 0) {
                assistantTraceByKey.put(stepKey(rec.turn(), rec.step()), rec);
            }
        }

        // 3. Pair tool results across all messages.
        Map<String, ToolOutcome> outcomesByCallId =
            collectToolOutcomes(transcript.messages(), transcript.timestampsByUuid());

        // 4. Project full turn list (keeping session-global cell indexes & request numbers stable!).
        List<ProjectedSection> allSections = new ArrayList<>();
        List<ObjectNode> allRequestNumbers = new ArrayList<>();
        CumulativeUsage runningTotals = new CumulativeUsage();
        int[] nextRecordIndex = {1};
        int[] nextRequestNumber = {1};
        int[] nextCompactionSeq = {1};
        String[] previousHeaderSha = {null};
        ObjectNode[] previousPromptSnapshot = {null};
        Map<String, String> activeToolSchemas = new HashMap<>();

        long currentTurn = 0;
        long currentStepInTurn = 0;
        ProjectedSection activeTurnSection = null;

        List<Message> msgs = transcript.messages();
        int i = 0;
        while (i < msgs.size()) {
            Message msg = msgs.get(i);
            if (msg instanceof UserMessage user) {
                if (isPureToolResultUser(user)) {
                    i++;
                    continue;
                }
                if (user.isCompactSummary()) {
                    String summaryText = extractUserText(user);
                    Long ts = messageTimestamp(user.uuid(), user.timestampValue(), transcript.timestampsByUuid());
                    LlmRequestTraceRecord compactTrace = pollMatchingCompactionTrace(compactionTraces, currentTurn);
                    int compSeq = nextCompactionSeq[0]++;
                    ObjectNode compCell = mapper.createObjectNode();
                    compCell.put("index", nextRecordIndex[0]++);
                    compCell.put("recordId", "compacted\u0000" + user.uuid());
                    compCell.put("kind", "compacted");
                    compCell.put("text", firstLineSummary(summaryText, 120));
                    compCell.put("outputDetail", truncateDetail(summaryText));
                    compCell.putNull("timeSeconds");
                    if (ts != null) compCell.put("startedAt", ts);

                    ObjectNode compGroup = mapper.createObjectNode();
                    compGroup.put("kind", "compaction");
                    compGroup.put("seq", compSeq);
                    compGroup.putArray("cells").add(compCell);

                    ProjectedSection compSection = new ProjectedSection(null);
                    compSection.groups.add(compGroup);
                    allSections.add(compSection);

                    allRequestNumbers.add(buildCompactionRequestNumber(
                        mapper, nextRequestNumber[0]++, compSeq,
                        currentTurn > 0 ? currentTurn : null, compactTrace, runningTotals));
                    i++;
                    continue;
                }
                if (user.isMeta()) {
                    String metaText = extractUserText(user);
                    if (StringUtils.isNotBlank(metaText) && activeTurnSection != null) {
                        Long ts = messageTimestamp(user.uuid(), user.timestampValue(), transcript.timestampsByUuid());
                        ObjectNode ctxCell = mapper.createObjectNode();
                        ctxCell.put("index", nextRecordIndex[0]++);
                        ctxCell.put("recordId", "context\u0000" + user.uuid());
                        ctxCell.put("kind", "context");
                        ctxCell.put("text", firstLineSummary(metaText, 120));
                        ctxCell.put("inputDetail", truncateDetail(metaText));
                        ctxCell.putNull("timeSeconds");
                        if (ts != null) ctxCell.put("startedAt", ts);
                        appendToMessageOrNewGroup(mapper, activeTurnSection, ctxCell);
                    }
                    i++;
                    continue;
                }

                // Human turn-opening user message!
                currentTurn++;
                currentStepInTurn = 0;
                activeTurnSection = new ProjectedSection(currentTurn);
                allSections.add(activeTurnSection);

                // Check if the first step of this turn introduces or updates System + Tools header.
                LlmRequestTraceRecord firstStepTrace = assistantTraceByKey.get(stepKey(currentTurn, 1));
                maybeEmitSystemHeaderCell(
                    mapper, headerCache, firstStepTrace, previousHeaderSha,
                    previousPromptSnapshot, activeToolSchemas, nextRecordIndex, activeTurnSection);

                String userText = extractUserText(user);
                Long turnStartedAt = turnStartByTurn.getOrDefault(
                    currentTurn,
                    messageTimestamp(user.uuid(), user.timestampValue(), transcript.timestampsByUuid()));
                ObjectNode userCell = mapper.createObjectNode();
                userCell.put("index", nextRecordIndex[0]++);
                userCell.put("recordId", "user\u0000" + user.uuid());
                userCell.put("kind", "user");
                userCell.put("opensTurn", true);
                userCell.put("text", "");
                userCell.put("previewMarkdown", truncateDetail(userText));
                userCell.put("inputDetail", truncateDetail(userText));
                userCell.putNull("timeSeconds");
                if (turnStartedAt != null) userCell.put("startedAt", turnStartedAt);

                ObjectNode msgGroup = mapper.createObjectNode();
                msgGroup.put("kind", "message");
                msgGroup.putArray("cells").add(userCell);
                activeTurnSection.groups.add(msgGroup);
                i++;
                continue;
            }

            if (msg instanceof AssistantMessage firstAsst) {
                if (activeTurnSection == null) {
                    currentTurn++;
                    currentStepInTurn = 0;
                    activeTurnSection = new ProjectedSection(currentTurn);
                    allSections.add(activeTurnSection);
                }
                currentStepInTurn++;
                String firstApiId = firstAsst.message() != null ? firstAsst.message().id() : null;
                List<AssistantMessage> stepMessages = new ArrayList<>();
                stepMessages.add(firstAsst);
                int j = i + 1;
                while (j < msgs.size() && msgs.get(j) instanceof AssistantMessage nextAsst) {
                    String nextApiId = nextAsst.message() != null ? nextAsst.message().id() : null;
                    if (StringUtils.isNotBlank(firstApiId)
                            && StringUtils.isNotBlank(nextApiId)
                            && !Strings.CS.equals(firstApiId, nextApiId)) {
                        break;
                    }
                    stepMessages.add(nextAsst);
                    j++;
                }

                LlmRequestTraceRecord trace =
                    assistantTraceByKey.get(stepKey(currentTurn, currentStepInTurn));
                if (currentStepInTurn > 1) {
                    maybeEmitSystemHeaderCell(
                        mapper, headerCache, trace, previousHeaderSha,
                        previousPromptSnapshot, activeToolSchemas, nextRecordIndex, activeTurnSection);
                }

                StepMetrics sm = stepMetricsByKey.get(stepKey(currentTurn, currentStepInTurn));
                projectAssistantStep(
                    mapper,
                    currentTurn,
                    currentStepInTurn,
                    stepMessages,
                    sm,
                    trace,
                    outcomesByCallId,
                    toolStartByCallId,
                    toolEndByCallId,
                    transcript.timestampsByUuid(),
                    transcript.subagents(),
                    activeToolSchemas,
                    agentDepth,
                    nextRecordIndex,
                    nextRequestNumber,
                    runningTotals,
                    activeTurnSection,
                    allRequestNumbers);
                i = j;
                continue;
            }

            if (msg instanceof AttachmentMessage att && activeTurnSection != null) {
                String label = att.payload() != null
                    ? att.payload().getClass().getSimpleName() : "Attachment";
                ObjectNode ctxCell = mapper.createObjectNode();
                ctxCell.put("index", nextRecordIndex[0]++);
                ctxCell.put("recordId", "context\u0000" + att.uuid());
                ctxCell.put("kind", "context");
                ctxCell.put("text", label);
                ctxCell.put("inputDetail", truncateDetail(JsonUtils.toJson(att.payload())));
                ctxCell.putNull("timeSeconds");
                appendToMessageOrNewGroup(mapper, activeTurnSection, ctxCell);
            } else if (msg instanceof SystemMessage sys && activeTurnSection != null
                    && StringUtils.isNotBlank(sys.content())) {
                ObjectNode ctxCell = mapper.createObjectNode();
                ctxCell.put("index", nextRecordIndex[0]++);
                ctxCell.put("recordId", "context\u0000" + sys.uuid());
                ctxCell.put("kind", "context");
                ctxCell.put("text", firstLineSummary(sys.content(), 120));
                ctxCell.put("inputDetail", truncateDetail(sys.content()));
                ctxCell.putNull("timeSeconds");
                appendToMessageOrNewGroup(mapper, activeTurnSection, ctxCell);
            }
            i++;
        }

        // 5. If a live turn is currently streaming in memory, append an in-flight step preview.
        if (runningSinceMs != null && streamingDraft != null) {
            if (activeTurnSection == null) {
                currentTurn++;
                currentStepInTurn = 0;
                activeTurnSection = new ProjectedSection(currentTurn);
                allSections.add(activeTurnSection);
            }
            long liveStep = currentStepInTurn + 1;
            ObjectNode liveCell = mapper.createObjectNode();
            liveCell.put("index", nextRecordIndex[0]++);
            liveCell.put("recordId", "live\u0000" + currentTurn + "\u0000" + liveStep);
            liveCell.put("kind", "message");
            liveCell.put("text", "");
            if (Strings.CS.equals("thinking", streamingDraft.kind())) {
                liveCell.put("thinkingDetail", truncateDetail(streamingDraft.content()));
            } else {
                liveCell.put("previewMarkdown", truncateDetail(streamingDraft.content()));
                liveCell.put("outputDetail", truncateDetail(streamingDraft.content()));
            }
            liveCell.putNull("timeSeconds");
            liveCell.put("startedAt", runningSinceMs);

            ObjectNode stepGroup = mapper.createObjectNode();
            stepGroup.put("kind", "step");
            stepGroup.put("step", liveStep);
            stepGroup.putArray("cells").add(liveCell);
            activeTurnSection.groups.add(stepGroup);

            ObjectNode liveReq = mapper.createObjectNode();
            liveReq.put("purpose", "assistant");
            liveReq.put("turn", currentTurn);
            liveReq.put("step", liveStep);
            liveReq.put("number", nextRequestNumber[0]++);
            liveReq.put("status", "running");
            liveReq.put("startedAt", runningSinceMs);
            liveReq.putNull("completedAt");
            allRequestNumbers.add(liveReq);
        }

        // 6. Apply turn-based pagination (`before_turn`, `limit`, `include_call_id`).
        long totalTurns = currentTurn;
        long beforeTurn = parseLongParam(query.get("before_turn"), totalTurns + 1);
        int limit = (int) Math.clamp(
            parseLongParam(query.get("limit"), DEFAULT_TURN_LIMIT), 1, MAX_TURN_LIMIT);
        String includeCallId = query.get("include_call_id");

        List<ProjectedSection> eligible = new ArrayList<>();
        for (ProjectedSection sec : allSections) {
            if (sec.turn == null || sec.turn < beforeTurn) {
                eligible.add(sec);
            }
        }
        // Count numbered turns from the end up to `limit`, expanding if `includeCallId` is older.
        long minIncludedTurn = computeMinIncludedTurn(eligible, limit, includeCallId);
        boolean hasOlderTurns = minIncludedTurn > 1;

        ArrayNode turnsOut = mapper.createArrayNode();
        boolean seenIncludedTurn = !hasOlderTurns;
        for (ProjectedSection sec : eligible) {
            if (sec.turn != null && sec.turn >= minIncludedTurn) {
                seenIncludedTurn = true;
            }
            if (!seenIncludedTurn) continue;
            ObjectNode turnNode = mapper.createObjectNode();
            if (sec.turn == null) {
                turnNode.putNull("turn");
            } else {
                turnNode.put("turn", sec.turn);
            }
            ArrayNode groupsNode = turnNode.putArray("groups");
            sec.groups.forEach(groupsNode::add);
            turnsOut.add(turnNode);
        }

        ArrayNode reqOut = mapper.createArrayNode();
        for (ObjectNode req : allRequestNumbers) {
            long reqTurn = req.path("turn").asLong(0);
            if (reqTurn > 0 && (reqTurn < minIncludedTurn || reqTurn >= beforeTurn)) {
                continue;
            }
            reqOut.add(req);
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("session_id", sessionId);
        if (agentId != null) {
            out.put("agent_id", agentId);
            if (agentType != null) out.put("agent_type", agentType);
            out.put("agent_depth", agentDepth);
        }
        out.put("total_turns", totalTurns);
        out.put("oldest_turn", totalTurns == 0 ? 0 : Math.max(1L, minIncludedTurn));
        out.put("has_older_turns", hasOlderTurns);
        if (runningSinceMs != null) {
            out.put("running_since", runningSinceMs);
        }
        out.set("turns", turnsOut);
        out.set("request_numbers", reqOut);
        return out;
    }

    private static void projectAssistantStep(
            ObjectMapper mapper,
            long turn,
            long step,
            List<AssistantMessage> stepMessages,
            StepMetrics sm,
            LlmRequestTraceRecord trace,
            Map<String, ToolOutcome> outcomesByCallId,
            Map<String, Long> toolStartByCallId,
            Map<String, Long> toolEndByCallId,
            Map<String, Long> timestampsByUuid,
            Map<String, TrajectoryTranscriptReader.SubagentSummary> subagents,
            Map<String, String> activeToolSchemas,
            int currentAgentDepth,
            int[] nextRecordIndex,
            int[] nextRequestNumber,
            CumulativeUsage runningTotals,
            ProjectedSection activeTurnSection,
            List<ObjectNode> allRequestNumbers) {

        StringBuilder textOut = new StringBuilder();
        StringBuilder thinkingOut = new StringBuilder();
        List<ToolUseBlock> toolCalls = new ArrayList<>();
        ArrayNode sourceBlocks = mapper.createArrayNode();
        Usage stepUsage = Usage.EMPTY;
        String model = null;
        boolean isError = false;
        Long firstMsgTs = null;
        Long lastMsgTs = null;

        for (AssistantMessage asst : stepMessages) {
            Long msgTs = messageTimestamp(asst.uuid(), asst.timestampValue(), timestampsByUuid);
            if (msgTs != null) {
                if (firstMsgTs == null) firstMsgTs = msgTs;
                lastMsgTs = msgTs;
            }
            if (asst.isApiErrorMessage()) {
                isError = true;
            }
            if (asst.message() == null) continue;
            if (StringUtils.isNotBlank(asst.message().model())) {
                model = asst.message().model();
            }
            if (asst.message().usage() != null) {
                stepUsage = stepUsage.updateCumulative(asst.message().usage());
            }
            if (asst.message().content() == null) continue;
            for (ContentBlock block : asst.message().content()) {
                switch (block) {
                    case ThinkingBlock tb -> {
                        if (StringUtils.isNotEmpty(tb.thinking())) {
                            if (!thinkingOut.isEmpty()) thinkingOut.append("\n\n");
                            thinkingOut.append(tb.thinking());
                            ObjectNode sb = sourceBlocks.addObject();
                            sb.put("type", "thinking");
                            sb.put("content", truncateDetail(tb.thinking()));
                        }
                    }
                    case RedactedThinkingBlock _ -> {
                        ObjectNode sb = sourceBlocks.addObject();
                        sb.put("type", "thinking");
                        sb.put("content", "[redacted thinking]");
                    }
                    case TextBlock txt -> {
                        if (StringUtils.isNotEmpty(txt.text())) {
                            if (!textOut.isEmpty()) textOut.append("\n\n");
                            textOut.append(txt.text());
                            ObjectNode sb = sourceBlocks.addObject();
                            sb.put("type", "text");
                            sb.put("content", truncateDetail(txt.text()));
                        }
                    }
                    case ToolUseBlock tub -> {
                        toolCalls.add(tub);
                        ObjectNode sb = sourceBlocks.addObject();
                        sb.put("type", "tool-call");
                        sb.put("callId", tub.id());
                        sb.put("toolName", tub.name());
                        sb.put("content", tub.input() != null ? truncateDetail(tub.input().toString()) : "{}");
                    }
                    case ServerToolUseBlock stub -> {
                        ObjectNode sb = sourceBlocks.addObject();
                        sb.put("type", "server_tool_use");
                        sb.put("callId", stub.id());
                        sb.put("toolName", stub.name());
                        sb.put("content", stub.input() != null ? truncateDetail(stub.input().toString()) : "{}");
                    }
                    case ServerToolResultBlock strb -> {
                        ObjectNode sb = sourceBlocks.addObject();
                        sb.put("type", "server_tool_result");
                        sb.put("toolName", strb.name());
                        sb.put("content", strb.content() != null ? truncateDetail(strb.content().toString()) : "");
                    }
                    default -> { }
                }
            }
        }

        // Fallback to SessionMetricsEvent usage if AssistantMessage carried empty usage.
        if (stepUsage.inputTokens() == 0 && stepUsage.outputTokens() == 0
                && sm != null && sm.usageEvent != null) {
            stepUsage = new Usage(
                sm.usageEvent.uncachedInputTokens(),
                sm.usageEvent.outputTokens(),
                sm.usageEvent.cacheWriteTokens(),
                sm.usageEvent.cacheReadTokens());
        }

        Long stepStartedAt = sm != null && sm.stepStartMs != null
            ? sm.stepStartMs
            : (trace != null && trace.startedAt() > 0 ? trace.startedAt() : firstMsgTs);
        Long stepCompletedAt = sm != null && sm.assistantMessageMs != null
            ? sm.assistantMessageMs
            : (trace != null && trace.completedAt() > 0 ? trace.completedAt() : lastMsgTs);
        Double assistantSeconds = (stepStartedAt != null && stepCompletedAt != null
                && stepCompletedAt >= stepStartedAt)
            ? (stepCompletedAt - stepStartedAt) / 1000.0
            : null;

        AssistantMessage anchorMsg = stepMessages.getFirst();
        ObjectNode asstCell = mapper.createObjectNode();
        asstCell.put("index", nextRecordIndex[0]++);
        asstCell.put("recordId", "message\u0000" + anchorMsg.uuid());
        asstCell.put("kind", "message");
        asstCell.put("text", "");
        if (!textOut.isEmpty()) {
            String fullText = truncateDetail(textOut.toString());
            asstCell.put("previewMarkdown", fullText);
            asstCell.put("outputDetail", fullText);
        }
        if (!thinkingOut.isEmpty()) {
            asstCell.put("thinkingDetail", truncateDetail(thinkingOut.toString()));
        }
        if (!sourceBlocks.isEmpty()) {
            asstCell.set("sourceBlocks", sourceBlocks);
        }
        if (isError || (trace != null && Strings.CS.equals("failed", trace.status()))) {
            asstCell.put("isError", true);
        }
        if (assistantSeconds != null) {
            asstCell.put("timeSeconds", assistantSeconds);
        } else {
            asstCell.putNull("timeSeconds");
        }
        if (stepStartedAt != null) {
            asstCell.put("startedAt", stepStartedAt);
        }

        boolean usageProvided = stepUsage.inputTokens() > 0
            || stepUsage.outputTokens() > 0
            || stepUsage.cacheReadInputTokens() > 0
            || stepUsage.cacheCreationInputTokens() > 0;
        if (usageProvided) {
            asstCell.put("input", stepUsage.inputTokens());
            asstCell.put("cacheRead", stepUsage.cacheReadInputTokens());
            asstCell.put("cacheWrite", stepUsage.cacheCreationInputTokens());
            asstCell.put("output", stepUsage.outputTokens());
            if (stepUsage.reasoningTokens() != null && stepUsage.reasoningTokens() > 0) {
                asstCell.put("think", stepUsage.reasoningTokens());
            }
        }

        ObjectNode metricsNode = asstCell.putObject("assistantMetrics");
        boolean timingRecorded = (sm != null && sm.stepStartMs != null)
            || (trace != null && trace.startedAt() > 0);
        metricsNode.put("timingRecorded", timingRecorded);
        putNullableLong(metricsNode, "stepStartTime", stepStartedAt);
        putNullableLong(metricsNode, "firstTokenTime", sm != null ? sm.firstTokenMs : null);
        putNullableLong(metricsNode, "completedTime", stepCompletedAt);
        metricsNode.put("usageProvided", usageProvided);
        if (usageProvided) {
            metricsNode.put("outputTokens", stepUsage.outputTokens());
        } else {
            metricsNode.putNull("outputTokens");
        }

        ObjectNode stepGroup = mapper.createObjectNode();
        stepGroup.put("kind", "step");
        stepGroup.put("step", step);
        ArrayNode cells = stepGroup.putArray("cells");
        cells.add(asstCell);

        // Compute parallel tool call batches within this step!
        Map<String, ParallelToolMeta> parallelByCallId =
            computeParallelBatches(mapper, turn, step, toolCalls, toolStartByCallId, toolEndByCallId);

        Long latestGroupEndMs = stepCompletedAt;
        for (ToolUseBlock tub : toolCalls) {
            ToolOutcome outcome = outcomesByCallId.get(tub.id());
            Long tStart = toolStartByCallId.get(tub.id());
            Long tEnd = toolEndByCallId.get(tub.id());
            if (tStart == null && stepCompletedAt != null) {
                tStart = stepCompletedAt;
            }
            if (tEnd == null && outcome != null && outcome.timestampMs() != null) {
                tEnd = outcome.timestampMs();
            }
            if (tEnd != null && (latestGroupEndMs == null || tEnd > latestGroupEndMs)) {
                latestGroupEndMs = tEnd;
            }
            Double toolSeconds = (tStart != null && tEnd != null && tEnd >= tStart)
                ? (tEnd - tStart) / 1000.0
                : null;

            ObjectNode toolCell = mapper.createObjectNode();
            toolCell.put("index", nextRecordIndex[0]++);
            toolCell.put("recordId", "tool\u0000call\u0000" + tub.id());
            toolCell.put("kind", "tool");
            toolCell.put("callId", tub.id());
            toolCell.put("toolName", tub.name());
            toolCell.put("text", formatToolCallSummary(tub.name(), tub.input()));
            toolCell.put("inputDetail", tub.input() != null
                ? truncateDetail(prettyJson(mapper, tub.input())) : "{}");
            String schemaJson = activeToolSchemas.get(tub.name());
            if (schemaJson != null) {
                toolCell.put("schemaDetail", schemaJson);
            }
            if (outcome != null && outcome.result() != null) {
                String outText = extractToolResultText(outcome.result());
                toolCell.put("result", firstLineSummary(outText, 120));
                toolCell.put("outputDetail", truncateDetail(outText));
                if (outcome.result().isError()) {
                    toolCell.put("isError", true);
                }
            }
            if (toolSeconds != null) {
                toolCell.put("timeSeconds", toolSeconds);
            } else {
                toolCell.putNull("timeSeconds");
            }
            if (tStart != null) {
                toolCell.put("startedAt", tStart);
            }

            // Parallel tool call metadata!
            ParallelToolMeta pMeta = parallelByCallId.get(tub.id());
            if (pMeta != null) {
                toolCell.put("parallelGroupId", pMeta.groupId());
                toolCell.put("parallelSize", pMeta.size());
                toolCell.put("parallelIndex", pMeta.index());
                if (pMeta.batchWallMs() != null) {
                    toolCell.put("parallelBatchWallMs", pMeta.batchWallMs());
                }
                if (pMeta.batchSavedMs() != null && pMeta.batchSavedMs() > 0) {
                    toolCell.put("parallelBatchSavedMs", pMeta.batchSavedMs());
                }
                if (pMeta.siblings() != null) {
                    toolCell.set("parallelSiblings", pMeta.siblings());
                }
            }

            // Subagent (`Agent` / `Task`) linkage + inline subtool rows!
            TrajectoryTranscriptReader.SubagentSummary subSummary = null;
            if ((Strings.CS.equals("Agent", tub.name()) || Strings.CS.equals("Task", tub.name()))
                    && currentAgentDepth < MAX_SUBAGENT_DEPTH) {
                String subId = extractSubagentId(outcome);
                if (StringUtils.isNotBlank(subId)) {
                    subSummary = subagents.get(subId);
                    toolCell.put("subagentId", subId);
                    toolCell.put("subagentLabel", formatSubagentLabel(tub.input(), subSummary));
                    if (subSummary != null) {
                        toolCell.put("subagentSteps", subSummary.stepCount());
                        toolCell.put("subagentTools", subSummary.toolCount());
                        if (StringUtils.isNotBlank(subSummary.model())) {
                            toolCell.put("subagentModel", subSummary.model());
                        }
                    }
                }
            }

            cells.add(toolCell);

            if (subSummary != null && !subSummary.subtools().isEmpty()) {
                for (TrajectoryTranscriptReader.SubtoolPreview sub : subSummary.subtools()) {
                    ObjectNode subCell = mapper.createObjectNode();
                    subCell.put("index", nextRecordIndex[0]++);
                    subCell.put("recordId", "subtool\u0000call\u0000" + sub.callId());
                    subCell.put("kind", "subtool");
                    subCell.put("callId", sub.callId());
                    subCell.put("toolName", sub.toolName());
                    subCell.put("text", formatToolCallSummary(sub.toolName(), sub.args()));
                    subCell.put("inputDetail", sub.args() != null
                        ? truncateDetail(prettyJson(mapper, sub.args())) : "{}");
                    if (sub.output() != null) {
                        subCell.put("result", firstLineSummary(sub.output(), 120));
                        subCell.put("outputDetail", truncateDetail(sub.output()));
                    }
                    if (sub.isError()) {
                        subCell.put("isError", true);
                    }
                    if (sub.startedAt() != null && sub.completedAt() != null
                            && sub.completedAt() >= sub.startedAt()) {
                        subCell.put("timeSeconds", (sub.completedAt() - sub.startedAt()) / 1000.0);
                    } else {
                        subCell.putNull("timeSeconds");
                    }
                    if (sub.startedAt() != null) {
                        subCell.put("startedAt", sub.startedAt());
                    }
                    cells.add(subCell);
                }
            }
        }

        if (sm != null && sm.stepEndMs != null
                && (latestGroupEndMs == null || sm.stepEndMs > latestGroupEndMs)) {
            latestGroupEndMs = sm.stepEndMs;
        }
        if (stepStartedAt != null && latestGroupEndMs != null && latestGroupEndMs >= stepStartedAt) {
            stepGroup.put("durationMs", latestGroupEndMs - stepStartedAt);
        }
        activeTurnSection.groups.add(stepGroup);

        // Build 4-protocol TrajectoryRequestNumber entry!
        allRequestNumbers.add(buildAssistantRequestNumber(
            mapper,
            nextRequestNumber[0]++,
            turn,
            step,
            trace,
            model,
            stepStartedAt,
            stepCompletedAt,
            isError,
            usageProvided ? stepUsage : null,
            runningTotals));
    }

    private static Map<String, ParallelToolMeta> computeParallelBatches(
            ObjectMapper mapper,
            long turn,
            long step,
            List<ToolUseBlock> toolCalls,
            Map<String, Long> toolStartByCallId,
            Map<String, Long> toolEndByCallId) {
        if (toolCalls.size() < 2) {
            return Map.of();
        }
        List<ToolSpan> spans = new ArrayList<>(toolCalls.size());
        boolean allTimed = true;
        for (int idx = 0; idx < toolCalls.size(); idx++) {
            ToolUseBlock tub = toolCalls.get(idx);
            Long s = toolStartByCallId.get(tub.id());
            Long e = toolEndByCallId.get(tub.id());
            if (s == null || e == null) {
                allTimed = false;
            }
            spans.add(new ToolSpan(tub.id(), tub.name(), s, e, idx));
        }

        Map<String, ParallelToolMeta> result = new HashMap<>();
        if (allTimed) {
            // Partition overlapping intervals via sweep-line cluster merging.
            List<ToolSpan> sorted = new ArrayList<>(spans);
            sorted.sort(Comparator
                .comparingLong((ToolSpan s) -> s.startMs)
                .thenComparingInt(ToolSpan::orderIndex));
            int batchSeq = 0;
            int idx = 0;
            while (idx < sorted.size()) {
                List<ToolSpan> cluster = new ArrayList<>();
                ToolSpan first = sorted.get(idx);
                cluster.add(first);
                long clusterMaxEnd = first.endMs;
                long clusterMinStart = first.startMs;
                int next = idx + 1;
                while (next < sorted.size()) {
                    ToolSpan cand = sorted.get(next);
                    // Overlap when cand starts strictly before clusterMaxEnd (or within <=1ms tick).
                    boolean overlaps = cand.startMs < clusterMaxEnd
                        || (cand.startMs == clusterMinStart && cluster.getLast().durationMs() == 0L);
                    if (!overlaps) {
                        break;
                    }
                    cluster.add(cand);
                    clusterMaxEnd = Math.max(clusterMaxEnd, cand.endMs);
                    next++;
                }
                if (cluster.size() >= 2) {
                    batchSeq++;
                    cluster.sort(Comparator.comparingInt(ToolSpan::orderIndex));
                    String groupId = "pg-" + turn + "-" + step + "-" + batchSeq;
                    long wallMs = Math.max(0L, clusterMaxEnd - clusterMinStart);
                    long sumMs = 0L;
                    ArrayNode siblings = mapper.createArrayNode();
                    for (ToolSpan member : cluster) {
                        long dur = member.durationMs();
                        sumMs += dur;
                        ObjectNode sib = siblings.addObject();
                        sib.put("callId", member.callId);
                        sib.put("toolName", member.toolName);
                        sib.put("offsetMs", Math.max(0L, member.startMs - clusterMinStart));
                        sib.put("durationMs", dur);
                    }
                    long savedMs = Math.max(0L, sumMs - wallMs);
                    for (int k = 0; k < cluster.size(); k++) {
                        result.put(cluster.get(k).callId, new ParallelToolMeta(
                            groupId, cluster.size(), k, wallMs, savedMs, siblings));
                    }
                }
                idx = next;
            }
            return result;
        }

        // Legacy cold transcript without per-tool metrics: group consecutive read-only / concurrent
        // tools co-issued inside the same single LLM response.
        int batchSeq = 0;
        int idx = 0;
        while (idx < toolCalls.size()) {
            if (!READ_ONLY_COISSUED_TOOLS.contains(toolCalls.get(idx).name())) {
                idx++;
                continue;
            }
            int end = idx + 1;
            while (end < toolCalls.size()
                    && READ_ONLY_COISSUED_TOOLS.contains(toolCalls.get(end).name())) {
                end++;
            }
            if (end - idx >= 2) {
                batchSeq++;
                String groupId = "pg-" + turn + "-" + step + "-" + batchSeq;
                int size = end - idx;
                ArrayNode siblings = mapper.createArrayNode();
                for (int k = idx; k < end; k++) {
                    ObjectNode sib = siblings.addObject();
                    sib.put("callId", toolCalls.get(k).id());
                    sib.put("toolName", toolCalls.get(k).name());
                    sib.put("offsetMs", 0L);
                    sib.put("durationMs", 0L);
                }
                for (int k = idx; k < end; k++) {
                    result.put(toolCalls.get(k).id(), new ParallelToolMeta(
                        groupId, size, k - idx, null, null, siblings));
                }
            }
            idx = end;
        }
        return result;
    }

    private static void maybeEmitSystemHeaderCell(
            ObjectMapper mapper,
            TrajectoryHeaderCache headerCache,
            LlmRequestTraceRecord trace,
            String[] previousHeaderSha,
            ObjectNode[] previousPromptSnapshot,
            Map<String, String> activeToolSchemas,
            int[] nextRecordIndex,
            ProjectedSection targetSection) {
        if (trace == null || StringUtils.isBlank(trace.headerSha256())) {
            return;
        }
        if (Strings.CS.equals(trace.headerSha256(), previousHeaderSha[0])) {
            return;
        }
        Optional<TrajectoryHeaderCache.HeaderSnapshot> loaded =
            headerCache.load(trace.headerSha256());
        if (loaded.isEmpty()) {
            return;
        }
        TrajectoryHeaderCache.HeaderSnapshot snap = loaded.get();
        previousHeaderSha[0] = snap.sha256();

        activeToolSchemas.clear();
        ObjectNode promptDetail = mapper.createObjectNode();
        ObjectNode cfg = promptDetail.putObject("config");
        if (StringUtils.isNotBlank(trace.providerLabel())) {
            cfg.put("provider", trace.providerLabel());
        }
        if (StringUtils.isNotBlank(trace.respondedModel())) {
            cfg.put("model", trace.respondedModel());
        }
        promptDetail.put("system", snap.systemPrompt());
        ArrayNode toolsArr = promptDetail.putArray("tools");
        for (TrajectoryHeaderCache.ToolSpec tool : snap.tools()) {
            ObjectNode tNode = toolsArr.addObject();
            tNode.put("name", tool.name());
            tNode.put("description", StringUtils.defaultString(tool.description()));
            JsonNode params = tool.parameters() != null && tool.parameters().isObject()
                ? tool.parameters()
                : mapper.createObjectNode();
            tNode.set("parameters", params);
            activeToolSchemas.put(tool.name(), prettyJson(mapper, params));
        }

        ObjectNode sysCell = mapper.createObjectNode();
        sysCell.put("index", nextRecordIndex[0]++);
        sysCell.put("recordId", "system\u0000" + snap.sha256().substring(0, 12)
            + "\u0000" + sysCell.path("index").asInt());
        sysCell.put("kind", "system");
        sysCell.put("text", snap.tools().size() + " tools");
        sysCell.set("promptDetail", promptDetail);
        if (previousPromptSnapshot[0] != null) {
            sysCell.set("previousPromptDetail", previousPromptSnapshot[0]);
        }
        sysCell.putNull("timeSeconds");
        if (trace.startedAt() > 0) {
            sysCell.put("startedAt", trace.startedAt());
        }
        previousPromptSnapshot[0] = promptDetail;

        ObjectNode sysGroup = mapper.createObjectNode();
        sysGroup.put("kind", "message");
        sysGroup.putArray("cells").add(sysCell);
        targetSection.groups.add(sysGroup);
    }

    private static ObjectNode buildAssistantRequestNumber(
            ObjectMapper mapper,
            int number,
            long turn,
            long step,
            LlmRequestTraceRecord trace,
            String fallbackModel,
            Long startedAt,
            Long completedAt,
            boolean messageIsError,
            Usage stepUsage,
            CumulativeUsage runningTotals) {
        ObjectNode req = mapper.createObjectNode();
        req.put("purpose", "assistant");
        req.put("seq", number);
        req.put("turn", turn);
        req.put("step", step);
        req.put("number", number);

        String status = "complete";
        if (trace != null && Strings.CS.equals("failed", trace.status())) {
            status = "error";
        } else if (messageIsError) {
            status = "error";
        }
        req.put("status", status);

        if (startedAt != null) req.put("startedAt", startedAt);
        if (completedAt != null) req.put("completedAt", completedAt);

        String effectiveModel = trace != null && StringUtils.isNotBlank(trace.respondedModel())
            ? trace.respondedModel()
            : fallbackModel;
        String provider = trace != null && StringUtils.isNotBlank(trace.providerLabel())
            ? trace.providerLabel()
            : inferProtocolProviderFromModel(effectiveModel);
        if (StringUtils.isNotBlank(provider)) req.put("provider", provider);
        if (StringUtils.isNotBlank(effectiveModel)) req.put("model", effectiveModel);

        if (trace != null) {
            if (trace.retry() > 0) req.put("retry", trace.retry());
            if (trace.maxRetries() > 0) req.put("maxRetries", trace.maxRetries());
            if (trace.retryDelayMs() > 0) req.put("retryDelayMs", trace.retryDelayMs());
            if (StringUtils.isNotBlank(trace.error())) req.put("error", trace.error());
            if (StringUtils.isNotBlank(trace.errorCode())) req.put("errorCode", trace.errorCode());
            if (StringUtils.isNotBlank(trace.optionsJson())) {
                try {
                    JsonNode parsedOpts = mapper.readTree(trace.optionsJson());
                    if (parsedOpts != null && parsedOpts.isObject()) {
                        req.set("requestConfig", parsedOpts);
                    }
                } catch (IOException _) {
                    // Ignore malformed options JSON.
                }
            }
        }
        if (!req.has("requestConfig") && StringUtils.isNotBlank(effectiveModel)) {
            ObjectNode inferredCfg = mapper.createObjectNode();
            if (StringUtils.isNotBlank(provider)) inferredCfg.put("provider", provider);
            inferredCfg.put("model", effectiveModel);
            req.set("requestConfig", inferredCfg);
        }

        if (stepUsage != null) {
            req.set("usage", toTrajectoryUsageNode(mapper, stepUsage));
            runningTotals.add(stepUsage);
            req.set("cumulativeUsage", runningTotals.toNode(mapper));
        } else if (runningTotals.hasAny) {
            req.set("cumulativeUsage", runningTotals.toNode(mapper));
        }
        return req;
    }

    private static ObjectNode buildCompactionRequestNumber(
            ObjectMapper mapper,
            int number,
            int seq,
            Long turn,
            LlmRequestTraceRecord trace,
            CumulativeUsage runningTotals) {
        ObjectNode req = mapper.createObjectNode();
        req.put("purpose", "compaction");
        req.put("seq", seq);
        if (turn != null) {
            req.put("turn", turn);
        } else {
            req.putNull("turn");
        }
        req.put("step", 0);
        req.put("number", number);
        req.put("status", trace != null && Strings.CS.equals("failed", trace.status())
            ? "error" : "complete");
        if (trace != null) {
            if (trace.startedAt() > 0) req.put("startedAt", trace.startedAt());
            if (trace.completedAt() > 0) req.put("completedAt", trace.completedAt());
            if (StringUtils.isNotBlank(trace.providerLabel())) {
                req.put("provider", trace.providerLabel());
            }
            if (StringUtils.isNotBlank(trace.respondedModel())) {
                req.put("model", trace.respondedModel());
            }
            if (StringUtils.isNotBlank(trace.optionsJson())) {
                try {
                    JsonNode parsed = mapper.readTree(trace.optionsJson());
                    if (parsed != null && parsed.isObject()) req.set("requestConfig", parsed);
                } catch (IOException _) {
                    // Ignore.
                }
            }
        }
        if (runningTotals.hasAny) {
            req.set("cumulativeUsage", runningTotals.toNode(mapper));
        }
        return req;
    }

    private static String inferProtocolProviderFromModel(String model) {
        if (StringUtils.isBlank(model)) return null;
        String m = model.toLowerCase(Locale.ROOT);
        if (Strings.CS.startsWith(m, "claude-")) return "anthropic";
        if (Strings.CS.startsWith(m, "gemini-")) return "gemini";
        if (Strings.CS.startsWith(m, "gpt-5") || Strings.CS.startsWith(m, "o1")
                || Strings.CS.startsWith(m, "o3") || Strings.CS.startsWith(m, "o4")
                || Strings.CS.contains(m, "codex")) {
            return "responses";
        }
        return "chat";
    }

    private static ObjectNode toTrajectoryUsageNode(ObjectMapper mapper, Usage u) {
        ObjectNode node = mapper.createObjectNode();
        node.put("input", u.inputTokens());
        node.put("cacheRead", u.cacheReadInputTokens());
        node.put("cacheWrite", u.cacheCreationInputTokens());
        node.put("output", u.outputTokens());
        if (u.reasoningTokens() != null && u.reasoningTokens() > 0) {
            node.put("reasoning", u.reasoningTokens());
        }
        return node;
    }

    private static final class CumulativeUsage {
        long input;
        long cacheRead;
        long cacheWrite;
        long output;
        long reasoning;
        boolean hasReasoning;
        boolean hasAny;

        void add(Usage u) {
            this.input += u.inputTokens();
            this.cacheRead += u.cacheReadInputTokens();
            this.cacheWrite += u.cacheCreationInputTokens();
            this.output += u.outputTokens();
            if (u.reasoningTokens() != null && u.reasoningTokens() > 0) {
                this.reasoning += u.reasoningTokens();
                this.hasReasoning = true;
            }
            this.hasAny = true;
        }

        ObjectNode toNode(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("input", input);
            node.put("cacheRead", cacheRead);
            node.put("cacheWrite", cacheWrite);
            node.put("output", output);
            if (hasReasoning) {
                node.put("reasoning", reasoning);
            }
            return node;
        }
    }

    private static final class ProjectedSection {
        final Long turn;
        final List<ObjectNode> groups = new ArrayList<>();

        ProjectedSection(Long turn) {
            this.turn = turn;
        }
    }

    private static long computeMinIncludedTurn(
            List<ProjectedSection> eligible, int limit, String includeCallId) {
        List<Long> turnNumbers = new ArrayList<>();
        Long callTurn = null;
        for (ProjectedSection sec : eligible) {
            if (sec.turn != null) {
                turnNumbers.add(sec.turn);
                if (StringUtils.isNotBlank(includeCallId) && sectionContainsCallId(sec, includeCallId)) {
                    callTurn = sec.turn;
                }
            }
        }
        if (turnNumbers.isEmpty()) return 1L;
        int startIdx = Math.max(0, turnNumbers.size() - limit);
        long minTurn = turnNumbers.get(startIdx);
        if (callTurn != null && callTurn < minTurn) {
            minTurn = callTurn;
        }
        return minTurn;
    }

    private static boolean sectionContainsCallId(ProjectedSection sec, String callId) {
        for (ObjectNode group : sec.groups) {
            JsonNode cells = group.get("cells");
            if (cells == null || !cells.isArray()) continue;
            for (JsonNode cell : cells) {
                if (Strings.CS.equals(callId, cell.path("callId").asText(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static LlmRequestTraceRecord pollMatchingCompactionTrace(
            List<LlmRequestTraceRecord> compactionTraces, long currentTurn) {
        if (compactionTraces.isEmpty()) return null;
        for (int idx = 0; idx < compactionTraces.size(); idx++) {
            LlmRequestTraceRecord c = compactionTraces.get(idx);
            if (c.turn() == currentTurn || c.turn() == 0) {
                return compactionTraces.remove(idx);
            }
        }
        return compactionTraces.removeFirst();
    }

    private static void appendToMessageOrNewGroup(
            ObjectMapper mapper, ProjectedSection section, ObjectNode cell) {
        if (!section.groups.isEmpty()
                && Strings.CS.equals("message", section.groups.getLast().path("kind").asText())) {
            ((ArrayNode) section.groups.getLast().get("cells")).add(cell);
            return;
        }
        ObjectNode grp = mapper.createObjectNode();
        grp.put("kind", "message");
        grp.putArray("cells").add(cell);
        section.groups.add(grp);
    }

    private static Map<String, ToolOutcome> collectToolOutcomes(
            List<Message> messages, Map<String, Long> timestampsByUuid) {
        Map<String, ToolOutcome> out = new HashMap<>();
        for (Message msg : messages) {
            if (!(msg instanceof UserMessage user)
                    || user.message() == null
                    || user.message().blocks() == null) {
                continue;
            }
            Long ts = messageTimestamp(user.uuid(), user.timestampValue(), timestampsByUuid);
            for (ContentBlock block : user.message().blocks()) {
                if (block instanceof ToolResultBlock tr && StringUtils.isNotBlank(tr.toolUseId())) {
                    out.put(tr.toolUseId(), new ToolOutcome(tr, user.toolUseResult(), ts));
                }
            }
        }
        return out;
    }

    private static boolean isPureToolResultUser(UserMessage user) {
        MessageContent mc = user.message();
        if (mc == null || StringUtils.isNotBlank(mc.text())) return false;
        List<ContentBlock> blocks = mc.blocks();
        if (blocks == null || blocks.isEmpty()) return false;
        boolean sawToolResult = false;
        for (ContentBlock b : blocks) {
            if (b instanceof ToolResultBlock) {
                sawToolResult = true;
            } else if (b instanceof TextBlock(String t) && StringUtils.isNotBlank(t)) {
                return false;
            } else if (b instanceof ImageBlock) {
                return false;
            }
        }
        return sawToolResult;
    }

    private static String extractUserText(UserMessage user) {
        MessageContent mc = user.message();
        if (mc == null) return "";
        if (StringUtils.isNotEmpty(mc.text())) return mc.text();
        if (mc.blocks() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : mc.blocks()) {
            if (b instanceof TextBlock(String t) && StringUtils.isNotEmpty(t)) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private static String extractToolResultText(ToolResultBlock block) {
        if (block.content() == null || block.content().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock part : block.content()) {
            if (part instanceof TextBlock(String t) && t != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private static String extractSubagentId(ToolOutcome outcome) {
        if (outcome == null) return null;
        if (outcome.toolUseResult() != null) {
            try {
                JsonNode node = JsonUtils.getMapper().valueToTree(outcome.toolUseResult());
                String id = node.path("agentId").asText(null);
                if (StringUtils.isNotBlank(id)) return id;
            } catch (RuntimeException _) {
                // Fall through to text regex.
            }
        }
        if (outcome.result() != null) {
            String text = extractToolResultText(outcome.result());
            Matcher m = AGENT_ID_TEXT_PATTERN.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String formatSubagentLabel(
            JsonNode input, TrajectoryTranscriptReader.SubagentSummary summary) {
        String subType = input != null ? input.path("subagent_type").asText(null) : null;
        if (StringUtils.isBlank(subType) && summary != null) {
            subType = summary.agentType();
        }
        String desc = input != null ? input.path("description").asText(null) : null;
        if (StringUtils.isNotBlank(subType) && StringUtils.isNotBlank(desc)) {
            return subType + " \u00b7 " + desc;
        }
        if (StringUtils.isNotBlank(desc)) return desc;
        if (StringUtils.isNotBlank(subType)) return subType;
        return summary != null ? "agent-" + summary.agentId() : "Subagent";
    }

    private static String formatToolCallSummary(String toolName, JsonNode input) {
        String name = StringUtils.defaultIfBlank(toolName, "Tool");
        if (input == null || !input.isObject() || input.isEmpty()) {
            return name;
        }
        String argSummary = firstNonBlankField(input,
            "file_path", "path", "command", "pattern", "query", "url",
            "description", "prompt", "skill");
        if (StringUtils.isBlank(argSummary)) {
            var fields = input.properties().iterator();
            if (fields.hasNext()) {
                var entry = fields.next();
                JsonNode val = entry.getValue();
                argSummary = entry.getKey() + "="
                    + (val.isValueNode() ? val.asText() : val.toString());
            }
        }
        if (StringUtils.isBlank(argSummary)) {
            return name;
        }
        return name + " \u00b7 " + firstLineSummary(argSummary, 96);
    }

    private static String firstNonBlankField(JsonNode obj, String... keys) {
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v != null && v.isTextual() && StringUtils.isNotBlank(v.asText())) {
                return v.asText();
            }
        }
        return null;
    }

    private static String firstLineSummary(String raw, int maxChars) {
        if (StringUtils.isBlank(raw)) return "";
        String singleLine = raw.strip().replace('\r', ' ').replace('\n', ' ');
        if (singleLine.length() <= maxChars) return singleLine;
        return singleLine.substring(0, maxChars) + "…";
    }

    private static String truncateDetail(String raw) {
        if (raw == null) return "";
        if (raw.length() <= MAX_DETAIL_CHARS) return raw;
        return raw.substring(0, MAX_DETAIL_CHARS) + TRUNCATION_SUFFIX;
    }

    private static String prettyJson(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException _) {
            return node.toString();
        }
    }

    private static Long messageTimestamp(
            String uuid, Instant msgInstant, Map<String, Long> timestampsByUuid) {
        if (uuid != null && timestampsByUuid.containsKey(uuid)) {
            return timestampsByUuid.get(uuid);
        }
        return msgInstant != null ? msgInstant.toEpochMilli() : null;
    }

    private static void putNullableLong(ObjectNode obj, String key, Long val) {
        if (val != null) {
            obj.put(key, val);
        } else {
            obj.putNull(key);
        }
    }

    private static StepMetrics stepMetrics(Map<String, StepMetrics> map, long turn, long step) {
        return map.computeIfAbsent(stepKey(turn, step), _ -> new StepMetrics());
    }

    private static String stepKey(long turn, long step) {
        return turn + ":" + step;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (StringUtils.isBlank(rawQuery)) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.put(k, v);
            }
        }
        return out;
    }

    private static long parseLongParam(String val, long fallback) {
        if (StringUtils.isBlank(val)) return fallback;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException _) {
            return fallback;
        }
    }
}
