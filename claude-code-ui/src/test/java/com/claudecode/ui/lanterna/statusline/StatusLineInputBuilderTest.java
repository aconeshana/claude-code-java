package com.claudecode.ui.lanterna.statusline;

import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class StatusLineInputBuilderTest {

    @BeforeEach
    void resetCost() {
        SessionCostState.get().reset();
    }

    private static AssistantMessage assistantWithUsage(Usage usage) {
        return new AssistantMessage("a1",
            new AssistantContent("m1", List.of(new TextBlock("hi")), usage));
    }

    private static AssistantMessage assistantWithUsage(String model, Usage usage) {
        return new AssistantMessage("a1", AssistantContent.apiResponse(
            "m1", List.of(new TextBlock("hi")), usage, model, "end_turn", null));
    }

    private static StatusLineInputBuilder.Ingredients ingredients(String model, String vim, String name) {
        return new StatusLineInputBuilder.Ingredients(
            "sess-1", name, "/home/u/.claude/projects/x/sess-1.jsonl",
            "/home/u/project", "/home/u/project", List.of("/extra"),
            model, null, vim, "0.1.0");
    }

    private static JsonNode json(StatusLineInput in) throws Exception {
        return JsonUtils.getMapper().readTree(in.toJson());
    }

    @Test
    void allDocumentedFieldsPresentAndNested() throws Exception {
        SessionCostState.get().recordApiRequest("claude-opus-4-8", new Usage(1000, 200, 50, 25), 4200);
        SessionCostState.get().recordLinesChanged(40, 9);

        StatusLineInput in = StatusLineInputBuilder.build(
            ingredients("claude-opus-4-8", null, null),
            List.of(assistantWithUsage(new Usage(80_000, 500, 10_000, 20_000))));
        JsonNode j = json(in);

        assertEquals("sess-1", j.get("session_id").asText());
        assertEquals("/home/u/.claude/projects/x/sess-1.jsonl", j.get("transcript_path").asText());
        assertEquals("/home/u/project", j.get("cwd").asText());
        assertEquals("claude-opus-4-8", j.get("model").get("id").asText());
        assertEquals("Opus 4.8", j.get("model").get("display_name").asText());
        assertEquals("/home/u/project", j.get("workspace").get("current_dir").asText());
        assertEquals("/home/u/project", j.get("workspace").get("project_dir").asText());
        assertEquals("/extra", j.get("workspace").get("added_dirs").get(0).asText());
        assertEquals("0.1.0", j.get("version").asText());
        assertEquals("default", j.get("output_style").get("name").asText());

        // cost block sourced from SessionCostState
        assertTrue(j.get("cost").get("total_cost_usd").asDouble() > 0);
        assertEquals(4200, j.get("cost").get("total_api_duration_ms").asLong());
        assertEquals(40, j.get("cost").get("total_lines_added").asLong());
        assertEquals(9, j.get("cost").get("total_lines_removed").asLong());

        // context window
        JsonNode ctx = j.get("context_window");
        assertEquals(200_000, ctx.get("context_window_size").asLong());
        assertEquals(1000, ctx.get("total_input_tokens").asLong());
        assertEquals(200, ctx.get("total_output_tokens").asLong());
        JsonNode cu = ctx.get("current_usage");
        assertEquals(80_000, cu.get("input_tokens").asLong());
        assertEquals(10_000, cu.get("cache_creation_input_tokens").asLong());
        assertEquals(20_000, cu.get("cache_read_input_tokens").asLong());
    }

    @Test
    void optionalSessionMetricsUsesCanonicalProjectionAndDerivedFields() throws Exception {
        SessionMetricsSnapshot metrics = new SessionMetricsSnapshot(true,
            2, 3, 4_000, 500, 1_200, 2, 2_000, 40,
            10, 40, 5, 85);
        StatusLineInputBuilder.Ingredients base = ingredients("claude-opus-4-8", null, null);
        StatusLineInputBuilder.Ingredients configured = new StatusLineInputBuilder.Ingredients(
            base.sessionId(), base.sessionName(), base.transcriptPath(), base.cwd(),
            base.projectDir(), base.addedDirs(), base.modelId(), base.outputStyleName(),
            base.vimMode(), base.version(), base.contextWindow(), metrics);

        JsonNode value = json(StatusLineInputBuilder.build(configured, List.of()));
        JsonNode sessionMetrics = value.path("session_metrics");
        assertEquals("complete", sessionMetrics.path("coverage").asText());
        assertEquals(100, sessionMetrics.path("billed_input_tokens").asLong());
        assertEquals(600, sessionMetrics.path("ttft_average_ms").asDouble());
        assertEquals(20, sessionMetrics.path("tokens_per_second").asDouble());
        assertEquals("85", sessionMetrics.path("cache_hit_percent").asText());
    }

    @Test
    void contextPercentagesMatchTsRoundingAndClamp() throws Exception {
        // used = round((input + cache_creation + cache_read) / window * 100)
        // (110_000 / 200_000) * 100 = 55  (output excluded)
        StatusLineInput in = StatusLineInputBuilder.build(
            ingredients("claude-opus-4-8", null, null),
            List.of(assistantWithUsage(new Usage(80_000, 999_999, 10_000, 20_000))));
        JsonNode ctx = json(in).get("context_window");
        assertEquals(55, ctx.get("used_percentage").asInt());
        assertEquals(45, ctx.get("remaining_percentage").asInt());
    }

    @Test
    void configuredContextWindowIsReportedForCustomModels() throws Exception {
        StatusLineInputBuilder.Ingredients base = ingredients("gpt-custom", null, null);
        StatusLineInputBuilder.Ingredients configured = new StatusLineInputBuilder.Ingredients(
            base.sessionId(), base.sessionName(), base.transcriptPath(), base.cwd(), base.projectDir(),
            base.addedDirs(), base.modelId(), base.outputStyleName(), base.vimMode(), base.version(), 400_000L);

        JsonNode context = json(StatusLineInputBuilder.build(configured,
            List.of(assistantWithUsage(new Usage(100_000, 0, 0, 0))))).get("context_window");
        assertEquals(400_000L, context.get("context_window_size").asLong());
        assertEquals(25, context.get("used_percentage").asInt());
    }

    @Test
    void customGptPercentageDoesNotDoubleCountCachedInputDetail() throws Exception {
        StatusLineInputBuilder.Ingredients base = ingredients("gpt-5.6-sol", null, null);
        StatusLineInputBuilder.Ingredients configured = new StatusLineInputBuilder.Ingredients(
            base.sessionId(), base.sessionName(), base.transcriptPath(), base.cwd(), base.projectDir(),
            base.addedDirs(), base.modelId(), base.outputStyleName(), base.vimMode(), base.version(), 200_000L);

        JsonNode context = json(StatusLineInputBuilder.build(configured,
            List.of(assistantWithUsage(new Usage(20_000, 5_000, 0, 80_000)))))
            .get("context_window");
        assertEquals(50, context.get("used_percentage").asInt());
        assertFalse(json(StatusLineInputBuilder.build(configured,
            List.of(assistantWithUsage(new Usage(20_000, 5_000, 0, 100_000)))))
            .get("exceeds_200k_tokens").asBoolean());
    }

    @Test
    void modelSwitchAndSyntheticErrorKeepTheLastRealProviderUsage() throws Exception {
        Usage usage = new Usage(20_000, 5_000, 0, 80_000, 105_000L);
        JsonNode context = json(StatusLineInputBuilder.build(
            ingredients("anthropic.claude-sonnet-5", null, null),
            List.of(
                assistantWithUsage("gpt-5.6-sol", usage),
                MessageFactory.createAssistantAPIErrorMessage(
                    "Context limit reached · /compact or /clear to continue"))))
            .get("context_window");

        assertEquals(20_000L, context.get("current_usage").get("input_tokens").asLong());
        assertEquals(50, context.get("used_percentage").asInt());
        assertEquals(50, context.get("remaining_percentage").asInt());
    }

    @Test
    void gpt56Reports372kDefaultWhenNoExplicitWindowExists() throws Exception {
        JsonNode context = json(StatusLineInputBuilder.build(
            ingredients("gpt-5.6-sol", null, null),
            List.of(assistantWithUsage(new Usage(20_000, 5_000, 0, 80_000)))))
            .get("context_window");

        assertEquals(372_000L, context.get("context_window_size").asLong());
        assertEquals(27, context.get("used_percentage").asInt());
    }

    @Test
    void percentagesClampAt100() throws Exception {
        StatusLineInput in = StatusLineInputBuilder.build(
            ingredients("claude-opus-4-8", null, null),
            List.of(assistantWithUsage(new Usage(500_000, 0, 0, 0))));
        JsonNode ctx = json(in).get("context_window");
        assertEquals(100, ctx.get("used_percentage").asInt());
        assertEquals(0, ctx.get("remaining_percentage").asInt());
    }

    @Test
    void nullCurrentUsageWhenNoMessages() throws Exception {
        StatusLineInput in = StatusLineInputBuilder.build(
            ingredients("claude-opus-4-8", null, null), List.of());
        JsonNode ctx = json(in).get("context_window");
        assertTrue(ctx.get("current_usage").isNull());
        assertTrue(ctx.get("used_percentage").isNull());
        assertTrue(ctx.get("remaining_percentage").isNull());
    }

    @Test
    void exceeds200kFromFourFieldSum() throws Exception {
        // 150k + 30k + 30k + 5k = 215k > 200k
        StatusLineInput over = StatusLineInputBuilder.build(
            ingredients("claude-opus-4-8", null, null),
            List.of(assistantWithUsage(new Usage(150_000, 5_000, 30_000, 30_000))));
        assertTrue(json(over).get("exceeds_200k_tokens").asBoolean());

        StatusLineInput under = StatusLineInputBuilder.build(
            ingredients("claude-opus-4-8", null, null),
            List.of(assistantWithUsage(new Usage(50_000, 5_000, 0, 0))));
        assertFalse(json(under).get("exceeds_200k_tokens").asBoolean());
    }

    @Test
    void sessionNameOmittedWhenAbsentPresentWhenSet() throws Exception {
        JsonNode without = json(StatusLineInputBuilder.build(
            ingredients("claude-opus-4-8", null, null), List.of()));
        assertFalse(without.has("session_name"));

        JsonNode with = json(StatusLineInputBuilder.build(
            ingredients("claude-opus-4-8", null, "My Session"), List.of()));
        assertEquals("My Session", with.get("session_name").asText());
    }

    @Test
    void vimBlockOnlyWhenModeSet() throws Exception {
        JsonNode without = json(StatusLineInputBuilder.build(
            ingredients("claude-opus-4-8", null, null), List.of()));
        assertFalse(without.has("vim"));

        JsonNode with = json(StatusLineInputBuilder.build(
            ingredients("claude-opus-4-8", "NORMAL", null), List.of()));
        assertEquals("NORMAL", with.get("vim").get("mode").asText());
    }

    @Test
    void oneMSuffixWidensContextWindow() throws Exception {
        StatusLineInput in = StatusLineInputBuilder.build(
            ingredients("claude-sonnet-5[1m]", null, null),
            List.of(assistantWithUsage(new Usage(100_000, 0, 0, 0))));
        JsonNode ctx = json(in).get("context_window");
        assertEquals(1_000_000, ctx.get("context_window_size").asLong());
        assertEquals(10, ctx.get("used_percentage").asInt());  // 100k/1M
    }
}
