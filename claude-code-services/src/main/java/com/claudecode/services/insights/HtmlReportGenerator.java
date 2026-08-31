package com.claudecode.services.insights;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.text.XmlEscaper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders the {@code /insights} HTML report from {@link AggregatedData} plus the LLM-generated
 * insight sections (section name → parsed JSON).
 */
public final class HtmlReportGenerator {


    public static final Map<String, String> LABEL_MAP;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // Goal categories
        m.put("debug_investigate", "Debug/Investigate");
        m.put("implement_feature", "Implement Feature");
        m.put("fix_bug", "Fix Bug");
        m.put("write_script_tool", "Write Script/Tool");
        m.put("refactor_code", "Refactor Code");
        m.put("configure_system", "Configure System");
        m.put("create_pr_commit", "Create PR/Commit");
        m.put("analyze_data", "Analyze Data");
        m.put("understand_codebase", "Understand Codebase");
        m.put("write_tests", "Write Tests");
        m.put("write_docs", "Write Docs");
        m.put("deploy_infra", "Deploy/Infra");
        m.put("warmup_minimal", "Cache Warmup");
        // Success factors
        m.put("fast_accurate_search", "Fast/Accurate Search");
        m.put("correct_code_edits", "Correct Code Edits");
        m.put("good_explanations", "Good Explanations");
        m.put("proactive_help", "Proactive Help");
        m.put("multi_file_changes", "Multi-file Changes");
        m.put("handled_complexity", "Multi-file Changes");
        m.put("good_debugging", "Good Debugging");
        // Friction types
        m.put("misunderstood_request", "Misunderstood Request");
        m.put("wrong_approach", "Wrong Approach");
        m.put("buggy_code", "Buggy Code");
        m.put("user_rejected_action", "User Rejected Action");
        m.put("claude_got_blocked", "Claude Got Blocked");
        m.put("user_stopped_early", "User Stopped Early");
        m.put("wrong_file_or_location", "Wrong File/Location");
        m.put("excessive_changes", "Excessive Changes");
        m.put("slow_or_verbose", "Slow/Verbose");
        m.put("tool_failed", "Tool Failed");
        m.put("user_unclear", "User Unclear");
        m.put("external_issue", "External Issue");
        // Satisfaction labels
        m.put("frustrated", "Frustrated");
        m.put("dissatisfied", "Dissatisfied");
        m.put("likely_satisfied", "Likely Satisfied");
        m.put("satisfied", "Satisfied");
        m.put("happy", "Happy");
        m.put("unsure", "Unsure");
        m.put("neutral", "Neutral");
        m.put("delighted", "Delighted");
        // Session types
        m.put("single_task", "Single Task");
        m.put("multi_task", "Multi Task");
        m.put("iterative_refinement", "Iterative Refinement");
        m.put("exploration", "Exploration");
        m.put("quick_question", "Quick Question");
        // Outcomes
        m.put("fully_achieved", "Fully Achieved");
        m.put("mostly_achieved", "Mostly Achieved");
        m.put("partially_achieved", "Partially Achieved");
        m.put("not_achieved", "Not Achieved");
        m.put("unclear_from_transcript", "Unclear");
        // Helpfulness
        m.put("unhelpful", "Unhelpful");
        m.put("slightly_helpful", "Slightly Helpful");
        m.put("moderately_helpful", "Moderately Helpful");
        m.put("very_helpful", "Very Helpful");
        m.put("essential", "Essential");
        LABEL_MAP = Collections.unmodifiableMap(m);
    }


    static final List<String> SATISFACTION_ORDER = List.of(
        "frustrated", "dissatisfied", "likely_satisfied", "satisfied", "happy", "unsure");


    static final List<String> OUTCOME_ORDER = List.of(
        "not_achieved", "partially_achieved", "mostly_achieved", "fully_achieved",
        "unclear_from_transcript");

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern LIST_DASH = Pattern.compile("^- ", Pattern.MULTILINE);
    private static final Pattern WORD_START = Pattern.compile("\\b\\w");

    private HtmlReportGenerator() {}

    // ------------------------------------------------------------------
    // Escaping
    // ------------------------------------------------------------------


    static String escapeHtml(String s) {
        return XmlEscaper.escapeAttribute(s);
    }

    /** Escape HTML but render {@code **bold**} as {@code <strong>}. */
    static String escapeHtmlWithBold(String text) {
        return BOLD.matcher(escapeHtml(text)).replaceAll("<strong>$1</strong>");
    }

    // ------------------------------------------------------------------
    // Charts
    // ------------------------------------------------------------------

    static String generateBarChart(Map<String, Long> data, String color) {
        return generateBarChart(data, color, 6, null);
    }

    static String generateBarChart(
        Map<String, Long> data, String color, int maxItems, List<String> fixedOrder) {
        Map<String, Long> source = data == null ? Map.of() : data;

        List<Map.Entry<String, Long>> entries;
        if (fixedOrder != null) {
            // Use fixed order, only including items that exist in data
            entries = fixedOrder.stream()
                .filter(key -> source.containsKey(key) && valueOf(source.get(key)) > 0)
                .map(key -> Map.entry(key, valueOf(source.get(key))))
                .toList();
        } else {
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(source.entrySet());
            sorted.sort((a, b) -> Long.compare(valueOf(b.getValue()), valueOf(a.getValue())));
            entries = List.copyOf(sorted.subList(0, Math.min(maxItems, sorted.size())));
        }

        if (entries.isEmpty()) return "<p class=\"empty\">No data</p>";

        long maxVal = entries.stream().mapToLong(e -> valueOf(e.getValue())).max().orElse(0);
        return entries.stream()
            .map(e -> {
                long count = valueOf(e.getValue());
                double pct = ((double) count / maxVal) * 100;
                String cleanLabel = LABEL_MAP.get(e.getKey());
                if (cleanLabel == null) cleanLabel = titleCase(e.getKey().replace('_', ' '));
                return render(HtmlReportTemplate.BAR_ROW,
                    "label", escapeHtml(cleanLabel),
                    "pct", jsNumber(pct),
                    "color", color,
                    "count", Long.toString(count));
            })
            .collect(Collectors.joining("\n"));
    }

    static String generateResponseTimeHistogram(List<Double> times) {
        List<Double> list = times == null ? List.of() : times;
        if (list.isEmpty()) return "<p class=\"empty\">No response time data</p>";

        // Create buckets (matching Python reference)
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (String key : new String[] {"2-10s", "10-30s", "30s-1m", "1-2m", "2-5m", "5-15m", ">15m"}) {
            buckets.put(key, 0L);
        }
        for (double t : list) {
            String key;
            if (t < 10) key = "2-10s";
            else if (t < 30) key = "10-30s";
            else if (t < 60) key = "30s-1m";
            else if (t < 120) key = "1-2m";
            else if (t < 300) key = "2-5m";
            else if (t < 900) key = "5-15m";
            else key = ">15m";
            buckets.merge(key, 1L, Long::sum);
        }

        long maxVal = buckets.values().stream().mapToLong(Long::longValue).max().orElse(0);
        if (maxVal == 0) return "<p class=\"empty\">No response time data</p>";

        return buckets.entrySet().stream()
            .map(e -> render(HtmlReportTemplate.BAR_ROW,
                "label", e.getKey(),
                "pct", jsNumber(((double) e.getValue() / maxVal) * 100),
                "color", "#6366f1",
                "count", Long.toString(e.getValue())))
            .collect(Collectors.joining("\n"));
    }

    static String generateTimeOfDayChart(List<Integer> messageHours) {
        List<Integer> hours = messageHours == null ? List.of() : messageHours;
        if (hours.isEmpty()) return "<p class=\"empty\">No time data</p>";

        record Period(String label, int[] range) {}
        List<Period> periods = List.of(
            new Period("Morning (6-12)", new int[] {6, 7, 8, 9, 10, 11}),
            new Period("Afternoon (12-18)", new int[] {12, 13, 14, 15, 16, 17}),
            new Period("Evening (18-24)", new int[] {18, 19, 20, 21, 22, 23}),
            new Period("Night (0-6)", new int[] {0, 1, 2, 3, 4, 5}));

        Map<Integer, Long> hourCounts = countHours(hours);
        List<Long> periodCounts = periods.stream()
            .map(p -> Arrays.stream(p.range())
                .mapToLong(h -> hourCounts.getOrDefault(h, 0L))
                .sum())
            .toList();

        long max = periodCounts.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxVal = max == 0 ? 1 : max;

        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < periods.size(); i++) {
            if (i > 0) bars.append('\n');
            bars.append(render(HtmlReportTemplate.TIME_BAR_ROW,
                "label", periods.get(i).label(),
                "pct", jsNumber(((double) periodCounts.get(i) / maxVal) * 100),
                "count", Long.toString(periodCounts.get(i))));
        }
        return "<div id=\"hour-histogram\">" + bars + "</div>";
    }

    static String getHourCountsJson(List<Integer> messageHours) {
        Map<Integer, Long> counts = countHours(messageHours == null ? List.of() : messageHours);
        return counts.entrySet().stream()
            .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
            .collect(Collectors.joining(",", "{", "}"));
    }

    private static Map<Integer, Long> countHours(List<Integer> hours) {
        Map<Integer, Long> counts = new TreeMap<>();
        for (int h : hours) counts.merge(h, 1L, Long::sum);
        return counts;
    }

    // ------------------------------------------------------------------
    // Report generation
    // ------------------------------------------------------------------


    public static String generate(AggregatedData data, Map<String, JsonNode> insights) {
        return generate(data, insights, Strings.CS.equals("ant", System.getenv("USER_TYPE")));
    }

    static String generate(AggregatedData data, Map<String, JsonNode> insights, boolean antUser) {
        Map<String, JsonNode> ins = insights == null ? Map.of() : insights;

        String scannedSuffix = "";
        Long scanned = data.totalSessionsScanned();
        if (scanned != null && scanned != 0 && scanned > data.totalSessions()) {
            scannedSuffix = " (" + localeNum(scanned) + " total)";
        }
        AggregatedData.DateRange range = data.dateRange();

        Map<String, Long> toolErrors =
            data.toolErrorCategories() == null ? Map.of() : data.toolErrorCategories();
        String toolErrorsChart = toolErrors.isEmpty()
            ? "<p class=\"empty\">No tool errors</p>"
            : generateBarChart(toolErrors, "#dc2626");

        String js = render(HtmlReportTemplate.JS,
            "hour_counts_json", getHourCountsJson(data.messageHours()));

        return render(HtmlReportTemplate.PAGE,
            "css", HtmlReportTemplate.CSS,
            "total_messages", localeNum(data.totalMessages()),
            "total_sessions", Long.toString(data.totalSessions()),
            "scanned_suffix", scannedSuffix,
            "date_start", range == null ? "" : range.start(),
            "date_end", range == null ? "" : range.end(),
            "at_a_glance", buildAtAGlance(node(ins, "at_a_glance")),
            "lines_added", localeNum(data.totalLinesAdded()),
            "lines_removed", localeNum(data.totalLinesRemoved()),
            "total_files_modified", Long.toString(data.totalFilesModified()),
            "days_active", Long.toString(data.daysActive()),
            "messages_per_day", jsNumber(data.messagesPerDay()),
            "project_areas", buildProjectAreas(node(ins, "project_areas")),
            "goal_chart", generateBarChart(data.goalCategories(), "#2563eb"),
            "tools_chart", generateBarChart(data.toolCounts(), "#0891b2"),
            "languages_chart", generateBarChart(data.languages(), "#10b981"),
            "session_types_chart", generateBarChart(data.sessionTypes(), "#8b5cf6"),
            "interaction", buildInteraction(node(ins, "interaction_style")),
            "response_time_histogram", generateResponseTimeHistogram(data.userResponseTimes()),
            "median_response_time", toFixed1(data.medianResponseTime()),
            "avg_response_time", toFixed1(data.avgResponseTime()),
            "multi_clauding", buildMultiClauding(data),
            "time_of_day_chart", generateTimeOfDayChart(data.messageHours()),
            "tool_errors_chart", toolErrorsChart,
            "what_works", buildWhatWorks(node(ins, "what_works")),
            "success_chart", generateBarChart(data.success(), "#16a34a"),
            "outcomes_chart", generateBarChart(data.outcomes(), "#8b5cf6", 6, OUTCOME_ORDER),
            "friction", buildFriction(node(ins, "friction_analysis")),
            "friction_chart", generateBarChart(data.friction(), "#dc2626"),
            "satisfaction_chart",
            generateBarChart(data.satisfaction(), "#eab308", 6, SATISFACTION_ORDER),
            "suggestions", buildSuggestions(node(ins, "suggestions")),
            "horizon", buildHorizon(node(ins, "on_the_horizon")),
            "fun_ending", buildFunEnding(node(ins, "fun_ending")),
            "team_feedback", buildTeamFeedback(
                node(ins, "cc_team_improvements"), node(ins, "model_behavior_improvements"), antUser),
            "js", js);
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    private static String buildAtAGlance(JsonNode atAGlance) {
        if (!truthy(atAGlance)) return "";
        return render(HtmlReportTemplate.AT_A_GLANCE,
            "working", glanceLine(atAGlance, "whats_working", HtmlReportTemplate.GLANCE_WORKING),
            "hindering", glanceLine(atAGlance, "whats_hindering", HtmlReportTemplate.GLANCE_HINDERING),
            "quick_wins", glanceLine(atAGlance, "quick_wins", HtmlReportTemplate.GLANCE_QUICK_WINS),
            "ambitious", glanceLine(atAGlance, "ambitious_workflows", HtmlReportTemplate.GLANCE_AMBITIOUS));
    }

    private static String glanceLine(JsonNode glance, String field, String template) {
        String text = glance.path(field).asText("");
        return text.isEmpty() ? "" : render(template, "text", escapeHtmlWithBold(text));
    }

    private static String buildProjectAreas(JsonNode projectAreas) {
        JsonNode areas = projectAreas.path("areas");
        if (!areas.isArray() || areas.isEmpty()) return "";
        StringBuilder items = new StringBuilder();
        for (JsonNode area : areas) {
            items.append(render(HtmlReportTemplate.PROJECT_AREA_ITEM,
                "name", escapeHtml(area.path("name").asText("")),
                "session_count", jsNumber(area.path("session_count").asDouble(0)),
                "description", escapeHtml(area.path("description").asText(""))));
        }
        return render(HtmlReportTemplate.PROJECT_AREAS, "items", items.toString());
    }

    private static String buildInteraction(JsonNode interactionStyle) {
        String narrative = interactionStyle.path("narrative").asText("");
        if (narrative.isEmpty()) return "";
        String keyPattern = interactionStyle.path("key_pattern").asText("");
        return render(HtmlReportTemplate.INTERACTION,
            "narrative", markdownToHtml(narrative),
            "key_pattern", keyPattern.isEmpty()
                ? ""
                : render(HtmlReportTemplate.KEY_PATTERN_LINE, "text", escapeHtml(keyPattern)));
    }

    private static String buildWhatWorks(JsonNode whatWorks) {
        JsonNode workflows = whatWorks.path("impressive_workflows");
        if (!workflows.isArray() || workflows.isEmpty()) return "";
        StringBuilder items = new StringBuilder();
        for (JsonNode wf : workflows) {
            items.append(render(HtmlReportTemplate.BIG_WIN_ITEM,
                "title", escapeHtml(wf.path("title").asText("")),
                "description", escapeHtml(wf.path("description").asText(""))));
        }
        return render(HtmlReportTemplate.WHAT_WORKS,
            "intro", introLine(whatWorks),
            "items", items.toString());
    }

    private static String buildFriction(JsonNode frictionAnalysis) {
        JsonNode categories = frictionAnalysis.path("categories");
        if (!categories.isArray() || categories.isEmpty()) return "";
        StringBuilder items = new StringBuilder();
        for (JsonNode cat : categories) {
            JsonNode examples = cat.path("examples");
            String examplesHtml = "";
            if (examples.isArray()) {
                StringBuilder lis = new StringBuilder();
                for (JsonNode ex : examples) {
                    lis.append("<li>").append(escapeHtml(ex.asText(""))).append("</li>");
                }
                examplesHtml = "<ul class=\"friction-examples\">" + lis + "</ul>";
            }
            items.append(render(HtmlReportTemplate.FRICTION_ITEM,
                "category", escapeHtml(cat.path("category").asText("")),
                "description", escapeHtml(cat.path("description").asText("")),
                "examples", examplesHtml));
        }
        return render(HtmlReportTemplate.FRICTION,
            "intro", introLine(frictionAnalysis),
            "items", items.toString());
    }

    private static String introLine(JsonNode section) {
        String intro = section.path("intro").asText("");
        return intro.isEmpty()
            ? ""
            : render(HtmlReportTemplate.SECTION_INTRO, "text", escapeHtml(intro));
    }

    private static String buildSuggestions(JsonNode suggestions) {
        if (!truthy(suggestions)) return "";

        String claudeMd = "";
        JsonNode additions = suggestions.path("claude_md_additions");
        if (additions.isArray() && !additions.isEmpty()) {
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < additions.size(); i++) {
                JsonNode add = additions.get(i);
                String head = firstNonEmpty(
                    add.path("prompt_scaffold").asText(""),
                    add.path("where").asText(""),
                    "Add to CLAUDE.md");
                items.append(render(HtmlReportTemplate.CLAUDE_MD_ITEM,
                    "i", Integer.toString(i),
                    "data_head", escapeHtml(head),
                    "addition", escapeHtml(add.path("addition").asText("")),
                    "why", escapeHtml(add.path("why").asText(""))));
            }
            claudeMd = render(HtmlReportTemplate.CLAUDE_MD_BLOCK, "items", items.toString());
        }

        String features = "";
        JsonNode featuresToTry = suggestions.path("features_to_try");
        if (featuresToTry.isArray() && !featuresToTry.isEmpty()) {
            StringBuilder items = new StringBuilder();
            for (JsonNode feat : featuresToTry) {
                String exampleCode = feat.path("example_code").asText("");
                items.append(render(HtmlReportTemplate.FEATURE_ITEM,
                    "feature", escapeHtml(feat.path("feature").asText("")),
                    "one_liner", escapeHtml(feat.path("one_liner").asText("")),
                    "why_for_you", escapeHtml(feat.path("why_for_you").asText("")),
                    "example", exampleCode.isEmpty()
                        ? ""
                        : render(HtmlReportTemplate.FEATURE_EXAMPLE,
                            "example_code", escapeHtml(exampleCode))));
            }
            features = render(HtmlReportTemplate.FEATURES_BLOCK, "items", items.toString());
        }

        String patterns = "";
        JsonNode usagePatterns = suggestions.path("usage_patterns");
        if (usagePatterns.isArray() && !usagePatterns.isEmpty()) {
            StringBuilder items = new StringBuilder();
            for (JsonNode pat : usagePatterns) {
                String detail = pat.path("detail").asText("");
                String prompt = pat.path("copyable_prompt").asText("");
                items.append(render(HtmlReportTemplate.PATTERN_ITEM,
                    "title", escapeHtml(pat.path("title").asText("")),
                    "suggestion", escapeHtml(pat.path("suggestion").asText("")),
                    "detail", detail.isEmpty()
                        ? ""
                        : render(HtmlReportTemplate.PATTERN_DETAIL_LINE, "text", escapeHtml(detail)),
                    "prompt", prompt.isEmpty()
                        ? ""
                        : render(HtmlReportTemplate.PATTERN_PROMPT,
                            "copyable_prompt", escapeHtml(prompt))));
            }
            patterns = render(HtmlReportTemplate.PATTERNS_BLOCK, "items", items.toString());
        }

        return render(HtmlReportTemplate.SUGGESTIONS,
            "claude_md", claudeMd, "features", features, "patterns", patterns);
    }

    private static String buildHorizon(JsonNode horizon) {
        JsonNode opportunities = horizon.path("opportunities");
        if (!opportunities.isArray() || opportunities.isEmpty()) return "";
        StringBuilder items = new StringBuilder();
        for (JsonNode opp : opportunities) {
            String howToTry = opp.path("how_to_try").asText("");
            String prompt = opp.path("copyable_prompt").asText("");
            items.append(render(HtmlReportTemplate.HORIZON_ITEM,
                "title", escapeHtml(opp.path("title").asText("")),
                "whats_possible", escapeHtml(opp.path("whats_possible").asText("")),
                "tip", howToTry.isEmpty()
                    ? ""
                    : render(HtmlReportTemplate.HORIZON_TIP_LINE, "text", escapeHtml(howToTry)),
                "prompt", prompt.isEmpty()
                    ? ""
                    : render(HtmlReportTemplate.HORIZON_PROMPT_LINE, "text", escapeHtml(prompt))));
        }
        return render(HtmlReportTemplate.HORIZON,
            "intro", introLine(horizon),
            "items", items.toString());
    }

    private static String buildTeamFeedback(JsonNode cc, JsonNode model, boolean antUser) {
        JsonNode ccImprovements = antUser ? cc.path("improvements") : MissingNode.getInstance();
        JsonNode modelImprovements = antUser ? model.path("improvements") : MissingNode.getInstance();
        int ccCount = ccImprovements.isArray() ? ccImprovements.size() : 0;
        int modelCount = modelImprovements.isArray() ? modelImprovements.size() : 0;
        if (ccCount == 0 && modelCount == 0) return "";

        String ccBlock = ccCount == 0 ? "" : render(HtmlReportTemplate.COLLAPSIBLE_BLOCK,
            "heading", "Product Improvements for CC Team",
            "items", feedbackItems(ccImprovements, "team-card"));
        String modelBlock = modelCount == 0 ? "" : render(HtmlReportTemplate.COLLAPSIBLE_BLOCK,
            "heading", "Model Behavior Improvements",
            "items", feedbackItems(modelImprovements, "model-card"));

        return render(HtmlReportTemplate.TEAM_FEEDBACK,
            "cc_block", ccBlock, "model_block", modelBlock);
    }

    private static String feedbackItems(JsonNode improvements, String cardClass) {
        StringBuilder items = new StringBuilder();
        for (JsonNode imp : improvements) {
            String evidence = imp.path("evidence").asText("");
            items.append(render(HtmlReportTemplate.FEEDBACK_ITEM,
                "card_class", cardClass,
                "title", escapeHtml(imp.path("title").asText("")),
                "detail", escapeHtml(imp.path("detail").asText("")),
                "evidence", evidence.isEmpty()
                    ? ""
                    : render(HtmlReportTemplate.FEEDBACK_EVIDENCE_LINE,
                        "text", escapeHtml(evidence))));
        }
        return items.toString();
    }

    private static String buildFunEnding(JsonNode funEnding) {
        String headline = funEnding.path("headline").asText("");
        if (headline.isEmpty()) return "";
        String detail = funEnding.path("detail").asText("");
        return render(HtmlReportTemplate.FUN_ENDING,
            "headline", escapeHtml(headline),
            "detail", detail.isEmpty()
                ? ""
                : render(HtmlReportTemplate.FUN_DETAIL_LINE, "text", escapeHtml(detail)));
    }

    private static String buildMultiClauding(AggregatedData data) {
        AggregatedData.MultiClauding mc = data.multiClauding();
        long overlapEvents = mc == null ? 0 : mc.overlapEvents();
        if (overlapEvents == 0) return HtmlReportTemplate.MULTI_CLAUDING_NONE;
        long pct = data.totalMessages() > 0
            ? Math.round((100.0 * mc.userMessagesDuring()) / data.totalMessages())
            : 0;
        return render(HtmlReportTemplate.MULTI_CLAUDING_STATS,
            "overlap_events", Long.toString(overlapEvents),
            "sessions_involved", Long.toString(mc.sessionsInvolved()),
            "pct_of_messages", Long.toString(pct));
    }


    static String markdownToHtml(String md) {
        if (StringUtils.isEmpty(md)) return "";
        return Arrays.stream(md.split("\n\n", -1))
            .map(p -> {
                String html = escapeHtml(p);
                html = BOLD.matcher(html).replaceAll("<strong>$1</strong>");
                html = LIST_DASH.matcher(html).replaceAll("• ");
                html = html.replace("\n", "<br>");
                return "<p>" + html + "</p>";
            })
            .collect(Collectors.joining("\n"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Substitutes {@code ${key}} markers in a template. Values are inserted
     * verbatim and never rescanned, so user data can safely contain markers.
     */
    static String render(String template, String... keyValues) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        StringBuilder out = new StringBuilder(template.length() + 256);
        int pos = 0;
        while (true) {
            int start = template.indexOf("${", pos);
            if (start < 0) {
                out.append(template, pos, template.length());
                return out.toString();
            }
            out.append(template, pos, start);
            int end = template.indexOf('}', start + 2);
            if (end < 0) throw new IllegalStateException("Unterminated ${...} in template");
            String key = template.substring(start + 2, end);
            String value = values.get(key);
            if (value == null) throw new IllegalStateException("Unbound template key: " + key);
            out.append(value);
            pos = end + 1;
        }
    }

    private static JsonNode node(Map<String, JsonNode> insights, String key) {
        JsonNode n = insights.get(key);
        return n == null ? MissingNode.getInstance() : n;
    }

    private static boolean truthy(JsonNode n) {
        return n != null && !n.isMissingNode() && !n.isNull();
    }

    private static long valueOf(Long boxed) {
        return boxed == null ? 0 : boxed;
    }

    private static String firstNonEmpty(String a, String b, String fallback) {
        if (!a.isEmpty()) return a;
        return b.isEmpty() ? fallback : b;
    }


    private static String titleCase(String s) {
        Matcher m = WORD_START.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb,
                Matcher.quoteReplacement(m.group().toUpperCase(Locale.ROOT)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String jsNumber(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "Infinity" : "-Infinity";
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String toFixed1(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "Infinity" : "-Infinity";
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }


    private static String localeNum(long value) {
        return String.format(Locale.US, "%,d", value);
    }
}
