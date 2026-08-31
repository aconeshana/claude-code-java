package com.claudecode.services.insights;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class HtmlReportGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // escaping
    // ------------------------------------------------------------------

    @Test
    void escapeHtmlEscapesAttrCharactersLikeEscapeXmlAttr() {
        assertEquals("say &quot;hi&quot; &amp; &apos;bye&apos; &lt;now&gt;",
            HtmlReportGenerator.escapeHtml("say \"hi\" & 'bye' <now>"));
    }

    @Test
    void escapeHtmlWithBoldRendersStrongAfterEscaping() {
        assertEquals("<strong>bold</strong> plain",
            HtmlReportGenerator.escapeHtmlWithBold("**bold** plain"));
        assertEquals("<strong>a&lt;b</strong>",
            HtmlReportGenerator.escapeHtmlWithBold("**a<b**"));
    }

    // ------------------------------------------------------------------
    // generateBarChart
    // ------------------------------------------------------------------

    @Test
    void barChartWidthsAreProportionalToMaxValue() {
        String html = HtmlReportGenerator.generateBarChart(
            orderedMap("first_label", 4L, "second_label", 2L, "third_label", 1L), "#123456");
        assertTrue(Strings.CS.contains(html, "width:100%;background:#123456"));
        assertTrue(Strings.CS.contains(html, "width:50%;background:#123456"));
        assertTrue(Strings.CS.contains(html, "width:25%;background:#123456"));
        // fallback label cleanup: underscores → spaces, title case
        assertTrue(Strings.CS.contains(html, ">First Label</div>"));
        assertTrue(Strings.CS.contains(html, ">Second Label</div>"));
    }

    @Test
    void barChartSingleRowMatchesTsTemplateExactly() {
        String html = HtmlReportGenerator.generateBarChart(
            orderedMap("tool_failed", 3L), "#dc2626");
        assertEquals("""
            <div class="bar-row">
                    <div class="bar-label">Tool Failed</div>
                    <div class="bar-track"><div class="bar-fill" style="width:100%;background:#dc2626"></div></div>
                    <div class="bar-value">3</div>
                  </div>""", html);
    }

    @Test
    void barChartUsesLabelMap() {
        String html = HtmlReportGenerator.generateBarChart(
            orderedMap("debug_investigate", 5L), "#2563eb");
        assertTrue(Strings.CS.contains(html, ">Debug/Investigate</div>"));
    }

    @Test
    void barChartCapsAtMaxItemsSortedDescending() {
        Map<String, Long> data = orderedMap(
            "a", 1L, "b", 2L, "c", 3L, "d", 4L, "e", 5L, "f", 6L, "g", 7L);
        String html = HtmlReportGenerator.generateBarChart(data, "#000000");
        assertEquals(6, countOccurrences(html, "bar-row"));
        assertFalse(Strings.CS.contains(html, ">A</div>"));   // lowest count dropped
        // highest count first
        assertTrue(html.indexOf(">G</div>") < html.indexOf(">B</div>"));
    }

    @Test
    void barChartFixedOrderFiltersZeroesAndKeepsOrder() {
        Map<String, Long> data = orderedMap(
            "satisfied", 3L, "frustrated", 1L, "unsure", 0L, "bogus", 5L);
        String html = HtmlReportGenerator.generateBarChart(
            data, "#eab308", 6, HtmlReportGenerator.SATISFACTION_ORDER);
        assertTrue(html.indexOf(">Frustrated</div>") < html.indexOf(">Satisfied</div>"));
        assertFalse(Strings.CS.contains(html, "Unsure"));   // zero count filtered
        assertFalse(Strings.CS.contains(html, "Bogus"));    // not part of the fixed order
    }

    @Test
    void barChartEmptyDataRendersEmptyMessage() {
        assertEquals("<p class=\"empty\">No data</p>",
            HtmlReportGenerator.generateBarChart(Map.of(), "#123456"));
    }

    // ------------------------------------------------------------------
    // generateResponseTimeHistogram
    // ------------------------------------------------------------------

    @Test
    void histogramBucketsTimesAndScalesWidths() {
        String html = HtmlReportGenerator.generateResponseTimeHistogram(
            List.of(5.0, 5.0, 15.0, 45.0, 100.0, 200.0, 400.0, 1000.0));
        // all seven fixed buckets, in order
        for (String label : new String[] {"2-10s", "10-30s", "30s-1m", "1-2m", "2-5m", "5-15m", ">15m"}) {
            assertTrue(Strings.CS.contains(html, ">" + label + "</div>"), "missing bucket " + label);
        }
        assertEquals(7, countOccurrences(html, "bar-row"));
        // 2-10s holds two samples (max) → 100%; every other bucket holds one → 50%
        assertTrue(Strings.CS.contains(html, "width:100%;background:#6366f1"));
        assertEquals(6, countOccurrences(html, "width:50%;background:#6366f1"));
        assertTrue(Strings.CS.contains(html, "<div class=\"bar-value\">2</div>"));
    }

    @Test
    void histogramEmptyTimesRendersEmptyMessage() {
        assertEquals("<p class=\"empty\">No response time data</p>",
            HtmlReportGenerator.generateResponseTimeHistogram(List.of()));
    }

    // ------------------------------------------------------------------
    // generateTimeOfDayChart / getHourCountsJson
    // ------------------------------------------------------------------

    @Test
    void timeOfDayChartGroupsHoursIntoFourPeriods() {
        String html = HtmlReportGenerator.generateTimeOfDayChart(List.of(6, 13, 19, 2));
        assertTrue(Strings.CS.startsWith(html, "<div id=\"hour-histogram\">"));
        assertTrue(Strings.CS.endsWith(html, "</div></div>"));
        for (String label : new String[] {
            "Morning (6-12)", "Afternoon (12-18)", "Evening (18-24)", "Night (0-6)"}) {
            assertTrue(Strings.CS.contains(html, ">" + label + "</div>"), "missing period " + label);
        }
        assertEquals(4, countOccurrences(html, "bar-row"));
        assertEquals(4, countOccurrences(html, "width:100%;background:#8b5cf6"));
    }

    @Test
    void timeOfDayChartZeroPeriodsGetZeroWidth() {
        String html = HtmlReportGenerator.generateTimeOfDayChart(List.of(7, 8));
        assertEquals(1, countOccurrences(html, "width:100%;background:#8b5cf6"));
        assertEquals(3, countOccurrences(html, "width:0%;background:#8b5cf6"));
        assertTrue(Strings.CS.contains(html, "<div class=\"bar-value\">2</div>"));
    }

    @Test
    void timeOfDayChartEmptyHoursRendersEmptyMessage() {
        assertEquals("<p class=\"empty\">No time data</p>",
            HtmlReportGenerator.generateTimeOfDayChart(List.of()));
    }

    @Test
    void hourCountsJsonIsCompactAndNumericallyOrdered() {
        assertEquals("{\"2\":2,\"5\":1}", HtmlReportGenerator.getHourCountsJson(List.of(2, 2, 5)));
        assertEquals("{\"2\":1,\"14\":1}", HtmlReportGenerator.getHourCountsJson(List.of(14, 2)));
        assertEquals("{}", HtmlReportGenerator.getHourCountsJson(List.of()));
    }

    // ------------------------------------------------------------------
    // markdownToHtml
    // ------------------------------------------------------------------

    @Test
    void markdownToHtmlHandlesParagraphsBulletsAndBold() {
        String html = HtmlReportGenerator.markdownToHtml(
            "You iterate **fast**.\n\n- Point one\n- Point two");
        assertEquals("<p>You iterate <strong>fast</strong>.</p>\n<p>• Point one<br>• Point two</p>",
            html);
        assertEquals("", HtmlReportGenerator.markdownToHtml(""));
    }

    // ------------------------------------------------------------------
    // full report
    // ------------------------------------------------------------------

    @Test
    void generateProducesFullReportWithAllSections() throws Exception {
        String html = HtmlReportGenerator.generate(sampleData(), sampleInsights(), true);

        assertTrue(Strings.CS.startsWith(html, "<!DOCTYPE html>"));
        assertTrue(Strings.CS.endsWith(html, "</html>"));

        // subtitle: toLocaleString + scanned suffix + date range
        assertTrue(Strings.CS.contains(html, 
            "1,234 messages across 12 sessions (3,500 total) | 2026-01-01 to 2026-07-01"));


        assertTrue(Strings.CS.contains(html, "At a Glance"));
        assertTrue(Strings.CS.contains(html, "<h2 id=\"section-work\">What You Work On</h2>"));
        assertTrue(Strings.CS.contains(html, "<h2 id=\"section-usage\">How You Use Claude Code</h2>"));
        assertTrue(Strings.CS.contains(html, "<h2 id=\"section-wins\">Impressive Things You Did</h2>"));
        assertTrue(Strings.CS.contains(html, "<h2 id=\"section-friction\">Where Things Go Wrong</h2>"));
        assertTrue(Strings.CS.contains(html, "<h2 id=\"section-features\">Existing CC Features to Try</h2>"));
        assertTrue(Strings.CS.contains(html, "<h2 id=\"section-patterns\">New Ways to Use Claude Code</h2>"));
        assertTrue(Strings.CS.contains(html, "<h2 id=\"section-horizon\">On the Horizon</h2>"));
        assertTrue(Strings.CS.contains(html, "Closing the Loop: Feedback for Other Teams"));
        assertTrue(Strings.CS.contains(html, "Product Improvements for CC Team"));
        assertTrue(Strings.CS.contains(html, "Model Behavior Improvements"));

        // chart card titles
        assertTrue(Strings.CS.contains(html, "<div class=\"chart-title\">What You Wanted</div>"));
        assertTrue(Strings.CS.contains(html, "<div class=\"chart-title\">Top Tools Used</div>"));
        assertTrue(Strings.CS.contains(html, "User Response Time Distribution"));
        assertTrue(Strings.CS.contains(html, "Multi-Clauding (Parallel Sessions)"));
        assertTrue(Strings.CS.contains(html, "<div class=\"chart-title\">Tool Errors Encountered</div>"));
        assertTrue(Strings.CS.contains(html, "Inferred Satisfaction (model-estimated)"));

        // **bold** → <strong> in at-a-glance
        assertTrue(Strings.CS.contains(html, "<strong>Parallel agents</strong> speed you up"));

        // stats row formatting
        assertTrue(Strings.CS.contains(html, "<div class=\"stat-value\">+1,000/-500</div>"));
        assertTrue(Strings.CS.contains(html, "<div class=\"stat-value\">41.1</div>"));
        assertTrue(Strings.CS.contains(html, "Median: 12.0s &bull; Average: 20.5s"));

        // multi-clauding percent: round(100 * 100 / 1234) = 8
        assertTrue(Strings.CS.contains(html, "color: #7c3aed;\">8%</div>"));


        assertTrue(Strings.CS.contains(html, "data-text=\"Add to CLAUDE.md\\n\\nUse Java 21\""));

        // fun ending headline is wrapped in quotes
        assertTrue(Strings.CS.contains(html, "<div class=\"fun-headline\">\"You ship at 2am\"</div>"));

        // narrative markdown rendering
        assertTrue(Strings.CS.contains(html, "<p>You iterate fast.</p>"));
        assertTrue(Strings.CS.contains(html, "<p>• Point one<br>• Point two</p>"));

        assertTrue(Strings.CS.contains(html, "const rawHourCounts = {\"2\":1,\"6\":1,\"13\":1,\"19\":1};"));
        assertTrue(Strings.CS.contains(html, "id=\"timezone-select\""));
    }

    @Test
    void generateEscapesXssInInsightText() throws Exception {
        String html = HtmlReportGenerator.generate(sampleData(), sampleInsights(), true);
        assertTrue(Strings.CS.contains(html, "Java port &lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(Strings.CS.contains(html, "<script>alert(1)</script>"));
    }

    @Test
    void generateHidesTeamFeedbackForNonAntUsers() throws Exception {
        String html = HtmlReportGenerator.generate(sampleData(), sampleInsights(), false);
        assertFalse(Strings.CS.contains(html, "Closing the Loop: Feedback for Other Teams"));
        assertFalse(Strings.CS.contains(html, "Product Improvements for CC Team"));
    }

    @Test
    void generateWithEmptyInsightsStillRendersSkeleton() {
        String html = HtmlReportGenerator.generate(sampleData(), Map.of(), false);
        assertTrue(Strings.CS.startsWith(html, "<!DOCTYPE html>"));
        assertFalse(Strings.CS.contains(html, "At a Glance"));
        assertFalse(Strings.CS.contains(html, "<h2 id=\"section-work\">"));   // nav link stays, section h2 gone
        assertFalse(Strings.CS.contains(html, "On the Horizon</h2>"));
        // data-driven charts still render
        assertTrue(Strings.CS.contains(html, ">Debug/Investigate</div>"));
    }

    @Test
    void generateRendersNoToolErrorsAndNoMultiClaudingBranches() {
        AggregatedData data = sampleData(Map.of(),
            new AggregatedData.MultiClauding(0, 0, 0), null);
        String html = HtmlReportGenerator.generate(data, Map.of(), false);
        assertTrue(Strings.CS.contains(html, "<p class=\"empty\">No tool errors</p>"));
        assertTrue(Strings.CS.contains(html, "No parallel session usage detected."));
        assertFalse(Strings.CS.contains(html, "Overlap Events"));
    }

    @Test
    void generateOmitsScannedSuffixWhenNotLarger() {
        AggregatedData data = sampleData(orderedMap("file_not_found", 2L),
            new AggregatedData.MultiClauding(2, 3, 100), 12L);
        String html = HtmlReportGenerator.generate(data, Map.of(), false);
        assertTrue(Strings.CS.contains(html, "1,234 messages across 12 sessions | 2026-01-01 to 2026-07-01"));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static AggregatedData sampleData() {
        return sampleData(orderedMap("file_not_found", 2L),
            new AggregatedData.MultiClauding(2, 3, 100), 3500L);
    }

    private static AggregatedData sampleData(
        Map<String, Long> toolErrorCategories,
        AggregatedData.MultiClauding multiClauding,
        Long totalSessionsScanned) {
        return AggregatedData.builder(12L, 10L,
                new AggregatedData.DateRange("2026-01-01", "2026-07-01"))
            .totalSessionsScanned(totalSessionsScanned)
            .totalMessages(1234L).totalDurationHours(42.5)
            .totalInputTokens(100L).totalOutputTokens(200L)
            .toolCounts(orderedMap("Bash", 40L, "Read", 20L))
            .languages(orderedMap("TypeScript", 5L, "Java", 3L))
            .gitCommits(7L).gitPushes(2L)
            .goalCategories(orderedMap("debug_investigate", 4L, "implement_feature", 2L))
            .outcomes(orderedMap("fully_achieved", 5L, "not_achieved", 1L))
            .satisfaction(orderedMap("satisfied", 3L, "frustrated", 1L))
            .sessionTypes(orderedMap("single_task", 6L))
            .friction(orderedMap("buggy_code", 2L))
            .success(orderedMap("correct_code_edits", 3L))
            .totalInterruptions(1L).totalToolErrors(2L)
            .toolErrorCategories(toolErrorCategories)
            .userResponseTimes(List.of(5.0, 15.0, 45.0))
            .medianResponseTime(12.0).avgResponseTime(20.5)
            .sessionsUsingTaskAgent(1L)
            .totalLinesAdded(1000L).totalLinesRemoved(500L)
            .totalFilesModified(42L).daysActive(30L).messagesPerDay(41.1)
            .messageHours(List.of(6, 13, 19, 2)).multiClauding(multiClauding)
            .build();
    }

    private static Map<String, JsonNode> sampleInsights() throws Exception {
        Map<String, JsonNode> insights = new LinkedHashMap<>();
        insights.put("at_a_glance", MAPPER.readTree("""
            {"whats_working":"**Parallel agents** speed you up",
             "whats_hindering":"Flaky tests",
             "quick_wins":"Try /memory",
             "ambitious_workflows":"Multi-repo refactors"}"""));
        insights.put("project_areas", MAPPER.readTree("""
            {"areas":[{"name":"claude-code-java","session_count":42,
                       "description":"Java port <script>alert(1)</script>"}]}"""));
        insights.put("interaction_style", MAPPER.readTree("""
            {"narrative":"You iterate fast.\\n\\n- Point one\\n- Point two",
             "key_pattern":"Short prompts"}"""));
        insights.put("what_works", MAPPER.readTree("""
            {"intro":"Some intro",
             "impressive_workflows":[{"title":"Big migration","description":"Moved 500 files"}]}"""));
        insights.put("friction_analysis", MAPPER.readTree("""
            {"intro":"Friction intro",
             "categories":[{"category":"Wrong approach","description":"Sometimes wrong",
                            "examples":["Example A","Example B"]}]}"""));
        insights.put("suggestions", MAPPER.readTree("""
            {"claude_md_additions":[{"addition":"Use Java 21","why":"Records rock"}],
             "features_to_try":[{"feature":"Hooks","one_liner":"Automate stuff",
                                 "why_for_you":"You repeat tasks","example_code":"claude config hooks"}],
             "usage_patterns":[{"title":"Plan first","suggestion":"Use plan mode",
                                "detail":"More detail","copyable_prompt":"Help me plan"}]}"""));
        insights.put("on_the_horizon", MAPPER.readTree("""
            {"intro":"Horizon intro",
             "opportunities":[{"title":"Agents","whats_possible":"Parallel work",
                               "how_to_try":"Spawn a subagent","copyable_prompt":"Run agents"}]}"""));
        insights.put("cc_team_improvements", MAPPER.readTree("""
            {"improvements":[{"title":"Faster paste","detail":"Paste is slow","evidence":"3 sessions"}]}"""));
        insights.put("model_behavior_improvements", MAPPER.readTree("""
            {"improvements":[{"title":"Less verbose","detail":"Too chatty"}]}"""));
        insights.put("fun_ending", MAPPER.readTree("""
            {"headline":"You ship at 2am","detail":"Night owl"}"""));
        return insights;
    }

    private static Map<String, Long> orderedMap(Object... kv) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], (Long) kv[i + 1]);
        }
        return map;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
