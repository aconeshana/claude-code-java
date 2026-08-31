package com.claudecode.tools.web;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.core.message.WebSearchToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ToolExecutionContext.of(new AbortController(), "test-session");
    }

    @Test
    void nameIsWebSearch() {
        assertEquals("WebSearch", new WebSearchTool().name());
    }

    @Test
    void noClientFixtureUsesTheFirstPartyEnablementDefault() {
        assertTrue(new WebSearchTool().isEnabled());
    }

    @Test
    void callWithNoProviderReturnsNotConfigured() {
        WebSearchTool tool = new WebSearchTool();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("query", "test query");

        String result = tool.call(input, context);
        assertTrue(Strings.CS.contains(result, "Web search not configured"));
    }

    @Test
    void callWithEmptyQueryReturnsError() {
        WebSearchTool tool = new WebSearchTool();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("query", "");

        String result = tool.call(input, context);
        assertEquals("Error: Missing query", result);
    }

    @Test
    void callWithMissingQueryReturnsError() {
        WebSearchTool tool = new WebSearchTool();
        ObjectNode input = MAPPER.createObjectNode();

        String result = tool.call(input, context);
        assertEquals("Error: Missing query", result);
    }

    @Test
    void callWithNullInputReturnsTheSameMissingQueryError() {
        assertEquals("Error: Missing query", new WebSearchTool().call(null, context));
    }

    @Test
    void isReadOnly() {
        assertTrue(new WebSearchTool().isReadOnly());
    }

    @Test
    void isConcurrencySafe() {
        assertTrue(new WebSearchTool().isConcurrencySafe());
    }

    @Test
    void schemaHasRequiredFields() {
        WebSearchTool tool = new WebSearchTool();
        var schema = tool.inputSchema();
        assertTrue(schema.has("properties"));
        assertTrue(schema.get("properties").has("query"));
    }

    @Test
    void checkPermissionsCarriesTheTsLocalSettingsSuggestion() {
        PermissionDecision decision = new WebSearchTool().checkPermissions(
            MAPPER.createObjectNode().put("query", "latest"),
            ToolPermissionContext.of(Path.of(".")));
        PermissionDecision.Ask ask = (PermissionDecision.Ask) decision;
        assertEquals("WebSearchTool requires permission.", ask.message());
        PermissionUpdate.AddRules update =
            (PermissionUpdate.AddRules) ask.suggestions().getFirst();
        assertEquals("WebSearch", update.rules().getFirst().toolName());
        assertEquals(PermissionUpdate.Destination.LOCAL_SETTINGS, update.destination());
    }

    // --- Fix #10: query minLength(2) enforced at runtime ---

    @Test
    void callWithSingleCharQueryReturnsError() {
        WebSearchTool tool = new WebSearchTool();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("query", "a");

        String result = tool.call(input, context);
        assertEquals("Error: Query must be at least 2 characters", result);
    }



    @Test
    void formatAnthropicSearchResultsIncludesQueryHeaderAndLinksJson() {
        WebSearchTool tool = new WebSearchTool();
        Map<Integer, StringBuilder> textBlocks = new TreeMap<>();
        Map<Integer, WebSearchToolResultBlock> searchResults = new TreeMap<>();
        List<WebSearchToolResultBlock.Hit> hits = List.of(
            new WebSearchToolResultBlock.Hit("Example Title", "https://example.com"));
        searchResults.put(0, new WebSearchToolResultBlock("tu_1", hits, null));

        String out = tool.formatAnthropicSearchResults("my query", textBlocks, searchResults);
        assertTrue(Strings.CS.contains(out, "Web search results for query: \"my query\""));
        assertTrue(Strings.CS.contains(out, "Links: [{\"title\":\"Example Title\",\"url\":\"https://example.com\"}]"));
    }

    @Test
    void formatAnthropicSearchResultsReportsErrorBlock() {
        WebSearchTool tool = new WebSearchTool();
        Map<Integer, StringBuilder> textBlocks = new TreeMap<>();
        Map<Integer, WebSearchToolResultBlock> searchResults = new TreeMap<>();
        searchResults.put(0, new WebSearchToolResultBlock("tu_1", null, "rate_limited"));

        String out = tool.formatAnthropicSearchResults("q", textBlocks, searchResults);
        assertTrue(Strings.CS.contains(out, "Web search error: rate_limited"));
    }

    @Test
    void formatAnthropicSearchResultsMatchesTsForEmptyAndMissingLinks() {
        WebSearchTool tool = new WebSearchTool();
        Map<Integer, StringBuilder> textBlocks = new TreeMap<>();
        Map<Integer, WebSearchToolResultBlock> searchResults = new TreeMap<>();
        searchResults.put(0, new WebSearchToolResultBlock("tu_empty", List.of(), null));

        String empty = tool.formatAnthropicSearchResults("q", textBlocks, searchResults);
        assertTrue(Strings.CS.contains(empty, "No links found."), empty);
        assertFalse(Strings.CS.contains(empty, "No results found."), empty);

        String none = tool.formatAnthropicSearchResults("q", textBlocks, new TreeMap<>());
        assertFalse(Strings.CS.contains(none, "No results found."), none);
    }
}
