package com.claudecode.services.insights;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.cost.ApiCallAccounting;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;


public final class InsightsGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(InsightsGenerator.class);


    private record InsightSection(String name, String prompt, int maxTokens) {}

    private static final String PROJECT_AREAS_PROMPT = """
        Analyze this Claude Code usage data and identify project areas.

        RESPOND WITH ONLY A VALID JSON OBJECT:
        {
          "areas": [
            {"name": "Area name", "session_count": N, "description": "2-3 sentences about what was worked on and how Claude Code was used."}
          ]
        }

        Include 4-5 areas. Skip internal CC operations.\
        """;

    private static final String INTERACTION_STYLE_PROMPT = """
        Analyze this Claude Code usage data and describe the user's interaction style.

        RESPOND WITH ONLY A VALID JSON OBJECT:
        {
          "narrative": "2-3 paragraphs analyzing HOW the user interacts with Claude Code. Use second person 'you'. Describe patterns: iterate quickly vs detailed upfront specs? Interrupt often or let Claude run? Include specific examples. Use **bold** for key insights.",
          "key_pattern": "One sentence summary of most distinctive interaction style"
        }\
        """;

    private static final String WHAT_WORKS_PROMPT = """
        Analyze this Claude Code usage data and identify what's working well for this user. Use second person ("you").

        RESPOND WITH ONLY A VALID JSON OBJECT:
        {
          "intro": "1 sentence of context",
          "impressive_workflows": [
            {"title": "Short title (3-6 words)", "description": "2-3 sentences describing the impressive workflow or approach. Use 'you' not 'the user'."}
          ]
        }

        Include 3 impressive workflows.\
        """;

    private static final String FRICTION_ANALYSIS_PROMPT = """
        Analyze this Claude Code usage data and identify friction points for this user. Use second person ("you").

        RESPOND WITH ONLY A VALID JSON OBJECT:
        {
          "intro": "1 sentence summarizing friction patterns",
          "categories": [
            {"category": "Concrete category name", "description": "1-2 sentences explaining this category and what could be done differently. Use 'you' not 'the user'.", "examples": ["Specific example with consequence", "Another example"]}
          ]
        }

        Include 3 friction categories with 2 examples each.\
        """;

    private static final String SUGGESTIONS_PROMPT = """
        Analyze this Claude Code usage data and suggest improvements.

        ## CC FEATURES REFERENCE (pick from these for features_to_try):
        1. **MCP Servers**: Connect Claude to external tools, databases, and APIs via Model Context Protocol.
           - How to use: Run `claude mcp add <server-name> -- <command>`
           - Good for: database queries, Slack integration, GitHub issue lookup, connecting to internal APIs

        2. **Custom Skills**: Reusable prompts you define as markdown files that run with a single /command.
           - How to use: Create `.claude/skills/commit/SKILL.md` with instructions. Then type `/commit` to run it.
           - Good for: repetitive workflows - /commit, /review, /test, /deploy, /pr, or complex multi-step workflows

        3. **Hooks**: Shell commands that auto-run at specific lifecycle events.
           - How to use: Add to `.claude/settings.json` under "hooks" key.
           - Good for: auto-formatting code, running type checks, enforcing conventions

        4. **Headless Mode**: Run Claude non-interactively from scripts and CI/CD.
           - How to use: `claude -p "fix lint errors" --allowedTools "Edit,Read,Bash"`
           - Good for: CI/CD integration, batch code fixes, automated reviews

        5. **Task Agents**: Claude spawns focused sub-agents for complex exploration or parallel work.
           - How to use: Claude auto-invokes when helpful, or ask "use an agent to explore X"
           - Good for: codebase exploration, understanding complex systems

        RESPOND WITH ONLY A VALID JSON OBJECT:
        {
          "claude_md_additions": [
            {"addition": "A specific line or block to add to CLAUDE.md based on workflow patterns. E.g., 'Always run tests after modifying auth-related files'", "why": "1 sentence explaining why this would help based on actual sessions", "prompt_scaffold": "Instructions for where to add this in CLAUDE.md. E.g., 'Add under ## Testing section'"}
          ],
          "features_to_try": [
            {"feature": "Feature name from CC FEATURES REFERENCE above", "one_liner": "What it does", "why_for_you": "Why this would help YOU based on your sessions", "example_code": "Actual command or config to copy"}
          ],
          "usage_patterns": [
            {"title": "Short title", "suggestion": "1-2 sentence summary", "detail": "3-4 sentences explaining how this applies to YOUR work", "copyable_prompt": "A specific prompt to copy and try"}
          ]
        }

        IMPORTANT for claude_md_additions: PRIORITIZE instructions that appear MULTIPLE TIMES in the user data. If user told Claude the same thing in 2+ sessions (e.g., 'always run tests', 'use TypeScript'), that's a PRIME candidate - they shouldn't have to repeat themselves.

        IMPORTANT for features_to_try: Pick 2-3 from the CC FEATURES REFERENCE above. Include 2-3 items for each category.\
        """;

    private static final String ON_THE_HORIZON_PROMPT = """
        Analyze this Claude Code usage data and identify future opportunities.

        RESPOND WITH ONLY A VALID JSON OBJECT:
        {
          "intro": "1 sentence about evolving AI-assisted development",
          "opportunities": [
            {"title": "Short title (4-8 words)", "whats_possible": "2-3 ambitious sentences about autonomous workflows", "how_to_try": "1-2 sentences mentioning relevant tooling", "copyable_prompt": "Detailed prompt to try"}
          ]
        }

        Include 3 opportunities. Think BIG - autonomous workflows, parallel agents, iterating against tests.\
        """;

    private static final String CC_TEAM_IMPROVEMENTS_PROMPT = """
        Analyze this Claude Code usage data and suggest product improvements for the CC team.

        RESPOND WITH ONLY A VALID JSON OBJECT:
        {
          "improvements": [
            {"title": "Product/tooling improvement", "detail": "3-4 sentences describing the improvement", "evidence": "3-4 sentences with specific session examples"}
          ]
        }

        Include 2-3 improvements based on friction patterns observed.\
        """;

    private static final String MODEL_BEHAVIOR_IMPROVEMENTS_PROMPT = """
        Analyze this Claude Code usage data and suggest model behavior improvements.

        RESPOND WITH ONLY A VALID JSON OBJECT:
        {
          "improvements": [
            {"title": "Model behavior change", "detail": "3-4 sentences describing what the model should do differently", "evidence": "3-4 sentences with specific examples"}
          ]
        }

        Include 2-3 improvements based on friction patterns observed.\
        """;

    private static final String FUN_ENDING_PROMPT = """
        Analyze this Claude Code usage data and find a memorable moment.

        RESPOND WITH ONLY A VALID JSON OBJECT:
        {
          "headline": "A memorable QUALITATIVE moment from the transcripts - not a statistic. Something human, funny, or surprising.",
          "detail": "Brief context about when/where this happened"
        }

        Find something genuinely interesting or amusing from the session summaries.\
        """;


    private static final String AT_A_GLANCE_PROMPT_PREFIX = """
        You're writing an "At a Glance" summary for a Claude Code usage insights report for Claude Code users. The goal is to help them understand their usage and improve how they can use Claude better, especially as models improve.

        Use this 4-part structure:

        1. **What's working** - What is the user's unique style of interacting with Claude and what are some impactful things they've done? You can include one or two details, but keep it high level since things might not be fresh in the user's memory. Don't be fluffy or overly complimentary. Also, don't focus on the tool calls they use.

        2. **What's hindering you** - Split into (a) Claude's fault (misunderstandings, wrong approaches, bugs) and (b) user-side friction (not providing enough context, environment issues -- ideally more general than just one project). Be honest but constructive.

        3. **Quick wins to try** - Specific Claude Code features they could try from the examples below, or a workflow technique if you think it's really compelling. (Avoid stuff like "Ask Claude to confirm before taking actions" or "Type out more context up front" which are less compelling.)

        4. **Ambitious workflows for better models** - As we move to much more capable models over the next 3-6 months, what should they prepare for? What workflows that seem impossible now will become possible? Draw from the appropriate section below.

        Keep each section to 2-3 not-too-long sentences. Don't overwhelm the user. Don't mention specific numerical stats or underlined_categories from the session data below. Use a coaching tone.

        RESPOND WITH ONLY A VALID JSON OBJECT:
        {
          "whats_working": "(refer to instructions above)",
          "whats_hindering": "(refer to instructions above)",
          "quick_wins": "(refer to instructions above)",
          "ambitious_workflows": "(refer to instructions above)"
        }

        SESSION DATA:
        """;

    private final LlmClient llmClient;
    private final Supplier<String> modelSupplier;


    public InsightsGenerator(LlmClient llmClient, Supplier<String> modelSupplier) {
        this.llmClient = llmClient;
        this.modelSupplier = modelSupplier;
    }


    public Map<String, JsonNode> generate(AggregatedData data, Map<String, SessionFacets> facets) {
        String fullContext = buildFullContext(data, facets);

        // Run sections in parallel first (excluding at_a_glance)
        List<InsightSection> sections = buildSections();
        Map<String, JsonNode> insights = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JsonNode>> futures = new ArrayList<>(sections.size());
            for (InsightSection section : sections) {
                futures.add(executor.submit(() -> generateSectionInsight(section, fullContext)));
            }
            for (int i = 0; i < sections.size(); i++) {
                JsonNode result;
                try {
                    result = futures.get(i).get();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    result = null;
                } catch (Exception _) {
                    result = null;
                }
                if (result != null) {
                    insights.put(sections.get(i).name(), result);
                }
            }
        }

        // Build rich context from generated sections for At a Glance
        String projectAreasText = joinItems(insights.get("project_areas"), "areas", "name", "description");
        String bigWinsText = joinItems(insights.get("what_works"), "impressive_workflows", "title", "description");
        String frictionText = joinItems(insights.get("friction_analysis"), "categories", "category", "description");
        String featuresText = joinItems(insights.get("suggestions"), "features_to_try", "feature", "one_liner");
        String patternsText = joinItems(insights.get("suggestions"), "usage_patterns", "title", "suggestion");
        String horizonText = joinItems(insights.get("on_the_horizon"), "opportunities", "title", "whats_possible");

        // Now generate "At a Glance" with access to other sections' outputs
        String atAGlancePrompt = AT_A_GLANCE_PROMPT_PREFIX + fullContext
            + "\n\n## Project Areas (what user works on)\n" + projectAreasText
            + "\n\n## Big Wins (impressive accomplishments)\n" + bigWinsText
            + "\n\n## Friction Categories (where things go wrong)\n" + frictionText
            + "\n\n## Features to Try\n" + featuresText
            + "\n\n## Usage Patterns to Adopt\n" + patternsText
            + "\n\n## On the Horizon (ambitious workflows for better models)\n" + horizonText;

        JsonNode atAGlance = generateSectionInsight(
            new InsightSection("at_a_glance", atAGlancePrompt, 8192), "");
        if (atAGlance != null) {
            insights.put("at_a_glance", atAGlance);
        }

        return insights;
    }


    public static String toJson(Map<String, JsonNode> insights) {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        insights.forEach(root::set);
        return jsStringify(root);
    }


    private static List<InsightSection> buildSections() {
        List<InsightSection> sections = new ArrayList<>(List.of(
            new InsightSection("project_areas", PROJECT_AREAS_PROMPT, 8192),
            new InsightSection("interaction_style", INTERACTION_STYLE_PROMPT, 8192),
            new InsightSection("what_works", WHAT_WORKS_PROMPT, 8192),
            new InsightSection("friction_analysis", FRICTION_ANALYSIS_PROMPT, 8192),
            new InsightSection("suggestions", SUGGESTIONS_PROMPT, 8192),
            new InsightSection("on_the_horizon", ON_THE_HORIZON_PROMPT, 8192)));
        if (Strings.CS.equals("ant", System.getenv("USER_TYPE"))) {
            sections.add(new InsightSection("cc_team_improvements", CC_TEAM_IMPROVEMENTS_PROMPT, 8192));
            sections.add(new InsightSection("model_behavior_improvements", MODEL_BEHAVIOR_IMPROVEMENTS_PROMPT, 8192));
        }
        sections.add(new InsightSection("fun_ending", FUN_ENDING_PROMPT, 8192));
        return sections;
    }


    private JsonNode generateSectionInsight(InsightSection section, String dataContext) {
        try {
            ApiMessage response = ApiCallAccounting.createMessage(llmClient,
                CreateMessageRequest.builder()
                .model(modelSupplier.get())
                .maxTokens(section.maxTokens())
                .messages(List.of(new CreateMessageRequest.RequestMessage(
                    "user", section.prompt() + "\n\nDATA:\n" + dataContext)))
                .stream(false)
                .querySource("insights")
                .build());

            String text = FacetExtractor.extractTextContent(response);
            if (text.isEmpty()) return null;

            String json = FacetExtractor.extractJsonObject(text);
            if (json == null) return null;
            try {
                return JsonUtils.getMapper().readTree(json);
            } catch (Exception _) {
                return null;
            }
        } catch (Exception e) {
            LOG.warn("{} failed: {}", section.name(), e.getMessage());
            return null;
        }
    }


    static String buildFullContext(AggregatedData data, Map<String, SessionFacets> facets) {
        StringBuilder facetSummaries = new StringBuilder();
        int summaryCount = 0;
        for (SessionFacets f : facets.values()) {
            if (summaryCount >= 50) break;
            if (summaryCount > 0) facetSummaries.append('\n');
            facetSummaries.append("- ").append(f.briefSummary())
                .append(" (").append(f.outcome()).append(", ").append(f.claudeHelpfulness()).append(')');
            summaryCount++;
        }

        StringBuilder frictionDetails = new StringBuilder();
        int frictionCount = 0;
        for (SessionFacets f : facets.values()) {
            if (frictionCount >= 20) break;
            if (StringUtils.isEmpty(f.frictionDetail())) continue;
            if (frictionCount > 0) frictionDetails.append('\n');
            frictionDetails.append("- ").append(f.frictionDetail());
            frictionCount++;
        }

        StringBuilder userInstructions = new StringBuilder();
        int instructionCount = 0;
        outer:
        for (SessionFacets f : facets.values()) {
            if (f.userInstructionsToClaude() == null) continue;
            for (String instruction : f.userInstructionsToClaude()) {
                if (instructionCount >= 15) break outer;
                if (instructionCount > 0) userInstructions.append('\n');
                userInstructions.append("- ").append(instruction);
                instructionCount++;
            }
        }

        String userInstructionsText = userInstructions.toString();
        return buildDataContext(data)
            + "\n\nSESSION SUMMARIES:\n" + facetSummaries
            + "\n\nFRICTION DETAILS:\n" + frictionDetails
            + "\n\nUSER INSTRUCTIONS TO CLAUDE:\n"
            + (userInstructionsText.isEmpty() ? "None captured" : userInstructionsText);
    }


    static String buildDataContext(AggregatedData data) {
        ObjectNode ctx = JsonUtils.getMapper().createObjectNode();
        ctx.put("sessions", data.totalSessions());
        ctx.put("analyzed", data.sessionsWithFacets());
        ObjectNode dateRange = ctx.putObject("date_range");
        dateRange.put("start", data.dateRange() != null ? data.dateRange().start() : "");
        dateRange.put("end", data.dateRange() != null ? data.dateRange().end() : "");
        ctx.put("messages", data.totalMessages());
        ctx.put("hours", Math.round(data.totalDurationHours()));
        ctx.put("commits", data.gitCommits());
        ctx.set("top_tools", topEntries(data.toolCounts(), 8));
        ctx.set("top_goals", topEntries(data.goalCategories(), 8));
        ctx.set("outcomes", countsNode(data.outcomes()));
        ctx.set("satisfaction", countsNode(data.satisfaction()));
        ctx.set("friction", countsNode(data.friction()));
        ctx.set("success", countsNode(data.success()));
        ctx.set("languages", countsNode(data.languages()));
        return jsStringify(ctx);
    }


    private static ArrayNode topEntries(Map<String, Long> counts, int limit) {
        ArrayNode result = JsonUtils.getMapper().createArrayNode();
        if (counts == null) return result;
        counts.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .forEach(e -> {
                ArrayNode pair = result.addArray();
                pair.add(e.getKey());
                pair.add(e.getValue());
            });
        return result;
    }

    private static ObjectNode countsNode(Map<String, Long> counts) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        if (counts != null) {
            counts.forEach(node::put);
        }
        return node;
    }


    private static String joinItems(JsonNode section, String arrayField, String firstField, String secondField) {
        if (section == null) return "";
        JsonNode array = section.get(arrayField);
        if (array == null || !array.isArray() || array.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : array) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("- ").append(item.path(firstField).asText())
                .append(": ").append(item.path(secondField).asText());
        }
        return sb.toString();
    }

    /** {@code JSON.stringify(node, null, 2)}, byte-for-byte. */
    static String jsStringify(JsonNode node) {
        try {
            return JsonUtils.getMapper().writer(new JsPrettyPrinter()).writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Jackson pretty printer tuned to emit exactly what
     * {@code JSON.stringify(value, null, 2)} emits: 2-space indent with LF
     * newlines for objects <em>and</em> arrays, {@code ": "} field separators
     * (no space before the colon), and {@code {}}/{@code []} for empty
     * containers.
     */
    private static final class JsPrettyPrinter extends DefaultPrettyPrinter {

        JsPrettyPrinter() {
            DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
            indentObjectsWith(indenter);
            indentArraysWith(indenter);
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new JsPrettyPrinter();
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(": ");
        }

        @Override
        public void writeEndObject(JsonGenerator g, int nrOfEntries) throws IOException {
            if (!_objectIndenter.isInline()) {
                --_nesting;
            }
            if (nrOfEntries > 0) {
                _objectIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw('}');
        }

        @Override
        public void writeEndArray(JsonGenerator g, int nrOfValues) throws IOException {
            if (!_arrayIndenter.isInline()) {
                --_nesting;
            }
            if (nrOfValues > 0) {
                _arrayIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw(']');
        }
    }
}
