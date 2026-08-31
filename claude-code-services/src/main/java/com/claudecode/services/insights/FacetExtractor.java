package com.claudecode.services.insights;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.services.cost.ApiCallAccounting;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Extracts per-session {@link SessionFacets} from a raw transcript via a non-streaming LLM call,
 * chunk-summarizing over-long transcripts first.
 */
public final class FacetExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(FacetExtractor.class);


    static final String FACET_EXTRACTION_PROMPT = """
        Analyze this Claude Code session and extract structured facets.

        CRITICAL GUIDELINES:

        1. **goal_categories**: Count ONLY what the USER explicitly asked for.
           - DO NOT count Claude's autonomous codebase exploration
           - DO NOT count work Claude decided to do on its own
           - ONLY count when user says "can you...", "please...", "I need...", "let's..."

        2. **user_satisfaction_counts**: Base ONLY on explicit user signals.
           - "Yay!", "great!", "perfect!" → happy
           - "thanks", "looks good", "that works" → satisfied
           - "ok, now let's..." (continuing without complaint) → likely_satisfied
           - "that's not right", "try again" → dissatisfied
           - "this is broken", "I give up" → frustrated

        3. **friction_counts**: Be specific about what went wrong.
           - misunderstood_request: Claude interpreted incorrectly
           - wrong_approach: Right goal, wrong solution method
           - buggy_code: Code didn't work correctly
           - user_rejected_action: User said no/stop to a tool call
           - excessive_changes: Over-engineered or changed too much

        4. If very short or just warmup, use warmup_minimal for goal_category

        SESSION:
        """;


    static final String FACET_JSON_SCHEMA_SUFFIX = "\n\n" + """
        RESPOND WITH ONLY A VALID JSON OBJECT matching this schema:
        {
          "underlying_goal": "What the user fundamentally wanted to achieve",
          "goal_categories": {"category_name": count, ...},
          "outcome": "fully_achieved|mostly_achieved|partially_achieved|not_achieved|unclear_from_transcript",
          "user_satisfaction_counts": {"level": count, ...},
          "claude_helpfulness": "unhelpful|slightly_helpful|moderately_helpful|very_helpful|essential",
          "session_type": "single_task|multi_task|iterative_refinement|exploration|quick_question",
          "friction_counts": {"friction_type": count, ...},
          "friction_detail": "One sentence describing friction or empty",
          "primary_success": "none|fast_accurate_search|correct_code_edits|good_explanations|proactive_help|multi_file_changes|good_debugging",
          "brief_summary": "One sentence: what user wanted and whether they got it"
        }\
        """;


    static final String SUMMARIZE_CHUNK_PROMPT = """
        Summarize this portion of a Claude Code session transcript. Focus on:
        1. What the user asked for
        2. What Claude did (tools used, files modified)
        3. Any friction or issues
        4. The outcome

        Keep it concise - 3-5 sentences. Preserve specific details like file names, error messages, and user feedback.

        TRANSCRIPT CHUNK:
        """;


    private static final int SUMMARIZATION_THRESHOLD = 30_000;

    private static final int CHUNK_SIZE = 25_000;

    private final LlmClient llmClient;
    private final Supplier<String> modelSupplier;


    public FacetExtractor(LlmClient llmClient, Supplier<String> modelSupplier) {
        this.llmClient = llmClient;
        this.modelSupplier = modelSupplier;
    }


    public SessionFacets extractFacets(SessionLog log, String sessionId) {
        try {
            String transcript = formatTranscriptWithSummarization(log);
            String jsonPrompt = FACET_EXTRACTION_PROMPT + transcript + FACET_JSON_SCHEMA_SUFFIX;

            ApiMessage response = ApiCallAccounting.createMessage(
                llmClient, request(jsonPrompt, 4096));
            String text = extractTextContent(response);

            String json = extractJsonObject(text);
            if (json == null) return null;

            SessionFacets parsed = JsonUtils.getMapper().readValue(json, SessionFacets.class);
            if (parsed == null || !parsed.isValid()) return null;
            return new SessionFacets(
                sessionId,
                parsed.underlyingGoal(),
                parsed.goalCategories(),
                parsed.outcome(),
                parsed.userSatisfactionCounts(),
                parsed.claudeHelpfulness(),
                parsed.sessionType(),
                parsed.frictionCounts(),
                parsed.frictionDetail(),
                parsed.primarySuccess(),
                parsed.briefSummary(),
                parsed.userInstructionsToClaude());
        } catch (Exception e) {
            LOG.warn("Facet extraction failed: {}", e.getMessage());
            return null;
        }
    }


    static String formatTranscriptForFacets(SessionLog log) {
        List<String> lines = new ArrayList<>();
        TranscriptHeader h = headerOf(log);

        lines.add("Session: " + h.shortId());
        lines.add("Date: " + h.startTime());
        lines.add("Project: " + h.projectPath());
        lines.add("Duration: " + h.durationMinutes() + " min");
        lines.add("");

        for (JsonNode msg : log.messages()) {
            String type = msg.path("type").asText();
            JsonNode message = msg.get("message");
            if (message == null || message.isNull()) continue;
            JsonNode content = message.get("content");
            if (content == null) continue;

            if (Strings.CS.equals("user", type)) {
                if (content.isTextual()) {
                    lines.add("[User]: " + slice(content.asText(), 500));
                } else if (content.isArray()) {
                    for (JsonNode block : content) {
                        if (Strings.CS.equals("text", block.path("type").asText()) && block.has("text")) {
                            lines.add("[User]: " + slice(block.get("text").asText(), 500));
                        }
                    }
                }
            } else if (Strings.CS.equals("assistant", type) && content.isArray()) {
                for (JsonNode block : content) {
                    String blockType = block.path("type").asText();
                    if (Strings.CS.equals("text", blockType) && block.has("text")) {
                        lines.add("[Assistant]: " + slice(block.get("text").asText(), 300));
                    } else if (Strings.CS.equals("tool_use", blockType) && block.has("name")) {
                        lines.add("[Tool: " + block.get("name").asText() + "]");
                    }
                }
            }
        }

        return String.join("\n", lines);
    }


    String formatTranscriptWithSummarization(SessionLog log) {
        String fullTranscript = formatTranscriptForFacets(log);
        if (fullTranscript.length() <= SUMMARIZATION_THRESHOLD) {
            return fullTranscript;
        }

        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < fullTranscript.length(); i += CHUNK_SIZE) {
            chunks.add(fullTranscript.substring(i, Math.min(fullTranscript.length(), i + CHUNK_SIZE)));
        }

        List<String> summaries = new ArrayList<>(chunks.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>(chunks.size());
            for (String chunk : chunks) {
                futures.add(executor.submit(() -> summarizeTranscriptChunk(chunk)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    summaries.add(futures.get(i).get());
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    summaries.add(slice(chunks.get(i), 2000));
                } catch (Exception _) {
                    summaries.add(slice(chunks.get(i), 2000));
                }
            }
        }

        TranscriptHeader h = headerOf(log);
        String header = String.join("\n", List.of(
            "Session: " + h.shortId(),
            "Date: " + h.startTime(),
            "Project: " + h.projectPath(),
            "Duration: " + h.durationMinutes() + " min",
            "[Long session - " + chunks.size() + " parts summarized]",
            ""));

        return header + String.join("\n\n---\n\n", summaries);
    }


    String summarizeTranscriptChunk(String chunk) {
        try {
            ApiMessage response = ApiCallAccounting.createMessage(
                llmClient, request(SUMMARIZE_CHUNK_PROMPT + chunk, 500));
            String text = extractTextContent(response);
            return text.isEmpty() ? slice(chunk, 2000) : text;
        } catch (Exception _) {
            return slice(chunk, 2000);
        }
    }


    static String extractTextContent(ApiMessage response) {
        if (response == null || response.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            if (block instanceof TextBlock(String text) && text != null) {
                sb.append(text);
            }
        }
        return sb.toString();
    }


    static String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        return text.substring(start, end + 1);
    }

    private CreateMessageRequest request(String userPrompt, int maxTokens) {
        return CreateMessageRequest.builder()
            .model(modelSupplier.get())
            .maxTokens(maxTokens)
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", userPrompt)))
            .stream(false)
            .querySource("insights_facet")
            .build();
    }

    private static String slice(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }


    private record TranscriptHeader(String shortId, String startTime, String projectPath, long durationMinutes) {}

    private static TranscriptHeader headerOf(SessionLog log) {
        String sessionId = log.sessionId() != null ? log.sessionId() : "unknown";

        Instant created = null;
        Instant modified = null;
        String projectPath = "";
        List<JsonNode> messages = log.messages();
        if (messages != null && !messages.isEmpty()) {
            JsonNode first = messages.getFirst();
            JsonNode last = messages.getLast();
            created = parseInstant(first.path("timestamp").asText(null));
            modified = parseInstant(last.path("timestamp").asText(null));
            projectPath = first.path("cwd").asText("");
        }

        String startTime = created != null ? FormatUtils.formatInstantIso(created) : "";
        long durationMinutes = created != null && modified != null
            ? Math.round((modified.toEpochMilli() - created.toEpochMilli()) / 1000.0 / 60.0)
            : 0;
        return new TranscriptHeader(slice(sessionId, 8), startTime, projectPath, durationMinutes);
    }

    private static Instant parseInstant(String timestamp) {
        if (StringUtils.isEmpty(timestamp)) return null;
        try {
            return Instant.parse(timestamp);
        } catch (Exception _) {
            try {
                return OffsetDateTime.parse(timestamp).toInstant();
            } catch (Exception _) {
                return null;
            }
        }
    }
}
