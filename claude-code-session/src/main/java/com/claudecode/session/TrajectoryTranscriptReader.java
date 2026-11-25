package com.claudecode.session;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.metrics.LlmRequestTraceRecord;
import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * On-the-fly reader projecting main or sidechain {@code .jsonl} transcripts (plus optional
 * {@code .metrics.jsonl} sidecar and {@code subagents/agent-*.jsonl} summaries) for Trajectory.
 */
@Explanation("Projects main and subagent JSONL transcripts + telemetry rows for WebUI Trajectory")
public final class TrajectoryTranscriptReader {

    private final SessionStorage storage;

    public record SubtoolPreview(
            String callId,
            String toolName,
            JsonNode args,
            String output,
            boolean isError,
            Long startedAt,
            Long completedAt) {}

    public record SubagentSummary(
            String agentId,
            String agentType,
            String model,
            int stepCount,
            int toolCount,
            List<SubtoolPreview> subtools) {}

    public record TrajectoryTranscript(
            List<Message> messages,
            List<SessionMetricsEvent> metricsEvents,
            List<LlmRequestTraceRecord> requestTraces,
            Map<String, Long> timestampsByUuid,
            Map<String, SubagentSummary> subagents) {}

    public TrajectoryTranscriptReader() {
        this(new SessionStorage());
    }

    public TrajectoryTranscriptReader(SessionStorage storage) {
        this.storage = storage != null ? storage : new SessionStorage();
    }

    /** Reads a main session transcript (optionally overriding message list with live engine messages). */
    public TrajectoryTranscript readSession(Path sessionFile, List<Message> liveMessagesOverride) {
        List<Message> messages = liveMessagesOverride;
        if (messages == null || messages.isEmpty()) {
            messages = sessionFile != null && Files.isRegularFile(sessionFile)
                ? storage.loadTranscriptFromFile(sessionFile).messages()
                : List.of();
        }
        RawTelemetry telemetry = sessionFile != null && Files.isRegularFile(sessionFile)
            ? readRawTelemetry(sessionFile)
            : RawTelemetry.EMPTY;
        Map<String, SubagentSummary> subagents = sessionFile != null
            ? scanSubagentSummaries(subagentsDirForMainSession(sessionFile))
            : Map.of();
        return new TrajectoryTranscript(
            List.copyOf(messages),
            telemetry.metricsEvents(),
            telemetry.requestTraces(),
            telemetry.timestampsByUuid(),
            subagents);
    }

    /** Reads a subagent's sidechain transcript ({@code agent-<agentId>.jsonl}) for drill-down. */
    public Optional<TrajectoryTranscript> readAgent(
            Path agentFile, String agentId, List<Message> liveMessagesOverride) {
        List<Message> messages = liveMessagesOverride;
        if (messages == null || messages.isEmpty()) {
            if (agentFile == null || !Files.isRegularFile(agentFile)) {
                return Optional.empty();
            }
            Optional<TranscriptLoader.AgentTranscript> loaded =
                storage.getAgentTranscript(agentFile, agentId);
            if (loaded.isEmpty()) {
                return Optional.empty();
            }
            messages = loaded.get().messages();
        }
        RawTelemetry telemetry = agentFile != null && Files.isRegularFile(agentFile)
            ? readRawTelemetry(agentFile)
            : RawTelemetry.EMPTY;
        // Nested depth-2 subagents inside the same subagents/ directory, if any.
        Map<String, SubagentSummary> siblingOrChildSubagents = agentFile != null
            ? scanSubagentSummaries(agentFile.getParent())
            : Map.of();
        return Optional.of(new TrajectoryTranscript(
            List.copyOf(messages),
            telemetry.metricsEvents(),
            telemetry.requestTraces(),
            telemetry.timestampsByUuid(),
            siblingOrChildSubagents));
    }

    private static Path subagentsDirForMainSession(Path sessionFile) {
        String fileName = sessionFile.getFileName().toString();
        if (!Strings.CS.endsWith(fileName, ".jsonl")) {
            return null;
        }
        String sessionId = fileName.substring(0, fileName.length() - ".jsonl".length());
        return sessionFile.resolveSibling(sessionId).resolve("subagents");
    }

    private Map<String, SubagentSummary> scanSubagentSummaries(Path subagentsDir) {
        if (subagentsDir == null || !Files.isDirectory(subagentsDir)) {
            return Map.of();
        }
        Map<String, SubagentSummary> out = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(subagentsDir, "agent-*.jsonl")) {
            for (Path agentFile : stream) {
                String name = agentFile.getFileName().toString();
                if (Strings.CS.endsWith(name, ".metrics.jsonl")) continue;
                String agentId = name.substring("agent-".length(), name.length() - ".jsonl".length());
                if (StringUtils.isBlank(agentId)) continue;
                summarizeSubagent(agentFile, agentId).ifPresent(summary -> out.put(agentId, summary));
            }
        } catch (IOException _) {
            // Best-effort subagent scan.
        }
        return Map.copyOf(out);
    }

    private Optional<SubagentSummary> summarizeSubagent(Path agentFile, String agentId) {
        try {
            Optional<TranscriptLoader.AgentTranscript> loaded =
                storage.getAgentTranscript(agentFile, agentId);
            if (loaded.isEmpty()) {
                return Optional.empty();
            }
            List<Message> messages = loaded.get().messages();
            RawTelemetry telemetry = readRawTelemetry(agentFile);
            String agentType = storage.readAgentMetadata(agentFile)
                .map(AgentMetadata::agentType)
                .orElse(null);

            Map<String, Long> toolStartByCallId = new HashMap<>();
            Map<String, Long> toolEndByCallId = new HashMap<>();
            for (SessionMetricsEvent ev : telemetry.metricsEvents()) {
                if (ev.callId() == null) continue;
                if (ev.kind() == SessionMetricsEvent.Kind.TOOL_CALL) {
                    toolStartByCallId.putIfAbsent(ev.callId(), ev.time());
                } else if (ev.kind() == SessionMetricsEvent.Kind.TOOL_RESULT) {
                    toolEndByCallId.put(ev.callId(), ev.time());
                }
            }

            Map<String, ToolResultBlock> resultsByCallId = new HashMap<>();
            for (Message msg : messages) {
                if (msg instanceof UserMessage user
                        && user.message() != null
                        && user.message().blocks() != null) {
                    for (ContentBlock block : user.message().blocks()) {
                        if (block instanceof ToolResultBlock tr && tr.toolUseId() != null) {
                            resultsByCallId.put(tr.toolUseId(), tr);
                        }
                    }
                }
            }

            String model = null;
            int stepCount = 0;
            String lastApiMessageId = null;
            List<SubtoolPreview> subtools = new ArrayList<>();

            for (Message msg : messages) {
                if (!(msg instanceof AssistantMessage asst) || asst.message() == null) {
                    continue;
                }
                if (StringUtils.isNotBlank(asst.message().model())) {
                    model = asst.message().model();
                }
                String apiMsgId = asst.message().id();
                if (apiMsgId != null) {
                    if (!Strings.CS.equals(apiMsgId, lastApiMessageId)) {
                        stepCount++;
                        lastApiMessageId = apiMsgId;
                    }
                } else {
                    stepCount++;
                }
                if (asst.message().content() == null) continue;
                for (ContentBlock block : asst.message().content()) {
                    if (block instanceof ToolUseBlock tub) {
                        ToolResultBlock result = resultsByCallId.get(tub.id());
                        String outText = result != null ? extractToolResultText(result) : null;
                        boolean isError = result != null && result.isError();
                        subtools.add(new SubtoolPreview(
                            tub.id(),
                            tub.name(),
                            tub.input(),
                            outText,
                            isError,
                            toolStartByCallId.get(tub.id()),
                            toolEndByCallId.get(tub.id())));
                    }
                }
            }

            return Optional.of(new SubagentSummary(
                agentId,
                agentType,
                model,
                stepCount,
                subtools.size(),
                List.copyOf(subtools)));
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    private static String extractToolResultText(ToolResultBlock block) {
        if (block.content() == null || block.content().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock part : block.content()) {
            if (part instanceof TextBlock(String text) && text != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private record RawTelemetry(
            List<SessionMetricsEvent> metricsEvents,
            List<LlmRequestTraceRecord> requestTraces,
            Map<String, Long> timestampsByUuid) {
        static final RawTelemetry EMPTY = new RawTelemetry(List.of(), List.of(), Map.of());
    }

    private RawTelemetry readRawTelemetry(Path transcriptFile) {
        List<SessionMetricsEvent> metrics = storage.readSessionMetrics(transcriptFile);
        List<LlmRequestTraceRecord> traces = new ArrayList<>();
        Map<String, Long> timestamps = new HashMap<>();

        Path sidecar = SessionMetricsFiles.sidecar(transcriptFile);
        boolean useSidecarForTraces = Files.isRegularFile(sidecar);

        try (var reader = Files.newBufferedReader(transcriptFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (!useSidecarForTraces
                        && Strings.CS.contains(trimmed, "\"java-llm-request\"")) {
                    LlmRequestTraceRecord rec = parseTraceRow(trimmed);
                    if (rec != null) traces.add(rec);
                    continue;
                }
                if (Strings.CS.contains(trimmed, "\"uuid\"")
                        && Strings.CS.contains(trimmed, "\"timestamp\"")) {
                    parseUuidTimestamp(trimmed, timestamps);
                }
            }
        } catch (IOException _) {
            // Best-effort scan.
        }

        if (useSidecarForTraces) {
            try (var reader = Files.newBufferedReader(sidecar, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (Strings.CS.contains(trimmed, "\"java-llm-request\"")) {
                        LlmRequestTraceRecord rec = parseTraceRow(trimmed);
                        if (rec != null) traces.add(rec);
                    }
                }
            } catch (IOException _) {
                // Best-effort sidecar scan.
            }
        }

        return new RawTelemetry(metrics, List.copyOf(traces), Map.copyOf(timestamps));
    }

    private static LlmRequestTraceRecord parseTraceRow(String line) {
        try {
            JsonNode node = JsonUtils.getMapper().readTree(line);
            if (node == null || !node.isObject()
                    || !Strings.CS.equals(
                        LlmRequestTraceRecord.TRANSCRIPT_TYPE, node.path("type").asText())) {
                return null;
            }
            return new LlmRequestTraceRecord(
                node.path("schemaVersion").asInt(1),
                node.path("sessionId").asText(null),
                node.path("turn").asLong(0),
                node.path("step").asLong(0),
                node.path("purpose").asText("assistant"),
                node.path("startedAt").asLong(0),
                node.path("completedAt").asLong(0),
                node.hasNonNull("configuredProtocol") ? node.get("configuredProtocol").asText() : null,
                node.hasNonNull("effectiveProtocol") ? node.get("effectiveProtocol").asText() : null,
                node.hasNonNull("providerLabel") ? node.get("providerLabel").asText() : null,
                node.hasNonNull("requestedModel") ? node.get("requestedModel").asText() : null,
                node.hasNonNull("respondedModel") ? node.get("respondedModel").asText() : null,
                node.hasNonNull("headerSha256") ? node.get("headerSha256").asText() : null,
                node.hasNonNull("optionsJson") ? node.get("optionsJson").asText() : null,
                node.hasNonNull("attemptsJson") ? node.get("attemptsJson").asText() : null,
                node.path("retry").asInt(0),
                node.path("maxRetries").asInt(0),
                node.path("retryDelayMs").asLong(0),
                node.path("status").asText("completed"),
                node.hasNonNull("error") ? node.get("error").asText() : null,
                node.hasNonNull("errorCode") ? node.get("errorCode").asText() : null);
        } catch (IOException _) {
            return null;
        }
    }

    private static void parseUuidTimestamp(String line, Map<String, Long> out) {
        try {
            JsonNode node = JsonUtils.getMapper().readTree(line);
            if (node == null || !node.isObject()) return;
            String uuid = node.path("uuid").asText(null);
            String ts = node.path("timestamp").asText(null);
            if (StringUtils.isNotBlank(uuid) && StringUtils.isNotBlank(ts)) {
                out.putIfAbsent(uuid, Instant.parse(ts).toEpochMilli());
            }
        } catch (Exception _) {
            // Ignore non-ISO or malformed rows.
        }
    }
}
