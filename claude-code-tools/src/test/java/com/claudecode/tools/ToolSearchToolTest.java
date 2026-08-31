package com.claudecode.tools;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.RawBlocksOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolReferenceBlock;
import com.claudecode.permissions.PermissionDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ToolSearchTool}'s rewritten {@code call} — select:/+prefix/ keyword/{@code
 * mcp__}-prefix matching, and the {@code tool_reference}-vs-plain-text output shape.
 */
class ToolSearchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

/** A fake deferred tool — shouldDefer true, real description for scoring. */
    private static Tool<Object, String> fakeDeferredTool(String name, String description) {
        return new Tool<>() {
            @Override public ToolIdentity identity() { return new ToolIdentity(name); }
            @Override public String description() { return description; }
            @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
            @Override public String call(Object input, ToolExecutionContext context) { return "n/a"; }
            @Override public boolean shouldDefer() { return true; }
        };
    }

    private static Tool<Object, String> hintedDeferredTool(String name, String description, String hint) {
        return new Tool<>() {
            @Override public ToolIdentity identity() { return new ToolIdentity(name); }
            @Override public String description() { return description; }
            @Override public String searchHint() { return hint; }
            @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
            @Override public String call(Object input, ToolExecutionContext context) { return "n/a"; }
            @Override public boolean shouldDefer() { return true; }
        };
    }

    private ToolRegistry registryWith(Tool<?, ?>... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool<?, ?> t : tools) registry.register(t);
        return registry;
    }

    private ObjectNode query(String q) {
        ObjectNode node = mapper.createObjectNode();
        node.put("query", q);
        return node;
    }

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    private static List<String> referencedNames(ToolSearchTool.Output output) {
        return output.modelContent().blocks().stream()
            .filter(ToolReferenceBlock.class::isInstance)
            .map(b -> ((ToolReferenceBlock) b).toolName())
            .toList();
    }

    @Test
    void permissionCheck_allowsSilentInternalDiscovery() {
        ToolSearchTool tool = new ToolSearchTool(new ToolRegistry());

        assertInstanceOf(PermissionDecision.Allow.class,
            tool.checkPermissions(mapper.createObjectNode(), null));
    }

    @Test
    void selectPrefix_findsExactDeferredToolsByName() {
        ToolRegistry registry = registryWith(
            fakeDeferredTool("WebFetch", "Fetches a URL"),
            fakeDeferredTool("WebSearch", "Searches the web"));
        ToolSearchTool tool = new ToolSearchTool(registry);

        ToolSearchTool.Output result = tool.call(query("select:WebFetch,WebSearch"), ctx());

        assertEquals(List.of("WebFetch", "WebSearch"), referencedNames(result));
        assertEquals(List.of("WebFetch", "WebSearch"), result.matches());
        assertEquals("select:WebFetch,WebSearch", result.query());
        assertEquals(2, result.totalDeferredTools());
        assertTrue(result.pendingMcpServers().isEmpty());
    }

    @Test
    void selectPrefix_noneFound_returnsPlainTextNotToolReference() {
        ToolRegistry registry = registryWith(fakeDeferredTool("WebFetch", "Fetches a URL"));
        ToolSearchTool tool = new ToolSearchTool(registry);

        ToolSearchTool.Output result = tool.call(query("select:DoesNotExist"), ctx());

        assertEquals(1, result.modelContent().blocks().size());
        assertInstanceOf(TextBlock.class, result.modelContent().blocks().getFirst());
        assertEquals("No matching deferred tools found",
            ((TextBlock) result.modelContent().blocks().getFirst()).text());
        assertTrue(result.matches().isEmpty());
    }

    @Test
    void selectPrefix_partialMatch_returnsOnlyFound() {
        ToolRegistry registry = registryWith(fakeDeferredTool("WebFetch", "Fetches a URL"));
        ToolSearchTool tool = new ToolSearchTool(registry);

        ToolSearchTool.Output result = tool.call(query("select:WebFetch,Nonexistent"), ctx());

        assertEquals(List.of("WebFetch"), referencedNames(result));
    }

    @Test
    void exactNameFastPath_caseInsensitive() {
        ToolRegistry registry = registryWith(
            fakeDeferredTool("WebFetch", "Fetches a URL"),
            fakeDeferredTool("CronCreate", "Schedules a job"));
        ToolSearchTool tool = new ToolSearchTool(registry);

        ToolSearchTool.Output result = tool.call(query("webfetch"), ctx());

        assertEquals(List.of("WebFetch"), referencedNames(result));
    }

    @Test
    void mcpPrefixSearch_matchesByServerName() {
        ToolRegistry registry = registryWith(
            fakeDeferredTool("mcp__slack__send_message", "Send a Slack message"),
            fakeDeferredTool("mcp__slack__list_channels", "List Slack channels"),
            fakeDeferredTool("mcp__github__create_issue", "Create a GitHub issue"));
        ToolSearchTool tool = new ToolSearchTool(registry);

        ToolSearchTool.Output result = tool.call(query("mcp__slack"), ctx());

        assertEquals(2, referencedNames(result).size());
        assertTrue(referencedNames(result).containsAll(
            List.of("mcp__slack__send_message", "mcp__slack__list_channels")));
    }

    @Test
    void keywordSearch_scoresNamePartsHigherThanDescription() {
        ToolRegistry registry = registryWith(
            fakeDeferredTool("NotebookEdit", "Edit a Jupyter notebook cell"),
            fakeDeferredTool("WebFetch", "Fetches content, sometimes from notebook-hosting sites"));
        ToolSearchTool tool = new ToolSearchTool(registry);

        ToolSearchTool.Output result = tool.call(query("notebook"), ctx());


        // in the description (score 2) — NotebookEdit must rank first.
        assertEquals("NotebookEdit", referencedNames(result).getFirst());
    }

    @Test
    void keywordSearch_requiredTermFiltersCandidates() {
        ToolRegistry registry = registryWith(
            fakeDeferredTool("mcp__slack__send_message", "Send a Slack message"),
            fakeDeferredTool("mcp__github__send_notification", "Send a GitHub notification"));
        ToolSearchTool tool = new ToolSearchTool(registry);

        ToolSearchTool.Output result = tool.call(query("+slack send"), ctx());

        assertEquals(List.of("mcp__slack__send_message"), referencedNames(result));
    }

    @Test
    void keywordSearch_consumesSearchHintForRankingAndRequiredTerms() {
        ToolRegistry registry = registryWith(
            hintedDeferredTool("CalendarAction", "Performs an operation", "schedule calendar events"),
            fakeDeferredTool("WebFetch", "Fetches a URL"));
        ToolSearchTool tool = new ToolSearchTool(registry);

        assertEquals(List.of("CalendarAction"), referencedNames(tool.call(query("schedule"), ctx())));
        assertEquals(List.of("CalendarAction"), referencedNames(tool.call(query("+calendar events"), ctx())));
    }

    @Test
    void alwaysLoadTool_isNotDeferredEvenWhenShouldDeferIsTrue() {
        Tool<Object, String> alwaysLoad = new Tool<>() {
            @Override public ToolIdentity identity() { return new ToolIdentity("AlwaysReady"); }
            @Override public String description() { return "always ready"; }
            @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
            @Override public String call(Object input, ToolExecutionContext context) { return "n/a"; }
            @Override public boolean shouldDefer() { return true; }
            @Override public boolean alwaysLoad() { return true; }
        };
        assertFalse(ToolSearchTool.isDeferredTool(alwaysLoad));
    }

    @Test
    void noMatches_mentionsPendingMcpServers() {
        ToolSearchTool tool = new ToolSearchTool(
            registryWith(fakeDeferredTool("WebFetch", "Fetches a URL")),
            () -> List.of("slow-server"));

        ToolSearchTool.Output result = tool.call(query("does-not-exist"), ctx());

        assertEquals("No matching deferred tools found. Some MCP servers are still connecting: "
            + "slow-server. Their tools will become available shortly — try searching again.",
            ((TextBlock) result.modelContent().blocks().getFirst()).text());
        assertEquals(List.of("slow-server"), result.pendingMcpServers());
    }

    @Test
    void keywordSearch_noMatches_returnsPlainText() {
        ToolRegistry registry = registryWith(fakeDeferredTool("WebFetch", "Fetches a URL"));
        ToolSearchTool tool = new ToolSearchTool(registry);

        ToolSearchTool.Output result = tool.call(query("zzz_nonexistent_keyword"), ctx());

        assertEquals(1, result.modelContent().blocks().size());
        assertInstanceOf(TextBlock.class, result.modelContent().blocks().getFirst());
    }

    @Test
    void nonDeferredTools_areExcludedFromSearch() {
        ToolRegistry registry = registryWith(
            fakeDeferredTool("WebFetch", "Fetches a URL"));
        // A non-deferred tool with the same query term must not be found by keyword search.
        registry.register(new Tool<Object, String>() {
            @Override public ToolIdentity identity() { return new ToolIdentity("Bash"); }
            @Override public String description() { return "Run a shell command"; }
            @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
            @Override public String call(Object input, ToolExecutionContext context) { return "n/a"; }
        });
        ToolSearchTool tool = new ToolSearchTool(registry);

        ToolSearchTool.Output result = tool.call(query("shell"), ctx());

        assertTrue(referencedNames(result).isEmpty());
    }

    @Test
    void isDeferredTool_excludesSelfAndNonDeferred_includesMcp() {
        Tool<?, ?> searchTool = new ToolSearchTool(new ToolRegistry());
        Tool<?, ?> deferred = fakeDeferredTool("WebFetch", "x");
        Tool<?, ?> nonDeferred = new Tool<Object, String>() {
            @Override public ToolIdentity identity() { return new ToolIdentity("Bash"); }
            @Override public String description() { return "x"; }
            @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
            @Override public String call(Object input, ToolExecutionContext context) { return "n/a"; }
        };
        Tool<?, ?> mcpTool = new Tool<Object, String>() {
            @Override public ToolIdentity identity() {
                return new ToolIdentity("mcp__server__thing");
            }
            @Override public String description() { return "x"; }
            @Override public boolean isMcp() { return true; }
            @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
            @Override public String call(Object input, ToolExecutionContext context) { return "n/a"; }
        };
        Tool<?, ?> prefixOnlyTool = new Tool<Object, String>() {
            @Override public ToolIdentity identity() {
                return new ToolIdentity("mcp__server__prefix_only");
            }
            @Override public String description() { return "x"; }
            @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
            @Override public String call(Object input, ToolExecutionContext context) { return "n/a"; }
        };

        assertFalse(ToolSearchTool.isDeferredTool(searchTool));
        assertTrue(ToolSearchTool.isDeferredTool(deferred));
        assertFalse(ToolSearchTool.isDeferredTool(nonDeferred));
        assertTrue(ToolSearchTool.isDeferredTool(mcpTool));
        assertFalse(ToolSearchTool.isDeferredTool(prefixOnlyTool));
    }

    @Test
    void inputSchema_marksQueryRequired() {
        ToolSearchTool tool = new ToolSearchTool(new ToolRegistry());
        JsonNode schema = tool.inputSchema();
        JsonNode required = schema.get("required");
        assertNotNull(required, "schema must declare required");
        assertTrue(required.isArray());
        boolean hasQuery = false;
        for (JsonNode r : required) {
            if (Strings.CS.equals("query", r.asText())) hasQuery = true;
        }
        assertTrue(hasQuery, "query must be required: " + required);
        assertEquals("string", schema.path("properties").path("query").path("type").asText());
        assertEquals("number", schema.path("properties").path("max_results").path("type").asText());
        assertEquals(5, schema.path("properties").path("max_results").path("default").asInt());

        assertNull(schema.get("additionalProperties"),
            "top-level schema must stay permissive (no additionalProperties:false)");
    }

    @Test
    void maxResultSizeChars_matchesTs() {

        assertEquals(100_000, new ToolSearchTool(new ToolRegistry()).maxResultSizeChars());
    }

    @Test
    void fractionalMaxResultsUsesJavaScriptSliceTruncation() {
        ToolRegistry registry = registryWith(
            fakeDeferredTool("AlphaTool", "alpha"),
            fakeDeferredTool("BetaTool", "beta"));
        ToolSearchTool tool = new ToolSearchTool(registry);
        ObjectNode input = query("tool");
        input.put("max_results", 1.9);

        ToolSearchTool.Output result = tool.call(input, ctx());

        assertEquals(1, referencedNames(result).size());
    }

    @Test
    void negativeMaxResults_matchesJavaScriptSliceInsteadOfThrowing() {
        ToolRegistry registry = registryWith(
            fakeDeferredTool("AlphaTool", "alpha"),
            fakeDeferredTool("BetaTool", "beta"),
            fakeDeferredTool("GammaTool", "gamma"));
        ToolSearchTool tool = new ToolSearchTool(registry);
        ObjectNode input = query("tool");
        input.put("max_results", -1);

        ToolSearchTool.Output result = tool.call(input, ctx());


        assertEquals(2, result.matches().size());
        assertEquals(result.matches(), referencedNames(result));
    }

    @Test
    void registryExecutionPreservesStructuredOutputAndToolReferences() {
        ToolRegistry registry = registryWith(fakeDeferredTool("WebFetch", "Fetches a URL"));
        ToolSearchTool tool = new ToolSearchTool(registry);
        registry.register(tool);

        ToolSearchTool.Output expected = tool.call(query("select:WebFetch"), ctx());
        var result = registry.execute("ToolSearch", query("select:WebFetch"), ctx());

        assertFalse(result.isError());
        assertEquals(expected.matches(),
            ((Map<?, ?>) result.toolUseResult()).get("matches"));
        assertEquals("select:WebFetch",
            ((Map<?, ?>) result.toolUseResult()).get("query"));
        assertEquals(1,
            ((Map<?, ?>) result.toolUseResult()).get("total_deferred_tools"));
        assertEquals(List.of("WebFetch"), referencedNames(
            new ToolSearchTool.Output(
                List.of("WebFetch"), "select:WebFetch", 1, List.of(),
                new RawBlocksOutput(result.content()))));
    }
}
