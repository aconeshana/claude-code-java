package com.claudecode.services.insights;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsGeneratorTest {

    private static final List<String> BASE_SECTIONS = List.of(
        "project_areas", "interaction_style", "what_works", "friction_analysis",
        "suggestions", "on_the_horizon", "fun_ending");

    /** Canned per-section responses routed by each prompt's distinctive first line. */
    private static String respond(String prompt) {
        if (Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and identify project areas.")) {
            return "```json\n{\"areas\":[{\"name\":\"A\",\"session_count\":2,\"description\":\"D\"}]}\n```";
        }
        if (Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and describe the user's interaction style.")) {
            return "{\"narrative\":\"N\",\"key_pattern\":\"K\"}";
        }
        if (Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and identify what's working well")) {
            return "{\"intro\":\"I\",\"impressive_workflows\":[{\"title\":\"W\",\"description\":\"WD\"}]}";
        }
        if (Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and identify friction points")) {
            return "{\"intro\":\"FI\",\"categories\":[{\"category\":\"C\",\"description\":\"CD\",\"examples\":[\"E1\",\"E2\"]}]}";
        }
        if (Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and suggest improvements.")) {
            return "{\"claude_md_additions\":[],"
                + "\"features_to_try\":[{\"feature\":\"F\",\"one_liner\":\"FO\"}],"
                + "\"usage_patterns\":[{\"title\":\"P\",\"suggestion\":\"PS\"}]}";
        }
        if (Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and identify future opportunities.")) {
            return "{\"intro\":\"HI\",\"opportunities\":[{\"title\":\"O\",\"whats_possible\":\"OP\"}]}";
        }
        if (Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and find a memorable moment.")) {
            return "{\"headline\":\"H\",\"detail\":\"HD\"}";
        }
        if (Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and suggest product improvements")
            || Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and suggest model behavior improvements.")) {
            return "{\"improvements\":[{\"title\":\"T\",\"detail\":\"TD\"}]}";
        }
        if (Strings.CS.startsWith(prompt, "You're writing an \"At a Glance\"")) {
            return "{\"whats_working\":\"WW\",\"whats_hindering\":\"WH\","
                + "\"quick_wins\":\"QW\",\"ambitious_workflows\":\"AW\"}";
        }
        throw new IllegalStateException("unexpected prompt: " + prompt.substring(0, Math.min(60, prompt.length())));
    }

    private static AggregatedData sampleData() {
        Map<String, Long> tools = new LinkedHashMap<>();
        tools.put("Edit", 1L);
        tools.put("Bash", 3L);
        return AggregatedData.builder(2, 1,
                new AggregatedData.DateRange("2026-07-01", "2026-07-02"))
            .totalMessages(7).totalDurationHours(1.5)
            .totalInputTokens(300).totalOutputTokens(75)
            .toolCounts(tools).languages(new LinkedHashMap<>(Map.of("Java", 2L)))
            .gitCommits(4).gitPushes(1)
            .projects(new LinkedHashMap<>(Map.of("/tmp/proj", 2L)))
            .goalCategories(new LinkedHashMap<>(Map.of("fix_bug", 3L)))
            .outcomes(new LinkedHashMap<>(Map.of("fully_achieved", 1L)))
            .satisfaction(new LinkedHashMap<>(Map.of("satisfied", 1L)))
            .helpfulness(new LinkedHashMap<>(Map.of("very_helpful", 1L)))
            .sessionTypes(new LinkedHashMap<>(Map.of("single_task", 1L)))
            .success(new LinkedHashMap<>(Map.of("correct_code_edits", 1L)))
            .daysActive(2).messagesPerDay(3.5).build();
    }

    private static Map<String, SessionFacets> sampleFacets() {
        Map<String, SessionFacets> facets = new LinkedHashMap<>();
        facets.put("s1", new SessionFacets("s1", "goal", Map.of("fix_bug", 1L), "fully_achieved",
            Map.of(), "very_helpful", "single_task", Map.of(), "some friction",
            "correct_code_edits", "BS", null));
        return facets;
    }

    @Test
    void generatesAllSectionsWithAtAGlanceLast() {
        RecordingLlmClient client = new RecordingLlmClient(InsightsGeneratorTest::respond);
        InsightsGenerator generator = new InsightsGenerator(client, () -> "opus-test");

        Map<String, JsonNode> insights = generator.generate(sampleData(), sampleFacets());

        for (String section : BASE_SECTIONS) {
            assertTrue(insights.containsKey(section), "missing section: " + section);
        }
        assertTrue(insights.containsKey("at_a_glance"));
        List<String> keys = new ArrayList<>(insights.keySet());
        assertEquals("at_a_glance", keys.getLast());
        assertEquals("project_areas", keys.getFirst());

        // Fenced JSON was scraped and parsed
        assertEquals("A", insights.get("project_areas").get("areas").get(0).get("name").asText());
        assertEquals("WW", insights.get("at_a_glance").get("whats_working").asText());

        // All calls used the supplied model and section budget
        assertTrue(client.requests.stream().allMatch(r -> Strings.CS.equals("opus-test", r.model())));
        assertTrue(client.requests.stream().allMatch(r -> r.maxTokens() == 8192));
    }

    @Test
    void singleSectionFailureIsSkippedWithoutAborting() {
        Function<String, String> failFriction = prompt -> {
            if (Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and identify friction points")) {
                throw new RuntimeException("boom");
            }
            return respond(prompt);
        };
        RecordingLlmClient client = new RecordingLlmClient(failFriction);
        InsightsGenerator generator = new InsightsGenerator(client, () -> "opus-test");

        Map<String, JsonNode> insights = generator.generate(sampleData(), sampleFacets());

        assertFalse(insights.containsKey("friction_analysis"));
        assertTrue(insights.containsKey("project_areas"));
        assertTrue(insights.containsKey("fun_ending"));
        assertTrue(insights.containsKey("at_a_glance"));

        // The failed section leaves its at-a-glance context block empty
        String atAGlancePrompt = client.prompts().stream()
            .filter(p -> Strings.CS.startsWith(p, "You're writing an \"At a Glance\""))
            .findFirst().orElseThrow();
        assertTrue(Strings.CS.contains(atAGlancePrompt, 
            "## Friction Categories (where things go wrong)\n\n\n## Features to Try"));
    }

    @Test
    void unparseableSectionResponseIsSkipped() {
        Function<String, String> badJson = prompt -> {
            if (Strings.CS.startsWith(prompt, "Analyze this Claude Code usage data and find a memorable moment.")) {
                return "sorry, no JSON today";
            }
            return respond(prompt);
        };
        RecordingLlmClient client = new RecordingLlmClient(badJson);
        InsightsGenerator generator = new InsightsGenerator(client, () -> "opus-test");

        Map<String, JsonNode> insights = generator.generate(sampleData(), sampleFacets());

        assertFalse(insights.containsKey("fun_ending"));
        assertTrue(insights.containsKey("project_areas"));
    }

    @Test
    void atAGlancePromptEmbedsDerivedSectionContext() {
        RecordingLlmClient client = new RecordingLlmClient(InsightsGeneratorTest::respond);
        InsightsGenerator generator = new InsightsGenerator(client, () -> "opus-test");

        generator.generate(sampleData(), sampleFacets());

        String prompt = client.prompts().stream()
            .filter(p -> Strings.CS.startsWith(p, "You're writing an \"At a Glance\""))
            .findFirst().orElseThrow();

        assertTrue(Strings.CS.contains(prompt, "SESSION DATA:\n{"));
        assertTrue(Strings.CS.contains(prompt, "## Project Areas (what user works on)\n- A: D"));
        assertTrue(Strings.CS.contains(prompt, "## Big Wins (impressive accomplishments)\n- W: WD"));
        assertTrue(Strings.CS.contains(prompt, "## Friction Categories (where things go wrong)\n- C: CD"));
        assertTrue(Strings.CS.contains(prompt, "## Features to Try\n- F: FO"));
        assertTrue(Strings.CS.contains(prompt, "## Usage Patterns to Adopt\n- P: PS"));
        assertTrue(Strings.CS.contains(prompt, "## On the Horizon (ambitious workflows for better models)\n- O: OP"));
        // at_a_glance runs with an empty data context appended, like every section
        assertTrue(Strings.CS.endsWith(prompt, "\n\nDATA:\n"));
    }

    @Test
    void sectionPromptsCarrySharedDataContext() {
        RecordingLlmClient client = new RecordingLlmClient(InsightsGeneratorTest::respond);
        InsightsGenerator generator = new InsightsGenerator(client, () -> "opus-test");

        generator.generate(sampleData(), sampleFacets());

        String prompt = client.prompts().stream()
            .filter(p -> Strings.CS.startsWith(p, "Analyze this Claude Code usage data and identify project areas."))
            .findFirst().orElseThrow();

        assertTrue(Strings.CS.contains(prompt, "\n\nDATA:\n{"));
        // JSON.stringify(…, null, 2) formatting: "key": value with 2-space indent
        assertTrue(Strings.CS.contains(prompt, "\"sessions\": 2"));
        assertTrue(Strings.CS.contains(prompt, "\"date_range\": {\n    \"start\": \"2026-07-01\""));
        // top_tools: stable value-desc sort of insertion-ordered entries, as [name, count] pairs
        assertTrue(Strings.CS.contains(prompt, 
            """
              "top_tools": [
                [
                  "Bash",
                  3
                ],
                [
                  "Edit",
                  1
                ]
              ],
            """));
        assertTrue(Strings.CS.contains(prompt, "\"friction\": {},"));
        assertTrue(Strings.CS.contains(prompt, "SESSION SUMMARIES:\n- BS (fully_achieved, very_helpful)"));
        assertTrue(Strings.CS.contains(prompt, "FRICTION DETAILS:\n- some friction"));
        assertTrue(Strings.CS.contains(prompt, "USER INSTRUCTIONS TO CLAUDE:\nNone captured"));
    }

    @Test
    void buildDataContextMatchesJsonStringifyByteForByte() {
        AggregatedData empty = InsightsAggregator.aggregate(List.of(), Map.of());

        assertEquals("""
            {
              "sessions": 0,
              "analyzed": 0,
              "date_range": {
                "start": "",
                "end": ""
              },
              "messages": 0,
              "hours": 0,
              "commits": 0,
              "top_tools": [],
              "top_goals": [],
              "outcomes": {},
              "satisfaction": {},
              "friction": {},
              "success": {},
              "languages": {}
            }""", InsightsGenerator.buildDataContext(empty));
    }

    @Test
    void toJsonSerializesInsightResultsShape() {
        RecordingLlmClient client = new RecordingLlmClient(InsightsGeneratorTest::respond);
        InsightsGenerator generator = new InsightsGenerator(client, () -> "opus-test");

        Map<String, JsonNode> insights = generator.generate(sampleData(), sampleFacets());
        String json = InsightsGenerator.toJson(insights);

        assertTrue(Strings.CS.startsWith(json, "{\n  \"project_areas\": {\n    \"areas\": [\n"));
        assertTrue(Strings.CS.contains(json, "\n  \"at_a_glance\": {\n    \"whats_working\": \"WW\","));
        assertFalse(Strings.CS.contains(json, " : "), "JS stringify never puts a space before the colon");

        JsonNode reparsed = JsonUtils.parseTree(json);
        assertEquals("H", reparsed.get("fun_ending").get("headline").asText());
        assertNotNull(reparsed.get("at_a_glance"));
    }

    @Test
    void userInstructionsAreFlattenedAndCappedAtFifteen() {
        Map<String, SessionFacets> facets = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            List<String> instructions = new ArrayList<>();
            for (int j = 0; j < 8; j++) {
                instructions.add("inst-" + i + "-" + j);
            }
            facets.put("s" + i, new SessionFacets("s" + i, "g", Map.of(), "fully_achieved",
                Map.of(), "very_helpful", "single_task", Map.of(), "", "none", "B" + i, instructions));
        }

        String context = InsightsGenerator.buildFullContext(sampleData(), facets);

        String instructionsBlock = context.substring(
            context.indexOf("USER INSTRUCTIONS TO CLAUDE:\n") + "USER INSTRUCTIONS TO CLAUDE:\n".length());
        assertEquals(15, instructionsBlock.lines().count());
        assertTrue(Strings.CS.startsWith(instructionsBlock, "- inst-0-0\n"));
        assertTrue(Strings.CS.endsWith(instructionsBlock, "- inst-1-6"));
    }
}
