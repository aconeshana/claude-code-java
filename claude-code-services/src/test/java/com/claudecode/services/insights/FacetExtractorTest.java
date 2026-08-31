package com.claudecode.services.insights;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacetExtractorTest {

    private static final ObjectMapper M = JsonUtils.getMapper();

    private static final String VALID_FACETS_JSON = """
        {
          "underlying_goal": "Fix the login bug",
          "goal_categories": {"fix_bug": 2},
          "outcome": "fully_achieved",
          "user_satisfaction_counts": {"satisfied": 1},
          "claude_helpfulness": "very_helpful",
          "session_type": "single_task",
          "friction_counts": {},
          "friction_detail": "",
          "primary_success": "correct_code_edits",
          "brief_summary": "User wanted a bug fixed and got it"
        }""";

    private static ObjectNode userEntry(String text, String timestamp, String cwd) {
        ObjectNode entry = M.createObjectNode();
        entry.put("type", "user");
        entry.put("timestamp", timestamp);
        entry.put("cwd", cwd);
        entry.putObject("message").put("role", "user").put("content", text);
        return entry;
    }

    private static ObjectNode userBlockEntry(String text, String timestamp) {
        ObjectNode entry = M.createObjectNode();
        entry.put("type", "user");
        entry.put("timestamp", timestamp);
        ObjectNode message = entry.putObject("message");
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        content.addObject().put("type", "text").put("text", text);
        return entry;
    }

    private static ObjectNode assistantEntry(String text, String toolName, String timestamp) {
        ObjectNode entry = M.createObjectNode();
        entry.put("type", "assistant");
        entry.put("timestamp", timestamp);
        ObjectNode message = entry.putObject("message");
        message.put("role", "assistant");
        ArrayNode content = message.putArray("content");
        if (text != null) {
            content.addObject().put("type", "text").put("text", text);
        }
        if (toolName != null) {
            content.addObject().put("type", "tool_use").put("name", toolName);
        }
        return entry;
    }

    private static SessionLog simpleLog() {
        return new SessionLog("abcdef1234", List.of(
            userEntry("hello", "2026-07-01T10:00:00.000Z", "/tmp/proj"),
            assistantEntry("hi", "Bash", "2026-07-01T10:05:00.000Z"),
            userBlockEntry("block text", "2026-07-01T10:30:00.000Z")));
    }

    @Test
    void successfulInsightsRequestsContributeToReleasedGlobalApiAccounting() {
        SessionCostState costs = SessionCostState.get();
        costs.reset();
        LlmClient client = new LlmClient() {
            @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
                throw new UnsupportedOperationException();
            }
            @Override public ApiMessage createMessage(CreateMessageRequest request) {
                LockSupport.parkNanos(3_000_000L);
                return ApiMessage.builder()
                    .model("served-opus")
                    .content(List.of(new TextBlock(VALID_FACETS_JSON)))
                    .usage(new Usage(40, 10, 0, 0))
                    .build();
            }
            @Override public String getModel() { return "served-opus"; }
        };

        try {
            SessionFacets facets = new FacetExtractor(client, () -> "requested-opus")
                .extractFacets(simpleLog(), "sid");

            assertNotNull(facets);
            assertEquals(new Usage(40, 10, 0, 0), costs.usageByModel().get("served-opus"));
            assertTrue(costs.apiDurationMs() >= 1L);
        } finally {
            costs.reset();
        }
    }

    @Test
    void extractsFacetsFromFencedJsonAndOverridesSessionId() {
        RecordingLlmClient client = new RecordingLlmClient(_ ->
            "Sure, here are the facets:\n```json\n" + VALID_FACETS_JSON + "\n```\nLet me know!");
        FacetExtractor extractor = new FacetExtractor(client, () -> "opus-test");

        SessionFacets facets = extractor.extractFacets(simpleLog(), "session-override");

        assertNotNull(facets);
        assertEquals("session-override", facets.sessionId());
        assertEquals("Fix the login bug", facets.underlyingGoal());
        assertEquals(2L, facets.goalCategories().get("fix_bug"));
        assertEquals("fully_achieved", facets.outcome());
        assertEquals("User wanted a bug fixed and got it", facets.briefSummary());

        assertEquals(1, client.requests.size());
        assertEquals("opus-test", client.requests.getFirst().model());
        assertEquals(4096, client.requests.getFirst().maxTokens());
        String prompt = client.prompts().getFirst();
        assertTrue(Strings.CS.startsWith(prompt, FacetExtractor.FACET_EXTRACTION_PROMPT));
        assertTrue(Strings.CS.contains(prompt, "RESPOND WITH ONLY A VALID JSON OBJECT matching this schema:"));
        assertTrue(Strings.CS.endsWith(prompt, FacetExtractor.FACET_JSON_SCHEMA_SUFFIX));
    }

    @Test
    void invalidFacetsReturnNull() {
// Missing required brief_summary → isValid false → silently skipped
        RecordingLlmClient client = new RecordingLlmClient(_ -> """
            {
              "underlying_goal": "x",
              "goal_categories": {},
              "outcome": "not_achieved",
              "user_satisfaction_counts": {},
              "friction_counts": {}
            }""");
        FacetExtractor extractor = new FacetExtractor(client, () -> "opus-test");

        assertNull(extractor.extractFacets(simpleLog(), "sid"));
    }

    @Test
    void responseWithoutJsonReturnsNull() {
        RecordingLlmClient client = new RecordingLlmClient(_ -> "I cannot analyze this session.");
        FacetExtractor extractor = new FacetExtractor(client, () -> "opus-test");

        assertNull(extractor.extractFacets(simpleLog(), "sid"));
    }

    @Test
    void malformedJsonReturnsNull() {
        RecordingLlmClient client = new RecordingLlmClient(_ -> "{\"underlying_goal\": ");
        FacetExtractor extractor = new FacetExtractor(client, () -> "opus-test");

        assertNull(extractor.extractFacets(simpleLog(), "sid"));
    }

    @Test
    void llmFailureReturnsNull() {
        RecordingLlmClient client = new RecordingLlmClient(_ -> {
            throw new RuntimeException("api down");
        });
        FacetExtractor extractor = new FacetExtractor(client, () -> "opus-test");

        assertNull(extractor.extractFacets(simpleLog(), "sid"));
    }

    @Test
    void formatsTranscriptHeaderAndMessages() {
        String transcript = FacetExtractor.formatTranscriptForFacets(simpleLog());

        assertEquals("""
            Session: abcdef12
            Date: 2026-07-01T10:00:00.000Z
            Project: /tmp/proj
            Duration: 30 min

            [User]: hello
            [Assistant]: hi
            [Tool: Bash]
            [User]: block text""", transcript);
    }

    @Test
    void truncatesUserTextAt500AndAssistantTextAt300() {
        String longUser = "u".repeat(600);
        String longAssistant = "a".repeat(400);
        SessionLog log = new SessionLog("s", List.of(
            userEntry(longUser, "2026-07-01T10:00:00.000Z", "/p"),
            assistantEntry(longAssistant, null, "2026-07-01T10:01:00.000Z")));

        String transcript = FacetExtractor.formatTranscriptForFacets(log);

        assertTrue(Strings.CS.contains(transcript, "[User]: " + "u".repeat(500) + "\n"));
        assertTrue(Strings.CS.endsWith(transcript, "[Assistant]: " + "a".repeat(300)));
    }

    @Test
    void longTranscriptIsChunkSummarized() {
        // ~70 user lines of 500 chars → transcript > 30000 chars → 2 chunks of 25000
        List<JsonNode> entries = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            entries.add(userEntry("m".repeat(500), "2026-07-01T10:00:00.000Z", "/tmp/proj"));
        }
        SessionLog log = new SessionLog("longsession", entries);

        RecordingLlmClient client = new RecordingLlmClient(prompt -> {
            if (Strings.CS.startsWith(prompt, "Summarize this portion")) {
                return "CHUNK SUMMARY";
            }
            return VALID_FACETS_JSON;
        });
        FacetExtractor extractor = new FacetExtractor(client, () -> "opus-test");

        SessionFacets facets = extractor.extractFacets(log, "sid");

        assertNotNull(facets);
        List<String> prompts = client.prompts();
        List<String> chunkPrompts = prompts.stream()
            .filter(p -> Strings.CS.startsWith(p, FacetExtractor.SUMMARIZE_CHUNK_PROMPT)).toList();
        assertEquals(2, chunkPrompts.size());
        assertEquals(500, client.requests.get(prompts.indexOf(chunkPrompts.getFirst())).maxTokens());

        String facetPrompt = prompts.stream()
            .filter(p -> Strings.CS.startsWith(p, FacetExtractor.FACET_EXTRACTION_PROMPT))
            .findFirst().orElseThrow();
        assertTrue(Strings.CS.contains(facetPrompt, "[Long session - 2 parts summarized]\n"));
        assertTrue(Strings.CS.contains(facetPrompt, "CHUNK SUMMARY\n\n---\n\nCHUNK SUMMARY"));
    }

    @Test
    void chunkSummarizationFailureFallsBackToTruncatedChunk() {
        List<JsonNode> entries = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            entries.add(userEntry("m".repeat(500), "2026-07-01T10:00:00.000Z", "/tmp/proj"));
        }
        SessionLog log = new SessionLog("longsession", entries);

        RecordingLlmClient client = new RecordingLlmClient(prompt -> {
            if (Strings.CS.startsWith(prompt, "Summarize this portion")) {
                throw new RuntimeException("summarize failed");
            }
            return VALID_FACETS_JSON;
        });
        FacetExtractor extractor = new FacetExtractor(client, () -> "opus-test");

        SessionFacets facets = extractor.extractFacets(log, "sid");

        assertNotNull(facets);
        String facetPrompt = client.prompts().stream()
            .filter(p -> Strings.CS.startsWith(p, FacetExtractor.FACET_EXTRACTION_PROMPT))
            .findFirst().orElseThrow();
        // Fallback = first 2000 chars of each chunk
        assertTrue(Strings.CS.contains(facetPrompt, "\n\n---\n\n"));
        assertTrue(Strings.CS.contains(facetPrompt, "Session: longsess\n"));
    }

    @Test
    void extractJsonObjectTakesFirstBraceToLastBrace() {
        assertEquals("{\"a\": {\"b\": 1}}",
            FacetExtractor.extractJsonObject("noise ```json\n{\"a\": {\"b\": 1}}\n``` trailing"));
        assertNull(FacetExtractor.extractJsonObject("no braces here"));
        assertNull(FacetExtractor.extractJsonObject(null));
        assertNull(FacetExtractor.extractJsonObject("} reversed {"));
    }
}
